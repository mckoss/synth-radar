# Field test protocol: first recording session

Goal of this session: collect a first set of real recordings on the Pixel 10 Pro, with enough
ground truth to measure how accurate the speed estimate is. Nothing is analyzed live yet. The
app only records video plus the sensor data we need, and you label each pass.

Location: a rural road with one lane each way, a long straight section, and light traffic.

---

## 0. Safety first

- Stay off the pavement. Stand on the shoulder or further back, ideally behind a ditch, fence or
  parked car. Never step toward the road to keep a car in frame.
- Wear something bright. Cars on a quiet rural road aren't expecting a pedestrian holding a phone
  up at them.
- Anything you place near the road (cones, stakes, chalk marks) goes on the shoulder, never in a
  traffic lane. Pick everything up when you're done.
- Only cross the road to set up markers when there's no traffic in sight.

## 1. Install the app (once)

1. Download `synth-radar-recorder.apk` on the phone (Claude sends this file).
2. Open it. Android asks you to allow installs from that source (Files or Chrome); allow it.
   Play Protect may warn about an unknown developer. Tap **More details → Install anyway**.
3. Open **Synth Radar**. It runs in landscape only. Allow camera and microphone.

## 2. Check the lens data (once, at home, 2 minutes)

1. Open the **Lens** tab. Each camera shows its focal length, intrinsics and distortion.
2. Tap **Export full dump** and send the JSON file back (Drive, email, anything). This records the
   Pixel's factory calibration, which the speed math depends on.

## 3. Optional but valuable: checkerboard calibration (at home, 5 minutes)

This checks the factory calibration independently.

1. Display a checkerboard full-screen on a laptop or monitor, or print one. Use a board with
   **9 × 6 inner corners**, for example from <https://calib.io/pages/camera-calibration-pattern-generator>.
   Measure the size of one square.
2. In the app, choose **2160p30**, start recording, and slowly move the phone around the
   board for about 30 s. Keep the whole board in view. Put it near each corner and edge of the
   frame, and tilt the phone up to about 45° in different directions. Hold each pose briefly
   so the frames aren't blurred.
3. Stop. In the notes, write "checkerboard, square = __ mm".

## 4. Getting ground-truth speed

Without some known speeds we can't measure accuracy. Here are the options, best first. Any one is
enough to start.

### A. A cooperating driver with GPS (best, if you have a helper)

Use our own fleet (Model S, Model Y, Sprinter, Spark). Their exact dimensions make them the best
test cars. Measure each one once with a tape measure: wheelbase (front to rear wheel center, both
sides) and track width (center of the left tire tread to center of the right, front and rear
axles). Write the values in `data/fleet.md` on your computer, and put the vehicle name in each
pass's make/model field.

- A friend or family member drives a known car past you at steady, pre-agreed speeds using cruise
  control, or just holding the speed.
- Their phone runs a GPS logging app that records speed with timestamps, such as
  **GPSLogger** (open source) on Android. Phone GPS speed is accurate to about ±0.5 mph at steady
  speed. That beats the car's speedometer, which usually reads 1–3 mph high.
- Make sure both phones have automatic network time on. The app records the wall-clock time of
  every recording, so the GPS log lines up afterwards.
- Write the target speed in the notes and set the source to **GPS logger in vehicle**. The exact
  value comes from the GPS log later.

### B. Timing gates marked beside the road (free, works for any car)

This turns any passing car into a measured one. The ground marks also give us a known distance
in the scene, which is the "known distance" calibration from plan §3.6.

1. Choose a straight section. Mark **two gates 20 m (65 ft) apart** along the road. Measure along
   the shoulder with a tape measure or measuring wheel. Since you'll be looking along the road,
   put the **near gate about 10–15 m from where you stand and the far gate about 30–35 m away**.
   Much farther, and a car moves too few pixels per frame to time the crossing well.
2. Each gate is **two markers, one on each shoulder**, directly across the road from each other,
   so the line between them is perpendicular to the road. That's 4 markers in total. Small
   traffic cones, orange stakes or bright objects on the ground work. Line them up by eye or with
   the 3-4-5 triangle trick.
3. Place each marker right at the pavement edge, on the ground. In the video the line from one
   shoulder marker to the other then shows exactly where that gate crosses the road.
4. Record passes with **both gates in view** when the car crosses them. Later I measure the
   frames where the front tires cross each gate line. 20 m ÷ elapsed time = speed. At 60 fps
   (1080p60) this is accurate to about ±1%, so use 1080p60 for gate passes.
5. Note the gate spacing in the first pass's notes and set the source to **Timed between road
   markers**.

### C. A handheld radar gun (optional)

- An affordable option is the **Bushnell Velocity Speed Gun**, usually around $90–150, rated about
  ±1 mph for vehicles. The **Pocket Radar Classic / Smart Coach** cost more. Ball-sport models
  like the Pocket Radar *Ball Coach* are meant for baseballs and have a short range, so avoid
  them for cars.
