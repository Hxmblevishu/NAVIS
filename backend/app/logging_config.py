"""Structured logging and JSONL event recording for NAVIS backend."""
import json
import logging
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock
from typing import Any, Dict, Optional

_jsonl_lock = Lock()
_events_log_path: Optional[Path] = None


def setup_logging(log_level: str = "INFO", events_log_file: str = "data/events.jsonl") -> logging.Logger:
    """Configure system logging and initialize the JSONL event log directory."""
    global _events_log_path

    numeric_level = getattr(logging, log_level.upper(), logging.INFO)

    # Configure root logger with structured console output
    logging.basicConfig(
        level=numeric_level,
        format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[logging.StreamHandler(sys.stdout)],
        force=True,
    )

    logger = logging.getLogger("navis")
    logger.setLevel(numeric_level)

    # Ensure events log directory exists
    log_path = Path(events_log_file)
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        _events_log_path = log_path
        logger.info(f"Structured event logging initialized at {_events_log_path.resolve()}")
    except Exception as e:
        logger.error(f"Failed to create event log directory: {e}")
        _events_log_path = None

    return logger


def record_event_log(
    event_id: str,
    component: str,
    message: str,
    priority: Optional[str] = None,
    frame_id: Optional[str] = None,
    detections: Optional[list] = None,
    sensor_reading: Optional[dict] = None,
    fusion_outcome: Optional[str] = None,
    fusion_reason: Optional[str] = None,
    hazard_decision: Optional[str] = None,
    processing_latency_ms: Optional[float] = None,
    error_category: Optional[str] = None,
    extra: Optional[Dict[str, Any]] = None,
) -> None:
    """Append a structured JSON line entry to the evaluation log file."""
    if _events_log_path is None:
        return

    record = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "event_id": event_id,
        "component": component,
        "priority": priority,
        "message": message,
        "frame_id": frame_id,
        "detections": detections,
        "sensor_reading": sensor_reading,
        "fusion_outcome": fusion_outcome,
        "fusion_reason": fusion_reason,
        "hazard_decision": hazard_decision,
        "processing_latency_ms": processing_latency_ms,
        "error_category": error_category,
    }
    if extra:
        record["extra"] = extra

    # Strip None values for compact representation
    record = {k: v for k, v in record.items() if v is not None}

    line = json.dumps(record, ensure_ascii=False)
    with _jsonl_lock:
        try:
            with open(_events_log_path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass  # Avoid crashing application if logging encounters disk failure
