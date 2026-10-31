"""Pydantic schemas and typed data models for NAVIS backend API and event streams."""
from datetime import datetime, timezone
from typing import Any, Dict, List, Literal, Optional
from pydantic import BaseModel, Field, field_validator


def utc_now() -> datetime:
    """Return current timezone-aware UTC datetime."""
    return datetime.now(timezone.utc)


# ============================================================================
# Health and Device Status
# ============================================================================

class HealthResponse(BaseModel):
    """System-wide health check response."""
    status: Literal["healthy", "degraded", "unhealthy"] = Field(
        ..., description="Overall backend health status"
    )
    backend_version: str = Field(..., description="Semantic version of NAVIS backend")
    model_loaded: bool = Field(..., description="Whether YOLO model is loaded in memory")
    voice_assistant_configured: bool = Field(
        ..., description="Whether Gemini voice assistance has an API key configured"
    )
    model_name: str = Field(..., description="Configured YOLO model name/weights")
    camera_configured: bool = Field(..., description="Whether a camera URL is configured")
    camera_connected: bool = Field(..., description="Whether camera is actively receiving frames")
    distance_sensor_mode: str = Field(..., description="Configured sensor mode: auto, mock, http_telemetry, disabled")
    distance_sensor_connected: bool = Field(..., description="Whether distance sensor has active valid readings")
    active_processing_mode: str = Field(..., description="Active processing mode (e.g., live, diagnostic, mock)")
    degraded_components: List[str] = Field(default_factory=list, description="List of degraded components")
    reasons: Dict[str, str] = Field(default_factory=dict, description="Reasons for component degradation")
    timestamp: datetime = Field(default_factory=utc_now, description="Response timestamp")


class DeviceStatus(BaseModel):
    """Hardware device and operational telemetry status."""
    camera_connected: bool = Field(..., description="Camera connection status")
    last_frame_at: Optional[datetime] = Field(None, description="Timestamp of latest successful camera frame")
    frame_width: Optional[int] = Field(None, description="Frame width in pixels")
    frame_height: Optional[int] = Field(None, description="Frame height in pixels")
    fps: float = Field(0.0, description="Estimated frame acquisition rate")
    distance_sensor_connected: bool = Field(..., description="Distance sensor operational status")
    distance_sensor_mode: str = Field(..., description="Active distance sensor mode")
    last_sensor_reading_at: Optional[datetime] = Field(None, description="Timestamp of latest sensor reading")
    backend_version: str = Field(..., description="Backend software version")
    operational_state: Literal["running", "degraded", "idle", "error"] = Field(
        ..., description="Overall operational state"
    )
    timestamp: datetime = Field(default_factory=utc_now)


# ============================================================================
# Detection and Computer Vision
# ============================================================================

class Detection(BaseModel):
    """Single object detection result from YOLO."""
    label: str = Field(..., description="Detected object class label (e.g. person, chair, stairs)")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Model confidence score")
    bbox: List[float] = Field(..., description="Bounding box [x1, y1, x2, y2] in normalized or pixel coords")
    position: Literal["left", "center", "right", "unknown"] = Field(
        ..., description="Horizontal region in image frame"
    )
    vertical_position: Optional[Literal["top", "middle", "bottom"]] = Field(
        None, description="Vertical region in image frame"
    )
    area_ratio: Optional[float] = Field(
        None, ge=0.0, le=1.0, description="Bounding box area relative to image area"
    )


class DetectionResponse(BaseModel):
    """Response returned by POST /api/detect."""
    detections: List[Detection] = Field(default_factory=list, description="List of structured detections")
    frame_timestamp: datetime = Field(default_factory=utc_now, description="Frame acquisition timestamp")
    frame_id: Optional[str] = Field(None, description="Unique frame identifier")
    inference_ms: float = Field(..., ge=0.0, description="Model inference latency in milliseconds")
    model_status: Literal["loaded", "inference_complete", "inference_failed", "model_unavailable"] = Field(
        "inference_complete", description="Model execution status"
    )


# ============================================================================
# Distance Sensor and Telemetry
# ============================================================================

