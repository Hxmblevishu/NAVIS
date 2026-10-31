"""Same-origin endpoints used by the browser dashboard without exposing client tokens."""

import json
import logging
import re
from typing import Any, Dict, Optional

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile, status

from backend.app.api.hazard_check import analyze_hazard_request
from backend.app.commands import get_command_router
from backend.app.image_input import read_image_upload
from backend.app.schemas import CommandRequest, VoiceCommandRequest
from backend.app.security import require_same_origin
from backend.app.voice_assistant import get_voice_assistant

logger = logging.getLogger("navis.web_api")
router = APIRouter(prefix="/api/web", tags=["Browser dashboard"])
_NAVIGATION_REQUEST = re.compile(
    r"\b(navigate|navigation|directions|take me|go to|route to|how do i get to)\b",
    re.IGNORECASE,
)


@router.post("/hazard-check")
async def dashboard_hazard_check(
    request: Request,
    image: Optional[UploadFile] = File(None, description="Optional camera frame"),
) -> Dict[str, Any]:
    require_same_origin(request)
    return await analyze_hazard_request(request, image)


@router.post("/voice-command")
def dashboard_voice_command(request: Request, payload: VoiceCommandRequest) -> Dict[str, Any]:
    require_same_origin(request)
    question = (payload.question or payload.text or "").strip()
    if not question:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="A voice question is required.")

    if _NAVIGATION_REQUEST.search(question):
        result = get_command_router().execute_command(
            CommandRequest(text=question, location=payload.location)
        )
        return {
            "ok": True,
            "route": "NAVIGATION" if result.intent == "navigate" else "VOICE_COMMAND",
            "response": result.message,
            "data": result.data,
            "status": result.status,
        }

    result = get_voice_assistant().generate_response_result(
        question=question,
        history=payload.history,
        scene_context=payload.scene_context,
    )
    return {
        "ok": result.ok,
        "route": "VOICE_ASSISTANT",
        "response": result.text,
        "status": result.status,
    }


@router.post("/describe-scene")
async def dashboard_describe_scene(
    request: Request,
    image: UploadFile = File(..., description="Camera frame to describe"),
    question: str = Form("Explain my surroundings"),
    history: Optional[str] = Form(None),
) -> Dict[str, Any]:
    require_same_origin(request)
    image_bytes = await read_image_upload(image)
    try:
        parsed_history = json.loads(history) if history else []
        if not isinstance(parsed_history, list):
            parsed_history = []
    except (json.JSONDecodeError, TypeError):
        parsed_history = []

    try:
        from backend.app.detection import get_detector_manager

        detection_result = get_detector_manager().detect(image_bytes)
        scene_context = [
            {"label": item.label, "position": item.position, "confidence": item.confidence}
            for item in detection_result.detections[:8]
        ]
        result = get_voice_assistant().generate_response_result(
            question=question,
            history=parsed_history,
            scene_context=scene_context,
            image_bytes=image_bytes,
        )
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Dashboard scene analysis failed (%s)", type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Scene analysis is currently unavailable.",
        ) from exc

    return {
        "ok": result.ok,
        "route": "DESCRIBE_SCENE",
        "response": result.text,
        "status": result.status,
        "detections": scene_context,
    }
