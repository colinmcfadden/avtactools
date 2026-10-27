"""What stands on and round an LZ, from the LiDAR (survey/obstacles.py; docs/LZ_SURVEYS.md §4, step L4).

The survey here is made by the tests, as tests/test_lidar_stream.py's are: an EPT octree of a scene laid out round the Ellijay test LZ,
its pieces written with laspy and served from memory in place of AWS, through the stream's own ``Streamer``. Every object in the scene is
where the test can say what must be found: a tree line 200 m past the approach's edge, a tower 400 m past the exit's, a tree in the LZ,
a building 50 m outside it, a lone return (a bird) and noise, over ground that rises 2 m in every 100 m to the north. No USGS data is
committed."""

import base64
import io
import json
import math
import shutil
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

from lidar import coverage, ept, names, stream  # noqa: E402
from survey import obstacles  # noqa: E402
from test_lidar_stream import write_laz  # noqa: E402

LAT, LON = 34.596407, -84.128098           # Ellijay
SECRET = "t" * 40
BASE = "https://aws.example/usgs-lidar-public"
FEET = 3.28084
# The LZ: a square 120 m on a side round the touchdown point; the approach flown east (from the west), the exit flown east.
HALF = 60.0
APPROACH, EXIT = 90.0, 90.0
FRAME = obstacles.Frame(LAT, LON)
BOUNDARY = [list(FRAME.latlon(e, n)) for e, n in ((-HALF, -HALF), (HALF, -HALF), (HALF, HALF), (-HALF, HALF))]


def ground(north):
    return 300.0 + 0.02 * north


def scene():
    """The scene's points in the frame: east, north, height (m) and class."""
    parts = []

    def add(east, north, height_above, cls):
        east, north = np.broadcast_arrays(np.asarray(east, float), np.asarray(north, float))
        parts.append((east.ravel(), north.ravel(), (ground(north) + height_above).ravel(), np.full(east.size, cls, np.uint8)))

    step = 1.5
    for (e0, e1), (n0, n1) in (((-220, 220), (-220, 220)), ((-1120, -40), (-70, 70)), ((40, 1120), (-70, 70))):
        e, n = np.meshgrid(np.arange(e0, e1, step), np.arange(n0, n1, step))
        add(e, n, 0.0, 2)
    for h in (10.0, 20.0, 25.0, 28.0, 30.0):                                   # the tree line, 200 m past the approach's edge
        e, n = np.meshgrid(np.arange(-262.0, -257.5, 1.0), np.arange(-40.0, 40.5, 1.0))
        add(e, n, h, 5)
    for h in np.arange(0.0, 50.5, 2.0):                                         # the tower, 400 m past the exit's edge, 10 m left
        e, n = np.meshgrid([459.0, 460.0, 461.0], [9.0, 10.0, 11.0])
        add(e, n, h, 15)
    angles = np.linspace(0, 2 * math.pi, 12, endpoint=False)
    for h, r in ((8.0, 2.0), (10.0, 1.5), (11.0, 1.0), (12.0, 0.0)):         # a tree in the LZ, 12 m
        add(20.0 + r * np.cos(angles), 30.0 + r * np.sin(angles), np.full(12, h), 5)
    e, n = np.meshgrid(np.arange(-3.0, 3.5, 1.0), np.arange(107.0, 113.5, 1.0))  # a building 47 m outside the north edge, 8 m
    add(e, n, 8.0, 6)
    e, n = np.meshgrid(np.arange(-31.0, -28.5, 0.5), np.arange(-31.0, -28.5, 0.5))   # grass in the LZ, under a metre
    add(e, n, 0.8, 3)
    add([-400.0], [0.0], 80.0, 1)                                               # a bird
    add([-500.0, -520.0], [0.0, 0.0], [100.0, 120.0], 7)                        # noise
    add([-540.0], [5.0], 140.0, 18)
    add(np.linspace(900.0, 905.0, 6), np.full(6, -2000.0), 0.0, 2)              # far from anything measured
    return tuple(np.concatenate([part[i] for part in parts]) for i in range(4))


