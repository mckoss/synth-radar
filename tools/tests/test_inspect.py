"""End-to-end check of the bundle reader and inspector on a synthetic bundle."""

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import inspect_recording  # noqa: E402
from synthradar import bundle as B  # noqa: E402


def make_bundle(root: Path, n_frames=60, fps=30, stabilization=0, drop_every=0, lens_ids=("2",)):
    t0 = 1_000_000_000_000
    period = int(1e9 / fps)
    frames, pts = [], []
    t = t0
    for i in range(n_frames):
        t += period * (2 if drop_every and i and i % drop_every == 0 else 1)
        frames.append({
            "frameNumber": i,
            B.SENSOR_TIMESTAMP: t,
            B.EXPOSURE_TIME: 2_000_000,
            B.ROLLING_SHUTTER_SKEW: 10_000_000,
            B.VIDEO_STABILIZATION: stabilization,
            B.INTRINSICS: [3000.0, 3000.0, 2004.0, 1504.0, 0.0],
            B.DISTORTION: [0.05, -0.1, 0.02, 0.0, 0.0],
            B.CROP_REGION: {"left": 0, "top": 0, "right": 4000, "bottom": 3000},
            "activePhysicalId": lens_ids[i * len(lens_ids) // n_frames],
            "zoomRatio": 1.0,
        })
        pts.append(t // 1000)
    (root / "frames.jsonl").write_text("\n".join(json.dumps(f) for f in frames) + "\n")
    (root / "video_frames.csv").write_text(
        "frame_index,pts_us\n" + "".join(f"{i},{p}\n" for i, p in enumerate(pts)))
    lines = ["sensor,timestamp_ns,v0,v1,v2,v3,v4,v5"]
    g = t0 - 50_000_000
    while g < t + 50_000_000:
        lines.append(f"gyro,{g},0.01,0.02,0.03,,,")
        lines.append(f"accel,{g},0.0,9.8,0.1,,,")
        g += 2_500_000  # 400 Hz
    (root / "imu.csv").write_text("\n".join(lines) + "\n")
    (root / "camera_characteristics.json").write_text(json.dumps({"cameras": {"0": {
        "android.sensor.info.preCorrectionActiveArraySize": {"left": 0, "top": 0, "right": 4008, "bottom": 3008},
        "android.sensor.info.activeArraySize": {"left": 4, "top": 4, "right": 4004, "bottom": 3004},
    }}}))
    (root / "session.json").write_text(json.dumps({
        "cameraId": "0",
        "device": {"manufacturer": "Google", "model": "Pixel 10 Pro", "sdkInt": 36},
        "camera": {"timestampSource": "REALTIME"},
        "video": {"width": 3840, "height": 2160, "fps": fps, "mime": "video/avc", "bitrate": 31_000_000},
        "audio": {"enabled": True},
    }))
    (root / "notes.json").write_text(json.dumps({
        "direction": "approaching", "vehicleType": "car", "groundTruthMph": 30.0,
        "groundTruthSource": "radar", "support": "handheld", "phoneHeightM": 1.4,
    }))


def run(path) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = inspect_recording.inspect(str(path))
    return rc, out.getvalue()


class InspectTest(unittest.TestCase):
    def test_good_bundle_passes(self):
        with tempfile.TemporaryDirectory() as d:
            make_bundle(Path(d))
            rc, out = run(d)
            self.assertEqual(rc, 0, out)
            self.assertIn("OK", out)
            self.assertIn("matched to capture metadata: 60", out)
            # 4000-wide active array -> 3840 px: scale 0.96, cx (2004-4)*0.96 = 1920.
            self.assertIn("cx=1920.0", out)
            self.assertIn("gyro 400 Hz", out)

    def test_stabilization_and_lens_switch_are_problems(self):
        with tempfile.TemporaryDirectory() as d:
            make_bundle(Path(d), stabilization=1, lens_ids=("2", "3"))
            rc, out = run(d)
            self.assertEqual(rc, 1)
            self.assertIn("stabilization was ON", out)
            self.assertIn("lens switched", out)

    def test_dropped_frames_are_counted(self):
        with tempfile.TemporaryDirectory() as d:
            make_bundle(Path(d), drop_every=10)
            _, out = run(d)
            self.assertIn("dropped ≈ 5", out)

    def test_missing_session_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            rc, out = run(d)
            self.assertEqual(rc, 1)
            self.assertIn("did not finish cleanly", out)


if __name__ == "__main__":
    unittest.main()
