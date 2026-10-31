# Stage 1: Build & Runtime
FROM python:3.11-slim

# Avoid prompts during apt installs
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# Install system dependencies required for OpenCV, image processing, and networking
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy dependency specifications first for Docker layer caching
COPY backend/requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy backend source code, templates, and optional models
COPY backend /app/backend
COPY templates /app/templates

# Ensure data directory exists for settings and event logs
RUN mkdir -p /app/data

# Default port for Cloud Run / Container deployments
EXPOSE 8000

ENV NAVIS_HOST=0.0.0.0
ENV NAVIS_PORT=8000

# Run FastAPI backend via Uvicorn
CMD ["uvicorn", "backend.app.main:app", "--host", "0.0.0.0", "--port", "8000"]

