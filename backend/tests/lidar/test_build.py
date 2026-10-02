"""Building one landing point: choosing a source, and the build itself.

PDAL and py3dtiles are replaced by a runner that writes the files each would
produce, so this exercises the decisions — which survey, which tiles, what is
recorded — without the toolchain.
"""

import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


from lidar import aoi, catalog, collection, coverage
from lidar.build import (BuildError, BuildRequest, build_for_target,
                         resolve_source)
from tests.lidar.helpers import write_tile

# Ellijay, and the Albers bounds of the 1 km tile under it.
LAT, LON = 34.596407, -84.128098
SURVEY = coverage.Survey(name="GA_Statewide_B3_2018",
                         url="https://example.com/GA_Statewide_B3_2018/ept.json",
                         points=1, year=2018)


def albers_tile(directory, name, x0, y0, size=1000.0):
    return write_tile(directory, name, bounds=(x0, y0, x0 + size, y0 + size),
                      geokeys={3072: (0, 1, 6350)})


def fake_runner(command):
    """Write what PDAL and py3dtiles would."""
    tool = Path(command[0]).name
    if tool == "pdal":
        stages = json.loads(Path(command[2]).read_text())["pipeline"]
        writer = next(s for s in stages if s["type"] == "writers.las")
        Path(writer["filename"]).write_bytes(b"LASF" + b"\0" * 400)
    elif tool == "py3dtiles":
        out = Path(command[command.index("--out") + 1])
        out.mkdir(parents=True, exist_ok=True)
        (out / "tileset.json").write_text('{"asset": {"version": "1.0"}}')
    return ""


class CoverageFractionTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: shutil.rmtree(self.dir, ignore_errors=True))

    def test_a_box_inside_one_tile_is_fully_covered(self):
        albers_tile(self.dir, "a.laz", 0.0, 0.0)
        tiles = collection.scan(self.dir)
        self.assertAlmostEqual(collection.coverage_fraction(tiles, (100, 100, 300, 300)), 1.0)

    def test_a_box_hanging_off_the_edge_is_partly_covered(self):
        """Intersecting is not covering — the case that cuts a cloud in half."""
        albers_tile(self.dir, "a.laz", 0.0, 0.0)
        tiles = collection.scan(self.dir)
        self.assertAlmostEqual(collection.coverage_fraction(tiles, (500, 0, 1500, 1000)), 0.5)

    def test_abutting_tiles_cover_a_box_across_their_seam(self):
        albers_tile(self.dir, "a.laz", 0.0, 0.0)
        albers_tile(self.dir, "b.laz", 1000.0, 0.0)
        tiles = collection.scan(self.dir)
        self.assertAlmostEqual(collection.coverage_fraction(tiles, (500, 0, 1500, 1000)), 1.0)


class SourceTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.dir, ignore_errors=True))
        # Project the target into Albers to place tiles under it.
        from pyproj import Transformer
        x, y = Transformer.from_crs("EPSG:4326", "EPSG:6350",
                                    always_xy=True).transform(LON, LAT)
        self.x0 = (x // 1000) * 1000
        self.y0 = (y // 1000) * 1000
        self.x, self.y = x, y

    def surround(self):
        """Nine tiles around the target, so any LZ-sized box is covered."""
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                albers_tile(self.dir, f"t{dx}{dy}.laz",
                            self.x0 + dx * 1000, self.y0 + dy * 1000)

    def test_a_covering_collection_is_used(self):
        self.surround()
        source = resolve_source(BuildRequest(lat=LAT, lon=LON, collection=self.dir))
        self.assertIsInstance(source.data, list)
        self.assertEqual(source.srs, "EPSG:6350+5703")

    @patch.object(coverage, "best_survey", return_value=SURVEY)
    def test_the_edge_of_a_collection_falls_back_to_aws_when_allowed(self, _best):
        # Only the tile to the east: the target's box hangs off its west edge.
        albers_tile(self.dir, "east.laz", self.x0 + 1000, self.y0)
        request = BuildRequest(lat=LAT, lon=LON, radius_m=1000.0,
                               collection=self.dir, collection_fallback=True)
        source = resolve_source(request)
        self.assertEqual(source.data, SURVEY.url)

    def test_the_edge_of_a_collection_is_used_with_a_warning_when_not(self):
        """An operator who names a collection gets it, and is told it's partial."""
        albers_tile(self.dir, "east.laz", self.x0 + 1000, self.y0)
        request = BuildRequest(lat=LAT, lon=LON, radius_m=1000.0, collection=self.dir)
        source = resolve_source(request)
        self.assertIsInstance(source.data, list)
        self.assertTrue(any("covers only" in note for note in source.notes))

    @patch.object(coverage, "best_survey", return_value=SURVEY)
    def test_a_collection_elsewhere_falls_back_when_allowed(self, _best):
        albers_tile(self.dir, "far.laz", 0.0, 0.0)
        request = BuildRequest(lat=LAT, lon=LON, collection=self.dir,
                               collection_fallback=True)
        self.assertEqual(resolve_source(request).data, SURVEY.url)

    def test_a_collection_elsewhere_is_an_error_when_not(self):
        albers_tile(self.dir, "far.laz", 0.0, 0.0)
        with self.assertRaises(BuildError):
            resolve_source(BuildRequest(lat=LAT, lon=LON, collection=self.dir))

    @patch.object(coverage, "best_survey", side_effect=coverage.CoverageError("none"))
    def test_no_coverage_anywhere_is_a_build_error(self, _best):
        with self.assertRaises(BuildError):
            resolve_source(BuildRequest(lat=LAT, lon=LON))


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.out = Path(tempfile.mkdtemp()) / "out"
        self.addCleanup(lambda: shutil.rmtree(self.out.parent, ignore_errors=True))

    @patch.object(coverage, "best_survey", return_value=SURVEY)
    def test_a_build_leaves_a_tileset_and_its_manifest(self, _best):
        build_for_target(BuildRequest(lat=LAT, lon=LON), self.out, runner=fake_runner)
        self.assertTrue((self.out / "tileset.json").exists())
        self.assertEqual(catalog.read_manifest(self.out), (LAT, LON, aoi.DEFAULT_RADIUS_M))

    @patch.object(coverage, "best_survey", return_value=SURVEY)
    def test_progress_moves_through_the_stages_in_order(self, _best):
        stages = []
        build_for_target(BuildRequest(lat=LAT, lon=LON), self.out,
                         runner=fake_runner, stage=stages.append)
        self.assertEqual(stages, ["locating survey", "processing points",
                                  "building tiles"])

    @patch.object(coverage, "best_survey", return_value=SURVEY)
    def test_a_context_ring_is_built_beside_the_core(self, _best):
        stages = []
        summary = build_for_target(
            BuildRequest(lat=LAT, lon=LON, context_m=1000.0), self.out,
            runner=fake_runner, stage=stages.append)
        self.assertTrue(summary["context"])
        self.assertTrue((self.out / catalog.CONTEXT_DIRNAME / "tileset.json").exists())
        self.assertIn("building context", stages)

    def test_a_context_ring_no_wider_than_the_core_is_refused(self):
        with self.assertRaises(BuildError):
            build_for_target(BuildRequest(lat=LAT, lon=LON, context_m=200.0),
                             self.out, runner=fake_runner)


class IndexDownloadTests(unittest.TestCase):
    def test_the_download_identifies_itself(self):
        """The index host answers urllib's default User-Agent with a 403."""
        seen = {}

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"features": []}'

        def opener(request):
            seen["agent"] = request.get_header("User-agent")
            return Response()

        directory = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(directory, ignore_errors=True))
        path = coverage.fetch_index(directory / "index.geojson", opener=opener)
        self.assertEqual(seen["agent"], coverage.USER_AGENT)
        self.assertTrue(path.exists())
        self.assertFalse((directory / "index.geojson.part").exists())

    def test_a_failed_download_leaves_no_truncated_index(self):
        def opener(_request):
            raise OSError("connection reset")

        directory = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(directory, ignore_errors=True))
        with self.assertRaises(OSError):
            coverage.fetch_index(directory / "index.geojson", opener=opener)
        self.assertFalse((directory / "index.geojson").exists())


if __name__ == "__main__":
    unittest.main()
