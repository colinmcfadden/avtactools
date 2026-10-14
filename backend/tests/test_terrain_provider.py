"""Focused regression tests for metric terrain-slope calculations."""

import math
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

import numpy as np
import rasterio
from rasterio.transform import from_origin

from terrain_provider import (
    UH60_LIMITS_DEG,
    LocalRasterCatalog,
    _directional_summary,
    _horn_gradients,
    _slope_by_heading,
    _steepest_candidates,
    build_slope_analysis,
)


def _ramp(degrees, size=9):
    """A plane rising east at [degrees], on 1 m cells, with its gradients and the cells that have them."""
    dem = np.tile(np.arange(size, dtype=np.float32) * np.float32(np.tan(np.radians(degrees))), (size, 1))
    _, grad_east, grad_north = _horn_gradients(dem, 1.0, 1.0)
    return np.isfinite(grad_east) & np.isfinite(grad_north), grad_east, grad_north


class Uh60LimitTests(unittest.TestCase):
    def test_the_uh60_lands_15_degrees_nose_up_and_6_nose_down(self):
        # The owner's limits (a UH-60 pilot, 2026-10-13): nose up 15°, nose down 6°, cross slope 15°. Nose high is the ground rising
        # ahead, the nose up; the two were once the other way round here.
        self.assertEqual(UH60_LIMITS_DEG, {"noseHigh": 15.0, "noseLow": 6.0, "crossSlope": 15.0})

    def test_eight_degrees_nose_up_is_inside_its_limit_and_eight_nose_down_is_over(self):
        mask, grad_east, grad_north = _ramp(8.0)

        uphill = _directional_summary(mask, grad_east, grad_north, 90.0)
        downhill = _directional_summary(mask, grad_east, grad_north, 270.0)

        self.assertAlmostEqual(uphill["noseHighMaxDeg"], 8.0, places=1)
        self.assertEqual(uphill["noseHighOverLimitPct"], 0.0)
        self.assertAlmostEqual(downhill["noseLowMaxDeg"], 8.0, places=1)
        self.assertEqual(downhill["noseLowOverLimitPct"], 100.0)


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

    @staticmethod
    def _write_ramp(directory):
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

    def test_local_geotiff_is_preferred_when_it_covers_the_requested_area(self):
        with tempfile.TemporaryDirectory() as directory:
            self._write_ramp(directory)
            with patch.dict("os.environ", {"TERRAIN_DATA_DIR": directory}, clear=False):
                grid = LocalRasterCatalog().load_best((34.094, -117.094, 34.098, -117.090))

        self.assertIsNotNone(grid)
        self.assertEqual(grid.source, "local_highres_cog")
        self.assertLess(grid.resolution_m, 15.0)
        self.assertTrue(np.isfinite(grid.elevation_m).all())

    def test_a_new_catalog_scans_on_a_machine_that_booted_moments_ago(self):
        # time.monotonic() counts from boot on Linux: a CI runner or a fresh host is seconds into it, inside the refresh
        # interval, and a catalog that took 0 for "never scanned" served its empty list (502 on every LZ) for five minutes.
        with tempfile.TemporaryDirectory() as directory:
            self._write_ramp(directory)
            with patch.dict("os.environ", {"TERRAIN_DATA_DIR": directory}, clear=False), \
                    patch("terrain_provider.time.monotonic", return_value=5.0):
                grid = LocalRasterCatalog().load_best((34.094, -117.094, 34.098, -117.090))

        self.assertIsNotNone(grid)