- It's used exactly like the app: from the shoulder, aimed at approaching or receding cars. That
  makes it a natural reference.
- Radar measures only the part of the speed directed toward it, so it reads low when not aimed
  along the direction of travel. That's about 2% low at 11° off and 3.4% low at 15°. From the
  shoulder the angle is small for distant cars, and we can correct it using the app's 3D track.
- Handling a radar gun and a phone at once is awkward: hold the phone in one hand and the radar
  in the other, or have a helper work the radar. Say the radar reading out loud: the
  recording's audio captures it, and you can type it in the notes afterwards.

### D. No ground truth

Still useful. These passes train and test the detector, the tracker and the consistency of the
estimate. Set the source to **None**.

## 5. The passes

The main use case is **radar-gun style, handheld**: you stand on the shoulder holding the phone
**casually, the way anyone would**, and point it along the road at a car **coming toward you** or **driving away** after it passes, at a slight
angle because you're off to the side of its lane.

Record each car as **one recording**. Start when the car first appears in the distance, and stop
when it's close (approaching) or small in the distance (receding). For a pass-by, one recording
can cover the approach, the pass and the recession. After you stop, the app asks you to describe
the pass. Fill in the direction, vehicle type, ground truth (if any), and your phone's height and
distance from the lane. The app remembers these between passes, so usually you only change one or
two fields.

**Hold the phone casually.** Don't brace it or try to be extra steady. The app has to cope with
ordinary hand motion, so that's what we need to record. Electronic stabilization is always off,
and the motion is corrected in software later from the gyro log. A few braced passes are useful
only for comparison.

**Optical stabilization:** leave it **on** for most passes, and record a few matched pairs with it
**off** (the switch is in the Record panel). That tells us whether the lens's own stabilizer helps
or hurts the measurements.

**Lens:** use **1×** for cars within about 50 m and **5×** to start measuring them farther out.
The zoom buttons are under the video-mode menu. Pick the lens before you start recording: the
app keeps the lens fixed during a recording.

**Video mode:** use **2160p30** (4K) for most passes, since more pixels on the car mean better
measurements. Do a few in **1080p60** for comparison.

| # | Where you stand | Direction | Lens | Notes |
|---|---|---|---|---|
| 1–2 | Shoulder, 2–4 m off the pavement edge, looking up the road | Approaching in the near lane | 1× | **The main use case.** Keep the whole car, all four tires if possible, in view |
| 3–4 | Same spot, turned around | Receding in the near lane | 1× | Rear plate always visible |
| 5–6 | Same spot | Approaching / receding in the **far** lane | 1× | Larger angle; tests the oblique geometry |
| 7–8 | Same spot | Approaching from far away | 5× | Start the recording when the car is 100–150 m out; harder to keep framed by hand, which is useful to know |
| 9 | Same spot | Pass-by: record the approach, the pass and the recession in one clip | 1× | Useful later for Doppler audio |
| 10–11 | Same spot | Approaching, optical stabilization **off** | 1× | Pair with passes 1–2 |
| 12–13 | Same spot | Approaching, phone braced (post, car roof) | 1× | Comparison only: how much does hand motion cost? |
| 14+ | Repeat at different speeds, both lanes, 1080p60 | Any | Any | More variety means a better test |

Six passes is a good first session. Around 20 gives the first useful accuracy numbers. If several
cars are in view, that's fine, just choose **Multiple vehicles**.

Tips:

- **Don't pinch-zoom.** Choose 1×, 2× or 5× before recording. The status panel shows the active
  lens; it should not change during a recording.
- **Bright daylight is best for now.** The status panel warns when exposure is longer than
  4 ms, which blurs moving cars.
- **Measure your distance to the lane center once per spot.** Pace it out, or better, use a tape
  measure. Enter your phone height (chest-to-eye height, about 1.3–1.6 m).
- If something odd happens, like a lens switch, a dropped recording or a car stopping, add a note
  rather than deleting the recording.

## 6. Sending the recordings

- In the **Passes** tab, tap **Share** on a pass. That sends all its files: `video.mp4`,
  `frames.jsonl`, `video_frames.csv`, `imu.csv`, `session.json`, `notes.json` and
  `camera_characteristics.json`. Sharing to a Google Drive folder works well. A 4K pass is roughly
  4 MB per second of video.
- Or connect the phone to a computer:
  `adb pull /sdcard/Android/data/com.mckoss.synthradar/files/recordings/`
- Recordings never go in the git repository: they're large and contain other people's plates.
  The repo ignores `data/`.

## 7. What happens next

For each pass, `tools/inspect_recording.py` checks that the frame timing, lens metadata, IMU
log and notes are complete. Run it on a downloaded pass folder:
`python3 tools/inspect_recording.py <pass-folder>`. If the recordings are clean, Phase 1 builds
the first speed estimate on exactly these passes, and the ground truth tells us how close it gets.
