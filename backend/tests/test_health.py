"""Unit tests for /health endpoint and health reporting logic."""
import pytest
from fastapi.testclient import TestClient
from backend.app.main import app
from backend.app.schemas import HealthResponse


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_health_endpoint_schema(client):
    """Verify /health returns HTTP 200 and conforms to HealthResponse schema."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()

    # Validate against Pydantic schema
    health = HealthResponse(**data)
    assert health.backend_version == "0.1.0"
    assert health.status in ("healthy", "degraded", "unhealthy")
    assert isinstance(health.model_loaded, bool)
    assert isinstance(health.camera_connected, bool)
    assert isinstance(health.distance_sensor_connected, bool)
    assert isinstance(health.degraded_components, list)


def test_health_degraded_reporting(client):
    """When a component is offline or mock, health honestly reports degraded status."""
    response = client.get("/health")
    data = response.json()
    # In initial test environment with no physical ESP32 connected, status should be degraded or healthy (if mock)
    assert data["status"] in ("healthy", "degraded")
    if data["degraded_components"]:
        assert len(data["reasons"]) > 0
