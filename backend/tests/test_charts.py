"""The VFR chart tiles' build (docs/VFR_CHARTS.md; C0): the catalog, the committed outlines, the neatline tool's pure steps, the
build's tile maths, compositing and encoding, a whole build over two small charts made here, and ``contracts/fixtures/charts/
coverage.json`` against the committed outlines.

The FAA's own charts are not here (hundreds of megabytes a cycle): what was measured on them is in the plan, §4.4 and §6.
``UPDATE_CONTRACTS=1`` writes the coverage fixture again from the outlines.
"""
import io
import json
import math
import os
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from datetime import date, datetime, timezone
from types import SimpleNamespace

import numpy as np
import rasterio
from PIL import Image
from pyproj import Transformer
from rasterio.transform import Affine

BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from charts import build, catalog, neatline  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(BACKEND_DIR), "contracts", "fixtures", "charts", "coverage.json")


class CatalogTests(unittest.TestCase):
    def test_the_charts_of_a_conus_build(self):
        tiers = [c.tier for c in catalog.CHARTS]
        self.assertEqual(tiers.count("sectional"), 37)
        self.assertEqual(tiers.count("tac"), 33)
        self.assertEqual(tiers.count("helicopter"), 11)
        self.assertEqual(tiers.count("inset"), 5)
        self.assertEqual(len(catalog.BY_ID), len(catalog.CHARTS), "every chart has an id of its own")
        self.assertEqual(len(catalog.zips()), 74, "a zip that holds several charts is downloaded once")

    def test_tiers_meet_and_are_laid_coarsest_first(self):
        self.assertEqual(tuple(catalog.TIERS), catalog.TIER_ORDER)
        stored = [catalog.TIERS[t][1] for t in catalog.TIER_ORDER]
        shown = [catalog.TIERS[t][0] for t in catalog.TIER_ORDER]
        self.assertEqual(shown[1:], stored[:-1], "each tier is shown from the level the one under it is stored to")
        self.assertEqual(shown[0], build.LOWEST)
        self.assertEqual(stored[0], build.TOP)

    def test_ids_are_the_file_names(self):
        self.assertEqual(catalog.BY_ID["atlanta-sec"].tif, "Atlanta SEC.tif")
        self.assertEqual(catalog.BY_ID["dallas-ft-worth-sec"].zip, "Dallas-Ft_Worth.zip")
        self.assertEqual(catalog.BY_ID["chicago-o-hare-inset-hel"].tier, "inset")
        self.assertEqual(catalog.BY_ID["eastern-long-island-hel"].tier, "tac")

    def test_cycles(self):
        known = date(2026, 10, 29)
        self.assertEqual(catalog.cycle_on(known), known)
        self.assertEqual(catalog.cycle_on(date(2026, 12, 23)), known)
        self.assertEqual(catalog.cycle_on(date(2026, 12, 24)), date(2026, 12, 24))
        self.assertEqual(catalog.cycle_on(date(2026, 10, 28)), date(2026, 9, 3))
        self.assertEqual(catalog.effective_instant(known), datetime(2026, 10, 29, 9, 1, tzinfo=timezone.utc))
        self.assertEqual(catalog.expires_instant(date(2026, 12, 23)), datetime(2026, 12, 24, 9, 1, tzinfo=timezone.utc))
        self.assertEqual(catalog.zip_url(known, "sectional-files", "Atlanta.zip"),
                         "https://aeronav.faa.gov/visual/10-29-2026/sectional-files/Atlanta.zip")


