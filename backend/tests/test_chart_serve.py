"""Serving the VFR chart tiles (docs/VFR_CHARTS.md §7; C2): the tile and coverage routes, overzoom, the transparent tile, the
connection registry, ``/api/config``'s ``charts``, the ArcGIS fallback table, and the Data tab's coverage layers (§5.6).

In CI's list (``.github/workflows/backend.yaml``): no app, no database, no FAA. A build is a small MBTiles made here with the
coverage fixture C0's prototype wrote (``contracts/fixtures/charts/coverage.json``), listed in a ``state.json`` of its own.
"""
import io
import json
import logging
import math
import shutil
import sqlite3
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from flask import Flask
from PIL import Image

BACKEND_DIR = Path(__file__).resolve().parents[1]
for _path in (BACKEND_DIR, BACKEND_DIR / "tests"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from charts import catalog, serve, state  # noqa: E402
from data_admin import layers  # noqa: E402
from openapi_check import load_spec, validate  # noqa: E402
from routes.chart_routes import chart_bp  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402

CONTRACTS = BACKEND_DIR.parent / "contracts"
COVERAGE = json.loads((CONTRACTS / "fixtures" / "charts" / "coverage.json").read_text())
ARCGIS = json.loads((CONTRACTS / "fixtures" / "charts" / "arcgis_vfr.json").read_text())
SPEC = load_spec()
NOW = datetime(2026, 11, 5, 12, tzinfo=timezone.utc)
LIVE, OTHER = "2026-10-29.1", "2026-10-29.2"

# Tiles of Atlanta, inside the fixture's bounds: level 11, and its children and grandchildren.
Z11 = (11, 540, 816)


def webp(color=None, *, quarters=None, alpha=255):
    """A 512-px WebP: one colour, or four (top left, top right, bottom left, bottom right)."""
    im = Image.new("RGBA", (512, 512), (*color, alpha) if color else (0, 0, 0, 0))
    if quarters:
        for i, c in enumerate(quarters):
            im.paste((*c, alpha), ((i % 2) * 256, (i // 2) * 256, (i % 2) * 256 + 256, (i // 2) * 256 + 256))
    if alpha == 255:
        im = im.convert("RGB")
    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=90)
    return buf.getvalue()


def tile_at(lon, lat, z):
    """The tile (x, y from the north) a point lies in."""
    n = 1 << z
    return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)


def pixels(data):
    with Image.open(io.BytesIO(data)) as im:
        return im.convert("RGBA").copy()


def make_build(root, name, tiles, coverage=None):
    """A build's folder: ``tiles`` as {(z, x, y from the north): bytes}, written as MBTiles writes them (rows from the south)."""
    folder = state.build_dir(root, name)
    folder.mkdir(parents=True)
    cov = dict(coverage or COVERAGE, build=name)
    (folder / "coverage.json").write_text(json.dumps(cov))
    with closing(sqlite3.connect(folder / "vfr.mbtiles")) as db:
        db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
        db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
        for (z, x, y), data in tiles.items():
            db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, x, (1 << z) - 1 - y, data))
        db.commit()
    return folder


def listed(root, name, now=NOW):
    state.register(root, name, {"cycle": name.split(".")[0], "effective": COVERAGE["effective"], "expires": COVERAGE["expires"],
                                "made": "2026-11-01T00:00:00Z", "by": "Ann", "bytes": 1, "tiles": 1, "held_back": []}, now=now)


class ServeCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.clock = [1000.0]
        self.registry = serve.Registry(clock=lambda: self.clock[0])
        self.addCleanup(self.registry.close_all)
        for target, name, value in ((serve, "REGISTRY", self.registry), (state, "root", lambda environ=None: self.root)):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        app = Flask(__name__)
        app.register_blueprint(chart_bp)
        app.register_blueprint(config_bp)
        self.client = app.test_client()
        self.red, self.blue = (220, 30, 30), (30, 30, 220)
        self.tiles = {Z11: webp(quarters=[self.red, self.blue, (30, 200, 30), (240, 240, 240)])}

    def tile(self, z, x, y, build=LIVE):
        return self.client.get(f"/charts/vfr/{build}/{z}/{x}/{y}.webp")

    def assert_tile(self, response):
        self.assertEqual(response.status_code, 200, response.data[:200])
        self.assertEqual(response.headers["Cache-Control"], "public, max-age=31536000, immutable")
        self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(response.mimetype, "image/webp")
        self.assertNotIn("X-Chart-Build", response.headers)

    def assert_out_of_range(self, response):
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.headers["Cache-Control"], "public, max-age=86400")
        self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")
        self.assertNotIn("X-Chart-Build", response.headers)
        self.assertEqual(response.get_json(), {"error": "out_of_range"})

    def assert_gone(self, response):
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertEqual(response.headers["X-Chart-Build"], "gone")
        self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(response.get_json(), {"error": "build_gone"})


