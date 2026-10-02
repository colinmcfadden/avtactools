"""Indexing a directory of downloaded 3DEP tiles.

The header parser is exercised against synthesised LAS headers rather than a
real 18 MB tile, so the byte offsets are pinned without carrying a fixture
around. The offsets themselves were validated against a real USGS LAZ file
(ARRA_GA_LAKELANIER_2010_000021), which reported 1500x1500 units and 4,295,344
points — the values this module's constants are built to read.
"""

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lidar import collection  # noqa: E402


def las_header(*, bounds, points=1000, version=(1, 2), geokeys=None,
               ascii_params=b"", wkt=None):
    """A LAS public header block, optionally followed by CRS VLRs."""
    minx, miny, maxx, maxy = bounds
    header_size = 227 if version < (1, 4) else 375

    head = bytearray(header_size)
    head[0:4] = b"LASF"
    head[24] = version[0]
    head[25] = version[1]
    struct.pack_into("<H", head, 94, header_size)
    struct.pack_into("<I", head, 107, points if version < (1, 4) else 0)
    # maxx, minx, maxy, miny, maxz, minz
    struct.pack_into("<6d", head, 179, maxx, minx, maxy, miny, 400.0, 300.0)
    if version >= (1, 4):
        struct.pack_into("<Q", head, 247, points)

    vlrs = bytearray()
    count = 0

    def add_vlr(record_id, payload):
        nonlocal count
        record = bytearray(54)
        record[2:18] = b"LASF_Projection".ljust(16, b"\x00")
        struct.pack_into("<H", record, 18, record_id)
        struct.pack_into("<H", record, 20, len(payload))
        vlrs.extend(record)
        vlrs.extend(payload)
        count += 1

    if geokeys:
        body = bytearray(struct.pack("<4H", 1, 1, 0, len(geokeys)))
        for key_id, (location, size, value) in sorted(geokeys.items()):
            body.extend(struct.pack("<4H", key_id, location, size, value))
        add_vlr(34735, bytes(body))
    if ascii_params:
        add_vlr(34737, ascii_params)
    if wkt is not None:
        add_vlr(2112, wkt.encode("latin-1") + b"\x00")

    struct.pack_into("<I", head, 100, count)
    return bytes(head) + bytes(vlrs)


def write_tile(directory, name, **kwargs):
    path = Path(directory) / name
    path.write_bytes(las_header(**kwargs))
    return path


class HeaderTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))

    def test_bounds_and_count_come_from_the_header_alone(self):
        path = write_tile(self.dir, "a.laz",
                          bounds=(237000.0, 3837000.0, 238500.0, 3838500.0),
                          points=4295344)
        tile = collection.read_header(path)
        self.assertEqual(tile.bounds, (237000.0, 3837000.0, 238500.0, 3838500.0))
        self.assertEqual(tile.points, 4295344)

    def test_a_1_4_file_reports_its_wide_point_count(self):
        """1.4 zeroes the legacy field, so reading only that reports no points."""
        path = write_tile(self.dir, "b.laz", bounds=(0.0, 0.0, 100.0, 100.0),
                          points=5_000_000_000, version=(1, 4))
        self.assertEqual(collection.read_header(path).points, 5_000_000_000)

    def test_a_non_las_file_is_refused(self):
        path = Path(self.dir) / "notes.txt"
        path.write_bytes(b"this is not a point cloud" * 20)
        with self.assertRaises(collection.CollectionError):
            collection.read_header(path)


class CrsTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))

    def test_the_projected_epsg_is_read_from_the_geokeys(self):
        path = write_tile(self.dir, "utm.laz", bounds=(0.0, 0.0, 1.0, 1.0),
                          geokeys={3072: (0, 1, 26917)})
        self.assertEqual(collection.read_crs(path).horizontal, "EPSG:26917")

    def test_a_vertical_datum_named_only_in_prose_does_not_count_as_declared(self):
        """USGS tiles commonly state the datum in prose and set no vertical key.

        PDAL infers NAVD88 from the surrounding keys on real files of this
        shape, so this is a belt-and-braces measure rather than a live fix; the
        point is that the pipeline never depends on that inference succeeding.
        """
        path = write_tile(
            self.dir, "prose.laz", bounds=(0.0, 0.0, 1.0, 1.0),
            geokeys={3072: (0, 1, 26917), 4097: (34737, 25, 0)},
            ascii_params=b"NAVD88 - Geoid09 (Meters)|\x00")
        crs = collection.read_crs(path)
        self.assertFalse(crs.vertical_declared)
        self.assertIn("NAVD88", crs.citation)
        # ...so the compound CRS forces it on.
        self.assertEqual(crs.compound(), "EPSG:26917+5703")

    def test_a_declared_vertical_datum_is_left_alone(self):
        path = write_tile(self.dir, "declared.laz", bounds=(0.0, 0.0, 1.0, 1.0),
                          geokeys={3072: (0, 1, 26917), 4096: (0, 1, 5703)})
        crs = collection.read_crs(path)
        self.assertTrue(crs.vertical_declared)
        # Appending a second vertical axis produces a CRS PROJ rejects.
        self.assertEqual(crs.compound(), "EPSG:26917")

    def test_the_compound_string_uses_projs_bare_code_syntax(self):
        """"EPSG:26917+EPSG:5703" is not valid PROJ; the code goes bare."""
        path = write_tile(self.dir, "utm.laz", bounds=(0.0, 0.0, 1.0, 1.0),
                          geokeys={3072: (0, 1, 26917)})
        self.assertNotIn("+EPSG:", collection.read_crs(path).compound())

    def test_an_undefined_geokey_value_is_not_mistaken_for_a_crs(self):
        # 32767 is GeoTIFF's "user defined" sentinel, and 0 is unset.
        path = write_tile(self.dir, "userdef.laz", bounds=(0.0, 0.0, 1.0, 1.0),
                          geokeys={3072: (0, 1, 32767), 2048: (0, 1, 4269)})
        self.assertEqual(collection.read_crs(path).horizontal, "EPSG:4269")

    def test_wkt_from_a_1_4_file_is_used_directly(self):
        wkt = 'COMPD_CS["x",PROJCS["y"],VERT_CS["NAVD88"]]'
        path = write_tile(self.dir, "wkt.laz", bounds=(0.0, 0.0, 1.0, 1.0),
                          version=(1, 4), wkt=wkt)
        crs = collection.read_crs(path)
        self.assertEqual(crs.horizontal, wkt)
        self.assertTrue(crs.vertical_declared)


