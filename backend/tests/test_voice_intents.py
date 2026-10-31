"""Local intent and API contract tests for the hybrid voice assistant."""

from fastapi.testclient import TestClient

from backend.app.config import get_config
from backend.app.main import app
from backend.app.voice.intent_engine import NavisIntentEngine
from backend.app.voice.intents import INTENTS
from backend.app.voice.router import resolve_voice_command


def test_every_documented_example_resolves_to_its_intent():
    engine = NavisIntentEngine()
    for intent, metadata in INTENTS.items():
        for example in metadata["examples"]:
            prediction = engine.predict(example)
            assert prediction is not None, example
            assert prediction["intent"] == intent, example
            assert prediction["method"] == "rule", example


def test_paraphrase_and_wake_word_resolve_with_nlp():
    prediction = NavisIntentEngine().predict("Hey NAVIS, please launch the camera")
    assert prediction is not None
    assert prediction["intent"] == "OPEN_CAMERA"
    assert prediction["method"] == "nlp"


def test_general_question_is_left_for_gemini():
    response = resolve_voice_command("Who is the Prime Minister of India?")
    assert response.success is False
    assert response.intent == "UNKNOWN"
    assert response.action == "NONE"
    assert response.method == "none"

    for question in (
        "What is the weather in Delhi?",
        "What time is it?",
        "Tell me a joke.",
        "What are you doing?",
    ):
        assert resolve_voice_command(question).success is False, question


def test_supplied_natural_phrases_map_to_the_intended_actions():
    expected = {
        "Can you show me what's around?": "START_DETECTION",
        "Tell me what is ahead": "START_DETECTION",
        "Take me back to the beginning": "GO_HOME",
        "I can't read this, help me": "OPEN_OCR",
    }
    for phrase, intent in expected.items():
        response = resolve_voice_command(phrase)
        assert response.success is True, phrase
        assert response.action == intent, phrase


def test_navigation_extracts_destination_without_starting_route():
    response = resolve_voice_command("Hey NAVIS, navigate to Connaught Place")
    assert response.success is True
    assert response.action == "OPEN_NAVIGATION"
    assert response.parameters["destination"] == "connaught place"

    natural = resolve_voice_command("Could you take me to the airport?")
    assert natural.success is True
    assert natural.action == "OPEN_NAVIGATION"
    assert natural.parameters["destination"] == "the airport"


def test_detection_requires_confirmation():
    response = resolve_voice_command("start object detection")
    assert response.success is True
    assert response.action == "START_DETECTION"
    assert response.requires_confirmation is True


def test_voice_command_endpoint_returns_the_android_contract():
    config = get_config()
    headers = {"X-API-Token": config.api_token} if config.api_token else {}
    response = TestClient(app, headers=headers).post(
        "/api/voice/command", json={"text": "open the text reader"}
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    assert payload["action"] == "OPEN_OCR"
    assert payload["parameters"]["screen"] == "read_text"
    assert set(payload) == {
        "success", "intent", "action", "confidence", "method", "message",
        "requires_confirmation", "parameters",
    }