class SlopeByHeadingTests(unittest.TestCase):
    """The worst slope for every whole heading, which the clients judge a landing heading and recommend one from."""

    FIELDS = ("noseHighMaxDeg", "noseLowMaxDeg", "crossSlopeMaxDeg")

    @staticmethod
    def _cells(dem, cell_m=1.0):
        _, grad_east, grad_north = _horn_gradients(np.asarray(dem, dtype=np.float32), cell_m, cell_m)
        return np.isfinite(grad_east) & np.isfinite(grad_north), grad_east, grad_north

    def assertAgreesWithTheLandingHeadingSummary(self, mask, grad_east, grad_north, headings=range(360)):
        table = _slope_by_heading(mask, grad_east, grad_north)
        for heading in headings:
            summary = _directional_summary(mask, grad_east, grad_north, float(heading))
            self.assertEqual([table[heading][f] for f in self.FIELDS], [summary[f] for f in self.FIELDS], f"heading {heading}")

    def test_every_whole_heading_once_and_in_order(self):
        table = _slope_by_heading(*_ramp(8.0))

        self.assertEqual([row["heading"] for row in table], list(range(360)))
        self.assertTrue(all(set(row) == {"heading", *self.FIELDS} for row in table))

    def test_a_tilted_plane_splits_into_its_components_at_every_heading(self):
        # 10° rising toward 030°: at heading h the ground ahead rises atan(tan 10° cos(h - 30°)), and across it atan(tan 10° sin(h - 30°)).
        rise, toward = math.tan(math.radians(10.0)), math.radians(30.0)
        rows, cols = np.mgrid[0:12, 0:12]
        dem = rise * (cols * math.sin(toward) - rows * math.cos(toward))          # rows run south
        table = _slope_by_heading(*self._cells(dem))

        for row in table:
            off = math.radians(row["heading"]) - toward
            along = math.degrees(math.atan(rise * math.cos(off)))
            self.assertAlmostEqual(row["noseHighMaxDeg"], max(along, 0.0), delta=0.051, msg=row)
            self.assertAlmostEqual(row["noseLowMaxDeg"], max(-along, 0.0), delta=0.051, msg=row)
            self.assertAlmostEqual(row["crossSlopeMaxDeg"], abs(math.degrees(math.atan(rise * math.sin(off)))), delta=0.051, msg=row)
        self.assertEqual([table[30][f] for f in self.FIELDS], [10.0, 0.0, 0.0])      # facing up the slope
        self.assertEqual([table[210][f] for f in self.FIELDS], [0.0, 10.0, 0.0])     # facing down it
        self.assertEqual(table[120]["crossSlopeMaxDeg"], 10.0)                         # across it

    def test_level_ground_is_level_at_every_heading_with_no_negative_zero(self):
        table = _slope_by_heading(*self._cells(np.zeros((30, 30))))

        values = [row[f] for row in table for f in self.FIELDS]
        self.assertEqual(set(values), {0.0})
        self.assertTrue(all(math.copysign(1.0, v) == 1.0 for v in values))            # JSON would write -0.0

    def test_rough_ground_agrees_with_the_landing_heading_summary_at_every_heading(self):
        # Past a few hundred cells only those on the edge of the gradients' hull are measured; the answer must still be every cell's.
        mask, grad_east, grad_north = self._cells(np.random.default_rng(20261013).normal(0.0, 3.0, (60, 60)))
        self.assertGreater(int(mask.sum()), 256)

        self.assertAgreesWithTheLandingHeadingSummary(mask, grad_east, grad_north)

    def test_a_hill_on_a_ramp_agrees_too(self):
        rows, cols = np.mgrid[0:120, 0:120]
        dem = 300.0 + 0.5 * cols + 6.0 * np.exp(-(((rows - 90) ** 2) + ((cols - 90) ** 2)) / 200.0)

        self.assertAgreesWithTheLandingHeadingSummary(*self._cells(dem, 11.0))

    def test_whole_metre_elevations_whose_gradients_tie_agree_too(self):
        # Rounded elevations give gradients on a lattice: many cells share each value and lie along the hull's edges.
        mask, grad_east, grad_north = self._cells(np.round(np.random.default_rng(7).normal(0.0, 2.0, (50, 50))), 10.0)

        self.assertAgreesWithTheLandingHeadingSummary(mask, grad_east, grad_north)

    def test_a_large_lz_is_measured_over_a_few_cells(self):
        mask, grad_east, grad_north = self._cells(np.cumsum(np.random.default_rng(3).normal(0.0, 0.5, (200, 200)), axis=0))
        east, north = _steepest_candidates(grad_east[mask], grad_north[mask])

        self.assertLess(len(east), 200)                                                # of 39,204
        self.assertAgreesWithTheLandingHeadingSummary(mask, grad_east, grad_north, headings=(0, 45, 133, 180, 270, 359))

    def test_a_plane_keeps_one_cell_for_each_gradient(self):
        # Every gradient on one line: the hull has no inside, so cells with the same gradient (the same numbers) count once.
        mask, grad_east, grad_north = self._cells(np.tile(np.arange(60) * 0.3, (60, 1)))
        east, _ = _steepest_candidates(grad_east[mask], grad_north[mask])

        self.assertLess(len(east), 10)
        self.assertAgreesWithTheLandingHeadingSummary(mask, grad_east, grad_north)

    def test_the_analysis_carries_the_table_and_it_agrees_with_its_landing_heading(self):
        polygon = [[34.098, -117.097], [34.098, -117.093], [34.094, -117.093], [34.094, -117.097]]
        with tempfile.TemporaryDirectory() as directory:
            HornSlopeTests._write_ramp(directory)
            env = {"TERRAIN_DATA_DIR": directory, "TERRAIN_SOURCE": "local"}
            with patch.dict("os.environ", env, clear=False), patch("terrain_provider.LOCAL_CATALOG", LocalRasterCatalog()):
                for heading in (0, 90, 225, 270):
                    analysis = build_slope_analysis(polygon, landing_heading_deg=float(heading))
                    self.assertEqual(len(analysis["byHeading"]), 360)
                    row = analysis["byHeading"][heading]
                    self.assertEqual([row[f] for f in self.FIELDS], [analysis["directional"][f] for f in self.FIELDS], f"heading {heading}")
                self.assertEqual(len(build_slope_analysis(polygon)["byHeading"]), 360)    # with no landing heading too


if __name__ == "__main__":
    unittest.main()
