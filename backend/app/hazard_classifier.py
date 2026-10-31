from typing import Dict, List


HAZARD_CLASSES = {
    "person",
    "bicycle",
    "bike",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "train",
    "bench",
    "chair",
    "couch",
    "sofa",
    "bed",
    "table",
    "dining table",
    "desk",
    "potted plant",
    "plant",
    "toilet",
    "suitcase",
    "backpack",
    "handbag",
    "umbrella",
    "skateboard",
    "traffic light",
    "stop sign",
    "dog",
    "cat",
}

PRIORITY_BY_LABEL = {
    "person": "high",
    "bicycle": "high",
    "bike": "high",
    "car": "high",
    "motorcycle": "high",
    "bus": "high",
    "truck": "high",
    "train": "high",
    "bench": "medium",
    "chair": "medium",
    "couch": "medium",
    "sofa": "medium",
    "bed": "medium",
    "table": "medium",
    "dining table": "medium",
    "desk": "medium",
    "potted plant": "low",
    "plant": "low",
    "suitcase": "medium",
    "backpack": "medium",
    "handbag": "low",
    "umbrella": "low",
    "skateboard": "medium",
    "traffic light": "medium",
    "stop sign": "medium",
    "dog": "medium",
    "cat": "medium",
}


def _normalize_label(label: str) -> str:
    normalized = (label or "").strip().lower()
    aliases = {
        "diningtable": "dining table",
        "dining table": "dining table",
        "sofa": "sofa",
        "plant": "potted plant",
        "desk": "desk",
        "table": "table",
    }
    return aliases.get(normalized, normalized)


def classify_hazards(
    detections: List[Dict]
) -> List[Dict]:

    hazards: List[Dict] = []

    for item in detections:
        label = _normalize_label(item.get("label", ""))
        if label not in HAZARD_CLASSES:
            continue

        bbox = item.get("bbox")
        center = item.get("center", [0.5, 0.5])
        if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
            continue

        x1, y1, x2, y2 = [float(value) for value in bbox]
        center_x, center_y = [float(value) for value in center]

        in_lower_view = y2 >= 0.30 or center_y >= 0.30
        in_forward_view = x2 >= 0.08 and x1 <= 0.92

        if not (in_lower_view and in_forward_view):
            continue

        bbox_height = max(0.0, y2 - y1)
        bbox_width = max(0.0, x2 - x1)
        base_priority = PRIORITY_BY_LABEL.get(label, "medium")

        if item.get("position") == "center" and bbox_height >= 0.22:
            priority = "high" if base_priority in {"high", "medium"} else "medium"
        elif (item.get("position") in {"left", "right"}) and bbox_width >= 0.22:
            priority = "medium" if base_priority != "low" else "low"
        else:
            priority = base_priority

        hazards.append({
            **item,
            "label": label,
            "hazard": True,
            "risk": "potential",
            "priority": priority,
            "note": (
                "Potential obstacle. "
                "Actual distance is not verified."
            )
        })

    return sorted(hazards, key=lambda entry: (
        {"high": 0, "medium": 1, "low": 2}[entry.get("priority", "medium")],
        float(entry.get("confidence", 0.0)),
    ))