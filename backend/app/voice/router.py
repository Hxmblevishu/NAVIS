"""HTTP adapter for local-first voice command intent resolution."""

import re
from typing import Any, Dict, Optional

from fastapi import APIRouter

from backend.app.schemas import (
    CommandRequest,
    HybridVoiceCommandRequest,
    HybridVoiceCommandResponse,
)
from backend.app.voice.intent_engine import NavisIntentEngine
from backend.app.voice.intents import INTENTS
from backend.app.voice.normalizer import normalize_command

router = APIRouter(prefix="/api/voice", tags=["Hybrid Voice"])
engine = NavisIntentEngine()
_DESTINATION_PATTERN = re.compile(
    r"\b(?:navigate|directions|route|take me|go)\s+(?:to|toward|towards)\s+(.+)$",
    re.IGNORECASE,
)
_GENERIC_DESTINATIONS = {"somewhere", "a place", "a location", "home"}


def _unknown_response() -> HybridVoiceCommandResponse:
    return HybridVoiceCommandResponse(
        success=False,
        intent="UNKNOWN",
        action="NONE",
        confidence=0.0,
        method="none",
        message="I didn't understand that command. Please repeat it or ask a question.",
    )


def resolve_voice_command(text: str) -> HybridVoiceCommandResponse:
    """Classify text and return an action for Kotlin; never performs UI operations."""
    prediction = engine.predict(text)
    if prediction is None:
        return _unknown_response()

    intent = prediction["intent"]
    metadata = INTENTS[intent]
    parameters: Dict[str, Any] = dict(prediction.get("parameters", {}))
    message = metadata["message"]

    if intent == "OPEN_NAVIGATION":
        match = _DESTINATION_PATTERN.search(normalize_command(text))
        destination = match.group(1).strip() if match else str(parameters.get("destination", "")).strip()
        if destination and destination not in _GENERIC_DESTINATIONS:
            parameters["destination"] = destination
            message = f"Opening pedestrian navigation to {destination}."

    if intent == "CHECK_STATUS":
        from backend.app.commands import get_command_router

        status_result = get_command_router().execute_command(
            CommandRequest(command="status", text="system status")
        )
        message = status_result.message
        parameters["status"] = status_result.data or {}

    return HybridVoiceCommandResponse(
        success=True,
        intent=intent,
        action=prediction["action"],
        confidence=round(float(prediction["confidence"]), 3),
        method=prediction["method"],
        message=message,
        requires_confirmation=bool(metadata.get("requires_confirmation", False)),
        parameters=parameters,
    )


@router.post("/command", response_model=HybridVoiceCommandResponse)
def process_voice_command(request: HybridVoiceCommandRequest) -> HybridVoiceCommandResponse:
    """Resolve a transcribed command into an approved action for the Android client."""
    return resolve_voice_command(request.text)
