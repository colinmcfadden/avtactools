"""The elevation lookup and the SAM field analysis, with the network and the
model replaced.

Written before this logic moved out of the route module. The analysis route is
the one that turns a click into a suggested LZ polygon, so the arithmetic that
maps pixels back to latitude and longitude is pinned here.
"""

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import cv2
import mercantile
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# The route module loads the SAM model when imported, which needs 350 MB of
# weights and a second or two. None of it is needed here.
if "app.routes.terrain" not in sys.modules:
    sys.modules.setdefault("ultralytics", MagicMock())
from app.routes import terrain as terrain_routes
from app.routes.terrain import (  # noqa: E402
    _decode_terrarium,
    _sample_elevations_ft,
    fetch_satellite_tile,
    terrain_bp,
)
from support import make_app  # noqa: E402

LZ = (34.783817, -84.08219)


def terrarium_png(metres):
    """A 256x256 Terrarium tile of one constant elevation."""
    value = metres + 32768.0
    red = int(value // 256)
    green = int(value - red * 256)
    blue = int(round((value - int(value)) * 256))
    tile = np.zeros((256, 256, 3), np.uint8)
    tile[:, :] = (blue, green, red)          # OpenCV is BGR
    ok, buffer = cv2.imencode(".png", tile)
    assert ok
    return buffer.tobytes()


def imagery_png():
    tile = np.full((256, 256, 3), (30, 90, 40), np.uint8)
    ok, buffer = cv2.imencode(".png", tile)
    assert ok
    return buffer.tobytes()


class Response:
    def __init__(self, content=b"", status=200, body=None):
        self.content, self.status_code, self._body = content, status, body

    def json(self):
        return self._body


class TerrariumTests(unittest.TestCase):
    def test_colour_channels_decode_to_metres(self):
        decoded = _decode_terrarium(terrarium_png(500.0))
        self.assertEqual(decoded.shape, (256, 256))
        self.assertAlmostEqual(float(decoded[0, 0]), 500.0, places=2)

    def test_garbage_is_not_a_tile(self):
        self.assertIsNone(_decode_terrarium(b"not a png"))

    def test_points_are_sampled_in_feet_from_one_fetch_per_tile(self):
        with patch.object(terrain_routes.requests, "get",
                          return_value=Response(terrarium_png(500.0))) as get:
            feet = _sample_elevations_ft([{"lat": LZ[0], "lon": LZ[1]},
                                          {"lat": LZ[0] + 0.0001, "lon": LZ[1] + 0.0001}])
        self.assertEqual(feet, [1640, 1640])               # 500 m
        self.assertEqual(get.call_count, 1)

    def test_a_tile_that_cannot_be_fetched_leaves_its_points_unknown(self):
        with patch.object(terrain_routes.requests, "get", return_value=Response(status=503)):
            self.assertEqual(_sample_elevations_ft([{"lat": LZ[0], "lon": LZ[1]}]), [None])
        with patch.object(terrain_routes.requests, "get",
                          side_effect=terrain_routes.requests.exceptions.ConnectionError):
            self.assertEqual(_sample_elevations_ft([{"lat": LZ[0], "lon": LZ[1]}]), [None])


class ElevationsEndpointTests(unittest.TestCase):
    def setUp(self):
        app, self.auth = make_app(terrain_bp)
        self.client = app.test_client()

    def post(self, body):
        return self.client.post("/api/elevations", json=body, headers=self.auth)

    def test_needs_a_token(self):
        self.assertEqual(self.client.post("/api/elevations", json={}).status_code, 401)

    def test_no_points_is_an_empty_list_without_any_lookup(self):
        with patch.object(terrain_routes.requests, "get") as get:
            self.assertEqual(self.post({"points": []}).get_json(), {"elevationsFt": []})
        get.assert_not_called()

    def test_malformed_points_are_refused(self):
        self.assertEqual(self.post({"points": [{"lat": 1}]}).status_code, 400)
        self.assertEqual(self.post({"points": [{"lat": "x", "lon": 2}]}).status_code, 400)

    def test_elevations_come_back_aligned_with_the_points(self):
        with patch.object(terrain_routes.requests, "get", return_value=Response(terrarium_png(100.0))):
            body = self.post({"points": [{"lat": LZ[0], "lon": LZ[1]}] * 2}).get_json()
        self.assertEqual(body, {"elevationsFt": [328, 328]})


class FakeSam:
    """Stands in for ultralytics.SAM: one mask, a square in the tile."""

    def __init__(self, polygon=((10, 10), (100, 10), (100, 100), (10, 100)), masks=True):
        self.polygon, self.masks, self.calls = polygon, masks, []

    def predict(self, image, **kwargs):
        self.calls.append(kwargs)
        masks = SimpleNamespace(xy=[np.array(self.polygon, float)]) if self.masks else None
        return [SimpleNamespace(masks=masks)]


class AnalyzeFieldTests(unittest.TestCase):
    def setUp(self):
        app, self.auth = make_app(terrain_bp)
        self.client = app.test_client()
        self.model = FakeSam()

    def analyze(self, body=None, *, tile_status=200, elevation=500, model=None):
        def fake_get(url, *args, **kwargs):
            if "opentopodata" in url:
                return Response(body={"results": [{"elevation": elevation}]})
            return Response(imagery_png(), status=tile_status)

        with patch.object(terrain_routes.requests, "get", side_effect=fake_get), \
                patch.object(terrain_routes, "model", model or self.model):
            return self.client.post("/api/analyze-field",
                                    json={"lat": LZ[0], "lon": LZ[1]} if body is None else body,
                                    headers=self.auth)

    def test_needs_a_token(self):
        self.assertEqual(self.client.post("/api/analyze-field", json={}).status_code, 401)

    def test_a_click_becomes_a_polygon_in_latitude_and_longitude(self):
        response = self.analyze()
        body = response.get_json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual((body["status"], body["message"]), ("success", "Field detected"))
        self.assertEqual(body["elevation"], "1640")

        tile = mercantile.tile(LZ[1], LZ[0], 14)
        box = mercantile.bounds(tile)
        expected = [[box.north - (py / 256) * (box.north - box.south),
                     box.west + (px / 256) * (box.east - box.west)]
                    for px, py in ((10, 10), (100, 10), (100, 100), (10, 100))]
        self.assertEqual(len(body["suggested_lz"]), 4)
        for got, want in zip(body["suggested_lz"], expected):
            self.assertAlmostEqual(got[0], want[0], places=9)
            self.assertAlmostEqual(got[1], want[1], places=9)

    def test_the_model_is_prompted_at_the_clicked_pixel_of_the_tile(self):
        self.analyze()
        tile = mercantile.tile(LZ[1], LZ[0], 14)
        box = mercantile.bounds(tile)
        px = int((LZ[1] - box.west) / (box.east - box.west) * 256)
        py = int((box.north - LZ[0]) / (box.north - box.south) * 256)
        call = self.model.calls[0]
        self.assertEqual(call["points"], [[px, py]])
        self.assertEqual((call["labels"], call["imgsz"]), ([1], 512))

    def test_no_mask_is_a_clear_answer_not_an_error(self):
        response = self.analyze(model=FakeSam(masks=False))
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()["message"], "No distinct field found at this point")

    def test_missing_imagery_is_reported(self):
        response = self.analyze(tile_status=404)
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.get_json()["message"], "Map data unavailable")

    def test_bad_coordinates_are_refused_before_anything_is_fetched(self):
        with patch.object(terrain_routes.requests, "get") as get:
            response = self.client.post("/api/analyze-field", json={"lat": "x"}, headers=self.auth)
        self.assertEqual(response.status_code, 400)
        get.assert_not_called()

    def test_elevation_is_to_be_determined_when_the_lookup_fails(self):
        def fake_get(url, *args, **kwargs):
            if "opentopodata" in url:
                raise terrain_routes.requests.exceptions.ConnectionError
            return Response(imagery_png())

        with patch.object(terrain_routes.requests, "get", side_effect=fake_get), \
                patch.object(terrain_routes, "model", self.model):
            body = self.client.post("/api/analyze-field", json={"lat": LZ[0], "lon": LZ[1]},
                                    headers=self.auth).get_json()
        self.assertEqual(body["elevation"], "TBD")
        self.assertEqual(body["status"], "success")

    def test_a_model_failure_is_reported_as_an_error(self):
        class Broken:
            def predict(self, *args, **kwargs):
                raise RuntimeError("out of memory")

        response = self.analyze(model=Broken())
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.get_json()["message"], "out of memory")

    def test_the_imagery_tile_comes_from_arcgis_at_the_zoom_asked_for(self):
        with patch.object(terrain_routes.requests, "get", return_value=Response(imagery_png())) as get:
            image, tile = fetch_satellite_tile(LZ[0], LZ[1], zoom=16)
        self.assertEqual(image.shape, (256, 256, 3))
        self.assertEqual((tile.z, tile.x, tile.y), (16, *mercantile.tile(LZ[1], LZ[0], 16)[:2]))
        self.assertIn("World_Imagery/MapServer/tile/16/", get.call_args.args[0])
        with patch.object(terrain_routes.requests, "get", return_value=Response(status=404)):
            self.assertEqual(fetch_satellite_tile(LZ[0], LZ[1]), (None, None))


if __name__ == "__main__":
    unittest.main()
