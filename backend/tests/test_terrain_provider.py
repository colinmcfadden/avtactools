"""Focused regression tests for metric terrain-slope calculations."""

import math
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

import numpy as np
import rasterio
from rasterio.transform import from_origin

from rasterio.crs import CRS
from rasterio.warp import transform as warp

from terrain_provider import (
    FEET_PER_METRE,
    UH60_LIMITS_DEG,
    LocalRasterCatalog,
    TerrainGrid,
    _contour_interval_ft,
    _polygon_mask,
    _slope_contours,
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



class ContourTests(unittest.TestCase):
    """The contour lines of the ground in and round an LZ (`contours` of the analysis)."""

    UTM = CRS.from_epsg(32611)
    ORIGIN = (500000.0, 3770000.0)    # the top-left corner of the grid, in metres

    def _grid(self, elevation_m):
        return TerrainGrid(
            elevation_m=np.asarray(elevation_m, dtype=np.float32), transform=from_origin(*self.ORIGIN, 1.0, 1.0), crs=self.UTM,
            bounds_latlon=(0, 0, 0, 0), source="test", resolution_m=1.0, xres_m=1.0, yres_m=1.0, vertical_datum="test",
        )

    def _latlon(self, xs, ys):
        lons, lats = warp(self.UTM, "EPSG:4326", list(xs), list(ys))
        return [[lat, lon] for lat, lon in zip(lats, lons)]

    def _metres(self, points):
        xs, ys = warp("EPSG:4326", self.UTM, [p[1] for p in points], [p[0] for p in points])
        return np.asarray(xs) - self.ORIGIN[0], self.ORIGIN[1] - np.asarray(ys)    # metres east of the corner, and south of it

    def _square(self, first=60, last=140):
        """The LZ: a square of cells [first, last] each way, as [lat, lon] corners."""
        x0, x1 = self.ORIGIN[0] + first, self.ORIGIN[0] + last
        y0, y1 = self.ORIGIN[1] - first, self.ORIGIN[1] - last
        return self._latlon([x0, x1, x1, x0], [y0, y0, y1, y1])

    def _contours(self, elevation_m, polygon=None):
        grid = self._grid(elevation_m)
        polygon = polygon or self._square()
        return _slope_contours(grid, polygon, _polygon_mask(grid, polygon))

    # A plane rising east, 5 cm a metre (about 2.9°).
    RAMP = np.tile(np.arange(200, dtype=np.float64) * 0.05, (200, 1))

    def test_the_interval_is_the_finest_that_keeps_about_a_dozen_lines(self):
        self.assertEqual(_contour_interval_ft(0.0), 1)
        self.assertEqual(_contour_interval_ft(12.0), 1)
        self.assertEqual(_contour_interval_ft(12.1), 2)
        self.assertEqual(_contour_interval_ft(24.0), 2)
        self.assertEqual(_contour_interval_ft(60.0), 5)
        self.assertEqual(_contour_interval_ft(1300.0), 100)
        self.assertEqual(_contour_interval_ft(5000.0), 100)

    def test_a_plane_s_contours_run_across_its_slope_at_their_own_height(self):
        contours = self._contours(self.RAMP)
        # The LZ and its 40 m ring span about 160 m of the ramp: 8 m, 26 ft, so 5 ft lines.
        self.assertEqual(contours["intervalFt"], 5)
        levels = sorted({line["elevationFt"] for line in contours["lines"]})
        self.assertGreaterEqual(len(levels), 4)
        self.assertTrue(all(level % 5 == 0 for level in levels))
        for line in contours["lines"]:
            east, south = self._metres(line["points"])
            # On the plane, height is 0.05 m for each metre east of the first cell's centre: every point is at its line's height.
            heights_ft = (east - 0.5) * 0.05 * FEET_PER_METRE
            np.testing.assert_allclose(heights_ft, line["elevationFt"], atol=0.05)
            self.assertGreater(np.ptp(south), 1.0)                 # and the line runs north and south, across the slope

    def test_lines_are_split_at_the_boundary_and_stop_a_ring_beyond_it(self):
        contours = self._contours(self.RAMP)
        inside = [line for line in contours["lines"] if line["inside"]]
        outside = [line for line in contours["lines"] if not line["inside"]]
        self.assertTrue(inside and outside)
        for line in inside:
            east, south = self._metres(line["points"])
            # The pieces share the vertex where they join, which can be up to a cell outside.
            self.assertTrue(np.all((east > 59) & (east < 141) & (south > 59) & (south < 141)), line["elevationFt"])
        for line in contours["lines"]:
            east, south = self._metres(line["points"])
            self.assertTrue(np.all((east > 18) & (east < 182) & (south > 18) & (south < 182)), "no further than the ring")
        # Each elevation inside the LZ is one line across it, outside it runs on north and south in the ring.
        self.assertEqual(sorted(line["elevationFt"] for line in inside), sorted({line["elevationFt"] for line in inside}))

    def test_labels_keep_apart_on_their_own_lines_every_major_first(self):
        # An LZ 80 m wide and 160 m from north to south on the ramp lifted 2.5 m: the 20, 25 and 30 ft lines cross it, 30 m apart, the
        # major 25 ft line between the others. It takes the middle; each of the others then fits only a fifth of the way along.
        x0, x1 = self.ORIGIN[0] + 60, self.ORIGIN[0] + 140
        y0, y1 = self.ORIGIN[1] - 20, self.ORIGIN[1] - 180
        contours = self._contours(self.RAMP + 2.5, self._latlon([x0, x1, x1, x0], [y0, y0, y1, y1]))
        labelled = [line for line in contours["lines"] if line["label"] is not None]
        heights = [line["elevationFt"] for line in labelled]
        self.assertEqual(len(heights), len(set(heights)), "one label a height")
        majors = {line["elevationFt"] for line in contours["lines"] if line["inside"] and line["major"]}
        self.assertTrue(majors)
        self.assertTrue(majors <= set(heights), "every major height inside is labelled")
        self.assertEqual(sorted(heights), [20, 25, 30], "and the minor ones where there is room")
        spots = []
        for line in labelled:
            self.assertTrue(line["inside"])
            east, south = self._metres([line["label"]])
            self.assertAlmostEqual(float(((east[0] - 0.5) * 0.05 + 2.5) * FEET_PER_METRE), line["elevationFt"], delta=0.05)   # on its line
            self.assertTrue(19 < south[0] < 181)                                                                     # inside the LZ
            spots.append((east[0], south[0]))
        for i, (x0, y0) in enumerate(spots):
            for x1, y1 in spots[i + 1:]:
                self.assertGreaterEqual(math.hypot(x1 - x0, y1 - y0), 39.9)
        # A major line, placed first, takes the middle of its line.
        major = next(line for line in labelled if line["major"])
        self.assertAlmostEqual(float(self._metres([major["label"]])[1][0]), 100.0, delta=2.0)

    def test_a_dem_s_noise_draws_no_specks_and_no_wiggles(self):
        # A centimetre or two of noise on the ramp, as a real DEM has: still one straight line across the LZ for each height, and no rings.
        noisy = self.RAMP + np.random.default_rng(7).normal(0.0, 0.02, self.RAMP.shape)
        contours = self._contours(noisy)
        inside = [line for line in contours["lines"] if line["inside"]]
        self.assertEqual(sorted(line["elevationFt"] for line in inside), sorted({line["elevationFt"] for line in inside}))
        self.assertFalse([line for line in contours["lines"] if line["points"][0] == line["points"][-1]])
        self.assertLess(max(len(line["points"]) for line in inside), 12)                   # a straight line needs few points

    def test_a_ring_a_few_metres_round_is_a_speck_and_left_out(self):
        rows, cols = np.mgrid[0:200, 0:200]
        mound = 300.0 + 1.0 * np.exp(-(((cols - 100) ** 2 + (rows - 100) ** 2) / (2 * 2.5 ** 2)))    # a metre high, a few metres across
        rings = [line for line in self._contours(mound)["lines"] if line["points"][0] == line["points"][-1]]
        self.assertTrue(rings, "the mound's lower contour is drawn")
        for ring in rings:
            east, south = self._metres(ring["points"])
            self.assertGreaterEqual(float(np.hypot(np.diff(east), np.diff(south)).sum()), 11.0, ring["elevationFt"])  # 12 cells, less the simplifying

    def test_the_label_goes_on_the_longest_piece_of_its_height(self):
        rows, cols = np.mgrid[0:200, 0:200]
        def hill(c, r, sigma):
            return 6.0 * np.exp(-(((cols - c) ** 2 + (rows - r) ** 2) / (2 * sigma ** 2)))
        two = 300.0 + np.maximum(hill(80, 80, 12.0), hill(122, 122, 5.0))         # a broad hill and a small one, as high
        for line in self._contours(two)["lines"]:
            if line["label"] is None:
                continue
            east, south = self._metres([line["label"]])
            to_broad = math.hypot(east[0] - 80.5, south[0] - 80.5)
            to_small = math.hypot(east[0] - 122.5, south[0] - 122.5)
            self.assertLess(to_broad, to_small, line["elevationFt"])                  # the broad hill's ring is the longer at every height

    def test_every_fifth_line_is_major(self):
        steep = np.tile(np.arange(200, dtype=np.float64) * 0.5, (200, 1))      # about 80 m over the zone: 25 ft lines, majors at 125 ft
        contours = self._contours(steep)
        self.assertEqual(contours["intervalFt"], 25)
        for line in contours["lines"]:
            self.assertEqual(line["major"], line["elevationFt"] % 125 == 0, line["elevationFt"])
        self.assertTrue(any(line["major"] for line in contours["lines"]))

    def test_level_ground_has_no_lines(self):
        contours = self._contours(np.full((200, 200), 312.4))
        self.assertEqual(contours, {"intervalFt": 1, "lines": []})

    def test_a_hill_in_the_lz_closes_on_itself_inside_it(self):
        rows, cols = np.mgrid[0:200, 0:200]
        hill = 300.0 + 6.0 * np.exp(-(((cols - 100) ** 2 + (rows - 100) ** 2) / (2 * 15.0 ** 2)))
        contours = self._contours(hill)
        rings = [line for line in contours["lines"] if line["inside"] and line["points"][0] == line["points"][-1]]
        self.assertTrue(rings, "a hilltop's contours are closed rings")
        self.assertTrue(all(line["inside"] for line in contours["lines"] if line["elevationFt"] > 985))

    def test_the_analysis_carries_its_contours(self):
        polygon = [[34.098, -117.097], [34.098, -117.093], [34.094, -117.093], [34.094, -117.097]]
        with tempfile.TemporaryDirectory() as directory:
            HornSlopeTests._write_ramp(directory)
            env = {"TERRAIN_DATA_DIR": directory, "TERRAIN_SOURCE": "local"}
            with patch.dict("os.environ", env, clear=False), patch("terrain_provider.LOCAL_CATALOG", LocalRasterCatalog()):
                contours = build_slope_analysis(polygon)["contours"]
        self.assertIn(contours["intervalFt"], (1, 2, 5, 10, 20, 25, 50, 100))
        self.assertTrue(contours["lines"])
        self.assertTrue(any(line["label"] for line in contours["lines"]))


if __name__ == "__main__":
    unittest.main()
