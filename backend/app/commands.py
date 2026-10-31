"""Voice command routing module for NAVIS.
Deterministic intent classifier and router for Android voice interactions.
"""
import logging
import re
from typing import Optional
from backend.app.camera import get_camera_manager
from backend.app.config import get_config
from backend.app.distance_sensor import get_distance_sensor_adapter
from backend.app.ocr import get_ocr_engine
from backend.app.navigation import get_navigation_adapter
from backend.app.scene_analysis import get_scene_analyzer
from backend.app.schemas import CommandRequest, CommandResponse, NavigationRouteRequest
from backend.app.voice_assistant import get_voice_assistant

logger = logging.getLogger("navis.commands")

# Regular expression patterns for deterministic intent recognition
_DESCRIBE_PATTERN = re.compile(
    r"\b(describe|surroundings?|around me|what do you see|what is visible|what(?:'s| is) in front of me|look|where is|clear|safe|path|aas paas|saamne)\b",
    re.IGNORECASE,
)
_READ_TEXT_PATTERN = re.compile(
    r"\b(read|ocr|what does (?:it|this) say|read the sign|read this text|read the label)\b",
    re.IGNORECASE,
)
_NAVIGATE_PATTERN = re.compile(
    r"\b(navigate|take me to|directions to|route to|how do i get to|go to)\b(?:\s+(.+))?",
    re.IGNORECASE,
)
_REPEAT_PATTERN = re.compile(
    r"\b(repeat|say that again|what did you say|say again|pardon)\b",
    re.IGNORECASE,
)
_CANCEL_PATTERN = re.compile(
    r"\b(cancel|stop|quiet|silence|shut up|nevermind)\b",
    re.IGNORECASE,
)
_STATUS_PATTERN = re.compile(
    r"\b(status|system status|battery|connectivity|how are you|are you working)\b",
    re.IGNORECASE,
)

# State tracking for repetition
_last_spoken_message: str = "NAVIS is ready. Ask for a system status check when needed."


def update_last_spoken(message: str) -> None:
    """Record the latest spoken message for the 'repeat' command."""
    global _last_spoken_message
    if message and message.strip():
        _last_spoken_message = message.strip()


class CommandRouter:
    """Routes voice transcribed commands to appropriate subsystems."""

    def classify_intent(self, text: str) -> str:
        """Classify input text into a supported intent."""
        cleaned = text.strip()
        if not cleaned:
            return "unknown"
        if _REPEAT_PATTERN.search(cleaned):
            return "repeat"
        if _CANCEL_PATTERN.search(cleaned):
            return "cancel"
        if _STATUS_PATTERN.search(cleaned):
            return "status"
        if _READ_TEXT_PATTERN.search(cleaned):
            return "read_text"
        if _DESCRIBE_PATTERN.search(cleaned):
            return "describe"
        if _NAVIGATE_PATTERN.search(cleaned):
            return "navigate"
        return "unknown"

    def execute_command(self, req: CommandRequest) -> CommandResponse:
        """Execute command based on explicit command field or classified text."""
        global _last_spoken_message
        intent = req.command or self.classify_intent(req.text)

        if intent == "repeat":
            return CommandResponse(
                intent="repeat",
                status="success",
                message=_last_spoken_message,
                data={"repeated_message": _last_spoken_message},
            )

        if intent == "cancel":
            msg = "Cancelled."
            update_last_spoken(msg)
            return CommandResponse(
                intent="cancel",
                status="cancelled",
                message=msg,
            )

        if intent == "status":
            camera = get_camera_manager()
            sensor = get_distance_sensor_adapter()
            cam_str = "online" if camera.is_connected else "disconnected"
            sensor_str = "online" if sensor.is_connected() else "unconnected"
            msg = f"System status: camera is {cam_str}. Distance sensor is {sensor_str}. Navigation ready."
            update_last_spoken(msg)
            return CommandResponse(
                intent="status",
                status="success",
                message=msg,
                data={
                    "camera_connected": camera.is_connected,
                    "sensor_connected": sensor.is_connected(),
                },
            )

        if intent == "describe":
            camera = get_camera_manager()
            frame = camera.get_fresh_frame(timeout_s=1.5)
            if frame is None:
                frame = camera.get_latest_frame(max_age_s=3.0)

            assistant = get_voice_assistant()
            if frame is None:
                msg = "The camera view is unavailable. I cannot describe the surroundings right now."
                update_last_spoken(msg)
                return CommandResponse(intent="describe", status="unavailable", message=msg)
            img_bytes = frame.image_bytes if frame else None
            generation = assistant.generate_response_result(
                question=req.text or "describe surroundings",
                image_bytes=img_bytes,
            )
            msg = generation.text
            update_last_spoken(msg)
            return CommandResponse(
                intent="describe",
                status="success" if generation.ok else "unavailable",
                message=msg,
                data={
                    "spoken_response": msg,
                    "model": get_config().yolo_model,
                    "assistant_model": get_config().gemini_model,
                },
            )

        if intent == "read_text":
            camera = get_camera_manager()
            frame = camera.get_fresh_frame(timeout_s=2.0)
            if frame is None:
                msg = "Cannot read text. Camera frame is unavailable."
                update_last_spoken(msg)
                return CommandResponse(
                    intent="read_text",
                    status="unavailable",
                    message=msg,
                )
            ocr = get_ocr_engine()
            res = ocr.extract_text(frame.image_bgr)
            if res.status == "success":
                msg = f"Sign reads: {res.text}"
            elif res.status == "no_text_found":
                msg = "No readable text detected in this frame."
            elif res.status == "blurred":
                msg = "The image is too blurry to read. Please hold still and try again."
            else:
                msg = "Text reading is currently unavailable on this system."
            update_last_spoken(msg)
            return CommandResponse(
                intent="read_text",
                status=res.status,
                message=msg,
                data=res.model_dump(),
            )

        if intent == "navigate":
            match = _NAVIGATE_PATTERN.search(req.text)
            dest = match.group(2).strip() if (match and match.group(2)) else ""
            if not dest:
                msg = "Where would you like to navigate?"
                update_last_spoken(msg)
                return CommandResponse(
                    intent="navigate",
                    status="destination_required",
                    message=msg,
                )
            if req.location is None:
                msg = f"Cannot calculate route to {dest}. Phone GPS location was not provided."
                update_last_spoken(msg)
                return CommandResponse(
                    intent="navigate",
                    status="location_required",
                    message=msg,
                    data={"destination": dest},
                )
            route = get_navigation_adapter().calculate_route(
                NavigationRouteRequest(origin=req.location, destination=dest)
            )
            command_status = {
                "active": "success",
                "ambiguous_destination": "clarification_needed",
                "unavailable": "unavailable",
                "failed": "unavailable",
            }[route.status]
            msg = route.message
            update_last_spoken(msg)
            return CommandResponse(
                intent="navigate",
                status=command_status,
                message=msg,
                data={"route": route.model_dump(mode="json")},
            )

        # Unknown intent
        msg = "I didn't understand that command. You can say: describe, read sign, navigate to a destination, or status."
        update_last_spoken(msg)
        return CommandResponse(
            intent="unknown",
            status="clarification_needed",
            message=msg,
        )


_command_router_instance: Optional[CommandRouter] = None


def get_command_router() -> CommandRouter:
    """Retrieve singleton CommandRouter."""
    global _command_router_instance
    if _command_router_instance is None:
        _command_router_instance = CommandRouter()
    return _command_router_instance
