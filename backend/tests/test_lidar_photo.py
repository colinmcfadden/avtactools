"""Photographic colour for streamed pieces, in the background (lidar.photo).

The imagery is stood in for: an image the tests draw, served by a fake fetch.
``EZPZ_LIVE_USGS=1`` also runs ``LiveUsgsTests``, which recolours a piece over
the Ellijay test LZ from USGS's NAIP service.
"""

import io
import json
import math
import os
import shutil
import struct
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from lidar import ept, names, photo, pieces, stream  # noqa: E402
from test_lidar_stream import BASE, GEOID, LAT, LON, SECRET, FakeSurvey, geoid, read_pnts  # noqa: E402


def piece_at(lons, lats, heights=None):
    """A .pnts of points at these places, coloured grey."""
    lons, lats = np.asarray(lons, float), np.asarray(lats, float)
    heights = np.zeros(lons.size) + 300.0 if heights is None else np.asarray(heights, float)
    x = np.radians(lons) * ept.MERCATOR_RADIUS
    y = ept.MERCATOR_RADIUS * np.log(np.tan(np.pi / 4 + np.radians(lats) / 2))
    ex, ey, ez = pieces.mercator_to_ecef(x, y, heights)
    return pieces.pnts(np.column_stack((ex, ey, ez)), np.full((lons.size, 3), 128, np.uint8))


