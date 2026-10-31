"""Main FastAPI application entry point for NAVIS backend.
Registers REST routes, WebSocket event streaming, and lifespan orchestration.
"""

import logging
import json
import threading
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile, WebSocket, WebSocketDisconnect, status
from fastapi.responses import HTMLResponse, Response, StreamingResponse

from backend.app import __version__
from backend.app.camera import get_camera_manager
from backend.app.commands import get_command_router
from backend.app.config import get_config
from backend.app.detection import get_detector_manager
from backend.app.distance_sensor import HTTPTelemetrySensorAdapter, get_distance_sensor_adapter
from backend.app.events import connection_manager, pipeline_instance
from backend.app.logging_config import setup_logging
from backend.app.image_input import read_image_upload
from backend.app.navigation import get_navigation_adapter
from backend.app.ocr import get_ocr_engine
from backend.app.scene_analysis import get_scene_analyzer
from backend.app.schemas import (
    CommandRequest,
    CommandResponse,
    DetectionResponse,
    DeviceStatus,
    DistanceReading,
    HealthResponse,
    LatestPerceptionResponse,
    NavigationProgressRequest,
    NavigationProgressResponse,
    NavigationRouteRequest,
    NavigationRouteResponse,
    OCRResponse,
    SceneAnalysisResponse,
    SensorTelemetryRequest,
    SettingsResponse,
    SettingsUpdate,
    VoiceCommandRequest,
    VoiceCommandResponse,
)
from backend.app.settings import get_settings_manager
from backend.app.security import verify_api_token, verify_websocket_token
from backend.app.gemini_service import GeminiStreamError
from backend.app.voice_assistant import get_voice_assistant
from backend.app.voice.router import router as hybrid_voice_router

logger = logging.getLogger("navis.main")


def _load_detector_in_background(detector) -> None:
    """Load optional vision weights without blocking HTTP server startup."""
    def load() -> None:
        try:
            if detector.load_model():
                logger.info("YOLO detector is ready")
            else:
                logger.warning("YOLO detector is unavailable: %s", detector.load_error)
        except Exception:
            logger.exception("Unexpected error while loading YOLO detector")

    threading.Thread(target=load, name="NavisModelLoader", daemon=True).start()

# ---------------------------------------------------------------------------
# Security helper – simple token check (token stored in .env -> Settings.api_token)
# ---------------------------------------------------------------------------
def verify_token(x_api_token: Optional[str] = Header(None, alias="X-API-Token")) -> bool:
    return verify_api_token(x_api_token)