class TileTests(ServeCase):
    def setUp(self):
        super().setUp()
        self.tiles[(12, 1081, 1632)] = webp((230, 200, 30))
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)

    def test_a_stored_tile_is_served_whole_from_its_row_counted_from_the_south(self):
        with closing(sqlite3.connect(state.build_dir(self.root, LIVE) / "vfr.mbtiles")) as db:
            self.assertEqual(db.execute("SELECT tile_row FROM tiles WHERE zoom_level = 11").fetchall(), [((1 << 11) - 1 - 816,)])
        response = self.tile(*Z11)
        self.assert_tile(response)
        self.assertEqual(response.data, self.tiles[Z11])

    def test_ground_with_nothing_stored_at_or_below_level_11_is_the_shared_transparent_tile(self):
        for z, x, y in ((11, 541, 816), (8, 67, 102), (5, 8, 12)):
            response = self.tile(z, x, y)
            self.assert_tile(response)
            self.assertEqual(response.data, serve.TRANSPARENT)
        im = pixels(serve.TRANSPARENT)
        self.assertEqual(im.size, (512, 512))
        self.assertEqual(im.getchannel("A").getextrema(), (0, 0))

    def test_past_level_11_a_tile_not_stored_is_its_nearest_stored_ancestor_enlarged(self):
        # (12, 1080, 1632) is the top-left quarter of the level-11 tile: red, enlarged twice.
        response = self.tile(12, 1080, 1632)
        self.assert_tile(response)
        im = pixels(response.data)
        self.assertEqual(im.size, (512, 512))
        r, g, b, a = im.getpixel((256, 256))
        self.assertTrue(r > 180 and g < 70 and b < 70 and a == 255, (r, g, b, a))
        # (12, 1080, 1633), the bottom-left quarter (a column even, a row odd): green, never the top right's blue.
        r, g, b, _ = pixels(self.tile(12, 1080, 1633).data).getpixel((256, 256))
        self.assertTrue(g > 150 and r < 90 and b < 90, (r, g, b))
        # The bottom right of the bottom-right quarter, three levels down, is the pale grey, opaque, as RGB.
        im = pixels(self.tile(14, 540 * 8 + 7, 816 * 8 + 7).data)
        self.assertTrue(all(v > 200 for v in im.getpixel((100, 100))[:3]))
        with Image.open(io.BytesIO(self.tile(14, 540 * 8 + 7, 816 * 8 + 7).data)) as raw:
            self.assertEqual(raw.mode, "RGB")

    def test_a_stored_ancestor_nearer_than_level_11_is_the_one_enlarged(self):
        # (12, 1081, 1632) is stored yellow, over the level-11 tile's blue quarter: its children are yellow.
        im = pixels(self.tile(13, 2162, 3264).data)
        r, g, b, _ = im.getpixel((256, 256))
        self.assertTrue(r > 180 and g > 150 and b < 90, (r, g, b))

    def test_an_enlarged_tile_is_kept_and_answered_again_the_same(self):
        first = self.tile(13, 2160, 3264).data
        with patch.object(serve, "enlarge", side_effect=AssertionError("enlarged twice")):
            self.assertEqual(self.tile(13, 2160, 3264).data, first)

    def test_the_enlarged_tiles_kept_are_the_last_used_and_no_more(self):
        a, b, c = (13, 2160, 3264), (13, 2161, 3264), (13, 2160, 3265)
        with patch.object(serve, "ENLARGED_KEPT", 2):
            for asked in (a, b, a, c):                  # a used again after b, so b is the one let go for c
                self.assert_tile(self.tile(*asked))
            self.assertEqual(len(self.registry._enlarged), 2)
            with patch.object(serve, "enlarge", side_effect=AssertionError("enlarged again")):
                self.assert_tile(self.tile(*a))
                self.assert_tile(self.tile(*c))
            with patch.object(serve, "enlarge", wraps=serve.enlarge) as made:
                self.assert_tile(self.tile(*b))
            self.assertEqual(made.call_count, 1)

    def test_ground_with_no_stored_ancestor_down_to_level_11_is_transparent_past_it(self):
        response = self.tile(13, 2170, 3270)
        self.assert_tile(response)
        self.assertEqual(response.data, serve.TRANSPARENT)

    def test_an_ancestor_with_nothing_drawn_under_the_tile_gives_the_transparent_tile(self):
        self.registry.close_all()
        shutil.rmtree(state.build_dir(self.root, LIVE))
        make_build(self.root, LIVE, {Z11: webp((50, 50, 50), alpha=0), (10, 270, 408): webp(self.red)})
        self.assertEqual(self.tile(12, 1080, 1632).data, serve.TRANSPARENT)

    def test_outside_the_levels_or_the_bounds_or_a_tiles_numbers_is_out_of_range(self):
        for z, x, y in ((4, 4, 6), (15, 540 * 16, 816 * 16), (11, 2048, 816), (11, 540, 2048), (8, 130, 80)):
            self.assert_out_of_range(self.tile(z, x, y))
        for path in ("-1/0/0", "08/67/102", "8/x/102", "8/67/1.5", "8/67/12345678"):
            self.assert_out_of_range(self.client.get(f"/charts/vfr/{LIVE}/{path}.webp"))

    def test_a_tile_across_each_edge_of_the_bounds_is_served_and_the_next_one_out_is_not(self):
        w, s, e, n = COVERAGE["bounds"]
        mid_lon, mid_lat = (w + e) / 2, (s + n) / 2
        for (lon, lat), (dx, dy) in (((w + 1e-3, mid_lat), (-1, 0)), ((e - 1e-3, mid_lat), (1, 0)),
                                     ((mid_lon, n - 1e-3), (0, -1)), ((mid_lon, s + 1e-3), (0, 1))):
            for z in (5, 11, 14):
                x, y = tile_at(lon, lat, z)
                self.assert_tile(self.tile(z, x, y))
                self.assert_out_of_range(self.tile(z, x + dx, y + dy))

    def test_numbers_past_the_grid_are_out_of_range_even_where_the_bounds_go_round_the_world(self):
        make_build(self.root, OTHER, {}, dict(COVERAGE, bounds=[-180.0, -85.05, 180.0, 85.05]))
        listed(self.root, OTHER)
        self.assert_tile(self.tile(5, 31, 10, build=OTHER))
        self.assert_out_of_range(self.tile(5, 32, 10, build=OTHER))

    def test_a_build_not_listed_or_not_a_builds_name_is_gone_and_kept_by_no_cache(self):
        self.assert_gone(self.tile(*Z11, build="2026-10-29.9"))
        self.assert_gone(self.tile(*Z11, build="latest"))
        self.assert_gone(self.client.get("/charts/vfr/2026-10-29.9/coverage.json"))
        # Gone before its numbers are looked at: a client asking an old build gets the refusal that sends it to the config.
        self.assert_gone(self.client.get("/charts/vfr/2026-10-29.9/x/0/0.webp"))

    def test_a_folder_listed_but_not_on_disk_is_gone_and_logged(self):
        listed(self.root, OTHER)
        with self.assertLogs("charts.serve", logging.WARNING):
            self.assert_gone(self.tile(*Z11, build=OTHER))

    def test_a_file_that_will_not_open_is_gone_never_a_500(self):
        make_build(self.root, OTHER, {})
        (state.build_dir(self.root, OTHER) / "vfr.mbtiles").write_bytes(b"not a database" * 100)
        listed(self.root, OTHER)
        with self.assertLogs("charts.serve", logging.WARNING):
            self.assert_gone(self.tile(*Z11, build=OTHER))
        (state.build_dir(self.root, OTHER) / "coverage.json").write_text("{")
        with self.assertLogs("charts.serve", logging.WARNING):
            self.assert_gone(self.client.get(f"/charts/vfr/{OTHER}/coverage.json"))
        for spoilt in ({"effective": "soon"}, {"expires": None}, {"minLevel": 9, "maxLevel": 8}, {"bounds": [1, 2, 3]}):
            self.registry.close_all()
            (state.build_dir(self.root, OTHER) / "coverage.json").write_text(json.dumps(dict(COVERAGE, **spoilt)))
            (state.build_dir(self.root, OTHER) / "vfr.mbtiles").unlink()
            shutil.copy(state.build_dir(self.root, LIVE) / "vfr.mbtiles", state.build_dir(self.root, OTHER) / "vfr.mbtiles")
            with self.assertLogs("charts.serve", logging.WARNING):
                self.assert_gone(self.tile(*Z11, build=OTHER))

    def test_a_server_with_no_chart_folder_has_no_builds(self):
        with patch.object(state, "root", lambda environ=None: None):
            self.assert_gone(self.tile(*Z11))
            self.assert_gone(self.client.get(f"/charts/vfr/{LIVE}/coverage.json"))

    def test_coverage_is_the_builds_own_file_cached_for_good(self):
        response = self.client.get(f"/charts/vfr/{LIVE}/coverage.json")
        self.assert_tile_headers_json(response)
        self.assertEqual(response.data, (state.build_dir(self.root, LIVE) / "coverage.json").read_bytes())
        self.assertEqual(validate(SPEC, SPEC["components"]["schemas"]["ChartCoverage"], response.get_json()), [])

    def assert_tile_headers_json(self, response):
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["Cache-Control"], "public, max-age=31536000, immutable")
        self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(response.mimetype, "application/json")