class MixedSurveyTests(unittest.TestCase):
    """A downloaded collection is not guaranteed to be a single survey.

    A real 1,731-tile download over north Georgia held 1,499 tiles of the 2018
    statewide product in Albers and 232 of the ARRA Lake Lanier project in
    UTM 17N. Their bounds are then numbers in different frames — Albers
    eastings near 1,076,000 against UTM ones near 237,000 — so one bbox
    compared against all of them selects nothing, or the wrong tiles.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        write_tile(self.dir, "albers_a.laz",
                   bounds=(1076000.0, 1347000.0, 1077000.0, 1348000.0),
                   geokeys={3072: (0, 1, 6350)})
        write_tile(self.dir, "albers_b.laz",
                   bounds=(1077000.0, 1347000.0, 1078000.0, 1348000.0),
                   geokeys={3072: (0, 1, 6350)})
        write_tile(self.dir, "utm.laz",
                   bounds=(237000.0, 3837000.0, 238500.0, 3838500.0),
                   geokeys={3072: (0, 1, 26917)})
        self.tiles = collection.scan(self.dir)

    def test_each_tile_carries_its_own_coordinate_system(self):
        systems = {tile.crs for tile in self.tiles}
        self.assertEqual(systems, {"EPSG:6350+5703", "EPSG:26917+5703"})

    def test_tiles_group_by_coordinate_system(self):
        groups = collection.by_crs(self.tiles)
        self.assertEqual(len(groups), 2)
        self.assertEqual(len(groups["EPSG:6350+5703"]), 2)
        self.assertEqual(len(groups["EPSG:26917+5703"]), 1)

    def test_a_bbox_only_selects_within_its_own_frame(self):
        """The failure: an Albers bbox must not match a UTM tile."""
        groups = collection.by_crs(self.tiles)
        albers_bbox = (1076500.0, 1347500.0, 1076600.0, 1347600.0)
        self.assertEqual(
            len(collection.tiles_for(groups["EPSG:6350+5703"], albers_bbox)), 1)
        self.assertEqual(
            len(collection.tiles_for(groups["EPSG:26917+5703"], albers_bbox)), 0)

    def test_the_crs_survives_the_cached_index(self):
        """Re-reading every header for 1,700 files is not free."""
        collection.save_index(self.tiles, Path(self.dir) / collection.INDEX_FILENAME)
        reloaded = collection.load_index(Path(self.dir) / collection.INDEX_FILENAME)
        self.assertEqual({t.crs for t in reloaded},
                         {"EPSG:6350+5703", "EPSG:26917+5703"})


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        # A 2x2 block of 1500 m tiles, the layout USGS publishes.
        self.grid = {}
        for ix in range(2):
            for iy in range(2):
                x0, y0 = 237000.0 + ix * 1500, 3837000.0 + iy * 1500
                name = f"tile_{ix}{iy}.laz"
                self.grid[name] = (x0, y0, x0 + 1500, y0 + 1500)
                write_tile(self.dir, name, bounds=self.grid[name])

    def test_scan_indexes_every_tile(self):
        self.assertEqual(len(collection.scan(self.dir)), 4)

    def test_scan_skips_what_it_cannot_read(self):
        """A download in progress leaves partial and unrelated files behind."""
        (Path(self.dir) / "half.laz").write_bytes(b"LASF" + b"\x00" * 40)
        (Path(self.dir) / "readme.txt").write_bytes(b"notes")
        self.assertEqual(len(collection.scan(self.dir)), 4)

    def test_a_target_well_inside_one_tile_needs_only_that_tile(self):
        tiles = collection.scan(self.dir)
        bbox = (237700.0, 3837700.0, 237800.0, 3837800.0)
        selected = collection.tiles_for(tiles, bbox)
        self.assertEqual([t.path.name for t in selected], ["tile_00.laz"])

    def test_a_target_on_a_corner_pulls_all_four_tiles(self):
        """The case that makes merging necessary rather than optional."""
        tiles = collection.scan(self.dir)
        bbox = (238400.0, 3838400.0, 238600.0, 3838600.0)
        selected = collection.tiles_for(tiles, bbox)
        self.assertEqual(len(selected), 4)

    def test_selection_is_ordered_by_how_much_of_the_target_each_tile_holds(self):
        tiles = collection.scan(self.dir)
        # Mostly in tile_00, clipping the edge of its eastern neighbour.
        bbox = (238300.0, 3837700.0, 238600.0, 3837800.0)
        selected = collection.tiles_for(tiles, bbox)
        self.assertEqual(selected[0].path.name, "tile_00.laz")
        self.assertEqual(selected[1].path.name, "tile_10.laz")

    def test_a_target_outside_the_collection_selects_nothing(self):
        tiles = collection.scan(self.dir)
        selected = collection.tiles_for(tiles, (100.0, 100.0, 200.0, 200.0))
        self.assertEqual(selected, [])

    def test_the_index_is_cached_and_reused(self):
        first = collection.index_for(self.dir)
        self.assertTrue((Path(self.dir) / collection.INDEX_FILENAME).exists())
        self.assertEqual(len(collection.load_index(
            Path(self.dir) / collection.INDEX_FILENAME)), len(first))

    def test_refresh_picks_up_tiles_that_arrived_after_the_index(self):
        """Downloads run for hours; the index has to be able to catch up."""
        collection.index_for(self.dir)
        write_tile(self.dir, "tile_20.laz", bounds=(240000.0, 3837000.0,
                                                    241500.0, 3838500.0))
        self.assertEqual(len(collection.index_for(self.dir)), 4)
        self.assertEqual(len(collection.index_for(self.dir, refresh=True)), 5)

    def test_a_cached_entry_whose_file_is_gone_is_dropped(self):
        collection.index_for(self.dir)
        (Path(self.dir) / "tile_00.laz").unlink()
        self.assertEqual(len(collection.index_for(self.dir)), 3)

    def test_an_empty_directory_is_an_error_rather_than_an_empty_build(self):
        empty = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(empty, ignore_errors=True))
        with self.assertRaises(collection.CollectionError):
            collection.index_for(empty)


if __name__ == "__main__":
    unittest.main()
