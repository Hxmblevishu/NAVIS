"""Sensor fusion module for NAVIS.
Associates camera object detections with physical distance sensor measurements
only when sufficient geometric, spatial, and temporal evidence exists.
"""
import logging
from datetime import datetime, timezone
from typing import List, Optional, Tuple

from backend.app.config import get_config
from backend.app.schemas import Detection, DistanceReading, FusionResult

logger = logging.getLogger("navis.sensor_fusion")


class SensorFusionEngine:
    """Performs evidence-based association between 2D vision detections and 1D ranging data."""

    def __init__(self):
        pass

    def fuse(
        self,
        detections: List[Detection],
        sensor_reading: DistanceReading,
        frame_timestamp: Optional[datetime] = None,
    ) -> List[FusionResult]:
        """Associate detections with the latest distance reading.

        Rules:
        1. Never infer distance from YOLO confidence or bounding box area.
        2. Reject stale or invalid sensor readings.
        3. Only associate forward-facing sensor measurements with objects in the 'center' image region.
        4. When multiple objects are in 'center', resolve cautiously or flag as uncertain.
        5. Never assign a single distance reading to all detected objects.
        """
        cfg = get_config()
        now = frame_timestamp or datetime.now(timezone.utc)
        results: List[FusionResult] = []

        if not detections:
            return results

        # Check sensor reading validity and freshness
        sensor_valid = sensor_reading.valid and (sensor_reading.distance_m is not None)
        sensor_age_ms = abs((now - sensor_reading.timestamp).total_seconds() * 1000.0)
        sensor_fresh = sensor_age_ms <= cfg.sensor_max_age_ms

        # Filter candidate detections in the forward/center zone
        center_candidates = [d for d in detections if d.position == "center"]

        # Track if the single forward distance measurement has already been attributed
        distance_assigned = False

        for det in detections:
            # Case 1: Detection is in the peripheral region (left or right)
            if det.position in ("left", "right", "unknown"):
                results.append(
                    FusionResult(
                        object_label=det.label,
                        object_confidence=det.confidence,
                        image_position=det.position,
                        distance_m=None,
                        distance_status="unassociated",
                        association_confidence=None,
                        association_reason=(
                            f"Object in '{det.position}' region; distance sensor is aligned "
                            f"forward ('{sensor_reading.measurement_direction}'). Ranging not associated."
                        ),
                    )
                )
                continue

            # Case 2: Detection is in center, but sensor is invalid or disconnected
            if not sensor_valid:
                reason = f"Distance sensor reported status '{sensor_reading.status}': {sensor_reading.error or 'Invalid reading'}"
                results.append(
                    FusionResult(
                        object_label=det.label,
                        object_confidence=det.confidence,
                        image_position="center",
                        distance_m=None,
                        distance_status="invalid_sensor",
                        association_confidence=None,
                        association_reason=reason,
                    )
                )
                continue

            # Case 3: Detection is in center, but sensor reading is stale
            if not sensor_fresh:
                reason = f"Distance reading is stale ({sensor_age_ms:.0f}ms > threshold {cfg.sensor_max_age_ms}ms)"
                results.append(
                    FusionResult(
                        object_label=det.label,
                        object_confidence=det.confidence,
                        image_position="center",
                        distance_m=None,
                        distance_status="stale_sensor",
                        association_confidence=None,
                        association_reason=reason,
                    )
                )
                continue

            # Case 4: Detection is in center, and sensor reading is valid & fresh
            # If multiple center objects exist, avoid blindly attributing the reading to all of them
            if len(center_candidates) == 1:
                # Single unambiguous center obstacle
                distance_assigned = True
                results.append(
                    FusionResult(
                        object_label=det.label,
                        object_confidence=det.confidence,
                        image_position="center",
                        distance_m=sensor_reading.distance_m,
                        distance_status="associated",
                        association_confidence=0.90,
                        association_reason=(
                            f"Direct forward alignment: single center target matches forward ranging "
                            f"measurement of {sensor_reading.distance_m:.2f}m."
                        ),
                    )
                )
            else:
                # Multiple center candidates: attribute to the primary/dominant target (largest area)
                # and mark others as unassociated/uncertain
                dominant = max(center_candidates, key=lambda d: d.area_ratio if d.area_ratio is not None else d.confidence)
                if det == dominant and not distance_assigned:
                    distance_assigned = True
                    results.append(
                        FusionResult(
                            object_label=det.label,
                            object_confidence=det.confidence,
                            image_position="center",
                            distance_m=sensor_reading.distance_m,
                            distance_status="associated",
                            association_confidence=0.70,
                            association_reason=(
                                f"Multiple center objects present; associated reading {sensor_reading.distance_m:.2f}m "
                                f"with primary dominant obstacle '{det.label}'."
                            ),
                        )
                    )
                else:
                    results.append(
                        FusionResult(
                            object_label=det.label,
                            object_confidence=det.confidence,
                            image_position="center",
                            distance_m=None,
                            distance_status="uncertain",
                            association_confidence=None,
                            association_reason=(
                                f"Multiple obstacles in center field; distance measurement was not uniquely "
                                f"attributable to secondary object '{det.label}'."
                            ),
                        )
                    )

        return results


# Global singleton
_fusion_engine_instance: Optional[SensorFusionEngine] = None


def get_fusion_engine() -> SensorFusionEngine:
    """Retrieve singleton SensorFusionEngine."""
    global _fusion_engine_instance
    if _fusion_engine_instance is None:
        _fusion_engine_instance = SensorFusionEngine()
    return _fusion_engine_instance