# ---------------------------------------------------------------------------
# Application lifecycle
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application startup and shutdown orchestration."""
    cfg = get_config()
    setup_logging(log_level=cfg.log_level, events_log_file=cfg.events_log_file)
    logger.info(f"Starting NAVIS backend v{__version__} on {cfg.navis_host}:{cfg.navis_port}")

    # Initialize subsystems
    camera = get_camera_manager(cfg.esp32_camera_url)
    camera.start()

    sensor = get_distance_sensor_adapter()
    sensor.start()

    detector = get_detector_manager(cfg.yolo_model)
    # Vision is optional for text/voice chat. Defer the large YOLO load until a
    # camera is actually connected; uploaded images still load it on demand.
    if cfg.esp32_camera_url and camera.is_connected:
        _load_detector_in_background(detector)

    # OCR initialization is intentionally lazy: PaddleOCR may download models on
    # first use, so it must not prevent health, voice, or WebSocket routes starting.
    get_ocr_engine()

    # Avoid running YOLO repeatedly over synthetic diagnostic frames when no
    # camera source is configured. The REST, voice, health, and WebSocket
    # services remain available; live perception starts when a source is set.
    if cfg.esp32_camera_url:
        pipeline_instance.start()
    else:
        logger.info("No camera source configured; live perception pipeline remains idle")

    yield

    # Shutdown sequence
    logger.info("Shutting down NAVIS backend subsystems...")
    pipeline_instance.stop()
    camera.stop()
    sensor.stop()
    logger.info("NAVIS backend shutdown complete.")

# ---------------------------------------------------------------------------
# FastAPI app creation
# ---------------------------------------------------------------------------
app = FastAPI(
    title="NAVIS Assistive Navigation Backend",
    description="AI-Powered Voice-Interactive Navigation Companion for Blind and Low-Vision Pedestrians",
    version=__version__,
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# Include API routers
# ---------------------------------------------------------------------------
from backend.app.api.detect import router as detect_router
from backend.app.api.hazard_check import router as hazard_check_router
from backend.app.api.web import router as web_router
app.include_router(detect_router)
app.include_router(hazard_check_router)
app.include_router(web_router)
app.include_router(hybrid_voice_router, dependencies=[Depends(verify_token)])

# ---------------------------------------------------------------------------
# Web UI - Voice Assistant Interface
# ---------------------------------------------------------------------------
@app.get("/", response_class=HTMLResponse, tags=["Web UI"])
def get_web_ui() -> HTMLResponse:
    """Serve the NAVIS Voice Assistant interactive web interface."""
    template_candidates = [
        Path("templates/index.html"),
        Path("../templates/index.html"),
        Path(__file__).parent.parent.parent / "templates" / "index.html",
    ]
    for p in template_candidates:
        if p.exists() and p.is_file():
            return HTMLResponse(content=p.read_text(encoding="utf-8"))
    return HTMLResponse(content="<h1>NAVIS Backend Active</h1><p>Visit <a href='/docs'>/docs</a> for API.</p>")

# ---------------------------------------------------------------------------
# Health and Device Status
# ---------------------------------------------------------------------------
@app.get("/health", response_model=HealthResponse, tags=["Diagnostics"])
def get_health() -> HealthResponse:
    """Return comprehensive system health and degradation diagnostics."""
    cfg = get_config()
    camera = get_camera_manager()
    sensor = get_distance_sensor_adapter()
    detector = get_detector_manager()

    degraded = []
    reasons = {}

    camera_configured = bool(cfg.esp32_camera_url)
    camera_available = camera_configured and camera.is_connected
    if camera_available and not detector.is_loaded:
        degraded.append("yolo_model")
        reasons["yolo_model"] = detector.load_error or "Model weights not loaded"

    if camera_configured and not camera_available:
        degraded.append("camera")
        reasons["camera"] = camera.last_error or "Camera stream is not connected"

    assistant_configured = bool(cfg.gemini_api_key.strip())
    if not assistant_configured:
        degraded.append("voice_assistant")
        reasons["voice_assistant"] = "Gemini API key is not configured"

    # Camera-based perception remains usable without ranging hardware. Treat the
    # sensor as a required component only when telemetry mode was explicitly
    # selected; auto and disabled modes support camera-only operation.
    sensor_required = cfg.distance_sensor_mode == "http_telemetry"
    if sensor_required and not sensor.is_connected():
        degraded.append("distance_sensor")
        reasons["distance_sensor"] = "Sensor offline or no telemetry received"

    overall_status = "healthy"
    if degraded:
        if ("yolo_model" in degraded) and ("camera" in degraded):
            overall_status = "unhealthy"
        else:
            overall_status = "degraded"

    return HealthResponse(
        status=overall_status,
        backend_version=__version__,
        model_loaded=detector.is_loaded,
        voice_assistant_configured=assistant_configured,
        model_name=cfg.yolo_model,
        camera_configured=camera_configured,
        camera_connected=camera_available,
        distance_sensor_mode=cfg.distance_sensor_mode,
        distance_sensor_connected=sensor.is_connected(),
        active_processing_mode="diagnostic" if (not camera_available or cfg.distance_sensor_mode == "mock") else "live",
        degraded_components=degraded,
        reasons=reasons,
        timestamp=datetime.now(timezone.utc),
    )

@app.get("/api/device", response_model=DeviceStatus, tags=["Diagnostics"])
def get_device_status() -> DeviceStatus:
    """Return device connection and operational telemetry."""
    camera = get_camera_manager()
    sensor = get_distance_sensor_adapter()
    cfg = get_config()
    reading = sensor.get_latest_reading()
    camera_available = bool(cfg.esp32_camera_url) and camera.is_connected
    state = "running"
    sensor_required = cfg.distance_sensor_mode == "http_telemetry"
    if (bool(cfg.esp32_camera_url) and not camera_available) or (sensor_required and not sensor.is_connected()):
        state = "degraded"
    return DeviceStatus(
        camera_connected=camera_available,
        last_frame_at=camera.last_frame_timestamp,
        frame_width=camera.frame_width,
        frame_height=camera.frame_height,
        fps=camera.fps,
        distance_sensor_connected=sensor.is_connected(),
        distance_sensor_mode=cfg.distance_sensor_mode,
        last_sensor_reading_at=reading.timestamp if reading.status != "disconnected" else None,
        backend_version=__version__,
        operational_state=state,
        timestamp=datetime.now(timezone.utc),
    )


@app.get("/api/perception/latest", response_model=LatestPerceptionResponse, tags=["Vision"], dependencies=[Depends(verify_token)])
def get_latest_perception() -> LatestPerceptionResponse:
    """Return the latest bounded detector and sensor result produced by the live pipeline."""
    return pipeline_instance.latest_perception

# ---------------------------------------------------------------------------
# Camera Endpoints
# ---------------------------------------------------------------------------
@app.get("/api/camera/snapshot", tags=["Camera"])
def get_camera_snapshot(fresh: bool = Query(False, description="Request a freshly acquired frame")) -> Response:
    """Return latest or fresh JPEG camera frame."""
    camera = get_camera_manager()
    frame = camera.get_fresh_frame(timeout_s=2.0) if fresh else camera.get_latest_frame(max_age_s=2.0)
    if frame is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="No valid camera frame is available. Check camera connection.",
        )
    return Response(content=frame.image_bytes, media_type="image/jpeg")

# ---------------------------------------------------------------------------
# Distance Sensor Telemetry
# ---------------------------------------------------------------------------
@app.get("/api/sensors/distance", response_model=DistanceReading, tags=["Sensors"])
def get_sensor_distance() -> DistanceReading:
    """Get the latest normalized distance reading."""
    sensor = get_distance_sensor_adapter()
    return sensor.get_latest_reading()

@app.post("/api/sensors/distance", response_model=DistanceReading, tags=["Sensors"], dependencies=[Depends(verify_token)])
def post_sensor_distance(req: SensorTelemetryRequest) -> DistanceReading:
    """Push telemetry measurement from ESP32-CAM or ranging sensor bridge."""
    sensor = get_distance_sensor_adapter()
    if isinstance(sensor, HTTPTelemetrySensorAdapter):
        return sensor.record_reading(req)
    return sensor.get_latest_reading()

# ---------------------------------------------------------------------------
# OCR Endpoint
# ---------------------------------------------------------------------------
@app.post("/api/ocr", response_model=OCRResponse, tags=["Vision"], dependencies=[Depends(verify_token)])
async def run_ocr(image: Optional[UploadFile] = File(None, description="Optional image upload for OCR. If omitted, uses fresh camera frame.")) -> OCRResponse:
    """Run text recognition on uploaded image or fresh camera frame."""
    ocr = get_ocr_engine()
    camera = get_camera_manager()
    if image is not None:
        contents = await read_image_upload(image)
        return ocr.extract_text(contents)
    frame = camera.get_fresh_frame(timeout_s=2.0)
    if frame is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Cannot perform OCR: Camera frame is unavailable.",
        )
    return ocr.extract_text(frame.image_bgr)

# ---------------------------------------------------------------------------
# Scene Analysis Endpoint
# ---------------------------------------------------------------------------
@app.post("/api/analyze", response_model=SceneAnalysisResponse, tags=["Vision"], dependencies=[Depends(verify_token)])
async def run_scene_analysis(
    prompt: Optional[str] = Query(None, description="Custom scene question"),
    image: Optional[UploadFile] = File(None, description="Optional image. If omitted, uses fresh camera frame."),
) -> SceneAnalysisResponse:
    """Run grounded scene description on uploaded image or fresh camera frame."""
    analyzer = get_scene_analyzer()
    camera = get_camera_manager()
    if image is not None:
        contents = await read_image_upload(image)
        return analyzer.analyze(contents, prompt=prompt)
    frame = camera.get_fresh_frame(timeout_s=2.0)
    if frame is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Cannot analyze scene: Camera frame is unavailable.",
        )
    return analyzer.analyze(frame.image_bgr, prompt=prompt)

# ---------------------------------------------------------------------------
# Voice Commands
# ---------------------------------------------------------------------------
@app.post("/api/command", response_model=CommandResponse, tags=["Voice"], dependencies=[Depends(verify_token)])
def post_voice_command(req: CommandRequest) -> CommandResponse:
    """Process transcribed voice command from Android client."""
    router = get_command_router()
    return router.execute_command(req)

@app.post("/api/voice-command", response_model=VoiceCommandResponse, tags=["Voice"], dependencies=[Depends(verify_token)])
def post_voice_assistant_query(req: VoiceCommandRequest) -> VoiceCommandResponse:
    """Answer conversational questions with Gemini and optional YOLO26 scene context."""
    query = (req.question or req.text or "").strip()
    if not query:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="A voice question or text command is required.",
        )
    assistant = get_voice_assistant()
    result = assistant.generate_response_result(
        question=query,
        history=req.history,
        scene_context=req.scene_context,
        response_style=req.response_style,
    )
    return VoiceCommandResponse(
        ok=result.ok,
        route="VOICE_ASSISTANT",
        response=result.text,
        message=None if result.ok else result.text,
        status=result.status,
    )


@app.post("/api/voice-command/stream", tags=["Voice"], dependencies=[Depends(verify_token)])
async def post_voice_assistant_stream(req: VoiceCommandRequest) -> StreamingResponse:
    """Stream answer deltas so chat text and speech start before Gemini finishes."""
    query = (req.question or req.text or "").strip()
    if not query:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="A voice question or text command is required.",
        )

    assistant = get_voice_assistant()

    async def events():
        try:
            async for delta in assistant.stream_response(
                question=query,
                history=req.history,
                scene_context=req.scene_context,
                response_style=req.response_style,
            ):
                yield f"data: {json.dumps({'type': 'delta', 'text': delta}, ensure_ascii=False)}\n\n"
            yield f"data: {json.dumps({'type': 'done'})}\n\n"
        except GeminiStreamError as exc:
            yield f"data: {json.dumps({'type': 'error', 'text': str(exc)}, ensure_ascii=False)}\n\n"
        except Exception:
            logger.exception("Unexpected failure while streaming the voice assistant response")
            yield f"data: {json.dumps({'type': 'error', 'text': 'NAVIS could not finish that answer. Please try again.'})}\n\n"

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
        },
    )

@app.post("/api/describe-scene", tags=["Voice"], dependencies=[Depends(verify_token)])
async def describe_scene(
    image: UploadFile = File(..., description="Uploaded camera snapshot"),
    question: str = Form("Explain my surroundings"),
    history: Optional[str] = Form(None),
) -> dict:
    """Describe uploaded camera scene for voice assistant with vision grounding."""
    image_bytes = await read_image_upload(image)
    parsed_history = []
    if history:
        try:
            import json
            parsed_history = json.loads(history)
        except Exception:
            parsed_history = []
    detector = get_detector_manager()
    det_res = detector.detect(image_bytes)
    scene_context = [{"label": d.label, "position": d.position, "confidence": d.confidence} for d in det_res.detections[:8]]
    assistant = get_voice_assistant()
    result = assistant.generate_response_result(
        question=question,
        history=parsed_history,
        scene_context=scene_context,
        image_bytes=image_bytes,
    )
    return {
        "ok": result.ok,
        "route": "DESCRIBE_SCENE",
        "response": result.text,
        "status": result.status,
        "detections": scene_context,
    }

@app.post("/api/voice/transcribe", tags=["Voice"], dependencies=[Depends(verify_token)])
async def transcribe_voice_audio(audio: UploadFile = File(..., description="Audio file (WAV, MP3, etc.)")) -> dict:
    """Report server-side STT availability without presenting received bytes as a transcript."""
    contents = await audio.read()
    if not contents:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty audio file.")
    return {
        "ok": False,
        "status": "unavailable",
        "message": "Server-side speech recognition is not configured. Use the phone or browser speech recognizer.",
    }

# ---------------------------------------------------------------------------
# Settings Endpoints
# ---------------------------------------------------------------------------
@app.get("/api/settings", response_model=SettingsResponse, tags=["Settings"])
def get_settings() -> SettingsResponse:
    """Retrieve current validated settings."""
    return get_settings_manager().get_settings()

@app.put("/api/settings", response_model=SettingsResponse, tags=["Settings"], dependencies=[Depends(verify_token)])
def put_settings(update: SettingsUpdate) -> SettingsResponse:
    """Update configurable runtime settings with disk persistence."""
    return get_settings_manager().update_settings(update)

# ---------------------------------------------------------------------------
# Navigation Endpoints
# ---------------------------------------------------------------------------
@app.post("/api/navigation/route", response_model=NavigationRouteResponse, tags=["Navigation"], dependencies=[Depends(verify_token)])
def post_navigation_route(req: NavigationRouteRequest) -> NavigationRouteResponse:
    """Calculate pedestrian route to destination."""
    nav = get_navigation_adapter()
    return nav.calculate_route(req)

@app.post("/api/navigation/progress", response_model=NavigationProgressResponse, tags=["Navigation"], dependencies=[Depends(verify_token)])
def post_navigation_progress(req: NavigationProgressRequest) -> NavigationProgressResponse:
    """Update pedestrian navigation progress with updated GPS coordinates."""
    nav = get_navigation_adapter()
    return nav.track_progress(req)

# ---------------------------------------------------------------------------
# WebSocket Event Stream
# ---------------------------------------------------------------------------
@app.websocket("/ws/events")
async def websocket_events_endpoint(websocket: WebSocket):
    """Real-time normalized event stream for Android client."""
    if not await verify_websocket_token(websocket):
        return
    await connection_manager.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            logger.debug(f"Received client message: {data[:100]}")
    except WebSocketDisconnect:
        await connection_manager.disconnect(websocket)
    except Exception as e:
        logger.warning(f"WebSocket client error: {e}")
        await connection_manager.disconnect(websocket)
