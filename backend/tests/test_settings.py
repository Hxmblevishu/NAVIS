"""Unit tests for settings manager, validation, and file persistence."""
from pathlib import Path
import pytest
from backend.app.schemas import SettingsUpdate
from backend.app.settings import SettingsManager


@pytest.fixture
def temp_settings(tmp_path):
    settings_file = tmp_path / "test_settings.json"
    mgr = SettingsManager(persistence_file=str(settings_file))
    return mgr, settings_file


def test_settings_defaults(temp_settings):
    mgr, _ = temp_settings
    curr = mgr.get_settings()
    assert curr.yolo_confidence == 0.55
    assert curr.frame_interval_ms == 500
    assert curr.alert_cooldown_s == 4.0
    assert curr.measurement_units == "metres"


def test_settings_update_and_persistence(temp_settings):
    mgr, filepath = temp_settings

    # Apply partial update
    update = SettingsUpdate(
        yolo_confidence=0.70,
        alert_cooldown_s=6.0,
        audio_verbosity="detailed",
    )
    res = mgr.update_settings(update)
    assert res.yolo_confidence == 0.70
    assert res.alert_cooldown_s == 6.0
    assert res.audio_verbosity == "detailed"
    assert res.measurement_units == "metres"  # Unchanged

    # Verify file was written
    assert filepath.exists()

    # Re-instantiate manager pointing to same file
    mgr2 = SettingsManager(persistence_file=str(filepath))
    loaded = mgr2.get_settings()
    assert loaded.yolo_confidence == 0.70
    assert loaded.alert_cooldown_s == 6.0
    assert loaded.audio_verbosity == "detailed"