class DistanceReading(BaseModel):
    """Normalized distance reading from physical or mock sensor."""
    sensor_id: str = Field(default="sensor_0", description="Sensor identifier")
    sensor_model: str = Field(default="unknown", description="Sensor hardware model")
    timestamp: datetime = Field(default_factory=utc_now, description="Measurement timestamp")
    distance_m: Optional[float] = Field(
        None, ge=0.0, description="Measured distance in metres, null if invalid or out of range"
    )
    valid: bool = Field(..., description="Whether reading is within valid operating specifications")
    status: Literal["valid", "out_of_range", "stale", "error", "no_target", "disconnected"] = Field(
        ..., description="Reading validity classification"
    )
    quality: Optional[float] = Field(
        None, ge=0.0, le=1.0, description="Signal rate / confidence from hardware if available"
    )
    measurement_direction: str = Field(
        default="center", description="Direction vector / horizontal orientation of sensor (default center)"
    )
    error: Optional[str] = Field(None, description="Error explanation if invalid or failed")


class SensorStatus(BaseModel):
    """Aggregated status and diagnostics for distance sensor subsystem."""
    connected: bool = Field(..., description="Whether sensor is actively communicating")
    mode: str = Field(..., description="Sensor mode: auto, mock, http_telemetry, disabled")
    model: str = Field(default="unknown", description="Sensor hardware model")
    last_reading: Optional[DistanceReading] = Field(None, description="Latest acquired distance reading")
    total_readings: int = Field(0, description="Total readings processed since startup")
    invalid_readings_count: int = Field(0, description="Number of invalid/out-of-range readings encountered")


class SensorTelemetryRequest(BaseModel):
    """Payload pushed by ESP32 or external sensor bridge via POST /api/sensors/distance."""
    sensor_id: str = Field(default="esp32_sensor", description="Hardware sensor identifier")
    sensor_model: Optional[str] = Field(None, description="Hardware sensor model")
    distance_m: Optional[float] = Field(None, description="Distance reading in metres")
    valid: bool = Field(True, description="Whether measurement is considered valid by firmware")
    timestamp: Optional[datetime] = Field(None, description="Sensor measurement timestamp")
    quality: Optional[float] = Field(None, ge=0.0, le=1.0, description="Signal quality indicator")
    status: Optional[str] = Field(None, description="Hardware status code")


# ============================================================================
# Sensor Fusion
# ============================================================================

class FusionResult(BaseModel):
    """Result of associating camera detection with physical distance measurement."""
    object_label: str = Field(..., description="Associated object label")
    object_confidence: float = Field(..., description="Detection confidence")
    image_position: Literal["left", "center", "right", "unknown"] = Field(
        ..., description="Horizontal image position"
    )
    distance_m: Optional[float] = Field(
        None, description="Physical distance in metres, null if unassociated or invalid"
    )
    distance_status: Literal[
        "associated", "unassociated", "invalid_sensor", "stale_sensor", "uncertain"
    ] = Field(..., description="Distance association classification")
    association_confidence: Optional[float] = Field(
        None, ge=0.0, le=1.0, description="Confidence in geometric/spatial association"
    )
    association_reason: str = Field(
        ..., description="Detailed explanation of why association was accepted or rejected"
    )


# ============================================================================
# Events and WebSocket Envelopes
# ============================================================================

class HazardEvent(BaseModel):
    """Prioritized hazard or obstacle notification dispatched to Android."""
    event_id: str = Field(..., description="Unique event ID")
    type: Literal["hazard"] = Field("hazard", description="Event type discriminator")
    timestamp: datetime = Field(default_factory=utc_now, description="Event creation timestamp")
    priority: Literal["critical", "high", "normal", "low"] = Field(
        ..., description="Alert urgency priority"
    )
    message: str = Field(..., description="Concise spoken alert message for TTS")
    source: Literal["sensor_fusion", "camera", "distance_sensor", "policy"] = Field(
        ..., description="Subsystem generating event"
    )
    evidence: Dict[str, Any] = Field(
        default_factory=dict, description="Diagnostic evidence (e.g. camera_available, distance_m, label)"
    )
    decision_reason: Optional[str] = Field(
        None, description="Rule or policy triggering announcement"
    )


