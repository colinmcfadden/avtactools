"""A brief's figures and strip charts on the FAA's own charts (docs/VFR_CHARTS.md §8; C3): one source per figure, read with no HTTP, and
ArcGIS's service wherever the charts cannot draw the whole of it.

In CI's list (``.github/workflows/backend.yaml``): no app, no database, no FAA. A build is a small MBTiles with the coverage fixture.
"""
import copy
import io
import json
import shutil
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

from PIL import Image

BACKEND_DIR = Path(__file__).resolve().parents[1]
for _path in (BACKEND_DIR, BACKEND_DIR / "tests"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from brief import figures  # noqa: E402
from charts import serve, state  # noqa: E402
from test_chart_serve import COVERAGE, LIVE, NOW, OTHER, listed, make_build, tile_at, webp  # noqa: E402

BRIEF = json.loads((BACKEND_DIR.parent / "contracts" / "fixtures" / "brief" / "compile.json").read_text(encoding="utf-8"))
MODEL = BRIEF["cases"][0]["expected"]
STRIP = MODEL["strips"][0]
EDITION = "29 Oct – 23 Dec 2026"
EXPIRES = state.instant(COVERAGE["expires"])

CHART, OTHER_CHART, ARCGIS = (200, 60, 40), (40, 180, 70), (40, 60, 200)
# Areas of the fixture's charts: Alabama has the Atlanta sectional alone, Atlanta its TAC, Baltimore a helicopter chart, Washington the inset.
POST = [[33.45, -87.05], [33.55, -86.95]]
TAC_POST = [[33.7, -84.45], [33.8, -84.35]]
HELICOPTER_POST = [[39.15, -76.65], [39.25, -76.55]]
INSET_POST = [[38.85, -77.05], [38.89, -77.01]]
HAWAII = [[21.25, -157.95], [21.35, -157.85]]
BETWEEN_THE_CHARTS = [[34.0, -80.55], [34.1, -80.45]]


def around(lon, lat, z, colour, reach=4):
    """A build's tiles of one colour round a place, as far as a figure or a strip's mosaic reads them."""
    x, y = tile_at(lon, lat, z)
    data = webp(colour)
    return {(z, x + i, y + j): data for i in range(-reach, reach + 1) for j in range(-reach, reach + 1)}


def png(colour, size=256):
    out = io.BytesIO()
    Image.new("RGB", (size, size), colour).save(out, "PNG")
    return out.getvalue()


def strip_at(lat, lon, **changes):
    strip = copy.deepcopy(STRIP)
    strip.update(centre=[lat, lon], **changes)
    strip.pop("grid", None)
    return strip


def near(found, wanted, within=14):
    return all(abs(a - b) <= within for a, b in zip(found, wanted))


class FigureCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.addCleanup(serve.REGISTRY.close_all)
        figures._cache.clear()
        self.addCleanup(figures._cache.clear)
        self.asked = []

        class Answer:
            content = png(ARCGIS)

            @staticmethod
            def raise_for_status():
                return None

        def get(url, timeout):
            self.asked.append(url)
            return Answer

        for target, name, value in ((state, "root", lambda environ=None: self.root), (state, "now_utc", lambda: NOW),
                                    (figures._session, "get", get)):
            patcher = patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def live(self, tiles, name=LIVE, coverage=None):
        make_build(self.root, name, tiles, coverage)
        listed(self.root, name)
        state.switch(self.root, name, "Ann", NOW)

    def draw(self, bounds, source="sectional"):
        figure = figures.draw({"bounds": bounds, "kind": "route"}, MODEL["geometry"], source)
        with Image.open(io.BytesIO(figure["jpeg"])) as image:
            figure["middle"] = image.convert("RGB").getpixel((image.width // 2, image.height // 2))
        return figure

    def strip(self, strip, source="sectional"):
        drawn = figures.draw_strip(strip, source)
        with Image.open(io.BytesIO(drawn["jpeg"])) as image:
            drawn["middle"] = image.convert("RGB").getpixel((image.width // 2, image.height // 2))
        return drawn

    def assert_charts(self, figure, words=f"FAA Atlanta Sectional, {EDITION}", colour=CHART):
        self.assertEqual(self.asked, [])
        self.assertTrue(near(figure["middle"], colour), figure["middle"])
        self.assertIn(words, figure["svg"])
        self.assertNotIn("fetched", figure["svg"])
        self.assertNotIn("FAA VFR sectional chart", figure["svg"])

    def assert_arcgis(self, figure):
        self.assertTrue(self.asked)
        self.assertTrue(all("tiles.arcgis.com" in url for url in self.asked))
        self.assertTrue(near(figure["middle"], ARCGIS), figure["middle"])
        self.assertIn("FAA VFR sectional chart", figure["svg"])
        self.assertNotIn("fetched", figure["svg"])


class TheThreePlaces(FigureCase):
    """The plan's own test: a chart, not grey squares, at a sectional-only CONUS post, in Hawaii, and on a server with no charts."""

    def test_a_post_under_a_sectional_alone(self):
        self.live(around(-87, 33.5, 11, CHART))
        figure = self.draw(POST)
        self.assertEqual(figure["zoom"], 11)
        self.assert_charts(figure)

    def test_a_strip_at_that_post(self):
        self.live(around(-87, 33.5, 11, CHART))
        self.assert_charts(self.strip(strip_at(33.5, -87)))

    def test_hawaii(self):
        self.live(around(-87, 33.5, 11, CHART))
        self.assert_arcgis(self.draw(HAWAII))

    def test_a_strip_in_hawaii(self):
        self.live(around(-87, 33.5, 11, CHART))
        self.assert_arcgis(self.strip(strip_at(21.3, -157.9)))

    def test_a_server_with_no_charts(self):
        with patch.object(state, "root", lambda environ=None: None):
            self.assert_arcgis(self.draw(POST))
            self.assert_arcgis(self.strip(strip_at(33.5, -87)))

    def test_a_server_with_charts_and_none_live(self):
        make_build(self.root, LIVE, around(-87, 33.5, 11, CHART))
        listed(self.root, LIVE)
        self.assert_arcgis(self.draw(POST))
        self.assert_arcgis(self.strip(strip_at(33.5, -87)))


class WhichSource(FigureCase):
    def setUp(self):
        super().setUp()
        self.live(around(-87, 33.5, 11, CHART))

    def source(self, bounds):
        return figures._figure_source("sectional", bounds)

    def test_is_framed_as_arcgis_frames_it_at_a_post_with_only_a_sectional(self):
        charts, arcgis = figures.Frame(POST, self.source(POST)), figures.Frame(POST, "sectional")
        self.assertEqual((charts.zoom, charts.tile_px, arcgis.zoom, arcgis.tile_px), (11, 512, 12, 256))
        for lat, lon in ((33.5, -87.0), (33.46, -87.04), (33.54, -86.96)):
            for a, b in zip(charts.project(lat, lon), arcgis.project(lat, lon)):
                self.assertAlmostEqual(a, b, places=3)
        self.assertAlmostEqual(charts.metres_per_px(), arcgis.metres_per_px())

    def test_goes_as_far_up_as_the_finest_chart_under_the_area(self):
        self.assertEqual(self.source(POST)["max_zoom"], 11)
        self.assertEqual(self.source(TAC_POST)["max_zoom"], 12)
        self.assertEqual(self.source(HELICOPTER_POST)["max_zoom"], 13)
        self.assertEqual(self.source(INSET_POST)["max_zoom"], 14)

    def test_draws_a_finer_chart_at_its_own_level(self):
        self.live(around(-84.4, 33.75, 12, OTHER_CHART), name=OTHER)
        figure = self.draw([[33.72, -84.45], [33.78, -84.35]])
        self.assertEqual(figure["zoom"], 12)
        self.assert_charts(figure, f"FAA Atlanta TAC +1, {EDITION}", OTHER_CHART)

    def test_names_the_charts_shown_at_the_zoom_it_was_drawn_at(self):
        source = self.source(TAC_POST)
        self.assertEqual(source["attribution"](10), f"FAA Atlanta Sectional, {EDITION}")
        self.assertEqual(source["attribution"](12), f"FAA Atlanta TAC +1, {EDITION}")

    def test_is_the_named_source_for_any_map_but_the_sectional(self):
        with patch.object(serve, "config") as config:
            self.assertEqual(figures._figure_source("satellite", POST), "satellite")
            self.assertEqual(figures._figure_source("topo", POST), "topo")
            self.assertEqual(figures._strip_source("topo", strip_at(33.5, -87)), "topo")
            self.assertEqual(figures._strip_source("satellite", strip_at(33.5, -87)), "satellite")
        config.assert_not_called()

    def test_is_arcgis_for_an_area_that_runs_past_the_build(self):
        self.assertEqual(self.source([[33.4, -88.2], [33.6, -87.8]]), "sectional")   # west
        self.assertEqual(self.source([[31.9, -87.1], [32.1, -86.9]]), "sectional")   # south
        self.assertEqual(self.source([[38.0, -72.1], [38.2, -71.9]]), "sectional")   # east
        self.assertEqual(self.source([[39.9, -75.1], [40.1, -74.9]]), "sectional")   # north

    def test_takes_an_area_up_to_the_build_edge(self):
        self.assertIsInstance(self.source([[32.0, -88.0], [32.1, -87.9]]), dict)

    def test_is_arcgis_where_no_chart_of_the_build_is_under_the_area(self):
        self.assertEqual(self.source(BETWEEN_THE_CHARTS), "sectional")

    def test_is_arcgis_for_an_area_touching_a_place_arcgis_draws(self):
        real = serve.config(self.root)
        fallback = dict(real["vfr"], fallback=[[-87.0, 33.5, -86.0, 34.0]])
        with patch.object(serve, "config", return_value={"vfr": fallback}):
            self.assertEqual(self.source(POST), "sectional")
        beside = dict(real["vfr"], fallback=[[-86.95, 33.5, -86.0, 34.0], [-87.05, 33.55, -86.95, 34.0],
                                         [-88.0, 33.0, -87.05, 34.0], [-87.5, 33.0, -86.5, 33.45]])
        with patch.object(serve, "config", return_value={"vfr": beside}):
            self.assertIsInstance(self.source(POST), dict)

    def test_reads_a_map_panned_round_the_world(self):
        source = self.source([[33.45, 272.95], [33.55, 273.05]])
        self.assertIsInstance(source, dict)
        self.assertEqual(source["max_zoom"], 11)

    def test_is_arcgis_for_a_figure_too_wide_for_the_build_coarsest_level(self):
        real = serve.config(self.root)["vfr"]
        wide = [[33.0, -87.5], [34.0, -86.0]]
        at_zoom = figures.choose_zoom(wide, figures.WIDTH_PX, figures.HEIGHT_PX, 512, 11)
        with patch.object(serve, "config", return_value={"vfr": dict(real, minZoom=at_zoom)}):
            self.assertIsInstance(self.source(wide), dict)
        with patch.object(serve, "config", return_value={"vfr": dict(real, minZoom=at_zoom + 1)}):
            self.assertEqual(self.source(wide), "sectional")

    def test_is_arcgis_where_the_build_cannot_be_read(self):
        for failure in (serve.Gone("gone"), ValueError("not JSON")):
            with patch.object(serve.REGISTRY, "coverage", side_effect=failure):
                self.assertEqual(self.source(POST), "sectional")
        with patch.object(serve.REGISTRY, "coverage", return_value=b"{}"):
            self.assertEqual(self.source(POST), "sectional")

    def test_is_arcgis_for_a_strip_that_runs_past_the_build(self):
        # Its length goes along its course: run east and west it leaves the build's west edge, run north and south it does not.
        self.assertEqual(figures._strip_source("sectional", strip_at(33.5, -87.95, course=90)), "sectional")
        self.assertIsInstance(figures._strip_source("sectional", strip_at(33.5, -87.95, course=0)), dict)
        self.assertEqual(figures._strip_source("sectional", strip_at(32.05, -87, course=180)), "sectional")
        self.assertIsInstance(figures._strip_source("sectional", strip_at(32.05, -87, course=270)), dict)

    def test_measures_a_strip_in_degrees_of_its_own_latitude(self):
        # 10.75 km to the west is 0.097° of longitude at the equator and 0.116° at this latitude, and the build ends at 88° W.
        self.assertIsInstance(figures._strip_source("sectional", strip_at(33.5, -87.87, course=90)), dict)
        self.assertEqual(figures._strip_source("sectional", strip_at(33.5, -87.89, course=90)), "sectional")


class ReadingTheTiles(FigureCase):
    def test_a_stored_tile_is_read_through_the_tile_route_function(self):
        self.live(around(-87, 33.5, 11, CHART))
        with patch.object(serve.REGISTRY, "tile", wraps=serve.REGISTRY.tile) as tile:
            self.draw(POST)
        self.assertTrue(tile.called)
        self.assertTrue(all(call.args[:2] == (self.root, LIVE) and call.args[2] == 11 for call in tile.call_args_list))

    def test_where_a_chart_has_nothing_the_ground_shows_and_not_black(self):
        tiles = {key: serve.TRANSPARENT for key in around(-87, 33.5, 11, CHART)}
        self.live(tiles)
        figure = self.draw(POST)
        self.assertTrue(near(figure["middle"], figures.GROUND), figure["middle"])
        self.assertNotIn("fetched", figure["svg"])

    def test_a_frame_that_runs_off_the_build_is_not_a_missing_tile(self):
        self.live(around(-87.97, 32.03, 11, CHART))
        figure = self.draw([[32.01, -87.99], [32.05, -87.95]])
        self.assertTrue(near(figure["middle"], CHART), figure["middle"])
        self.assertNotIn("fetched", figure["svg"])

    def test_a_build_deleted_under_the_figure_is_a_tile_that_did_not_come(self):
        self.live(around(-87, 33.5, 11, CHART))
        with patch.object(serve.REGISTRY, "tile", side_effect=serve.Gone("gone")):
            figure = self.draw(POST)
        self.assertIn("could not be fetched", figure["svg"])
        self.assertTrue(near(figure["middle"], figures.GROUND), figure["middle"])
        self.assertEqual(self.asked, [])

    def test_a_tile_of_one_build_is_never_taken_for_another_build_tile(self):
        self.live(around(-87, 33.5, 11, CHART))
        self.assert_charts(self.draw(POST))
        self.live(around(-87, 33.5, 11, OTHER_CHART), name=OTHER)
        self.assert_charts(self.draw(POST), colour=OTHER_CHART)

    def test_a_chart_tile_is_never_taken_for_arcgis_tile_of_the_same_address(self):
        self.live(around(-87, 33.5, 11, CHART))
        self.draw(POST)
        frame = figures.Frame(POST, "sectional")
        x, y = tile_at(-87, 33.5, 11)
        image = figures._fetch_tile("sectional", figures.SOURCES["sectional"]["url"], 11, x, y)
        self.assertTrue(near(image.getpixel((10, 10)), ARCGIS), image.getpixel((10, 10)))
        self.assertEqual(frame.tile_px, 256)

    def test_a_tile_read_twice_is_read_once(self):
        self.live(around(-87, 33.5, 11, CHART))
        with patch.object(serve.REGISTRY, "tile", wraps=serve.REGISTRY.tile) as tile:
            self.draw(POST)
            first = tile.call_count
            self.draw(POST)
        self.assertEqual(tile.call_count, first)


class WhatIsPrinted(unittest.TestCase):
    charts = {chart["id"]: chart for chart in COVERAGE["charts"]}
    IN_CYCLE = NOW

    def words(self, *ids, zoom=14, now=None):
        return figures._chart_attribution([self.charts[i] for i in ids], zoom, now or self.IN_CYCLE)

    def test_a_chart_and_its_edition(self):
        self.assertEqual(self.words("atlanta-sec"), f"FAA Atlanta Sectional, {EDITION}")

    def test_the_finest_chart_first_and_how_many_more(self):
        self.assertEqual(self.words("atlanta-sec", "atlanta-tac"), f"FAA Atlanta TAC +1, {EDITION}")

    def test_only_the_charts_shown_at_the_figure_zoom(self):
        self.assertEqual(self.words("atlanta-sec", "atlanta-tac", zoom=10), f"FAA Atlanta Sectional, {EDITION}")
        self.assertEqual(self.words("atlanta-sec", "atlanta-tac", zoom=11), f"FAA Atlanta TAC +1, {EDITION}")

    def test_says_when_the_edition_ran_out_at_its_instant(self):
        self.assertEqual(self.words("atlanta-sec", now=EXPIRES - timedelta(seconds=1)), f"FAA Atlanta Sectional, {EDITION}")
        self.assertEqual(self.words("atlanta-sec", now=EXPIRES), "FAA Atlanta Sectional, expired 23 Dec 2026")

    def test_one_edition_after_another(self):
        older = copy.deepcopy(self.charts["atlanta-sec"])
        older["edition"] = {"start": "2026-09-03", "end": "2026-10-28", "effective": "2026-09-03T09:01Z", "expires": "2026-10-29T09:01Z"}
        found = figures._chart_attribution([self.charts["atlanta-tac"], older], 14, NOW)
        self.assertEqual(found, f"FAA Atlanta TAC, {EDITION}; Atlanta Sectional, expired 28 Oct 2026")

    def test_a_year_twice_only_when_an_edition_crosses_one(self):
        chart = copy.deepcopy(self.charts["atlanta-sec"])
        chart["edition"] = {"start": "2026-12-24", "end": "2027-02-18", "effective": "2026-12-24T09:01Z", "expires": "2027-02-19T09:01Z"}
        self.assertEqual(figures._chart_attribution([chart], 14, NOW), "FAA Atlanta Sectional, 24 Dec 2026 – 18 Feb 2027")

    def test_a_strip_names_it_too(self):
        source = {"attribution": lambda zoom: f"at {zoom}", "tile_px": 512}
        frame = type("F", (), {"source": source, "zoom": 11})
        self.assertEqual(figures._attribution(frame), "at 11")
        self.assertEqual(figures._attribution(type("F", (), {"source": figures.SOURCES["sectional"], "zoom": 11})), "FAA VFR sectional chart")


class UnderTheArea(unittest.TestCase):
    triangle = {"outline": {"type": "Polygon", "coordinates": [[[0, 0], [10, 0], [10, 10], [0, 0]]]}}

    def test_an_area_inside_the_outline(self):
        self.assertTrue(figures._under_area(self.triangle, [7, 2, 8, 3]))

    def test_a_vertex_of_the_outline_inside_the_area(self):
        self.assertTrue(figures._under_area(self.triangle, [-1, -1, 0.01, 0.01]))

    def test_inside_the_outline_box_and_not_the_outline(self):
        self.assertFalse(figures._under_area(self.triangle, [1, 8, 2, 9]))

    def test_the_tip_of_a_chart_between_the_points_tried(self):
        sliver = {"outline": {"type": "Polygon", "coordinates": [[[1.5, 1.5], [-5, 1.6], [-5, 1.4], [1.5, 1.5]]]}}
        self.assertTrue(figures._under_area(sliver, [0, 0, 4, 4]))

    def test_a_chart_that_crosses_the_area_with_no_corner_of_either_in_the_other(self):
        ribbon = {"outline": {"type": "Polygon", "coordinates": [[[-1, -10], [1, -10], [1, 10], [-1, 10], [-1, -10]]]}}
        self.assertTrue(figures._under_area(ribbon, [-5, -1, 5, 1]))

    def test_an_area_elsewhere(self):
        self.assertFalse(figures._under_area(self.triangle, [20, 20, 21, 21]))


if __name__ == "__main__":
    unittest.main()
