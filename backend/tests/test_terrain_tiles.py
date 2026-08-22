"""Terrain heights served to the 3D view.

The tiling arithmetic has to agree exactly with Cesium's GeographicTilingScheme
— an off-by-one in the tile bounds shifts the whole surface sideways, which
looks like bad terrain rather than a bug in a coordinate formula.
"""

import struct
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import terrain_tiles  # noqa: E402


class TileBoundsTests(unittest.TestCase):
    def test_level_zero_is_two_tiles_covering_the_world(self):
        west = terrain_tiles.tile_bounds(0, 0, 0)
        east = terrain_tiles.tile_bounds(0, 1, 0)
        self.assertEqual(west, (-180.0, -90.0, 0.0, 90.0))
        self.assertEqual(east, (0.0, -90.0, 180.0, 90.0))

    def test_tiles_quarter_at_each_level(self):
        _w, _s, e, n = terrain_tiles.tile_bounds(1, 0, 0)
        self.assertEqual((e, n), (-90.0, 90.0))

    def test_neighbouring_tiles_share_an_edge_without_gap_or_overlap(self):
        left = terrain_tiles.tile_bounds(5, 10, 7)
        right = terrain_tiles.tile_bounds(5, 11, 7)
        self.assertEqual(left[2], right[0])
        below = terrain_tiles.tile_bounds(5, 10, 8)
        self.assertEqual(left[1], below[3])

    def test_a_known_target_lands_in_the_tile_that_contains_it(self):
        lat, lon = 34.64815, -83.86130
        level = 13
        x = int((lon + 180.0) / (360.0 / (terrain_tiles.ROOT_TILES_X << level)))
        y = int((90.0 - lat) / (180.0 / (terrain_tiles.ROOT_TILES_Y << level)))
        west, south, east, north = terrain_tiles.tile_bounds(level, x, y)
        self.assertTrue(west <= lon <= east)
        self.assertTrue(south <= lat <= north)

    def test_a_tile_outside_the_world_is_refused(self):
        with self.assertRaises(terrain_tiles.TerrainTileError):
            terrain_tiles.tile_bounds(1, 4, 0)

    def test_a_negative_coordinate_is_refused(self):
        with self.assertRaises(terrain_tiles.TerrainTileError):
            terrain_tiles.tile_bounds(3, -1, 0)


class EncodingTests(unittest.TestCase):
    def test_heights_encode_as_little_endian_int16_metres(self):
        grid = np.array([[0.0, 1.4], [1.6, -2.5]], dtype=np.float32)
        raw = terrain_tiles.encode(grid)
        self.assertEqual(len(raw), 8)
        self.assertEqual(struct.unpack("<4h", raw), (0, 1, 2, -2))

    def test_a_full_tile_is_the_size_cesium_expects(self):
        samples = terrain_tiles.TILE_SAMPLES
        raw = terrain_tiles.encode(np.zeros((samples, samples), dtype=np.float32))
        self.assertEqual(len(raw), samples * samples * 2)

    def test_the_grid_is_odd_sized_so_tiles_share_edge_samples(self):
        """An even grid leaves a visible seam between neighbouring tiles."""
        self.assertEqual(terrain_tiles.TILE_SAMPLES % 2, 1)

    def test_extreme_values_clamp_rather_than_wrap(self):
        """A wrapped Int16 would put a mountain below sea level."""
        grid = np.array([[40000.0, -40000.0]], dtype=np.float32)
        low, high = struct.unpack("<2h", terrain_tiles.encode(grid))
        self.assertEqual(low, 32767)
        self.assertEqual(high, -32768)


