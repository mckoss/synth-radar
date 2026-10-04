# Synth Radar — Project Plan

An on-device, camera-based vehicle speed estimator used like a radar gun. A pedestrian on the
shoulder of a road braces a phone and points it up or down the road at a car approaching or
driving away at an oblique angle. The app detects and tracks each vehicle, estimates its
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
| Capture posture | **Radar-gun style:** pedestrian on the shoulder, phone **braced** (or handheld), pointed along the road at a car **approaching or receding at an oblique angle**. Side-on crossing views are a secondary case: from the shoulder the camera can't fit a passing car side-on |
| Lenses | 1× main camera at 4K for near/medium range; **5× telephoto** (Pixel 10 Pro) for distant approaching cars, five times the pixels on target |
| Compute | On-device only for capture and speed estimation |
| Scale cues | **Track width** (left–right wheel spacing), **license plate size**, headlight/taillight spacing, ground plane (phone height), plus wheelbase in oblique views and make/model when known |
| Licenses | Permissive only (Apache-2.0 is fine) — rules out Ultralytics YOLOv5/v8/11 (AGPL) and YOLOv9 reference code (GPL) |
| Real-time | Live on-screen estimate is desired; a more accurate offline re-analysis of saved clips is acceptable |
| Jurisdiction | **US only.** Plates 12 × 6 in (305 × 152 mm); OCR trained for US formats; legal review against US/state law |
| Training hardware | **Mac Studio (Apple Silicon)** for fine-tuning and evaluation (PyTorch MPS); rent a cloud GPU only for jobs that need NVIDIA (CARLA rendering, very large runs) |
| Test fleet | Owner's **Tesla Model S, Tesla Model Y, Mercedes Sprinter, Chevy Spark**: known dimensions, spanning small car → large van (§4.6) |
| Builds | GitHub Actions builds the APK on every push and publishes it as a GitHub Release ([docs/releases.md](docs/releases.md)) |

## 2. Open questions (not blocking Phase 0/1)

1. **State-level rules** (US): legal review of plate reading, audio recording and citizen
   reporting for the user's state.
2. **Accuracy target:** proposed goal is **±3 mph (±5 km/h) at 95 % confidence for 20–45 mph,
   tracking the car between roughly 80 m and 15 m away** (1× lens up to ~50 m, 5× beyond), with the app refusing to show a number when the band is wider than ±15 %.
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
If we think the track width is 1.60 m ± 6 %, speed is ± 6 % from that alone. So the plan puts
effort into getting the scale reference right and fusing several of them.

**The primary geometry: approaching or receding at an oblique angle.** The camera stands on the
shoulder, a few metres to the side of the car's path, and looks along the road. The car's
motion is mostly toward or away from the camera, so speed comes mainly from how fast its
**range changes**, i.e. how fast it grows or shrinks in the image. The small sideways offset
(3–5 m) makes the view oblique, but the estimator works in 3D world coordinates, so unlike a
radar gun there is no cosine error to correct. Rough numbers for the Pixel 10 Pro at 4K
(focal length ≈ 2,660 px at 1×, ≈ 12,000 px at 5×) and a 1.6 m track width:

| Range | Track width on screen, 1× | 5× | Plate width on screen, 1× | 5× |
|---|---|---|---|---|
| 15 m | 284 px | — (too close) | 54 px | — |
| 30 m | 142 px | 640 px | 27 px | 122 px |
| 60 m | 71 px | 320 px | 14 px | 61 px |
| 100 m | 43 px | 192 px | 8 px | 37 px |

What this means for accuracy:

- **Random noise is not the problem.** A car tracked from 50 m to 15 m at 30 mph gives ~80
  frames over ~2.6 s. Even with 1 px of noise per frame, the fitted speed's random error is
  under 0.1 mph.
- **Systematic errors dominate.** These are a wrong size prior, and keypoints consistently
  placed a pixel or two off (e.g. the detected tire contact always slightly outside the true
  one). A 1 px bias on a 27 px plate is 4 %. So the fixes are better priors (make/model, plate,
  several fused cues), larger images of the car (**5× telephoto** for distant cars), and
  careful keypoint training.
- **Self-calibration over a long approach.** The ground-plane cue (§3.2) gives range from the
  phone's height and the downward angle to the tires, independent of vehicle size. The scale cues
  give range proportional to the assumed size. Over an approach from 80 m to 15 m the two
  curves only agree for one vehicle size and one small camera-pitch bias, so a joint fit can
  estimate the car's true size. This needs a flat road and a known phone height; testing it is a
  Phase 1 research item. A braced phone at a measured height (monopod, fence post, car roof)
  makes the height known.

