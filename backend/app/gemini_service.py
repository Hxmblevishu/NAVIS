"""Server-side Gemini client shared by NAVIS voice and scene assistance."""

import base64
import json
import logging
import re
import time
from dataclasses import dataclass
from io import BytesIO
from typing import Any, AsyncIterator, Dict, List, Optional
from urllib.parse import quote

import httpx
from PIL import Image

from backend.app.config import get_config
from backend.app.response_planner import ResponsePlan, ResponseStyle, plan_response

logger = logging.getLogger("navis.gemini")
_GEMINI_API_ROOT = "https://generativelanguage.googleapis.com/v1beta/models"
_DEFAULT_SYSTEM_INSTRUCTION = (
    "You are NAVIS, a concise, helpful, voice-first AI assistant. Answer the user's question directly, "
    "including general knowledge questions, in natural language that sounds clear when spoken aloud. "
    "Use at most three short sentences unless the user asks for detail. If the question depends on "
    "current information you cannot verify, say so. When describing a supplied image, distinguish "
    "visible details from inference. YOLO labels and positions are approximate 2D detections, not "
    "physical distance measurements. Never guarantee that a path, road crossing, or route is safe, "
    "and never invent navigation routes or sensor readings."
)


@dataclass(frozen=True)
class GeminiResult:
    """Normalized outcome from one Gemini generation request."""

    status: str
    text: str

    @property
    def ok(self) -> bool:
        return self.status == "success"


class GeminiStreamError(Exception):
    """Safe user-facing failure from a streamed Gemini generation."""


def _response_text(payload: Dict[str, Any]) -> str:
    """Extract answer text while excluding any model-internal thought parts."""
    candidates = payload.get("candidates", [])
    if not isinstance(candidates, list) or not candidates:
        return ""
    content = candidates[0].get("content", {})
    parts = content.get("parts", []) if isinstance(content, dict) else []
    return " ".join(
        part["text"].strip()
        for part in parts
        if isinstance(part, dict)
        and not part.get("thought", False)
        and isinstance(part.get("text"), str)
        and part["text"].strip()
    )


def _history_contents(
    history: Optional[List[Dict[str, Any]]], *, max_items: int = 8, max_chars: int = 1500
) -> List[Dict[str, Any]]:
    """Convert recent app history to Gemini's user/model content format."""
    contents: List[Dict[str, Any]] = []
    for entry in (history or [])[-max_items:]:
        if not isinstance(entry, dict):
            continue
        role = str(entry.get("role") or entry.get("sender") or "").strip().lower()
        if role in {"assistant", "model", "navis"}:
            gemini_role = "model"
        elif role in {"user", "human"}:
            gemini_role = "user"
        else:
            continue
        text = entry.get("text") or entry.get("content") or entry.get("message")
        if not isinstance(text, str) or not text.strip():
            continue
        contents.append({"role": gemini_role, "parts": [{"text": text.strip()[:max_chars]}]})
    return contents


def _scene_context_text(scene_context: Optional[List[Dict[str, Any]]]) -> str:
    """Format detector facts without treating boxes as physical range measurements."""
    observations = []
    for item in (scene_context or [])[:12]:
        if not isinstance(item, dict):
            continue
        label = item.get("label") or item.get("object_label")
        position = item.get("position") or item.get("image_position")
        if not isinstance(label, str) or not label.strip():
            continue
        observation = label.strip()[:80]
        if isinstance(position, str) and position.strip():
            observation += f" ({position.strip()[:24]})"
        confidence = item.get("confidence") or item.get("object_confidence")
        try:
            if confidence is not None:
                observation += f", confidence {float(confidence):.2f}"
        except (TypeError, ValueError):
            pass
        observations.append(observation)
    if not observations:
        return ""
    return (
        "NAVIS YOLO26 camera detections (approximate 2D labels and image positions only; "
        "they do not provide physical distance): " + "; ".join(observations) + ".\n"
    )


def _image_part(image_bytes: bytes) -> Dict[str, Any]:
    """Normalize supported camera images to a compact JPEG part."""
    with Image.open(BytesIO(image_bytes)) as source:
        normalized = source.convert("RGB")
    output = BytesIO()
    normalized.save(output, format="JPEG", quality=85, optimize=True)
    return {
        "inlineData": {
            "mimeType": "image/jpeg",
            "data": base64.b64encode(output.getvalue()).decode("ascii"),
        }
    }


