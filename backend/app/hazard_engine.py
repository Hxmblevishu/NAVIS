"""Hazard decision engine for NAVIS.
Transforms imperfect perception and sensor fusion data into concise,
prioritized, and deduplicated spoken alerts.
"""
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

from backend.app.config import get_config
from backend.app.logging_config import record_event_log
from backend.app.schemas import FusionResult, HazardEvent, SensorStatus
from backend.app.settings import get_settings_manager

logger = logging.getLogger("navis.hazard_engine")

CRITICAL_DISTANCE_M = 0.8
DEFAULT_HIGH_PRIORITY_CLASSES = {
    "car", "truck", "bus", "motorcycle", "bicycle", "stairs",
    "pothole", "curb", "construction", "hole", "dropoff", "pole", "wire", "obstacle"
}
HIGH_PRIORITY_CLASSES = DEFAULT_HIGH_PRIORITY_CLASSES


@dataclass
class TrackedObstacle:
    """Internal state tracking an obstacle across frames for persistence and cooldown."""
    label: str
    position: str
    first_seen_time: float
    last_seen_time: float
    consecutive_frames: int = 1
    last_announced_time: float = 0.0


class HazardEngine:
    """Evaluates perception results against temporal persistence, cooldown, and urgency policies."""

    def __init__(self):
        self._tracked: Dict[Tuple[str, str], TrackedObstacle] = {}
        self._last_camera_status: bool = True
        self._last_sensor_status: bool = True

    def reset_state(self) -> None:
        """Clear all temporal tracking and cooldown state."""
        self._tracked.clear()
        self._last_camera_status = True
        self._last_sensor_status = True

    def _format_distance_str(self, distance_m: float) -> str:
        """Format distance into natural spoken text."""
        if distance_m < 0.95:
            return "less than one metre away"
        elif distance_m < 1.4:
            return "approximately one metre away"
        elif distance_m < 2.4:
            return f"approximately {round(distance_m, 1)} metres away"
        else:
            return f"about {round(distance_m)} metres ahead"

    def _get_high_priority_classes(self) -> set:
        """Resolve combined default and custom high-priority hazard classes."""
        cfg = get_config()
        classes = set(DEFAULT_HIGH_PRIORITY_CLASSES)
        if cfg.custom_high_priority_classes:
            for c in cfg.custom_high_priority_classes.split(","):
                item = c.strip().lower()
                if item:
                    classes.add(item)
        return classes

    def evaluate(
        self,
        fusion_results: List[FusionResult],
        camera_available: bool = True,
        sensor_status: Optional[SensorStatus] = None,
    ) -> List[HazardEvent]:
        """Evaluate fusion results and produce prioritized hazard events."""
        now_ts = time.time()
        now_dt = datetime.now(timezone.utc)
        settings = get_settings_manager().get_settings()
        cfg = get_config()
        high_priority_classes = self._get_high_priority_classes()
        events: List[HazardEvent] = []

        # 1. System state change checks
        if not camera_available and self._last_camera_status:
            self._last_camera_status = False
            evt = HazardEvent(
                event_id=f"evt_{uuid.uuid4().hex[:8]}",
                priority="high",
                message="Camera unavailable. Obstacle detection is currently degraded.",
                source="camera",
                evidence={"camera_available": False},
                decision_reason="Camera stream disconnected or unavailable",
            )
            events.append(evt)
            record_event_log(
                event_id=evt.event_id,
                component="camera",
                message=evt.message,
                priority=evt.priority,
                hazard_decision=evt.decision_reason,
            )
        elif camera_available and not self._last_camera_status:
            self._last_camera_status = True

        if not camera_available:
            return events

        # 2. Track current active keys
        active_keys = set()

        for item in fusion_results:
            key = (item.object_label, item.image_position)
            active_keys.add(key)

            # Update or create tracked state
            if key in self._tracked:
                track = self._tracked[key]
                track.last_seen_time = now_ts
                track.consecutive_frames += 1
            else:
                track = TrackedObstacle(
                    label=item.object_label,
                    position=item.image_position,
                    first_seen_time=now_ts,
                    last_seen_time=now_ts,
                    consecutive_frames=1,
                    last_announced_time=0.0,
                )
                self._tracked[key] = track

            # Determine urgency and priority
            is_urgent_close = item.distance_m is not None and item.distance_m <= settings.distance_warn_threshold_m
            is_critical_close = item.distance_m is not None and item.distance_m <= CRITICAL_DISTANCE_M
            is_urgent_class = item.object_label.lower() in high_priority_classes

            # Persistence rule: urgent/close hazards bypass frame persistence; routine narration requires consecutive frames
            # Central high-priority hazards are time-sensitive; only routine
            # observations wait for temporal confirmation.
            urgent_center_class = is_urgent_class and item.image_position == "center"
            required_frames = 1 if (is_urgent_close or is_critical_close or urgent_center_class) else cfg.persistence_required_frames
            if track.consecutive_frames < required_frames:
                record_event_log(
                    event_id=f"evt_{uuid.uuid4().hex[:8]}",
                    component="hazard_engine",
                    message=f"Pending persistence: {item.object_label} at {item.image_position}",
                    hazard_decision=f"Suppressed: frame count {track.consecutive_frames} < {required_frames}",
                )
                continue

            # Cooldown rule: suppress repeated announcements within alert_cooldown_s
            cooldown_s = settings.alert_cooldown_s
            # If obstacle suddenly moves significantly closer into critical range, allow cooldown override
            time_since_last = now_ts - track.last_announced_time
            if track.last_announced_time > 0 and time_since_last < cooldown_s and not is_critical_close:
                record_event_log(
                    event_id=f"evt_{uuid.uuid4().hex[:8]}",
                    component="hazard_engine",
                    message=f"Suppressed duplicate: {item.object_label} at {item.image_position}",
                    hazard_decision=f"Suppressed by cooldown ({time_since_last:.1f}s < {cooldown_s:.1f}s)",
                )
                continue

            # Assign Priority
            if is_critical_close:
                priority = "critical"
            elif is_urgent_close or (is_urgent_class and item.image_position == "center"):
                priority = "high"
            elif item.image_position == "center":
                priority = "normal"
            else:
                priority = "low"

            # Construct concise spoken message
            pos_label = "ahead" if item.image_position == "center" else f"on {item.image_position}"
            label_spoken = item.object_label

            if item.distance_status == "associated" and item.distance_m is not None:
                dist_str = self._format_distance_str(item.distance_m)
                message = f"{label_spoken.capitalize()} {pos_label}, {dist_str}."
            elif item.distance_status == "invalid_sensor" or item.distance_status == "stale_sensor":
                if item.image_position == "center":
                    message = f"Possible obstacle ahead. Distance unavailable."
                else:
                    message = f"{label_spoken.capitalize()} {pos_label}."
            else:
                message = f"Possible {label_spoken} {pos_label}."

            # Mark as announced
            track.last_announced_time = now_ts

            evt = HazardEvent(
                event_id=f"evt_{uuid.uuid4().hex[:8]}",
                priority=priority,
                message=message,
                source="sensor_fusion",
                evidence={
                    "object_label": item.object_label,
                    "confidence": item.object_confidence,
                    "position": item.image_position,
                    "distance_m": item.distance_m,
                    "distance_status": item.distance_status,
                    "association_confidence": item.association_confidence,
                },
                decision_reason=(
                    f"Emitted: priority={priority}, consecutive_frames={track.consecutive_frames}, "
                    f"distance={item.distance_m}m, reason={item.association_reason}"
                ),
            )
            events.append(evt)
            record_event_log(
                event_id=evt.event_id,
                component="hazard_engine",
                message=evt.message,
                priority=evt.priority,
                hazard_decision=evt.decision_reason,
            )

        # 3. Clean up stale tracks not seen in last 5 seconds
        expired_keys = [k for k, v in self._tracked.items() if now_ts - v.last_seen_time > 5.0]
        for k in expired_keys:
            del self._tracked[k]

        # Sort events by priority: critical -> high -> normal -> low
        priority_order = {"critical": 0, "high": 1, "normal": 2, "low": 3}
        events.sort(key=lambda e: priority_order.get(e.priority, 99))

        return events


# Singleton hazard engine
_hazard_engine_instance: Optional[HazardEngine] = None


def get_hazard_engine() -> HazardEngine:
    """Retrieve singleton HazardEngine."""
    global _hazard_engine_instance
    if _hazard_engine_instance is None:
        _hazard_engine_instance = HazardEngine()
    return _hazard_engine_instance