class RegistryTests(ServeCase):
    def setUp(self):
        super().setUp()
        for name in (LIVE, OTHER):
            make_build(self.root, name, self.tiles)
            listed(self.root, name)
        state.switch(self.root, LIVE, "Ann", NOW)

    def test_one_connection_per_build_shared_by_every_request(self):
        self.tile(*Z11)
        first = self.registry.build(self.root, LIVE)
        self.tile(*Z11)
        self.assertIs(self.registry.build(self.root, LIVE), first)
        self.assertEqual(self.registry.open_names(self.root), {LIVE})

    def test_a_build_deleted_has_its_connection_closed_at_the_next_request_and_is_gone(self):
        self.assert_tile(self.tile(*Z11, build=OTHER))
        held = self.registry.build(self.root, OTHER)
        state.delete_build(self.root, OTHER, "Ann", NOW)
        self.assert_tile(self.tile(*Z11))           # any request: the state changed, so OTHER's connection is closed
        self.assertTrue(held.closed)
        self.assertEqual(self.registry.open_names(self.root), {LIVE})
        self.assert_gone(self.tile(*Z11, build=OTHER))
        self.assert_gone(self.client.get(f"/charts/vfr/{OTHER}/coverage.json"))

    def test_a_connection_unused_for_ten_minutes_is_closed_and_opened_again_when_asked(self):
        self.tile(*Z11)
        self.tile(*Z11, build=OTHER)
        old = self.registry.build(self.root, OTHER)
        self.clock[0] += serve.IDLE_S - 1
        self.tile(*Z11)                             # a sweep: LIVE used again, OTHER not yet idle long enough
        self.assertFalse(old.closed)
        self.clock[0] += serve.SWEEP_S - 1
        self.tile(*Z11)                             # no sweep yet: the last was less than a minute ago
        self.assertFalse(old.closed)
        self.clock[0] += 1
        self.tile(*Z11)                             # a sweep: OTHER idle for more than ten minutes
        self.assertTrue(old.closed)
        self.assertEqual(self.registry.open_names(self.root), {LIVE})
        self.assert_tile(self.tile(*Z11, build=OTHER))
        self.assertIsNot(self.registry.build(self.root, OTHER), old)

    def test_a_connection_idle_for_exactly_ten_minutes_is_closed(self):
        self.tile(*Z11, build=OTHER)                # used at 1000, as the registry last swept
        old = self.registry.build(self.root, OTHER)
        self.clock[0] += serve.IDLE_S
        self.tile(*Z11)
        self.assertTrue(old.closed)

    def test_a_connection_closed_between_finding_it_and_reading_it_is_opened_again(self):
        real = self.registry.build
        stale = real(self.root, LIVE)
        stale.close()                               # as another thread's sweep, or a change of state, would just after
        handed = iter([stale])
        with patch.object(self.registry, "build", side_effect=lambda where, name: next(handed, None) or real(where, name)):
            self.assertEqual(self.registry._read(self.root, LIVE, lambda b: b.stored(*Z11)), self.tiles[Z11])

    def test_a_connection_closed_while_a_request_held_it_is_opened_again(self):
        found = self.registry.build(self.root, LIVE)
        found.close()
        self.assertEqual(self.tile(*Z11).data, self.tiles[Z11])

    def test_requests_from_many_threads_while_builds_are_deleted_and_made_again_never_fail_otherwise(self):
        errors, stop = [], threading.Event()

        def read():
            while not stop.is_set():
                try:
                    self.registry.tile(self.root, OTHER, *Z11)
                except serve.Gone:
                    pass
                except Exception as error:  # noqa: BLE001 — what this test is for
                    errors.append(error)
                    return

        threads = [threading.Thread(target=read) for _ in range(4)]
        for t in threads:
            t.start()
        try:
            for i in range(20):
                self.registry.close_all()
                self.registry.tile(self.root, LIVE, *Z11)
        finally:
            stop.set()
            for t in threads:
                t.join()
        self.assertEqual(errors, [])


