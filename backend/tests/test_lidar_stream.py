"""3D streamed from USGS's octree (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C).

The octree here is made by the tests (``FakeSurvey``): an ept.json, its index
split into two pages, and LAZ pieces written with laspy, served from memory in
place of AWS. No USGS data is committed. ``EZPZ_LIVE_AWS=1`` also runs
``LiveAwsTests``, which streams the Ellijay test LZ from the real survey.
"""

import io
import json
import math
import os
import shutil
import struct
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from lidar import coverage, ept, names, pieces, pipeline, stream  # noqa: E402
from lidar.ept import Node  # noqa: E402

LAT, LON = 34.596407, -84.128098          # Ellijay
SECRET = "t" * 40
GEOID = -30.35                             # north Georgia's separation
BASE = "https://aws.example/usgs-lidar-public"


def write_laz(x, y, z, classification, intensity=None) -> bytes:
    import laspy

    header = laspy.LasHeader(point_format=6, version="1.4")
    header.scales = np.array([0.001, 0.001, 0.001])
    header.offsets = np.array([float(np.min(x)), float(np.min(y)), float(np.min(z))])
    las = laspy.LasData(header)
    las.x, las.y, las.z = x, y, z
    las.classification = np.asarray(classification, dtype=np.uint8)
    if intensity is not None:
        las.intensity = np.asarray(intensity, dtype=np.uint16)
    buffer = io.BytesIO()
    las.write(buffer, do_compress=True)
    return buffer.getvalue()


