# Synth Radar — Project Plan

A handheld, on-device, camera-based vehicle speed estimator. A pedestrian standing beside a
road points a phone at passing traffic. The app detects and tracks each vehicle, estimates its
speed with an explicit error band, reads its license plate when it can, saves a short evidence
clip, and helps the user send a report (email/share) to a chosen recipient.

- **Platform:** Flutter UI, native Android (Kotlin + C++) for the camera/ML pipeline. Android
  first; iOS later.
- **Test device:** Google Pixel 10 Pro (Tensor G5, 50 MP 1/1.31" main camera, ~25 mm-equivalent).
- **Constraint:** all capture-time processing is on-device. No cloud compute.
- **Licensing:** Apache-2.0 / MIT / BSD components only (no AGPL/GPL models or code).

---

## 1. Decisions made so far

| Topic | Decision |
|---|---|
| UI framework | Flutter (Dart) |
| First platform | Android, Pixel 10 Pro |
| Capture posture | **Handheld**, pedestrian at the roadside, car passing roughly side-to-side |
| Compute | On-device only for capture and speed estimation |
| Scale cues | Vehicle size priors (length, wheelbase, height), headlight/taillight spacing, and **license plate size** when visible |
| Licenses | Permissive only (Apache-2.0 is fine) — rules out Ultralytics YOLOv5/v8/11 (AGPL) and YOLOv9 reference code (GPL) |
| Real-time | Live on-screen estimate is desired; a more accurate offline re-analysis of saved clips is acceptable |

## 2. Open questions (not blocking Phase 0/1)

1. **Jurisdiction** (country/state): sets plate dimensions and formats, OCR character set, and the
   legal review of plate reading, audio recording, and citizen reporting. US is assumed below
   (plate 12 in × 6 in = 305 × 152 mm).
2. **Accuracy target:** proposed goal is **±3 mph (±5 km/h) at 95 % confidence for 20–45 mph at
   8–30 m range**, with the app refusing to show a number when the band is wider than ±15 %.
3. **Report recipient:** police non-emergency email, city traffic-calming program, HOA, etc. This
   affects report format, not architecture.
4. **Speed units and display:** mph vs km/h (settings toggle; default from locale).

---

## 3. How speed is measured (the core problem)

Speed is distance over time. The phone gives very accurate **time** (per-frame sensor timestamps)
and very accurate **angles** (lens calibration + gyroscope). What it lacks is **range**, the
metric distance to the car. All the cues the user listed are ways to recover range.

### 3.1 Geometry

For a camera with focal length `f` (pixels), an object of known real size `S` that appears
`s` pixels across is at range:

```
Z ≈ f · S / s
```

The car's world position in each frame is the **bearing** to the car (pixel → ray using lens
intrinsics, rotated into a world frame using the gyroscope orientation) multiplied by that
**range**. Speed is the slope of a constant-velocity (or constant-acceleration) fit of world
position against time across the whole track, not a frame-to-frame difference. The fit is what
turns noisy per-frame measurements into a tight estimate.

Key consequence: **a relative error in the size prior becomes the same relative error in speed.**
If we think the wheelbase is 2.8 m ± 8 %, speed is ± 8 % from that alone. So the plan puts effort
into getting the scale reference right and fusing several of them.

### 3.2 Scale cues, best to worst

| Cue | Known size | Typical uncertainty | When available |
|---|---|---|---|
| **License plate** (width and height) | US: 305 × 152 mm (standardized) | ~1–2 % physical; pixel error dominates (~54 px wide at 15 m in 4K) | Approaching/receding or oblique views; often not in a pure side view |
| **Wheelbase** (front/rear wheel–ground contact points) | Class prior: compact ~2.6 m, sedan ~2.8 m, SUV/pickup ~2.9–3.6 m | ±5–10 % by class; ~±1 % if make/model is known | Side view — **the best cue for the primary use case** |
| **Headlight / taillight spacing** | ~1.2–1.6 m by class | ±10–15 % | Front/rear views |
| **Overall length / height** | Class prior | ±10–20 % | Always, but bounding boxes are sloppy |
| **Ground plane** (phone height + gravity) | Phone held ~1.3–1.6 m above road; gravity vector from IMU | Range from depression angle to wheel contact; ±10 % from height uncertainty | Whenever wheels are visible; independent cross-check |
| **Make/model → spec sheet** | Exact wheelbase/length | ~1 % | User picks make/model in review (§3.6) now; automatic classifier later |

