"""Terrain tiles computed once and served from disk; coarse levels ahead of time.

The first 3D view used to climb through the terrain levels for ~15 s: levels
0-8 each read a little of dozens of DEMs, 0.3-3 s per tile, and every browser
paid that again. These check that a tile is computed once, that the cache can
never serve heights from different DEMs or code, and that warming covers the
DEMs and nothing else.
"""

import os
import shutil
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from app.services.terrain import tiles as terrain_tiles
from app.services.terrain.provider import LocalRasterCatalog, RasterEntry

GRID = np.full((terrain_tiles.TILE_SAMPLES, terrain_tiles.TILE_SAMPLES), 412.0,
               dtype=np.float32)


def dem(path, bounds=(34.0, -85.0, 35.0, -84.0)):
    """A catalog entry; the file itself only needs to exist for its stat."""
    Path(path).write_bytes(b"dem")
    return RasterEntry(path=str(path), kind="geotiff", bounds_latlon=bounds,
                       resolution_m=10.0, vertical_datum="NAVD88")


class CacheHarness(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="terrain-cache-"))
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.cache = self.dir / "cache"
        self.entries = [dem(self.dir / "a.tif")]

        for item in (
            patch.dict(os.environ, {"TERRAIN_TILE_CACHE_DIR": str(self.cache)}),
            patch.object(terrain_tiles.LOCAL_CATALOG, "entries", lambda: self.entries),
            patch.object(terrain_tiles, "geoid_offset", return_value=-30.35),
        ):
            item.start()
            self.addCleanup(item.stop)

        self.sample = patch.object(terrain_tiles, "sample_tile", return_value=GRID).start()
        self.addCleanup(patch.stopall)
        # The fingerprint is memoised per catalog listing; start clean.
        terrain_tiles._fingerprint_memo = (None, None)


class TileCacheTests(CacheHarness):
    def test_a_tile_is_computed_once_and_then_read_from_disk(self):
        first = terrain_tiles.tile_bytes(12, 1100, 830)
        second = terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(first, second)
        self.assertEqual(first, terrain_tiles.encode(GRID))
        self.assertEqual(self.sample.call_count, 1)

    def test_somewhere_without_a_dem_is_not_cached(self):
        """Cesium asks it of tiles across the whole globe; it costs nothing."""
        self.sample.return_value = None
        self.assertIsNone(terrain_tiles.tile_bytes(3, 0, 0))
        self.assertIsNone(terrain_tiles.tile_bytes(3, 0, 0))
        self.assertEqual(self.sample.call_count, 2)
        self.assertFalse(any(self.cache.rglob("*.i16")))

    def test_mounting_another_dem_starts_a_new_generation(self):
        terrain_tiles.tile_bytes(12, 1100, 830)
        self.entries = self.entries + [dem(self.dir / "b.tif", (33.0, -85.0, 34.0, -84.0))]
        terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(self.sample.call_count, 2)

    def test_a_dem_replaced_in_place_starts_a_new_generation(self):
        terrain_tiles.tile_bytes(12, 1100, 830)
        Path(self.entries[0].path).write_bytes(b"a newer survey")
        self.entries = list(self.entries)   # a fresh catalog listing
        terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(self.sample.call_count, 2)

    def test_installing_the_geoid_grids_starts_a_new_generation(self):
        """Tiles cached without the grids are 30 m out; they must not survive."""
        with patch.object(terrain_tiles, "geoid_offset", return_value=0.0):
            terrain_tiles.tile_bytes(12, 1100, 830)
        self.entries = list(self.entries)
        terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(self.sample.call_count, 2)

    def test_the_cache_can_be_turned_off(self):
        with patch.dict(os.environ, {"TERRAIN_TILE_CACHE_DIR": "none"}):
            terrain_tiles.tile_bytes(12, 1100, 830)
            terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(self.sample.call_count, 2)
        self.assertFalse(self.cache.exists())

    def test_an_unwritable_cache_still_serves_the_tile(self):
        with patch.object(terrain_tiles, "_write_atomic",
                          side_effect=lambda *_a: None):
            data = terrain_tiles.tile_bytes(12, 1100, 830)
        self.assertEqual(data, terrain_tiles.encode(GRID))

    def test_no_partial_file_is_ever_visible(self):
        """Written beside and moved into place, so a reader sees all or nothing."""
        terrain_tiles.tile_bytes(12, 1100, 830)
        names = [p.name for p in self.cache.rglob("*") if p.is_file()]
        self.assertEqual(names, ["830.i16"])


class WarmingTests(CacheHarness):
    def test_covering_tiles_touch_the_dems_and_nothing_else(self):
        # One 1x1 degree DEM at 34-35N, 84-85W.
        self.assertEqual(terrain_tiles.tiles_covering(self.entries, 0), [(0, 0)])
        tiles = terrain_tiles.tiles_covering(self.entries, 8)
        size = 180.0 / (1 << 8)
        for x, y in tiles:
            west = -180.0 + x * size
            north = 90.0 - y * size
            self.assertLess(west, -84.0)
            self.assertGreater(west + size, -85.0)
            self.assertGreater(north, 34.0)
            self.assertLess(north - size, 35.0)
        self.assertLessEqual(len(tiles), 4)

    def test_warming_computes_the_coarse_levels_once(self):
        made = terrain_tiles.warm(4, log=lambda _m: None)
        self.assertGreater(made, 0)
        self.assertEqual(self.sample.call_count, made)
        self.assertEqual(terrain_tiles.warm(4, log=lambda _m: None), 0)
        self.assertEqual(self.sample.call_count, made)

    def test_warmed_tiles_are_what_a_request_then_reads(self):
        terrain_tiles.warm(2, log=lambda _m: None)
        calls = self.sample.call_count
        x, y = terrain_tiles.tiles_covering(self.entries, 2)[0]
        terrain_tiles.tile_bytes(2, x, y)
        self.assertEqual(self.sample.call_count, calls)

    def test_warming_needs_dems_and_a_cache(self):
        with patch.dict(os.environ, {"TERRAIN_WARM_LEVEL": "9"}, clear=False):
            os.environ.pop("TERRAIN_DATA_DIR", None)
            self.assertIsNone(terrain_tiles.start_warming())
        with patch.dict(os.environ, {"TERRAIN_DATA_DIR": str(self.dir),
                                     "TERRAIN_WARM_LEVEL": "-1"}):
            self.assertIsNone(terrain_tiles.start_warming())


