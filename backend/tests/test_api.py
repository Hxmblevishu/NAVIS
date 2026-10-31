"""Integration tests for all NAVIS REST API endpoints."""
import io
import pytest
from types import SimpleNamespace
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.config import get_config
from PIL import Image


class FakeVoiceAssistant:
    """Keep integration tests offline while exercising the voice API contract."""

    def generate_response_result(self, question, **kwargs):
        query = question.lower()
        if "prime minister" in query:
            text = "The Prime Minister of India is Narendra Modi."
        elif "path clear" in query or "clear" in query:
            text = "I cannot confirm that a path is clear from this request."
        elif "hello" in query:
            text = "Hello! I am NAVIS, your Gemini-powered voice assistant."
        else:
            text = "System status: camera is disconnected. Distance sensor is unconnected."
        return SimpleNamespace(ok=True, status="success", text=text)

    def generate_response(self, question, **kwargs):
        return self.generate_response_result(question, **kwargs).text


@pytest.fixture(scope="module")
def client():
    headers = {"X-API-Token": get_config().api_token} if get_config().api_token else {}
    with TestClient(app, headers=headers) as c:
        yield c


def _encode_polyline(points):
    encoded = []
    last_latitude = last_longitude = 0
    for latitude, longitude in points:
        for value, is_latitude in ((round(latitude * 1e5), True), (round(longitude * 1e5), False)):
            previous = last_latitude if is_latitude else last_longitude
            delta = value - previous
            if is_latitude:
                last_latitude = value
            else:
                last_longitude = value
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                encoded.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            encoded.append(chr(value + 63))
    return "".join(encoded)


