"""Distance sensor acquisition module for NAVIS.
Provides a normalized interface supporting unconfirmed physical hardware
and mock/telemetry adapters with validity and freshness validation.
"""
import abc
import logging
import threading
import time
from datetime import datetime, timezone
from typing import Optional

from backend.app.config import get_config
from backend.app.schemas import DistanceReading, SensorStatus, SensorTelemetryRequest

logger = logging.getLogger("navis.distance_sensor")


def normalize_sensor_model(model: Optional[str]) -> str:
    """Normalize sensor model name, recognizing VL530X/GY-530 as ST VL53L0X."""
    if not model:
        return "VL53L0X"
    s = model.strip().upper()
    if s in {"VL530X", "VL53L0X", "VL53L0", "VL-530X", "VL-53L0X", "GY-530", "GY530"}:
        return "VL53L0X"
    return model.strip()


class DistanceSensorAdapter(abc.ABC):
    """Abstract base class for distance sensor adapters."""

    @abc.abstractmethod
    def start(self) -> None:
        """Start sensor communication or listening thread."""
        pass

    @abc.abstractmethod
    def stop(self) -> None:
        """Stop sensor communication."""
        pass

    @abc.abstractmethod
    def get_latest_reading(self) -> DistanceReading:
        """Return the most recent normalized distance reading."""
        pass

    @abc.abstractmethod
    def is_connected(self) -> bool:
        """Return whether sensor communication is active."""
        pass

    @abc.abstractmethod
    def get_status(self) -> SensorStatus:
        """Return current sensor status and diagnostic telemetry."""
        pass


class HTTPTelemetrySensorAdapter(DistanceSensorAdapter):
    """Adapter for distance telemetry pushed by ESP32 or serial bridge via HTTP."""

    def __init__(self, sensor_model: str = "VL53L0X"):
        self.sensor_model = normalize_sensor_model(sensor_model)
        self._latest_reading: Optional[DistanceReading] = None
        self._lock = threading.Lock()
        self._total_readings = 0
        self._invalid_readings = 0
        self._last_received_time: float = 0.0

    def start(self) -> None:
        logger.info(f"HTTP Telemetry sensor adapter ready for incoming readings (model: {self.sensor_model})")

    def stop(self) -> None:
        logger.info("HTTP Telemetry sensor adapter stopped")

    def record_reading(self, req: SensorTelemetryRequest) -> DistanceReading:
        """Ingest incoming telemetry payload from firmware push."""
        cfg = get_config()
        now = datetime.now(timezone.utc)
        dist = req.distance_m
        valid = req.valid

        status = "valid"
        error_msg = None

        if not valid or dist is None:
            status = "error"
            valid = False
            error_msg = req.status or "Sensor flagged reading as invalid"
        elif dist < cfg.sensor_min_range_m:
            status = "out_of_range"
            valid = False
            error_msg = f"Distance {dist:.2f}m is below min range {cfg.sensor_min_range_m:.2f}m"
        elif dist > cfg.sensor_max_range_m:
            status = "out_of_range"
            valid = False
            error_msg = f"Distance {dist:.2f}m exceeds max range {cfg.sensor_max_range_m:.2f}m"

        reading = DistanceReading(
            sensor_id=req.sensor_id,
            sensor_model=normalize_sensor_model(req.sensor_model or self.sensor_model),
            timestamp=req.timestamp or now,
            distance_m=dist if valid else None,
            valid=valid,
            status=status,
            quality=req.quality,
            measurement_direction="center",
            error=error_msg,
        )

        with self._lock:
            self._latest_reading = reading
            self._total_readings += 1
            if not valid:
                self._invalid_readings += 1
            self._last_received_time = time.time()

        return reading

    def get_latest_reading(self) -> DistanceReading:
        cfg = get_config()
        now = datetime.now(timezone.utc)

        with self._lock:
            if self._latest_reading is None:
                return DistanceReading(
                    sensor_id="esp32_sensor",
                    sensor_model=self.sensor_model,
                    timestamp=now,
                    distance_m=None,
                    valid=False,
                    status="disconnected",
                    error="No sensor telemetry received yet",
                )

            # Check freshness
            age_ms = (now - self._latest_reading.timestamp).total_seconds() * 1000.0
            if age_ms > cfg.sensor_max_age_ms:
                return DistanceReading(
                    sensor_id=self._latest_reading.sensor_id,
                    sensor_model=self._latest_reading.sensor_model,
                    timestamp=self._latest_reading.timestamp,
                    distance_m=None,
                    valid=False,
                    status="stale",
                    error=f"Sensor reading is stale ({age_ms:.0f}ms > {cfg.sensor_max_age_ms}ms)",
                )

            return self._latest_reading

    def is_connected(self) -> bool:
        cfg = get_config()
        with self._lock:
            if self._latest_reading is None:
                return False
            # Connected if reading received within 3x max age
            return (time.time() - self._last_received_time) * 1000.0 <= (cfg.sensor_max_age_ms * 3)

    def get_status(self) -> SensorStatus:
        cfg = get_config()
        with self._lock:
            connected = (
                self._latest_reading is not None
                and (time.time() - self._last_received_time) * 1000.0 <= cfg.sensor_max_age_ms * 3
            )
            return SensorStatus(
                connected=connected,
                mode="http_telemetry",
                model=self.sensor_model,
                last_reading=self._latest_reading,
                total_readings=self._total_readings,
                invalid_readings_count=self._invalid_readings,
            )


