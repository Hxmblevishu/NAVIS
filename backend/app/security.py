"""Shared authentication and browser-origin checks for the NAVIS API."""

import hmac
from urllib.parse import urlsplit

from fastapi import Header, HTTPException, Request, WebSocket, status

from backend.app.config import get_config


def verify_api_token(
    x_api_token: str | None = Header(None, alias="X-API-Token"),
) -> bool:
    """Require the configured client token; leave tokenless local development usable."""
    configured_token = get_config().api_token.strip()
    if not configured_token:
        return True
    if x_api_token is None or not hmac.compare_digest(x_api_token, configured_token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="A valid X-API-Token header is required.",
        )
    return True


def require_same_origin(request: Request) -> None:
    """Limit browser-only write endpoints to the origin serving the dashboard."""
    origin = request.headers.get("origin")
    if not origin or origin == "null":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This endpoint is available only to the NAVIS dashboard.",
        )

    parsed_origin = urlsplit(origin)
    request_host = request.headers.get("host", "").lower()
    origin_host = (parsed_origin.netloc or "").lower()
    if (
        parsed_origin.scheme not in {"http", "https"}
        or not origin_host
        or origin_host != request_host
        or parsed_origin.path not in {"", "/"}
        or parsed_origin.query
        or parsed_origin.fragment
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Cross-origin requests are not allowed.",
        )


async def verify_websocket_token(websocket: WebSocket) -> bool:
    """Authenticate native WebSocket clients before accepting the connection."""
    configured_token = get_config().api_token.strip()
    supplied_token = websocket.headers.get("x-api-token", "")
    if configured_token and not hmac.compare_digest(supplied_token, configured_token):
        await websocket.close(code=1008)
        return False
    return True