class ConfigTests(ServeCase):
    def config(self):
        response = self.client.get("/api/config")
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(validate(SPEC, SPEC["components"]["schemas"]["Config"], body), [])
        return body

    def test_no_charts_without_a_live_build(self):
        self.assertNotIn("charts", self.config())
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)
        self.assertNotIn("charts", self.config())                  # listed, not yet switched to
        with patch.object(state, "root", lambda environ=None: None):
            self.assertNotIn("charts", self.config())

    def test_the_live_build_with_its_bounds_and_the_regions_left_to_arcgis(self):
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        charts = self.config()["charts"]["vfr"]
        self.assertEqual(charts, {
            "build": LIVE, "cycle": "2026-10-29",
            "tiles": f"/charts/vfr/{LIVE}/{{z}}/{{x}}/{{y}}.webp", "tileSize": 512,
            "minZoom": 5, "maxZoom": 14,
            "effective": "2026-10-29T09:01:00Z", "expires": "2026-12-24T09:01:00Z",
            "coverage": f"/charts/vfr/{LIVE}/coverage.json",
            "bounds": COVERAGE["bounds"],
            "fallback": [box for _, box in serve.fallback_regions()],
        })
        # The URLs it names answer.
        self.assert_tile(self.client.get(charts["tiles"].format(z=11, x=540, y=816)))
        self.assertEqual(self.client.get(charts["coverage"]).status_code, 200)

    def test_a_scheduled_switch_whose_time_has_passed_is_named_without_anything_written(self):
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        dec = dict(COVERAGE, cycle="2026-12-24", effective="2026-12-24T09:01Z", expires="2027-02-19T09:01Z")
        make_build(self.root, "2026-12-24.1", self.tiles, dec)
        listed(self.root, "2026-12-24.1")
        state.schedule(self.root, "2026-12-24.1", "Ann", NOW)
        written = (self.root / state.STATE).read_bytes()
        with patch.object(state, "now_utc", lambda: datetime(2026, 12, 24, 9, 1, tzinfo=timezone.utc)):
            charts = self.config()["charts"]["vfr"]
        self.assertEqual((charts["build"], charts["cycle"], charts["expires"]),
                         ("2026-12-24.1", "2026-12-24", "2027-02-19T09:01:00Z"))
        self.assertEqual((self.root / state.STATE).read_bytes(), written)

    def test_a_region_the_live_build_reaches_is_not_left_to_arcgis(self):
        honolulu = json.loads(json.dumps(COVERAGE))
        honolulu["charts"][0]["outline"] = {"type": "Polygon", "coordinates": [[[-158.5, 21.0], [-157.5, 21.0], [-157.5, 21.8],
                                                                                [-158.5, 21.8], [-158.5, 21.0]]]}
        make_build(self.root, LIVE, self.tiles, honolulu)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        regions = dict(serve.fallback_regions())
        self.assertEqual(self.config()["charts"]["vfr"]["fallback"],
                         [regions[name] for name in ("alaska", "western-aleutians", "marianas", "american-samoa")])

    def test_a_live_build_the_state_does_not_describe_leaves_charts_out(self):
        make_build(self.root, LIVE, self.tiles)
        (self.root / state.STATE).write_text(json.dumps({"live": LIVE, "builds": {LIVE: "?"}}))
        self.assertNotIn("charts", self.config())

    def test_a_live_build_whose_files_will_not_open_leaves_charts_out(self):
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        (state.build_dir(self.root, LIVE) / "coverage.json").unlink()
        with self.assertLogs("charts.serve", logging.WARNING):
            self.assertNotIn("charts", self.config())

    def test_the_spec_catches_a_charts_field_gone_wrong(self):
        make_build(self.root, LIVE, self.tiles)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        body = self.config()
        schema = SPEC["components"]["schemas"]["Config"]
        for spoil in (lambda v: v.pop("fallback"), lambda v: v.update(tiles="https://elsewhere/{z}/{x}/{y}.png"),
                      lambda v: v.update(bounds=[1, 2, 3]), lambda v: v.update(surprise=1), lambda v: v.update(tileSize=256)):
            spoilt = json.loads(json.dumps(body))
            spoil(spoilt["charts"]["vfr"])
            self.assertTrue(validate(SPEC, schema, spoilt))


