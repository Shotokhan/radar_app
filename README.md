# 📡 Radar App

A passive RF radar for Android that discovers nearby devices by walking — using Bluetooth LE and Wi-Fi signals to build a real-time spatial map of your environment.

## Features

- **Passive BLE scanning** — discovers any Bluetooth device broadcasting nearby
- **Wi-Fi AP detection** — maps access points as spatial anchors
- **Motion-correlated convergence** — walking toward a device moves it closer to radar center
- **MAC rotation detection** — tracks devices across address changes using spatial-temporal continuity
- **2D radar view** — classic sweep with device blips and bearing cones
- **3D sphere view** — star topology showing devices at elevation for multi-floor use
- **Pinch to zoom** — see distant devices or separate close ones
- **Device detail panel** — tap any blip for full info: name, IP, protocols, RSSI trend, MAC history

## Architecture

```
Layer 4: UI (RadarScreen, RadarCanvas2D/3D, DeviceDetailSheet)
              ↕  TrackedDevice
Layer 3: ConvergenceEngine (motion-correlated belief updates, MAC rotation, merging)
              ↕  SignalObservation / MotionSample
Layer 2: MotionTracker (accelerometer + magnetometer fusion)
Layer 1: SignalProviders (BleSignalProvider, WifiApSignalProvider)
```

## Getting the APK

### Via GitHub Actions (recommended)
1. Push this repo to GitHub
2. Go to **Actions** → **Build & Release APK**
3. Each push to `main` automatically creates a release with the APK attached
4. Download `app-debug.apk` from the release, sideload on your device

### Manually (if you have the Android SDK)
```bash
chmod +x gradlew
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Installation

1. On your Android device: **Settings → Security → Install unknown apps** → enable for your browser
2. Download the APK and tap to install
3. Grant Bluetooth and Location permissions when prompted

## Requirements

- Android 8.0+ (API 26)
- Bluetooth LE support
- Location permission (required by Android for BLE scanning)

## Development

Two developers can work independently by forking from the interface definitions:

- **Dev B** owns `data/provider/` (Layer 1) and `domain/motion/` (Layer 2)
- **Dev A** owns `domain/engine/` (Layer 3) and `ui/` (Layer 4)

The contract is defined in:
- `SignalProvider` interface → `SignalObservation` data class
- `MotionTracker` interface → `MotionSample` data class
- `ConvergenceEngine.devices: StateFlow<List<TrackedDevice>>`

Tests use `FakeSignalProvider` and `FakeMotionTracker` exclusively — no hardware needed.

```bash
./gradlew test    # run unit tests
```