class FakeSurvey:
    """A survey's EPT, 1,600 m on a side round Ellijay, four levels deep.

    The index is two pages: the root lists depths 0 to 2, with one depth-2
    node marked -1 whose own page lists it and its descendants. Each listed
    node has a LAZ piece of ground on a slope, trees and noise.
    """

    name = "GA_Fake_2018"
    span = 4

    def __init__(self, *, year=2018, crs="3857"):
        self.name = f"GA_Fake_{year}"
        cx, cy = ept.lonlat_to_mercator(LON, LAT)
        half = 800.0
        self.bounds = [cx - half, cy - half, 300.0 - half, cx + half, cy + half, 300.0 + half]
        self.conforming = [cx - 700, cy - 700, 250.0, cx + 700, cy + 700, 420.0]
        self.files = {}
        self.fetched = []
        self.document = {"bounds": self.bounds, "boundsConforming": self.conforming,
                         "dataType": "laszip", "hierarchyType": "json", "points": 0,
                         "span": self.span, "srs": {"authority": "EPSG", "horizontal": crs},
                         "version": "1.1.0"}
        self.put("ept.json", json.dumps(self.document).encode())
        info = ept.Info.read(self.document)
        centre = ept.node_at(info, LON, LAT, 2)
        # Depths 0-2 in the root page, the centre's depth-2 node in a page of its
        # own with everything under it down to depth 4.
        self.nodes = {ept.ROOT}
        for depth in (1, 2):
            self.nodes.add(ept.node_at(info, LON, LAT, depth))
        deep = set()
        for depth in (3, 4):
            node = ept.node_at(info, LON, LAT, depth)
            deep.add(node)
            deep.update(child for child in Node(depth - 1, node.x // 2, node.y // 2, node.z // 2).children()
                        if child.depth == depth and child.z == node.z)
        root_page = {str(node): 100 for node in self.nodes}
        root_page[str(centre)] = -1
        own_page = {str(centre): 100, **{str(node): 100 for node in deep}}
        self.put("ept-hierarchy/0-0-0-0.json", json.dumps(root_page).encode())
        self.put(f"ept-hierarchy/{centre}.json", json.dumps(own_page).encode())
        self.centre = centre
        self.all_nodes = self.nodes | deep
        rng = np.random.default_rng(7)
        for node in self.all_nodes:
            xmin, ymin, _zmin, xmax, ymax, _zmax = info.cube(node)
            count = 100
            x = rng.uniform(xmin, xmax, count)
            y = rng.uniform(ymin, ymax, count)
            ground = 300.0 + (x - cx) * 0.01
            classification = np.full(count, pipeline.GROUND)
            classification[60:80] = pipeline.HIGH_VEGETATION
            classification[80:90] = pipeline.NOISE
            classification[90:] = 6                      # buildings: not kept
            z = ground.copy()
            z[60:80] += 20.0                              # 20 m canopy
            self.put(f"ept-data/{node}.laz", write_laz(x, y, z, classification,
                                                       intensity=rng.integers(200, 2000, count)))

    def put(self, path, data):
        self.files[f"{BASE}/{self.name}/{path}"] = data

    def fetch(self, url, limit):
        self.fetched.append(url)
        if url not in self.files:
            raise stream.StreamError("USGS has no such file.", code="not_found", status=404)
        return self.files[url]

    def index(self):
        cx, cy = LON, LAT
        ring = [[cx - 1, cy - 1], [cx + 1, cy - 1], [cx + 1, cy + 1], [cx - 1, cy + 1], [cx - 1, cy - 1]]
        survey = coverage.Survey(name=self.name, url="", points=1000, year=int(self.name[-4:]))
        return [(survey, {"type": "Polygon", "coordinates": [ring]})]


def geoid(lons, lats):
    return [GEOID for _ in lons]


def read_pnts(data: bytes):
    """Points of a .pnts as geodetic (lon, lat, h) and RGB, decoded independently."""
    magic, version, length, table_json, table_bin, batch_json, batch_bin = struct.unpack("<4s6I", data[:28])
    assert magic == b"pnts" and version == 1 and length == len(data)
    table = json.loads(data[28:28 + table_json])
    body = data[28 + table_json:28 + table_json + table_bin]
    count = table["POINTS_LENGTH"]
    q = np.frombuffer(body, dtype="<u2", count=3 * count,
                      offset=table["POSITION_QUANTIZED"]["byteOffset"]).reshape(-1, 3)
    ecef = np.array(table["QUANTIZED_VOLUME_OFFSET"]) + q / 65535.0 * np.array(table["QUANTIZED_VOLUME_SCALE"])
    rgb = np.frombuffer(body, dtype=np.uint8, count=3 * count,
                        offset=table["RGB"]["byteOffset"]).reshape(-1, 3)
    from pyproj import Transformer
    lon, lat, h = Transformer.from_crs("EPSG:4978", "EPSG:4979", always_xy=True).transform(
        ecef[:, 0], ecef[:, 1], ecef[:, 2])
    return np.column_stack((lon, lat, h)), rgb, table, (28 + table_json) % 8


class NodeTests(unittest.TestCase):
    def test_an_address_reads_and_writes_back(self):
        self.assertEqual(str(Node.parse("10-512-300-7")), "10-512-300-7")

    def test_an_address_outside_its_depth_is_refused(self):
        for bad in ("2-4-0-0", "1-0-0-2", "-1-0-0-0", "20-0-0-0", "a-b-c-d", "1-0-0", ""):
            with self.assertRaises(ept.EptError, msg=bad):
                Node.parse(bad)

    def test_a_node_has_eight_children_a_level_down(self):
        children = list(Node(1, 1, 0, 1).children())
        self.assertEqual(len(set(children)), 8)
        self.assertTrue(all(c.depth == 2 and c.x in (2, 3) and c.z in (2, 3) for c in children))

    def test_a_cube_halves_with_each_level_and_spacing_is_width_over_span(self):
        info = ept.Info.read(FakeSurvey().document)
        self.assertAlmostEqual(info.width(Node(2, 0, 0, 0)), 400.0)
        self.assertAlmostEqual(info.spacing(Node(2, 0, 0, 0)), 100.0)
        cube = info.cube(Node(1, 1, 0, 1))
        self.assertAlmostEqual(cube[0] - info.bounds[0], 800.0)
        self.assertAlmostEqual(cube[2] - info.bounds[2], 800.0)

    def test_mercator_round_trips(self):
        x, y = ept.lonlat_to_mercator(LON, LAT)
        lon, lat = ept.mercator_to_lonlat(x, y)
        self.assertAlmostEqual(lon, LON, places=10)
        self.assertAlmostEqual(lat, LAT, places=10)

    def test_a_survey_not_in_web_mercator_is_refused(self):
        """Albers read as Mercator is Nigeria (AGENTS.md §13)."""
        with self.assertRaises(ept.EptError):
            ept.Info.read(FakeSurvey(crs="5070").document)

    def test_a_survey_of_other_pieces_is_refused(self):
        document = dict(FakeSurvey().document, dataType="binary")
        with self.assertRaises(ept.EptError):
            ept.Info.read(document)


class TilesetTests(unittest.TestCase):
    def setUp(self):
        self.survey = FakeSurvey()
        self.info = ept.Info.read(self.survey.document)
        pages = {ept.ROOT: "ept-hierarchy/0-0-0-0.json",
                 self.survey.centre: f"ept-hierarchy/{self.survey.centre}.json"}
        self.hierarchy = ept.Hierarchy(
            lambda node: json.loads(self.survey.files[f"{BASE}/{self.survey.name}/{pages[node]}"]))

    def make(self, top=ept.ROOT, page=ept.ROOT, levels=ept.LEVELS_PER_TILESET):
        return ept.tileset(self.info, self.hierarchy, top, page_root=page,
                           name=lambda n: str(n), geoid=geoid, levels=levels)

    def walk(self, tile, depth=0):
        yield tile, depth
        for child in tile.get("children", []):
            yield from self.walk(child, depth + 1)

    def test_the_index_is_followed_into_its_own_pages(self):
        found = self.hierarchy.subtree(ept.ROOT, 4, page_root=ept.ROOT)
        self.assertEqual(set(found), self.survey.all_nodes)
        self.assertTrue(all(count > 0 for count, _page in found.values()))

    def test_every_node_is_a_tile_whose_piece_is_named(self):
        document, joined = self.make(levels=5)
        uris = {tile["content"]["uri"] for tile, _d in self.walk(document["root"])}
        self.assertEqual(uris, {f"{n}.pnts" for n in self.survey.all_nodes})
        self.assertEqual(joined, {})
        self.assertEqual(document["root"]["refine"], "ADD")
        self.assertEqual(document["asset"]["version"], "1.0")

    def test_the_last_level_is_joined_as_external_tilesets(self):
        document, joined = self.make(levels=2)
        tiles = list(self.walk(document["root"]))
        external = [tile for tile, depth in tiles if depth == 2]
        self.assertEqual({tile["content"]["uri"] for tile in external}, {f"{self.survey.centre}.json"})
        self.assertNotIn("children", external[0])
        # Listed as -1 in the root page: its own page is where it starts.
        self.assertEqual(joined, {self.survey.centre: ept.ROOT})
        inner, _ = self.make(top=self.survey.centre, page=joined[self.survey.centre], levels=2)
        self.assertEqual(inner["root"]["content"]["uri"], f"{self.survey.centre}.pnts")
        self.assertEqual(inner["root"]["refine"], "ADD")
        self.assertEqual(len(list(self.walk(inner["root"]))), 1 + len(self.survey.all_nodes - self.survey.nodes))

    def test_geometric_error_is_the_spacing_and_a_leaf_has_none(self):
        document, _ = self.make(levels=5)
        for tile, depth in self.walk(document["root"]):
            if tile.get("children"):
                self.assertAlmostEqual(tile["geometricError"], 1600.0 / 2 ** depth / 4)
            else:
                self.assertEqual(tile["geometricError"], 0.0)

    def test_a_region_holds_its_cube_with_ellipsoidal_heights(self):
        document, _ = self.make()
        for tile, depth in self.walk(document["root"]):
            west, south, east, north, low, high = tile["boundingVolume"]["region"]
            self.assertLess(west, east)
            self.assertLess(south, north)
            self.assertLessEqual(math.degrees(west), LON + 0.01)
            self.assertGreaterEqual(math.degrees(east), LON - 0.01)
            # Clamped to the points' own heights, then moved to the ellipsoid.
            self.assertGreaterEqual(low, 250.0 + GEOID - ept.REGION_HEIGHT_PAD_M - 1e-9)
            self.assertLessEqual(high, 420.0 + GEOID + ept.REGION_HEIGHT_PAD_M + 1e-9)
        root = document["root"]["boundingVolume"]["region"]
        self.assertAlmostEqual(root[4], 250.0 + GEOID - ept.REGION_HEIGHT_PAD_M)
        self.assertAlmostEqual(root[5], 420.0 + GEOID + ept.REGION_HEIGHT_PAD_M)

    def test_a_node_with_no_points_has_no_tileset(self):
        with self.assertRaises(ept.EptError):
            self.make(top=Node(1, 0, 0, 0) if Node(1, 0, 0, 0) not in self.survey.nodes else Node(1, 1, 1, 1))


class NameTests(unittest.TestCase):
    def test_a_piece_name_reads_back_to_its_node(self):
        rng = np.random.default_rng(3)
        for _ in range(2000):
            depth = int(rng.integers(0, ept.MAX_DEPTH + 1))
            node = Node(depth, *(int(rng.integers(0, 2 ** depth)) for _ in range(3)))
            name = names.piece_name("GA", node, secret=SECRET)
            self.assertRegex(name, r"^[0-9a-f]{16}$")
            self.assertEqual(names.piece_node("GA", name, secret=SECRET), node)

    def test_a_name_says_nothing_without_the_secret(self):
        node = Node(10, 300, 512, 7)
        self.assertNotEqual(names.piece_name("GA", node, secret=SECRET),
                            names.piece_name("GA", node, secret="o" * 40))
        self.assertNotEqual(names.piece_name("GA", node, secret=SECRET),
                            names.piece_name("KY", node, secret=SECRET))
        for fragment in ("300", "512", "10-"):
            self.assertNotIn(fragment, names.piece_name("GA", node, secret=SECRET))

    def test_most_names_never_handed_out_read_as_none(self):
        rng = np.random.default_rng(5)
        made_up = [f"{int(v):016x}" for v in rng.integers(0, 2 ** 63, 2000, dtype=np.int64)]
        nodes = [names.piece_node("GA", name, secret=SECRET) for name in made_up]
        self.assertLess(sum(node is not None for node in nodes), 600)

    def test_a_malformed_name_is_none(self):
        for bad in ("", "../../etc", "ABCDEF0123456789", "0" * 15, "0" * 17):
            self.assertIsNone(names.piece_node("GA", bad, secret=SECRET))

    def test_a_new_look_is_a_new_key_so_no_cache_keeps_the_old_colours(self):
        """Browsers and Android keep a piece a week under its URL."""
        self.assertNotEqual(names.survey_key("GA", secret=SECRET, look=1),
                            names.survey_key("GA", secret=SECRET, look=2))
        self.assertEqual(names.survey_key("GA", secret=SECRET), names.survey_key("GA", secret=SECRET, look=pieces.LOOK))

    def test_a_survey_key_is_an_hmac_of_its_name(self):
        import hashlib
        import hmac
        seed = f"survey:look{pieces.LOOK}:GA_Statewide_B3_2018".encode()
        expected = hmac.new(SECRET.encode(), seed, hashlib.sha256).hexdigest()[:16]
        self.assertEqual(names.survey_key("GA_Statewide_B3_2018", secret=SECRET), expected)


class PieceTests(unittest.TestCase):
    def piece(self, classified=True):
        survey = FakeSurvey()
        node = survey.centre
        data = survey.files[f"{BASE}/{survey.name}/ept-data/{node}.laz"]
        x, y, z, classification, _intensity = pieces.read_laz(data)
        body, kept = pieces.convert(data, classified=classified, geoid_m=GEOID)
        return (x, y, z, classification), body, kept

    def test_noise_and_unkept_classes_are_dropped(self):
        (_x, _y, _z, classification), body, kept = self.piece()
        self.assertEqual(kept, int(np.isin(classification, (2, 5)).sum()))
        self.assertEqual(kept, 80)
        _points, _rgb, table, _ = read_pnts(body)
        self.assertEqual(table["POINTS_LENGTH"], 80)

    def test_an_older_survey_keeps_the_surface_classes(self):
        """Pre-2015 surveys label only ground; their trees are classes 0 and 1."""
        (_x, _y, _z, classification), _body, kept = self.piece(classified=False)
        self.assertEqual(kept, int(np.isin(classification, pipeline.SURFACE_CLASSES).sum()))

    def test_points_land_where_they_were_with_the_geoid_added(self):
        (x, y, z, classification), body, _kept = self.piece()
        points, _rgb, _table, _ = read_pnts(body)
        keep = np.isin(classification, (2, 5))
        lons = np.degrees(x[keep] / ept.MERCATOR_RADIUS)
        lats = np.degrees(2 * np.arctan(np.exp(y[keep] / ept.MERCATOR_RADIUS)) - np.pi / 2)
        # Quantized over the piece's box: a 200 m piece is good to a few mm.
        metres = 111_320.0
        self.assertLess(np.max(np.abs(points[:, 0] - lons)) * metres, 0.02)
        self.assertLess(np.max(np.abs(points[:, 1] - lats)) * metres, 0.02)
        self.assertLess(np.max(np.abs(points[:, 2] - (z[keep] + GEOID))), 0.02)

    def test_the_ground_is_brown_and_the_trees_green_never_orange(self):
        """The owner's screenshot at Ellijay: nearly every crown orange, since
        north Georgia's hardwoods stand over 15 m. Nature's colours instead."""
        (_x, _y, _z, classification), body, _kept = self.piece()
        _points, rgb, _table, _ = read_pnts(body)
        kept = classification[np.isin(classification, (2, 5))]
        r, g, b = (rgb[:, i].astype(int) for i in range(3))
        ground, trees = kept == 2, kept == 5
        self.assertTrue((r[ground] > g[ground]).all() and (g[ground] > b[ground]).all(), "browns: red over green over blue")
        self.assertTrue((g[trees] > r[trees]).all() and (g[trees] > b[trees]).all(), "greens")
        self.assertFalse(((r - b > 80) & (r > g + 30)).any(), "nothing orange")

    def test_greens_darken_with_height_above_ground(self):
        heights = np.array([0.0, 0.0, 0.0, 1.0, 3.0, 8.0, 20.0, 40.0])
        classes = [2, 2, 2, 3, 4, 5, 5, 5]
        x = np.array([0.0, 4.0, 8.0, 1.0, 2.0, 3.0, 4.0, 5.0])
        data = write_laz(x, np.zeros(8), 300.0 + heights, classes, intensity=[1000] * 8)
        _p, rgb, _t, _ = read_pnts(pieces.convert(data, classified=True, geoid_m=GEOID, survey_range=(0, 2000))[0])
        brightness = rgb[3:].astype(int).sum(axis=1)
        self.assertTrue((np.diff(brightness) <= 0).all(), brightness)
        self.assertTrue(brightness[0] > brightness[-1] + 60)

    def test_each_point_is_shaded_by_its_intensity_within_the_surveys_range(self):
        x = np.array([0.0, 1.0, 2.0, 3.0])
        data = write_laz(x, np.zeros(4), np.full(4, 300.0), [2, 2, 2, 2], intensity=[100, 900, 1700, 5000])
        _p, rgb, _t, _ = read_pnts(pieces.convert(data, classified=True, geoid_m=GEOID, survey_range=(100, 1700))[0])
        brightness = rgb.astype(int).sum(axis=1)
        self.assertTrue(brightness[0] < brightness[1] < brightness[2], brightness)
        # Past the survey's range is the range's end, not a glare.
        self.assertEqual(brightness[2], brightness[3])

    def test_a_piece_given_no_range_shades_by_its_own(self):
        x = np.array([0.0, 1.0, 2.0])
        data = write_laz(x, np.zeros(3), np.full(3, 300.0), [2, 2, 2], intensity=[10, 20, 30])
        _p, rgb, _t, _ = read_pnts(pieces.convert(data, classified=True, geoid_m=GEOID)[0])
        brightness = rgb.astype(int).sum(axis=1)
        self.assertTrue(brightness[0] < brightness[1] < brightness[2], brightness)

    def test_a_survey_with_no_intensity_is_not_shaded_to_black(self):
        data = write_laz(np.array([0.0, 1.0]), np.zeros(2), np.full(2, 300.0), [2, 2], intensity=[0, 0])
        _p, rgb, _t, _ = read_pnts(pieces.convert(data, classified=True, geoid_m=GEOID)[0])
        self.assertTrue((rgb.astype(int).sum(axis=1) > 200).all())

    def test_a_piece_with_no_ground_is_coloured_by_class(self):
        data = write_laz(np.array([0.0, 1.0]), np.array([0.0, 1.0]), np.array([5.0, 6.0]),
                         [pipeline.HIGH_VEGETATION, pipeline.LOW_VEGETATION], intensity=[500, 500])
        _p, rgb, _t, _ = read_pnts(pieces.convert(data, classified=True, geoid_m=GEOID, survey_range=(0, 1000))[0])
        r, g, b = (rgb[:, i].astype(int) for i in range(3))
        self.assertTrue((g > r).all() and (g > b).all())
        # High vegetation stands for a tall crown, low for scrub: the darker the taller.
        self.assertLess(int(rgb[0].astype(int).sum()), int(rgb[1].astype(int).sum()))

    def test_intensity_range_is_the_middle_of_a_sample(self):
        lo, hi = pieces.intensity_range(np.concatenate([np.arange(1, 1001), [60000]]))
        self.assertLess(lo, 30)
        self.assertTrue(950 < hi < 1001)
        self.assertIsNone(pieces.intensity_range(np.zeros(50)))

    def test_the_feature_table_is_aligned_for_its_binary(self):
        _source, body, _kept = self.piece()
        _points, _rgb, _table, misalignment = read_pnts(body)
        self.assertEqual(misalignment, 0)
        self.assertEqual(len(body) % 8, 0)

    def test_a_piece_that_keeps_nothing_is_an_empty_cloud(self):
        data = write_laz(np.array([0.0]), np.array([0.0]), np.array([5.0]), [pipeline.NOISE])
        body, kept = pieces.convert(data, classified=True, geoid_m=GEOID)
        self.assertEqual(kept, 0)
        self.assertEqual(read_pnts(body)[2]["POINTS_LENGTH"], 0)

    def test_a_damaged_piece_is_a_piece_error(self):
        with self.assertRaises(pieces.PieceError):
            pieces.convert(b"LASF not really", classified=True, geoid_m=GEOID)


class StreamerTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="stream-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.survey = FakeSurvey()
        self.streamer = stream.Streamer(self.root, fetch=self.survey.fetch, geoid=geoid,
                                        index=self.survey.index(), base=BASE, secret=SECRET)
        self.key = names.survey_key(self.survey.name, secret=SECRET)

    def name(self, node):
        return names.piece_name(self.survey.name, node, secret=SECRET)

    def test_a_target_finds_its_survey_and_key(self):
        survey, key = self.streamer.survey_for(LAT, LON)
        self.assertEqual((survey.name, key), (self.survey.name, self.key))
        self.assertIsNone(self.streamer.survey_for(LAT + 5, LON))

    def test_the_newest_survey_is_streamed(self):
        older = FakeSurvey(year=2010)
        streamer = stream.Streamer(self.root, fetch=self.survey.fetch, geoid=geoid,
                                   index=older.index() + self.survey.index(), base=BASE, secret=SECRET)
        self.assertEqual(streamer.survey_for(LAT, LON)[0].name, self.survey.name)

    def test_the_root_tileset_is_made_once_then_served_from_the_cache(self):
        path = self.streamer.file(self.key, "tileset.json")
        document = json.loads(path.read_bytes())
        self.assertEqual(document["root"]["content"]["uri"], f"{self.name(ept.ROOT)}.pnts")
        fetched = len(self.survey.fetched)
        self.streamer.file(self.key, "tileset.json")
        self.assertEqual(len(self.survey.fetched), fetched)

    def test_the_whole_tree_can_be_walked_from_the_root(self):
        """What a viewer does: every uri named is a file the server makes."""
        pending, seen_pieces = ["tileset.json"], set()
        while pending:
            name = pending.pop()
            document = json.loads(self.streamer.file(self.key, name).read_bytes())
            stack = [document["root"]]
            while stack:
                tile = stack.pop()
                stack.extend(tile.get("children", []))
                uri = tile["content"]["uri"]
                if uri.endswith(".json"):
                    pending.append(uri)
                else:
                    self.assertTrue(self.streamer.file(self.key, uri).is_file())
                    seen_pieces.add(uri)
        self.assertEqual(seen_pieces, {f"{self.name(n)}.pnts" for n in self.survey.all_nodes})

    def test_a_piece_is_fetched_once_and_converted(self):
        node = self.survey.centre
        path = self.streamer.file(self.key, f"{self.name(node)}.pnts")
        self.assertEqual(read_pnts(path.read_bytes())[2]["POINTS_LENGTH"], 80)
        laz = [url for url in self.survey.fetched if url.endswith(".laz")]
        self.streamer.file(self.key, f"{self.name(node)}.pnts")
        self.assertEqual([url for url in self.survey.fetched if url.endswith(".laz")], laz)

    def test_every_piece_is_shaded_by_the_surveys_range_taken_once_from_its_root(self):
        """One range for the survey, so two pieces side by side are shaded alike."""
        with patch.object(pieces, "convert", wraps=pieces.convert) as convert:
            for node in sorted(self.survey.all_nodes)[:3]:
                self.streamer.file(self.key, f"{self.name(node)}.pnts")
        ranges = {call.kwargs["survey_range"] for call in convert.call_args_list}
        self.assertEqual(len(ranges), 1)
        self.assertIsNotNone(next(iter(ranges)))
        stats = json.loads((self.root / self.key / "stats.json").read_text())
        self.assertEqual(tuple(stats["intensity"]), next(iter(ranges)))
        # The root's piece is read for it once, and is not fetched again as a piece.
        self.streamer.file(self.key, f"{self.name(ept.ROOT)}.pnts")
        root_laz = f"{BASE}/{self.survey.name}/ept-data/0-0-0-0.laz"
        self.assertEqual(self.survey.fetched.count(root_laz), 1)

    def test_two_asking_at_once_fetch_once(self):
        node = self.survey.centre
        slow = self.survey.fetch

        def fetch(url, limit):
            time.sleep(0.05)
            return slow(url, limit)
        streamer = stream.Streamer(self.root, fetch=fetch, geoid=geoid, index=self.survey.index(),
                                   base=BASE, secret=SECRET)
        threads = [threading.Thread(target=streamer.file, args=(self.key, f"{self.name(node)}.pnts"))
                   for _ in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        piece = f"{BASE}/{self.survey.name}/ept-data/{node}.laz"
        self.assertEqual(self.survey.fetched.count(piece), 1)

    def test_a_name_never_handed_out_is_not_found(self):
        for name in ("0123456789abcdef.pnts", "tileset.pnts", "area.json", "ept.json",
                     f"{self.name(Node(4, 0, 0, 0))}.pnts", "../tileset.json"):
            with self.assertRaises(stream.StreamError, msg=name) as caught:
                self.streamer.file(self.key, name)
            self.assertEqual(caught.exception.status, 404, name)
        self.assertFalse(any(url.endswith(".laz") for url in self.survey.fetched))

    def test_without_geoid_grids_nothing_is_served(self):
        """PROJ passes heights through and reports success: refuse, as builds do."""
        streamer = stream.Streamer(self.root, fetch=self.survey.fetch,
                                   geoid=lambda lons, lats: [0.0 for _ in lons],
                                   index=self.survey.index(), base=BASE, secret=SECRET)
        for name in ("tileset.json", f"{self.name(self.survey.centre)}.pnts"):
            with self.assertRaises(stream.StreamError) as caught:
                streamer.file(self.key, name)
            self.assertEqual(caught.exception.code, "geoid_unavailable")
        self.assertEqual(list(self.root.rglob("*.pnts")), [])

    def test_aws_unreachable_is_a_502_and_nothing_is_cached(self):
        def down(url, limit):
            raise stream.StreamError("USGS could not be reached.", code="upstream_unavailable", status=502)
        streamer = stream.Streamer(self.root, fetch=down, geoid=geoid, index=self.survey.index(),
                                   base=BASE, secret=SECRET)
        with self.assertRaises(stream.StreamError) as caught:
            streamer.file(self.key, "tileset.json")
        self.assertEqual(caught.exception.status, 502)
        self.assertEqual([p for p in self.root.rglob("*") if p.is_file()], [])

    def test_the_cache_lets_go_of_the_pieces_used_longest_ago(self):
        names_ = [f"{self.name(node)}.pnts" for node in sorted(self.survey.all_nodes)[:4]]
        paths = [self.streamer.file(self.key, name) for name in names_]
        self.streamer.file(self.key, "tileset.json")
        for age, path in enumerate(paths):
            os.utime(path, (1000 + age, 1000 + age))
        sizes = [path.stat().st_size for path in paths]
        total = sum(p.stat().st_size for p in self.root.rglob("*") if p.is_file())
        freed = self.streamer.prune(cap=total - sizes[0] - 1)
        self.assertEqual(freed, sizes[0] + sizes[1])
        self.assertFalse(paths[0].exists() or paths[1].exists())
        self.assertTrue(paths[2].exists() and paths[3].exists())
        self.assertTrue((self.root / self.key / "tileset.json").exists())
        self.assertTrue((self.root / self.key / "ept.json").exists())


class FetchTests(unittest.TestCase):
    """Reading from AWS: a viewer never asks again for a tile that failed, so a
    reset connection is tried again here, and a refusal is not."""

    class Response:
        def __init__(self, status, body=b"piece"):
            self.status_code, self.body = status, body

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def iter_content(self, _size):
            yield self.body

    def fetching(self, *answers):
        import requests
        calls = []

        class Session:
            def get(self, url, **_kwargs):
                calls.append(url)
                answer = answers[len(calls) - 1]
                if answer == "reset":
                    raise requests.ConnectionError("reset by peer")
                return FetchTests.Response(answer)
        patches = [patch.object(stream, "_http", return_value=Session()),
                   patch.object(stream, "FETCH_BACKOFF_S", 0)]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)
        return calls

    def test_a_reset_connection_is_tried_again(self):
        calls = self.fetching("reset", 503, 200)
        self.assertEqual(stream._http_get("https://aws/x.laz", 100), b"piece")
        self.assertEqual(len(calls), 3)

    def test_it_gives_up_after_its_tries(self):
        calls = self.fetching("reset", "reset", "reset")
        with self.assertRaises(stream.StreamError) as caught:
            stream._http_get("https://aws/x.laz", 100)
        self.assertEqual((caught.exception.status, caught.exception.code), (502, "upstream_unavailable"))
        self.assertEqual(len(calls), stream.FETCH_TRIES)

    def test_a_missing_file_is_not_asked_for_again(self):
        calls = self.fetching(404)
        with self.assertRaises(stream.StreamError) as caught:
            stream._http_get("https://aws/x.laz", 100)
        self.assertEqual(caught.exception.status, 404)
        self.assertEqual(len(calls), 1)

    def test_more_than_a_piece_holds_is_refused(self):
        self.fetching(200)
        with self.assertRaises(stream.StreamError):
            stream._http_get("https://aws/x.laz", 3)


@unittest.skipUnless(os.environ.get("EZPZ_LIVE_AWS") == "1", "set EZPZ_LIVE_AWS=1 to stream from AWS")
class LiveAwsTests(unittest.TestCase):
    """The Ellijay test LZ from GA_Statewide_B3_2018, as a first view would
    have it: the root, down the external tilesets to the finest piece there."""

    def test_the_ellijay_lz_streams_from_the_real_survey(self):
        import terrain_tiles
        if not terrain_tiles.geoid_grids_available():
            self.skipTest("geoid grids unavailable: set PROJ_NETWORK=ON")
        root = Path(tempfile.mkdtemp(prefix="stream-live-"))
        self.addCleanup(shutil.rmtree, root, True)
        streamer = stream.Streamer(root, secret=SECRET)
        survey, key = streamer.survey_for(LAT, LON)
        self.assertEqual(survey.name, "GA_Statewide_B3_2018")
        info = streamer.info(key)
        name, deepest = "tileset.json", None
        while name:
            document = json.loads(streamer.file(key, name).read_bytes())
            tile, name = document["root"], None
            while True:
                inside = [child for child in tile.get("children", [])
                          if _holds(child["boundingVolume"]["region"], LON, LAT)]
                if not inside:
                    break
                tile = inside[0]
            uri = tile["content"]["uri"]
            if uri.endswith(".json"):
                name = uri
            else:
                deepest = uri
        points, _rgb, table, _ = read_pnts(streamer.file(key, deepest).read_bytes())
        self.assertGreater(table["POINTS_LENGTH"], 1000)
        # Terrarium (USGS's NED) has the ground there at 2,064 ft, 629 m NAVD88,
        # so about 599 m above the ellipsoid; the canopy is a little above it.
        self.assertTrue(580 < float(np.median(points[:, 2])) < 640)
        self.assertLess(info.width(names.piece_node(survey.name, deepest[:-5], secret=SECRET)), 400)


def _holds(region, lon, lat):
    west, south, east, north = (math.degrees(v) for v in region[:4])
    return west <= lon <= east and south <= lat <= north


if __name__ == "__main__":
    unittest.main()