class GapFillingTests(unittest.TestCase):
    """Cells with no DEM must not be invented as sea level.

    A coarse tile spans hundreds of kilometres while the DEMs cover a few
    states, so most of its cells have no data. Filling those with zero cut a
    trough over 1200 m deep through the middle distance; Cesium cannot skirt a
    seam that size, so the globe tore open and the sky showed through it.
    """

    def test_a_gap_takes_the_surrounding_elevation_not_sea_level(self):
        grid = np.full((9, 9), 600.0, dtype=np.float32)
        grid[3:6, 3:6] = np.nan
        filled = terrain_tiles._close_gaps(grid)
        self.assertFalse(np.isnan(filled).any())
        self.assertAlmostEqual(float(filled[4, 4]), 600.0, places=3)

    def test_no_cell_is_left_near_sea_level_beside_high_ground(self):
        """The specific failure: a 600 m plateau with a 0 m hole in it."""
        grid = np.full((9, 9), 600.0, dtype=np.float32)
        grid[2:7, 2:7] = np.nan
        filled = terrain_tiles._close_gaps(grid)
        self.assertGreater(float(filled.min()), 500.0)

    def test_mostly_empty_tiles_are_still_closed(self):
        """Coarse tiles are the ones that were worst: a sliver of coverage."""
        grid = np.full((17, 17), np.nan, dtype=np.float32)
        grid[0, 0] = 450.0
        filled = terrain_tiles._close_gaps(grid)
        self.assertFalse(np.isnan(filled).any())
        self.assertGreater(float(filled.min()), 400.0)

    def test_a_fully_covered_tile_is_untouched(self):
        """Near the landing point nothing should change at all."""
        grid = np.arange(81, dtype=np.float32).reshape(9, 9) + 400.0
        filled = terrain_tiles._close_gaps(grid)
        np.testing.assert_array_equal(filled, grid)

    def test_real_relief_survives_the_fill(self):
        """Filling must not flatten the terrain it is patching around."""
        grid = np.tile(np.linspace(400.0, 900.0, 9, dtype=np.float32), (9, 1))
        grid[4, 4] = np.nan
        filled = terrain_tiles._close_gaps(grid)
        self.assertAlmostEqual(float(filled.max() - filled.min()), 500.0, places=2)


class LevelBoundTests(unittest.TestCase):
    """The level cap must not withhold elevation Cesium is actually asking for.

    Refusing a tile does not stop Cesium subdividing; it leaves that tile at
    ellipsoid height. With the cap at 14, zooming in far enough dropped the
    surface some 600 m below the real ground in north Georgia and tore a hole
    in the globe under the camera.
    """

    def test_the_bound_is_past_anything_cesium_will_ask_for(self):
        # An LZ view reaches level 18-20; a tile there is tens of metres.
        self.assertGreaterEqual(terrain_tiles.MAX_LEVEL, 20)

    def test_a_tile_far_finer_than_the_source_still_has_bounds(self):
        west, south, east, north = terrain_tiles.tile_bounds(20, 262143, 393216)
        self.assertLess(east - west, 0.001)
        self.assertGreater(east, west)
        self.assertGreater(north, south)


class SamplingTests(unittest.TestCase):
    def test_a_tile_with_no_local_dem_is_none_rather_than_an_error(self):
        """Cesium asks across the whole globe; the DEMs cover part of one country."""
        with patch.object(terrain_tiles, "_sources_for", return_value=[]):
            self.assertIsNone(terrain_tiles.sample_tile(13, 4375, 2519))

    def test_the_geoid_offset_matches_the_lidar_pipeline(self):
        """Terrain and points must land in one frame or they will not line up.

        Both add the same separation for the same reason; a mismatch would
        float the point cloud above or sink it into the surface.
        """
        from lidar import crs

        lon, lat = -83.86130, 34.64815
        try:
            offset = terrain_tiles.geoid_offset(lon, lat)
            separation = crs.geoid_separation(
                crs.compound_crs("EPSG:26917"), 237749.995, 3837749.995, 330.0)
        except Exception as error:  # noqa: BLE001
            self.skipTest(f"geoid grids unavailable: {error}")

        # Without the grids PROJ returns the height unchanged and reports
        # success, so a zero separation means the environment lacks them
        # rather than that the code is wrong. Production catches this case
        # itself — see crs.assert_vertical_datum_applied — so here it is an
        # environment skip, run with PROJ_NETWORK=ON to exercise it.
        if offset == 0.0 and separation == 0.0:
            self.skipTest("geoid grids unavailable (PROJ returned a "
                          "pass-through); set PROJ_NETWORK=ON")

        self.assertAlmostEqual(offset, separation, places=1)
        # And it is a real correction, not a silent pass-through.
        self.assertGreater(abs(offset), 1.0)


if __name__ == "__main__":
    unittest.main()