class GeminiService:
    """Small HTTPX adapter for Gemini generateContent; API keys stay in headers."""

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None, timeout_s: Optional[float] = None):
        cfg = get_config()
        self.api_key = (api_key if api_key is not None else cfg.gemini_api_key).strip()
        self.model = (model or cfg.gemini_model).strip()
        self.quick_model = cfg.gemini_quick_model.strip()
        self.timeout_s = cfg.gemini_timeout_seconds if timeout_s is None else timeout_s

    def _select_model(self, plan: ResponsePlan, image_bytes: Optional[bytes]) -> str:
        """Use Flash-Lite for short text turns, retaining Flash for vision/deep work."""
        if plan.style == "quick" and image_bytes is None:
            return self.quick_model
        return self.model

    def generate(
        self,
        question: str,
        *,
        history: Optional[List[Dict[str, Any]]] = None,
        scene_context: Optional[List[Dict[str, Any]]] = None,
        image_bytes: Optional[bytes] = None,
        system_instruction: Optional[str] = None,
        max_output_tokens: Optional[int] = None,
        response_style: ResponseStyle = "auto",
    ) -> GeminiResult:
        query = question.strip()
        if not query:
            return GeminiResult("error", "Please ask a question.")
        if not self.api_key:
            return GeminiResult(
                "unavailable",
                "Gemini assistance is not configured. Add GEMINI_API_KEY to the backend environment and restart NAVIS.",
            )
        try:
            plan, payload = self._build_payload(
                query,
                history=history,
                scene_context=scene_context,
                image_bytes=image_bytes,
                system_instruction=system_instruction,
                max_output_tokens=max_output_tokens,
                response_style=response_style,
            )
        except Exception as exc:
            logger.warning("Gemini request preparation failed: %s", type(exc).__name__)
            return GeminiResult("error", "I could not prepare that request. Please try again.")

        model = self._select_model(plan, image_bytes)
        if not re.fullmatch(r"[A-Za-z0-9._-]+", model):
            logger.error("Gemini model name contains invalid characters")
            return GeminiResult("error", "Gemini is unavailable because its model configuration is invalid.")

        endpoint = f"{_GEMINI_API_ROOT}/{quote(model, safe='._-')}:generateContent"
        try:
            response = httpx.post(
                endpoint,
                headers={"x-goog-api-key": self.api_key, "Content-Type": "application/json"},
                json=payload,
                timeout=self.timeout_s,
            )
            if response.is_error:
                logger.warning("Gemini request failed with HTTP %s", response.status_code)
                return GeminiResult("unavailable", "Gemini could not answer right now. Please try again.")
            body = response.json()
        except httpx.TimeoutException:
            logger.warning("Gemini request timed out")
            return GeminiResult("unavailable", "Gemini is taking too long to respond. Please try again.")
        except httpx.HTTPError as exc:
            logger.warning("Gemini connection failed: %s", type(exc).__name__)
            return GeminiResult("unavailable", "Gemini could not be reached. Check the backend internet connection.")
        except (ValueError, TypeError) as exc:
            logger.warning("Gemini returned an invalid response: %s", type(exc).__name__)
            return GeminiResult("error", "Gemini returned an unreadable response. Please try again.")

        try:
            answer = _response_text(body)
        except (AttributeError, IndexError, TypeError):
            answer = ""

        if not answer:
            logger.warning("Gemini response contained no text candidate")
            return GeminiResult("unavailable", "Gemini did not return an answer. Please rephrase and try again.")
        return GeminiResult("success", answer[:6000])

    def _build_payload(
        self,
        question: str,
        *,
        history: Optional[List[Dict[str, Any]]],
        scene_context: Optional[List[Dict[str, Any]]],
        image_bytes: Optional[bytes],
        system_instruction: Optional[str],
        max_output_tokens: Optional[int],
        response_style: ResponseStyle,
    ) -> tuple[Any, Dict[str, Any]]:
        plan = plan_response(
            question,
            requested_style=response_style,
            base_instruction=system_instruction or _DEFAULT_SYSTEM_INSTRUCTION,
        )
        user_text = _scene_context_text(scene_context) + question[:4000]
        user_parts: List[Dict[str, Any]] = [{"text": user_text}]
        if image_bytes:
            user_parts.append(_image_part(image_bytes))

        if plan.style == "quick":
            # Keep the quick path's prompt small as chats grow; the question itself
            # remains intact while older turns are represented by fewer tokens.
            contents = _history_contents(history, max_items=4, max_chars=600)
        else:
            contents = _history_contents(history, max_items=8, max_chars=1500)
        contents.append({"role": "user", "parts": user_parts})
        output_limit = plan.max_output_tokens
        if max_output_tokens is not None:
            output_limit = min(output_limit, max(32, int(max_output_tokens)))

        return plan, {
            "systemInstruction": {"parts": [{"text": plan.system_instruction}]},
            "contents": contents,
            "generationConfig": {
                "maxOutputTokens": output_limit,
                "thinkingConfig": {"thinkingLevel": plan.thinking_level},
            },
        }

    async def stream(
        self,
        question: str,
        *,
        history: Optional[List[Dict[str, Any]]] = None,
        scene_context: Optional[List[Dict[str, Any]]] = None,
        image_bytes: Optional[bytes] = None,
        system_instruction: Optional[str] = None,
        response_style: ResponseStyle = "auto",
    ) -> AsyncIterator[str]:
        """Yield answer deltas from Gemini's SSE stream as soon as they arrive."""
        query = question.strip()
        if not query:
            raise GeminiStreamError("Please ask a question.")
        if not self.api_key:
            raise GeminiStreamError(
                "Gemini assistance is not configured. Add GEMINI_API_KEY to the backend environment."
            )
        if not re.fullmatch(r"[A-Za-z0-9._-]+", self.model):
            raise GeminiStreamError("Gemini is unavailable because its model configuration is invalid.")

        try:
            plan, payload = self._build_payload(
                query,
                history=history,
                scene_context=scene_context,
                image_bytes=image_bytes,
                system_instruction=system_instruction,
                max_output_tokens=None,
                response_style=response_style,
            )
        except Exception as exc:
            logger.warning("Gemini stream preparation failed: %s", type(exc).__name__)
            raise GeminiStreamError("I could not prepare that request. Please try again.") from exc

        model = self._select_model(plan, image_bytes)
        if not re.fullmatch(r"[A-Za-z0-9._-]+", model):
            raise GeminiStreamError("Gemini is unavailable because its model configuration is invalid.")

        started_at = time.perf_counter()
        first_text_ms: Optional[float] = None
        endpoint = f"{_GEMINI_API_ROOT}/{quote(model, safe='._-')}:streamGenerateContent"
        received_text = False
        try:
            timeout = httpx.Timeout(self.timeout_s)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    endpoint,
                    params={"alt": "sse"},
                    headers={"x-goog-api-key": self.api_key, "Content-Type": "application/json"},
                    json=payload,
                ) as response:
                    if response.is_error:
                        logger.warning("Gemini stream failed with HTTP %s", response.status_code)
                        raise GeminiStreamError("Gemini could not answer right now. Please try again.")

                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if not data or data == "[DONE]":
                            continue
                        try:
                            event = json.loads(data)
                        except json.JSONDecodeError:
                            logger.warning("Gemini stream returned an invalid event")
                            continue
                        delta = _response_text(event)
                        if delta:
                            if first_text_ms is None:
                                first_text_ms = (time.perf_counter() - started_at) * 1000.0
                                logger.info(
                                    "Gemini first text: model=%s style=%s ttfb_ms=%.0f",
                                    model,
                                    plan.style,
                                    first_text_ms,
                                )
                            received_text = True
                            yield delta
        except GeminiStreamError:
            raise
        except httpx.TimeoutException as exc:
            logger.warning("Gemini stream timed out: model=%s elapsed_ms=%.0f", model, (time.perf_counter() - started_at) * 1000.0)
            raise GeminiStreamError("Gemini is taking too long to respond. Please try again.") from exc
        except httpx.HTTPError as exc:
            logger.warning(
                "Gemini stream connection failed: model=%s error=%s elapsed_ms=%.0f",
                model,
                type(exc).__name__,
                (time.perf_counter() - started_at) * 1000.0,
            )
            raise GeminiStreamError("Gemini could not be reached. Check the backend internet connection.") from exc

        if not received_text:
            logger.warning(
                "Gemini stream returned no text: model=%s elapsed_ms=%.0f",
                model,
                (time.perf_counter() - started_at) * 1000.0,
            )
            raise GeminiStreamError("Gemini did not return an answer. Please rephrase and try again.")
        logger.info(
            "Gemini stream complete: model=%s style=%s ttfb_ms=%.0f total_ms=%.0f",
            model,
            plan.style,
            first_text_ms or 0.0,
            (time.perf_counter() - started_at) * 1000.0,
        )


_gemini_service_instance: Optional[GeminiService] = None


def get_gemini_service() -> GeminiService:
    """Return the configured Gemini service singleton."""
    global _gemini_service_instance
    if _gemini_service_instance is None:
        _gemini_service_instance = GeminiService()
    return _gemini_service_instance
