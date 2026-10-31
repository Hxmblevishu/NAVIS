"""Unit tests for distance sensor adapter, validation, and telemetry ingestion."""
import time
from datetime import datetime, timezone, timedelta
import pytest
from backend.app.distance_sensor import (
    HTTPTelemetrySensorAdapter,
    MockDistanceSensorAdapter,
    DisabledSensorAdapter,
)
from backend.app.schemas import SensorTelemetryRequest


def test_mock_sensor_valid():
    """Verify mock sensor returns valid reading in operating range."""
    sensor = MockDistanceSensorAdapter(initial_distance=1.5)
    sensor.start()
    reading = sensor.get_latest_reading()

    assert reading.valid is True
    assert reading.distance_m == 1.5
    assert reading.status == "valid"
    assert reading.measurement_direction == "center"


def test_mock_sensor_out_of_range():
    """Verify mock sensor catches distances outside VL53L0X operating range (0.03m to 2.0m)."""
    sensor = MockDistanceSensorAdapter(initial_distance=0.01)  # Below 0.03m min range (VL53L0X minimum)
    reading = sensor.get_latest_reading()
    assert reading.valid is False
    assert reading.distance_m is None
    assert reading.status == "out_of_range"

    sensor.set_simulated_distance(2.5)  # Exceeds 2.0m max range (VL53L0X maximum)
    reading = sensor.get_latest_reading()
    assert reading.valid is False
    assert reading.status == "out_of_range"


def test_mock_sensor_error_injection():
    """Verify explicit error injection."""
    sensor = MockDistanceSensorAdapter(initial_distance=1.5)
    sensor.set_simulated_distance(None, valid=False)
    reading = sensor.get_latest_reading()
    assert reading.valid is False
    assert reading.distance_m is None
    assert reading.status == "error"


def test_http_telemetry_sensor_ingestion():
    """Verify HTTP telemetry ingestion and validation."""
    adapter = HTTPTelemetrySensorAdapter(sensor_model="VL53L0X_test")
    adapter.start()

    # Initial state before reading
    initial = adapter.get_latest_reading()
    assert initial.status == "disconnected"
    assert initial.valid is False

    # Ingest valid telemetry
    req = SensorTelemetryRequest(
        sensor_id="esp32_cam_sensor",
        sensor_model="VL53L0X_test",
        distance_m=1.35,
        valid=True,
        quality=0.92,
    )
    result = adapter.record_reading(req)
    assert result.valid is True
    assert result.distance_m == 1.35
    assert result.status == "valid"
    assert adapter.is_connected() is True

    # Retrieve latest reading
    latest = adapter.get_latest_reading()
    assert latest.distance_m == 1.35
    assert latest.valid is True


def test_http_telemetry_stale_reading():
    """Verify telemetry becomes stale when no update arrives within max age."""
    adapter = HTTPTelemetrySensorAdapter()
    adapter.start()

    # Ingest reading stamped 5 seconds ago
    old_time = datetime.now(timezone.utc) - timedelta(seconds=5.0)
    req = SensorTelemetryRequest(
        sensor_id="esp32_sensor",
        distance_m=1.20,
        valid=True,
        timestamp=old_time,
    )
    adapter.record_reading(req)

    # Retrieval should mark it as stale
    reading = adapter.get_latest_reading()
    assert reading.valid is False
    assert reading.status == "stale"
    assert reading.distance_m is None


def test_disabled_sensor_adapter():
    """Verify disabled adapter returns safe disconnected defaults."""
    sensor = DisabledSensorAdapter()
    assert sensor.is_connected() is False
    reading = sensor.get_latest_reading()
    assert reading.valid is False
    assert reading.status == "disconnected"


def test_sensor_model_alias_normalization():
    """Verify that VL530X, vl530x, and GY-530 are normalized to VL53L0X."""
    from backend.app.config import Settings

    # Settings normalization
    cfg1 = Settings(distance_sensor_model="vl530x")
    assert cfg1.distance_sensor_model == "VL53L0X"

    cfg2 = Settings(distance_sensor_model="VL530X")
    assert cfg2.distance_sensor_model == "VL53L0X"

    cfg3 = Settings(distance_sensor_model="GY-530")
    assert cfg3.distance_sensor_model == "VL53L0X"

    # Adapter normalization
    adapter = HTTPTelemetrySensorAdapter(sensor_model="vl530x")
    assert adapter.sensor_model == "VL53L0X"

    # Ingestion normalization
    req = SensorTelemetryRequest(sensor_id="esp32", sensor_model="vl530x", distance_m=1.1, valid=True)
    res = adapter.record_reading(req)
    assert res.sensor_model == "VL53L0X"
