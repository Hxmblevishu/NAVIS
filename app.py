"""Compatibility launcher for the canonical NAVIS FastAPI application.

Run ``python app.py`` from the project root or use the documented Uvicorn
command. Both entry points serve the same API and browser frontend.
"""

import uvicorn

from backend.app.config import get_config
from backend.app.main import app


if __name__ == "__main__":
    config = get_config()
    uvicorn.run(app, host=config.navis_host, port=config.navis_port)
