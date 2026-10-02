"""Terrain masking for threats: who can see what, and how it is drawn.

Written before the code moved out of the route module, to hold its behavior
steady: the viewshed decides which ground a radar covers, so a change in what
it returns is a change in what a crew is told is safe.
"""

import base64
import unittest

import cv2
import numpy as np

from app.services.threats.kmz import _circle_coords, _kml_color, _viewshed_rings
from app.services.threats.viewshed import (
    _hex_to_bgr,
    radar_elev_m,
    render_mask_png,
    viewshed,
)

SIZE = 121
CENTER = SIZE // 2
MPP = 30.0
GROUND_M = 100.0
ANTENNA_M = 110.0   # 10 m above the ground

META = {"radar_row": CENTER, "radar_col": CENTER, "mpp": MPP,
        "north": 34.80, "south": 34.78, "west": -84.10, "east": -84.07}


def flat():
    return np.full((SIZE, SIZE), GROUND_M, np.float32)


def ridge():
    """A wall 20 cells east of the radar, 3 cells wide and 400 m high."""
    dem = flat()
    dem[:, CENTER + 20:CENTER + 23] = 400.0
    return dem


class ViewshedTests(unittest.TestCase):
    def test_flat_ground_is_visible_out_to_the_requested_range_and_no_further(self):
        seen = viewshed(flat(), CENTER, CENTER, ANTENNA_M, 0.0, 40, MPP)
        self.assertTrue(seen[CENTER, CENTER + 39])
        self.assertTrue(seen[CENTER + 39, CENTER])
        self.assertFalse(seen[CENTER, CENTER + 45])
        self.assertFalse(seen[0, 0])

    def test_a_ridge_hides_what_is_behind_it_from_a_low_target(self):
        seen = viewshed(ridge(), CENTER, CENTER, ANTENNA_M, 0.0, 55, MPP)
        self.assertFalse(seen[CENTER, CENTER + 35])
        # The other side of the radar has nothing in the way.
        self.assertTrue(seen[CENTER, CENTER - 35])

    def test_a_target_high_enough_is_seen_over_the_ridge(self):
        seen = viewshed(ridge(), CENTER, CENTER, ANTENNA_M, 1000.0, 55, MPP)
        self.assertTrue(seen[CENTER, CENTER + 35])

    def test_the_earth_curves_away_from_the_radar(self):
        """Flat ground 45 km out is below the horizon of a 10 m antenna."""
        big = np.full((401, 401), GROUND_M, np.float32)
        seen = viewshed(big, 200, 200, ANTENNA_M, 0.0, 190, 250.0)
        self.assertTrue(seen[200, 208])      # 2 km
        self.assertFalse(seen[200, 380])     # 45 km

    def test_refraction_bends_the_horizon_a_little_further_out(self):
        """A 10 m antenna sees flat ground to ~12.1 km with standard refraction,
        ~11.3 km without. The cell at 11.75 km tells the two apart."""
        big = np.full((401, 401), GROUND_M, np.float32)
        seen = viewshed(big, 200, 200, ANTENNA_M, 0.0, 100, 250.0)
        self.assertTrue(seen[200, 247])      # 11.75 km
        self.assertFalse(seen[200, 250])     # 12.5 km

    def test_no_range_sees_nothing(self):
        self.assertFalse(viewshed(flat(), CENTER, CENTER, ANTENNA_M, 0.0, 0, MPP).any())


class RadarElevationTests(unittest.TestCase):
    META = {"radar_row": CENTER, "radar_col": CENTER}

    def test_above_ground_adds_the_antenna_height_in_metres(self):
        self.assertAlmostEqual(radar_elev_m(flat(), self.META, 30, True), 109.144, places=3)

    def test_above_sea_level_is_the_antenna_height_alone(self):
        self.assertAlmostEqual(radar_elev_m(flat(), self.META, 30, False), 9.144, places=3)

    def test_ground_is_the_highest_cell_of_the_radars_footprint(self):
        """So one coarse cell beside a low antenna cannot shadow the whole site."""
        dem = flat()
        dem[CENTER - 1, CENTER + 1] = 150.0
        self.assertAlmostEqual(radar_elev_m(dem, self.META, 0, True), 150.0)