class CoverageSpecTests(unittest.TestCase):
    """``ChartCoverage`` in the spec, held to the recorded coverage C0's prototype wrote (§4.5); a spec that cannot fail proves nothing."""

    schema = SPEC["components"]["schemas"]["ChartCoverage"]

    def test_the_recorded_coverage_conforms(self):
        self.assertEqual(validate(SPEC, self.schema, COVERAGE), [])

    def test_a_coverage_gone_wrong_is_caught(self):
        for spoil in (lambda c: c.pop("bounds"), lambda c: c.update(tileSize=256), lambda c: c.update(surprise=1),
                      lambda c: c["charts"][0].update(tier="wac"), lambda c: c["charts"][0]["edition"].pop("expires"),
                      lambda c: c["charts"][0].update(kept=None), lambda c: c.update(effective="2026-10-29"),
                      lambda c: c["charts"][0]["outline"].update(type="MultiPolygon"),
                      lambda c: c["tiles"].update({"12": "many"})):
            spoilt = json.loads(json.dumps(COVERAGE))
            spoil(spoilt)
            self.assertTrue(validate(SPEC, self.schema, spoilt))


class FallbackTests(unittest.TestCase):
    """The table of §4.6, pinned to ArcGIS's tiles as measured (``contracts/scripts/arcgis_vfr.py``)."""

    def test_the_table_is_the_regions_measured(self):
        self.assertEqual(ARCGIS["level"], serve.FALLBACK_LEVEL)
        self.assertEqual([(r["name"], *r["columns"], *r["rows"]) for r in ARCGIS["regions"]], list(serve.FALLBACK_TILES))

    def test_each_rectangle_holds_its_tiles_and_no_more_than_rounding(self):
        for (name, box), region in zip(serve.fallback_regions(), ARCGIS["regions"]):
            exact = region["bounds"]
            self.assertTrue(box[0] <= exact[0] and box[1] <= exact[1] and box[2] >= exact[2] and box[3] >= exact[3], name)
            self.assertTrue(all(abs(a - b) < 1e-6 for a, b in zip(box, exact)), name)

    def test_each_edge_was_fetched_inside_and_found_past_it(self):
        for name, x0, x1, y0, y1 in serve.FALLBACK_TILES:
            region = next(r for r in ARCGIS["regions"] if r["name"] == name)
            edges = {p["edge"] for p in region["probes"]}
            self.assertEqual(edges, {"north", "south", "west", "east"} - ({"west"} if x0 == 0 else set())
                             - ({"east"} if x1 == 4095 else set()), name)
            for probe in region["probes"]:
                (ix, iy, inside), (ox, oy, outside) = probe["inside"], probe["outside"]
                self.assertEqual((inside, outside), (200, 404), name)
                self.assertTrue(x0 <= ix <= x1 and y0 <= iy <= y1, name)
                self.assertFalse(x0 <= ox <= x1 and y0 <= oy <= y1, name)

    def test_no_region_reaches_a_conus_chart_and_none_is_left_out_of_a_conus_build(self):
        boxes = []
        for chart in catalog.CHARTS:
            outline = json.loads((BACKEND_DIR / "charts" / "outlines" / f"{chart.id}.geojson").read_text())
            boxes.append(serve._box(outline.get("geometry", outline)))
        self.assertEqual(serve.fallback(boxes), [box for _, box in serve.fallback_regions()])

    def test_a_region_a_build_reaches_leaves_the_list_by_itself(self):
        honolulu = [-158.5, 21.0, -157.5, 21.8]
        names = [name for name, box in serve.fallback_regions() if box in serve.fallback([honolulu])]
        self.assertEqual(names, ["alaska", "western-aleutians", "marianas", "american-samoa"])

    def test_a_chart_that_only_touches_a_region_leaves_it_to_arcgis(self):
        hawaii = dict(serve.fallback_regions())["hawaii"]
        w, s, e, n = hawaii
        for touching in ([w - 1, s, w, n], [e, s, e + 1, n], [w, n, e, n + 1], [w, s - 1, e, s]):
            self.assertIn(hawaii, serve.fallback([touching]), touching)

    def test_a_rectangle_is_rounded_outward(self):
        z = serve.FALLBACK_LEVEL
        with patch.object(serve, "FALLBACK_TILES", (("odd", 1, 1, 1, 1),)):
            ((_, box),) = serve.fallback_regions()
        exact = [serve._lon(1, z), serve._lat(2, z), serve._lon(2, z), serve._lat(1, z)]
        self.assertTrue(box[0] < exact[0] and box[1] < exact[1] and box[2] > exact[2] and box[3] > exact[3], (box, exact))
        self.assertTrue(all(abs(a - b) < 1e-6 for a, b in zip(box, exact)))

    def test_alaska_is_split_at_the_antimeridian(self):
        regions = dict(serve.fallback_regions())
        self.assertEqual(regions["alaska"][0], -180.0)
        self.assertEqual(regions["western-aleutians"][2], 180.0)