The estimator fuses every available cue per frame (weighted by its variance) and reports the
combined speed with an uncertainty from a proper error budget (prior uncertainty + pixel
localization noise + timestamp jitter + residual camera rotation error). The overlay shows
`32 mph ± 2` style ranges, and a confidence level.

**Wheelbase is a strong cue but it is not standardized.** Approximate published values:

| Vehicle | Wheelbase |
|---|---|
| Smart Fortwo | 1.87 m (73.7 in) |
| Mini Cooper hardtop | ~2.50 m |
| Toyota RAV4 | ~2.69 m |
| Honda Civic sedan | ~2.73 m |
| Toyota Camry | ~2.83 m |
| Tesla Model 3 | ~2.88 m |
| Ford F-150 SuperCrew (5.5 ft bed) | ~3.69 m |
| Full-size crew-cab long-bed pickup / 15-passenger van | ~4.0–4.2 m |

Most passenger cars and small SUVs cluster at **2.6–2.9 m**, so a class-based prior is good to
about ±5–7 %. Pickups and vans spread much wider. Two things tighten it:

- **Vehicle class** from the detector (car vs. pickup vs. van) narrows the prior.
- **Knowing the make/model** pins it to ~1 %. The user can supply this in review (§3.6); an
  automatic make/model classifier is a later addition.

### 3.3 Handheld camera motion

The pedestrian will pan to follow the car. That rotation must not be mistaken for car motion.

- Read the **gyroscope** at high rate (≥200 Hz) and integrate orientation, synchronized to frame
  `SENSOR_TIMESTAMP`s (same clock base on Pixel; verify in Phase 0).
- Express every bearing in a **gravity-aligned world frame**, so panning cancels out.
- Optionally refine with background feature tracking (static scene points) to correct gyro drift.
- Translation of the handheld phone (a few cm of hand shake) is negligible at 10–30 m.

### 3.4 Camera and lens model (no external database needed)

Android Camera2 exposes the factory calibration of each physical camera:

