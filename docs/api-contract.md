# NAVIS API & WebSocket Contract

## SAARTHI 2026 Hackathon | Version 1.0

The NAVIS backend exposes RESTful APIs for on-demand queries and commands, and a WebSocket stream for real-time hazard notifications and telemetry.

Base URL: `http://<laptop_ip>:8000`  
Interactive Swagger Docs: `http://<laptop_ip>:8000/docs`

When `API_TOKEN` is configured, Android REST requests and `/ws/events` connections must send the matching `X-API-Token` header. The Android Settings screen stores the server URL and token. Requests without a token are allowed only when the backend has no token configured. The browser dashboard uses same-origin `/api/web/*` endpoints and does not receive the server token.

---

### 1. Health & Device Telemetry

#### `GET /health`
Returns backend health status, component states, and degradation reasons.

**Response (200 OK):**
```json
{
  "status": "degraded",
  "backend_version": "0.1.0",
  "model_loaded": true,
  "model_name": "yolo26n.pt",
  "camera_configured": false,
  "camera_connected": false,
  "distance_sensor_mode": "auto",
  "distance_sensor_connected": false,
  "active_processing_mode": "diagnostic",
  "degraded_components": ["camera", "distance_sensor"],
  "reasons": {
    "camera": "No camera stream connected",
    "distance_sensor": "Sensor offline or no telemetry received"
  },
  "timestamp": "2026-10-29T16:30:00Z"
}
```

#### `GET /api/device`
Returns hardware operational status and frame rate telemetry.

**Response (200 OK):**
```json
{
  "camera_connected": true,
  "last_frame_at": "2026-10-29T16:30:00Z",
  "frame_width": 640,
  "frame_height": 480,
  "fps": 12.5,
  "distance_sensor_connected": true,
  "distance_sensor_mode": "http_telemetry",
  "last_sensor_reading_at": "2026-10-29T16:30:00Z",
  "backend_version": "0.1.0",
  "operational_state": "running",
  "timestamp": "2026-10-29T16:30:01Z"
}
```

---

### 2. Camera Snapshot & Object Detection

#### `GET /api/camera/snapshot?fresh=false`
Retrieves latest JPEG frame from bounded buffer (or forces fresh frame with `fresh=true`).

- **Response (200 OK):** `image/jpeg` binary payload.
- **Response (503 Service Unavailable):** JSON error when no valid frame exists.

#### `POST /api/detect`
Runs YOLO inference on an uploaded image file or on the latest live camera frame. The image upload is optional; without one, the endpoint uses a fresh backend camera frame.

- **Request:** Optional `multipart/form-data` with `image` file field.
- **Response (200 OK):**
```json
{
  "detections": [
    {
      "label": "person",
      "confidence": 0.912,
      "bbox": [210.0, 120.0, 430.0, 440.0],
      "position": "center",
      "vertical_position": "middle",
      "area_ratio": 0.231
    }
  ],
  "frame_timestamp": "2026-10-29T16:30:00Z",
  "frame_id": "frm_a1b2c3d4",
  "inference_ms": 28.5,
  "model_status": "inference_complete"
}
```

---

### 3. Distance Sensor Telemetry

#### `GET /api/sensors/distance`
Returns the latest normalized physical distance measurement.

**Response (200 OK):**
```json
{
  "sensor_id": "esp32_sensor",
  "sensor_model": "unknown",
  "timestamp": "2026-10-29T16:30:00Z",
  "distance_m": 1.42,
  "valid": true,
  "status": "valid",
  "quality": 0.95,
  "measurement_direction": "center",
  "error": null
}
```

#### `POST /api/sensors/distance`
Ingestion endpoint for ESP32 firmware to push ranging telemetry.

**Request Body (application/json):**
```json
{
  "sensor_id": "esp32_sensor",
  "sensor_model": "VL53L0X",
  "distance_m": 1.42,
  "valid": true,
  "quality": 0.95
}
```

---

### 4. OCR & Scene Analysis

#### `POST /api/ocr`
Extracts text from an image or the current camera view.

- **Request:** Optional `image` file field.
- **Response (200 OK):**
```json
{
  "status": "success",
  "text": "EMERGENCY EXIT",
  "confidence": 0.89,
  "quality": "good",
  "processing_ms": 115.0,
  "timestamp": "2026-10-29T16:30:00Z"
}
```

#### `POST /api/analyze?prompt=...`
Generates a grounded scene description with Gemini vision and YOLO26 detections, or returns a local detection-only fallback when Gemini is unavailable. Configure the backend-only `GEMINI_API_KEY` and `GEMINI_MODEL` values.

- **Response (200 OK):**
```json
{
  "status": "success",
  "description": "You are facing an open indoor corridor with a clear floor. A person is walking ahead approximately two metres away.",
  "uncertainty": "Visual description based on single-frame camera analysis; physical distance not verified.",
  "detections": [...],
  "timestamp": "2026-10-29T16:30:00Z"
}
```

---

### 5. Voice Commands & Settings

#### `POST /api/command`
Accepts speech-to-text transcripts from Android SpeechRecognizer.

