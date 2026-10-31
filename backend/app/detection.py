"""Object detection module for NAVIS using Ultralytics YOLO.
Provides structured detections with bounded-box normalization and horizontal region categorization.
"""
import logging
import os
import threading
import time

os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"
os.environ["ULTRALYTICS_CHECK_UPDATES"] = "0"
os.environ["YOLO_VERBOSE"] = "False"

from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional, Tuple, Union
import numpy as np
from PIL import Image

from backend.app.config import get_config
from backend.app.schemas import Detection, DetectionResponse

logger = logging.getLogger("navis.detection")


class DetectorManager:
    """Manages YOLO model lifecycle and bounded inference."""

    def __init__(self, model_name: Optional[str] = None):
        cfg = get_config()
        self.model_name = model_name or cfg.yolo_model
        self._model = None
        self._lock = threading.Lock()
        self.is_loaded = False
        self.load_error: Optional[str] = None
        self.classes_map: dict = {}

    def _resolve_model_path(self) -> str:
        """Resolve model weights file from local directory, models folder, or parent workspace."""
        candidates = [
            Path(self.model_name),
            Path("backend") / self.model_name,
            Path("models") / self.model_name,
            Path("backend/models") / self.model_name,
            Path("..") / self.model_name,
            Path(os.path.dirname(__file__)).parent / "models" / self.model_name,
            Path(os.path.dirname(__file__)).parent.parent / self.model_name,
        ]
        for c in candidates:
            if c.exists() and c.is_file():
                logger.info(f"Resolved YOLO model file at: {c.resolve()}")
                return str(c.resolve())
        return self.model_name

    def load_model(self) -> bool:
        """Load the YOLO model weights into memory."""
        with self._lock:
            if self.is_loaded and self._model is not None:
                return True

            try:
                from ultralytics import YOLO
                resolved_path = self._resolve_model_path()
                logger.info(f"Loading YOLO model weights from '{resolved_path}'...")
                self._model = YOLO(resolved_path)
                self.is_loaded = True
                self.load_error = None
                self.classes_map = self._model.names or {}
                logger.info(f"YOLO model '{self.model_name}' loaded successfully with {len(self.classes_map)} classes.")
                return True
            except Exception as e:
                self.is_loaded = False
                self.load_error = str(e)
                logger.error(f"Failed to load YOLO model: {e}")
                return False

    def detect(
        self,
        image_input: Union[np.ndarray, Image.Image, bytes],
        confidence: Optional[float] = None,
        imgsz: Optional[int] = None,
        frame_id: Optional[str] = None,
    ) -> DetectionResponse:
        """Run object detection on an image.
        Returns structured DetectionResponse with timing and bounding box regions.
        """
        now = datetime.now(timezone.utc)
        if not self.is_loaded:
            loaded = self.load_model()
            if not loaded:
                return DetectionResponse(
                    detections=[],
                    frame_timestamp=now,
                    frame_id=frame_id,
                    inference_ms=0.0,
                    model_status="model_unavailable",
                )

        cfg = get_config()
        conf_threshold = confidence if confidence is not None else cfg.yolo_confidence
        image_size = imgsz if imgsz is not None else cfg.yolo_imgsz

        # Convert bytes to PIL if necessary
        if isinstance(image_input, bytes):
            import io
            image = Image.open(io.BytesIO(image_input)).convert("RGB")
        elif isinstance(image_input, np.ndarray):
            # OpenCV BGR -> RGB for YOLO
            import cv2
            image = cv2.cvtColor(image_input, cv2.COLOR_BGR2RGB)
        else:
            image = image_input

        t0 = time.perf_counter()
        detections: List[Detection] = []

        try:
            with self._lock:
                results = self._model.predict(
                    image,
                    verbose=False,
                    conf=conf_threshold,
                    imgsz=image_size,
                )

            inference_ms = round((time.perf_counter() - t0) * 1000.0, 2)

            if results and len(results) > 0 and results[0].boxes is not None:
                res = results[0]
                orig_h, orig_w = res.orig_shape[0], res.orig_shape[1]
                total_area = float(orig_w * orig_h) if (orig_w and orig_h) else 1.0

                for box in res.boxes:
                    coords = box.xyxy[0].tolist()
                    x1, y1, x2, y2 = [round(c, 2) for c in coords]
                    conf = round(float(box.conf[0].item()), 3)
                    cls_id = int(box.cls[0].item())
                    label = str(self.classes_map.get(cls_id, f"class_{cls_id}"))

                    # Compute horizontal region based on center_x
                    cx = (x1 + x2) / 2.0
                    cy = (y1 + y2) / 2.0

                    if cx < orig_w * 0.35:
                        h_pos = "left"
                    elif cx > orig_w * 0.65:
                        h_pos = "right"
                    else:
                        h_pos = "center"

                    if cy < orig_h * 0.35:
                        v_pos = "top"
                    elif cy > orig_h * 0.65:
                        v_pos = "bottom"
                    else:
                        v_pos = "middle"

                    box_area = max(0.0, (x2 - x1) * (y2 - y1))
                    area_ratio = round(box_area / total_area, 4)

                    detections.append(
                        Detection(
                            label=label,
                            confidence=conf,
                            bbox=[x1, y1, x2, y2],
                            position=h_pos,
                            vertical_position=v_pos,
                            area_ratio=area_ratio,
                        )
                    )

            # Sort detections by confidence descending
            detections.sort(key=lambda d: d.confidence, reverse=True)

            return DetectionResponse(
                detections=detections,
                frame_timestamp=now,
                frame_id=frame_id,
                inference_ms=inference_ms,
                model_status="inference_complete",
            )

        except Exception as e:
            inference_ms = round((time.perf_counter() - t0) * 1000.0, 2)
            logger.error(f"Inference execution failed: {e}")
            return DetectionResponse(
                detections=[],
                frame_timestamp=now,
                frame_id=frame_id,
                inference_ms=inference_ms,
                model_status="inference_failed",
            )


# Singleton detector instance
_detector_instance: Optional[DetectorManager] = None


def get_detector_manager(model_name: Optional[str] = None) -> DetectorManager:
    """Retrieve singleton YOLO detector manager."""
    global _detector_instance
    if _detector_instance is None:
        _detector_instance = DetectorManager(model_name)
    return _detector_instance
