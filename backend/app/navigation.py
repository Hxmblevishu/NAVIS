"""Google Maps Routes API adapter and conservative pedestrian progress tracker."""

import logging
import math
import re
import uuid
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

import httpx

from backend.app.config import get_config
from backend.app.schemas import (
    LocationCoordinate,
    NavigationProgressRequest,
    NavigationProgressResponse,
    NavigationRouteRequest,
    NavigationRouteResponse,
    RouteManeuver,
)

logger = logging.getLogger("navis.navigation")
ROUTES_API_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
ROUTES_FIELD_MASK = ",".join(
    (
        "routes.distanceMeters",
        "routes.duration",
        "routes.polyline.encodedPolyline",
        "routes.legs.steps.distanceMeters",
        "routes.legs.steps.duration",
        "routes.legs.steps.navigationInstruction",
    )
)

_AMBIGUOUS_KEYWORDS = {
    "library": "Which library do you mean? Please include its name or area.",
    "coffee shop": "Which coffee shop do you mean? Please include its name or area.",
    "bank": "Which bank or branch do you mean? Please include its name or area.",
    "hospital": "Which hospital do you mean? Please include its name or area.",
    "restaurant": "Which restaurant do you mean? Please include its name or area.",
    "market": "Which market do you mean? Please include its name or area.",
}

_MANEUVER_NAMES = {
    "DEPART": "depart",
    "ARRIVE": "arrive",
    "STRAIGHT": "straight",
    "TURN_LEFT": "turn-left",
    "TURN_RIGHT": "turn-right",
    "TURN_SLIGHT_LEFT": "turn-slight-left",
    "TURN_SLIGHT_RIGHT": "turn-slight-right",
    "TURN_SHARP_LEFT": "turn-sharp-left",
    "TURN_SHARP_RIGHT": "turn-sharp-right",
    "U_TURN_LEFT": "u-turn-left",
    "U_TURN_RIGHT": "u-turn-right",
}