**Request:**
```json
{
  "text": "Take me to Central Library",
  "command": "navigate",
  "location": {
    "latitude": 12.9716,
    "longitude": 77.5946
  }
}
```

**Response:**
```json
{
  "intent": "navigate",
  "status": "success",
  "message": "Calculating pedestrian route to Central Library.",
  "data": {
    "destination": "Central Library",
    "origin": { "latitude": 12.9716, "longitude": 77.5946 }
  }
}
```

#### `POST /api/voice/command`
Authenticated, local-first intent resolution for Android voice requests. Exact phrases and safe destination patterns use deterministic rules; paraphrases use character TF-IDF similarity. NAVIS returns `UNKNOWN` for general questions so the Android client can send those to `/api/voice-command` (Gemini). This endpoint never performs Android navigation or starts camera processing.

**Request:**
```json
{ "text": "Hey NAVIS, navigate to Connaught Place" }
```

**Response:**
```json
{
  "success": true,
  "intent": "OPEN_NAVIGATION",
  "action": "OPEN_NAVIGATION",
  "confidence": 1.0,
  "method": "rule",
  "message": "Opening pedestrian navigation to connaught place.",
  "requires_confirmation": false,
  "parameters": { "screen": "navigate", "destination": "connaught place" }
}
```

`START_DETECTION` sets `requires_confirmation: true`; Android asks the user before starting the live assist screen. `confidence` is a similarity score for intent ranking, not a calibrated probability. `STOP_SPEAKING`, `REPEAT_RESPONSE`, `GO_BACK`, and `CANCEL_OPERATION` are intercepted locally on Android and remain usable without the backend.

#### `POST /api/voice-command`
Answers general spoken-language questions with Gemini. Camera context is optional; when detections or an image are supplied, YOLO26 is used as visual context. A Gemini configuration or service problem returns `status: "unavailable"` with a speakable explanation instead of fabricating an answer.

**Request:**
```json
{
  "question": "Who is the Prime Minister of India?",
  "history": []
}
```

**Response:**
```json
{
  "ok": true,
  "route": "VOICE_ASSISTANT",
  "response": "...",
  "status": "success"
}
```

#### `GET /api/settings` and `PUT /api/settings`
Queries or updates runtime thresholds (persists to `data/settings.json`).

**Payload:**
```json
{
  "yolo_confidence": 0.55,
  "frame_interval_ms": 500,
  "alert_cooldown_s": 4.0,
  "sensor_max_age_ms": 1000,
  "audio_verbosity": "concise",
  "distance_warn_threshold_m": 1.5,
  "measurement_units": "metres"
}
```

---

### 6. Navigation Integration

Routes are requested from the Google Maps Routes API as pedestrian (`WALK`) routes. Configure a server-side `GOOGLE_MAPS_API_KEY` with Routes API access. Without the key, the API returns `status: "unavailable"` and does not invent distance, ETA, or maneuvers. Google route displays include the attribution returned by the API. Camera detections do not validate that a route or crossing is safe.

#### `POST /api/navigation/route`
Calculates pedestrian routes with disambiguation checks.

**Response:**
```json
{
  "route_id": "route_1a2b3c4d",
  "status": "active",
  "message": "Route calculated to Central Library. Distance is 250 metres, about 4 minutes.",
  "destination": "Central Library",
  "distance_remaining_m": 250.0,
  "eta_s": 208,
  "next_maneuver": "Start walking east towards Central Library",
  "maneuvers": [
    { "instruction": "Start walking east towards Central Library", "distance_m": 120.0, "maneuver_type": "depart" },
    { "instruction": "Turn left onto Pedestrian Walkway", "distance_m": 85.0, "maneuver_type": "turn-left" },
    { "instruction": "Continue straight. Your destination will be on the right", "distance_m": 45.0, "maneuver_type": "arrive" }
  ]
}
```

#### `POST /api/navigation/progress`
Updates turn-by-turn maneuvers as coordinates change.

#### `GET /api/perception/latest`
Returns the latest frame detections and cautious sensor-fusion associations produced by the live WebSocket perception pipeline. Peripheral camera objects are not assigned the forward distance sensor reading.

---

### 7. Real-Time WebSocket Events (`WS /ws/events`)

Stream of prioritized JSON event envelopes broadcast to Android clients. If `API_TOKEN` is configured, connect with the `X-API-Token` handshake header.

### Browser Dashboard

The same-origin dashboard uses `POST /api/web/hazard-check`, `POST /api/web/describe-scene`, and `POST /api/web/voice-command`. These routes validate the browser `Origin`; other clients should use the authenticated `/api/*` endpoints.

**Hazard Event Envelope:**
```json
{
  "event_id": "evt_9f8e7d6c",
  "type": "hazard",
  "timestamp": "2026-10-29T16:30:00Z",
  "priority": "high",
  "message": "Person ahead, approximately one metre away.",
  "source": "sensor_fusion",
  "evidence": {
    "object_label": "person",
    "confidence": 0.91,
    "position": "center",
    "distance_m": 1.25,
    "distance_status": "associated",
    "association_confidence": 0.90
  },
  "decision_reason": "Emitted: priority=high, consecutive_frames=2, distance=1.25m"
}
```