class OutlineTests(unittest.TestCase):
    """The committed outlines (§4.2): one for every chart, each a closed polygon in GeoJSON's order, in CONUS."""

    def test_every_chart_has_its_outline(self):
        files = {f[:-len(".geojson")] for f in os.listdir(neatline.OUTLINES) if f.endswith(".geojson")}
        self.assertEqual(files, set(catalog.BY_ID))

    def test_each_outline_is_what_its_chart_says(self):
        for c in catalog.CHARTS:
            with self.subTest(c.id):
                f = neatline.load(c.id)
                p = f["properties"]
                self.assertEqual((p["id"], p["title"], p["tier"], p["file"]), (c.id, c.title, c.tier, c.tif))
                made = p["made_from"]
                self.assertGreater(made["width"], 0)
                self.assertEqual(len(made["transform"]), 6)
                rings = f["geometry"]["coordinates"]
                self.assertEqual(f["geometry"]["type"], "Polygon")
                for i, ring in enumerate(rings):
                    self.assertGreaterEqual(len(ring), 4)
                    self.assertEqual(ring[0], ring[-1], "closed")
                    self.assertEqual(neatline.ring_area(ring) > 0, i == 0, "outer anticlockwise, holes clockwise")
                    for x, y in ring:
                        self.assertTrue(-125.1 <= x <= -60.5 and 23.9 <= y <= 49.1, (x, y))

    def test_edits_name_charts(self):
        edits = neatline.load_edits()
        self.assertTrue(edits)
        self.assertEqual({k for k in edits if not k.startswith("_")} - set(catalog.BY_ID), set())

    def test_the_committed_form_reads_back(self):
        f = neatline.load("washington-inset-hel")
        self.assertEqual(json.loads(neatline.feature_text(f)), f)
        with open(os.path.join(neatline.OUTLINES, "washington-inset-hel.geojson"), encoding="utf-8") as fh:
            self.assertEqual(fh.read(), neatline.feature_text(f), "outlines are committed as feature_text writes them")


def _source(index: np.ndarray, palette: np.ndarray):
    """A chart's pixels with no file behind them, for the neatline tool's steps."""
    lum = palette @ np.array([0.299, 0.587, 0.114])
    return neatline.Source(index.shape[1], index.shape[0], Affine.identity(), None, index, lum < neatline.DARK,
                           (palette > neatline.WHITE).all(axis=1), palette)


PALETTE = np.zeros((256, 3), np.uint8)
PALETTE[0] = (255, 255, 255)           # paper
PALETTE[1] = (90, 120, 90)             # the chart's body
PALETTE[2] = (20, 20, 20)              # ink


def _inside(ring, x, y) -> bool:
    import cv2

    return cv2.pointPolygonTest(np.asarray(ring, np.float32).reshape(-1, 1, 2), (float(x), float(y)), False) > 0


class NeatlineTests(unittest.TestCase):
    def test_a_ring_cut_to_a_rectangle(self):
        square = [(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)]
        cut = neatline.clip_ring(square, 5, -1, 20, 20)
        self.assertAlmostEqual(abs(neatline.ring_area(cut)), 50.0)
        self.assertTrue(all(x >= 5 for x, _ in cut))
        self.assertEqual(neatline.clip_ring(square, 20, 20, 30, 30), [])

    def test_oriented_follows_geojson(self):
        cw = [(0, 0), (0, 1), (1, 1), (1, 0)]
        self.assertGreater(neatline.ring_area(neatline.oriented(cw, True)), 0)
        self.assertLess(neatline.ring_area(neatline.oriented(cw, False)), 0)
        self.assertEqual(neatline.oriented(cw, True)[0], neatline.oriented(cw, True)[-1])

    def test_a_cut_inside_is_a_hole_and_one_across_the_edge_a_notch(self):
        src = _source(np.ones((300, 400), np.uint8), PALETTE)
        outer = [(10, 10), (390, 10), (390, 290), (10, 290)]
        hole = [(100, 100), (150, 100), (150, 150), (100, 150)]
        self.assertEqual(neatline.apply_cuts(src, outer, [hole]), (outer, [hole]))
        notched, holes = neatline.apply_cuts(src, outer, [[(-10, -10), (50, -10), (50, 50), (-10, 50)]])
        self.assertEqual(holes, [])
        self.assertFalse(_inside(notched, 30, 30))
        self.assertTrue(_inside(notched, 200, 150))
        self.assertTrue(_inside(notched, 30, 200))
        with self.assertRaisesRegex(ValueError, "split the chart in two"):
            neatline.apply_cuts(src, outer, [[(195, -10), (205, -10), (205, 310), (195, 310)]])

    def test_an_inset_is_found_by_its_white_frame(self):
        index = np.ones((450, 600), np.uint8)
        index[150:300, 200:400] = 0                      # the inset's white frame, 8 pixels deep
        index[158:292, 208:392] = 1                      # and what is printed in it
        corners = neatline.find_cut(_source(index, PALETTE), (190, 140, 410, 310))
        want = [(194, 144), (405, 144), (405, 305), (194, 305)]       # the frame's outer edge, 6 pixels out
        for got, w in zip(corners, want):
            self.assertAlmostEqual(got[0], w[0], places=6)
            self.assertAlmostEqual(got[1], w[1], places=6)

    def test_the_paper_a_sheet_keeps_at_its_edge(self):
        index = np.ones((300, 400), np.uint8)
        index[:30] = 0
        index[10:14, 50:60] = 2                          # a label printed over the border is not the body
        src = _source(index, PALETTE)
        ring = [(0, 0), (399, 0), (399, 299), (0, 299)]
        cut, deepest = neatline.paper_border(src, "top", ring)
        self.assertEqual(deepest, 30)
        self.assertTrue(_inside(cut, 200, 20))
        self.assertFalse(_inside(cut, 200, 40))
        self.assertIsNone(neatline.paper_border(_source(np.ones((300, 400), np.uint8), PALETTE), "top", ring))


