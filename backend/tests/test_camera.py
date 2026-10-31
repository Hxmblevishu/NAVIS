"""Unit tests for camera acquisition and bounded buffering module."""
import time
from datetime import datetime, timezone, timedelta
import numpy as np
import pytest
from backend.app.camera import CameraManager, CameraFrame


def test_camera_mock_generation():
    """Verify camera generates valid synthetic frames when unconfigured or in mock mode."""
    cam = CameraManager(camera_url="mock")
    img, img_bytes = cam._generate_synthetic_frame()
    assert isinstance(img, np.ndarray)
    assert img.shape == (480, 640, 3)
    assert len(img_bytes) > 1000  # Valid JPEG bytes


def test_camera_bounded_buffer():
    """Verify latest frame retrieval and bounded buffering."""
    cam = CameraManager(camera_url="mock")
    img, img_bytes = cam._generate_synthetic_frame()
    cam._update_frame(img, img_bytes)

    frame = cam.get_latest_frame(max_age_s=2.0)
    assert frame is not None
    assert frame.width == 640
    assert frame.height == 480
    assert frame.frame_id.startswith("frm_")


def test_camera_stale_frame_rejection():
    """Verify stale frames older than max_age_s are rejected."""
    cam = CameraManager(camera_url="")
    img, img_bytes = cam._generate_synthetic_frame()
    cam._update_frame(img, img_bytes)

    # Artificially age the frame
    with cam._lock:
        cam._latest_frame.timestamp = datetime.now(timezone.utc) - timedelta(seconds=5.0)

    # Should reject stale frame
    frame = cam.get_latest_frame(max_age_s=2.0)
    assert frame is None


def test_camera_start_stop():
    """Verify camera lifecycle start and stop."""
    cam = CameraManager(camera_url="mock")
    cam.start()
    time.sleep(0.4)
    assert cam.is_connected is True
    assert cam.frame_count >= 1

    fresh = cam.get_fresh_frame(timeout_s=1.0)
    assert fresh is not None

    cam.stop()
    assert cam.is_connected is False
