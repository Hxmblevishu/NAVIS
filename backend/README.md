# NAVIS Cloud Backend & Detection Service

This backend powers the AI-driven assistive perception engine for NAVIS. It runs on FastAPI, serving WebSocket event streams, sensor telemetry, and an authenticated high-performance `/api/detect` endpoint for offloading heavy YOLO object detection from mobile devices (Option 2 Architecture).

---

## Features

- **Authenticated Remote YOLO26 Detection (`/api/detect`)**: Runs Ultralytics `yolo26n.pt` and protects server resources with an `X-API-Token` header.
- **Evidence-Based Hazard Fusion**: Merges 2D bounding boxes with VL53L0X Time-of-Flight ranging.
- **Gemini Voice & Scene Assistance**: Answers general questions with Gemini and supplies YOLO26 context for visual questions.
- **Hybrid Voice Intent Routing**: Resolves supported UI commands with rules and local character TF-IDF; sends only unknown conversational questions to Gemini.
- **Multilingual OCR**: Uses the supplied PaddleOCR 3.x engine configuration; `PADDLEOCR_LANG` selects the recognition language.
- **Turn-by-Turn Navigation**: Pedestrian routing API for visually impaired wayfinding.
- **Real Route Provider**: Uses the Google Maps Routes API when `GOOGLE_MAPS_API_KEY` is configured; reports routing unavailable when it is not.
- **Docker & Cloud Run Ready**: Containerized deployment with minimal footprint.

---

## Configuration (`.env`)

Create a `.env` file in the project root:

```env
API_TOKEN=your_secure_random_api_token_here
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-3.8-flash
GEMINI_TIMEOUT_SECONDS=50
YOLO_MODEL=yolo26n.pt
PADDLEOCR_LANG=hi
GOOGLE_MAPS_API_KEY=your_google_maps_routes_api_key_here
GOOGLE_MAPS_REGION_CODE=IN
NAVIS_SPEECH_LOCALE=en-IN
DISTANCE_SENSOR_MODE=auto
DISTANCE_SENSOR_MODEL=VL53L0X
```

The Gemini key stays on the backend and is never sent to Android or the browser. If it is unset or unavailable, voice requests return an explicit spoken message. Gemini requests allow up to 50 seconds, and the Android client allows 60 seconds so the response is not cut off early. PaddleOCR downloads its recognition models on first OCR initialization; `PADDLEOCR_LANG` selects the language (Hindi by default, matching the supplied OCR project). The API token is required for Android REST and WebSocket requests when `API_TOKEN` is set; enter the same value in Android Settings. If no `GOOGLE_MAPS_API_KEY` is configured, routes report unavailable.

`DISTANCE_SENSOR_MODE=auto` supports camera-only operation when no distance sensor is connected. NAVIS continues visual detection and reports obstacles without inventing range estimates; the health endpoint does not mark the optional sensor as a backend failure. Use `http_telemetry` when a sensor feed is expected and should be reported as degraded if it is missing. The Android dashboard shows that range data is unavailable.

---

## Local Development

```bash
# 1. Create and activate virtual environment
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate

# 2. Install dependencies
pip install -r backend/requirements.txt

# 3. Start development server
uvicorn backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## Docker Build & Run

```bash
# Build Docker image
docker build -f backend/Dockerfile -t navis-backend:latest .

# Run container locally
docker run -d \
  -p 8000:8000 \
  -e API_TOKEN="your_secure_random_api_token_here" \
  --name navis-service \
  navis-backend:latest
```

---

## Deployment to Google Cloud Run

```bash
# 1. Set Google Cloud project
gcloud config set project YOUR_PROJECT_ID

# 2. Build and submit image to Google Artifact Registry
gcloud builds submit --tag gcr.io/YOUR_PROJECT_ID/navis-backend:latest -f backend/Dockerfile .

# 3. Deploy to Cloud Run
gcloud run deploy navis-backend \
  --image gcr.io/YOUR_PROJECT_ID/navis-backend:latest \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --set-env-vars API_TOKEN="your_secure_random_api_token_here"
```

---

## API Testing with curl

### Hybrid Voice Commands
```bash
curl -X POST http://localhost:8000/api/voice/command \
  -H "Content-Type: application/json" \
  -H "X-API-Token: your_secure_random_api_token_here" \
  -d '{"text":"open the text reader"}'
```

Android uses this endpoint to resolve navigation and screen actions. General questions return `UNKNOWN` and are sent to `/api/voice-command` for Gemini. Detection requires on-device confirmation before it starts.

### 1. Test Without Token (Should Return 401 Unauthorized)
```bash
curl -i -X POST http://localhost:8000/api/detect \
  -F "image=@sample.jpg"
```

### 2. Test With Valid Token
```bash
curl -i -X POST http://localhost:8000/api/detect \
  -H "X-API-Token: your_secure_random_api_token_here" \
  -F "image=@sample.jpg"
```

