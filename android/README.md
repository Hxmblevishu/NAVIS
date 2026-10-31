# NAVIS Android Companion

The Android app is a client for the NAVIS FastAPI backend. It reads device, sensor, and perception state from the service; sends voice commands, scene-analysis, OCR, and navigation requests; and receives live perception events. Android speech recognition and text-to-speech provide the phone's voice input and output.

The app displays backend results and reports unavailable capabilities explicitly. It does not fill missing camera, sensor, or route data with sample values. A physical camera and distance sensor are required for live hardware readings. Pedestrian route calculation is provided by Google Maps Routes API when the backend has a configured key.

## Connect the app

1. Start the backend on the computer with `--host 0.0.0.0 --port 8000` so a phone on the same network can reach it.
2. In the app's **Settings**, enter the backend URL. Use `http://10.0.2.2:8000` from the standard Android emulator, or the computer's LAN address (for example, `http://192.168.1.105:8000`) from a phone.
3. If the backend `.env` sets `API_TOKEN`, enter the same value in the app's **API token** field. The app persists this value locally. A build-time token can be supplied using a `NAVIS_API_TOKEN` Gradle property or environment variable, but is optional.
4. To enable pedestrian routes, configure `GOOGLE_MAPS_API_KEY` on the backend with access to Google Maps Routes API. Without it, route requests report unavailable.

The app requires location permission to request and update routes. Navigation currently requests walking routes. An unavailable sensor is shown as unavailable; the app does not infer physical distance from camera boxes.

## Build

Open the `android` folder in Android Studio and sync the project. The project uses Gradle 9.6.0, Android Gradle Plugin 9.5.0, JDK 17, and Android SDK 34. Build the debug app with:

```powershell
.\gradlew.bat :app:assembleDebug
```

The Android client uses the same endpoint and response definitions documented in [the API contract](../docs/api-contract.md). Backend setup and test commands are in the [main project README](../README.md).
