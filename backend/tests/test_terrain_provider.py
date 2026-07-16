"""Focused regression tests for metric terrain-slope calculations."""

import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

import numpy as np
import rasterio
from rasterio.transform import from_origin

from terrain_provider import LocalRasterCatalog, _directional_summary, _horn_gradients


class HornSlopeTests(unittest.TestCase):
    def test_east_rising_plane_has_45_degree_slope(self):
        # One metre of rise for every one metre east gives a 45 degree plane.
        dem = np.tile(np.arange(9, dtype=np.float32), (9, 1))
        slope, grad_east, grad_north = _horn_gradients(dem, 1.0, 1.0)

        np.testing.assert_allclose(slope[1:-1, 1:-1], 45.0, atol=0.01)
        np.testing.assert_allclose(grad_east[1:-1, 1:-1], 1.0, atol=0.001)
        np.testing.assert_allclose(grad_north[1:-1, 1:-1], 0.0, atol=0.001)

    def test_heading_splits_nose_high_low_and_cross_slope(self):
        dem = np.tile(np.arange(9, dtype=np.float32), (9, 1))
        _, grad_east, grad_north = _horn_gradients(dem, 1.0, 1.0)
        mask = np.isfinite(grad_east) & np.isfinite(grad_north)

        east = _directional_summary(mask, grad_east, grad_north, 90.0)
        west = _directional_summary(mask, grad_east, grad_north, 270.0)
        north = _directional_summary(mask, grad_east, grad_north, 0.0)

        self.assertAlmostEqual(east["noseHighMaxDeg"], 45.0, places=1)
        self.assertAlmostEqual(east["crossSlopeMaxDeg"], 0.0, places=1)
        self.assertAlmostEqual(west["noseLowMaxDeg"], 45.0, places=1)
        self.assertAlmostEqual(north["crossSlopeMaxDeg"], 45.0, places=1)

    def test_local_geotiff_is_preferred_when_it_covers_the_requested_area(self):
        with tempfile.TemporaryDirectory() as directory:
            raster_path = Path(directory) / "test-lz.tif"
            # ~11 m cells at this latitude; the terrain rises one metre per cell east.
            elevation = np.tile(np.arange(120, dtype=np.float32), (120, 1))
            with rasterio.open(
                raster_path,
                "w",
                driver="GTiff",
                height=elevation.shape[0],
                width=elevation.shape[1],
                count=1,
                dtype=elevation.dtype,
                crs="EPSG:4326",
                transform=from_origin(-117.10, 34.10, 0.0001, 0.0001),
                nodata=-9999,
            ) as dataset:
                dataset.write(elevation, 1)

            with patch.dict("os.environ", {"TERRAIN_DATA_DIR": directory}, clear=False):
                grid = LocalRasterCatalog().load_best((34.094, -117.094, 34.098, -117.090))

        self.assertIsNotNone(grid)
        self.assertEqual(grid.source, "local_highres_cog")
        self.assertLess(grid.resolution_m, 15.0)
        self.assertTrue(np.isfinite(grid.elevation_m).all())


if __name__ == "__main__":
    unittest.main()