def halves(box, width, height):
    """West half red, east half blue."""
    image = np.zeros((height, width, 3), np.uint8)
    image[:, : width // 2] = (200, 0, 0)
    image[:, width // 2:] = (0, 0, 200)
    return image


class SampleTests(unittest.TestCase):
    def test_a_point_takes_the_colour_under_it_blended_between_pixels(self):
        image = np.zeros((2, 2, 3), np.uint8)
        image[0, 0] = (0, 0, 0)
        image[0, 1] = (100, 0, 0)
        image[1, 0] = (0, 100, 0)
        image[1, 1] = (100, 100, 0)
        box = (0.0, 0.0, 2.0, 2.0)
        # Pixel centres: (0.5, 1.5) top-left ... the middle of the box blends all four.
        rgb = photo.sample(image, box, np.array([1.0, 0.5, 1.5]), np.array([1.0, 1.5, 0.5]))
        self.assertEqual(tuple(rgb[0]), (50, 50, 0))
        self.assertEqual(tuple(rgb[1]), (0, 0, 0))
        self.assertEqual(tuple(rgb[2]), (100, 100, 0))

    def test_a_point_on_the_edge_takes_the_edge(self):
        image = np.full((4, 4, 3), 60, np.uint8)
        rgb = photo.sample(image, (0.0, 0.0, 4.0, 4.0), np.array([0.0, 4.0]), np.array([0.0, 4.0]))
        self.assertTrue((rgb == 60).all())


class SizeTests(unittest.TestCase):
    def test_a_fine_piece_is_asked_for_at_naips_own_resolution(self):
        self.assertEqual(photo.image_size((0.0, 0.0, 195.0, 195.0), spacing_m=0.76), (325, 325))

    def test_a_coarse_piece_is_asked_for_at_its_own_spacing(self):
        width, height = photo.image_size((0.0, 0.0, 3000.0, 1500.0), spacing_m=24.0)
        self.assertEqual((width, height), (250, 125))

    def test_no_image_is_larger_than_the_service_gives(self):
        width, height = photo.image_size((0.0, 0.0, 200000.0, 100000.0), spacing_m=0.5)
        self.assertEqual(max(width, height), photo.MAX_PIXELS)


class RecolourTests(unittest.TestCase):
    def test_each_point_takes_the_imagerys_colour_where_it_stands(self):
        lons = [LON - 0.0005, LON + 0.0005]
        data = piece_at(lons, [LAT, LAT])
        asked = []

        def fetch(box, width, height):
            asked.append((box, width, height))
            return halves(box, width, height)

        recoloured = photo.recolour(data, fetch, spacing_m=0.76)
        _points, rgb, _table, _ = read_pnts(recoloured)
        self.assertEqual([tuple(c) for c in rgb], [(200, 0, 0), (0, 0, 200)])
        # The box is the points', in Web Mercator.
        box, width, height = asked[0]
        x = np.radians(lons) * ept.MERCATOR_RADIUS
        self.assertAlmostEqual(box[0], x.min(), delta=0.01)
        self.assertAlmostEqual(box[2], x.max(), delta=0.01)

    def test_only_the_colours_change(self):
        data = piece_at([LON - 0.0005, LON + 0.0005, LON], [LAT, LAT, LAT + 0.0003])
        recoloured = photo.recolour(data, halves, spacing_m=0.76)
        self.assertEqual(len(recoloured), len(data))
        before, after = read_pnts(data), read_pnts(recoloured)
        self.assertEqual(before[2], after[2])
        np.testing.assert_array_equal(before[0], after[0])

    def test_a_piece_with_no_points_is_left_as_it_is(self):
        empty = pieces.pnts(np.zeros((0, 3)), np.zeros((0, 3), np.uint8))
        self.assertEqual(photo.recolour(empty, halves, spacing_m=1.0), empty)

    def test_an_image_of_the_wrong_shape_is_refused(self):
        data = piece_at([LON], [LAT])
        with self.assertRaises(photo.PhotoError):
            photo.recolour(data, lambda box, w, h: np.zeros((h + 1, w, 3), np.uint8), spacing_m=1.0)


class UsgsTests(unittest.TestCase):
    class Response:
        def __init__(self, status=200, content_type="image/jpeg", body=b""):
            self.status_code, self.headers, self.content = status, {"Content-Type": content_type}, body

    def jpeg(self, width, height):
        from PIL import Image
        buffer = io.BytesIO()
        Image.new("RGB", (width, height), (10, 120, 30)).save(buffer, "JPEG")
        return buffer.getvalue()

    def test_the_service_is_asked_for_the_box_in_web_mercator(self):
        calls = []

        class Session:
            def get(_self, url, params, timeout):
                calls.append((url, params))
                return UsgsTests.Response(body=self.jpeg(4, 2))

        with patch.object(photo, "_http", return_value=Session()):
            image = photo.usgs_image((1.0, 2.0, 5.0, 4.0), 4, 2)
        self.assertEqual(image.shape, (2, 4, 3))
        url, params = calls[0]
        self.assertEqual(url, photo.NAIP_EXPORT)
        self.assertEqual(params["bbox"], "1.0,2.0,5.0,4.0")
        self.assertEqual((params["bboxSR"], params["imageSR"], params["size"]), (3857, 3857, "4,2"))
        self.assertEqual((params["format"], params["f"]), ("jpg", "image"))

    def test_an_answer_that_is_not_an_image_is_refused(self):
        """The service answers a fault with a 200 and JSON."""
        class Session:
            def get(self, url, params, timeout):
                return UsgsTests.Response(content_type="application/json", body=b'{"error": {}}')

        with patch.object(photo, "_http", return_value=Session()), self.assertRaises(photo.PhotoError):
            photo.usgs_image((1.0, 2.0, 5.0, 4.0), 4, 2)

    def test_an_image_of_another_size_is_refused(self):
        class Session:
            def get(_self, url, params, timeout):
                return UsgsTests.Response(body=self.jpeg(3, 3))

        with patch.object(photo, "_http", return_value=Session()), self.assertRaises(photo.PhotoError):
            photo.usgs_image((1.0, 2.0, 5.0, 4.0), 4, 2)


class PassTests(unittest.TestCase):
    """The pass over a real streamer, its workers run by the test."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="photo-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.survey = FakeSurvey()
        self.fetched = []
        self.streamer = stream.Streamer(self.root, fetch=self.survey.fetch, geoid=geoid,
                                        index=self.survey.index(), base=BASE, secret=SECRET)
        self.passer = photo.PhotoPass(self.streamer, image=self.image, workers=0)
        self.streamer.photo = self.passer
        self.key = names.survey_key(self.survey.name, secret=SECRET)

    def image(self, box, width, height):
        self.fetched.append((box, width, height))
        return np.full((height, width, 3), (30, 140, 40), np.uint8)

    def name(self, node):
        return f"{names.piece_name(self.survey.name, node, secret=SECRET)}.pnts"

    def test_a_piece_made_for_a_view_is_offered_and_recoloured_later(self):
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertFalse(self.streamer.photographic(self.key, path.name), "not before the pass")
        self.assertEqual(self.fetched, [], "a view never waits on the imagery")
        self.assertEqual(self.passer.run_pending(), 1)
        _p, rgb, _t, _ = read_pnts(path.read_bytes())
        self.assertTrue((rgb == (30, 140, 40)).all())
        self.assertTrue(self.streamer.photographic(self.key, path.name))

    def test_a_photographic_piece_is_not_offered_again(self):
        self.streamer.file(self.key, self.name(self.survey.centre))
        self.passer.run_pending()
        self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertEqual(self.passer.run_pending(), 0)
        self.assertEqual(len(self.fetched), 1)

    def test_a_palette_piece_served_from_the_cache_is_offered(self):
        """Pieces cached before the pass ran, or across a restart."""
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        self.passer.forget()
        self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertEqual(self.passer.run_pending(), 1)
        self.assertTrue(self.streamer.photographic(self.key, path.name))

    def test_one_piece_is_offered_once_however_often_it_is_asked(self):
        for _ in range(3):
            self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertEqual(self.passer.run_pending(), 1)

    def test_the_queue_is_bounded(self):
        passer = photo.PhotoPass(self.streamer, image=self.image, workers=0, limit=2)
        for node in sorted(self.survey.all_nodes)[:4]:
            passer.offer(self.key, self.name(node))
        self.assertEqual(passer.waiting(), 2)

    def test_a_failed_fetch_leaves_the_palette_and_is_tried_again_later(self):
        def down(box, width, height):
            raise photo.PhotoError("USGS did not answer")
        passer = photo.PhotoPass(self.streamer, image=down, workers=0, retry_after_s=3600)
        self.streamer.photo = passer
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        before = path.read_bytes()
        self.assertEqual(passer.run_pending(), 0)
        self.assertEqual(path.read_bytes(), before)
        self.assertFalse(self.streamer.photographic(self.key, path.name))
        self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertEqual(passer.waiting(), 0, "not again within the hour")
        with patch.object(photo.time, "monotonic", return_value=time.monotonic() + 3601):
            self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertEqual(passer.waiting(), 1)

    def test_a_piece_the_cache_let_go_of_is_skipped(self):
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        path.unlink()
        self.assertEqual(self.passer.run_pending(), 0)
        self.assertEqual(self.fetched, [])

    def test_it_waits_while_an_analysis_runs(self):
        import terrain_tiles
        self.streamer.file(self.key, self.name(self.survey.centre))
        with terrain_tiles.analysis_running():
            self.assertEqual(self.passer.run_pending(wait_s=0.05), 0)
        self.assertEqual(self.passer.run_pending(), 1)

    def test_letting_go_of_a_piece_lets_go_of_its_mark(self):
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        self.passer.run_pending()
        os.utime(path, (1, 1))
        self.streamer.prune(cap=0)
        self.assertFalse(path.exists())
        self.assertFalse(self.streamer.photographic(self.key, path.name))
        self.assertEqual([p for p in self.root.rglob("*.photo")], [])

    def test_workers_run_off_the_request_and_a_view_never_waits(self):
        release = threading.Event()

        def slow(box, width, height):
            release.wait(5)
            return np.full((height, width, 3), 90, np.uint8)
        passer = photo.PhotoPass(self.streamer, image=slow, workers=2)
        self.streamer.photo = passer
        started = time.monotonic()
        path = self.streamer.file(self.key, self.name(self.survey.centre))
        self.assertLess(time.monotonic() - started, 2.0)
        release.set()
        deadline = time.monotonic() + 5
        while not self.streamer.photographic(self.key, path.name) and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(self.streamer.photographic(self.key, path.name))
        passer.stop()


@unittest.skipUnless(os.environ.get("EZPZ_LIVE_USGS") == "1", "set EZPZ_LIVE_USGS=1 to fetch from USGS")
class LiveUsgsTests(unittest.TestCase):
    def test_a_piece_at_ellijay_is_recoloured_from_naip(self):
        lons = LON + np.linspace(-0.001, 0.001, 50)
        data = piece_at(lons, np.full(50, LAT))
        started = time.perf_counter()
        recoloured = photo.recolour(data, photo.usgs_image, spacing_m=0.76)
        elapsed = time.perf_counter() - started
        _p, rgb, _t, _ = read_pnts(recoloured)
        self.assertGreater(len({tuple(c) for c in rgb}), 10, "a photograph, not one colour")
        self.assertLess(elapsed, 30)


if __name__ == "__main__":
    unittest.main()
