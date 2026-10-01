"""Ground heights for placing route graphics in the 3D view.

A route drawn in 3D is only useful if it sits in the same frame as the terrain
under it. These heights come from the same DEMs, with the same geoid
correction, as the terrain tiles — so a curtain hung from a route meets the
surface the viewer actually draws.
"""

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import rasterio
from flask import Flask
from flask_jwt_extended import JWTManager, create_access_token
from rasterio.transform import from_origin

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import terrain_tiles  # noqa: E402
from terrain_provider import RasterEntry  # noqa: E402

# The terrain blueprint loads the SAM model at import, which downloads 350 MB
# when the weights are missing. None of it is needed here.
if "routes.terrain_routes" not in sys.modules:
    sys.modules.setdefault("ultralytics", MagicMock())
from routes.terrain_routes import terrain_bp  # noqa: E402

PIXEL_DEG = 1.0 / 1200


def plane(col, row):
    """A tilted plane: bilinear interpolation reproduces it exactly."""
    return 300.0 + 0.25 * col + 0.1 * row


def write_dem(path, *, west, north, size, value=None, nodata_box=None):
    cols, rows = np.meshgrid(np.arange(size), np.arange(size))
    heights = (np.full((size, size), value, dtype=np.float32) if value is not None
               else plane(cols, rows).astype(np.float32))
    if nodata_box:
        r0, r1, c0, c1 = nodata_box
        heights[r0:r1, c0:c1] = -999999.0
    with rasterio.open(path, "w", driver="GTiff", width=size, height=size, count=1,
                       dtype="float32", crs="EPSG:4269",
                       transform=from_origin(west, north, PIXEL_DEG, PIXEL_DEG),
                       nodata=-999999.0) as dst:
        dst.write(heights, 1)
    south = north - size * PIXEL_DEG
    east = west + size * PIXEL_DEG
    return RasterEntry(path=str(path), kind="geotiff",
                       bounds_latlon=(south, west, north, east),
                       resolution_m=PIXEL_DEG * 111_000, vertical_datum="NAVD88")


def at_pixel(col, row, *, west=-84.0, north=35.0):
    """(lat, lon) of a fractional pixel position measured from pixel centres."""
    return (north - (row + 0.5) * PIXEL_DEG, west + (col + 0.5) * PIXEL_DEG)


class FixedGeoid:
    def __init__(self, separation):
        self.separation = separation

    def transform(self, lons, lats, heights):
        return lons, lats, np.full(len(lons), self.separation)


class SamplePointsTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="dem-"))
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.dem = write_dem(self.dir / "dem.tif", west=-84.0, north=35.0, size=200)
        self.sources = patch.object(terrain_tiles, "_sources_for", return_value=[self.dem])
        self.sources.start()
        self.addCleanup(self.sources.stop)
        self.geoid = patch.object(terrain_tiles, "_geoid_transformer",
                                  return_value=FixedGeoid(-30.35))
        self.geoid.start()
        self.addCleanup(self.geoid.stop)

    def test_heights_are_interpolated_between_pixels(self):
        """The tiles are bilinear; a nearest-pixel height would sit off them."""
        ground, _geoid = terrain_tiles.sample_points([at_pixel(10.5, 20.25)])
        self.assertAlmostEqual(ground[0], plane(10.5, 20.25) - 30.35, places=1)

    def test_ground_is_ellipsoidal_and_the_separation_is_returned(self):
        """Ground in Cesium's frame; geoid so the client can place MSL altitudes."""
        ground, geoid = terrain_tiles.sample_points([at_pixel(50, 50)])
        self.assertAlmostEqual(ground[0], plane(50, 50) - 30.35, places=1)
        self.assertEqual(geoid, [-30.35])

    def test_a_point_off_every_dem_has_no_ground(self):
        """The tiles draw it flat; inventing a height would misplace the curtain."""
        ground, geoid = terrain_tiles.sample_points([(10.0, 10.0)])
        self.assertEqual(ground, [None])
        self.assertEqual(geoid, [-30.35])

    def test_nodata_is_not_a_height(self):
        holed = write_dem(self.dir / "holed.tif", west=-84.0, north=35.0, size=200,
                          nodata_box=(40, 60, 40, 60))
        with patch.object(terrain_tiles, "_sources_for", return_value=[holed]):
            ground, _ = terrain_tiles.sample_points([at_pixel(50, 50), at_pixel(10, 10)])
        self.assertIsNone(ground[0])
        self.assertIsNotNone(ground[1])

    def test_a_coarser_dem_fills_only_what_the_finer_one_cannot(self):
        coarse = write_dem(self.dir / "coarse.tif", west=-84.5, north=35.5, size=1200,
                           value=999.0)
        coarse = RasterEntry(**{**coarse.__dict__, "resolution_m": 900.0})
        with patch.object(terrain_tiles, "_sources_for", return_value=[self.dem, coarse]):
            ground, _ = terrain_tiles.sample_points([at_pixel(50, 50), (35.4, -84.4)])
        self.assertAlmostEqual(ground[0], plane(50, 50) - 30.35, places=1)
        self.assertAlmostEqual(ground[1], 999.0 - 30.35, places=1)

    def test_results_stay_aligned_with_the_request(self):
        points = [at_pixel(10, 10), (10.0, 10.0), at_pixel(100, 30)]
        ground, geoid = terrain_tiles.sample_points(points)
        self.assertEqual(len(ground), 3)
        self.assertEqual(len(geoid), 3)
        self.assertIsNone(ground[1])
        self.assertAlmostEqual(ground[2], plane(100, 30) - 30.35, places=1)

    def test_no_points_is_no_work(self):
        self.assertEqual(terrain_tiles.sample_points([]), ([], []))

    def test_missing_geoid_grids_leave_ground_and_route_in_one_frame(self):
        """Like the tiles: orthometric throughout, so clearance is still right."""
        broken = MagicMock()
        broken.transform.side_effect = RuntimeError("no grids")
        with patch.object(terrain_tiles, "_geoid_transformer", return_value=broken):
            ground, geoid = terrain_tiles.sample_points([at_pixel(50, 50)])
        self.assertAlmostEqual(ground[0], plane(50, 50), places=1)
        self.assertEqual(geoid, [0.0])

    def test_missing_geoid_grids_fail_when_the_geoid_is_required(self):
        broken = MagicMock()
        broken.transform.side_effect = RuntimeError("no grids")
        with patch.object(terrain_tiles, "_geoid_transformer", return_value=broken), \
                patch.dict(os.environ, {"TERRAIN_REQUIRE_GEOID": "1"}):
            with self.assertRaises(terrain_tiles.TerrainTileError):
                terrain_tiles.sample_points([at_pixel(50, 50)])


