"""Offline tests for the Gemini REST adapter."""

import io

from PIL import Image

from backend.app import gemini_service
from backend.app.gemini_service import GeminiService


class FakeResponse:
    is_error = False
    status_code = 200

    def json(self):
        return {
            "candidates": [{
                "content": {"parts": [{"text": "The answer is seven."}]},
                "finishReason": "STOP",
            }]
        }


def test_gemini_service_sends_history_context_and_image(monkeypatch):
    calls = {}

    def fake_post(url, **kwargs):
        calls["url"] = url
        calls.update(kwargs)
        return FakeResponse()

    monkeypatch.setattr(gemini_service.httpx, "post", fake_post)
    image_buffer = io.BytesIO()
    Image.new("RGB", (8, 8), color="white").save(image_buffer, format="PNG")

    service = GeminiService(api_key="test-key", model="gemini-3.8-flash")
    assert service.timeout_s == gemini_service.get_config().gemini_timeout_seconds
    result = service.generate(
        "What is this?",
        history=[{"role": "user", "content": "Earlier question"}],
        scene_context=[{"label": "sign", "position": "center", "confidence": 0.9}],
        image_bytes=image_buffer.getvalue(),
    )

    assert result.ok is True
    assert result.text == "The answer is seven."
    assert calls["url"].endswith("/gemini-3.8-flash:generateContent")
    assert calls["headers"]["x-goog-api-key"] == "test-key"
    contents = calls["json"]["contents"]
    assert contents[0]["role"] == "user"
    assert "Earlier question" in contents[0]["parts"][0]["text"]
    assert "YOLO26 camera detections" in contents[1]["parts"][0]["text"]
    assert contents[1]["parts"][1]["inlineData"]["mimeType"] == "image/jpeg"


def test_gemini_service_reports_missing_key_without_request(monkeypatch):
    def unexpected_request(*args, **kwargs):
        raise AssertionError("Gemini must not be called without a key")

    monkeypatch.setattr(gemini_service.httpx, "post", unexpected_request)
    result = GeminiService(api_key="").generate("Who is the Prime Minister of India?")
    assert result.status == "unavailable"
    assert "GEMINI_API_KEY" in result.text
