"""Unit tests for WebSocket event broadcasting and ConnectionManager."""
import asyncio
from datetime import datetime, timezone
import pytest
from backend.app.events import ConnectionManager
from backend.app.schemas import HazardEvent, StatusEvent


class MockWebSocket:
    """Mock WebSocket for unit testing."""
    def __init__(self):
        self.accepted = False
        self.sent_messages = []
        self.closed = False

    async def accept(self):
        self.accepted = True

    async def send_text(self, text: str):
        if self.closed:
            raise RuntimeError("WebSocket is closed")
        self.sent_messages.append(text)


@pytest.mark.asyncio
async def test_connection_manager_lifecycle():
    """Verify client connect, broadcast, and disconnect."""
    mgr = ConnectionManager()
    ws1 = MockWebSocket()
    ws2 = MockWebSocket()

    await mgr.connect(ws1)
    await mgr.connect(ws2)
    assert len(mgr._active_connections) == 2

    # Broadcast JSON
    payload = {"type": "ping", "data": "hello"}
    await mgr.broadcast_json(payload)
    assert len(ws1.sent_messages) == 1
    assert len(ws2.sent_messages) == 1
    assert "ping" in ws1.sent_messages[0]

    # Disconnect one client
    await mgr.disconnect(ws1)
    assert len(mgr._active_connections) == 1

    # Broadcast typed event
    evt = StatusEvent(
        event_id="evt_stat_01",
        component="camera",
        state="connected",
        message="Camera online",
    )
    await mgr.broadcast_event(evt)
    assert len(ws2.sent_messages) == 2
    assert "Camera online" in ws2.sent_messages[1]


@pytest.mark.asyncio
async def test_connection_manager_dropped_client():
    """Verify failed client write automatically unregisters client without crash."""
    mgr = ConnectionManager()
    ws = MockWebSocket()
    await mgr.connect(ws)

    # Force failure
    ws.closed = True

    await mgr.broadcast_json({"message": "test"})
    # The broken connection should be removed
    assert len(mgr._active_connections) == 0
