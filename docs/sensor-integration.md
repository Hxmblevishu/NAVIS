# NAVIS VL530X / VL53L0X Distance Sensor & Hardware Integration Guide

## SAARTHI 2026 Hackathon | Confirmed Hardware: VL530X / STMicroelectronics VL53L0X

---

### 1. Sensor Overview: VL530X / VL53L0X Time-of-Flight (ToF)

> [!NOTE]
> **Hardware Clarification:** The sensor labeled **VL530X** (frequently sold as the **GY-530** or **VL530X V2** breakout board) uses the **STMicroelectronics VL53L0X** FlightSense™ Time-of-Flight laser ranging chip. The NAVIS backend, ESP32-CAM firmware, and configuration automatically recognize both `VL530X` and `VL53L0X` interchangeably.

The **VL530X / VL53L0X** is a high-precision laser-ranging sensor based on FlightSense™ technology. Unlike ultrasonic sensors that bounce sound waves or optical triangulation sensors that depend on target reflectance, the VL530X emits invisible 940 nm infrared laser photons and measures the exact picoseconds required for photons to reflect back to its Single Photon Avalanche Diode (SPAD) array.

#### Key Technical Specifications:
- **Operating Principle:** Laser Time-of-Flight (940 nm Class 1 eye-safe VCSEL).
- **Communication Protocol:** I²C (Default 7-bit slave address: `0x29`).
- **Operating Voltage:** 2.6 V to 3.5 V (Most breakout boards include an onboard 3.3V LDO regulator accepting 3.3V or 5V).
- **Minimum Ranging Distance:** 0.03 m (30 mm).
- **Maximum Ranging Distance:** 
  - Standard/Indoor: Up to 2.0 m (2000 mm).
  - Outdoor bright sunlight: ~0.8 m to 1.2 m (due to ambient sunlight infrared noise).
- **Field of View (Ranging Cone):** 25° conical cone.
- **Ranging Precision:** Typically ±3% under indoor controlled conditions.

---

### 2. Wiring VL53L0X to the ESP32-CAM (AI-Thinker)

The ESP32-CAM shares pins between the camera, micro-SD slot, and flash LED. The recommended and tested I²C pin assignment uses the high-speed SD card pins when no micro-SD card is mounted:

```
+------------------------+                    +-------------------------+
|   VL53L0X Breakout     |                    |   ESP32-CAM AI-Thinker  |
+------------------------+                    +-------------------------+
|  VIN                   | -----------------> |  3.3V (or 5V if 5V tolerant)
|  GND                   | -----------------> |  GND                    |
|  SDA                   | -----------------> |  GPIO 13 (HS2_DATA3)    |
|  SCL                   | -----------------> |  GPIO 15 (HS2_CMD)      |
|  XSHUT (optional)      | -----------------> |  Not connected / High   |
|  GPIO1 (interrupt)     | -----------------> |  Not connected          |
+------------------------+                    +-------------------------+
                                 |
              [ 4.7kΩ Pull-Up Resistors ]
              - Pull SDA (GPIO 13) to 3.3V
              - Pull SCL (GPIO 15) to 3.3V
```

> [!IMPORTANT]
> **I²C Pull-Up Resistors:** Most commercial purple/blue VL53L0X breakout boards have onboard 4.7kΩ or 10kΩ pull-up resistors to 3.3V. If your board does not include them, connect external 4.7kΩ resistors between SDA/SCL and 3.3V.
> **Flashing Pin:** Connect GPIO 0 to GND while uploading code via USB-to-UART, then disconnect GPIO 0 from GND and hit Reset for normal boot.

---

### 3. Arduino IDE Setup for ESP32-CAM Firmware

1. In the Arduino Library Manager (`Ctrl + Shift + I`), search and install:
   - **`Adafruit_VL53L0X`** by Adafruit (version 1.2.4 or higher).
2. Open [firmware/esp32_cam/esp32_cam.ino](file:///d:/Projects/projects%20of%20codex/NAVIS/firmware/esp32_cam/esp32_cam.ino).
3. Set your Wi-Fi credentials:
   ```cpp
   const char* ssid = "YOUR_WIFI_SSID";
   const char* password = "YOUR_WIFI_PASSWORD";
   const char* backend_url = "http://<YOUR_LAPTOP_IP>:8000/api/sensors/distance";
   ```
4. Select Board: **AI Thinker ESP32-CAM** (CPU Frequency: 240MHz, Flash Frequency: 80MHz).
5. Upload and open the Serial Monitor at 115200 baud.
6. The firmware initializes the OV2640 camera and VL53L0X sensor, connects to Wi-Fi, and streams telemetry at 2 Hz (`SENSOR_PUSH_INTERVAL_MS = 500`).

---

### 4. Telemetry Format Transmitted to Laptop Backend

The ESP32-CAM posts structured JSON to `POST /api/sensors/distance`:

```json
{
  "sensor_id": "esp32_vl53l0x",
  "sensor_model": "VL53L0X",
  "distance_m": 1.15,
  "valid": true,
  "quality": 0.95
}
```

- When the laser target is out of range (> 2.0 m) or photon return is weak (RangeStatus == 4), `valid` is sent as `false` and `distance_m` as `null`.

---

### 5. Backend Configuration in `.env`

In your [.env](file:///d:/Projects/projects%20of%20codex/NAVIS/.env) file:

```ini
DISTANCE_SENSOR_MODE=auto
DISTANCE_SENSOR_MODEL=VL53L0X
SENSOR_MIN_RANGE_M=0.03
SENSOR_MAX_RANGE_M=2.0
DISTANCE_WARN_THRESHOLD_M=1.2
SENSOR_MAX_AGE_MS=1000
```

---

### 6. Sensor Fusion Behavior with the VL53L0X

1. **25° Forward Cone Alignment:**
   - The VL53L0X has a ~25° field of view directly in front of the glasses/wearable.
   - When YOLO detects an object in the **center zone**, the backend fuses the VL53L0X distance reading (`1.15m`) and generates:  
     *"Person ahead, approximately one metre away."*
   - If an object is detected on the **left** or **right**, the backend marks the reading as `unassociated` (because the VL53L0X laser points forward), generating:  
     *"Possible chair on left."*
2. **Hazard Warnings:**
   - Any center obstacle detected at `<= 0.8m` triggers an immediate **critical** alert.
   - Obstacles between `0.8m` and `1.2m` trigger a **high priority** alert.