def _create_test_image_bytes():
    """Create a minimal valid JPEG image in memory for testing."""
    img = Image.new("RGB", (320, 240), color=(100, 150, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_api_health(client):
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json()["backend_version"] == "0.1.0"


def test_api_device(client):
    res = client.get("/api/device")
    assert res.status_code == 200
    data = res.json()
    assert "camera_connected" in data
    assert "distance_sensor_connected" in data
    assert "operational_state" in data


def test_api_camera_snapshot(client):
    res = client.get("/api/camera/snapshot")
    assert res.status_code in (200, 503)
    if res.status_code == 200:
        assert res.headers["content-type"] == "image/jpeg"
        assert len(res.content) > 100


def test_api_detect_upload(client):
    img_bytes = _create_test_image_bytes()
    files = {"image": ("test.jpg", img_bytes, "image/jpeg")}
    res = client.post("/api/detect", files=files)
    assert res.status_code == 200
    data = res.json()
    assert "detections" in data
    assert "inference_ms" in data
    assert data["model_status"] in ("inference_complete", "model_unavailable")


def test_api_sensors_distance_lifecycle(client):
    # GET distance
    res_get = client.get("/api/sensors/distance")
    assert res_get.status_code == 200
    assert "distance_m" in res_get.json()

    # POST telemetry
    payload = {
        "sensor_id": "esp32_sensor_0",
        "sensor_model": "VL53L0X",
        "distance_m": 1.45,
        "valid": True,
        "quality": 0.95,
    }
    res_post = client.post("/api/sensors/distance", json=payload)
    assert res_post.status_code == 200
    data = res_post.json()
    assert data["valid"] is True
    assert data["distance_m"] == 1.45


def test_api_command_endpoints(client):
    # 1. Status command
    res = client.post("/api/command", json={"text": "What is the status?"})
    assert res.status_code == 200
    assert res.json()["intent"] == "status"
    assert "status:" in res.json()["message"].lower()

    # 2. Repeat command
    res = client.post("/api/command", json={"text": "Repeat that"})
    assert res.status_code == 200
    assert res.json()["intent"] == "repeat"

    # 3. Cancel command
    res = client.post("/api/command", json={"text": "Cancel speaking"})
    assert res.status_code == 200
    assert res.json()["intent"] == "cancel"

    # 4. Navigate without destination -> requires destination
    res = client.post("/api/command", json={"text": "Take me to"})
    assert res.status_code == 200
    assert res.json()["intent"] == "navigate"
    assert res.json()["status"] == "destination_required"

    # 5. Navigate without location -> requires location
    res = client.post("/api/command", json={"text": "Take me to City Hall"})
    assert res.status_code == 200
    assert res.json()["status"] == "location_required"

    # 6. Unknown command
    res = client.post("/api/command", json={"text": "Abracadabra quantum flux"})
    assert res.status_code == 200
    assert res.json()["intent"] == "unknown"


def test_api_navigation_route(client, monkeypatch):
    from backend.app import navigation

    points = [(12.9716, 77.5946), (12.9717, 77.5948), (12.9718, 77.5950)]
    route_payload = {
        "routes": [{
            "distanceMeters": 120,
            "duration": "180s",
            "polyline": {"encodedPolyline": _encode_polyline(points)},
            "legs": [{"steps": [
                {"distanceMeters": 70, "duration": "90s", "navigationInstruction": {"instructions": "Walk toward Main Road", "maneuver": "DEPART"}},
                {"distanceMeters": 50, "duration": "90s", "navigationInstruction": {"instructions": "Turn left onto Main Road", "maneuver": "TURN_LEFT"}},
            ]}],
        }]
    }

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return route_payload

    monkeypatch.setattr(navigation.httpx, "post", lambda *args, **kwargs: FakeResponse())
    monkeypatch.setattr(navigation, "_nav_adapter_instance", navigation.NavigationAdapter(api_key="test"))

    # Ambiguous destination: library
    ambiguous_req = {
        "origin": {"latitude": 12.9716, "longitude": 77.5946},
        "destination": "library",
    }
    res_ambig = client.post("/api/navigation/route", json=ambiguous_req)
    assert res_ambig.status_code == 200
    assert res_ambig.json()["status"] == "ambiguous_destination"

    # Specific destination: Metro Station
    valid_req = {
        "origin": {"latitude": 12.9716, "longitude": 77.5946},
        "destination": "MG Road Metro Station",
    }
    res_valid = client.post("/api/navigation/route", json=valid_req)
    assert res_valid.status_code == 200
    data = res_valid.json()
    assert data["status"] == "active"
    assert data["route_id"].startswith("route_")
    assert len(data["maneuvers"]) > 0
    assert data["attribution"] == "Powered by Google, © 2026 Google"

    route_id = data["route_id"]

    # Test Progress tracking
    prog_req = {
        "route_id": route_id,
        "location": {"latitude": 12.9718, "longitude": 77.5948},
    }
    res_prog = client.post("/api/navigation/progress", json=prog_req)
    assert res_prog.status_code == 200
    assert res_prog.json()["status"] in ("in_progress", "completed")


def test_api_settings_get_put(client):
    # GET settings
    res = client.get("/api/settings")
    assert res.status_code == 200
    orig = res.json()

    # PUT settings update
    update_payload = {"yolo_confidence": 0.65, "audio_verbosity": "normal"}
    res_put = client.put("/api/settings", json=update_payload)
    assert res_put.status_code == 200
    assert res_put.json()["yolo_confidence"] == 0.65
    assert res_put.json()["audio_verbosity"] == "normal"


def test_api_voice_command_endpoint(client, monkeypatch):
    """Verify /api/voice-command returns Gemini-backed conversational answers."""
    from backend.app import main

    monkeypatch.setattr(main, "get_voice_assistant", lambda: FakeVoiceAssistant())
    res = client.post("/api/voice-command", json={"question": "system status"})
    assert res.status_code == 200
    data = res.json()
    assert data["ok"] is True
    assert "System status:" in data["response"]

    # Verify greeting / wake-word
    greet_res = client.post("/api/voice-command", json={"question": "hello navis"})
    assert greet_res.status_code == 200
    assert greet_res.json()["status"] == "success"
    assert "Hello! I am NAVIS" in greet_res.json()["response"]

    general_res = client.post(
        "/api/voice-command",
        json={"question": "Who is the Prime Minister of India?"},
    )
    assert general_res.status_code == 200
    assert "Prime Minister of India" in general_res.json()["response"]

    # Verify compound wake-word question
    compound_res = client.post("/api/voice-command", json={"question": "hello navis, is the path clear?"})
    assert compound_res.status_code == 200
    assert "clear" in compound_res.json()["response"].lower() or "obstacle" in compound_res.json()["response"].lower()

    # Verify 400 when empty
    empty_res = client.post("/api/voice-command", json={})
    assert empty_res.status_code == 400


def test_api_web_ui_and_describe_scene(client, monkeypatch):
    """Verify GET / serves HTML web UI and POST /api/describe-scene handles scene query."""
    from backend.app import main

    monkeypatch.setattr(main, "get_voice_assistant", lambda: FakeVoiceAssistant())
    # 1. GET /
    res_ui = client.get("/")
    assert res_ui.status_code == 200
    assert "text/html" in res_ui.headers["content-type"]
    assert "NAVIS" in res_ui.text

    # 2. POST /api/describe-scene
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (320, 240), color="blue").save(buf, format="JPEG")
    buf.seek(0)

    files = {"image": ("test.jpg", buf.getvalue(), "image/jpeg")}
    data = {"question": "What is around me?", "history": "[]"}
    res_desc = client.post("/api/describe-scene", files=files, data=data)
    assert res_desc.status_code == 200
    res_json = res_desc.json()
    assert res_json["ok"] is True
    assert res_json["route"] == "DESCRIBE_SCENE"
    assert "response" in res_json


def test_browser_voice_api_requires_same_origin(client, monkeypatch):
    from backend.app.api import web

    monkeypatch.setattr(web, "get_voice_assistant", lambda: FakeVoiceAssistant())
    navigation = client.post(
        "/api/web/voice-command",
        headers={"Origin": "http://testserver"},
        json={"question": "How do I get to the airport?"},
    )
    assert navigation.status_code == 200
    assert navigation.json()["route"] == "NAVIGATION"
    assert navigation.json()["status"] == "location_required"

    response = client.post(
        "/api/web/voice-command",
        headers={"Origin": "http://testserver"},
        json={"question": "system status"},
    )
    assert response.status_code == 200
    assert response.json()["ok"] is True

    blocked = client.post(
        "/api/web/voice-command",
        headers={"Origin": "https://untrusted.example"},
        json={"question": "system status"},
    )
    assert blocked.status_code == 403
