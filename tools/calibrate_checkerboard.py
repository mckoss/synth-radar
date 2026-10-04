#!/usr/bin/env python3
"""Calibrate the camera from a checkerboard recording and compare with Camera2.

Record a pass with the Synth Radar app while slowly moving the phone around a
flat checkerboard (printed, or shown full-screen on a monitor), covering the
corners of the frame and tilting up to ~45°. Then:

    pip install opencv-python numpy
    python3 tools/calibrate_checkerboard.py <bundle-dir> --cols 9 --rows 6 --square-mm 25

--cols/--rows count *inner* corners. The square size only affects the
extrinsics, not the intrinsics, but record it anyway.

Prints OpenCV's fitted intrinsics and distortion, Camera2's factory values
mapped into the same video pixel coordinates, and the difference.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from synthradar import bundle as B  # noqa: E402
from synthradar import lens  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle")
    ap.add_argument("--cols", type=int, default=9)
    ap.add_argument("--rows", type=int, default=6)
    ap.add_argument("--square-mm", type=float, default=25.0)
    ap.add_argument("--max-views", type=int, default=60)
    args = ap.parse_args()

    try:
        import cv2
        import numpy as np
    except ImportError:
        print("Needs: pip install opencv-python numpy")
        return 2

    b = B.load(args.bundle, with_imu=False)
    video_path = b.path / "video.mp4"
    cap = cv2.VideoCapture(str(video_path))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    step = max(1, total // (args.max_views * 3))
    pattern = (args.cols, args.rows)
    objp = np.zeros((args.cols * args.rows, 3), np.float32)
    objp[:, :2] = np.mgrid[0 : args.cols, 0 : args.rows].T.reshape(-1, 2) * args.square_mm
    obj_points, img_points = [], []
    size = None
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % step == 0:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            size = gray.shape[::-1]
            found, corners = cv2.findChessboardCornersSB(gray, pattern, flags=cv2.CALIB_CB_EXHAUSTIVE)
            if found:
                obj_points.append(objp)
                img_points.append(corners)
        idx += 1
    cap.release()
    if len(obj_points) > args.max_views:
        sel = np.linspace(0, len(obj_points) - 1, args.max_views).astype(int)
        obj_points = [obj_points[i] for i in sel]
        img_points = [img_points[i] for i in sel]
    print(f"frames read: {idx}, checkerboard views used: {len(obj_points)}")
    if len(obj_points) < 10:
        print("Not enough views with a detected checkerboard (need ≥10).")
        return 1

    rms, K, dist, _, _ = cv2.calibrateCamera(obj_points, img_points, size, None, None)
    print(f"OpenCV calibration: RMS reprojection error {rms:.3f} px")
    print(f"  fx={K[0, 0]:.1f} fy={K[1, 1]:.1f} cx={K[0, 2]:.1f} cy={K[1, 2]:.1f}")
    print(f"  distortion k1,k2,p1,p2,k3 = {np.round(dist.ravel(), 5).tolist()}")

    chars = b.camera_characteristics()
    frames_intr = [f.get(B.INTRINSICS) for f in b.frames if f.get(B.INTRINSICS)]
    intr = frames_intr[0] if frames_intr else chars.get("android.lens.intrinsicCalibration")
    pre = B.rect_from_json(chars.get("android.sensor.info.preCorrectionActiveArraySize"))
    act = B.rect_from_json(chars.get("android.sensor.info.activeArraySize"))
    crops = [f.get(B.CROP_REGION) for f in b.frames if f.get(B.CROP_REGION)]
    if not (intr and pre and act):
        print("Camera2 intrinsics not available in this bundle; nothing to compare.")
        return 0
    kv = lens.video_intrinsics(
        lens.Intrinsics.from_camera2(intr), pre, act, size[0], size[1],
        B.rect_from_json(crops[0]) if crops else None,
    )
    print("Camera2 factory calibration mapped to video pixels:")
    print(f"  fx={kv.fx:.1f} fy={kv.fy:.1f} cx={kv.cx:.1f} cy={kv.cy:.1f}")
    print("Difference (OpenCV − Camera2):")
    print(
        f"  fx {100 * (K[0, 0] / kv.fx - 1):+.2f}%  fy {100 * (K[1, 1] / kv.fy - 1):+.2f}%"
        f"  cx {K[0, 2] - kv.cx:+.1f} px  cy {K[1, 2] - kv.cy:+.1f} px"
    )
    print(
        "A focal-length difference here becomes the same percentage error in range\n"
        "and speed, so we want it well under 1%."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
