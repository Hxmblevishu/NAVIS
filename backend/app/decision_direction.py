from typing import Dict, List


def overlaps(a1, a2, b1, b2):
    return max(a1, b1) < min(a2, b2)


def _center_region_hazard(hazard: Dict) -> bool:
    bbox = hazard.get("bbox")
    position = hazard.get("position")
    if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
        return False

    x1, y1, x2, y2 = [float(value) for value in bbox]
    if position == "center":
        return y2 >= 0.62 or (x1 >= 0.35 and x2 <= 0.65)

    center_x = hazard.get("center", [0.5, 0.5])[0]
    if isinstance(center_x, (int, float)):
        return 0.35 <= center_x <= 0.65 and y2 >= 0.45

    return False


def decide_direction(
    detections: List[Dict],
    hazards: List[Dict]
) -> Dict:

    if not hazards:
        return {
            "action": "monitor",
            "message": "",
            "hazard_detected": False
        }

    people_ahead = []
    for hazard in hazards:
        if hazard.get("label") != "person":
            continue
        bbox = hazard.get("bbox")
        if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
            continue
        x1, _, x2, y2 = [float(value) for value in bbox]
        if overlaps(x1, x2, 0.35, 0.65) and y2 >= 0.35:
            people_ahead.append(hazard)

    if people_ahead:
        return {
            "action": "person_ahead",
            "message": "Person ahead. Please pause and check your surroundings.",
            "hazard_detected": True,
            "priority": "high",
            "reason": (
                "A person was detected in the forward image region. "
                "Distance and collision risk are not measured."
            )
        }

    center_hazards = [
        hazard for hazard in hazards
        if _center_region_hazard(hazard)
    ]

    if not center_hazards:
        labels = ", ".join(sorted({hazard.get("label", "object") for hazard in hazards}))
        return {
            "action": "caution",
            "message": (
                f"Potential obstacle detected: {labels}. "
                "Please slow down and check the path ahead."
            ),
            "hazard_detected": True
        }

    left_occupied = False
    right_occupied = False

    for item in hazards:
        bbox = item.get("bbox")
        if not isinstance(bbox, (list, tuple)) or len(bbox) != 4:
            continue

        x1, y1, x2, y2 = [float(value) for value in bbox]

        if y2 < 0.45:
            continue

        if overlaps(x1, x2, 0.0, 0.35):
            left_occupied = True

        if overlaps(x1, x2, 0.65, 1.0):
            right_occupied = True

    if not left_occupied and right_occupied:
        action = "possible_left"
        message = (
            "Obstacle ahead. A possible opening is visible on your left. "
            "Stop and verify before moving."
        )
    elif not right_occupied and left_occupied:
        action = "possible_right"
        message = (
            "Obstacle ahead. A possible opening is visible on your right. "
            "Stop and verify before moving."
        )
    elif left_occupied and right_occupied:
        action = "stop"
        message = (
            "Obstacle ahead and both sides appear occupied. Please stop."
        )
    else:
        action = "stop"
        message = (
            "Obstacle ahead. I cannot verify a clear direction. Please stop."
        )

    return {
        "action": action,
        "message": message,
        "hazard_detected": True,
        "priority": max(
            (item.get("priority", "low") for item in hazards),
            key=lambda priority: {"high": 2, "medium": 1, "low": 0}.get(priority, 0)
        ),
        "left_occupied": left_occupied,
        "right_occupied": right_occupied,
        "reason": (
            "Based on detected bounding boxes. "
            "Safe passage is not verified."
        )
    }