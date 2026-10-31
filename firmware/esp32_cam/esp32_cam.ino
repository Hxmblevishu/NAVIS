/*
  NAVIS - ESP32-CAM (AI-Thinker) Camera & VL530X / VL53L0X Distance Sensor Firmware
  SAARTHI 2026 Hackathon

  Hardware:
    - ESP32-CAM (AI-Thinker module) with OV2640 camera
    - STMicroelectronics VL53L0X Time-of-Flight (ToF) laser ranging sensor
      (Ubiquitously sold as "VL530X", "GY-530", or "VL53L0X V2" breakout boards)

  Pinout (AI-Thinker ESP32-CAM to VL530X / VL53L0X):
    - VL530X VCC / VIN -> ESP32-CAM 3.3V (or 5V if breakout has 3.3V LDO regulator)
    - VL530X GND       -> ESP32-CAM GND
    - VL530X SCL       -> ESP32-CAM GPIO 15 (I2C SCL, with 4.7k pull-up resistor to 3.3V)
    - VL530X SDA       -> ESP32-CAM GPIO 13 (I2C SDA, with 4.7k pull-up resistor to 3.3V)
    - VL530X XSHUT     -> Not connected / Floating (internal pull-up keeps chip enabled)
    - VL530X GPIO1     -> Not connected (interrupt pin not required for polled ranging)
    - Flash Mode: Connect GPIO 0 to GND while uploading, disconnect for normal boot.

  Endpoints Exposed:
    - http://<esp32_ip>/capture -> JPEG snapshot
    - Pushes periodic distance measurements to laptop backend:
      POST http://<laptop_ip>:8000/api/sensors/distance
*/

#include "esp_camera.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <Wire.h>
#include "Adafruit_VL53L0X.h"

// ============================================================================
// Network and Backend Configuration (Edit before flashing)
// ============================================================================
const char* ssid = "YOUR_WIFI_SSID";
const char* password = "YOUR_WIFI_PASSWORD";
const char* backend_url = "http://192.168.1.100:8000/api/sensors/distance";

// Telemetry interval (ms)
const unsigned long SENSOR_PUSH_INTERVAL_MS = 500;
unsigned long last_sensor_push = 0;

// VL53L0X I2C Pin Configuration (AI-Thinker ESP32-CAM)
#define I2C_SDA_PIN 13
#define I2C_SCL_PIN 15

Adafruit_VL53L0X lox = Adafruit_VL53L0X();
bool vl53l0x_available = false;

// ============================================================================
// Camera Pin Definitions (AI-Thinker Model)
// ============================================================================
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27

#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

WiFiServer cameraServer(80);

// ============================================================================
// Camera Initialization
// ============================================================================
bool initCamera() {
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;

  if (psramFound()) {
    config.frame_size = FRAMESIZE_VGA; // 640x480
    config.jpeg_quality = 12;
    config.fb_count = 2;
  } else {
    config.frame_size = FRAMESIZE_CIF; // 400x296
    config.jpeg_quality = 14;
    config.fb_count = 1;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("Camera init failed with error 0x%x\n", err);
    return false;
  }

  sensor_t * s = esp_camera_sensor_get();
  s->set_brightness(s, 1);
  s->set_contrast(s, 1);
  s->set_saturation(s, 0);

  Serial.println("Camera initialized successfully.");
  return true;
}

// ============================================================================
// VL530X / VL53L0X Distance Sensor Initialization
// ============================================================================
bool initVL53L0X() {
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN, 100000); // 100kHz I2C clock
  if (!lox.begin()) {
    Serial.println("WARNING: Failed to initialize VL530X / VL53L0X ToF sensor.");
    Serial.println("Check I2C wiring (SDA -> GPIO 13, SCL -> GPIO 15, VCC -> 3.3V, GND -> GND).");
    Serial.println("Verify 4.7k pull-up resistors on SDA/SCL and ensure XSHUT is floating or tied to 3.3V.");
    vl53l0x_available = false;
    return false;
  }
  Serial.println("VL530X / VL53L0X Time-of-Flight sensor online.");
  vl53l0x_available = true;
  return true;
}