def haversine_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Return great-circle distance between two coordinates in metres."""
    earth_radius_m = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    return earth_radius_m * 2.0 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def decode_polyline(encoded: str) -> List[Tuple[float, float]]:
    """Decode a Google encoded polyline into (latitude, longitude) pairs."""
    points: List[Tuple[float, float]] = []
    index = lat = lng = 0
    while index < len(encoded):
        values = []
        for _ in range(2):
            result = shift = 0
            while True:
                if index >= len(encoded):
                    raise ValueError("Truncated encoded route polyline")
                byte = ord(encoded[index]) - 63
                index += 1
                result |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20:
                    break
                if shift > 30:
                    raise ValueError("Invalid encoded route polyline")
            values.append(~(result >> 1) if result & 1 else result >> 1)
        lat += values[0]
        lng += values[1]
        points.append((lat / 1e5, lng / 1e5))
    return points


@dataclass
class RouteSession:
    route_id: str
    destination: str
    origin: LocationCoordinate
    maneuvers: List[RouteManeuver]
    step_end_distances: List[float]
    path: List[Tuple[float, float]]
    total_distance_m: float
    duration_s: int


class NavigationAdapter:
    """Calculate real pedestrian routes when configured; never invent route data."""

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = (api_key if api_key is not None else get_config().google_maps_api_key).strip()
        self._active_routes: Dict[str, RouteSession] = {}

    def _unavailable(self, destination: str, message: str) -> NavigationRouteResponse:
        return NavigationRouteResponse(
            route_id="",
            status="unavailable",
            message=message,
            destination=destination,
            distance_remaining_m=None,
            eta_s=None,
            next_maneuver=None,
            maneuvers=[],
        )

    def calculate_route(self, req: NavigationRouteRequest) -> NavigationRouteResponse:
        """Request a real WALK route; return unavailable when routing is not configured."""
        destination = req.destination.strip()
        keyword = destination.lower()
        clarification = _AMBIGUOUS_KEYWORDS.get(keyword.removeprefix("the "))
        if clarification:
            return NavigationRouteResponse(
                route_id="",
                status="ambiguous_destination",
                message=clarification,
                destination=destination,
                distance_remaining_m=None,
                eta_s=None,
                next_maneuver=None,
                maneuvers=[],
            )

        if not self.api_key:
            return self._unavailable(
                destination,
                "Pedestrian routing is not configured. No route has been generated.",
            )

        cfg = get_config()
        payload = {
            "origin": {
                "location": {
                    "latLng": {
                        "latitude": req.origin.latitude,
                        "longitude": req.origin.longitude,
                    }
                }
            },
            "destination": {"address": destination},
            "travelMode": "WALK",
            "computeAlternativeRoutes": False,
            "languageCode": cfg.navis_speech_locale,
            "units": "METRIC",
            "regionCode": cfg.google_maps_region_code,
        }

        try:
            response = httpx.post(
                ROUTES_API_URL,
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "X-Goog-Api-Key": self.api_key,
                    "X-Goog-FieldMask": ROUTES_FIELD_MASK,
                },
                timeout=10.0,
            )
            response.raise_for_status()
            body = response.json()
            routes = body.get("routes") or []
            if not routes:
                return NavigationRouteResponse(
                    route_id="",
                    status="failed",
                    message="The route service could not find a pedestrian route. Try a more specific destination.",
                    destination=destination,
                    distance_remaining_m=None,
                    eta_s=None,
                    next_maneuver=None,
                    maneuvers=[],
                )
            return self._build_route(req.origin, destination, routes[0])
        except (httpx.TimeoutException, httpx.NetworkError):
            logger.warning("Pedestrian route service is unreachable")
            return self._unavailable(destination, "The route service is unreachable. No route has been generated.")
        except (httpx.HTTPStatusError, ValueError, KeyError, TypeError) as exc:
            logger.warning("Pedestrian route request failed (%s)", type(exc).__name__)
            return self._unavailable(destination, "A pedestrian route could not be retrieved. Please try again.")
        except Exception as exc:
            logger.exception("Unexpected pedestrian routing error (%s)", type(exc).__name__)
            return self._unavailable(destination, "A pedestrian route could not be retrieved. Please try again.")

    def _build_route(
        self,
        origin: LocationCoordinate,
        destination: str,
        route: dict,
    ) -> NavigationRouteResponse:
        total_distance_m = float(route["distanceMeters"])
        duration_s = max(0, int(float(str(route["duration"]).removesuffix("s"))))
        polyline = route.get("polyline", {}).get("encodedPolyline", "")
        path = decode_polyline(polyline)
        if len(path) < 2:
            raise ValueError("Route response did not contain a usable path")

        maneuvers: List[RouteManeuver] = []
        step_end_distances: List[float] = []
        cumulative_distance = 0.0
        for leg in route.get("legs", []):
            for step in leg.get("steps", []):
                distance_m = max(0.0, float(step.get("distanceMeters", 0.0)))
                instruction = step.get("navigationInstruction", {}).get("instructions")
                maneuver = step.get("navigationInstruction", {}).get("maneuver", "STRAIGHT")
                if instruction:
                    maneuvers.append(
                        RouteManeuver(
                            instruction=str(instruction),
                            distance_m=distance_m,
                            maneuver_type=_MANEUVER_NAMES.get(str(maneuver), str(maneuver).lower().replace("_", "-")),
                        )
                    )
                    cumulative_distance += distance_m
                    step_end_distances.append(cumulative_distance)

        if not maneuvers:
            maneuvers = [
                RouteManeuver(
                    instruction=f"Follow the pedestrian route to {destination}.",
                    distance_m=total_distance_m,
                    maneuver_type="depart",
                )
            ]
            step_end_distances = [total_distance_m]

        route_id = f"route_{uuid.uuid4().hex[:8]}"
        session = RouteSession(
            route_id=route_id,
            destination=destination,
            origin=origin,
            maneuvers=maneuvers,
            step_end_distances=step_end_distances,
            path=path,
            total_distance_m=total_distance_m,
            duration_s=duration_s,
        )
        self._active_routes[route_id] = session
        if len(self._active_routes) > 50:
            self._active_routes.pop(next(iter(self._active_routes)))

        eta_minutes = max(1, math.ceil(duration_s / 60))
        first_maneuver = maneuvers[0].instruction
        return NavigationRouteResponse(
            route_id=route_id,
            status="active",
            message=(
                f"Pedestrian route calculated to {destination}: {round(total_distance_m)} metres, "
                f"about {eta_minutes} minutes. Follow your mobility aid and verify crossings."
            ),
            destination=destination,
            distance_remaining_m=total_distance_m,
            eta_s=duration_s,
            next_maneuver=first_maneuver,
            maneuvers=maneuvers,
            overview_polyline=polyline,
            attribution="Powered by Google, © 2026 Google",
        )

    @staticmethod
    def _nearest_route_progress(
        location: LocationCoordinate,
        path: Sequence[Tuple[float, float]],
    ) -> Tuple[float, float]:
        """Return cross-track distance and along-route distance at the nearest segment."""
        if len(path) < 2:
            return math.inf, 0.0

        cumulative = 0.0
        best_cross_track = math.inf
        best_along = 0.0
        for start, end in zip(path, path[1:]):
            segment_length = haversine_distance_m(start[0], start[1], end[0], end[1])
            mean_latitude = math.radians((start[0] + end[0] + location.latitude) / 3.0)
            scale_x = 6_371_000.0 * math.cos(mean_latitude) * math.pi / 180.0
            scale_y = 6_371_000.0 * math.pi / 180.0
            dx = (end[1] - start[1]) * scale_x
            dy = (end[0] - start[0]) * scale_y
            px = (location.longitude - start[1]) * scale_x
            py = (location.latitude - start[0]) * scale_y
            length_squared = dx * dx + dy * dy
            fraction = 0.0 if length_squared == 0.0 else max(0.0, min(1.0, (px * dx + py * dy) / length_squared))
            cross_track = math.hypot(px - fraction * dx, py - fraction * dy)
            if cross_track < best_cross_track:
                best_cross_track = cross_track
                best_along = cumulative + segment_length * fraction
            cumulative += segment_length
        return best_cross_track, best_along

    def track_progress(self, req: NavigationProgressRequest) -> NavigationProgressResponse:
        """Match phone GPS to the calculated path before giving route progress."""
        session = self._active_routes.get(req.route_id)
        if session is None:
            message = "Route session expired or not found. Request a new pedestrian route."
            return NavigationProgressResponse(
                route_id=req.route_id,
                status="off_route",
                next_maneuver=message,
                distance_remaining_m=0.0,
                eta_s=0,
                message=message,
            )

        if req.location.accuracy_m is not None and req.location.accuracy_m > 50.0:
            message = "Phone location is too imprecise for route progress. Wait for a clearer GPS fix."
            return NavigationProgressResponse(
                route_id=req.route_id,
                status="stale_location",
                next_maneuver=message,
                distance_remaining_m=session.total_distance_m,
                eta_s=session.duration_s,
                message=message,
            )

        cross_track_m, distance_travelled_m = self._nearest_route_progress(req.location, session.path)
        remaining_m = max(0.0, session.total_distance_m - distance_travelled_m)
        eta_s = max(0, int(session.duration_s * remaining_m / max(session.total_distance_m, 1.0)))

        if cross_track_m > 35.0:
            message = "You appear to be away from the planned route. Stop and request a new route."
            return NavigationProgressResponse(
                route_id=req.route_id,
                status="off_route",
                next_maneuver=message,
                distance_remaining_m=round(remaining_m, 1),
                eta_s=eta_s,
                message=message,
            )

        if remaining_m <= 10.0:
            message = f"You appear to have arrived at {session.destination}."
            return NavigationProgressResponse(
                route_id=req.route_id,
                status="completed",
                next_maneuver=message,
                distance_remaining_m=0.0,
                eta_s=0,
                message=message,
            )

        step_index = next(
            (index for index, end_distance in enumerate(session.step_end_distances) if distance_travelled_m < end_distance),
            len(session.maneuvers) - 1,
        )
        instruction = session.maneuvers[step_index].instruction
        message = f"{instruction}. About {round(remaining_m)} metres remain."
        return NavigationProgressResponse(
            route_id=req.route_id,
            status="in_progress",
            next_maneuver=instruction,
            distance_remaining_m=round(remaining_m, 1),
            eta_s=eta_s,
            message=message,
        )


_nav_adapter_instance: Optional[NavigationAdapter] = None


def get_navigation_adapter() -> NavigationAdapter:
    """Return the singleton navigation adapter."""
    global _nav_adapter_instance
    if _nav_adapter_instance is None:
        _nav_adapter_instance = NavigationAdapter()
    return _nav_adapter_instance
