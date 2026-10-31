"""Bounded image upload parsing shared by NAVIS API endpoints."""

from io import BytesIO

from fastapi import HTTPException, UploadFile, status
from PIL import Image, UnidentifiedImageError

MAX_IMAGE_BYTES = 12 * 1024 * 1024


async def read_image_upload(upload: UploadFile) -> bytes:
    contents = await upload.read(MAX_IMAGE_BYTES + 1)
    if not contents:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Empty image file.")
    if len(contents) > MAX_IMAGE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Image exceeds the {MAX_IMAGE_BYTES // (1024 * 1024)} MB upload limit.",
        )
    validate_image_bytes(contents)
    return contents


def validate_image_bytes(contents: bytes) -> None:
    if not contents:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No image data provided.")
    if len(contents) > MAX_IMAGE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Image exceeds the {MAX_IMAGE_BYTES // (1024 * 1024)} MB upload limit.",
        )
    try:
        with Image.open(BytesIO(contents)) as image:
            if image.format not in {"JPEG", "PNG", "WEBP", "BMP", "TIFF"}:
                raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported image format.")
            image.verify()
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Could not decode the uploaded image.",
        ) from exc