def edition(start, end):
    return {"start": start, "end": end, "effective": f"{start}T09:01Z",
            "expires": (datetime.fromisoformat(end) + timedelta(days=1)).strftime("%Y-%m-%dT09:01Z")}


class CoverageLayerTests(ServeCase):
    """The Data tab's VFR layers (§5.6): a build's charts by tier, each with its state against now, the builds that hold it, and
    whether the build scheduled next holds another edition. Read from the builds' own coverage.json, as state.json lists them."""

    NEXT = "2026-12-24.1"
    NEXT_EDITION = edition("2026-12-24", "2027-02-18")

    def setUp(self):
        super().setUp()
        live = json.loads(json.dumps(COVERAGE))
        tac = next(c for c in live["charts"] if c["id"] == "atlanta-tac")
        tac.update(kept=True, edition=edition("2026-09-03", "2026-10-28"))     # held back: the last cycle's stands in
        make_build(self.root, LIVE, self.tiles, live)
        listed(self.root, LIVE)
        state.switch(self.root, LIVE, "Ann", NOW)
        ahead = json.loads(json.dumps(COVERAGE))
        ahead.update(cycle="2026-12-24", effective="2026-12-24T09:01Z", expires="2027-02-18T09:01Z")
        ahead["charts"] = [c for c in ahead["charts"] if c["id"] != "baltimore-hel"]     # held back with no copy: left out
        for c in ahead["charts"]:
            c["edition"] = dict(self.NEXT_EDITION)
        held = next(c for c in ahead["charts"] if c["id"] == "washington-hel")
        held.update(kept=True, edition=COVERAGE["charts"][4]["edition"])            # the edition live holds, standing in
        make_build(self.root, self.NEXT, self.tiles, ahead)
        listed(self.root, self.NEXT)
        state.schedule(self.root, self.NEXT, "Ann", NOW)

    def charts(self, name, build=None, now=NOW):
        found = layers.vfr_charts(name, build, now=now)
        return found, {f["properties"]["id"]: f["properties"] for f in found["features"]}

    def test_the_live_build_by_layer(self):
        found, sec = self.charts("vfr-sectional")
        self.assertEqual((found["available"], found["build"], found["role"], found["cycle"], found["next"]),
                         (True, LIVE, "live", "2026-10-29", self.NEXT))
        self.assertEqual(set(sec), {"atlanta-sec", "washington-sec"})
        atlanta = sec["atlanta-sec"]
        self.assertEqual((atlanta["title"], atlanta["tier"], atlanta["scale"], atlanta["state"], atlanta["over"]),
                         ("Atlanta Sectional", "sectional", "1:500,000", "current", False))
        self.assertEqual((atlanta["start"], atlanta["end"], atlanta["effective"], atlanta["expires"]),
                         ("2026-10-29", "2026-12-23", "2026-10-29T09:01Z", "2026-12-24T09:01Z"))
        self.assertEqual((atlanta["shown_from"], atlanta["stored_to"]), (5, 11))
        self.assertEqual((atlanta["dashed"], atlanta["next_edition"]), (True, "2026-12-24"))
        self.assertEqual(atlanta["builds"], f"{self.NEXT} (next), {LIVE} (live)")
        outline = next(c for c in COVERAGE["charts"] if c["id"] == "atlanta-sec")["outline"]
        self.assertEqual(next(f for f in found["features"] if f["properties"]["id"] == "atlanta-sec")["geometry"], outline)

        _, tac = self.charts("vfr-tac")
        self.assertEqual({k: (v["state"], v["over"]) for k, v in tac.items()},
                         {"atlanta-tac": ("kept", True), "baltimore-washington-tac": ("current", False)})

        _, hel = self.charts("vfr-helicopter")                       # the insets are drawn with the helicopter charts
        self.assertEqual(set(hel), {"washington-hel", "baltimore-hel", "washington-inset-hel"})
        self.assertEqual((hel["washington-hel"]["dashed"], hel["washington-hel"]["next_edition"]), (False, "2026-10-29"))
        self.assertEqual((hel["baltimore-hel"]["dashed"], hel["baltimore-hel"]["next_edition"]), (True, None))
        self.assertEqual(hel["baltimore-hel"]["builds"], f"{LIVE} (live)")
        self.assertEqual(hel["washington-inset-hel"]["stored_to"], 14)

    def test_a_build_chosen_ahead_of_its_cycle(self):
        found, sec = self.charts("vfr-sectional", self.NEXT)
        self.assertEqual((found["build"], found["role"], found["next"]), (self.NEXT, "next", None))
        self.assertEqual({v["state"] for v in sec.values()}, {"next"})
        self.assertEqual({v["dashed"] for v in sec.values()}, {False})        # nothing is scheduled after it
        _, hel = self.charts("vfr-helicopter", self.NEXT)
        self.assertEqual(hel["washington-hel"]["state"], "kept")
        self.assertNotIn("baltimore-hel", hel)

    def test_once_the_switch_has_come(self):
        after = datetime(2026, 12, 24, 10, tzinfo=timezone.utc)
        found, sec = self.charts("vfr-sectional", now=after)                  # the switch applied, as every reader does
        self.assertEqual((found["build"], found["role"], found["next"]), (self.NEXT, "live", None))
        self.assertEqual({v["state"] for v in sec.values()}, {"current"})
        found, sec = self.charts("vfr-sectional", LIVE, now=after)
        self.assertEqual(found["role"], "previous")
        self.assertEqual({(v["state"], v["over"]) for v in sec.values()}, {("expired", True)})

    def test_at_the_switch_itself(self):
        at = datetime(2026, 12, 24, 9, 1, tzinfo=timezone.utc)
        _, sec = self.charts("vfr-sectional", now=at)                         # the next build, live from this instant
        self.assertEqual({v["state"] for v in sec.values()}, {"current"})
        _, sec = self.charts("vfr-sectional", LIVE, now=at)
        self.assertEqual({(v["state"], v["over"]) for v in sec.values()}, {("expired", True)})

    def test_a_coverage_that_is_not_one_or_is_too_large_is_not_read(self):
        path = state.build_dir(self.root, LIVE) / "coverage.json"
        whole = json.dumps(COVERAGE)
        with patch.object(layers, "COVERAGE_MAX_BYTES", len(whole) + 32):
            for text in ("[]", '{"charts": 5}', whole + " " * 64):            # the last still JSON when cut at the bound
                path.write_text(text)
                found = layers.vfr_charts("vfr-sectional", now=NOW)
                self.assertEqual(found["reason"], f"Build {LIVE}'s coverage.json could not be read.", text[:20])
            path.write_text(whole)
            self.assertTrue(layers.vfr_charts("vfr-sectional", now=NOW)["available"])

    def test_nothing_to_show_is_said_and_a_build_not_listed_is_refused(self):
        for name in ("2026-10-29.9", "../x", "", "2026-10-29.1/.."):
            with self.assertRaises(layers.BuildNotListed):
                layers.vfr_charts("vfr-sectional", name, now=NOW)
        with patch.object(state, "root", lambda environ=None: None):
            self.assertEqual(layers.vfr_charts("vfr-tac", now=NOW),
                             {"type": "FeatureCollection", "features": [], "available": False,
                              "reason": "CHART_TILES_DIR is not set on the API."})
        (state.build_dir(self.root, LIVE) / "coverage.json").write_text("{")
        found = layers.vfr_charts("vfr-tac", now=NOW)
        self.assertEqual((found["available"], found["reason"]), (False, f"Build {LIVE}'s coverage.json could not be read."))
        self.assertTrue(self.charts("vfr-tac", self.NEXT)[0]["available"])          # another build is read all the same
        (self.root / state.STATE).write_text("{")
        found = layers.vfr_charts("vfr-tac", now=NOW)
        self.assertFalse(found["available"])
        self.assertTrue(found["reason"])

    def test_no_build_live(self):
        other = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, other, True)
        make_build(other, OTHER, self.tiles)
        listed(other, OTHER)
        with patch.object(state, "root", lambda environ=None: other):
            self.assertEqual(layers.vfr_charts("vfr-sectional", now=NOW)["reason"], "No build is live: choose one in the Charts card.")
            self.assertEqual(len(layers.vfr_charts("vfr-sectional", OTHER, now=NOW)["features"]), 2)


class SameAsTheBuildTests(unittest.TestCase):
    def test_tile_size_quality_and_top_level_are_the_builds(self):
        from charts import build
        self.assertEqual((serve.TILE, serve.QUALITY, serve.TOP), (build.TILE, build.QUALITY, build.TOP))
        self.assertEqual(serve.tms_row(11, 816), build.tms_row(11, 816))
        self.assertEqual(catalog.TIERS["sectional"][1], serve.TOP)


if __name__ == "__main__":
    unittest.main()
