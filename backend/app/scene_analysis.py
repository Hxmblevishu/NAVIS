"""Grounded scene summaries combining YOLO26 detections with Gemini vision."""

import io
import logging
from datetime import datetime, timezone
from typing import List, Optional, Union

import cv2
import numpy as np
from PIL import Image

from backend.app.detection import get_detector_manager
from backend.app.gemini_service import get_gemini_service
from backend.app.schemas import Detection, SceneAnalysisResponse

logger = logging.getLogger("navis.scene_analysis")
_SCENE_SYSTEM_INSTRUCTION = (
    "You are NAVIS, an assistive voice companion for a blind or low-vision pedestrian. "
    "Describe the supplied camera image in three short spoken sentences, prioritizing the overall "
    "setting and prominent objects by approximate image position. Answer the user's specific "
    "question when possible. Treat YOLO26 labels as approximate 2D detections, not measurements. "
    "Do not infer exact physical distances or guarantee that a path, crossing, or route is safe. "
    "If an object or detail is unclear, say that it is unclear. Do not use markdown."
)


class SceneAnalyzer:
    """Describe a single image with Gemini and preserve YOLO26 detections in the response."""

    @staticmethod
    def _to_jpeg(image_input: Union[np.ndarray, bytes, Image.Image]) -> bytes:
        if isinstance(image_input, bytes):
            with Image.open(io.BytesIO(image_input)) as image:
                normalized = image.convert("RGB")
        elif isinstance(image_input, np.ndarray):
            rgb = cv2.cvtColor(image_input, cv2.COLOR_BGR2RGB)
            normalized = Image.fromarray(rgb)
        else:
            normalized = image_input.convert("RGB")
        output = io.BytesIO()
        normalized.save(output, format="JPEG", quality=85, optimize=True)
        return output.getvalue()

    @staticmethod
    def _format_local_summary(detections: List[Detection]) -> str:
        if not detections:
            return "No familiar objects were confidently identified in this camera view."
        items = [f"{item.label} on the {item.position}" for item in detections[:6]]
        return f"Visible objects include: {', '.join(items)}."

    def analyze(
        self,
        image_input: Union[np.ndarray, bytes, Image.Image],
        prompt: Optional[str] = None,
    ) -> SceneAnalysisResponse:
        now = datetime.now(timezone.utc)
        detector = get_detector_manager()
        detections = detector.detect(image_input).detections
        context = [
            {"label": item.label, "position": item.position, "confidence": item.confidence}
            for item in detections[:12]
        ]
        try:
            jpeg_bytes = self._to_jpeg(image_input)
            result = get_gemini_service().generate(
                question=prompt or "Describe my surroundings concisely.",
                scene_context=context,
                image_bytes=jpeg_bytes,
                system_instruction=_SCENE_SYSTEM_INSTRUCTION,
                max_output_tokens=220,
            )
        except Exception as exc:
            logger.warning("Scene analysis failed: %s", type(exc).__name__)
            result = None

        if result is not None and result.ok:
            return SceneAnalysisResponse(
                status="success",
                description=result.text,
                uncertainty=(
                    "Single-frame Gemini vision supported by YOLO26 detections. "
                    "Physical distances and route safety are not verified."
                ),
                detections=detections,
                timestamp=now,
            )

        status = result.status if result is not None and result.status in {"unavailable", "error"} else "error"
        return SceneAnalysisResponse(
            status=status,
            description=self._format_local_summary(detections),
            uncertainty=(
                "Gemini scene description is unavailable; this fallback contains only local YOLO26 "
                "2D detections. It cannot confirm physical distance or path safety."
            ),
            detections=detections,
            timestamp=now,
        )


_scene_analyzer_instance: Optional[SceneAnalyzer] = None


def get_scene_analyzer() -> SceneAnalyzer:
    """Retrieve singleton SceneAnalyzer."""
    global _scene_analyzer_instance
    if _scene_analyzer_instance is None:
        _scene_analyzer_instance = SceneAnalyzer()
    return _scene_analyzer_instance
