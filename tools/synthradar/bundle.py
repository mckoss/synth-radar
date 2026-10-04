"""Read Synth Radar recording bundles (one directory per recorded pass)."""

from __future__ import annotations

import csv
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

# Camera2 CaptureResult key names as written to frames.jsonl.
SENSOR_TIMESTAMP = "android.sensor.timestamp"
EXPOSURE_TIME = "android.sensor.exposureTime"
FRAME_DURATION = "android.sensor.frameDuration"
ROLLING_SHUTTER_SKEW = "android.sensor.rollingShutterSkew"
INTRINSICS = "android.lens.intrinsicCalibration"
DISTORTION = "android.lens.distortion"
CROP_REGION = "android.scaler.cropRegion"
VIDEO_STABILIZATION = "android.control.videoStabilizationMode"
OIS_MODE = "android.lens.opticalStabilizationMode"
DISTORTION_CORRECTION = "android.distortionCorrection.mode"
FOCUS_DISTANCE = "android.lens.focusDistance"
FOCAL_LENGTH = "android.lens.focalLength"


@dataclass
class ImuSample:
    sensor: str
    t_ns: int
    values: List[float]


@dataclass
class Bundle:
    path: Path
    session: Dict[str, Any]
    frames: List[Dict[str, Any]]
    video_pts_us: List[int]
    imu: List[ImuSample]
    notes: Optional[Dict[str, Any]]
    characteristics: Optional[Dict[str, Any]] = field(default=None, repr=False)

    @property
    def camera_id(self) -> str:
        return str(self.session.get("cameraId", ""))

    def camera_characteristics(self, camera_id: Optional[str] = None) -> Dict[str, Any]:
        cams = (self.characteristics or {}).get("cameras", {})
        return cams.get(camera_id or self.camera_id, {})

    def imu_of(self, sensor: str) -> List[ImuSample]:
        return [s for s in self.imu if s.sensor == sensor]


def _read_json(p: Path) -> Optional[Dict[str, Any]]:
    try:
        return json.loads(p.read_text())
    except (OSError, ValueError):
        return None


def load(path: str | Path, with_imu: bool = True) -> Bundle:
    root = Path(path)
    session = _read_json(root / "session.json") or {}
    frames: List[Dict[str, Any]] = []
    fpath = root / "frames.jsonl"
    if fpath.exists():
        with fpath.open() as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        frames.append(json.loads(line))
                    except ValueError:
                        pass  # truncated last line
    pts: List[int] = []
    vpath = root / "video_frames.csv"
    if vpath.exists():
        with vpath.open() as f:
            for row in csv.DictReader(f):
                pts.append(int(row["pts_us"]))
    imu: List[ImuSample] = []
    ipath = root / "imu.csv"
    if with_imu and ipath.exists():
        with ipath.open() as f:
            reader = csv.reader(f)
            next(reader, None)
            for row in reader:
                if len(row) < 3:
                    continue
                vals = [float(v) for v in row[2:] if v != ""]
                imu.append(ImuSample(row[0], int(row[1]), vals))
    return Bundle(
        path=root,
        session=session,
        frames=frames,
        video_pts_us=pts,
        imu=imu,
        notes=_read_json(root / "notes.json"),
        characteristics=_read_json(root / "camera_characteristics.json"),
    )


def rect_from_json(v: Any):
    """Camera2 Rect JSON ({left, top, right, bottom}) -> tuple, or None."""
    if isinstance(v, dict) and {"left", "top", "right", "bottom"} <= v.keys():
        return (v["left"], v["top"], v["right"], v["bottom"])
    if isinstance(v, (list, tuple)) and len(v) == 4:
        return tuple(v)
    return None
