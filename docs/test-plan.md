# NAVIS Test Plan & Validation Strategy

## SAARTHI 2026 Hackathon | Evaluation & Reliability

---

### 1. Test Strategy Overview

The testing strategy validates the end-to-end perception, hazard filtering, voice routing, and Android WebSocket pipeline using deterministic automated unit tests and supervised live scenario testing.

```
+-------------------------------------------------------------------------------+
|                             Automated Test Suite                              |
|                          (37 Passed Unit & API Tests)                         |
+-------------------------------------------------------------------------------+
        |                     |                       |                    |
        v                     v                       v                    v
  [ Camera Tests ]     [ Sensor Tests ]       [ Fusion Tests ]      [ Hazard Tests ]
  - Mock generator     - Range validation     - Aligned forward     - Persistence (2 f)
  - Bounded buffer     - Stale detection      - Peripheral reject   - Cooldown (4.0s)
  - Stale rejection    - Telemetry push       - Dominant target     - Critical bypass
  - Lifecycle          - Error injection      - Multi-target        - Qualified speech
```

---

### 2. Automated Test Matrix

| Test Module | Coverage Area | Key Verification Scenarios | Status |
| :--- | :--- | :--- | :--- |
| `test_health.py` | Health endpoint & degradation | Schema compliance, degraded component reporting | Passed (2/2) |
| `test_camera.py` | Frame acquisition & buffering | Synthetic frame generation, bounded buffer, stale rejection | Passed (4/4) |
| `test_distance_sensor.py` | Physical sensor interface | Valid range (0.05m–4m), out-of-range, stale check, telemetry push | Passed (6/6) |
| `test_detection.py` | Ultralytics YOLO26n | Model loading, empty image handling, left/center/right zones | Passed (3/3) |
| `test_sensor_fusion.py` | Vision-distance association | Aligned forward match, peripheral rejection, stale rejection, cautious multi-target | Passed (5/5) |
| `test_hazard_engine.py` | Decision & policy engine | Temporal persistence, critical urgency bypass, duplicate cooldown, failure alerts | Passed (5/5) |
| `test_settings.py` | Settings & persistence | Defaults, partial updates, JSON file persistence & reloading | Passed (2/2) |
| `test_events.py` | WebSocket manager | Client connect/disconnect, JSON broadcast, dropped slow client handling | Passed (2/2) |
| `test_api.py` | Full REST API surface | Snapshot, detect, sensors, commands, navigation route/progress, settings | Passed (8/8) |
| **Total** | **All Subsystems** | **37 Automated Tests** | **37 / 37 Passed** |

---

### 3. Controlled Hackathon Rehearsal Scenarios

#### Scenario 1: Stationary Clear Corridor
- **Setup:** Indoor hallway with clear floor, sensor aligned forward.
- **Expected Spoken Alert:** None (no obstacles within warning threshold).
- **Validation Criteria:** Zero false collision alarms over 60 seconds.

#### Scenario 2: Approaching Pedestrian Ahead
- **Setup:** A person walks towards the user along the central optical axis.
- **Expected Spoken Alert:** *"Person ahead, approximately one metre away."*
- **Validation Criteria:** Duplicate announcements suppressed for at least 4.0 seconds.

#### Scenario 3: Peripheral Obstacle (Chair on Left)
- **Setup:** A chair positioned on the left side of the room.
- **Expected Spoken Alert:** *"Possible chair on left."*
- **Validation Criteria:** Ranging sensor distance is **not** attributed to the chair (`distance_status = "unassociated"`).

#### Scenario 4: Hardware Disconnection / Camera Drop
- **Setup:** Power off ESP32-CAM or disconnect Wi-Fi.
- **Expected Spoken Alert:** *"Camera unavailable. Obstacle detection is currently degraded."*
- **Validation Criteria:** Backend immediately transitions to degraded state without server crash.

---

### 4. Quantitative Performance Metrics

All pipeline runs are logged to `data/events.jsonl`. Key evaluation metrics tracked include:

1. **Inference Latency (`inference_ms`):** Target median <= 40ms on laptop CPU.
2. **Duplicate Suppression Efficiency:** Reduction in duplicate announcements compared to unfiltered baseline.
3. **Sensor Validity Rate:** Count of valid vs invalid/stale readings.
4. **Command Routing Accuracy:** Voice intent recognition success rate (target >= 90%).
