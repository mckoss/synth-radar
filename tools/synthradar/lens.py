"""Camera2 lens model: map factory calibration into recorded-video pixel coordinates.

Android reports LENS_INTRINSIC_CALIBRATION [fx, fy, cx, cy, s] in the
*pre-correction active array* pixel coordinate system, and LENS_DISTORTION
(Brown-Conrady, kappa_1..kappa_5) in normalized coordinates centred on the
optical axis. Processed (non-RAW) output buffers are in *active array*
coordinates. A video stream is produced from the crop region (full active
array at zoom 1.0): the camera takes the largest centred region of the output
aspect ratio and scales it to the output size.

All rectangles here are (left, top, right, bottom) in sensor pixel-array
coordinates, as Camera2 reports them.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Sequence, Tuple

Rect = Tuple[float, float, float, float]


@dataclass(frozen=True)
class Intrinsics:
    fx: float
    fy: float
    cx: float
    cy: float
    skew: float = 0.0

    @staticmethod
    def from_camera2(values: Sequence[float]) -> "Intrinsics":
        fx, fy, cx, cy, s = (list(values) + [0.0] * 5)[:5]
        return Intrinsics(fx, fy, cx, cy, s)


def rect_size(r: Rect) -> Tuple[float, float]:
    return r[2] - r[0], r[3] - r[1]


def output_region(crop: Rect, out_w: int, out_h: int) -> Rect:
    """Largest centred sub-rectangle of `crop` with the output aspect ratio."""
    cw, ch = rect_size(crop)
    target = out_w / out_h
    if cw / ch > target:  # crop is wider: trim the sides
        w, h = ch * target, ch
    else:  # crop is taller: trim top and bottom
        w, h = cw, cw / target
    left = crop[0] + (cw - w) / 2
    top = crop[1] + (ch - h) / 2
    return (left, top, left + w, top + h)


def video_intrinsics(
    k: Intrinsics,
    pre_correction_array: Rect,
    active_array: Rect,
    out_w: int,
    out_h: int,
    crop_region: Optional[Rect] = None,
) -> Intrinsics:
    """Intrinsics expressed in the pixel coordinates of an output stream.

    `crop_region` is SCALER_CROP_REGION (active-array coordinates, i.e. relative
    to the active array's top-left); None means the full active array.
    """
    aw, ah = rect_size(active_array)
    crop = crop_region if crop_region is not None else (0.0, 0.0, aw, ah)
    region = output_region(crop, out_w, out_h)
    scale_x = out_w / rect_size(region)[0]
    scale_y = out_h / rect_size(region)[1]
    # Pre-correction array coords -> active array coords.
    dx = active_array[0] - pre_correction_array[0]
    dy = active_array[1] - pre_correction_array[1]
    return Intrinsics(
        fx=k.fx * scale_x,
        fy=k.fy * scale_y,
        cx=(k.cx - dx - region[0]) * scale_x,
        cy=(k.cy - dy - region[1]) * scale_y,
        skew=k.skew * scale_x,
    )


def distort_normalized(x: float, y: float, kappa: Sequence[float]) -> Tuple[float, float]:
    """Camera2 / Brown-Conrady forward model on normalized coordinates.

    Given an ideal (corrected) normalized point, returns where it is sampled in
    the uncorrected image.
    """
    k1, k2, k3, k4, k5 = (list(kappa) + [0.0] * 5)[:5]
    r2 = x * x + y * y
    radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
    xc = x * radial + k4 * (2 * x * y) + k5 * (r2 + 2 * x * x)
    yc = y * radial + k5 * (2 * x * y) + k4 * (r2 + 2 * y * y)
    return xc, yc


def undistort_normalized(
    xc: float, yc: float, kappa: Sequence[float], iterations: int = 20
) -> Tuple[float, float]:
    """Inverse of distort_normalized by fixed-point iteration."""
    x, y = xc, yc
    for _ in range(iterations):
        dx, dy = distort_normalized(x, y, kappa)
        x, y = x - (dx - xc), y - (dy - yc)
    return x, y


def horizontal_fov_deg(k: Intrinsics, width: int) -> float:
    import math

    return math.degrees(2 * math.atan(width / (2 * k.fx)))
