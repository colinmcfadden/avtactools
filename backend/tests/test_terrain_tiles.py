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
        self.assertAlmostEqual(offset, separation, places=1)
        # And it is a real correction, not a silent pass-through.
        self.assertGreater(abs(offset), 1.0)


if __name__ == "__main__":
    unittest.main()