class StatusEvent(BaseModel):
    """System or component health status update broadcast to Android."""
    event_id: str = Field(..., description="Unique event ID")
    type: Literal["status"] = Field("status", description="Event type discriminator")
    timestamp: datetime = Field(default_factory=utc_now, description="Status event timestamp")
    component: Literal["camera", "distance_sensor", "model", "navigation", "backend"] = Field(
        ..., description="Subsystem emitting status"
    )
    state: Literal["connected", "disconnected", "degraded", "reconnecting", "error", "ready"] = Field(
        ..., description="Component state"
    )
    priority: Literal["high", "normal", "low"] = Field("normal", description="Message urgency")
    message: str = Field(..., description="Spoken or logged status message")


# ============================================================================
# OCR and Scene Analysis
# ============================================================================

class OCRResponse(BaseModel):
    """Response returned by POST /api/ocr."""
    status: Literal["success", "no_text_found", "blurred", "unavailable", "error"] = Field(
        ..., description="OCR execution outcome"
    )
    text: str = Field(..., description="Extracted text or empty string")
    confidence: Optional[float] = Field(None, ge=0.0, le=1.0, description="Estimated OCR confidence")
    quality: Optional[str] = Field(None, description="Quality indicator: good, low_confidence, degraded")
    processing_ms: float = Field(..., ge=0.0, description="Processing time in milliseconds")
    timestamp: datetime = Field(default_factory=utc_now)


class SceneAnalysisResponse(BaseModel):
    """Response returned by POST /api/analyze."""
    status: Literal["success", "unavailable", "error"] = Field(
        ..., description="Scene analysis outcome"
    )
    description: str = Field(..., description="Grounded scene description")
    uncertainty: str = Field(..., description="Explicit statement of perception uncertainty")
    detections: List[Detection] = Field(default_factory=list, description="Detector objects identified in scene")
    timestamp: datetime = Field(default_factory=utc_now)


# ============================================================================
# Voice Commands
# ============================================================================

class LocationCoordinate(BaseModel):
    """Geographic coordinate from mobile device."""
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    accuracy_m: Optional[float] = Field(None, ge=0.0)


class CommandRequest(BaseModel):
    """Voice command request from Android."""
    command: Optional[str] = Field(
        None, description="Optional raw command intent: describe, read_text, navigate, repeat, cancel, status"
    )
    text: str = Field(..., min_length=1, max_length=2000, description="Transcribed voice text")
    location: Optional[LocationCoordinate] = Field(None, description="Current GPS coordinates from phone")


class CommandResponse(BaseModel):
    """Command router response."""
    intent: Literal["describe", "read_text", "navigate", "repeat", "cancel", "status", "unknown"] = Field(
        ..., description="Resolved command intent"
    )
    status: Literal[
        "success", "clarification_needed", "destination_required",
        "location_required", "unavailable", "cancelled"
    ] = Field(..., description="Execution status")
    message: str = Field(..., description="Spoken feedback for user")
    data: Optional[Dict[str, Any]] = Field(None, description="Supplemental structured result payload")


class VoiceCommandRequest(BaseModel):
    """Voice command request supporting conversational question, history, and context."""
    question: Optional[str] = Field(None, description="Voice query or command (from speech recognition)")
    text: Optional[str] = Field(None, description="Alternative text field")
    history: Optional[List[Dict[str, str]]] = Field(None, description="Optional conversation turns")
    scene_context: Optional[List[Dict[str, Any]]] = Field(None, description="Optional scene detections")
    location: Optional[LocationCoordinate] = Field(None, description="Current device location for route requests")
    response_style: Literal["auto", "quick", "detailed"] = Field(
        "auto", description="Auto-select concise/deep answer mode or request one explicitly"
    )


class VoiceCommandResponse(BaseModel):
    """Voice command response for speech feedback."""
    ok: bool = Field(True, description="Whether request succeeded")
    route: str = Field("GENERAL_CHAT", description="Command classification category")
    response: str = Field(..., description="Spoken response text")
    message: Optional[str] = Field(None, description="Diagnostic message if any")
    data: Optional[Dict[str, Any]] = Field(None, description="Structured result such as an active navigation route")
    status: Optional[str] = Field(None, description="Command status when the request is an action")


class HybridVoiceCommandRequest(BaseModel):
    """Spoken text to classify into a safe, typed NAVIS action."""

    text: str = Field(..., min_length=1, max_length=2000)


