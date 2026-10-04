# Synth Radar

On-device, camera-based vehicle speed estimation for Android (Flutter + native Camera2).
See [plan.md](plan.md) for the design and roadmap.

## Current state: Phase 0 recorder

The app records measurement-grade passes: video with per-frame lens metadata, a high-rate
IMU log, and tester annotations. There is no speed estimate yet.

| Path | What |
|---|---|
| `app/` | Flutter app: Record, Passes (annotate/share), Lens (calibration dump) |
| `packages/radar_camera/` | Flutter plugin, Kotlin: Camera2 capture (EIS off, zoom 1.0), MediaCodec recording, metadata + IMU logging |
| `tools/` | Python: `inspect_recording.py` (bundle quality report), `calibrate_checkerboard.py`, lens math |
| `docs/test-protocol.md` | How to record test passes and get ground-truth speeds |

### Recording bundle format

One directory per pass:

| File | Contents |
|---|---|
| `video.mp4` | H.264 video (+ AAC audio); PTS on the boot-time clock |
| `video_frames.csv` | MP4 frame index → PTS (µs, boot-time clock) |
| `frames.jsonl` | One Camera2 capture result per frame: sensor timestamp, exposure, rolling-shutter skew, intrinsics, distortion, crop, OIS, active physical lens |
| `imu.csv` | Gyro, accelerometer, gravity, rotation vectors at the fastest rate (ns, boot-time clock) |
| `camera_characteristics.json` | Every characteristic of every camera |
| `session.json` | Device, mode, encoder, wall-clock ↔ boot-clock pair, summary |
| `notes.json` | Tester annotations: direction, vehicle, ground-truth speed and source, geometry |

## Building

```sh
cd app
flutter pub get
flutter build apk --release --target-platform android-arm64   # → build/app/outputs/flutter-apk/app-release.apk
```

Requires Flutter 3.47+ and Android SDK platform 37. CI builds the APK on every push and publishes
GitHub Releases from `main`, version tags and manual runs. Latest build:
<https://github.com/mckoss/synth-radar/releases/latest/download/synth-radar.apk>.
See [docs/releases.md](docs/releases.md), including the one-time signing-key setup.

```sh
python3 -m unittest discover -s tools/tests   # tool tests
python3 tools/inspect_recording.py <bundle-dir>
```
