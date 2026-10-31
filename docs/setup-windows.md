# Windows Setup and Execution Guide

## NAVIS Backend | SAARTHI 2026 Hackathon

This guide provides exact PowerShell commands to configure, test, and run the NAVIS backend on a Windows laptop using Python 3.12.

---

### 1. Prerequisites

- Windows 10 or 11 (64-bit)
- Python 3.12 installed and added to PATH (`python --version`)
- Git for Windows
- Wi-Fi network that allows device-to-device communication (no client isolation)

---

### 2. Environment Setup (PowerShell)

Open PowerShell and navigate to the repository directory:

```powershell
# 1. Navigate to the backend directory
cd "d:\Projects\projects of codex\NAVIS\backend"

# 2. Allow local PowerShell script execution for this session (if restricted)
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# 3. Create virtual environment
python -m venv .venv

# 4. Activate virtual environment
.\.venv\Scripts\Activate.ps1

# 5. Install pinned dependencies
python -m pip install --upgrade pip
pip install -r requirements.txt
```

---

### 3. Configuration Setup

Copy the example configuration to a local `.env` file:

```powershell
# From the repository root (or backend folder):
cd "d:\Projects\projects of codex\NAVIS"
Copy-Item .env.example .env
```

Edit `.env` using your text editor (e.g. Notepad, VS Code):

```ini
NAVIS_HOST=0.0.0.0
NAVIS_PORT=8000

# Set to ESP32-CAM snapshot or stream URL (e.g., http://192.168.1.50/capture)
# Leave empty or set to "mock" for diagnostic / testing mode:
ESP32_CAMERA_URL=http://192.168.1.50/capture

# Distance sensor mode: auto, mock, http_telemetry, disabled
DISTANCE_SENSOR_MODE=auto
DISTANCE_SENSOR_MODEL=unknown
DISTANCE_SENSOR_URL=

# Detection and Timing
YOLO_MODEL=yolo26n.pt
YOLO_CONFIDENCE=0.55
FRAME_INTERVAL_MS=500
ALERT_COOLDOWN_S=4.0
SENSOR_MAX_AGE_MS=1000
LOG_LEVEL=INFO

# Gemini assistant and scene analysis (key remains on the backend):
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.8-flash
GEMINI_TIMEOUT_SECONDS=50

# PaddleOCR recognition language from the supplied OCR package:
PADDLEOCR_LANG=hi

# Optional pedestrian routing; routes remain unavailable until configured:
GOOGLE_MAPS_API_KEY=
GOOGLE_MAPS_REGION_CODE=IN

# Optional shared token for Android API and WebSocket authentication:
API_TOKEN=
```

The first OCR request downloads PaddleOCR recognition models for `PADDLEOCR_LANG`. The first backend startup downloads `yolo26n.pt` if it is not already present. Gemini generation can wait up to 50 seconds; the browser and Android client allow up to 60 seconds for the reply.

---

### 4. Running the Backend Server

Start Uvicorn with auto-reload:

```powershell
cd "d:\Projects\projects of codex\NAVIS"
.\backend\.venv\Scripts\uvicorn.exe backend.app.main:app --host 0.0.0.0 --port 8000 --reload
```

Output should show:
```
INFO:     Started server process
INFO:     Waiting for application startup.
INFO:     [navis.main] Starting NAVIS backend v0.1.0 on 0.0.0.0:8000
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8000
```

---

### 5. Verifying Operation

Open your web browser:
1. **Interactive API Documentation:** Navigate to [http://localhost:8000/docs](http://localhost:8000/docs).
2. **Health Check:** Check [http://localhost:8000/health](http://localhost:8000/health).  
   Expect `status: "healthy"` or `status: "degraded"` (if physical camera/sensor is offline).
3. **Camera Snapshot:** Check [http://localhost:8000/api/camera/snapshot](http://localhost:8000/api/camera/snapshot).

---

### 6. Connecting the Android Application

1. **Find Laptop LAN IP Address:**
   ```powershell
   ipconfig
   ```
   Look for `IPv4 Address` under your active Wi-Fi adapter (e.g., `192.168.1.105`).
2. **Configure Android Companion App:**  
   In the Android settings, set the backend URL to:
   ```
   http://192.168.1.105:8000
   ```
   If `API_TOKEN` is set in `.env`, enter that value in the app's **Settings**. Routes use Google Maps Routes API when `GOOGLE_MAPS_API_KEY` is configured.
   WebSocket event endpoint:
   ```
   ws://192.168.1.105:8000/ws/events
   ```
3. **Windows Firewall Rule:**  
   If Android cannot connect, allow port 8000 through the Windows firewall:
   ```powershell
   New-NetFirewallRule -DisplayName "NAVIS Backend Port 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
   ```

---

### 7. Running the Automated Test Suite

Run pytest in PowerShell:

```powershell
cd "d:\Projects\projects of codex\NAVIS"
.\backend\.venv\Scripts\python.exe -m pytest -v
```

Expected result:
```
======================= 54 passed ========================
```

---

### 8. Troubleshooting

| Issue | Cause | Solution |
| :--- | :--- | :--- |
| **`Activate.ps1 cannot be loaded`** | PowerShell script execution disabled. | Run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` in your PowerShell terminal. |
| **Port 8000 already in use** | A dangling process is occupying port 8000. | Run `netstat -ano \| findstr :8000`, locate PID, and terminate it: `taskkill /F /PID <PID>`. |
| **Android connection timed out** | Wi-Fi client isolation or firewall blocking LAN traffic. | Check Windows Firewall rule above; connect laptop and phone to the same mobile hotspot. |
| **ESP32-CAM resets continuously** | Insufficient power supply during Wi-Fi transmission. | Use a dedicated 5V 2A power adapter; avoid powering ESP32-CAM from 3.3V laptop USB pins. |
| **YOLO model download fails** | Offline laptop or restricted network. | `yolo26n.pt` downloads from Ultralytics on first startup. Place the model file in the project root and rerun the backend when offline. |