class FakeSurvey:
    """The scene as USGS publishes a survey: an EPT octree 4,096 m (Mercator) on a side round the LZ, five levels deep, every point in
    the piece of its level that holds it (a sixteenth at the root, half at the deepest), and the index in two pages. ``ground_only``
    classifies nothing but the ground (vegetation and buildings as unclassified), as a survey to 3DEP's base specification may."""

    def __init__(self, year=2018, name=None, ground_only=False):
        self.name = name or f"GA_Obstacles_{year}"
        self.year = year
        self.files = {}
        self.fetched = []
        cx, cy = ept.lonlat_to_mercator(LON, LAT)
        half = 2048.0
        self.bounds = [cx - half, cy - half, 300.0 - half, cx + half, cy + half, 300.0 + half]
        document = {"bounds": self.bounds, "boundsConforming": self.bounds, "dataType": "laszip", "hierarchyType": "json",
                    "points": 0, "span": 128, "srs": {"authority": "EPSG", "horizontal": "3857"}, "version": "1.1.0"}
        self.put("ept.json", json.dumps(document).encode())
        info = ept.Info.read(document)
        east, north, z, cls = scene()
        if ground_only:
            cls = np.where(np.isin(cls, (3, 4, 5, 6)), 1, cls).astype(np.uint8)
        lat, lon = FRAME.latlon(east, north)
        x = np.radians(lon) * ept.MERCATOR_RADIUS
        y = ept.MERCATOR_RADIUS * np.log(np.tan(np.pi / 4.0 + np.radians(lat) / 2.0))
        depth = np.array([0, 1, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4])[np.arange(east.size) % 16]
        pieces = {}
        for d in range(5):
            chosen = np.flatnonzero(depth == d)
            width = (self.bounds[3] - self.bounds[0]) / (1 << d)
            keys = np.stack([((x[chosen] - self.bounds[0]) // width), ((y[chosen] - self.bounds[1]) // width),
                             ((z[chosen] - self.bounds[2]) // width)], axis=1).astype(int)
            for key in {tuple(k) for k in keys}:
                at = chosen[np.all(keys == key, axis=1)]
                pieces[ept.Node(d, *key)] = at
        self.nodes = set(pieces)
        for node, at in pieces.items():
            self.put(f"ept-data/{node}.laz", write_laz(x[at], y[at], z[at], cls[at]))
        # The depth-2 piece under the LZ has a page of its own, with everything under it.
        own = ept.node_at(info, LON, LAT, 2)
        own = next(n for n in self.nodes if n.depth == 2 and (n.x, n.y) == (own.x, own.y))
        under = {n for n in self.nodes if n.depth > 2 and (n.x >> (n.depth - 2), n.y >> (n.depth - 2), n.z >> (n.depth - 2)) ==
                 (own.x, own.y, own.z)}
        root_page = {str(n): int(pieces[n].size) for n in self.nodes - under}
        root_page[str(own)] = -1
        self.put("ept-hierarchy/0-0-0-0.json", json.dumps(root_page).encode())
        self.put(f"ept-hierarchy/{own}.json", json.dumps({str(n): int(pieces[n].size) for n in under | {own}}).encode())

    def put(self, path, data):
        self.files[f"{BASE}/{self.name}/{path}"] = data

    def fetch(self, url, limit):
        self.fetched.append(url)
        if url not in self.files:
            raise stream.StreamError("USGS has no such file.", code="not_found", status=404)
        return self.files[url]

    def index(self):
        ring = [[LON - 1, LAT - 1], [LON + 1, LAT - 1], [LON + 1, LAT + 1], [LON - 1, LAT + 1], [LON - 1, LAT - 1]]
        survey = coverage.Survey(name=self.name, url="", points=1000, year=self.year)
        return [(survey, {"type": "Polygon", "coordinates": [ring]})]


def profiles():
    """The terrain's slope profiles as terrain_provider makes them, centred on the LZ: 61 steps of 10 m each way along every 2°."""
    return {"center": [LAT, LON], "stepM": 10.0, "halfLengthM": 300.0, "headingStepDeg": 2,
            "lines": [{"heading": h, "elevationsFt": [984.0] * 61, "lz": [-HALF, HALF]} for h in range(0, 180, 2)]}


_SURVEYS = {}


class ObstacleCase(unittest.TestCase):
    survey_year = 2018
    survey_name = None
    ground_only = False

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="obstacles-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        which = (self.survey_year, self.survey_name, self.ground_only)
        if which not in _SURVEYS:
            _SURVEYS[which] = FakeSurvey(*which)                                   # a second or two to write: made once
        self.survey = _SURVEYS[which]
        self.survey.fetched = []
        self.streamer = stream.Streamer(self.root, fetch=self.survey.fetch, geoid=lambda lons, lats: [-30.35] * len(lons),
                                        index=self.survey.index(), base=BASE, secret=SECRET)

    def measure(self, approach=APPROACH, exit_=EXIT, source=None, boundary=BOUNDARY):
        return obstacles.measure_obstacles((LAT, LON), boundary, approach, exit_, profiles(),
                                           source=self.streamer if source is None else source)


class SceneTests(ObstacleCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = None

    def found(self):
        if SceneTests.shared is None:
            SceneTests.shared = self.measure()
        return SceneTests.shared

    def test_it_names_its_survey(self):
        found = self.found()
        self.assertEqual((found["status"], found["words"]), ("measured", None))
        self.assertEqual(found["survey"], {"name": "GA_Obstacles_2018", "year": 2018})

    def test_the_approach_clears_the_tree_line_from_the_lz_s_edge(self):
        approach = self.found()["approach"]
        self.assertEqual((approach["headingDeg"], approach["bearingDeg"]), (90.0, 270.0))   # flown east, so it lies west
        self.assertAlmostEqual(approach["edge"]["fromTargetM"], HALF, places=1)
        self.assertAlmostEqual(approach["edge"]["groundFt"], 300.0 * FEET, delta=0.2)
        clear = approach["clearance"]
        # The tree line's top 30 m over the ground under it, its nearest trees 198 m past the edge, each measured from the edge's ground
        # at its own place across the corridor (the ground rises to the side, not along). The bird, 80 m up 340 m out, would need
        # 13.2°: a lone return is not something standing.
        self.assertAlmostEqual(clear["angleDeg"], math.degrees(math.atan2(30.0, 198.0)), delta=0.1)
        self.assertEqual(clear["obstacle"]["kind"], "vegetation")
        self.assertAlmostEqual(clear["obstacle"]["heightFt"], 30.0 * FEET, delta=0.5)
        self.assertAlmostEqual(clear["obstacle"]["fromEdgeM"], 198.0, delta=0.6)
        self.assertEqual(len(approach["bins"]), 100)
        self.assertIsNone(approach["bins"][18]["tallestFt"])                    # 180-190 m past the edge: only ground
        for k in (19, 20):                                                       # the tree line, 198-202 m
            self.assertAlmostEqual(approach["bins"][k]["tallestFt"], 30.0 * FEET, delta=0.5)
        self.assertAlmostEqual(approach["bins"][0]["groundFt"], 300.0 * FEET, delta=0.2)
        self.assertEqual(approach["tallest"]["kind"], "vegetation")

    def test_the_exit_clears_the_tower(self):
        exit_ = self.found()["exit"]
        self.assertEqual(exit_["bearingDeg"], 90.0)
        clear = exit_["clearance"]
        self.assertAlmostEqual(clear["angleDeg"], math.degrees(math.atan2(50.0, 399.0)), delta=0.1)
        self.assertEqual(clear["obstacle"]["kind"], "tower")
        self.assertAlmostEqual(clear["obstacle"]["heightFt"], 50.0 * FEET, delta=0.5)
        self.assertLess(clear["obstacle"]["offsetM"], 0)                         # left of the track, flying it
        self.assertGreater(exit_["fromTarget"]["angleDeg"], 0)
        self.assertLess(exit_["fromTarget"]["angleDeg"], clear["angleDeg"])       # the touchdown point is further from it

    def test_what_stands_in_the_lz_and_round_it(self):
        found = self.found()
        inside = found["inside"]
        self.assertEqual(inside["kind"], "vegetation")
        self.assertAlmostEqual(inside["heightFt"], 12.0 * FEET, delta=0.5)
        self.assertAlmostEqual(inside["bearingDeg"], math.degrees(math.atan2(20, 30)), delta=4)
        self.assertAlmostEqual(inside["distanceM"], math.hypot(20, 30), delta=2.5)
        around = found["around"]
        self.assertEqual(around["kind"], "building")
        self.assertAlmostEqual(around["heightFt"], 8.0 * FEET, delta=0.5)
        self.assertGreaterEqual(around["fromEdgeM"], 46.5)
        self.assertLessEqual(around["fromEdgeM"], 53.5)
        standing = found["standing"]
        self.assertEqual(set(standing), {"overM", "count"})
        self.assertGreater(standing["count"], 0)
        (tree,) = found["trees"]                                                  # the tree, not the building or the grass
        self.assertAlmostEqual(tree["heightFt"], 12.0 * FEET, delta=0.5)

    def test_the_height_map_is_a_picture_of_what_stands(self):
        from PIL import Image

        picture = self.found()["heightMap"]
        self.assertTrue(picture["overlay"].startswith("data:image/png;base64,"))
        image = Image.open(io.BytesIO(base64.b64decode(picture["overlay"].split(",", 1)[1])))
        self.assertEqual(image.mode, "RGBA")
        (south, west), (north, east) = picture["bounds"]
        self.assertLess(south, LAT)
        self.assertGreater(north, LAT)
        alpha = np.asarray(image)[:, :, 3]
        self.assertTrue((alpha > 0).any())
        self.assertGreater((alpha == 0).mean(), 0.9)                               # clear where nothing stands

    def test_the_slope_profile_s_line_has_its_treetops(self):
        tops = self.found()["profileTops"]
        self.assertEqual(tops["heading"], 90)
        self.assertEqual(len(tops["aboveFt"]), 61)
        self.assertAlmostEqual(tops["aboveFt"][4], 30.0 * FEET, delta=0.5)        # the tree line, 260 m west of the middle
        self.assertIsNone(tops["aboveFt"][30])                                     # the LZ's middle: nothing stands there

    def test_only_the_pieces_over_what_is_measured_are_read(self):
        self.measure()
        streamed = {url.rsplit("/", 1)[1][:-4] for url in self.survey.fetched if "/ept-data/" in url}
        self.assertTrue(streamed)
        far = {str(n) for n in self.survey.nodes if n.depth == 4} - streamed
        self.assertTrue(far)                                                       # a piece far from the LZ is never fetched


class CacheTests(ObstacleCase):
    def test_a_piece_is_fetched_once_and_kept(self):
        first = self.measure()
        fetched = [url for url in self.survey.fetched if "/ept-data/" in url]
        self.survey.fetched.clear()
        again = self.measure()                                                     # a preview, then the same measured to keep
        self.assertTrue(fetched)
        self.assertEqual([url for url in self.survey.fetched if "/ept-data/" in url], [])
        self.assertEqual(again, first)
        key = names.survey_key(self.survey.name, secret=SECRET)
        self.assertTrue(list((self.root / key / "laz").glob("*.laz")))

    def test_kept_pieces_are_let_go_with_the_streamed_ones(self):
        self.measure()
        key = names.survey_key(self.survey.name, secret=SECRET)
        self.assertGreater(self.streamer.prune(cap=0), 0)
        self.assertFalse(list((self.root / key / "laz").glob("*.laz")))


class WithoutNumbersTests(ObstacleCase):
    def test_no_heading_no_corridor(self):
        found = self.measure(approach=None, exit_=None)
        self.assertEqual(found["status"], "measured")
        self.assertIsNone(found["approach"])
        self.assertIsNone(found["exit"])
        self.assertIsNone(found["profileTops"])
        self.assertIsNotNone(found["inside"])

    def test_without_streaming_nothing_is_measured(self):
        with patch.object(obstacles, "default_source", return_value=None):
            found = obstacles.measure_obstacles((LAT, LON), BOUNDARY, APPROACH, EXIT, profiles())
        self.assertEqual(found, {"status": "unavailable", "words": obstacles.NO_SERVER})

    def test_no_survey_here(self):
        found = obstacles.measure_obstacles((LAT + 5, LON), BOUNDARY, APPROACH, EXIT, profiles(), source=self.streamer)
        self.assertEqual(found, {"status": "none", "words": obstacles.NO_SURVEY})

    def test_a_piece_that_cannot_be_fetched_is_a_measure_to_try_again(self):
        def broken(url, limit):
            if "/ept-data/" in url:
                raise stream.StreamError("USGS could not be reached.", code="upstream_unavailable", status=502)
            return self.survey.fetch(url, limit)
        self.streamer._fetch = broken
        with self.assertLogs("survey.obstacles", level="ERROR"):
            found = self.measure()
        self.assertEqual((found["status"], found["words"]), ("failed", obstacles.FAILED))
        self.assertEqual(found["survey"]["name"], self.survey.name)


class GroundOnlyTests(ObstacleCase):
    survey_year = 2010

    def test_a_survey_that_classifies_only_the_ground_gives_no_numbers(self):
        found = self.measure()
        self.assertEqual(found["status"], "none")
        self.assertIn("GA_Obstacles_2010 (2010), classifies only the ground", found["words"])
        self.assertFalse([url for url in self.survey.fetched if "/ept-data/" in url])


class YearlessTests(ObstacleCase):
    """A survey whose name gives no year (KY_FullState's, at Fort Campbell) is judged by what its returns are classified as."""

    survey_year = None
    survey_name = "KY_Obstacles"

    def test_one_that_classifies_what_stands_is_measured(self):
        found = self.measure()
        self.assertEqual(found["status"], "measured")
        self.assertEqual(found["survey"], {"name": "KY_Obstacles", "year": None})
        self.assertAlmostEqual(found["approach"]["clearance"]["angleDeg"], math.degrees(math.atan2(30.0, 198.0)), delta=0.1)


class YearlessGroundOnlyTests(ObstacleCase):
    survey_year = None
    survey_name = "KY_Ground"
    ground_only = True

    def test_one_that_classifies_only_the_ground_says_so_without_a_year(self):
        found = self.measure()
        self.assertEqual(found["status"], "none")
        self.assertEqual(found["words"], "The LiDAR here, KY_Ground, classifies only the ground, so no obstacle numbers are given from it.")


class ReadingTests(ObstacleCase):
    """How a reading of USGS's pieces ends: at its first failure, past its time or its returns, on an index that cannot be read."""

    def pieces_read(self):
        self.measure()
        return [url for url in self.survey.fetched if "/ept-data/" in url]

    def test_it_stops_at_the_first_piece_that_cannot_be_fetched(self):
        every = len(self.pieces_read())
        self.assertGreater(every, 2 * obstacles.FETCH_THREADS)
        release, lock, calls = threading.Event(), threading.Lock(), []

        def fetch(url, limit):
            if "/ept-data/" in url:
                with lock:
                    calls.append(url)
                    first = len(calls) == 1
                if first:
                    raise stream.StreamError("USGS could not be reached.", code="upstream_unavailable", status=502)
                release.wait(10)                                                     # the rest hang, as a dead connection does
            return self.survey.fetch(url, limit)

        self.addCleanup(release.set)
        self.root = Path(tempfile.mkdtemp(prefix="obstacles-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.streamer = stream.Streamer(self.root, fetch=fetch, geoid=lambda lons, lats: [-30.35] * len(lons),
                                        index=self.survey.index(), base=BASE, secret=SECRET)
        started = time.monotonic()
        with self.assertLogs("survey.obstacles", level="ERROR"):
            found = self.measure()
        self.assertEqual((found["status"], found["words"]), ("failed", obstacles.FAILED))
        self.assertLess(time.monotonic() - started, 5)                              # not waiting for the pieces still hanging
        self.assertLessEqual(len(calls), obstacles.FETCH_THREADS + 1)               # nor fetching what was still waiting

    def test_a_reading_past_its_time_says_so(self):
        release = threading.Event()
        self.addCleanup(release.set)

        def fetch(url, limit):
            if "/ept-data/" in url:
                release.wait(10)
            return self.survey.fetch(url, limit)

        self.streamer._fetch = fetch
        with patch.object(obstacles, "FETCH_DEADLINE_S", 0.3), self.assertLogs("survey.obstacles", level="WARNING"):
            found = self.measure()
        self.assertEqual((found["status"], found["words"]), ("failed", obstacles.SLOW))

    def test_more_returns_than_a_reading_holds_is_said(self):
        with patch.object(obstacles, "MAX_POINTS", 1000):
            found = self.measure()
        self.assertEqual((found["status"], found["words"]), ("none", obstacles.TOO_LARGE))

    def test_an_index_that_cannot_be_read_is_said_and_not_tried_again(self):
        def fetch(url, limit):
            if url.endswith("/ept-hierarchy/0-0-0-0.json"):
                return b"[]"                                                         # a page that is not an object
            return self.survey.fetch(url, limit)

        self.streamer._fetch = fetch
        with self.assertLogs("survey.obstacles", level="WARNING"):
            found = self.measure()
        self.assertEqual((found["status"], found["words"]), ("none", obstacles.UNREADABLE))

    def test_bytes_that_are_not_a_piece_are_not_kept(self):
        def fetch(url, limit):
            if "/ept-data/" in url:
                return b"<html>Service Unavailable</html>"
            return self.survey.fetch(url, limit)

        self.streamer._fetch = fetch
        with self.assertLogs("survey.obstacles", level="ERROR"):
            found = self.measure()
        self.assertEqual(found["status"], "failed")
        self.assertFalse(list(self.root.rglob("*.laz")))

    def test_a_kept_piece_that_cannot_be_read_is_let_go_and_fetched_again(self):
        import laspy

        first = self.measure()
        kept = sorted(self.root.rglob("*.laz"), key=lambda path: -path.stat().st_size)[0]
        data = kept.read_bytes()
        header = laspy.LasHeader.read_from(io.BytesIO(data))
        kept.write_bytes(data[:header.offset_to_point_data] + b"\0" * 64)           # its header reads; its points do not
        with self.assertLogs("survey.obstacles", level="ERROR"):
            broken = self.measure()
        self.assertEqual(broken["status"], "failed")
        self.assertFalse(kept.exists())                                              # let go of
        self.survey.fetched.clear()
        self.assertEqual(self.measure(), first)
        self.assertEqual(len([url for url in self.survey.fetched if "/ept-data/" in url]), 1)


def points_over(ground, extra=(), noise=0.0, seed=1, density=1.0, corridor_to=-1100.0):
    """Points round the scene's LZ and along its approach's corridor (west of it, out to ``corridor_to``): ground returns scattered at
    ``density`` a square metre with ``noise`` metres of scatter over ``ground(east, north)``, and ``extra`` parts as (east, north, height
    above the ground, class); measured over the ground and rid of lone returns, as a reading is."""
    rng = np.random.default_rng(seed)
    parts = []
    for (e0, e1), (n0, n1) in (((-180.0, 180.0), (-180.0, 180.0)), ((corridor_to, -40.0), (-70.0, 70.0))):
        if e1 <= e0:
            continue
        count = int((e1 - e0) * (n1 - n0) * density)
        e, n = rng.uniform(e0, e1, count), rng.uniform(n0, n1, count)
        parts.append((e, n, ground(e, n) + rng.normal(0.0, noise, count), np.full(count, 2, np.uint8)))
    for e, n, h, cls in extra:
        e, n = np.broadcast_arrays(np.asarray(e, float), np.asarray(n, float))
        e, n = e.ravel(), n.ravel()
        parts.append((e, n, ground(e, n) + np.broadcast_to(np.asarray(h, float), e.shape), np.full(e.size, cls, np.uint8)))
    points = obstacles.Points(*(np.concatenate([part[i] for part in parts]) for i in range(4)))
    points.measure_ground()
    points.drop_lone()
    return points


RING = np.array([[-HALF, -HALF], [HALF, -HALF], [HALF, HALF], [-HALF, HALF]])


def flat(east, north):
    return np.full(np.shape(east), 300.0)


def approach(points, ring=RING):
    return obstacles.corridor(FRAME, points, ring, APPROACH, approach=True)


class CorridorTests(unittest.TestCase):
    """What sets a way's angle (the review's probes, made tests): what stands 2 m or more, and the ground only where it rises."""

    def test_the_ground_s_scatter_and_grass_at_the_edge_need_no_angle(self):
        # The steepest of thousands of returns scattered by 10 cm, or of grass, a centimetre past the edge once read 86-89°.
        rng = np.random.default_rng(3)
        e, n = rng.uniform(-1100.0, -40.0, 60000), rng.uniform(-70.0, 70.0, 60000)
        found = approach(points_over(flat, extra=[(e, n, rng.uniform(0.1, 0.4, e.size), 3)], noise=0.10))
        self.assertEqual(found["clearance"], {"angleDeg": 0.0, "obstacle": None})
        self.assertEqual(found["fromTarget"], {"angleDeg": 0.0, "obstacle": None})
        self.assertIsNone(found["missing"])

    def test_the_ground_where_it_rises_along_the_way_sets_the_angle(self):
        rising = approach(points_over(lambda e, n: 300.0 + np.where(e < -HALF, 0.05 * (-HALF - e), 0.0), noise=0.05))
        self.assertAlmostEqual(rising["clearance"]["angleDeg"], math.degrees(math.atan(0.05)), delta=0.3)
        self.assertEqual(rising["clearance"]["obstacle"]["kind"], "terrain")
        ridge = approach(points_over(lambda e, n: 300.0 + 20.0 * np.exp(-((e + HALF + 300.0) / 40.0) ** 2), noise=0.05))
        self.assertAlmostEqual(ridge["clearance"]["angleDeg"], math.degrees(math.atan2(20.0, 300.0)), delta=0.3)
        obstacle = ridge["clearance"]["obstacle"]
        self.assertAlmostEqual(obstacle["fromEdgeM"], 300.0, delta=10.0)
        self.assertAlmostEqual(obstacle["riseFt"], 20.0 * FEET, delta=2.0)
        falling = approach(points_over(lambda e, n: 300.0 - np.where(e < -HALF, 0.05 * (-HALF - e), 0.0), noise=0.05))
        self.assertEqual(falling["clearance"], {"angleDeg": 0.0, "obstacle": None})

    def test_ground_rising_across_the_corridor_needs_no_angle_nor_draws_a_treetop(self):
        found = approach(points_over(lambda e, n: 300.0 + 0.2 * n, noise=0.05))
        self.assertEqual(found["clearance"], {"angleDeg": 0.0, "obstacle": None})
        for row in found["bins"]:
            self.assertAlmostEqual(row["topFt"], row["groundFt"], delta=1.0)      # the track's ground, not the hillside's

    def test_a_wire_stands_though_its_returns_are_far_apart(self):
        wire = (np.full(16, -HALF - 300.0), np.arange(-30.0, 34.0, 4.0), 20.0, 14)
        found = approach(points_over(flat, extra=[wire]))
        clear = found["clearance"]
        self.assertEqual(clear["obstacle"]["kind"], "wire")
        self.assertAlmostEqual(clear["angleDeg"], math.degrees(math.atan2(20.0, 300.0)), delta=0.1)

    def test_how_much_of_the_corridor_the_survey_covers_is_kept(self):
        found = approach(points_over(flat, corridor_to=-HALF - 300.0))
        self.assertEqual(found["covered"], 30)
        # Past it, 200 m of a lake: water returns (class 9) are the survey's too, and nothing stands on a lake.
        rng = np.random.default_rng(9)
        lake = (rng.uniform(-HALF - 500.0, -HALF - 300.0, 20000), rng.uniform(-60.0, 60.0, 20000), 0.0, 9)
        found = approach(points_over(flat, extra=[lake], corridor_to=-HALF - 300.0))
        self.assertEqual(found["covered"], 50)
        rng = np.random.default_rng(8)
        e, n = rng.uniform(-HALF + 1.0, 180.0, 40000), rng.uniform(-180.0, 180.0, 40000)
        east_only = obstacles.Points(e, n, flat(e, n), np.full(e.size, 2, np.uint8))  # nothing west of the LZ's edge at all
        east_only.measure_ground()
        empty = approach(east_only)
        self.assertEqual((empty["covered"], empty["clearance"], empty["missing"]), (0, None, "no_returns"))

    def test_with_no_ground_at_the_edge_there_is_no_angle_and_why(self):
        def ground(east, north):
            return np.full(np.shape(east), 300.0)

        rng = np.random.default_rng(5)
        e, n = rng.uniform(-1100.0, -100.0, 40000), rng.uniform(-70.0, 70.0, 40000)
        points = obstacles.Points(e, n, ground(e, n), np.full(e.size, 2, np.uint8))  # nothing within 15 m of the edge, inside or out
        points.measure_ground()
        points.drop_lone()
        found = approach(points)
        self.assertEqual((found["clearance"], found["missing"]), (None, "no_edge_ground"))

    def test_the_ground_is_answered_in_the_order_asked(self):
        points = points_over(lambda e, n: 300.0 + 0.01 * e + 0.02 * n)
        rng = np.random.default_rng(7)
        e, n = rng.uniform(-150.0, 150.0, 2000), rng.uniform(-150.0, 150.0, 2000)
        np.testing.assert_allclose(points.ground_at(e, n), 300.0 + 0.01 * e + 0.02 * n, atol=0.05)


class RoundTheLzTests(unittest.TestCase):
    def test_a_building_is_no_tree_and_grass_is_nothing_standing(self):
        building = (*np.meshgrid(np.arange(10.0, 18.0, 0.7), np.arange(10.0, 18.0, 0.7)), 7.0, 6)
        rng = np.random.default_rng(6)
        grass = (rng.uniform(-55.0, 55.0, 6000), rng.uniform(-55.0, 55.0, 6000), rng.uniform(0.1, 0.4, 6000), 3)
        found = obstacles.round_the_lz(FRAME, points_over(flat, extra=[building, grass]), RING)
        self.assertEqual(found["trees"], [])
        self.assertEqual(found["inside"]["kind"], "building")
        self.assertAlmostEqual(found["inside"]["heightFt"], 7.0 * FEET, delta=0.5)
        bare = obstacles.round_the_lz(FRAME, points_over(flat, extra=[grass]), RING)
        self.assertEqual((bare["inside"], bare["standing"]["count"], bare["trees"]), (None, 0, []))

    def test_the_profile_s_treetops_beside_a_narrow_lz_are_not_in_it(self):
        # The review's: an LZ 40 m wide and 160 m long, flown along its length, a 25 m tree line 5 m outside each long side.
        ring = np.array([[-80.0, -20.0], [80.0, -20.0], [80.0, 20.0], [-80.0, 20.0]])
        sides = [(*np.meshgrid(np.arange(-90.0, 91.0, 1.0), [side - 1.0, side, side + 1.0]), 25.0, 5) for side in (-25.0, 25.0)]
        tops = obstacles.profile_tops(FRAME, points_over(flat, extra=sides), ring, profiles(), APPROACH)
        self.assertEqual(tops["aboveFt"][23:38], [None] * 15)                       # within the LZ's span: nothing stands in it
        self.assertAlmostEqual(tops["aboveFt"][21], 25.0 * FEET, delta=0.5)          # past its end, the tree line beyond it


class GeometryTests(unittest.TestCase):
    def test_a_point_is_inside_a_ring_or_not(self):
        ring = np.array([[-1.0, -1.0], [1.0, -1.0], [1.0, 1.0], [-1.0, 1.0]])
        self.assertEqual(list(obstacles.inside(ring, np.array([0.0, 2.0, 0.5]), np.array([0.0, 0.0, -0.5]))), [True, False, True])

    def test_a_line_leaves_the_ring_where_it_last_crosses_it(self):
        ring = np.array([[-1.0, -1.0], [3.0, -1.0], [3.0, 1.0], [-1.0, 1.0]])
        self.assertAlmostEqual(obstacles.leaves(ring, np.array([1.0, 0.0])), 3.0)
        self.assertAlmostEqual(obstacles.leaves(ring, np.array([-1.0, 0.0])), 1.0)
        self.assertEqual(obstacles.leaves(ring + 10, np.array([1.0, 0.0])), 0.0)   # a touchdown point outside: from it

    def test_a_point_s_distance_to_the_ring_s_edge(self):
        ring = np.array([[-1.0, -1.0], [1.0, -1.0], [1.0, 1.0], [-1.0, 1.0]])
        self.assertEqual(list(np.round(obstacles.distance_to_edge(ring, np.array([3.0, 0.0]), np.array([0.0, 0.0])), 6)), [2.0, 1.0])

    def test_a_turned_strip_meets_only_the_boxes_it_crosses(self):
        strip = obstacles.Strip((0.0, 0.0), 45.0, 0.0, 100.0, 5.0)
        corners = strip.corners()
        self.assertTrue(obstacles._meets(corners, (30.0, 30.0, 40.0, 40.0)))
        self.assertFalse(obstacles._meets(corners, (60.0, 0.0, 70.0, 10.0)))   # inside its bounding box, off the strip