class HeightsEndpointTests(unittest.TestCase):
    def setUp(self):
        self.app = Flask(__name__)
        self.app.config.update(TESTING=True,
                               JWT_SECRET_KEY="test-only-secret-over-32-bytes-long")
        JWTManager(self.app)
        self.app.register_blueprint(terrain_bp)
        with self.app.app_context():
            self.auth = {"Authorization": f"Bearer {create_access_token(identity='1')}"}
        self.client = self.app.test_client()
        sampler = patch.object(terrain_tiles, "sample_points",
                               side_effect=lambda pts: ([412.5] * len(pts), [-30.35] * len(pts)))
        self.sample = sampler.start()
        self.addCleanup(sampler.stop)

    def post(self, body, headers=None):
        return self.client.post("/api/terrain/heights", json=body,
                                headers=self.auth if headers is None else headers)

    def test_requires_a_token(self):
        """It describes where someone is planning to fly."""
        self.assertEqual(self.post({"points": []}, headers={}).status_code, 401)

    def test_returns_ground_and_geoid_aligned_with_the_points(self):
        response = self.post({"points": [{"lat": 34.6, "lon": -84.1},
                                         {"lat": 34.7, "lon": -84.2}]})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(),
                         {"groundM": [412.5, 412.5], "geoidM": [-30.35, -30.35]})
        self.sample.assert_called_once_with([(34.6, -84.1), (34.7, -84.2)])

    def test_is_never_cached_publicly(self):
        """A shared cache would hand one user's route to another."""
        response = self.post({"points": [{"lat": 34.6, "lon": -84.1}]})
        cache = response.headers.get("Cache-Control", "")
        self.assertIn("private", cache)
        self.assertIn("no-store", cache)
        self.assertNotIn("public", cache)

    def test_caps_the_number_of_points(self):
        points = [{"lat": 34.6, "lon": -84.1}] * (terrain_tiles.MAX_POINTS + 1)
        self.assertEqual(self.post({"points": points}).status_code, 400)
        self.sample.assert_not_called()

    def test_refuses_malformed_points(self):
        for body in ({}, {"points": "x"}, {"points": [{"lat": 34.6}]},
                     {"points": [{"lat": "north", "lon": -84.1}]},
                     {"points": [{"lat": 95, "lon": -84.1}]},
                     {"points": [{"lat": float("nan"), "lon": -84.1}]}):
            with self.subTest(body=body):
                self.assertEqual(self.post(body).status_code, 400)

    def test_missing_required_geoid_is_a_server_error_not_bad_heights(self):
        self.sample.side_effect = terrain_tiles.TerrainTileError("no grids")
        self.assertEqual(self.post({"points": [{"lat": 34.6, "lon": -84.1}]}).status_code, 503)


if __name__ == "__main__":
    unittest.main()
