"""Camera acquisition module for NAVIS.
Handles frame acquisition from ESP32-CAM snapshot or MJPEG stream,
maintains a bounded single-frame buffer, and manages reconnects.
"""
import logging
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional, Tuple
from urllib.request import Request, urlopen
from urllib.parse import urlparse
import cv2
import numpy as np

logger = logging.getLogger("navis.camera")


@dataclass
class CameraFrame:
    """Represents a single acquired camera frame."""
    frame_id: str
    image_bytes: bytes
    image_bgr: np.ndarray
    timestamp: datetime
    width: int
    height: int


class CameraManager:
    """Acquires frames from ESP32-CAM or local fallback without building stale queues."""

    def __init__(self, camera_url: str = ""):
        self.camera_url = camera_url.strip()
        self._latest_frame: Optional[CameraFrame] = None
        self._lock = threading.Lock()
        self._new_frame_event = threading.Event()
        self._running = False
        self._worker_thread: Optional[threading.Thread] = None

        # Telemetry & status
        self.is_connected = False
        self.reconnecting = False
        self.last_frame_timestamp: Optional[datetime] = None
        self.frame_width: Optional[int] = None
        self.frame_height: Optional[int] = None
        self.frame_count = 0
        self.last_error: Optional[str] = None
        self.fps: float = 0.0

    def start(self) -> None:
        """Start the background frame acquisition thread."""
        if self._running:
            return
        self._running = True
        self._worker_thread = threading.Thread(target=self._run_loop, name="CameraWorker", daemon=True)
        self._worker_thread.start()
        logger.info(f"Camera manager started with URL: '{self.camera_url or 'None (mock/fallback)'}'")

    def stop(self) -> None:
        """Stop background acquisition and release resources."""
        self._running = False
        if self._worker_thread and self._worker_thread.is_alive():
            self._worker_thread.join(timeout=2.0)
        with self._lock:
            self.is_connected = False
            self.reconnecting = False
            self._latest_frame = None
        logger.info("Camera manager stopped")

    def get_latest_frame(self, max_age_s: float = 2.0) -> Optional[CameraFrame]:
        """Retrieve the most recent frame from the bounded buffer.
        Returns None if no frame exists or if the frame is older than max_age_s.
        """
        with self._lock:
            if self._latest_frame is None:
                return None
            age = (datetime.now(timezone.utc) - self._latest_frame.timestamp).total_seconds()
            if age > max_age_s:
                logger.debug(f"Rejecting stale frame ({age:.2f}s > {max_age_s}s)")
                return None
            return self._latest_frame

    def get_fresh_frame(self, timeout_s: float = 2.0) -> Optional[CameraFrame]:
        """Block until a newly acquired frame arrives within timeout_s."""
        self._new_frame_event.clear()
        if self._new_frame_event.wait(timeout=timeout_s):
            with self._lock:
                return self._latest_frame
        # If timeout fired, check if latest frame is still sufficiently fresh (< 1s)
        return self.get_latest_frame(max_age_s=1.0)

    def _generate_synthetic_frame(self) -> Tuple[np.ndarray, bytes]:
        """Generate a synthetic test frame for development/mock mode."""
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        # Background gradient
        img[:, :] = (35, 30, 25)
        # Draw guidance text
        cv2.putText(img, "NAVIS Synthetic Frame", (40, 60), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
        now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        cv2.putText(img, f"Timestamp: {now_str}", (40, 110), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 200), 2)
        cv2.putText(img, "ESP32-CAM Not Connected (Diagnostic Mode)", (40, 160), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (100, 200, 255), 1)

        # Draw a simulated obstacle in center
        cv2.rectangle(img, (220, 200), (420, 420), (0, 140, 255), 2)
        cv2.putText(img, "Simulated Obstacle (Center)", (230, 230), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 140, 255), 1)

        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])
        return img, buf.tobytes() if ok else b""

    def _run_loop(self) -> None:
        """Background acquisition loop with bounded backoff and stale-frame avoidance."""
        backoff_s = 1.0
        fps_start_time = time.time()
        fps_frames = 0
        parsed_url = urlparse(self.camera_url)
        snapshot_mode = parsed_url.path.rstrip("/").lower().endswith(("/capture", ".jpg", ".jpeg", ".png"))

        while self._running:
            if not self.camera_url or self.camera_url.lower() == "mock":
                # Diagnostic / synthetic mode
                img, img_bytes = self._generate_synthetic_frame()
                self._update_frame(img, img_bytes)
                time.sleep(0.3)
                continue

            # Attempt stream / snapshot connection
            cap = None
            try:
                if snapshot_mode:
                    request = Request(self.camera_url, headers={"Accept": "image/jpeg"})
                    with urlopen(request, timeout=3.0) as response:
                        if response.status != 200:
                            raise RuntimeError(f"Camera snapshot returned HTTP {response.status}")
                        image_bytes = response.read(8 * 1024 * 1024 + 1)
                    if len(image_bytes) > 8 * 1024 * 1024:
                        raise RuntimeError("Camera snapshot exceeded the 8 MB limit")
                    frame = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
                    if frame is None or frame.size == 0:
                        raise RuntimeError("Camera snapshot was not a valid JPEG image")

                    backoff_s = 1.0
                    with self._lock:
                        self.is_connected = True
                        self.reconnecting = False
                        self.last_error = None
                    self._update_frame(frame, image_bytes)
                    fps_frames += 1
                    elapsed = time.time() - fps_start_time
                    if elapsed >= 2.0:
                        self.fps = round(fps_frames / elapsed, 1)
                        fps_frames = 0
                        fps_start_time = time.time()
                    time.sleep(0.25)
                    continue

                # Support integer device index (e.g. "0") or RTSP/HTTP URL
                device_target = int(self.camera_url) if self.camera_url.isdigit() else self.camera_url
                cap = cv2.VideoCapture(device_target)

                if not cap.isOpened():
                    raise RuntimeError(f"Could not open camera stream at '{self.camera_url}'")

                # Reset backoff on successful connect
                backoff_s = 1.0
                with self._lock:
                    self.is_connected = True
                    self.reconnecting = False
                    self.last_error = None
                logger.info(f"Connected to camera stream: {self.camera_url}")

                # Capture loop
                while self._running:
                    # Grab latest frame without queuing
                    grabbed = cap.grab()
                    if not grabbed:
                        raise RuntimeError("Camera stream grab failed (end of stream or disconnected)")

                    ret, frame = cap.retrieve()
                    if not ret or frame is None or frame.size == 0:
                        continue

                    # Encode to JPEG for downstream consumers
                    ok, jpeg_buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
                    if not ok:
                        continue

                    self._update_frame(frame, jpeg_buf.tobytes())

                    # FPS measurement
                    fps_frames += 1
                    elapsed = time.time() - fps_start_time
                    if elapsed >= 2.0:
                        self.fps = round(fps_frames / elapsed, 1)
                        fps_frames = 0
                        fps_start_time = time.time()

                    # Bounded pace to avoid pegging CPU when capture source provides unlimited FPS
                    time.sleep(0.03)

            except Exception as e:
                with self._lock:
                    self.is_connected = False
                    self.reconnecting = True
                    self.last_error = str(e)
                logger.warning(f"Camera connection error: {e}. Retrying in {backoff_s:.1f}s...")
                time.sleep(backoff_s)
                backoff_s = min(backoff_s * 1.5, 10.0)
            finally:
                if cap is not None:
                    cap.release()

    def _update_frame(self, frame_bgr: np.ndarray, jpeg_bytes: bytes) -> None:
        """Atomically update the bounded single-frame buffer."""
        now = datetime.now(timezone.utc)
        h, w = frame_bgr.shape[:2]
        new_frame = CameraFrame(
            frame_id=f"frm_{uuid.uuid4().hex[:8]}",
            image_bytes=jpeg_bytes,
            image_bgr=frame_bgr,
            timestamp=now,
            width=w,
            height=h,
        )
        with self._lock:
            self._latest_frame = new_frame
            self.last_frame_timestamp = now
            self.frame_width = w
            self.frame_height = h
            self.frame_count += 1
            if not self.camera_url or self.camera_url.lower() == "mock":
                self.is_connected = True
        self._new_frame_event.set()


# Singleton camera instance
_camera_instance: Optional[CameraManager] = None


def get_camera_manager(camera_url: Optional[str] = None) -> CameraManager:
    """Retrieve singleton camera manager."""
    global _camera_instance
    if _camera_instance is None:
        url = camera_url if camera_url is not None else ""
        _camera_instance = CameraManager(url)
    return _camera_instance