class HybridVoiceCommandResponse(BaseModel):
    """Intent decision for Kotlin to execute or reject on the device."""

    success: bool
    intent: str
    action: str
    confidence: float = Field(..., ge=0.0, le=1.0)
    method: Literal["rule", "nlp", "none"]
    message: str
    requires_confirmation: bool = False
    parameters: Dict[str, Any] = Field(default_factory=dict)


# ============================================================================
# User and System Settings
# ============================================================================

class SettingsResponse(BaseModel):
    """Current active backend settings."""
    yolo_confidence: float = Field(..., ge=0.05, le=1.0)
    frame_interval_ms: int = Field(..., ge=50, le=5000)
    alert_cooldown_s: float = Field(..., ge=0.5, le=60.0)
    sensor_max_age_ms: int = Field(..., ge=100, le=10000)
    audio_verbosity: Literal["concise", "normal", "detailed"] = Field("concise")
    distance_warn_threshold_m: float = Field(..., ge=0.1, le=10.0)
    measurement_units: Literal["metres", "feet"] = Field("metres")


class SettingsUpdate(BaseModel):
    """Payload to update runtime settings."""
    yolo_confidence: Optional[float] = Field(None, ge=0.05, le=1.0)
    frame_interval_ms: Optional[int] = Field(None, ge=50, le=5000)
    alert_cooldown_s: Optional[float] = Field(None, ge=0.5, le=60.0)
    sensor_max_age_ms: Optional[int] = Field(None, ge=100, le=10000)
    audio_verbosity: Optional[Literal["concise", "normal", "detailed"]] = None
    distance_warn_threshold_m: Optional[float] = Field(None, ge=0.1, le=10.0)
    measurement_units: Optional[Literal["metres", "feet"]] = None


# ============================================================================
# Navigation Integration
# ============================================================================

class RouteManeuver(BaseModel):
    """Single turn-by-turn navigation maneuver."""
    instruction: str = Field(..., description="Spoken turn instruction (e.g., Turn left on MG Road)")
    distance_m: float = Field(..., ge=0.0, description="Distance for this step in metres")
    maneuver_type: str = Field(default="straight", description="depart, turn-left, turn-right, straight, arrive")


class NavigationRouteRequest(BaseModel):
    """Route calculation request from Android."""
    origin: LocationCoordinate = Field(..., description="Current GPS coordinates")
    destination: str = Field(..., min_length=2, description="Target destination address or landmark name")
    mode: Literal["pedestrian"] = Field("pedestrian", description="Routing mode")


class NavigationRouteResponse(BaseModel):
    """Calculated pedestrian route response."""
    route_id: str = Field(..., description="Generated route ID")
    status: Literal["active", "ambiguous_destination", "unavailable", "failed"] = Field(...)
    message: str = Field(..., description="Spoken confirmation or clarification prompt")
    destination: str = Field(...)
    distance_remaining_m: Optional[float] = Field(None, ge=0.0)
    eta_s: Optional[int] = Field(None, ge=0)
    next_maneuver: Optional[str] = Field(None)
    maneuvers: List[RouteManeuver] = Field(default_factory=list)
    overview_polyline: Optional[str] = Field(None, description="Encoded overview path returned by the route provider")
    attribution: Optional[str] = Field(None, description="Required provider attribution for displayed route data")


class LatestPerceptionResponse(BaseModel):
    """Latest detector and sensor data from the live perception pipeline."""
    status: Literal["live", "unavailable"] = Field(...)
    frame_timestamp: Optional[datetime] = None
    frame_id: Optional[str] = None
    detections: List[Detection] = Field(default_factory=list)
    fusion: List[FusionResult] = Field(default_factory=list)
    distance_reading: Optional[DistanceReading] = None
    message: Optional[str] = None


class NavigationProgressRequest(BaseModel):
    """Updated GPS location during active navigation."""
    route_id: str = Field(..., description="Active route ID")
    location: LocationCoordinate = Field(..., description="Updated GPS coordinates")


class NavigationProgressResponse(BaseModel):
    """Turn-by-turn progress update."""
    route_id: str = Field(...)
    status: Literal["in_progress", "completed", "rerouting", "stale_location", "off_route"] = Field(...)
    next_maneuver: str = Field(...)
    distance_remaining_m: float = Field(..., ge=0.0)
    eta_s: int = Field(..., ge=0)
    message: str = Field(...)