// ============================================================================
// Telemetry Transmission to Laptop Backend
// ============================================================================
void pushSensorTelemetry(float distance_m, bool valid) {
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  HTTPClient http;
  http.begin(backend_url);
  http.addHeader("Content-Type", "application/json");

  // Construct JSON telemetry matching NAVIS SensorTelemetryRequest schema
  // (Both 'VL530X' and 'VL53L0X' are recognized and normalized by backend)
  String payload = "{";
  payload += "\"sensor_id\":\"esp32_vl530x\",";
  payload += "\"sensor_model\":\"VL530X\",";
  payload += "\"distance_m\":" + (valid ? String(distance_m, 2) : "null") + ",";
  payload += "\"valid\":" + String(valid ? "true" : "false") + ",";
  payload += "\"quality\":0.95";
  payload += "}";

  int httpCode = http.POST(payload);
  if (httpCode <= 0) {
    Serial.printf("[HTTP] VL530X telemetry push failed: %s\n", http.errorToString(httpCode).c_str());
  }
  http.end();
}

void readAndPushDistance() {
  if (!vl53l0x_available) {
    // Attempt re-initialization if sensor was not ready at boot
    initVL53L0X();
    return;
  }

  VL53L0X_RangingMeasurementData_t measure;
  lox.rangingTest(&measure, false);

  bool valid = false;
  float distance_m = 0.0;

  // Status 4 indicates target out of range or laser phase fail
  if (measure.RangeStatus != 4) {
    distance_m = measure.RangeMilliMeter / 1000.0f; // Convert mm to metres
    // Range validation: VL53L0X standard range is 0.03m to 2.0m
    if (distance_m >= 0.03f && distance_m <= 2.0f) {
      valid = true;
    }
  }

  pushSensorTelemetry(distance_m, valid);
}

// ============================================================================
// Setup and Loop
// ============================================================================
void setup() {
  Serial.begin(115200);
  Serial.println("\n--- NAVIS ESP32-CAM & VL53L0X Firmware Starting ---");

  // 1. Initialize camera
  if (!initCamera()) {
    Serial.println("FATAL: Camera initialization failed. Halting.");
    while (true) { delay(1000); }
  }

  // 2. Initialize VL53L0X ToF sensor
  initVL53L0X();

  // 3. Connect to Wi-Fi
  WiFi.begin(ssid, password);
  Serial.print("Connecting to Wi-Fi");
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWi-Fi Connected!");
    Serial.print("ESP32-CAM IP: http://");
    Serial.println(WiFi.localIP());
    Serial.println("Snapshot endpoint: http://" + WiFi.localIP().toString() + "/capture");
  } else {
    Serial.println("\nWARNING: Wi-Fi connection timed out. Camera running offline.");
  }

  cameraServer.begin();
}

void loop() {
  // Handle HTTP client snapshot requests from laptop
  WiFiClient client = cameraServer.available();
  if (client) {
    String req = client.readStringUntil('\r');
    client.flush();

    if (req.indexOf("/capture") != -1) {
      camera_fb_t * fb = esp_camera_fb_get();
      if (!fb) {
        client.println("HTTP/1.1 500 Camera Capture Failed\r\n\r\n");
      } else {
        client.println("HTTP/1.1 200 OK");
        client.println("Content-Type: image/jpeg");
        client.println("Content-Length: " + String(fb->len));
        client.println("Access-Control-Allow-Origin: *");
        client.println("Connection: close\r\n");
        client.write(fb->buf, fb->len);
        esp_camera_fb_return(fb);
      }
    } else {
      client.println("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nNAVIS ESP32-CAM Ready with VL53L0X. GET /capture for frame.");
    }
    client.stop();
  }

  // Periodic distance sensor reading and telemetry push to laptop backend
  unsigned long now = millis();
  if (now - last_sensor_push >= SENSOR_PUSH_INTERVAL_MS) {
    last_sensor_push = now;
    readAndPushDistance();
  }
}
