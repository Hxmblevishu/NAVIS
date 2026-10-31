package com.navis.companion.network

import com.google.gson.annotations.SerializedName

/**
 * Real-time hazard event pushed from NAVIS backend over WebSocket (/ws/events).
 */
data class HazardEventEnvelope(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("type") val type: String = "hazard",
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("priority") val priority: String, // "critical", "high", "normal", "low"
    @SerializedName("message") val message: String,   // Spoken text for Android Text-to-Speech
    @SerializedName("source") val source: String,     // "sensor_fusion", "camera", "distance_sensor"
    @SerializedName("evidence") val evidence: Map<String, Any>? = null,
    @SerializedName("decision_reason") val decisionReason: String? = null
)

/**
 * System or sensor status update pushed from NAVIS backend.
 */
data class StatusEventEnvelope(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("type") val type: String = "status",
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("component") val component: String,
    @SerializedName("state") val state: String,
    @SerializedName("priority") val priority: String,
    @SerializedName("message") val message: String
)

/**
 * Transcribed voice command dispatched to POST /api/command.
 */
data class CommandRequest(
    @SerializedName("text") val text: String,
    @SerializedName("command") val command: String? = null,
    @SerializedName("location") val location: LocationCoordinate? = null
)

data class LocationCoordinate(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy_m") val accuracyM: Float? = null
)

data class CommandResponse(
    @SerializedName("intent") val intent: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: Map<String, Any>? = null
)

data class BackendHealth(
    @SerializedName("status") val status: String,
    @SerializedName("model_loaded") val modelLoaded: Boolean,
    @SerializedName("voice_assistant_configured") val voiceAssistantConfigured: Boolean? = null,
    @SerializedName("camera_connected") val cameraConnected: Boolean,
    @SerializedName("distance_sensor_connected") val distanceSensorConnected: Boolean,
    @SerializedName("distance_sensor_mode") val distanceSensorMode: String,
    @SerializedName("reasons") val reasons: Map<String, String> = emptyMap()
)

data class DeviceStatus(
    @SerializedName("camera_connected") val cameraConnected: Boolean,
    @SerializedName("distance_sensor_connected") val distanceSensorConnected: Boolean,
    @SerializedName("distance_sensor_mode") val distanceSensorMode: String,
    @SerializedName("fps") val fps: Float = 0f,
    @SerializedName("operational_state") val operationalState: String
)

data class DistanceReading(
    @SerializedName("sensor_id") val sensorId: String,
    @SerializedName("sensor_model") val sensorModel: String,
    @SerializedName("distance_m") val distanceM: Float?,
    @SerializedName("valid") val valid: Boolean,
    @SerializedName("status") val status: String
)

data class FusionItem(
    @SerializedName("object_label") val objectLabel: String,
    @SerializedName("object_confidence") val objectConfidence: Float,
    @SerializedName("image_position") val imagePosition: String,
    @SerializedName("distance_m") val distanceM: Float?,
    @SerializedName("distance_status") val distanceStatus: String
)

data class LatestPerceptionResponse(
    @SerializedName("status") val status: String,
    @SerializedName("frame_timestamp") val frameTimestamp: String?,
    @SerializedName("frame_id") val frameId: String?,
    @SerializedName("detections") val detections: List<DetectionItem> = emptyList(),
    @SerializedName("fusion") val fusion: List<FusionItem> = emptyList(),
    @SerializedName("distance_reading") val distanceReading: DistanceReading?,
    @SerializedName("message") val message: String?
)

data class OcrResponse(
    @SerializedName("status") val status: String,
    @SerializedName("text") val text: String,
    @SerializedName("quality") val quality: String?,
    @SerializedName("processing_ms") val processingMs: Float
)

data class SceneAnalysisResponse(
    @SerializedName("status") val status: String,
    @SerializedName("description") val description: String,
    @SerializedName("uncertainty") val uncertainty: String,
    @SerializedName("detections") val detections: List<DetectionItem> = emptyList()
)

data class RouteManeuver(
    @SerializedName("instruction") val instruction: String,
    @SerializedName("distance_m") val distanceM: Float,
    @SerializedName("maneuver_type") val maneuverType: String
)

data class NavigationRouteResponse(
    @SerializedName("route_id") val routeId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String,
    @SerializedName("destination") val destination: String,
    @SerializedName("distance_remaining_m") val distanceRemainingM: Float?,
    @SerializedName("eta_s") val etaS: Int?,
    @SerializedName("next_maneuver") val nextManeuver: String?,
    @SerializedName("maneuvers") val maneuvers: List<RouteManeuver> = emptyList(),
    @SerializedName("attribution") val attribution: String?,
    @SerializedName("overview_polyline") val overviewPolyline: String? = null
)

data class NavigationProgressResponse(
    @SerializedName("route_id") val routeId: String,
    @SerializedName("status") val status: String,
    @SerializedName("next_maneuver") val nextManeuver: String,
    @SerializedName("distance_remaining_m") val distanceRemainingM: Float,
    @SerializedName("eta_s") val etaS: Int,
    @SerializedName("message") val message: String
)

/**
 * Conversational voice assistance request dispatched to POST /api/voice-command.
 */
data class VoiceCommandRequest(
    @SerializedName("question") val question: String,
    @SerializedName("response_style") val responseStyle: String = "auto",
    @SerializedName("history") val history: List<Map<String, String>>? = null
)

data class VoiceStreamResult(
    val completed: Boolean,
    val errorMessage: String? = null
)

data class HybridVoiceCommandRequest(
    @SerializedName("text") val text: String
)

data class HybridVoiceCommand(
    @SerializedName("success") val success: Boolean,
    @SerializedName("intent") val intent: String,
    @SerializedName("action") val action: String,
    @SerializedName("confidence") val confidence: Double,
    @SerializedName("method") val method: String,
    @SerializedName("message") val message: String,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean = false,
    @SerializedName("parameters") val parameters: Map<String, Any>? = null
)

data class VoiceCommandResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("route") val route: String,
    @SerializedName("response") val response: String,
    @SerializedName("detections") val detections: List<Map<String, Any>>? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("data") val data: Map<String, Any>? = null
)

data class DescribeSceneResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("route") val route: String,
    @SerializedName("response") val response: String,
    @SerializedName("detections") val detections: List<Map<String, Any>>? = null
)

/**
 * Structured object detection returned by POST /api/detect.
 */
data class DetectionItem(
    @SerializedName("label") val label: String,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("bbox") val bbox: List<Float>,
    @SerializedName("position") val position: String,
    @SerializedName("vertical_position") val verticalPosition: String? = null,
    @SerializedName("area_ratio") val areaRatio: Float? = null
)

data class DetectionResponse(
    @SerializedName("detections") val detections: List<DetectionItem> = emptyList(),
    @SerializedName("frame_timestamp") val frameTimestamp: String? = null,
    @SerializedName("frame_id") val frameId: String? = null,
    @SerializedName("inference_ms") val inferenceMs: Float = 0f,
    @SerializedName("model_status") val modelStatus: String? = null
)
