"""Multilingual PaddleOCR engine for NAVIS image-to-text requests."""

import json
import logging
import time
from datetime import datetime, timezone
from io import BytesIO
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

import cv2
import numpy as np
from PIL import Image

from backend.app.config import get_config
from backend.app.schemas import OCRResponse

logger = logging.getLogger("navis.ocr")
OCR_MIN_CONFIDENCE = 0.45


class OCREngine:
    """Runs the supplied PaddleOCR 3.x pipeline with configurable recognition language."""

    def __init__(self, language: Optional[str] = None, ocr_factory: Optional[Callable[..., Any]] = None):
        self.language = (language or get_config().paddleocr_lang or "hi").strip()
        self._ocr_factory = ocr_factory
        self._engine = None
        self._is_available = False
        self._init_checked = False

    def check_availability(self) -> bool:
        """Lazily load PaddleOCR and its pretrained text models on first real request."""
        if self._init_checked:
            return self._is_available
        self._init_checked = True
        try:
            factory = self._ocr_factory
            if factory is None:
                from paddleocr import PaddleOCR

                factory = PaddleOCR
            resolve_models = getattr(factory, "_get_ocr_model_names", None)
            if resolve_models is None:
                # Test/custom factories can use PaddleOCR's public language selector.
                model_config = {"lang": self.language, "ocr_version": "PP-OCRv5"}
            else:
                # PaddleOCR 3.x ignores `lang` whenever either model name is explicit.
                # Resolve the matching PP-OCRv5 recognizer, then keep the supplied
                # archive's smaller mobile detector without silently switching to
                # PaddleOCR's default language/model.
                _, recognition_model = resolve_models(
                    factory, self.language, "PP-OCRv5"
                )
                if recognition_model is None:
                    raise ValueError(
                        f"PaddleOCR PP-OCRv5 does not support language {self.language!r}"
                    )
                model_config = {
                    "text_detection_model_name": "PP-OCRv5_mobile_det",
                    "text_recognition_model_name": recognition_model,
                }
            self._engine = factory(
                **model_config,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
                enable_mkldnn=False,
                cpu_threads=4,
            )
            self._is_available = True
            logger.info("PaddleOCR initialized for language '%s'", self.language)
        except Exception as exc:
            self._is_available = False
            logger.warning("PaddleOCR is unavailable: %s", type(exc).__name__)
        return self._is_available

    @staticmethod
    def _assess_blur(image_bgr: np.ndarray) -> Tuple[bool, float]:
        gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
        variance = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        return variance < 35.0, variance

    @staticmethod
    def _image_to_bgr(image_input: Union[np.ndarray, bytes, Image.Image]) -> Optional[np.ndarray]:
        if isinstance(image_input, bytes):
            return cv2.imdecode(np.frombuffer(image_input, np.uint8), cv2.IMREAD_COLOR)
        if isinstance(image_input, Image.Image):
            rgb = np.asarray(image_input.convert("RGB"))
            return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        if isinstance(image_input, np.ndarray):
            if image_input.ndim == 2:
                return cv2.cvtColor(image_input, cv2.COLOR_GRAY2BGR)
            return image_input
        return None

    @staticmethod
    def _result_dict(result: Any) -> Dict[str, Any]:
        data = getattr(result, "json", None)
        if callable(data):
            data = data()
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                data = None
        if not isinstance(data, dict) and isinstance(result, dict):
            data = result
        if isinstance(data, dict) and isinstance(data.get("res"), dict):
            data = data["res"]
        return data if isinstance(data, dict) else {}

    def extract_text(self, image_input: Union[np.ndarray, bytes, Image.Image]) -> OCRResponse:
        """Detect and recognize multilingual text in an uploaded or camera image."""
        started = time.perf_counter()
        now = datetime.now(timezone.utc)
        image_bgr = self._image_to_bgr(image_input)
        if image_bgr is None or image_bgr.size == 0:
            return OCRResponse(
                status="error",
                text="",
                confidence=None,
                quality="invalid_image",
                processing_ms=round((time.perf_counter() - started) * 1000.0, 2),
                timestamp=now,
            )

        try:
            is_blurred, blur_score = self._assess_blur(image_bgr)
        except cv2.error:
            is_blurred, blur_score = True, 0.0
        if is_blurred:
            return OCRResponse(
                status="blurred",
                text="",
                confidence=None,
                quality=f"blurry (sharpness score: {blur_score:.1f})",
                processing_ms=round((time.perf_counter() - started) * 1000.0, 2),
                timestamp=now,
            )

        if not self.check_availability() or self._engine is None:
            return OCRResponse(
                status="unavailable",
                text="",
                confidence=None,
                quality="paddleocr_not_available",
                processing_ms=round((time.perf_counter() - started) * 1000.0, 2),
                timestamp=now,
            )

        try:
            results = self._engine.predict(input=image_bgr)
            lines: List[str] = []
            confidences: List[float] = []
            for result in results or []:
                data = self._result_dict(result)
                texts = data.get("rec_texts", [])
                scores = data.get("rec_scores", [])
                if not isinstance(texts, (list, tuple)):
                    continue
                for index, raw_text in enumerate(texts):
                    if not isinstance(raw_text, str) or not raw_text.strip():
                        continue
                    try:
                        score = float(scores[index]) if index < len(scores) else 0.0
                    except (TypeError, ValueError):
                        score = 0.0
                    if score < OCR_MIN_CONFIDENCE:
                        continue
                    lines.append(raw_text.strip())
                    confidences.append(max(0.0, min(score, 1.0)))

            processing_ms = round((time.perf_counter() - started) * 1000.0, 2)
            if not lines:
                return OCRResponse(
                    status="no_text_found",
                    text="",
                    confidence=None,
                    quality="no_confident_text",
                    processing_ms=processing_ms,
                    timestamp=now,
                )

            average_confidence = sum(confidences) / len(confidences)
            return OCRResponse(
                status="success",
                text=" ".join(lines),
                confidence=round(average_confidence, 3),
                quality="good" if average_confidence >= 0.6 else "low_confidence",
                processing_ms=processing_ms,
                timestamp=now,
            )
        except Exception as exc:
            logger.warning("PaddleOCR inference failed: %s", type(exc).__name__)
            return OCRResponse(
                status="error",
                text="",
                confidence=None,
                quality="paddleocr_inference_error",
                processing_ms=round((time.perf_counter() - started) * 1000.0, 2),
                timestamp=now,
            )


_ocr_engine_instance: Optional[OCREngine] = None


def get_ocr_engine() -> OCREngine:
    """Retrieve singleton PaddleOCR engine."""
    global _ocr_engine_instance
    if _ocr_engine_instance is None:
        _ocr_engine_instance = OCREngine()
    return _ocr_engine_instance