class MockDistanceSensorAdapter(DistanceSensorAdapter):
    """Mock distance sensor adapter for local testing and simulation."""

    def __init__(self, sensor_model: str = "VL53L0X", initial_distance: float = 1.2):
        self.sensor_model = normalize_sensor_model(sensor_model)
        self.simulated_distance = initial_distance
        self.is_active = True
        self.force_invalid = False
        self._total_readings = 0
        self._invalid_readings = 0

    def start(self) -> None:
        self.is_active = True
        logger.info(f"Mock distance sensor adapter started (simulated: {self.simulated_distance}m)")

    def stop(self) -> None:
        self.is_active = False

    def set_simulated_distance(self, distance_m: Optional[float], valid: bool = True) -> None:
        """Helper for unit tests to inject distance values."""
        self.simulated_distance = distance_m
        self.force_invalid = not valid

    def get_latest_reading(self) -> DistanceReading:
        now = datetime.now(timezone.utc)
        self._total_readings += 1

        if not self.is_active:
            return DistanceReading(
                sensor_id="mock_0",
                sensor_model=self.sensor_model,
                timestamp=now,
                distance_m=None,
                valid=False,
                status="disconnected",
                error="Mock sensor is stopped",
            )

        if self.force_invalid or self.simulated_distance is None:
            self._invalid_readings += 1
            return DistanceReading(
                sensor_id="mock_0",
                sensor_model=self.sensor_model,
                timestamp=now,
                distance_m=None,
                valid=False,
                status="error",
                error="Simulated sensor reading error",
            )

        cfg = get_config()
        if self.simulated_distance < cfg.sensor_min_range_m or self.simulated_distance > cfg.sensor_max_range_m:
            self._invalid_readings += 1
            return DistanceReading(
                sensor_id="mock_0",
                sensor_model=self.sensor_model,
                timestamp=now,
                distance_m=None,
                valid=False,
                status="out_of_range",
                error=f"Simulated distance {self.simulated_distance}m out of valid range",
            )

        return DistanceReading(
            sensor_id="mock_0",
            sensor_model=self.sensor_model,
            timestamp=now,
            distance_m=self.simulated_distance,
            valid=True,
            status="valid",
            quality=0.95,
            measurement_direction="center",
        )

    def is_connected(self) -> bool:
        return self.is_active

    def get_status(self) -> SensorStatus:
        return SensorStatus(
            connected=self.is_connected(),
            mode="mock",
            model=self.sensor_model,
            last_reading=self.get_latest_reading(),
            total_readings=self._total_readings,
            invalid_readings_count=self._invalid_readings,
        )


class DisabledSensorAdapter(DistanceSensorAdapter):
    """Adapter used when sensor is explicitly disabled or unconfigured."""

    def start(self) -> None:
        pass

    def stop(self) -> None:
        pass

    def get_latest_reading(self) -> DistanceReading:
        return DistanceReading(
            sensor_id="disabled",
            sensor_model="none",
            timestamp=datetime.now(timezone.utc),
            distance_m=None,
            valid=False,
            status="disconnected",
            error="Distance sensor is disabled in configuration",
        )

    def is_connected(self) -> bool:
        return False

    def get_status(self) -> SensorStatus:
        return SensorStatus(
            connected=False,
            mode="disabled",
            model="none",
            last_reading=None,
            total_readings=0,
            invalid_readings_count=0,
        )


# Global adapter instance
_sensor_adapter_instance: Optional[DistanceSensorAdapter] = None


def get_distance_sensor_adapter() -> DistanceSensorAdapter:
    """Retrieve singleton sensor adapter based on configuration."""
    global _sensor_adapter_instance
    if _sensor_adapter_instance is None:
        cfg = get_config()
        mode = cfg.distance_sensor_mode.lower()

        if mode == "mock":
            _sensor_adapter_instance = MockDistanceSensorAdapter(cfg.distance_sensor_model)
        elif mode == "disabled":
            _sensor_adapter_instance = DisabledSensorAdapter()
        else:
            # "auto" or "http_telemetry"
            _sensor_adapter_instance = HTTPTelemetrySensorAdapter(cfg.distance_sensor_model)

        _sensor_adapter_instance.start()
    return _sensor_adapter_instance


def set_distance_sensor_adapter(adapter: DistanceSensorAdapter) -> DistanceSensorAdapter:
    """Explicitly override sensor adapter (for testing)."""
    global _sensor_adapter_instance
    if _sensor_adapter_instance is not None:
        _sensor_adapter_instance.stop()
    _sensor_adapter_instance = adapter
    _sensor_adapter_instance.start()
    return _sensor_adapter_instance
