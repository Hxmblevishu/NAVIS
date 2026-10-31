# -*- coding: utf-8 -*-
"""FastAPI router exposing the YOLO detection endpoint.
The router is imported in ``backend/app/main.py`` and added to the app with
``app.include_router(detect_router)``.
All calls are protected by a simple ``X-API-Token`` header – the same token
used by the other secured endpoints.
"""

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status

from backend.app.camera import get_camera_manager
from backend.app.detection import get_detector_manager
from backend.app.image_input import read_image_upload
from backend.app.schemas import DetectionResponse
from backend.app.security import verify_api_token

router = APIRouter(prefix="/api", tags=["Detection"])

@router.post("/detect", response_model=DetectionResponse, dependencies=[Depends(verify_api_token)])
async def detect_image(image: UploadFile | None = File(None, description="Optional image; otherwise use the latest camera frame")) -> DetectionResponse:
    """Detect objects in an uploaded image or the latest live camera frame."""
    detector = get_detector_manager()
    if image is not None:
        contents = await read_image_upload(image)
        return detector.detect(contents)

    frame = get_camera_manager().get_latest_frame(max_age_s=2.0)
    if frame is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="No fresh camera frame is available.",
        )
    result = detector.detect(frame.image_bgr, frame_id=frame.frame_id)
    result.frame_timestamp = frame.timestamp
    return result

