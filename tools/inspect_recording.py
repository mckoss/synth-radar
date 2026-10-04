#!/usr/bin/env python3
"""Check a Synth Radar recording bundle and print a quality report.

Usage: python3 tools/inspect_recording.py <bundle-dir> [<bundle-dir> ...]

Reports frame timing and drops, how video frames join to capture metadata,
which physical lens was active, whether stabilization stayed off, the lens
calibration mapped into video pixels, IMU rates and coverage, and the
tester's notes. Exits non-zero if any bundle has a blocking problem.
"""

from __future__ import annotations

import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from synthradar import bundle as B  # noqa: E402
from synthradar import lens  # noqa: E402


def _fmt_ms(ns: float) -> str:
    return f"{ns / 1e6:.2f} ms"


def inspect(path: str) -> int:
    b = B.load(path)
    problems: list[str] = []
    warnings: list[str] = []
    s = b.session
    print(f"== {b.path}")
    if not s:
        print("  session.json missing: recording did not finish cleanly")
        return 1
    dev = s.get("device", {})
    video = s.get("video", {})
    cam = s.get("camera", {})
    print(f"  device: {dev.get('manufacturer')} {dev.get('model')} (SDK {dev.get('sdkInt')})")
    print(
        f"  camera {s.get('cameraId')}: {video.get('width')}x{video.get('height')}"
        f" @ {video.get('fps')} fps, {video.get('mime')}, "
        f"{(video.get('bitrate') or 0) / 1e6:.0f} Mbps; audio "
        f"{'on' if s.get('audio', {}).get('enabled') else 'off'}"
    )
    if cam.get("timestampSource") != "REALTIME":
        problems.append(
            f"camera timestamp source is {cam.get('timestampSource')}: "
            "frames and IMU may not share a clock"
        )

    # Frame timing.
    ts = [f.get(B.SENSOR_TIMESTAMP) for f in b.frames if f.get(B.SENSOR_TIMESTAMP)]
    fps = video.get("fps") or 30
    nominal = 1e9 / fps
    if len(ts) < 2:
        problems.append("fewer than 2 capture results")
    else:
        d = [b2 - a for a, b2 in zip(ts, ts[1:])]
        dropped = sum(round(x / nominal) - 1 for x in d if x > 1.5 * nominal)
        dur = (ts[-1] - ts[0]) / 1e9
        print(
            f"  capture results: {len(ts)} over {dur:.2f} s; interval mean {_fmt_ms(statistics.mean(d))}"
            f", sd {_fmt_ms(statistics.pstdev(d))}, max {_fmt_ms(max(d))}; dropped ≈ {dropped}"
        )
        if dropped > 0.01 * len(ts):
            warnings.append(f"{dropped} dropped frames (>1%)")

    # Video ↔ metadata join.
    if b.video_pts_us:
        meta_us = {t // 1000 for t in ts}
        joined = sum(1 for p in b.video_pts_us if p in meta_us or p - 1 in meta_us or p + 1 in meta_us)
        print(f"  video frames: {len(b.video_pts_us)}; matched to capture metadata: {joined}")
        if joined < 0.98 * len(b.video_pts_us):
            problems.append(
                f"only {joined}/{len(b.video_pts_us)} video frames match capture timestamps"
            )
    else:
        problems.append("video_frames.csv missing or empty")

    # Lens state per frame.
    def values(key):
        return [f.get(key) for f in b.frames if key in f]

    def distinct(key):
        out = []
        for v in values(key):
            if v not in out:
                out.append(v)
        return out

    phys = distinct("activePhysicalId")
    if phys:
        print(f"  active physical camera(s): {phys}")
        if len(phys) > 1:
            problems.append(f"lens switched during recording: {phys}")
    zooms = distinct("zoomRatio")
    requested = video.get("zoomRatio", 1.0)
    if zooms:
        print(f"  zoom ratio: requested {requested}, reported {zooms}")
        if any(abs(z - requested) > 0.01 for z in zooms):
            problems.append(f"zoom ratio changed or differs from requested {requested}: {zooms}")
    stab = distinct(B.VIDEO_STABILIZATION)
    print(
        f"  video stabilization: {stab}; OIS: {distinct(B.OIS_MODE)}; "
        f"distortion correction: {distinct(B.DISTORTION_CORRECTION)}"
    )
    if any(v not in (0, None) for v in stab):
        problems.append("electronic video stabilization was ON")
    exp = values(B.EXPOSURE_TIME)
    if exp:
        print(
            f"  exposure: median {_fmt_ms(statistics.median(exp))}, max {_fmt_ms(max(exp))}"
        )
        if statistics.median(exp) > 4e6:
            warnings.append("median exposure > 4 ms: expect motion blur on cars")
    skew = values(B.ROLLING_SHUTTER_SKEW)
    if skew:
        print(f"  rolling-shutter skew: median {_fmt_ms(statistics.median(skew))}")
    focus = values(B.FOCUS_DISTANCE)
    if focus:
        print(
            f"  focus distance: {min(focus):.3f}–{max(focus):.3f} diopters "
            f"(≈ {1 / max(max(focus), 1e-6):.1f} m nearest)"
        )
    if b.frames and "oisSamples" in b.frames[0]:
        n = sum(len(f.get("oisSamples") or []) for f in b.frames)
        print(f"  OIS samples: {n}")

    # Intrinsics → video pixels.
    intr = distinct(B.INTRINSICS)
    chars = b.camera_characteristics()
    if not intr and chars.get("android.lens.intrinsicCalibration"):
        intr = [chars["android.lens.intrinsicCalibration"]]
    pre = B.rect_from_json(chars.get("android.sensor.info.preCorrectionActiveArraySize"))
    act = B.rect_from_json(chars.get("android.sensor.info.activeArraySize"))
    crops = distinct(B.CROP_REGION)
    if intr and pre and act and video.get("width"):
        k = lens.Intrinsics.from_camera2(intr[0])
        crop = B.rect_from_json(crops[0]) if crops else None
        kv = lens.video_intrinsics(k, pre, act, video["width"], video["height"], crop)
        print(
            f"  intrinsics (sensor): fx={k.fx:.1f} fy={k.fy:.1f} cx={k.cx:.1f} cy={k.cy:.1f}"
            f"; {len(intr)} distinct value(s) during recording"
        )
        print(
            f"  intrinsics (video px): fx={kv.fx:.1f} fy={kv.fy:.1f} cx={kv.cx:.1f} cy={kv.cy:.1f}"
            f"; horizontal FOV {lens.horizontal_fov_deg(kv, video['width']):.1f}°"
        )
        dist = distinct(B.DISTORTION) or [chars.get("android.lens.distortion")]
        if dist and dist[0]:
            print(f"  distortion: {[round(x, 5) for x in dist[0]]}")
    else:
        warnings.append("lens intrinsics not available")

    # IMU.
    if b.imu:
        by = {}
        for smp in b.imu:
            by.setdefault(smp.sensor, []).append(smp.t_ns)
        parts = []
        for name, t in sorted(by.items()):
            hz = (len(t) - 1) / ((t[-1] - t[0]) / 1e9) if len(t) > 1 and t[-1] > t[0] else 0
            parts.append(f"{name} {hz:.0f} Hz")
        print(f"  IMU: {', '.join(parts)}")
        gyro = by.get("gyro", [])
        if not gyro:
            problems.append("no gyroscope samples")
        elif ts:
            if gyro[0] > ts[0] or gyro[-1] < ts[-1]:
                warnings.append("gyro log does not fully cover the video")
            gaps = [b2 - a for a, b2 in zip(gyro, gyro[1:])]
            if gaps and max(gaps) > 20e6:
                warnings.append(f"gyro gap of {_fmt_ms(max(gaps))}")
            ghz = (len(gyro) - 1) / ((gyro[-1] - gyro[0]) / 1e9)
            if ghz < 150:
                warnings.append(f"gyro rate only {ghz:.0f} Hz")
    else:
        problems.append("imu.csv missing or empty")

    if b.notes:
        n = b.notes
        gt = n.get("groundTruthMph")
        print(
            f"  notes: {n.get('direction')}, {n.get('vehicleType')}, "
            f"ground truth {gt if gt is not None else '—'} mph ({n.get('groundTruthSource')}), "
            f"{n.get('support')}, phone {n.get('phoneHeightM')} m high"
            + (f", lane {n.get('distanceToLaneM')} m away" if n.get("distanceToLaneM") else "")
        )
        if n.get("notes"):
            print(f"         \"{n['notes']}\"")
    else:
        warnings.append("no notes.json (pass not annotated)")

    for w in warnings:
        print(f"  WARNING: {w}")
    for p in problems:
        print(f"  PROBLEM: {p}")
    if not warnings and not problems:
        print("  OK")
    return 1 if problems else 0


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    rc = 0
    for p in argv:
        rc |= inspect(p)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