class RacingWriterTests(CacheHarness):
    """Two writers for one tile: request threads, the warm-up, a second process.

    On Windows the loser cannot replace a file someone has open, but the
    winner wrote the same bytes, so the tile is cached all the same.
    """

    def setUp(self):
        super().setUp()
        terrain_tiles._write_failure_logged = False
        self.logged = []
        log = patch.object(terrain_tiles, "_log", side_effect=self.logged.append)
        log.start()
        self.addCleanup(log.stop)

    def test_losing_the_race_is_not_a_failure(self):
        path = terrain_tiles._cache_path(12, 1100, 830)
        path.parent.mkdir(parents=True)
        path.write_bytes(terrain_tiles.encode(GRID))     # the winner's copy
        with patch.object(terrain_tiles.os, "replace", side_effect=PermissionError):
            terrain_tiles._write_atomic(path, terrain_tiles.encode(GRID))
        self.assertEqual(self.logged, [])
        self.assertEqual([p.name for p in path.parent.iterdir()], ["830.i16"])

    def test_an_unwritable_cache_is_reported_once_not_per_tile(self):
        with patch.object(terrain_tiles.os, "replace", side_effect=PermissionError):
            for y in range(830, 835):
                self.assertEqual(terrain_tiles.tile_bytes(12, 1100, y),
                                 terrain_tiles.encode(GRID))
        self.assertEqual(len(self.logged), 1)
        self.assertFalse(any(self.cache.rglob("*.tmp")))

    def test_warming_clears_abandoned_writes_but_not_ones_in_progress(self):
        import time
        folder = self.cache / "leftovers"
        folder.mkdir(parents=True)
        old = folder / "1.i16.111.222.tmp"
        fresh = folder / "2.i16.111.333.tmp"
        old.write_bytes(b"x")
        fresh.write_bytes(b"x")
        stale = time.time() - terrain_tiles.ABANDONED_WRITE_AGE_S - 5
        os.utime(old, (stale, stale))
        terrain_tiles.warm(0, log=lambda _m: None)
        self.assertFalse(old.exists())
        self.assertTrue(fresh.exists())


class GeoidCheckTests(unittest.TestCase):
    """PROJ without grids reports success and returns the height unchanged."""

    def test_a_real_separation_means_the_grids_are_there(self):
        with patch.object(terrain_tiles, "geoid_offset", return_value=-30.35):
            self.assertTrue(terrain_tiles.geoid_grids_available())

    def test_a_pass_through_means_they_are_not(self):
        with patch.object(terrain_tiles, "geoid_offset", return_value=0.0):
            self.assertFalse(terrain_tiles.geoid_grids_available())

    def test_an_error_means_they_are_not(self):
        with patch.object(terrain_tiles, "geoid_offset", side_effect=RuntimeError):
            self.assertFalse(terrain_tiles.geoid_grids_available())


class AnalysisFirstTests(CacheHarness):
    """Warming waits while an analysis runs; they would only slow each other."""

    def test_warming_holds_until_the_analysis_finishes(self):
        finished = threading.Event()
        with terrain_tiles.analysis_running():
            worker = threading.Thread(
                target=lambda: (terrain_tiles.warm(2, log=lambda _m: None), finished.set()))
            worker.start()
            self.assertFalse(finished.wait(0.3))
            self.assertEqual(self.sample.call_count, 0)
        self.assertTrue(finished.wait(5))
        self.assertGreater(self.sample.call_count, 0)

    def test_overlapping_analyses_hold_it_until_the_last_ends(self):
        first = terrain_tiles.analysis_running()
        second = terrain_tiles.analysis_running()
        first.__enter__()
        second.__enter__()
        first.__exit__(None, None, None)
        self.assertFalse(terrain_tiles._no_analysis.is_set())
        second.__exit__(None, None, None)
        self.assertTrue(terrain_tiles._no_analysis.is_set())

    def test_a_failing_analysis_still_lets_warming_go(self):
        @terrain_tiles.pauses_warming
        def broken():
            raise RuntimeError("SAM fell over")

        with self.assertRaises(RuntimeError):
            broken()
        self.assertTrue(terrain_tiles._no_analysis.is_set())


class CatalogRefreshTests(unittest.TestCase):
    def test_concurrent_requests_rescan_the_dems_once(self):
        """With threads, every request arriving at a stale catalog would rescan."""
        catalog = LocalRasterCatalog()
        scans = []
        gate = threading.Event()

        def slow_refresh():
            scans.append(1)
            gate.wait(1)
            catalog._refreshed_at = float("inf")
            return []

        with patch.object(catalog, "_refresh", side_effect=slow_refresh):
            threads = [threading.Thread(target=catalog.entries) for _ in range(6)]
            for thread in threads:
                thread.start()
            gate.set()
            for thread in threads:
                thread.join(5)
        self.assertEqual(len(scans), 1)


if __name__ == "__main__":
    unittest.main()
