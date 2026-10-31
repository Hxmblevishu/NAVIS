# NAVIS System Architecture

## SAARTHI 2026 | AI-Powered Assistive Navigation Companion

---

### 1. Architectural Overview

NAVIS delivers voice-first, context-aware navigational awareness for blind and low-vision pedestrians. The system integrates wearable edge hardware, laptop AI compute, and a native Android companion application.

```
+---------------------+             Wi-Fi (HTTP / RTSP)            +----------------------------------+
|      Wearable       |------------------------------------------->|          Laptop Backend          |
|  ESP32-CAM (OV2640) |                                            |       FastAPI + Python 3.12      |
|  Distance Sensor    |-- HTTP Telemetry (POST /api/sensors/dist)->|                                  |
+---------------------+                                            |  1. Camera Bounded Buffer        |
                                                                   |  2. Ultralytics YOLO26n          |
                                                                   |  3. Sensor Fusion Engine         |
                                                                   |  4. Hazard Decision Engine       |
                                                                   |  5. PaddleOCR + Gemini Vision     |
                                                                   |  6. Navigation Progress Tracker  |
                                                                   +----------------------------------+
                                                                                    |
                                                                     WebSocket (/ws/events) & REST
                                                                                    v
                                                                   +----------------------------------+
                                                                   |        Android Companion         |
                                                                   |      Kotlin + Jetpack Compose    |
                                                                   |                                  |
                                                                   |  - Android SpeechRecognizer (ASR)|
                                                                   |  - Android TextToSpeech (TTS)    |
                                                                   |  - High-Accuracy GPS Provider    |
                                                                   +----------------------------------+
```

---

### 2. Core Perception & Hazard Pipeline

The pipeline processes perception evidence cautiously without assuming that high model confidence equates to physical safety or exact obstacle distance:

```
[ Camera Stream / Snapshot ]
            |
            v
[ Camera Manager: Single-Frame Bounded Buffer ] ---> Rejects stale images (> 2.0s)
            |
            v
[ Ultralytics YOLO26n Detection ] -------------> Horizontal categorization: left, center, right
            |                                    Vertical: top, middle, bottom
            +--------------------+
                                 |
[ Distance Sensor Adapter ] -----+
(Telemetry / Mock / Physical)    |
                                 v
                     [ Sensor Fusion Engine ]
                     - Timestamp alignment check (<= 1000ms)
                     - Validity & range check (0.05m to 4.0m)
                     - Spatial alignment check (forward cone <=> center image)
                     - Peripheral detections (left/right) marked "unassociated"
                     - Multi-target disambiguation: dominant target vs "uncertain"
                                 |
                                 v
                     [ Hazard Decision Engine ]
                     - Confidence thresholding (configurable, default 0.55)
                     - Temporal persistence (requires 2 consecutive frames for routine items)
                     - Critical distance override (< 0.8m emits immediately)
                     - Duplicate suppression cooldown (configurable, default 4.0s)
                     - Qualified message generation
                                 |
                                 v
                     [ WebSocket Event Manager ]
                     - Bounded asynchronous dispatch to Android
                     - Non-blocking client queue handling
```

---

### 3. Key Subsystems and Responsibilities

| Subsystem | Primary Module | Responsibilities |
| :--- | :--- | :--- |
| **Configuration** | `app/config.py` | Validated environment parameters, safe defaults, numeric range bounds. |
| **Settings** | `app/settings.py` | Runtime parameter updates (`PUT /api/settings`) with local JSON disk persistence. |
| **Logging** | `app/logging_config.py` | Console structured logs and append-only `data/events.jsonl` audit records. |
| **Camera** | `app/camera.py` | Stream connection, JPEG decoding, bounded latest-frame buffer, exponential backoff. |
| **Detection** | `app/detection.py` | Single model load, CPU-friendly inference, bounding box normalization, horizontal/vertical zone classification. |
| **Distance Sensor** | `app/distance_sensor.py` | Normalized sensor reading interface, HTTP push telemetry adapter, mock simulator. |
| **Sensor Fusion** | `app/sensor_fusion.py` | Evidence-based association of 2D bounding boxes with 1D physical range measurements. |
| **Hazard Policy** | `app/hazard_engine.py` | Deduplication, persistence tracking, urgency grading, spoken alert formatting. |
| **Events & WS** | `app/events.py` | WebSocket client lifecycle, broadcast queues, async perception background loop. |
| **Voice Commands** | `app/commands.py` | Deterministic intent router (`describe`, `read_text`, `navigate`, `repeat`, `cancel`, `status`). |
| **Hybrid Voice Intent** | `app/voice/` and Android `commands/` | Exact rules and character TF-IDF resolve safe actions; Kotlin dispatches allow-listed actions to existing Compose screens. Unknown conversational questions go to Gemini. |
| **OCR** | `app/ocr.py` | Multilingual PaddleOCR text detection and recognition, blur assessment, confidence filtering. |
| **Gemini Assistant** | `app/gemini_service.py`, `app/voice_assistant.py` | General spoken Q&A and multimodal scene answers, optionally grounded by YOLO26 detections. |
| **Scene Analysis** | `app/scene_analysis.py` | Gemini vision summaries grounded by YOLO26 labels, with a local fallback. |
| **Navigation** | `app/navigation.py` | Pedestrian route calculation, ambiguous destination resolution, turn progress tracking. |

The Android client keeps speech recognition and speech playback native. It first sends a transcript to `POST /api/voice/command`; the backend returns a typed action without performing UI operations. Kotlin executes recognized actions in the existing app, asks for confirmation before live detection, and sends only `UNKNOWN` conversational requests to the Gemini endpoint. Stop speaking, repeat, back, and cancel controls also have local offline aliases. TF-IDF scores are ranking heuristics, not calibrated probabilities.
