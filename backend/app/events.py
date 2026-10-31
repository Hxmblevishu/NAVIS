"""WebSocket connection manager and background perception loop for NAVIS."""
import asyncio
import json
import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Set
from fastapi import WebSocket, WebSocketDisconnect

from backend.app.camera import get_camera_manager
from backend.app.config import get_config
from backend.app.detection import get_detector_manager
from backend.app.distance_sensor import get_distance_sensor_adapter
from backend.app.hazard_engine import get_hazard_engine
from backend.app.logging_config import record_event_log
from backend.app.schemas import HazardEvent, LatestPerceptionResponse, StatusEvent
from backend.app.sensor_fusion import get_fusion_engine
from backend.app.settings import get_settings_manager

logger = logging.getLogger("navis.events")


class ConnectionManager:
    """Manages active WebSocket connections to Android companion clients."""

    def __init__(self):
        self._active_connections: Set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket) -> None:
        """Accept and register a new client connection."""
        await websocket.accept()
        async with self._lock:
            self._active_connections.add(websocket)
        logger.info(f"Android client connected. Total clients: {len(self._active_connections)}")

    async def disconnect(self, websocket: WebSocket) -> None:
        """Unregister a client connection."""
        async with self._lock:
            self._active_connections.discard(websocket)
        logger.info(f"Android client disconnected. Remaining clients: {len(self._active_connections)}")

    @property
    def has_clients(self) -> bool:
        """Check if any WebSocket clients are currently listening."""
        return len(self._active_connections) > 0

    async def broadcast_json(self, payload: Dict[str, Any]) -> None:
        """Broadcast JSON payload to all connected clients without blocking on slow clients."""
        if not self._active_connections:
            return

        message_str = json.dumps(payload, ensure_ascii=False)
        disconnected: List[WebSocket] = []

        async with self._lock:
            clients = list(self._active_connections)

        for ws in clients:
            try:
                # Bounded timeout prevents a slow network client from blocking the pipeline
                await asyncio.wait_for(ws.send_text(message_str), timeout=1.0)
            except (asyncio.TimeoutError, WebSocketDisconnect, Exception) as e:
                logger.warning(f"Failed to send to client ({type(e).__name__}); scheduling removal")
                disconnected.append(ws)

        if disconnected:
            async with self._lock:
                for ws in disconnected:
                    self._active_connections.discard(ws)

    async def broadcast_event(self, event: HazardEvent | StatusEvent) -> None:
        """Broadcast a typed event model."""
        await self.broadcast_json(event.model_dump(mode="json"))


# Global connection manager instance
connection_manager = ConnectionManager()


class PerceptionPipeline:
    """Background loop orchestrating Camera -> Detection -> Sensor -> Fusion -> Hazard -> WebSocket."""

    def __init__(self):
        self._task: asyncio.Task | None = None
        self._running = False
        self._latest_perception = LatestPerceptionResponse(
            status="unavailable",
            message="Waiting for the first live camera frame.",
        )

    @property
    def latest_perception(self) -> LatestPerceptionResponse:
        return self._latest_perception.model_copy(deep=True)

    def start(self) -> None:
        """Start the async perception background loop."""
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._loop(), name="PerceptionLoop")
        logger.info("Background perception pipeline started")

    def stop(self) -> None:
        """Signal background loop to terminate."""
        self._running = False
        if self._task and not self._task.done():
            self._task.cancel()
        logger.info("Background perception pipeline stopped")

    async def _loop(self) -> None:
        camera = get_camera_manager()
        detector = get_detector_manager()
        sensor = get_distance_sensor_adapter()
        fusion = get_fusion_engine()
        hazard = get_hazard_engine()

        while self._running:
            try:
                # Only run perception loop if clients are listening
                if not connection_manager.has_clients:
                    await asyncio.sleep(0.3)
                    continue

                settings = get_settings_manager().get_settings()
                interval_s = max(0.1, settings.frame_interval_ms / 1000.0)
                t0 = time.perf_counter()

                # 1. Acquire latest camera frame
                frame = camera.get_latest_frame(max_age_s=2.0)
                camera_available = frame is not None

                if not camera_available:
                    self._latest_perception = LatestPerceptionResponse(
                        status="unavailable",
                        message="No fresh camera frame is available.",
                    )
                    # Evaluate camera unavailable state through hazard engine
                    events = hazard.evaluate([], camera_available=False, sensor_status=sensor.get_status())
                    for evt in events:
                        await connection_manager.broadcast_event(evt)
                    await asyncio.sleep(interval_s)
                    continue

                # 2. Run object detection in thread pool to prevent blocking event loop
                det_res = await asyncio.to_thread(
                    detector.detect,
                    frame.image_bgr,
                    settings.yolo_confidence,
                    None,
                    frame.frame_id,
                )

                # 3. Read distance sensor
                reading = sensor.get_latest_reading()

                # 4. Sensor Fusion
                fusion_results = fusion.fuse(
                    detections=det_res.detections,
                    sensor_reading=reading,
                    frame_timestamp=frame.timestamp,
                )

                self._latest_perception = LatestPerceptionResponse(
                    status="live",
                    frame_timestamp=frame.timestamp,
                    frame_id=frame.frame_id,
                    detections=det_res.detections,
                    fusion=fusion_results,
                    distance_reading=reading,
                )

                # 5. Hazard Policy Engine
                hazard_events = hazard.evaluate(
                    fusion_results=fusion_results,
                    camera_available=True,
                    sensor_status=sensor.get_status(),
                )

                # 6. Broadcast prioritized events
                for evt in hazard_events:
                    await connection_manager.broadcast_event(evt)

                # Pace the perception loop
                elapsed = time.perf_counter() - t0
                sleep_time = max(0.01, interval_s - elapsed)
                await asyncio.sleep(sleep_time)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Perception pipeline unexpected error: {e}", exc_info=True)
                await asyncio.sleep(1.0)


pipeline_instance = PerceptionPipeline()
