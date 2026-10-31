"""Unit tests for hazard decision engine, deduplication, persistence, and priority."""
import time
import pytest
from backend.app.hazard_engine import HazardEngine
from backend.app.schemas import FusionResult


@pytest.fixture
def engine():
    eng = HazardEngine()
    eng.reset_state()
    return eng


def test_hazard_persistence_routine(engine):
    """Verify routine detection requires consecutive frames before announcement."""
    fusion_item = FusionResult(
        object_label="chair",
        object_confidence=0.85,
        image_position="center",
        distance_m=2.5,
        distance_status="associated",
        association_confidence=0.9,
        association_reason="forward aligned",
    )

    # Frame 1: Pending persistence -> should not emit yet
    events_1 = engine.evaluate([fusion_item], camera_available=True)
    assert len(events_1) == 0

    # Frame 2: Persistence threshold met -> should emit event
    events_2 = engine.evaluate([fusion_item], camera_available=True)
    assert len(events_2) == 1
    assert "Chair ahead" in events_2[0].message
    assert events_2[0].priority == "normal"


def test_hazard_urgent_immediate(engine):
    """Verify close hazard (< warn threshold) bypasses persistence."""
    close_obstacle = FusionResult(
        object_label="person",
        object_confidence=0.90,
        image_position="center",
        distance_m=0.75,  # Critical (< 0.8m)
        distance_status="associated",
        association_confidence=0.95,
        association_reason="immediate close obstacle",
    )

    # Frame 1: Should emit immediately with critical priority
    events = engine.evaluate([close_obstacle], camera_available=True)
    assert len(events) == 1
    assert events[0].priority == "critical"
    assert "less than one metre away" in events[0].message


def test_hazard_cooldown_deduplication(engine):
    """Verify repeated same-object detections within cooldown are suppressed."""
    item = FusionResult(
        object_label="person",
        object_confidence=0.90,
        image_position="center",
        distance_m=1.8,
        distance_status="associated",
        association_confidence=0.9,
        association_reason="aligned",
    )

    # Establish persistence
    engine.evaluate([item], camera_available=True)
    events_announced = engine.evaluate([item], camera_available=True)
    assert len(events_announced) == 1

    # Immediate follow-up frame within 4.0s cooldown -> suppressed
    events_suppressed = engine.evaluate([item], camera_available=True)
    assert len(events_suppressed) == 0


def test_hazard_camera_failure_reporting(engine):
    """Verify camera disconnection produces an explicit degraded event."""
    events = engine.evaluate([], camera_available=False)
    assert len(events) == 1
    assert events[0].source == "camera"
    assert "Camera unavailable" in events[0].message
    assert events[0].priority == "high"


def test_hazard_unassociated_message_formatting(engine):
    """Verify qualified language when distance is unassociated."""
    item_left = FusionResult(
        object_label="door",
        object_confidence=0.88,
        image_position="left",
        distance_m=None,
        distance_status="unassociated",
        association_reason="peripheral",
    )
    # Establish persistence
    engine.evaluate([item_left], camera_available=True)
    events = engine.evaluate([item_left], camera_available=True)
    assert len(events) == 1
    assert events[0].priority == "low"
    assert "Possible door on left" in events[0].message


def test_custom_high_priority_hazard_class(engine):
    """Verify built-in navigation hazard classes (e.g. pothole) are recognized as high priority."""
    pothole_item = FusionResult(
        object_label="pothole",
        object_confidence=0.88,
        image_position="center",
        distance_m=1.5,
        distance_status="associated",
        association_confidence=0.90,
        association_reason="forward aligned",
    )
    # Frame 1: Pothole is in DEFAULT_HIGH_PRIORITY_CLASSES -> high priority in center
    events = engine.evaluate([pothole_item], camera_available=True)
    assert len(events) == 1
    assert events[0].priority == "high"
    assert "Pothole ahead" in events[0].message
