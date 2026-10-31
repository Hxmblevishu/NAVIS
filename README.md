# NAVIS
## SAARTHI 2026 Hackathon | AI-Powered Voice-Interactive Navigation Companion

NAVIS is an AI-powered assistive navigation companion built for blind and low-vision pedestrians. It combines real-time camera perception, physical distance sensor evidence, context-aware alert filtering, and turn-by-turn pedestrian route guidance into prioritized, non-fatiguing audio notifications spoken via an Android companion app.

---

### Core Engineering Innovation

The core breakthrough of NAVIS is **evidence-based hazard filtering**:
- **Distinguishes Perception Facts:** Separates a 2D camera detection from a 1D physical ranging measurement, an associated measurement, and an uncertain observation.
- **Cautious Spatial Association:** Ranging readings (narrow forward cone) are only associated with center targets covering the forward optical axis. Peripheral detections (left/right) are never falsely given a forward distance reading.
- **Controlled Audio & Deduplication:** Eliminates repetitive narration through temporal persistence (requiring consecutive frames for routine items) and cooldown suppression (4.0s default), while critical close hazards (< 0.8m) immediately preempt routine speech.

---

### Repository Structure

```
navis/
├── README.md                           # Main project documentation
├── .env.example                        # Configuration template
├── .gitignore                          # Excludes secrets, .venv, and logs
├── yolo26n.pt                           # YOLO26 nano detection weights
│
├── android/                            # Native Android Companion App (Kotlin)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml         # Permissions (Camera, Audio, Location, Haptics)
│   │   └── java/com/navis/companion/   # TTS, Haptics, WebSocket, Voice ASR & UI
│   └── build.gradle.kts                # Android Studio build configuration
│
├── firmware/
│   └── esp32_cam/
│       └── esp32_cam.ino               # ESP32-CAM firmware with camera server & sensor telemetry push
│
├── backend/
│   ├── requirements.txt                # Python backend dependencies, including local voice intent NLP
│   ├── app/
│   │   ├── __init__.py                 # Version and package init
│   │   ├── main.py                     # FastAPI application, REST endpoints, and lifespan
│   │   ├── config.py                   # Pydantic Settings and environment validation
│   │   ├── schemas.py                  # Strongly-typed Pydantic request/response models
│   │   ├── camera.py                   # Bounded latest-frame buffer and camera reconnection
│   │   ├── distance_sensor.py          # Distance sensor adapter (HTTP telemetry, mock, disabled)
│   │   ├── detection.py                # Ultralytics YOLO26 inference and spatial region classifier
│   │   ├── sensor_fusion.py            # Evidence-based camera & distance association engine
│   │   ├── hazard_engine.py            # Deduplication, persistence, priority, and spoken alert policy
│   │   ├── events.py                   # WebSocket connection manager and async perception loop
│   │   ├── ocr.py                      # PaddleOCR multilingual text recognition
│   │   ├── gemini_service.py           # Backend-only Gemini text and image generation client
│   │   ├── scene_analysis.py           # Gemini vision grounded with YOLO26 context
│   │   ├── commands.py                 # Deterministic voice intent router
│   │   ├── voice/                      # Hybrid rule + TF-IDF voice intent API
│   │   ├── navigation.py               # Pedestrian route guidance and turn progress tracker
│   │   ├── settings.py                 # Runtime settings manager with JSON disk persistence
│   │   └── logging_config.py           # Structured logging and JSONL audit trail
│   │
│   └── tests/
│       ├── test_health.py              # Health check and degradation tests
│       ├── test_camera.py              # Frame acquisition, buffering, and stale rejection tests
│       ├── test_distance_sensor.py     # Range limits, stale detection, and telemetry ingestion tests
│       ├── test_detection.py           # YOLO inference and bounding box positioning tests
│       ├── test_ocr.py                  # PaddleOCR language selection and result parsing tests
│       ├── test_gemini_service.py       # Gemini payload, image context, and fallback tests
│       ├── test_sensor_fusion.py       # Geometric association and peripheral rejection tests
│       ├── test_hazard_engine.py       # Persistence, cooldown, and urgency prioritization tests
│       ├── test_settings.py            # Settings validation and file persistence tests
│       ├── test_events.py              # WebSocket broadcast and slow client handling tests
│       ├── test_api.py                 # Full REST API integration test suite
│       └── test_voice_intents.py       # Hybrid voice intents and Android API contract
│
└── docs/
    ├── architecture.md                 # Detailed system architecture and dataflow
    ├── api-contract.md                 # Complete REST and WebSocket API specifications
    ├── sensor-integration.md           # Sensor wiring, pinouts, and physical integration guide
    ├── setup-windows.md                # Step-by-step PowerShell setup instructions
    └── test-plan.md                    # Verification matrix and evaluation report
```

---

### Quickstart on Windows (PowerShell)

#### 1. Setup Virtual Environment & Dependencies
```powershell
cd "d:\Projects\projects of codex\NAVIS"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
python -m venv backend\.venv
.\backend\.venv\Scripts\Activate.ps1
pip install -r backend\requirements.txt
```

#### 2. Configure Environment
```powershell
Copy-Item .env.example .env
```
*(The backend defaults to diagnostic mock mode if no physical ESP32-CAM is connected).*

When `API_TOKEN` is set in `.env`, enter the same token in Android **Settings**. Configure `GOOGLE_MAPS_API_KEY` in `.env` to enable pedestrian route requests; without it, NAVIS reports routing unavailable instead of showing a sample route.

#### 3. Run Automated Test Suite
```powershell
.\backend\.venv\Scripts\python.exe -m pytest -v
```
All 54 unit and integration tests pass cleanly in the latest local run:
```
======================= 54 passed ========================
```

#### 4. Start Backend Server
```powershell
.\backend\.venv\Scripts\uvicorn.exe backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```

- **Interactive Swagger Docs:** [http://localhost:8000/docs](http://localhost:8000/docs)
- **Health Check:** [http://localhost:8000/health](http://localhost:8000/health)
- **Live Frame Snapshot:** [http://localhost:8000/api/camera/snapshot](http://localhost:8000/api/camera/snapshot)
- **WebSocket Event Stream:** `ws://<laptop_ip>:8000/ws/events`

---

### Hardware Integration Summary

- **Camera:** Set `ESP32_CAMERA_URL` in `.env` to the ESP32-CAM source. The checked-in ESP32 firmware serves JPEG snapshots at `http://<camera-ip>/capture`; NAVIS also supports MJPEG streams such as `http://<camera-ip>:81/stream`.
- **Ranging Sensor:** Supported via HTTP telemetry push (`POST /api/sensors/distance`) from ESP32-CAM or USB serial bridge.
- **Mock Mode:** Enable `DISTANCE_SENSOR_MODE=mock` in `.env` to simulate valid, out-of-range, and stale measurements for reproducible testing.

---

### Safety and Responsible Assistive Use Notice

> [!IMPORTANT]
> NAVIS is an experimental assistive prototype developed for the SAARTHI 2026 Hackathon.
> It is designed to supplement, **never replace**, primary mobility tools such as a white cane, guide dog, or orientation-and-mobility (O&M) training.
> The system does not guarantee obstacle avoidance or detection of stairs, drop-offs, or fast-moving vehicles.
