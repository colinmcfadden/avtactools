"""The threat KMZ's mask outlines are simple rings (ForeFlight hangs on any that are not)."""

import io
import os
import re
import sys
import unittest
import zipfile
from unittest import mock

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mask_rings as mr  # noqa: E402


def _masks(seed, count, size=60):
    """Speckled and blobby masks: the shapes a viewshed gives, necks and corners included."""
    rng = np.random.default_rng(seed)
    for k in range(count):
        if k % 2:
            yield rng.random((size, size)) < rng.uniform(0.35, 0.65)
        else:
            m = np.zeros((size, size), bool)
            for _ in range(rng.integers(3, 12)):
                r, c = rng.integers(0, size, 2)
                h, w = rng.integers(1, 15, 2)
                m[r:r + h, c:c + w] = True
            m[rng.random((size, size)) < 0.08] ^= True
            yield m


class IsSimpleTest(unittest.TestCase):
    def test_square_is_simple(self):
        self.assertTrue(mr.is_simple([(0, 0), (4, 0), (4, 4), (0, 4)]))

    def test_bow_tie_is_not(self):
        self.assertFalse(mr.is_simple([(0, 0), (4, 4), (4, 0), (0, 4)]))

    def test_a_point_twice_is_not(self):
        self.assertFalse(mr.is_simple([(0, 0), (2, 0), (1, 1), (2, 2), (0, 2), (1, 1)]))

    def test_a_point_on_another_edge_is_not(self):
        # (2, 0) lies on the edge (0, 0)-(4, 0) without being one of its ends
        self.assertFalse(mr.is_simple([(0, 0), (4, 0), (4, 3), (2, 0), (0, 3)]))

    def test_a_flat_triangle_is_not(self):
        self.assertFalse(mr.is_simple([(0, 0), (1, 1), (2, 2)]))


class MaskRingsTest(unittest.TestCase):
    def assert_all_simple(self, vis):
        rings = mr.mask_rings(vis)
        for ring in rings:
            self.assertTrue(mr.is_simple(ring), ring)
        return rings

    def test_two_areas_touching_at_a_corner_are_two_rings(self):
        vis = np.zeros((12, 12), bool)
        vis[1:5, 1:5] = True
        vis[5:9, 5:9] = True  # meets the first only at a corner
        rings = self.assert_all_simple(vis)
        self.assertEqual(len(rings), 2)

    def test_a_one_pixel_neck_is_cut(self):
        vis = np.zeros((12, 20), bool)
        vis[2:9, 1:7] = True
        vis[5, 7:12] = True  # a line one pixel wide
        vis[2:9, 12:18] = True
        rings = self.assert_all_simple(vis)
        self.assertEqual(len(rings), 2)

    def test_a_spike_is_left_out(self):
        vis = np.zeros((12, 12), bool)
        vis[3:9, 3:9] = True
        vis[5, 9:12] = True
        rings = self.assert_all_simple(vis)
        self.assertEqual(len(rings), 1)

    def test_small_areas_are_left_out(self):
        vis = np.zeros((10, 10), bool)
        vis[1:3, 1:3] = True
        self.assertEqual(mr.mask_rings(vis), [])

    def test_every_ring_of_random_masks_is_simple(self):
        for vis in _masks(seed=7, count=200):
            self.assert_all_simple(vis)

    def test_the_rings_cover_what_the_old_outlines_did(self):
        """Splitting and simplifying move an outline by about a pixel; they take no area away."""
        import cv2

        def covered(rings, shape):
            drawn = np.zeros(shape, np.uint8)
            cv2.fillPoly(drawn, [np.array(r, np.int32) for r in rings], 1)
            return drawn.astype(bool)

        def old_rings(vis):
            contours, _ = cv2.findContours(vis.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            return [cv2.approxPolyDP(c, 1.5, True)[:, 0, :] for c in contours
                    if cv2.contourArea(c) >= 8 and len(cv2.approxPolyDP(c, 1.5, True)) >= 3]

        # The blobby masks only: an old ring folded over itself could fill pixels
        # of dense speckle that no simple outline of it reaches.
        for k, vis in enumerate(_masks(seed=11, count=40)):
            if k % 2:
                continue
            new = (vis & covered(mr.mask_rings(vis), vis.shape)).sum()
            old = (vis & covered(old_rings(vis), vis.shape)).sum()
            self.assertGreaterEqual(new, 0.95 * old)

    def test_unsimplified_loops_are_simple(self):
        """The fallback, when no simplification stays simple, is itself simple."""
        for vis in _masks(seed=3, count=100):
            contours = mr.cv2.findContours(vis.astype(np.uint8), mr.cv2.RETR_EXTERNAL, mr.cv2.CHAIN_APPROX_NONE)[0]
            for c in contours:
                for loop in mr._split_at_repeats([(int(x), int(y)) for x, y in c[:, 0, :]]):
                    if mr._area(loop) != 0:
                        self.assertTrue(mr.is_simple(loop), loop)


class ThreatKmzTest(unittest.TestCase):
    """End to end through build_threats_kmz, over a rough synthetic DEM."""

    def test_every_polygon_in_the_kmz_is_simple(self):
        from routes import threat_routes

        rng = np.random.default_rng(5)
        size = 241
        dem = rng.normal(0, 25, (size, size)).cumsum(0).cumsum(1) / 40 + 300
        meta = {'west': -84.4, 'east': -84.0, 'south': 34.5, 'north': 34.8,
                'mpp': 120.0, 'radar_row': size // 2, 'radar_col': size // 2}
        bands = [{'altFt': a, 'color': '#ff0000', 'alpha': 0.35} for a in (50, 100, 250)]
        threat = {'name': 'SA-8', 'lat': 34.65, 'lon': -84.2, 'milstdId': 'SHGPEWMAI------',
                  'radars': [{'type': 0, 'rangeNmi': 7, 'antennaHeightFt': 20, 'aglNotMsl': True, 'bands': bands},
                             {'type': 1, 'rangeNmi': 4, 'antennaHeightFt': 20, 'aglNotMsl': True, 'bands': bands}]}
        with mock.patch.object(threat_routes, 'fetch_dem', return_value=(dem, meta)):
            data = threat_routes.build_threats_kmz([threat])
        kml = zipfile.ZipFile(io.BytesIO(data)).read('doc.kml').decode()
        rings = re.findall(r'<LinearRing><coordinates>(.*?)</coordinates>', kml)
        self.assertGreater(len(rings), 10)
        for text in rings:
            pts = [tuple(map(float, p.split(',')[:2])) for p in text.split()]
            self.assertEqual(pts[0], pts[-1])
            self.assertTrue(mr.is_simple(pts[:-1]), text[:200])


if __name__ == '__main__':
    unittest.main()