- `LENS_INTRINSIC_CALIBRATION` (fx, fy, cx, cy, skew), `LENS_DISTORTION`,
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`,
  `SENSOR_ROLLING_SHUTTER_SKEW` (per frame).

These are exactly the "optical profile" we need. Pitfalls to control:

- **Lock to the main physical camera** (no logical-multicamera lens switching, no zoom changes
  during a measurement).
- **Turn off electronic video stabilization** for the measurement stream; EIS crops and warps the
  image and invalidates the intrinsics. (OIS may stay on; its small principal-point shift goes in
  the error budget.)
- **Map intrinsics through the stream crop/scale** (active array → output stream resolution).
- **Rolling shutter:** a car moving sideways is captured at slightly different times top to
  bottom; use the per-frame skew to time-stamp each keypoint by its row.
- Fallback for devices that don't publish intrinsics: compute from focal length + sensor size,
  and offer an optional one-time checkerboard calibration (OpenCV) in a developer menu. Use the
  same checkerboard tool in Phase 0 to **verify** the Pixel's published values.

### 3.5 Doppler audio (future enhancement)

A passing car's tire/engine tones shift by the ratio `(c + v) / (c − v)` between approach and
recession (≈ 8 % at 30 mph). With the closest-approach time and distance known from vision, a
spectrogram fit of tonal components can give an **independent** speed estimate that has no
scale-prior error at all. It's most valuable where vision is weakest (cars coming head-on). It is
planned as a later phase; the audio track is recorded with clips from the start so the data
exists.

### 3.6 Human-in-the-loop review (optional accuracy boost)

The automatic estimate is never blocked on the user, but before a report is sent the user can
open a **review screen** for the saved clip and tighten the estimate. The two sources of error
are handled separately:

- **Pixel error** (where exactly the wheels touch the road): the user drags **wheel–ground contact
  markers** onto the front and rear tires on two or more frames, ideally far apart in time. The
  app pre-places the markers from the keypoint model and the segmentation mask; the user only
  nudges them. Tapping the plate corners works the same way.
- **Size-prior error** (what the true wheelbase is): the user selects the **vehicle class or
  exact make/model** from a searchable list backed by a bundled wheelbase/length table. This is
  the bigger win: it cuts the wheelbase uncertainty from ±5–10 % to ~1 %.
- **Scene calibration** (optional): the user marks two points on the road a known distance apart
  (lane width, a measured curb segment, parking-space markings). This gives an independent check
  of range and of the ground plane.
- **Corrections:** fix plate text, merge or split tracks if the tracker swapped vehicles, and
  confirm which vehicle the report is about.

Every manual input is recorded in the JSON sidecar as "user-supplied", the estimate is recomputed
with the new inputs, and the report shows both the automatic and the reviewed values with their
error bands. Reviewed corrections (with consent, and kept on-device unless exported) are also
useful training labels for the keypoint model.

---

## 4. Models

### 4.1 Requirements

- Detect vehicles (car, SUV/pickup, van, truck, bus, motorcycle) at 640 px input, ≥15 fps live
  on the Pixel 10 Pro, ideally 30 fps.
- Detect license plates (small objects, usually on a high-resolution crop of the vehicle).
- Locate **keypoints** that serve as scale references: wheel–ground contact points, wheel
  centers, headlight/taillight centers, plate corners.
- Read plate text (OCR).
- Permissive licenses; export to **LiteRT** (formerly TensorFlow Lite) with GPU/NPU delegates,
  or ONNX Runtime Mobile as a fallback.

### 4.2 Candidates

| Role | Candidate | License | Notes |
|---|---|---|---|
| Vehicle detector (baseline) | **RF-DETR Nano** (Roboflow) | Apache-2.0 | DINOv2 backbone, NMS-free; an official **LiteRT** build and Android sample exist; ~27 ms inference on a Pixel 8a (Tensor G3), so G5 should be faster. **First choice.** |
| Vehicle detector (alt.) | **DEIM-D-FINE-N** | Apache-2.0 | ~4 M params, 43.0 COCO AP; strong small model; ONNX export, LiteRT conversion untested |
| Vehicle detector (fallback) | MediaPipe / EfficientDet-Lite0 | Apache-2.0 | Weaker accuracy but trivially deployable; good for a day-one smoke test |
| Plate detector | `open-image-models` YOLOv9-t plate detector (ankandrew) | MIT (weights; verify that no GPL code ships with them) | Or fine-tune RF-DETR/DEIM with a `plate` class (preferred long term — one license story) |
| Plate OCR | **fast-plate-ocr** (CCT-XS global model) | MIT | Tiny, exports to TFLite/ONNX; fine-tune on US plates |
| Keypoints | Small top-down keypoint head on vehicle crops (e.g. RTMPose-style, MMPose) | Apache-2.0 | Trained on vehicle-keypoint data (see §4.5) |
| Tracker | **ByteTrack** or **OC-SORT** | MIT | Pure algorithm; reimplement in C++ (small), with motion model in world frame |
| Segmentation (offline, on-device) | **EdgeTAM** (Meta; on-device SAM 2) | Apache-2.0 (verify) | Box-prompted segmentation and tracking; ~16 fps on an iPhone 15 Pro Max. Used on **saved clips**, not live (§4.4) |
| Labeling assistant (desktop only) | **SAM 3** / **SAM 2** (Meta) | SAM 3: Meta SAM License (gated, custom); SAM 2: Apache-2.0 | Text-prompted ("car", "license plate", "wheel") auto-labeling of our footage. Never shipped in the app |

### 4.3 Recommended path: start off-the-shelf, then specialize

1. **Phase 1:** RF-DETR Nano COCO weights as-is, keeping only vehicle classes. Wheelbase comes
   from a cheap heuristic (lower corners of the box plus wheel-blob search) and ground-plane
   range. This proves the end-to-end pipeline and the estimator math.
2. **Phase 2:** **Fine-tune** one model with our own classes and keypoints:
   `vehicle{car, suv_pickup, van, truck, bus, motorcycle}`, `plate`, plus keypoints. Fine-tuning a
   pretrained detector needs only a few thousand labeled frames, many of them from our own Pixel
   footage. Training a detector from scratch is **not** recommended: it costs far more data and
   compute for a worse result.
3. **Phase 3+:** Shrink if needed (lower input resolution, INT8 quantization with calibration
   data, pruning). Only do this if profiling shows we miss the fps or battery targets.

The two-stage design (fast detector on a downscaled frame → keypoints, plate, and OCR on
**full-resolution crops**) is what makes small plates and precise wheel points feasible while
keeping the live loop fast.

### 4.4 Where Meta's Segment Anything (SAM) family fits

SAM is not the live detector: SAM 2 and EdgeTAM have to be told *where* the object is (a click or
a box), and SAM 3, which can find "cars" by itself, is far too heavy for a phone (~5–6 fps on a
data-center GPU). It is useful in three places:

1. **Labeling (Phase 2, biggest win).** SAM 3 text prompts pre-label vehicles, plates and wheels
   across our footage; annotators correct rather than draw. CVAT and Label Studio both support
   SAM-assisted labeling. SAM 3 runs only on a development machine, so its custom license affects
   the tooling, not the app (terms still to be reviewed).
2. **Offline refinement of saved clips (Phase 3).** EdgeTAM, prompted with the detector's box,
   produces a per-frame mask of the car. The mask's lower silhouette gives tighter wheel–ground
   contact points and vehicle length than a bounding box, and it pre-places the markers in the
   review screen (§3.6).
3. **Background-only motion estimation.** Masking out vehicles leaves static background points
   for correcting gyro drift (§3.3).

### 4.5 Data

- **Own footage (most important):** Pixel 10 Pro clips from the actual use posture, recorded
  with the app's own capture pipeline so intrinsics and timestamps are exact.
- **Public datasets** (check each license before use; some are research-only):
  COCO, Open Images (vehicles, plates), UA-DETRAC, BDD100K, CarFusion and ApolloCar3D (vehicle
  keypoints), CCPD and other plate datasets (OCR pretraining).
- **Synthetic data with ground-truth speed:** **CARLA** (MIT) can render street scenes with known
  vehicle speeds, known camera intrinsics, and simulated handheld motion. That makes it an ideal
  regression test for the speed estimator, and a source of keypoint labels for free.
- Labeling tool: CVAT or Label Studio (both permissive, self-hostable), with SAM-assisted
  pre-labeling (§4.4).
- **Vehicle dimension table:** make/model/year → wheelbase, length, width, track width, for the
  review screen's make/model picker and, later, the automatic classifier. Source and license to be
  determined (manufacturer spec sheets, open datasets).

### 4.6 Ground truth for real-world speed validation

- A volunteer drives a known car past the tester at set speeds, logging speed with a phone GPS
  logger (Doppler-derived GPS speed is accurate to ~0.1–0.5 mph at steady speed) and/or an
  OBD-II dongle.
- **Timing gates beside the road (no equipment needed):** two gates a measured distance apart
  (e.g. 20 m), each marked by a pair of markers directly across the road from each other at
  the pavement edges. In the image, the line between a gate's two markers is that gate's
  ground line, so the frame where a tire crosses it is exact (no parallax). Speed = spacing ÷
  elapsed time, about ±1 % at 60 fps. Works for any passing car, and the four markers double as
  the known-distance scene calibration of §3.6.
- Optional: an inexpensive handheld radar gun (e.g. Bushnell Velocity, ~$90–150, ±1 mph) as a
  second reference for approaching/receding passes (cosine error makes it read low off-axis).
- Every test pass is logged (clip + sensor dump + ground truth) into a growing evaluation set.
  Field procedure: [docs/test-protocol.md](docs/test-protocol.md).

---

## 5. Architecture

```
┌──────────────────────── Flutter (Dart) ────────────────────────┐
│  Live view (Texture) + overlay painter   Clips & reports UI    │
│  Settings (units, phone height, recipients)   Calibration UI   │
└───────────────▲──────────── Pigeon (typed platform API) ───────┘
                │ track/speed events, clip metadata
