import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from synthradar import lens  # noqa: E402


class OutputRegionTest(unittest.TestCase):
    def test_16x9_from_4x3_trims_top_and_bottom(self):
        r = lens.output_region((0, 0, 4000, 3000), 3840, 2160)
        self.assertAlmostEqual(r[0], 0)
        self.assertAlmostEqual(r[2], 4000)
        self.assertAlmostEqual(r[3] - r[1], 2250)
        self.assertAlmostEqual(r[1], 375)

    def test_tall_output_trims_sides(self):
        r = lens.output_region((0, 0, 4000, 3000), 1000, 1000)
        self.assertAlmostEqual(r, (500, 0, 3500, 3000))


class VideoIntrinsicsTest(unittest.TestCase):
    def test_centered_camera_maps_to_video_center(self):
        k = lens.Intrinsics(fx=3000, fy=3000, cx=2000, cy=1500)
        array = (0, 0, 4000, 3000)
        kv = lens.video_intrinsics(k, array, array, 1920, 1080)
        scale = 1920 / 4000
        self.assertAlmostEqual(kv.fx, 3000 * scale)
        self.assertAlmostEqual(kv.cx, 960)
        self.assertAlmostEqual(kv.cy, 540)

    def test_pre_correction_offset_is_removed(self):
        k = lens.Intrinsics(fx=3000, fy=3000, cx=2008, cy=1508)
        pre = (0, 0, 4016, 3016)
        act = (8, 8, 4008, 3008)
        kv = lens.video_intrinsics(k, pre, act, 4000, 2250)
        self.assertAlmostEqual(kv.cx, 2000)
        self.assertAlmostEqual(kv.cy, 1125)

    def test_crop_region_zooms(self):
        k = lens.Intrinsics(fx=3000, fy=3000, cx=2000, cy=1500)
        array = (0, 0, 4000, 3000)
        crop = (1000, 750, 3000, 2250)  # 2x zoom
        kv = lens.video_intrinsics(k, array, array, 2000, 1500, crop)
        self.assertAlmostEqual(kv.fx, 3000)
        self.assertAlmostEqual(kv.cx, 1000)

    def test_fov(self):
        k = lens.Intrinsics(fx=960, fy=960, cx=960, cy=540)
        self.assertAlmostEqual(lens.horizontal_fov_deg(k, 1920), 90.0)


class DistortionTest(unittest.TestCase):
    def test_round_trip(self):
        kappa = [0.08, -0.15, 0.05, 0.001, -0.0005]
        for x, y in [(0.1, 0.2), (-0.5, 0.3), (0.6, -0.4)]:
            xc, yc = lens.distort_normalized(x, y, kappa)
            xu, yu = lens.undistort_normalized(xc, yc, kappa)
            self.assertAlmostEqual(xu, x, places=6)
            self.assertAlmostEqual(yu, y, places=6)

    def test_zero_distortion_is_identity(self):
        self.assertEqual(lens.distort_normalized(0.3, -0.2, [0, 0, 0, 0, 0]), (0.3, -0.2))


if __name__ == "__main__":
    unittest.main()