### 3.2 Scale cues, best to worst

| Cue | Known size | Typical uncertainty | When available |
|---|---|---|---|
| **Track width** (left–right wheel–ground contacts) | Most cars/SUVs/pickups/vans 1.55–1.75 m; subcompacts ~1.40–1.50 m | ±5–6 % by class, ~±1 % if make/model known | **Primary cue:** front/rear views, i.e. every approaching or receding car |
| **License plate** (width and height) | US: 305 × 152 mm (standardized) | ~1–2 % physical; pixel bias dominates at range (see §3.1 table) | Rear plate on every receding car. Front plate only in states that require one (about 20 US states are rear-plate-only) |
| **Headlight / taillight spacing** | ~1.2–1.6 m by class | ±10–15 % | Front/rear views; lights are high-contrast, so they're also easy to find at night |
| **Ground plane** (phone height + gravity) | Phone ~1.3–1.6 m above road (exact if braced at a measured height); gravity from IMU | Range from the downward angle to the tire contacts; error grows with range | Whenever the tires are visible; size-independent, enables the self-calibration in §3.1 |
| **Wheelbase** (front/rear wheel–ground contact points) | Class prior: compact ~2.6 m, sedan ~2.8 m, SUV/pickup ~2.9–3.6 m | ±5–10 % by class; ~±1 % if make/model is known | Oblique views where both wheels on the near side are visible (foreshortened); side-on crossing views |
| **Overall length / height** | Class prior | ±10–20 % | Always, but bounding boxes are sloppy |
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
- **Track width** (left–right wheel spacing) is the more uniform measure across classes, and the
  one the primary geometry sees best. Wheelbase
  runs from ~1.9 m (Smart) to over 4 m (long vans, crew-cab pickups), about ±35 % around the
  middle. Track width stays around 1.40–1.75 m for nearly everything on the road, about ±11 %,
  or ±5–6 % once subcompacts are recognized as a class. A pickup's or van's track is about the
  same as a sedan's, even though its wheelbase is much longer. The catch is visibility: track
  width can only be measured when both wheels of an axle are visible, i.e. in **front/rear
  views** (approaching or receding cars), which is exactly the primary use case. In a pure side
  view the far wheels are hidden. In oblique views both cues are partly visible, so the
  estimator fits them together. Track width is measured at the tire–ground contact points, which
  avoids the variable body and mirror width.
- **Knowing the make/model** pins it to ~1 %. The user can supply this in review (§3.6); an
  automatic make/model classifier is a later addition.

### 3.3 Camera motion (braced or handheld)

The phone is normally braced like a radar gun, but it still moves a little, and handheld use
must work too. The user may also pan slightly to keep an approaching car centered (much less
than for a crossing car). That rotation must not be mistaken for car motion. It matters most
for the ground-plane cue, where 0.1° of pitch error is ~4 % of range at 30 m.

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

- **Lock to one physical camera per recording**, either the 1× main or the 5× telephoto. No
  lens switching or zoom changes during a measurement. The recorder fixes the zoom ratio and
  logs the active physical camera on every frame. Calibrate each (lens, resolution) mode once
  with the checkerboard tool, which gives intrinsics directly in video pixels.
- **Turn off electronic video stabilization** for the measurement stream; EIS crops and warps the
  image and invalidates the intrinsics. (OIS may stay on; its small principal-point shift goes in
  the error budget.)
- **Map intrinsics through the stream crop/scale** (active array → output stream resolution).
- **Rolling shutter:** each row is exposed at a slightly different time. For approaching cars the
  image motion is small, so the effect is minor, but the per-frame skew is logged so each
  keypoint can be time-stamped by its row.
- Fallback for devices that don't publish intrinsics: compute from focal length + sensor size,
  and offer an optional one-time checkerboard calibration (OpenCV) in a developer menu. Use the
  same checkerboard tool in Phase 0 to **verify** the Pixel's published values.

### 3.5 Doppler audio (future enhancement)

A passing car's tire/engine tones shift by the ratio `(c + v) / (c − v)` between approach and
recession (≈ 8 % at 30 mph). With the closest-approach time and distance known from vision, a
spectrogram fit of tonal components can give an **independent** speed estimate that has no
scale-prior error at all. This suits the radar-gun geometry, since the motion is mostly toward
or away from the microphone. The catch: the tone's true pitch is unknown, so a single
approaching (or receding) clip constrains speed only weakly. A clip that spans the car passing
(approach and recession) works best. It is planned as a later phase; the audio track is recorded
with clips from the start so the data exists.

### 3.6 Human-in-the-loop review (optional accuracy boost)

