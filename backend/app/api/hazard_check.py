"""Hazard check API endpoint supporting camera frame uploads and real-time directional decision."""
import base64
import binascii
import io
import json
import logging
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status
from PIL import Image

from backend.app.detection import get_detector_manager
from backend.app.hazard_classifier import classify_hazards
from backend.app.decision_direction import decide_direction
from backend.app.image_input import read_image_upload, validate_image_bytes
from backend.app.security import verify_api_token

logger = logging.getLogger("navis.hazard_check")
router = APIRouter(prefix="/api", tags=["Hazards"])


async def analyze_hazard_request(
    request: Request,
    image: Optional[UploadFile] = File(None, description="Optional uploaded image file")
) -> Dict[str, Any]:
    """Analyze a camera image for immediate hazards and directional recommendation."""
    image_bytes = None

    if image is not None:
        image_bytes = await read_image_upload(image)
    else:
        try:
            raw_body = await request.body()
            if len(raw_body) > 16 * 1024 * 1024:
                raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="Request body is too large.")
            body = json.loads(raw_body) if raw_body else {}
        except HTTPException:
            raise
        except (json.JSONDecodeError, UnicodeDecodeError):
            body = {}
        image_val = body.get("image")
        if image_val and isinstance(image_val, str):
            if image_val.startswith("data:image"):
                _, _, b64_data = image_val.partition(",")
                try:
                    image_bytes = base64.b64decode(b64_data, validate=True)
                except (ValueError, binascii.Error) as exc:
                    raise HTTPException(status_code=400, detail="Invalid base64 image data") from exc
            else:
                try:
                    image_bytes = base64.b64decode(image_val, validate=True)
                except (ValueError, binascii.Error) as exc:
                    raise HTTPException(status_code=400, detail="Invalid base64 image data") from exc

    if not image_bytes:
        raise HTTPException(status_code=400, detail="No camera image provided")

    validate_image_bytes(image_bytes)

    try:
        pil_img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        img_w, img_h = pil_img.size
    except Exception as e:
        logger.warning(f"Could not decode image for hazard check: {e}")
        raise HTTPException(status_code=400, detail="Could not decode camera image")

    # Run YOLO detection
    detector = get_detector_manager()
    detection_res = detector.detect(image_bytes)

    # Format detections to match the dictionary structure expected by hazard_classifier
    detections_dict: List[Dict[str, Any]] = []
    for d in detection_res.detections:
        # Normalize bbox to 0..1 ratio if pixel coords were returned
        x1, y1, x2, y2 = d.bbox
        if x2 > 1.0 or y2 > 1.0:
            norm_bbox = [x1 / max(1, img_w), y1 / max(1, img_h), x2 / max(1, img_w), y2 / max(1, img_h)]
        else:
            norm_bbox = [x1, y1, x2, y2]

        cx = (norm_bbox[0] + norm_bbox[2]) / 2.0
        cy = (norm_bbox[1] + norm_bbox[3]) / 2.0

        detections_dict.append({
            "label": d.label,
            "confidence": d.confidence,
            "bbox": norm_bbox,
            "center": [cx, cy],
            "position": d.position,
            "vertical_position": d.vertical_position,
        })

    hazards = classify_hazards(detections_dict)
    decision = decide_direction(detections_dict, hazards)

    return {
        "ok": True,
        "detections": detections_dict,
        "hazards": hazards,
        "decision": decision
    }


@router.post("/hazard-check", dependencies=[Depends(verify_api_token)])
async def hazard_check_endpoint(
    request: Request,
    image: Optional[UploadFile] = File(None, description="Optional uploaded image file"),
) -> Dict[str, Any]:
    """Analyze an image for potential hazards. Visual detections do not verify a safe path."""
    return await analyze_hazard_request(request, image)