class TileTests(unittest.TestCase):
    def test_tile_maths(self):
        h = build.HALF
        self.assertEqual(build.tile_box(0, 0, 0), (-h, -h, h, h))
        self.assertEqual(build.tile_box(1, 0, 0), (-h, 0.0, 0.0, h))
        self.assertEqual(build.tms_row(1, 0), 1)
        self.assertEqual(build.tms_row(12, 1640), 2455)
        t = build.tile_transform(1, 1, 1)
        self.assertEqual(t * (0, 0), (0.0, 0.0))
        self.assertAlmostEqual((t * (build.TILE, build.TILE))[0], h)

    def test_tiles_under_a_footprint(self):
        fp = build.footprint(catalog.BY_ID["atlanta-sec"])
        at5 = build.tiles_under(fp, 5)
        self.assertEqual(at5, {(8, 12)})
        self.assertEqual(build.tiles_under(fp, 5, inside=True), set())
        inside = build.tiles_under(fp, 10, inside=True)
        self.assertTrue(inside and inside < build.tiles_under(fp, 10))

    def test_layers_are_laid_coarsest_first(self):
        prints = [build.footprint(catalog.BY_ID[i]) for i in ("washington-hel", "baltimore-washington-tac", "washington-sec")]
        top = build.plan(prints)
        self.assertEqual(sorted(top), [11, 12, 13])
        mid = build.tiles_under(prints[0], 13, inside=True)
        self.assertTrue(mid)
        self.assertEqual(top[13][sorted(mid)[0]], ["washington-sec", "baltimore-washington-tac", "washington-hel"])
        self.assertTrue(all(layers[0] == "washington-sec" and "washington-hel" not in layers for layers in top[11].values()),
                        "a helicopter chart is not drawn at 11")
        self.assertEqual(set(top[12]), build.tiles_under(prints[0], 12) | build.tiles_under(prints[1], 12))

    def test_composite(self):
        def layer(rgb, a):
            d = np.zeros((4, 4, 4), np.uint8)
            d[:3] = np.asarray(rgb, np.uint8)[:, None, None]
            d[3] = a
            return d

        red, blue = layer((255, 0, 0), 255), layer((0, 0, 255), 255)
        self.assertIsNone(build.composite([None, None]))
        self.assertEqual(build.composite([red, blue])[0, 0].tolist(), [0, 0, 255, 255])
        self.assertEqual(build.composite([red, layer((0, 0, 255), 128)])[0, 0].tolist(), [127, 0, 128, 255])
        half = build.composite([layer((0, 0, 255), 128)])
        self.assertEqual(half.shape, (4, 4, 4))
        self.assertEqual(half[0, 0].tolist(), [0, 0, 255, 128])
        over_nothing = build.composite([layer((255, 0, 0), 0), layer((0, 0, 255), 128)])
        self.assertEqual(over_nothing[0, 0].tolist(), [0, 0, 255, 128])

    def test_encode(self):
        self.assertIsNone(build.encode(None))
        self.assertIsNone(build.encode(np.zeros((512, 512, 4), np.uint8)))
        tile = np.full((512, 512, 4), 200, np.uint8)
        tile[..., 3] = 255
        self.assertEqual(Image.open(io.BytesIO(build.encode(tile))).mode, "RGB")
        tile[:100, :, 3] = 0
        im = Image.open(io.BytesIO(build.encode(tile)))
        self.assertEqual((im.format, im.mode), ("WEBP", "RGBA"))
        self.assertEqual(build.decode(build.encode(tile))[0, 0, 3], 0)

    def test_edition_dates(self):
        self.assertEqual(build._metadata_dates("<dt>Beginning_Date:</dt> <dd>20261029</dd> Ending_Date: 20261223"),
                         (date(2026, 10, 29), date(2026, 12, 23)))
        with self.assertRaisesRegex(build.Refused, "no Ending_Date"):
            build._metadata_dates("Beginning_Date: 20261029")
        with self.assertRaisesRegex(build.Refused, "ends before it begins"):
            build._metadata_dates("Beginning_Date: 20261029 Ending_Date: 20261028")