┌───────────────┴──────────── Android native (Kotlin) ───────────┐
│  Camera2/CameraX: main physical camera, EIS off, 4K30 or        │
│    1080p60 YUV + per-frame metadata (timestamps, intrinsics)    │
│  Sensor service: gyro/accel at ≥200 Hz, same clock              │
│  LiteRT runtime (GPU / NPU delegate): detector, keypoints, OCR  │
│  Ring-buffer encoder (MediaCodec): last N seconds of video+audio│
│  Clip writer + JSON sidecar; Media3 Transformer for overlays    │
└───────────────▲─────────────────────────────────────────────────┘
                │ JNI
┌───────────────┴──────────── core/ (portable C++) ──────────────┐
│  Lens model & undistortion   Orientation (gyro integration)    │
│  Tracker (ByteTrack/OC-SORT, world-frame motion model)         │
│  Speed estimator: cue fusion, track fit, error budget          │
│  Built for Android (NDK), later iOS, and desktop + Python      │
│  bindings (pybind11) for offline evaluation                    │
└─────────────────────────────────────────────────────────────────┘
```

Rationale:

- **Native camera pipeline.** The Flutter `camera` plugin's image stream copies frames through
  platform channels and doesn't expose Camera2 intrinsics, rolling-shutter skew, or EIS
  control. The live preview is handed to Flutter as a `Texture`; Flutter draws the overlay.
- **Portable C++ core for the math.** The same tracker and estimator code runs on the phone, on
  iOS later, and in a desktop Python harness that replays recorded clips and CARLA scenes
  against ground truth. One implementation, tested once.
- **Live vs. offline.** Live mode runs the detector at 15–30 fps on a downscaled frame and gives
  a provisional estimate. When a track completes, the saved clip is re-analyzed on-device at full
  frame rate and resolution with smoothing (forward-backward / RTS smoother), plus EdgeTAM
  segmentation for tighter keypoints, and the user may refine it further in the review screen
  (§3.6). That refined number is what goes into a report.

### 5.1 Evidence package (per vehicle)

- Short clip (e.g. 2 s before first detection to 1 s after the track ends), video + audio.
- Best frame(s) with burned-in overlay: bounding box, track ID, `speed ± error`, timestamp,
  location.
- Plate crop and OCR text with confidence (user can correct it).
- JSON sidecar: device model, intrinsics, per-frame detections, keypoints, cue values, fit
  residuals, app and model versions. This makes the estimate reproducible and explainable.
- **Multi-vehicle attribution:** every overlay label and report ties the speed to a specific track
  ID, and the overlay draws that ID on the same box in every frame of the clip.

### 5.2 Reporting

- A report screen assembles the package. The user reviews and edits it, then sends it through the
  Android share sheet / email intent (with attachments) to a saved recipient. Nothing leaves the
  device until the user sends it.
- Wording is "estimated speed" with the error band and method summary. The app does not claim
  to be a calibrated speed-measurement device.

---

## 6. Phased roadmap

### Phase 0 — Feasibility spikes (1–2 weeks)

- [x] Flutter project skeleton + Android native plugin module (`packages/radar_camera`), CI
      (format, analyze, unit tests, APK build). Platform calls use a plain MethodChannel for
      now; move to Pigeon when the API grows.
- [x] **Recorder app**: Camera2 capture with EIS off and zoom locked at 1.0, H.264 + AAC
      recording with boot-clock timestamps, per-frame capture metadata (`frames.jsonl`),
      ≥200 Hz IMU log, per-pass annotation (direction, vehicle, ground truth, geometry), share.
- [x] Bundle checker (`tools/inspect_recording.py`) and lens math mapping Camera2 intrinsics
      into video pixels (`tools/synthradar/lens.py`).
- [x] Field test protocol with ground-truth options ([docs/test-protocol.md](docs/test-protocol.md)).
- [ ] Install on the Pixel 10 Pro and verify on device (preview orientation, recording,
      bundle passes `inspect_recording.py`).
- [ ] Dump Pixel 10 Pro `CameraCharacteristics` for every camera ID; confirm intrinsics,
      distortion, rolling-shutter skew, EIS control, and 4K30 / 1080p60 availability.
- [ ] Checkerboard calibration with OpenCV to verify the published intrinsics
      (`tools/calibrate_checkerboard.py`).
- [ ] Confirm gyro and frame timestamps share a clock (`SENSOR_INFO_TIMESTAMP_SOURCE`).
- [ ] Run RF-DETR Nano LiteRT sample on the Pixel 10 Pro; measure latency on CPU / GPU / NPU
      delegates at 384, 512 and 640 input.
- [ ] Record a first batch of test passes with sensor logs and ground truth.

**Exit:** we know the achievable fps and that the lens/IMU data are trustworthy.

### Phase 1 — Live detection, tracking and first speed estimate (3–4 weeks)

- [ ] Native capture pipeline → detector → tracker → overlay in Flutter (boxes + track IDs).
- [ ] `core/` C++ library: lens model, gyro orientation, world-frame bearings, tracker.
- [ ] Speed estimator v1: ground-plane range + box-based wheelbase heuristic + class length
      priors; constant-velocity fit; error budget; overlay `speed ± error`.
- [ ] Desktop replay harness (Python + pybind11) for recorded clips.
- [ ] CARLA scene generator for side-of-road passes with known speeds; estimator regression test.
- [ ] First real-world ground-truth session (GPS-logged drive-bys at 20/25/30/35/40 mph).

**Exit:** median error and 95 % error band measured against ground truth; we know which cue limits
accuracy.

### Phase 2 — Custom model: keypoints and plates (4–6 weeks)

- [ ] Labeling pipeline (CVAT/Label Studio) with SAM 3 / SAM 2 pre-labeling; label own footage +
      subsets of public data.
- [ ] Review SAM 3 license terms for use as an internal labeling tool.
- [ ] Fine-tune detector with vehicle subclasses + `plate`; train keypoint head (wheel contacts,
      lights, plate corners).
- [ ] Plate OCR: fast-plate-ocr fine-tuned on US plates; per-character confidence.
- [ ] Estimator v2: fuse plate, wheelbase, light spacing and ground-plane cues per frame.
- [ ] Quantize (FP16/INT8) and re-profile; target ≥20 fps live on the Pixel 10 Pro.

**Exit:** meets the accuracy target from §2 on the evaluation set for side-view passes.

### Phase 3 — Evidence, clips and reporting (2–3 weeks)

- [ ] Ring-buffer recording; automatic clip save per completed track; clip library UI.
- [ ] Offline re-analysis of saved clips (full fps, smoother) → refined estimate.
- [ ] EdgeTAM on-device: convert to LiteRT, profile on the Pixel 10 Pro, use masks to refine
      wheel contact points.
- [ ] Review screen (§3.6): frame scrubber, draggable wheel and plate markers, vehicle class /
      make-model picker backed by a dimension table, optional two-point road calibration,
      track merge/split, plate text correction; live recomputation of `speed ± error`.
- [ ] Overlay burn-in (Media3 Transformer), JSON sidecar, report composer, share/email.
- [ ] Settings: units, phone height, recipients, retention/auto-delete policy.

### Phase 4 — Hardening

- [ ] Night / rain / glare evaluation; low-light handling (exposure lock, shutter limits to cut
      motion blur).
- [ ] Multiple simultaneous vehicles, occlusion, vehicles in both directions.
- [ ] Battery and thermal profiling for 10+ minute sessions.
- [ ] Other Android devices (fallback calibration path).

### Future

- Doppler audio speed estimate fused with vision (§3.5).
- Automatic make/model classifier → exact dimensions from the same spec table the review screen
  uses.
- iOS (AVFoundation + Core ML or LiteRT; same `core/` C++).
- Head-on / receding geometry (plate-dominated, Doppler-assisted).

---

## 7. Proposed repository layout

```
synth-radar/
├── app/                    # Flutter application
│   ├── lib/
│   └── android/
├── packages/
│   └── radar_camera/       # Flutter plugin: Pigeon API + Kotlin camera/ML pipeline
├── core/                   # Portable C++: lens, orientation, tracker, estimator (+ tests)
├── ml/                     # Python: training, export to LiteRT, evaluation scripts
├── sim/                    # CARLA scene scripts and synthetic dataset generation
├── tools/                  # Calibration, replay harness, sensor-log viewers
├── data/                   # (git-ignored) clips, labels; manifest checked in
└── docs/                   # Design notes, error-budget derivation, test protocol
```

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Size priors too loose for the accuracy target | Fuse several cues; plate when visible; future make/model lookup; show wide bands honestly and refuse to report when too wide |
| Gyro/frame time misalignment | Verify the shared clock in Phase 0; estimate offset online from background features |
| EIS/OIS altering geometry | Disable EIS on the measurement stream; include OIS in the error budget |
| Plates too small or blurred | Full-res crops, short exposure, prefer frames at closest approach; mark OCR as low-confidence and editable |
| NPU delegate unsupported for model ops | GPU delegate fallback; choose models with a known-good LiteRT export (RF-DETR) |
| Legal/privacy (plate reading, recording audio, reporting) | Legal review per jurisdiction before public release; on-device only; user-initiated sharing; retention limits |
| Reports ignored by authorities | Package evidence clearly (method, error band, raw data); target recipients that accept citizen data |

## 9. Immediate next steps

1. Answer the open questions in §2 (at least jurisdiction and accuracy target).
2. Phase 0: create the Flutter + native plugin skeleton and the camera-characteristics dump tool.
3. Run the RF-DETR Nano LiteRT sample on the Pixel 10 Pro and record latency numbers here.

---

### References

- RF-DETR (Apache-2.0): https://github.com/roboflow/rf-detr — LiteRT build:
  https://huggingface.co/litert-community/RF-DETR-Nano-LiteRT
- D-FINE / DEIM (Apache-2.0): https://github.com/Peterande/D-FINE, https://github.com/ShihuaHuang95/DEIM
- fast-plate-ocr (MIT): https://github.com/ankandrew/fast-plate-ocr
- open-image-models plate detector (MIT): https://github.com/ankandrew/open-image-models
- Android `CameraCharacteristics` (intrinsics, distortion):
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- Pixel 10 Pro specs: https://store.google.com/product/pixel_10_pro_specs
- CARLA simulator (MIT): https://carla.org
- SAM 2 (Apache-2.0): https://github.com/facebookresearch/sam2 — SAM 3:
  https://github.com/facebookresearch/sam3
- EdgeTAM: https://huggingface.co/facebook/EdgeTAM
