"""Offline tests for the supplied PaddleOCR pipeline integration."""

import numpy as np

from backend.app.ocr import OCREngine


class FakeOCRResult:
    def json(self):
        return {
            "res": {
                "rec_texts": ["  EXIT ", "uncertain"],
                "rec_scores": [0.92, 0.2],
                "rec_polys": [],
            }
        }


class FakePaddleOCR:
    def __init__(self, **kwargs):
        self.kwargs = kwargs

    def predict(self, input):
        assert input.shape == (120, 240, 3)
        return [FakeOCRResult()]


def test_paddleocr_extracts_confident_text_and_uses_configured_language():
    engines = []

    def factory(**kwargs):
        engine = FakePaddleOCR(**kwargs)
        engines.append(engine)
        return engine

    factory._get_ocr_model_names = lambda _factory, language, version: (
        "PP-OCRv5_server_det",
        "devanagari_PP-OCRv5_mobile_rec" if language == "hi" else None,
    )

    image = np.random.default_rng(8).integers(0, 255, (120, 240, 3), dtype=np.uint8)
    response = OCREngine(language="hi", ocr_factory=factory).extract_text(image)

    assert response.status == "success"
    assert response.text == "EXIT"
    assert response.confidence == 0.92
    assert engines[0].kwargs["text_detection_model_name"] == "PP-OCRv5_mobile_det"
    assert engines[0].kwargs["text_recognition_model_name"] == "devanagari_PP-OCRv5_mobile_rec"


def test_paddleocr_rejects_blurred_image_before_loading_model():
    def unexpected_engine(**kwargs):
        raise AssertionError("Blurred images must not initialize PaddleOCR")

    response = OCREngine(language="hi", ocr_factory=unexpected_engine).extract_text(
        np.zeros((120, 240, 3), dtype=np.uint8)
    )

    assert response.status == "blurred"
