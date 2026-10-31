"""Configuration module for NAVIS backend using Pydantic Settings."""
from pathlib import Path
from typing import Optional
from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """NAVIS backend configuration settings."""

    # Server configuration
    navis_host: str = Field(default="0.0.0.0", description="Backend bind host")
    navis_port: int = Field(default=8000, ge=1, le=65535, description="Backend bind port")

    # Camera configuration
    esp32_camera_url: str = Field(
        default="",
        description="ESP32-CAM stream, snapshot endpoint URL, or local webcam device index"
    )

    # Distance sensor configuration (Confirmed Hardware: STMicroelectronics VL53L0X Time-of-Flight)
    distance_sensor_mode: str = Field(
        default="auto",
        description=(
            "Distance sensor mode: auto allows camera-only operation without telemetry; "
            "http_telemetry requires a connected sensor; mock simulates range data; disabled turns ranging off"
        )
    )
    distance_sensor_model: str = Field(
        default="VL53L0X",
        description="Hardware model of the distance sensor (STMicroelectronics VL53L0X ToF)"
    )
    distance_sensor_url: str = Field(
        default="",
        description="Remote distance sensor stream/pull endpoint if applicable"
    )
    sensor_min_range_m: float = Field(default=0.03, ge=0.0, description="Minimum valid distance in metres (VL53L0X: 0.03m / 30mm)")
    sensor_max_range_m: float = Field(default=2.0, ge=0.1, description="Maximum valid distance in metres (VL53L0X: 2.0m / 2000mm)")
    distance_warn_threshold_m: float = Field(default=1.2, ge=0.1, description="Hazard warning threshold in metres (VL53L0X: 1.2m)")

    # Detection configuration
    yolo_model: str = Field(
        default="yolo26n.pt",
        validation_alias=AliasChoices("yolo_model", "navis_yolo_model"),
        description="YOLO model path or weight name"
    )
    yolo_confidence: float = Field(
        default=0.55,
        ge=0.05,
        le=1.0,
        description="Detection confidence threshold"
    )
    yolo_imgsz: int = Field(default=640, ge=160, le=1920, description="YOLO input image size")
    custom_high_priority_classes: str = Field(
        default="",
        description="Comma-separated custom high-priority class names (e.g. 'pothole,curb,stairs_down') for immediate alert escalation"
    )

    # Security configuration
    api_token: str = Field(
        default="",
        description="API token for authenticating external and mobile clients"
    )

    # Timing and hazard policy
    frame_interval_ms: int = Field(default=500, ge=50, le=5000, description="Minimum loop interval in milliseconds")
    alert_cooldown_s: float = Field(default=4.0, ge=0.5, le=60.0, description="Deduplication cooldown in seconds")
    sensor_max_age_ms: int = Field(default=1000, ge=100, le=10000, description="Max sensor reading age in milliseconds")
    persistence_required_frames: int = Field(default=2, ge=1, le=10, description="Consecutive frames for routine announcements")

    # Logging and persistence
    log_level: str = Field(default="INFO", description="Log level: DEBUG, INFO, WARNING, ERROR")
    settings_file: str = Field(default="data/settings.json", description="Persistent user settings file path")
    events_log_file: str = Field(default="data/events.jsonl", description="Structured JSONL event log file path")

    # External AI Services
    gemini_api_key: str = Field(default="", repr=False, description="Server-side Gemini API key for conversational and vision assistance")
    gemini_model: str = Field(default="gemini-3.8-flash", description="Gemini model used by voice and scene assistance")
    gemini_quick_model: str = Field(
        default="gemini-3.5-flash-lite",
        description="Lower-latency Gemini model for short text-only voice replies",
    )
    gemini_timeout_seconds: float = Field(default=50.0, ge=5.0, le=120.0, description="Maximum wait for Gemini generation responses")
    paddleocr_lang: str = Field(default="hi", description="PaddleOCR recognition language code (for example hi or en)")
    google_maps_api_key: str = Field(default="", description="Server-side key for Google Maps Routes API pedestrian routing")
    google_maps_region_code: str = Field(default="IN", min_length=2, max_length=2, description="Region bias for route destination addresses")
    navis_speech_locale: str = Field(default="en-IN", description="Default speech locale")

    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    @field_validator("distance_sensor_mode")
    @classmethod
    def validate_sensor_mode(cls, v: str) -> str:
        valid_modes = {"auto", "mock", "http_telemetry", "disabled"}
        mode = v.strip().lower()
        if mode not in valid_modes:
            raise ValueError(f"distance_sensor_mode must be one of {valid_modes}, got '{v}'")
        return mode

    @field_validator("distance_sensor_model")
    @classmethod
    def validate_sensor_model(cls, v: str) -> str:
        s = v.strip().upper()
        # Normalize common breakout designations (VL530X, GY-530, VL53L0X)
        if s in {"VL530X", "VL53L0X", "VL53L0", "VL-530X", "VL-53L0X", "GY-530", "GY530"}:
            return "VL53L0X"
        return v.strip()


# Global configuration instance
_config_instance: Optional[Settings] = None


def get_config() -> Settings:
    """Retrieve the cached configuration instance or initialize a new one."""
    global _config_instance
    if _config_instance is None:
        _config_instance = Settings()
    return _config_instance


def reset_config(new_settings: Optional[Settings] = None) -> Settings:
    """Reset configuration instance (useful for unit tests)."""
    global _config_instance
    _config_instance = new_settings
    return get_config()
