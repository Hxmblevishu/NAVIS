"""Runtime settings manager with persistent disk storage for NAVIS."""
import json
import logging
from pathlib import Path
from threading import Lock
from typing import Optional
from backend.app.config import get_config
from backend.app.schemas import SettingsResponse, SettingsUpdate

logger = logging.getLogger("navis.settings")
_settings_lock = Lock()


class SettingsManager:
    """Manages active runtime settings and file-based persistence."""

    def __init__(self, persistence_file: Optional[str] = None):
        cfg = get_config()
        self.persistence_path = Path(persistence_file or cfg.settings_file)
        self._current = SettingsResponse(
            yolo_confidence=cfg.yolo_confidence,
            frame_interval_ms=cfg.frame_interval_ms,
            alert_cooldown_s=cfg.alert_cooldown_s,
            sensor_max_age_ms=cfg.sensor_max_age_ms,
            audio_verbosity="concise",
            distance_warn_threshold_m=cfg.distance_warn_threshold_m,
            measurement_units="metres",
        )
        self._load_from_disk()

    def _load_from_disk(self) -> None:
        """Load persisted settings from JSON file if available."""
        if not self.persistence_path.exists():
            return
        try:
            with open(self.persistence_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            # Validate loaded data against schema
            updated = self._current.model_copy(update=data)
            self._current = updated
            logger.info(f"Loaded persistent settings from {self.persistence_path}")
        except Exception as e:
            logger.warning(f"Could not load settings from {self.persistence_path}, using defaults: {e}")

    def _save_to_disk(self) -> None:
        """Save active settings to JSON file."""
        try:
            self.persistence_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.persistence_path, "w", encoding="utf-8") as f:
                f.write(self._current.model_dump_json(indent=2))
        except Exception as e:
            logger.error(f"Failed to persist settings to disk: {e}")

    def get_settings(self) -> SettingsResponse:
        """Return a copy of the current validated settings."""
        with _settings_lock:
            return self._current.model_copy()

    def update_settings(self, update: SettingsUpdate) -> SettingsResponse:
        """Update configurable settings and persist to disk."""
        with _settings_lock:
            update_data = {k: v for k, v in update.model_dump().items() if v is not None}
            if not update_data:
                return self._current.model_copy()

            # Create updated model
            new_settings = self._current.model_copy(update=update_data)
            self._current = new_settings
            self._save_to_disk()
            logger.info(f"Updated runtime settings: {update_data}")
            return self._current.model_copy()


_manager_instance: Optional[SettingsManager] = None


def get_settings_manager() -> SettingsManager:
    """Retrieve singleton SettingsManager instance."""
    global _manager_instance
    if _manager_instance is None:
        _manager_instance = SettingsManager()
    return _manager_instance
