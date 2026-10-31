"""Gemini-backed voice assistance grounded with NAVIS YOLO26 detections."""

import logging
from typing import Any, AsyncIterator, Dict, List, Optional

from backend.app.detection import get_detector_manager
from backend.app.gemini_service import GeminiResult, get_gemini_service
from backend.app.response_planner import ResponseStyle

logger = logging.getLogger("navis.voice_assistant")


class GeminiVoiceAssistant:
    """Generate spoken answers and use YOLO26 detections as optional scene context."""

    def generate_response_result(
        self,
        question: str,
        history: Optional[List[Dict[str, Any]]] = None,
        scene_context: Optional[List[Dict[str, Any]]] = None,
        image_bytes: Optional[bytes] = None,
        response_style: ResponseStyle = "auto",
    ) -> GeminiResult:
        context = scene_context
        if image_bytes is not None and context is None:
            try:
                detection_result = get_detector_manager().detect(image_bytes)
                context = [
                    {
                        "label": detection.label,
                        "position": detection.position,
                        "confidence": detection.confidence,
                    }
                    for detection in detection_result.detections[:12]
                ]
            except Exception as exc:
                logger.warning("YOLO26 context unavailable for voice request: %s", type(exc).__name__)
                context = []

        return get_gemini_service().generate(
            question=question,
            history=history,
            scene_context=context,
            image_bytes=image_bytes,
            response_style=response_style,
        )

    async def stream_response(
        self,
        question: str,
        history: Optional[List[Dict[str, Any]]] = None,
        scene_context: Optional[List[Dict[str, Any]]] = None,
        response_style: ResponseStyle = "auto",
    ) -> AsyncIterator[str]:
        """Stream conversational answer text for immediate display and speech."""
        async for delta in get_gemini_service().stream(
            question=question,
            history=history,
            scene_context=scene_context,
            response_style=response_style,
        ):
            yield delta

    def generate_response(
        self,
        question: str,
        history: Optional[List[Dict[str, Any]]] = None,
        scene_context: Optional[List[Dict[str, Any]]] = None,
        image_bytes: Optional[bytes] = None,
        response_style: ResponseStyle = "auto",
    ) -> str:
        """Compatibility wrapper for existing command and scene call sites."""
        return self.generate_response_result(
            question=question,
            history=history,
            scene_context=scene_context,
            image_bytes=image_bytes,
            response_style=response_style,
        ).text


_voice_assistant_instance: Optional[GeminiVoiceAssistant] = None


def get_voice_assistant() -> GeminiVoiceAssistant:
    """Return the shared Gemini voice assistant."""
    global _voice_assistant_instance
    if _voice_assistant_instance is None:
        _voice_assistant_instance = GeminiVoiceAssistant()
    return _voice_assistant_instance
