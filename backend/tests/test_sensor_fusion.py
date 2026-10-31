"""Unit tests for sensor fusion and vision-to-distance association."""
from datetime import datetime, timezone, timedelta
import pytest
from backend.app.schemas import Detection, DistanceReading
from backend.app.sensor_fusion import SensorFusionEngine


@pytest.fixture
def fusion():
    return SensorFusionEngine()


def test_fusion_valid_aligned(fusion):
    """Verify single center detection associates with valid forward distance reading."""
    det = Detection(
        label="person",
        confidence=0.92,
        bbox=[200.0, 100.0, 440.0, 400.0],
        position="center",
        vertical_position="middle",
        area_ratio=0.25,
    )
    reading = DistanceReading(
        sensor_id="sensor_0",
        sensor_model="test_sensor",
        timestamp=datetime.now(timezone.utc),
        distance_m=1.35,
        valid=True,
        status="valid",
        measurement_direction="center",
    )

    results = fusion.fuse([det], reading)
    assert len(results) == 1
    res = results[0]
    assert res.object_label == "person"
    assert res.distance_status == "associated"
    assert res.distance_m == 1.35
    assert res.association_confidence is not None
    assert "Direct forward alignment" in res.association_reason


def test_fusion_peripheral_unassociated(fusion):
    """Verify peripheral (left/right) detection is NEVER associated with forward distance."""
    det_left = Detection(
        label="chair",
        confidence=0.85,
        bbox=[20.0, 150.0, 120.0, 350.0],
        position="left",
        vertical_position="middle",
        area_ratio=0.10,
    )
    reading = DistanceReading(
        sensor_id="sensor_0",
        sensor_model="test_sensor",
        timestamp=datetime.now(timezone.utc),
        distance_m=1.20,
        valid=True,
        status="valid",
        measurement_direction="center",
    )

    results = fusion.fuse([det_left], reading)
    assert len(results) == 1
    res = results[0]
    assert res.distance_status == "unassociated"
    assert res.distance_m is None
    assert "left" in res.association_reason


def test_fusion_invalid_sensor(fusion):
    """Verify center detection is not given distance if sensor is invalid."""
    det = Detection(
        label="obstacle",
        confidence=0.78,
        bbox=[250.0, 150.0, 390.0, 350.0],
        position="center",
        vertical_position="middle",
        area_ratio=0.15,
    )
    reading = DistanceReading(
        sensor_id="sensor_0",
        sensor_model="test_sensor",
        timestamp=datetime.now(timezone.utc),
        distance_m=None,
        valid=False,
        status="error",
        error="Hardware sensor timeout",
    )

    results = fusion.fuse([det], reading)
    assert len(results) == 1
    res = results[0]
    assert res.distance_status == "invalid_sensor"
    assert res.distance_m is None


def test_fusion_stale_reading(fusion):
    """Verify center detection is rejected if sensor reading is stale (> 1000ms)."""
    det = Detection(
        label="person",
        confidence=0.88,
        bbox=[250.0, 100.0, 390.0, 400.0],
        position="center",
        vertical_position="middle",
        area_ratio=0.18,
    )
    old_time = datetime.now(timezone.utc) - timedelta(milliseconds=2500)
    reading = DistanceReading(
        sensor_id="sensor_0",
        sensor_model="test_sensor",
        timestamp=old_time,
        distance_m=1.10,
        valid=True,
        status="valid",
    )

    results = fusion.fuse([det], reading)
    assert len(results) == 1
    assert results[0].distance_status == "stale_sensor"
    assert results[0].distance_m is None


def test_fusion_multiple_center_cautious(fusion):
    """Verify multiple center objects do not blindly all get the distance reading."""
    dominant = Detection(
        label="person",
        confidence=0.95,
        bbox=[200.0, 100.0, 440.0, 450.0],
        position="center",
        vertical_position="middle",
        area_ratio=0.30,  # Dominant
    )
    secondary = Detection(
        label="chair",
        confidence=0.75,
        bbox=[310.0, 200.0, 360.0, 280.0],
        position="center",
        vertical_position="middle",
        area_ratio=0.04,  # Secondary
    )
    reading = DistanceReading(
        sensor_id="sensor_0",
        sensor_model="test_sensor",
        timestamp=datetime.now(timezone.utc),
        distance_m=1.20,
        valid=True,
        status="valid",
    )

    results = fusion.fuse([dominant, secondary], reading)
    assert len(results) == 2
    # Dominant is associated
    assert results[0].object_label == "person"
    assert results[0].distance_status == "associated"
    assert results[0].distance_m == 1.20

    # Secondary is uncertain
    assert results[1].object_label == "chair"
    assert results[1].distance_status == "uncertain"
    assert results[1].distance_m is None