def decode_png(data_url):
    prefix = "data:image/png;base64,"
    assert data_url.startswith(prefix)
    raw = base64.b64decode(data_url[len(prefix):])
    return cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_UNCHANGED)


class MaskRenderingTests(unittest.TestCase):
    def test_a_visible_band_is_painted_in_its_colour_and_opacity(self):
        band = {"altFt": 50, "color": "#ff0000", "alpha": 0.5}
        image = decode_png(render_mask_png(flat(), META, ANTENNA_M, [band], 30))
        self.assertEqual(image.shape, (SIZE, SIZE, 4))
        b, g, r, a = image[CENTER, CENTER + 20]
        self.assertEqual((b, g, r), (0, 0, 255))
        self.assertEqual(a, 128)
        self.assertEqual(image[0, 0, 3], 0)          # outside range: transparent

    def test_the_lower_band_is_drawn_over_the_higher_one(self):
        """Low-altitude exposure is the more restrictive, so it must stay on top."""
        bands = [{"altFt": 4000, "color": "#00ff00", "alpha": 1.0},
                 {"altFt": 50, "color": "#ff0000", "alpha": 1.0}]
        image = decode_png(render_mask_png(ridge(), META, ANTENNA_M, bands, 55))
        in_front = image[CENTER, CENTER + 10]
        behind = image[CENTER, CENTER + 35]
        self.assertEqual(tuple(in_front[:3]), (0, 0, 255))    # low band visible: red wins
        self.assertEqual(tuple(behind[:3]), (0, 255, 0))      # only the high band sees over

    def test_nothing_in_range_is_no_image(self):
        self.assertIsNone(render_mask_png(flat(), META, ANTENNA_M, [{"altFt": 50}], 0))

    def test_colours_default_to_red(self):
        self.assertEqual(_hex_to_bgr("#ff8800"), (0, 136, 255))
        self.assertEqual(_hex_to_bgr(None), (0, 0, 255))


class VectorOutlineTests(unittest.TestCase):
    def visible(self):
        return viewshed(flat(), CENTER, CENTER, ANTENNA_M, 0.0, 40, MPP)

    def test_a_visible_area_becomes_a_closed_ring_inside_the_map(self):
        rings = _viewshed_rings(self.visible(), META, SIZE, SIZE)
        self.assertEqual(len(rings), 1)
        ring = rings[0]
        self.assertEqual(ring[0], ring[-1])
        for lon, lat in ring:
            self.assertTrue(META["west"] <= lon <= META["east"])
            self.assertTrue(META["south"] <= lat <= META["north"])

    def test_specks_too_small_to_matter_are_dropped(self):
        speck = np.zeros((SIZE, SIZE), bool)
        speck[10, 10] = True
        self.assertEqual(_viewshed_rings(speck, META, SIZE, SIZE), [])

    def test_kml_colour_is_alpha_blue_green_red(self):
        self.assertEqual(_kml_color("#ff8800", 0.5), "7f0088ff")
        self.assertEqual(_kml_color(None, 2), "ff0000ff")       # default red, alpha clamped
        self.assertEqual(_kml_color("#ff8800", -1), "000088ff")

    def test_a_range_ring_is_closed_and_the_right_size(self):
        ring = _circle_coords(34.78, -84.08, 1000, n=8)
        self.assertEqual(len(ring), 9)
        self.assertAlmostEqual(ring[0][0], ring[-1][0], places=9)
        self.assertAlmostEqual(ring[0][1], ring[-1][1], places=9)
        # Due north of the centre, 1 km is about 0.009 degrees of latitude.
        self.assertAlmostEqual(ring[0][1] - 34.78, 1000 / 111320.0, places=6)


if __name__ == "__main__":
    unittest.main()
