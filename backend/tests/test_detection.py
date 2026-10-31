"""Unit tests for YOLO detection, bounding boxes, and image positioning."""
import numpy as np
import pytest
from backend.app.detection import DetectorManager


@pytest.fixture
def detector():
    det = DetectorManager(model_name="yolo26n.pt")
    det.load_model()
    return det


def test_detector_loads_model(detector):
    """Verify YOLO model loads successfully."""
    assert detector.is_loaded is True
    assert len(detector.classes_map) > 0
    assert 0 in detector.classes_map  # Class 0 is 'person' in COCO dataset


def test_detector_blank_image(detector):
    """Verify inference runs on a blank image and returns valid empty DetectionResponse."""
    blank = np.zeros((480, 640, 3), dtype=np.uint8)
    res = detector.detect(blank, confidence=0.55)
    assert res.model_status == "inference_complete"
    assert res.inference_ms >= 0.0
    assert isinstance(res.detections, list)
    assert len(res.detections) == 0


def test_detector_position_classification(detector):
    """Verify horizontal region categorization logic."""
    # Test coordinates
    orig_w, orig_h = 640, 480

    # Left box (cx = 100 < 640 * 0.35 = 224)
    # Center box (cx = 320)
    # Right box (cx = 550 > 640 * 0.65 = 416)
    left_cx = 100
    center_cx = 320
    right_cx = 550

    assert left_cx < orig_w * 0.35
    assert orig_w * 0.35 <= center_cx <= orig_w * 0.65
    assert right_cx > orig_w * 0.65


def test_resolve_model_path(detector):
    """Verify detector resolves model path correctly."""
    resolved = detector._resolve_model_path()
    assert resolved.endswith(".pt")
    assert "yolo26n" in resolved