The automatic estimate is never blocked on the user, but before a report is sent the user can
open a **review screen** for the saved clip and tighten the estimate. The two sources of error
are handled separately:

- **Pixel error** (where exactly the wheels touch the road): the user drags **wheel–ground contact
  markers** onto the left and right tires (track width, the main case) or the front and rear
  tires on the near side (oblique views, wheelbase) on two or more frames, ideally far apart in
  time. The
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

1. **Phase 1:** RF-DETR Nano COCO weights as-is, keeping only vehicle classes. Range comes from
   the box width against a class width prior, plus ground-plane range from the box's bottom
   edge. This is crude but proves the end-to-end pipeline and the estimator math.
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
- **Synthetic data with ground-truth speed:** CARLA scenes (§4.7) with exact speeds, boxes,
  segmentation masks and keypoints for every vehicle.
- Labeling tool: CVAT or Label Studio (both permissive, self-hostable), with SAM-assisted
  pre-labeling (§4.4).
- **Vehicle dimension table:** make/model/year → wheelbase, length, width, track width, for the
  review screen's make/model picker and, later, the automatic classifier. Source and license to be
  determined (manufacturer spec sheets, open datasets).

### 4.6 Ground truth for real-world speed validation

- **Owner's test fleet.** Four vehicles with known dimensions that span the size range:

  | Vehicle | Wheelbase | Track width (front / rear) | Role |
  |---|---|---|---|
  | Chevy Spark | ~2.38 m (93.5–93.9 in) | ~1.40–1.50 m | Small end of the range |
  | Tesla Model Y | ~2.89 m (113.8 in) | ~1.64–1.66 m | Typical crossover |
  | Tesla Model S | ~2.96 m (116.5 in) | ~1.66–1.70 m | Large sedan |
  | Mercedes Sprinter | 3.66 m (144 in) or 4.33 m (170 in) | ~1.7 m | Long van: stress-tests the wheelbase prior |

  Values vary by model year, trim and wheels. Measure each vehicle once with a tape measure
  (wheel center to wheel center along each side for wheelbase, tire-tread center to center
  across each axle for track width) and record those as the ground truth. Driving these past the
  camera at GPS-logged speeds tests the estimator twice: once with class priors (pretending we
  don't know the car), once with the exact dimensions (the §3.6 make/model path).
- A volunteer drives a known car past the tester at set speeds, logging speed with a phone GPS
  logger (Doppler-derived GPS speed is accurate to ~0.1–0.5 mph at steady speed) and/or an
  OBD-II dongle.
- **Timing gates beside the road (no equipment needed):** two gates a measured distance apart
  (e.g. 20 m), each marked by a pair of markers directly across the road from each other at
  the pavement edges. In the image, the line between a gate's two markers is that gate's
  ground line, so the frame where a tire crosses it is exact (no parallax). Speed = spacing ÷
  elapsed time. Seen from the shoulder looking along the road, place the near gate ~10–15 m
  and the far gate ~30–35 m from the camera: beyond that, a car's image moves too little per
  frame to time the crossing precisely (about ±1 % at 60 fps within that range; use 5× for
  farther gates). Works for any passing car, and the four markers double as
  the known-distance scene calibration of §3.6.
- Optional: an inexpensive handheld radar gun (e.g. Bushnell Velocity, ~$90–150, ±1 mph). It's
  used from the same spot and in the same direction as the app, so it's a natural reference.
  Its cosine error (reading low off-axis) is computable from our own 3D track and can be
  corrected.
- Every test pass is logged (clip + sensor dump + ground truth) into a growing evaluation set.
  Field procedure: [docs/test-protocol.md](docs/test-protocol.md).

### 4.7 Simulation with CARLA

**What it is.** [CARLA](https://carla.org) is an open-source driving simulator from the Computer
Vision Center (Barcelona) and Intel Labs, built on Unreal Engine and used widely in
autonomous-driving research. Code is MIT-licensed and assets are CC-BY. A Python API spawns
vehicles in town and rural maps, drives them on autopilot or along scripted paths at exact
speeds, and places cameras anywhere. For every frame it outputs RGB plus perfect ground truth:
instance and semantic segmentation, depth, 2D/3D boxes, each vehicle's pose and velocity, and
the camera intrinsics. Its vehicle library includes a Tesla Model 3, a Mercedes Sprinter van,
pickups, compacts, trucks, buses and motorcycles.

**How we'd use it.**

1. **Estimator and tracker regression tests (highest value).** Scripted scenes recreate the
   app's geometry: a camera at 1.4 m on the shoulder of a rural two-lane road, looking along the
   road, with the Pixel's 1× and 5× intrinsics and lens distortion. One or several cars approach
   and recede in both lanes at known speeds, with occlusions. Real **camera motion from our own
   recordings' IMU logs** is replayed on the simulated camera. The speed error and track-ID mix-ups are then measured exactly, on every
   code change, across thousands of passes no field session could produce.
2. **Training data for multi-vehicle detection, segmentation and tracking.** Every frame comes
   with perfect masks, boxes, keypoints and track IDs at no labeling cost. Synthetic images alone
   don't transfer perfectly to real video (the "sim-to-real gap": rendering, lighting, sensor
   noise and plates look different). The established recipe is to **pretrain or mix on synthetic
   data, then fine-tune on a smaller set of real labeled frames**, with domain randomization
   (weather, time of day, vehicle colors, camera noise, motion blur) to narrow the gap. We
   measure the benefit rather than assume it: train with and without CARLA data and compare on
   held-out *real* Pixel footage.
3. **Rare and dangerous cases** that are hard to film safely, such as very high speeds, many
   cars at once, or night and rain.

**Where it runs.** CARLA supports **Linux and Windows with an NVIDIA GPU** (6 GB VRAM minimum,
8 GB+ recommended). It does not run on macOS, so it can't run on the Mac Studio. Plan: run
dataset-generation jobs on a rented cloud GPU instance (a few dollars per hour, used in batches)
and copy the rendered datasets back to the Mac for training. If we only need simple scenes,
**Blender** (runs natively on Apple Silicon, with Python scripting for exact ground truth) is a
lighter alternative for rendering on the Mac itself.

**Limits to keep in mind.** CARLA renders with a global shutter by default, so rolling shutter
must be added in post-processing. US plates must be added as textures. Vehicle variety is
smaller than real traffic. Simulation supplements real data; it doesn't replace it.

### 4.8 Training on the Mac Studio

- PyTorch's **MPS** backend (Apple GPU) runs fine-tuning for RF-DETR / DEIM-class detectors,
  keypoint heads and the plate OCR model. Unified memory allows large batches. Expect slower
  training than a high-end NVIDIA card but no cloud bills for routine runs. A few operations may
  fall back to CPU; we check this early with a short fine-tuning run in Phase 2.
- Export: PyTorch → LiteRT (`litert-torch`) → `.tflite`. If any conversion step is Linux-only,
  run it in CI or Docker. Every exported model is verified against the PyTorch model on a fixed
  image set before it ships.
- Datasets and checkpoints live outside git (the Mac, plus an external or cloud backup). The
  repo holds training code, configs and dataset manifests.

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
      (format, analyze, unit tests, APK build) and **GitHub Releases** with the APK. Platform calls use a plain MethodChannel for
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
- [ ] Speed estimator v1 for approaching/receding cars: range from box width (class width prior)
      and ground plane; constant-velocity fit of 3D position; error budget; overlay
      `speed ± error`. Try the scale/ground-plane joint fit (§3.1 self-calibration).
- [ ] Desktop replay harness (Python + pybind11) for recorded clips.
- [ ] CARLA scene generator (cloud GPU) for approaching/receding passes seen from the shoulder,
      with known speeds, replaying real IMU camera motion; estimator and tracker regression tests (§4.7).
- [ ] Owner's test fleet: measure wheelbase and track width of all four vehicles; GPS-logged
      drive-bys at 20–45 mph.
- [ ] First real-world ground-truth session (GPS-logged drive-bys at 20/25/30/35/40 mph,
      approaching and receding, 1× and 5×).

**Exit:** median error and 95 % error band measured against ground truth; we know which cue limits
accuracy.

### Phase 2 — Custom model: keypoints and plates (4–6 weeks)

- [ ] Short MPS fine-tuning smoke test on the Mac Studio (§4.8); CARLA synthetic set and a
      with/without-synthetic comparison on real footage.
- [ ] Labeling pipeline (CVAT/Label Studio) with SAM 3 / SAM 2 pre-labeling; label own footage +
      subsets of public data.
- [ ] Review SAM 3 license terms for use as an internal labeling tool.
- [ ] Fine-tune detector with vehicle subclasses + `plate`; train keypoint head (wheel contacts,
      lights, plate corners).
- [ ] Plate OCR: fast-plate-ocr fine-tuned on US plates; per-character confidence.
- [ ] Estimator v2: fuse plate, wheelbase, track width, light spacing and ground-plane cues per frame.
- [ ] Quantize (FP16/INT8) and re-profile; target ≥20 fps live on the Pixel 10 Pro.

**Exit:** meets the accuracy target from §2 on the evaluation set for approaching and receding passes.

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