# --------------------------------------------------------------------------------------------- a build over two small charts

LCC = "+proj=lcc +lat_1=33 +lat_2=45 +lat_0=34 +lon_0=-84.5 +datum=NAD83 +units=m +no_defs"
SEC = catalog.Chart("test-sec", "Test Sectional", "sectional", "sectional-files", "Test.zip", "Test SEC.tif")
TAC = catalog.Chart("test-tac", "Test TAC", "tac", "tac-files", "Test_TAC.zip", "Test TAC.tif")
METADATA = "<html><body><dl><dt>Beginning_Date:</dt><dd>20261029</dd><dt>Ending_Date:</dt><dd>20261223</dd></dl></body></html>"


def _chart(folder: str, chart: catalog.Chart, box, scale: int, colours) -> dict:
    """A chart as the FAA zips one: a one-band paletted GeoTIFF in a Lambert projection, the lon/lat box ``box`` drawn on a sheet a
    little larger, scanned at 300 dpi for ``scale``; and its outline, as the neatline tool would commit it."""
    mpp = scale * 0.0254 / build.SCAN_DPI
    w, s, e, n = box
    ring = neatline.densify(neatline.box_ring(w, s, e, n), 0.01)
    xs, ys = Transformer.from_crs(neatline.NAD83, LCC, always_xy=True).transform([p[0] for p in ring], [p[1] for p in ring])
    x0, y1 = min(xs) - 20 * mpp, max(ys) + 20 * mpp
    width, height = int(math.ceil((max(xs) + 20 * mpp - x0) / mpp)), int(math.ceil((y1 - min(ys) + 20 * mpp) / mpp))
    transform = Affine(mpp, 0, x0, 0, -mpp, y1)
    rows, cols = np.mgrid[0:height, 0:width]
    index = (1 + (rows // 24 + 2 * (cols // 24)) % len(colours)).astype(np.uint8)
    tif = os.path.join(folder, chart.tif)
    with rasterio.open(tif, "w", driver="GTiff", width=width, height=height, count=1, dtype="uint8", crs=LCC,
                       transform=transform, photometric="palette") as dst:
        dst.write(index, 1)
        dst.write_colormap(1, {i + 1: (*c, 255) for i, c in enumerate(colours)} | {0: (255, 255, 255, 255)})
    with zipfile.ZipFile(os.path.join(folder, chart.zip), "w", zipfile.ZIP_DEFLATED) as z:
        z.write(tif, chart.tif)
        z.writestr(chart.tif.rsplit(".", 1)[0] + ".htm", METADATA)
    os.remove(tif)
    sheet = SimpleNamespace(width=width, height=height, transform=transform)
    return neatline.feature(chart, sheet, neatline.box_ring(w, s, e, n))


class BuildTests(unittest.TestCase):
    """Steps 3 to 7 over a sectional and a TAC inside it, made here: what C0 ran over the FAA's charts, small enough for a test."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.zips = os.path.join(cls.tmp.name, "zips")
        cls.out = os.path.join(cls.tmp.name, "build")
        os.makedirs(cls.zips)
        cls.outlines = {
            SEC.id: _chart(cls.zips, SEC, (-84.85, 34.25, -84.25, 34.75), 500_000, [(150, 190, 140), (190, 210, 160), (120, 160, 120)]),
            TAC.id: _chart(cls.zips, TAC, (-84.65, 34.42, -84.45, 34.58), 250_000, [(200, 80, 60), (230, 140, 90)]),
        }
        cls.charts = {SEC.id: SEC, TAC.id: TAC}
        cls.lines = []
        cls.report = build.build(cls.zips, cls.out, [SEC.id, TAC.id], processes=1, log=cls.lines.append, charts=cls.charts,
                                 outlines=cls.outlines)
        with open(os.path.join(cls.out, "coverage.json"), encoding="utf-8") as f:
            cls.coverage = json.load(f)
        cls.db = sqlite3.connect(f"file:{os.path.join(cls.out, 'vfr.mbtiles')}?mode=ro", uri=True)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def stored(self, z):
        return {(x, build.tms_row(z, r)) for x, r in
                self.db.execute("SELECT tile_column, tile_row FROM tiles WHERE zoom_level = ?", (z,))}

    def tile(self, z, x, y):
        row = self.db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                              (z, x, build.tms_row(z, y))).fetchone()
        return build.decode(row[0])

    def test_it_validates(self):
        self.assertEqual(self.report["validate"]["refused"], [])
        self.assertTrue(any(line.startswith("Test TAC: tile 12/") for line in self.report["validate"]["notes"]))

    def test_levels_and_counts(self):
        counts = {int(k): v for k, v in self.report["counts"].items()}
        self.assertEqual(sorted(counts), list(range(5, 13)))
        for z, n in counts.items():
            self.assertEqual(len(self.stored(z)), n, z)
        self.assertEqual(self.coverage["tiles"], self.report["counts"])

    def test_rows_are_numbered_from_the_south_in_the_file(self):
        sec, tac = (build.footprint(c, self.outlines[c.id]) for c in (SEC, TAC))
        self.assertLessEqual(self.stored(12), build.tiles_under(tac, 12))
        self.assertLessEqual(self.stored(11), build.tiles_under(sec, 11))
        self.assertEqual(len(self.stored(5)), 1)

    def test_a_tile_inside_the_tac_is_the_tac_at_12_and_the_sectional_below_11(self):
        tac = build.footprint(TAC, self.outlines[TAC.id])
        work = os.path.join(self.out, "work")
        x, y = sorted(build.tiles_under(tac, 12, inside=True))[0]
        got = self.tile(12, x, y)
        self.assertEqual(build.difference(got, build.decode(build.encode(build.reference(
            os.path.join(work, "test-tac.tif"), 12, x, y))))["mean"], 0.0)
        # its parent at 11 has the TAC on it, and a seed of the sectional alone, which level 10 is shrunk from
        self.assertTrue(os.path.exists(build._seed_path(work, 11, x // 2, y // 2)))
        x10, y10 = x // 4, y // 4
        ten = self.tile(10, x10, y10)
        sec, tac_ = (build.reference(os.path.join(work, f"{c.id}.tif"), 10, x10, y10) for c in (SEC, TAC))
        under = tac_[..., 3] == 255                      # where the TAC is drawn at 12, level 10 shows the sectional
        self.assertGreater(under.sum(), 10_000)

        def off(ref):
            return float(np.abs(ten[under][:, :3].astype(int) - ref[under][:, :3].astype(int)).mean())

        self.assertLess(off(sec), 8)
        self.assertGreater(off(tac_), 40)

    def test_the_edge_of_the_coverage_is_transparent(self):
        sec = build.footprint(SEC, self.outlines[SEC.id])
        edges = sorted((build.tiles_under(sec, 11) - build.tiles_under(sec, 11, inside=True)) & self.stored(11))
        self.assertTrue(edges)
        alphas = [self.tile(11, *xy)[..., 3] for xy in edges]
        self.assertTrue(all((a == 0).any() for a in alphas))
        self.assertTrue(any((a == 255).any() for a in alphas))
        self.assertTrue(all((a == 255).all() for a in (self.tile(11, *xy)[..., 3] for xy in build.tiles_under(sec, 11, inside=True))))

    def test_coverage(self):
        c = self.coverage
        self.assertEqual((c["version"], c["build"], c["cycle"]), (1, "2026-10-29.1", "2026-10-29"))
        self.assertEqual((c["effective"], c["expires"]), ("2026-10-29T09:01Z", "2026-12-24T09:01Z"))
        self.assertEqual((c["tileSize"], c["minLevel"], c["maxLevel"]), (512, 5, 12))
        self.assertEqual(c["bounds"], [-84.85, 34.25, -84.25, 34.75])
        sec, tac = c["charts"]
        self.assertEqual((sec["id"], sec["tier"], sec["scale"], sec["shownFrom"], sec["storedTo"]), ("test-sec", "sectional", 500000, 5, 11))
        self.assertEqual((tac["id"], tac["tier"], tac["scale"], tac["shownFrom"], tac["storedTo"]), ("test-tac", "tac", 250000, 11, 12))
        self.assertAlmostEqual(sec["nativeLevel"], 10.57, delta=0.02)
        self.assertAlmostEqual(tac["nativeLevel"] - sec["nativeLevel"], 1.0, delta=0.02)
        self.assertEqual(sec["outline"]["coordinates"][0], [[-84.85, 34.25], [-84.25, 34.25], [-84.25, 34.75], [-84.85, 34.75], [-84.85, 34.25]])
        self.assertEqual(sec["edition"], {"start": "2026-10-29", "end": "2026-12-23", "effective": "2026-10-29T09:01Z",
                                          "expires": "2026-12-24T09:01Z"})
        self.assertFalse(sec["kept"])

    def test_the_file(self):
        meta = dict(self.db.execute("SELECT name, value FROM metadata"))
        self.assertEqual((meta["format"], meta["minzoom"], meta["maxzoom"], meta["version"]), ("webp", "5", "12", "2026-10-29.1"))
        self.assertEqual(self.db.execute("PRAGMA journal_mode").fetchone()[0], "delete")
        self.assertFalse(os.path.exists(os.path.join(self.out, "vfr.mbtiles-wal")))

    def test_a_build_resumed_redoes_nothing(self):
        again = build.build(self.zips, self.out, [SEC.id, TAC.id], processes=1, log=lambda *a: None,
                            charts=self.charts, outlines=self.outlines)
        self.assertEqual(again["times"]["tile_count"], 0)
        self.assertEqual(again["counts"], self.report["counts"])
        self.assertEqual(again["validate"]["refused"], [])

    def test_a_chart_that_is_not_its_outline_is_refused(self):
        moved = json.loads(json.dumps(self.outlines[SEC.id]))
        moved["properties"]["made_from"]["width"] += 1
        with self.assertRaisesRegex(build.Refused, "its outline was made from one of"):
            build.check(self.zips, SEC, moved)
        with self.assertRaisesRegex(build.Refused, "a sectional was refused"):
            build.build(self.zips, os.path.join(self.tmp.name, "refused"), [SEC.id], processes=1, log=lambda *a: None,
                        charts=self.charts, outlines={SEC.id: moved})


class CoverageFixtureTests(unittest.TestCase):
    """``contracts/fixtures/charts/coverage.json`` is C0's prototype build's (7 charts, cycle 2026-10-29). Its outlines must be what
    ``coverage`` makes of the committed ones, and its charts what the catalog says, so a client tested against it is tested against
    what a build serves."""

    def test_the_fixture_is_what_a_build_writes(self):
        with open(FIXTURE, encoding="utf-8") as f:
            text = f.read()
        fixture = json.loads(text)
        checked, prints = [], {}
        for c in fixture["charts"]:
            chart = catalog.BY_ID[c["id"]]
            fp = prints[chart.id] = build.footprint(chart)
            w, s, e, n = fp.box
            lat = build._FROM_MERC.transform((w + e) / 2, (s + n) / 2)[1]
            mpp = 2 * build.HALF * math.cos(math.radians(lat)) / (build.TILE * 2 ** c["nativeLevel"])
            checked.append(build.Checked(chart, "", date.fromisoformat(c["edition"]["start"]),
                                         date.fromisoformat(c["edition"]["end"]), c["scale"], mpp))
        cov = build.coverage(fixture["build"], date.fromisoformat(fixture["cycle"]), checked,
                             {c.chart.id: neatline.load(c.chart.id) for c in checked}, prints,
                             {int(z): n for z, n in fixture["tiles"].items()})
        if os.environ.get("UPDATE_CONTRACTS"):
            with open(FIXTURE, "w", encoding="utf-8") as f:
                f.write(build.coverage_text(cov))
            return
        self.assertEqual(cov, fixture)
        self.assertEqual(text, build.coverage_text(cov))

    def test_the_prototype_covers_every_tier(self):
        with open(FIXTURE, encoding="utf-8") as f:
            fixture = json.load(f)
        self.assertEqual({c["tier"] for c in fixture["charts"]}, set(catalog.TIER_ORDER))
        self.assertEqual((fixture["minLevel"], fixture["maxLevel"]), (5, 14))


if __name__ == "__main__":
    unittest.main()
