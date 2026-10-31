"""Speech text normalization shared by the intent engine."""

import re
import unicodedata

_WAKE_PREFIX = re.compile(r"^(?:(?:hey|hello|okay|ok)\s+)?navis\b\s*")


def normalize_command(text: str) -> str:
    """Normalize ASR punctuation, spacing, Unicode, and an optional NAVIS wake word."""
    normalized = unicodedata.normalize("NFKC", text or "").lower().strip()
    normalized = re.sub(r"[^\w\s]", " ", normalized, flags=re.UNICODE)
    normalized = re.sub(r"\s+", " ", normalized).strip()
    normalized = _WAKE_PREFIX.sub("", normalized).strip()
    return normalized
