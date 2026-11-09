"""The place search: NASR and GNIS read into the index, the ranking, the refresh and ``POST /api/places/search``
(docs/PLACE_SEARCH.md).

The NASR and GNIS files here are written by the test, a handful of real rows in the FAA's and USGS's own columns,
so what is read, matched and ranked is the code's own; the FAA's two hosts and USGS's bucket are stood in for.
"""

import csv
import io
import json
import logging
import os
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from auth_rate_limit import clear_rate_limits  # noqa: E402
from models import User, db  # noqa: E402
from places import gnis, index, nasr, refresh, search, store  # noqa: E402
from places.text import fold  # noqa: E402
from routes.place_routes import place_bp  # noqa: E402

CYCLE = "2026/10/29"
APT_COLUMNS = ["EFF_DATE", "SITE_NO", "SITE_TYPE_CODE", "STATE_CODE", "ARPT_ID", "CITY", "COUNTRY_CODE", "ARPT_NAME",
               "OWNERSHIP_TYPE_CODE", "FACILITY_USE_CODE", "LAT_DECIMAL", "LONG_DECIMAL", "ELEV", "ARPT_STATUS", "ICAO_ID"]
NAV_COLUMNS = ["EFF_DATE", "NAV_ID", "NAV_TYPE", "STATE_CODE", "CITY", "COUNTRY_CODE", "NAV_STATUS", "NAME",
               "PUBLIC_USE_FLAG", "LAT_DECIMAL", "LONG_DECIMAL", "ELEV"]
FIX_COLUMNS = ["EFF_DATE", "FIX_ID", "ICAO_REGION_CODE", "STATE_CODE", "COUNTRY_CODE", "LAT_DECIMAL", "LONG_DECIMAL",
               "FIX_USE_CODE"]

AIRPORTS = [
    # Rows of the 10-29-2026 cycle, trimmed to the columns read.
    ["21530.", "A", "GA", "DZJ", "BLAIRSVILLE", "US", "BLAIRSVILLE", "PU", "PU", "34.85508", "-83.99733", "1907.4", "O", ""],
    ["21528.1", "H", "GA", "1GA3", "BLAIRSVILLE", "US", "UNION GENERAL HOSPITAL", "PR", "PR", "34.8817", "-83.9624", "1900", "O", ""],
    ["34601.", "A", "NJ", "1N7", "BLAIRSTOWN", "US", "BLAIRSTOWN", "PU", "PU", "40.97111", "-74.99750", "372", "O", ""],
    ["03524.", "A", "GA", "ATL", "ATLANTA", "US", "HARTSFIELD/JACKSON ATLANTA INTL", "PU", "PU", "33.63672", "-84.42807", "1026", "O", "KATL"],
    ["00144.", "A", "AL", "OZR", "FORT RUCKER", "US", "CAIRNS AAF (FORT RUCKER)", "MR", "PR", "31.27571", "-85.71316", "301", "O", "KOZR"],
    ["99901.", "H", "GA", "9GA9", "DAHLONEGA", "US", "PEÑA PAD", "PR", "PR", "34.53", "-83.98", "1500", "CI", ""],
    ["99902.", "H", "GA", "8GA8", "DAHLONEGA", "US", "ST. MARYS PAD", "PR", "PR", "34.54", "-83.99", "1510", "O", ""],
    # No position: left out, never put at 0°, 0°.
    ["99903.", "A", "GA", "0XX", "NOWHERE", "US", "NOWHERE FIELD", "PU", "PU", "", "", "", "O", ""],
]
NAVAIDS = [
    ["OZR", "VOR/DME", "AL", "FORT RUCKER", "US", "OPERATIONAL IFR", "CAIRNS", "Y", "31.27", "-85.72", "300"],
    ["GSU", "DME", "GA", "ATLANTA", "US", "OPERATIONAL IFR", "ATLANTA", "Y", "33.65", "-84.40", "1000"],
]
FIXES = [
    ["BOBBY", "K7", "GA", "US", "34.6", "-84.1", "WP"],
    ["ATLEE", "K5", "IN", "US", "40.1", "-86.1", "RP"],
    ["VPNEL", "K7", "GA", "US", "34.73", "-83.92", "VFR"],
]


def _csv(columns, rows):
    out = io.StringIO()
    writer = csv.writer(out, quoting=csv.QUOTE_ALL)
    writer.writerow(columns)
    for row in rows:
        writer.writerow([CYCLE] + row)
    return out.getvalue()


def write_zips(folder, airports=AIRPORTS, navaids=NAVAIDS, fixes=FIXES):
    """The three NASR CSV zips, as the FAA names their insides."""
    paths = []
    for name, member, columns, rows in (("APT", "APT_BASE.csv", APT_COLUMNS, airports),
                                        ("NAV", "NAV_BASE.csv", NAV_COLUMNS, navaids),
                                        ("FIX", "FIX_BASE.csv", FIX_COLUMNS, fixes)):
        path = os.path.join(folder, f"29_Oct_2026_{name}_CSV.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(f"{name} DATA LAYOUT.pdf", b"%PDF")
            archive.writestr(member, "﻿" + _csv(columns, rows))
        paths.append(path)
    return paths


GNIS_HEADER = ["feature_id", "feature_name", "feature_class", "state_name", "state_numeric", "county_name",
               "county_numeric", "map_name", "date_created", "date_edited", "prim_lat_dec", "prim_long_dec"]
GNIS = {
    "GA": [
        ["356130", "Blairsville", "Populated Place", "Georgia", "13", "Union", "291", "Blairsville", "", "", "34.8762", "-83.9582"],
        # Civil and Census repeat the towns: left out.
        ["2405232", "City of Blairsville", "Civil", "Georgia", "13", "Union", "291", "Blairsville", "", "", "34.87", "-83.95"],
        ["1867200", "Blairsville CCD", "Census", "Georgia", "13", "Union", "291", "Blairsville", "", "", "34.86", "-83.94"],
        ["354520", "Brasstown Bald", "Summit", "Georgia", "13", "Towns", "281", "Jacks Gap", "", "", "34.8742", "-83.8107"],
        ["332066", "Lake Nottely", "Reservoir", "Georgia", "13", "Union", "291", "Blairsville", "", "", "34.9567", "-84.0921"],
        ["356362", "Nottely River", "Stream", "Georgia", "13", "Union", "291", "Ranger", "", "", "35.0573", "-84.0466"],
        ["2589130", "Blairs Mill (historical)", "Populated Place", "Georgia", "13", "Union", "291", "Coosa Bald", "", "", "34.72", "-83.91"],
        ["2589131", "Nowhere Hill", "Summit", "Georgia", "13", "Union", "291", "", "", "", "0", "0"],
        ["2589132", "Nameless", "Summit", "Georgia", "13", "Union", "291", "", "", "", "", ""],
    ],
    # The river reaches North Carolina: listed there too, under the same feature_id, read once.
    "NC": [
        ["356362", "Nottely River", "Stream", "North Carolina", "37", "Cherokee", "039", "Ranger", "", "", "35.0573", "-84.0466"],
        ["1021474", "Murphy", "Populated Place", "North Carolina", "37", "Cherokee", "039", "Murphy", "", "", "35.0876", "-84.0213"],
    ],
}


def write_gnis(folder, states=GNIS, date=(2026, 11, 2), name="DomesticNames_AllStates_Text.zip"):
    """GNIS's national file, as USGS lays it out: one pipe-delimited text per state, with a byte order mark."""
    path = os.path.join(folder, name)
    with zipfile.ZipFile(path, "w") as archive:
        for state, rows in states.items():
            info = zipfile.ZipInfo(f"Text/DomesticNames_{state}.txt", date_time=(*date, 12, 0, 0))
            body = "|".join(GNIS_HEADER) + "\n" + "".join("|".join(row) + "\n" for row in rows)
            archive.writestr(info, "\ufeff" + body)
        archive.writestr(zipfile.ZipInfo("Text/readme.txt", date_time=(2020, 1, 1, 0, 0, 0)), "not a state")
    return path


class FoldTests(unittest.TestCase):
    def test_case_accents_and_punctuation_fold_away(self):
        self.assertEqual(fold("Peña"), "pena")
        self.assertEqual(fold("St. Marys"), "st marys")
        self.assertEqual(fold("  HARTSFIELD/JACKSON  "), "hartsfield jackson")
        self.assertEqual(fold(None), "")


class NasrTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.folder, ignore_errors=True))

    def places(self):
        return {p["ident"]: p for p in nasr.read(write_zips(self.folder))}

    def test_rows_become_places_with_their_words(self):
        places = self.places()
        self.assertEqual(places["DZJ"]["detail"], "Airport")
        self.assertEqual(places["DZJ"]["elev_ft"], 1907)
        self.assertEqual(places["1GA3"]["detail"], "Heliport, private")
        self.assertEqual(places["9GA9"]["detail"], "Heliport, private, closed")
        self.assertEqual(places["VPNEL"]["detail"], "VFR waypoint")
        self.assertEqual(places["BOBBY"]["kind"], "fix")
        self.assertIsNone(places["BOBBY"]["name"])

    def test_military_owners_are_said_in_place_of_private(self):
        cairns = [p for p in nasr.read(write_zips(self.folder)) if p["kind"] == "airport" and p["ident"] == "OZR"][0]
        self.assertEqual(cairns["detail"], "Airport, Army")
        self.assertEqual(cairns["military"], "Army")
        self.assertEqual(cairns["icao"], "KOZR")

    def test_a_row_with_no_position_is_left_out(self):
        self.assertNotIn("0XX", self.places())

    def test_the_effective_date_is_the_rows(self):
        self.assertEqual(nasr.effective_date(write_zips(self.folder)), "2026-10-29")

    def test_a_missing_file_is_refused(self):
        zips = write_zips(self.folder)
        with self.assertRaisesRegex(nasr.NasrError, "FIX_BASE"):
            nasr.read(zips[:2])

    def test_wrong_columns_are_refused(self):
        path = os.path.join(self.folder, "bad.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("APT_BASE.csv", "A,B\n1,2\n")
        with self.assertRaisesRegex(nasr.NasrError, "APT_BASE.csv has no column"):
            nasr.read([path] + write_zips(self.folder)[1:])


class GnisTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.folder, ignore_errors=True))

    def places(self):
        return list(gnis.read(write_gnis(self.folder)))

    def test_each_feature_is_read_once_with_civil_and_census_left_out(self):
        names = [p["name"] for p in self.places()]
        self.assertEqual(sorted(names), ["Blairs Mill (historical)", "Blairsville", "Brasstown Bald", "Lake Nottely",
                                         "Murphy", "Nottely River"])

    def test_a_feature_is_said_with_its_county_and_state(self):
        bald = [p for p in self.places() if p["name"] == "Brasstown Bald"][0]
        self.assertEqual(bald, {
            "id": "gnis:354520", "source": "gnis", "kind": "summit", "detail": "Summit", "name": "Brasstown Bald",
            "ident": None, "icao": None, "city": None, "county": "Towns", "state": "GA", "lat": 34.8742,
            "lon": -83.8107, "elev_ft": None, "use": None, "status": "open", "military": None, "rank": gnis.RANK_TERRAIN,
        })

    def test_a_historical_name_is_a_place_that_was(self):
        mill = [p for p in self.places() if p["name"].startswith("Blairs Mill")][0]
        self.assertEqual((mill["status"], mill["detail"], mill["kind"]),
                         ("historical", "Populated place, historical", "populated_place"))

    def test_ranks_put_towns_before_terrain_water_and_streams(self):
        ranks = {p["name"]: p["rank"] for p in self.places()}
        self.assertLess(ranks["Blairsville"], ranks["Brasstown Bald"])
        self.assertLess(ranks["Brasstown Bald"], ranks["Lake Nottely"])
        self.assertLess(ranks["Lake Nottely"], ranks["Nottely River"])
        self.assertGreater(ranks["Blairsville"], nasr.RANK_FIX if hasattr(nasr, "RANK_FIX") else 4)

    def test_the_files_date_is_its_states_newest(self):
        self.assertEqual(gnis.file_date(write_gnis(self.folder)), "2026-11-02")

    def test_a_file_with_no_state_or_the_wrong_columns_is_refused(self):
        with self.assertRaisesRegex(gnis.GnisError, "No state"):
            list(gnis.read(write_gnis(self.folder, states={})))
        bad = os.path.join(self.folder, "bad.zip")
        with zipfile.ZipFile(bad, "w") as archive:
            archive.writestr("Text/DomesticNames_GA.txt", "a|b\n1|2\n")
        with self.assertRaisesRegex(gnis.GnisError, "no column"):
            list(gnis.read(bad))


class SearchTests(unittest.TestCase):
    NEAR = (34.80, -83.90)   # Union County, Georgia

    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.mkdtemp()
        path = os.path.join(cls.folder, "places.sqlite")
        index.build(nasr.read(write_zips(cls.folder)), path, {"nasr_cycle": "2026-10-29"})
        cls.connection = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
        cls.connection.row_factory = sqlite3.Row

    @classmethod
    def tearDownClass(cls):
        cls.connection.close()
        __import__("shutil").rmtree(cls.folder, ignore_errors=True)

    def idents(self, text, near=NEAR, limit=8):
        return [row["ident"] for row in search.search(self.connection, text, near, limit)]

    def test_an_identifier_typed_whole_comes_first_whatever_the_distance(self):
        self.assertEqual(self.idents("katl")[0], "ATL")
        self.assertEqual(self.idents("ATL")[0], "ATL")
        self.assertEqual(self.idents("1ga3"), ["1GA3"])
        self.assertEqual(self.idents("bobby")[0], "BOBBY")

    def test_what_is_near_comes_before_what_is_far(self):
        # Blairsville's airport, then the hospital pad (found by its city): both near. Blairstown (NJ), far, after.
        self.assertEqual(self.idents("blairs"), ["DZJ", "1GA3", "1N7"])

    def test_where_all_is_far_names_starting_with_the_text_come_before_a_city_match(self):
        # From central Georgia everything is far: Blairsville's, then Blairstown's, then the pad found by its city.
        self.assertEqual(self.idents("blairs", near=(32.5, -83.5)), ["DZJ", "1N7", "1GA3"])
        # From New Jersey, Blairstown is the near one.
        self.assertEqual(self.idents("blairs", near=(40.9, -75.0)), ["1N7", "DZJ", "1GA3"])

    def test_names_come_before_identifiers_that_start_with_the_text(self):
        # GSU's name is ATLANTA; the fix ATLEE only starts with the text.
        self.assertEqual(self.idents("atl"), ["ATL", "GSU", "ATLEE"])

    def test_accents_and_punctuation_do_not_matter(self):
        self.assertIn("9GA9", self.idents("pena"))
        self.assertEqual(self.idents("st marys"), ["8GA8"])
        self.assertEqual(self.idents("st. marys"), ["8GA8"])

    def test_closed_places_come_after_open_ones(self):
        self.assertEqual(self.idents("dahlonega"), ["8GA8", "9GA9"])

    def test_fixes_need_three_characters(self):
        self.assertNotIn("BOBBY", self.idents("bo"))
        self.assertIn("BOBBY", self.idents("bob"))

    def test_too_little_text_finds_nothing(self):
        self.assertEqual(self.idents("b"), [])
        self.assertEqual(self.idents(" . "), [])

    def test_a_navaid_named_exactly_comes_before_an_airport_that_starts_with_it(self):
        rows = search.search(self.connection, "cairns", self.NEAR)
        self.assertEqual([(r["ident"], r["kind"]) for r in rows], [("OZR", "vordme"), ("OZR", "airport")])

    def test_without_near_the_order_is_by_rank_then_name(self):
        self.assertEqual(self.idents("blairs", near=None), ["1N7", "DZJ", "1GA3"])

    def test_the_answer_is_said_as_the_api_says_it(self):
        row = search.search(self.connection, "dzj", self.NEAR)[0]
        self.assertEqual(search.as_answer(row), {
            "id": "nasr:apt:21530.", "source": "nasr", "kind": "airport", "kindWords": "Airport", "name": "BLAIRSVILLE",
            "ident": "DZJ", "icao": None, "city": "BLAIRSVILLE", "county": None, "state": "GA", "lat": 34.85508,
            "lon": -83.99733,
            "elevationFt": 1907, "use": "public", "status": "open", "military": None,
        })

    def test_the_file_is_fts4_with_the_simple_tokenizer_which_android_reads(self):
        sql = self.connection.execute("SELECT sql FROM sqlite_master WHERE name = 'place_fts'").fetchone()[0]
        self.assertIn("fts4", sql.lower())
        self.assertNotIn("tokenize", sql.lower())
        self.assertEqual(index.read_meta(self.connection)["schema"], str(index.SCHEMA))


class GnisSearchTests(unittest.TestCase):
    """NASR and GNIS in one index: what a crew types finds an airfield first, then the names round them."""
    NEAR = (34.80, -83.90)

    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.mkdtemp()
        path = os.path.join(cls.folder, "places.sqlite")
        places = list(nasr.read(write_zips(cls.folder))) + list(gnis.read(write_gnis(cls.folder)))
        index.build(places, path, {"nasr_cycle": "2026-10-29", "gnis_date": "2026-11-02"})
        cls.connection = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
        cls.connection.row_factory = sqlite3.Row

    @classmethod
    def tearDownClass(cls):
        cls.connection.close()
        __import__("shutil").rmtree(cls.folder, ignore_errors=True)

    def found(self, text, near=NEAR):
        return [row["ident"] or row["name"] for row in search.search(self.connection, text, near, 10)]

    def test_a_town_is_found_by_its_name(self):
        self.assertEqual(self.found("brasstown"), ["Brasstown Bald"])
        self.assertEqual(self.found("murphy"), ["Murphy"])

    def test_the_airfield_and_the_town_of_one_name_come_together_the_town_that_was_after(self):
        self.assertEqual(self.found("blairs"), ["Blairsville", "DZJ", "Blairs Mill (historical)", "1GA3", "1N7"])

    def test_an_identifier_typed_whole_still_comes_first(self):
        self.assertEqual(self.found("dzj"), ["DZJ"])

    def test_a_feature_in_two_states_is_found_once(self):
        # The river's name starts with the text; the lake's only holds it.
        self.assertEqual(self.found("nottely"), ["Nottely River", "Lake Nottely"])

    def test_civil_areas_are_not_found(self):
        self.assertEqual(self.found("city of"), [])

    def test_a_gnis_place_is_said_with_its_county(self):
        row = search.search(self.connection, "brasstown", self.NEAR)[0]
        answer = search.as_answer(row)
        self.assertEqual((answer["kindWords"], answer["county"], answer["state"], answer["elevationFt"], answer["ident"]),
                         ("Summit", "Towns", "GA", None, None))


class Reply:
    def __init__(self, status=200, body=None, data=b"", headers=None):
        self.status_code, self._body, self._data = status, body, data
        self.headers = headers or {}

    def json(self):
        return self._body

    def iter_content(self, chunk_size):
        for start in range(0, len(self._data), chunk_size):
            yield self._data[start:start + chunk_size]

    def close(self):
        pass


class RefreshTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.source = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root, ignore_errors=True))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.source, ignore_errors=True))
        self.zips = {os.path.basename(p): Path(p).read_bytes() for p in write_zips(self.source)}
        self.zips[refresh.GNIS_FILE] = Path(write_gnis(self.source)).read_bytes()
        self.modified = "Mon, 02 Nov 2026 09:00:00 GMT"
        self.asked = []
        self.edition = "10/29/2026"
        # The real checks need a whole cycle and file; a handful of rows will do here.
        for minimum, kinds in ((refresh.MINIMUM, ("airport", "heliport", "fix")),
                               (refresh.MINIMUM_GNIS, ("populated_place", "summit", "stream"))):
            patcher = patch.dict(minimum, dict.fromkeys(kinds, 1), clear=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    def get(self, url, **kwargs):
        self.asked.append((url, kwargs.get("allow_redirects")))
        if url.startswith(refresh.APRA):
            return Reply(body={"edition": [{"editionDate": self.edition}]})
        name = url.rsplit("/", 1)[-1]
        return Reply(data=self.zips[name]) if name in self.zips else Reply(status=404)

    def head(self, url, **kwargs):
        self.asked.append(("HEAD " + url, kwargs.get("allow_redirects")))
        return Reply(headers={"Last-Modified": self.modified}) if refresh.GNIS_FILE in self.zips else Reply(status=404)

    def refresh(self):
        # Built here, so the patched minimums hold; the child process is tried on its own below.
        return refresh.refresh(self.root, get=self.get, head=self.head,
                               run=lambda root, zips, gnis_zip, cycle: refresh.build_here(root, zips, cycle, gnis_zip))

    def test_the_current_cycle_and_gnis_are_fetched_built_and_served(self):
        self.assertEqual(self.refresh(), "built")
        current = store.read_current(self.root)
        self.assertEqual(current, {"build": "2026-10-29.g20261102.s2", "file": "places-2026-10-29.g20261102.s2.sqlite",
                                   "nasr_cycle": "2026-10-29", "gnis_date": "2026-11-02"})
        self.assertEqual([url.rsplit("/", 1)[-1] for url, _ in self.asked[1:]],
                         ["29_Oct_2026_APT_CSV.zip", "29_Oct_2026_NAV_CSV.zip", "29_Oct_2026_FIX_CSV.zip",
                          refresh.GNIS_FILE, refresh.GNIS_FILE])
        self.assertTrue(self.asked[4][0].startswith("HEAD "))
        # No redirect is ever followed.
        self.assertTrue(all(follow is False for _, follow in self.asked))
        self.assertEqual(sorted(os.listdir(self.root)),
                         [".lock", "current.json", "places-2026-10-29.g20261102.s2.sqlite", "sources"])
        self.assertTrue(os.path.exists(os.path.join(self.root, "sources", "gnis", refresh.GNIS_FILE)))

    def test_what_is_already_built_is_not_fetched_again(self):
        self.refresh()
        self.asked.clear()
        self.assertEqual(self.refresh(), "current")
        # The cycle asked, GNIS's date looked at; neither file fetched.
        self.assertEqual(len(self.asked), 2)
        self.assertTrue(self.asked[0][0].startswith(refresh.APRA))
        self.assertEqual(self.asked[1][0], "HEAD " + refresh.GNIS_URL)

    def test_a_new_cycle_is_built_with_the_gnis_file_held(self):
        self.refresh()
        self.edition = "11/26/2026"
        global CYCLE
        saved, CYCLE = CYCLE, "2026/11/26"
        try:
            self.zips.update({os.path.basename(p).replace("29_Oct", "26_Nov"): Path(p).read_bytes()
                              for p in write_zips(tempfile.mkdtemp(dir=self.source))})
        finally:
            CYCLE = saved
        self.asked.clear()
        self.assertEqual(self.refresh(), "built")
        self.assertEqual(store.read_current(self.root)["build"], "2026-11-26.g20261102.s2")
        self.assertNotIn(refresh.GNIS_URL, [url for url, _ in self.asked])
        # Only the cycle being built is kept among the sources.
        self.assertEqual(os.listdir(os.path.join(self.root, "sources", "nasr")), ["2026-11-26"])

    def test_a_changed_gnis_file_is_fetched_and_built(self):
        self.refresh()
        self.modified = "Mon, 09 Nov 2026 09:00:00 GMT"
        self.zips[refresh.GNIS_FILE] = Path(write_gnis(tempfile.mkdtemp(dir=self.source), date=(2026, 11, 9))).read_bytes()
        self.assertEqual(self.refresh(), "built")
        self.assertEqual(store.read_current(self.root)["gnis_date"], "2026-11-09")

    def test_gnis_that_cannot_be_fetched_leaves_out_nothing_it_had(self):
        self.refresh()
        self.modified = "Mon, 09 Nov 2026 09:00:00 GMT"
        self.zips[refresh.GNIS_FILE] = b"not a zip"
        with self.assertLogs("places", level=logging.WARNING):
            self.assertEqual(self.refresh(), "current")
        self.assertEqual(store.read_current(self.root)["gnis_date"], "2026-11-02")

    def test_with_no_gnis_ever_fetched_nasr_is_built_alone_until_it_comes(self):
        gnis_zip = self.zips.pop(refresh.GNIS_FILE)
        with self.assertLogs("places", level=logging.WARNING):
            self.assertEqual(self.refresh(), "built")
        self.assertEqual(store.read_current(self.root), {"build": "2026-10-29.s2", "file": "places-2026-10-29.s2.sqlite",
                                                         "nasr_cycle": "2026-10-29"})
        self.zips[refresh.GNIS_FILE] = gnis_zip
        self.assertEqual(self.refresh(), "built")
        self.assertEqual(store.read_current(self.root)["build"], "2026-10-29.g20261102.s2")

    def test_a_failed_download_keeps_the_build_being_served(self):
        self.refresh()
        self.edition = "11/26/2026"
        with self.assertRaisesRegex(refresh.RefreshError, "26_Nov_2026_APT_CSV.zip: 404"):
            self.refresh()
        self.assertEqual(store.read_current(self.root)["nasr_cycle"], "2026-10-29")
        leftovers = [name for _, _, names in os.walk(self.root) for name in names + _
                     if name.startswith(".download-") or name.startswith(".staging-")]
        self.assertFalse(leftovers)

    def test_files_of_another_cycle_are_refused(self):
        self.edition = "11/26/2026"
        self.zips = {name.replace("29_Oct", "26_Nov"): data for name, data in self.zips.items()}
        with self.assertRaisesRegex(refresh.RefreshError, "not the cycle 2026-11-26"):
            self.refresh()
        self.assertIsNone(store.read_current(self.root))

    def test_a_build_far_from_the_last_ones_counts_is_refused(self):
        kinds = {"airport": 4, "heliport": 3, "fix": 3}
        with self.assertRaisesRegex(refresh.RefreshError, "airport: 4 against 100"):
            refresh.check(kinds, {"airport": 100})

    def test_a_build_without_gnis_never_replaces_one_with_it(self):
        with self.assertRaisesRegex(refresh.RefreshError, "populated_place: 0 against 200000"):
            refresh.check({"airport": 4, "heliport": 3, "fix": 3}, {"airport": 4, "populated_place": 200_000})

    def test_a_gnis_file_short_of_its_minimum_is_refused(self):
        with patch.dict(refresh.MINIMUM_GNIS, {"populated_place": 10}):
            with self.assertRaisesRegex(refresh.RefreshError, "not a whole GNIS file"):
                refresh.check({"airport": 4, "heliport": 3, "fix": 3, "populated_place": 2, "summit": 1,
                               "stream": 1}, with_gnis=True)

    def test_the_server_builds_in_a_process_of_its_own(self):
        # The child knows only the real minimums, so a handful of rows is refused there, and said here.
        zips = [self._write(item) for item in self.zips.items() if item[0] != refresh.GNIS_FILE]
        with self.assertRaisesRegex(refresh.RefreshError, "The build failed: .*not a whole NASR cycle"):
            refresh.build_in_child(self.root, zips, None, "2026-10-29")
        self.assertIsNone(store.read_current(self.root))

    def _write(self, item):
        path = os.path.join(self.source, item[0])
        Path(path).write_bytes(item[1])
        return path

    def test_only_the_faas_and_usgss_hosts_over_https(self):
        for url in ("http://nfdc.faa.gov/x.zip", "https://example.com/x.zip", "https://nfdc.faa.gov.evil.com/x.zip",
                    "http://prd-tnm.s3.amazonaws.com/x.zip", "https://other.s3.amazonaws.com/x.zip"):
            with self.assertRaises(refresh.RefreshError):
                refresh.download(url, os.path.join(self.root, "x"), get=self.get)

    def test_a_file_larger_than_any_cycles_is_refused(self):
        with self.assertRaisesRegex(refresh.RefreshError, "larger"):
            refresh.download(refresh.CSV_BASE + "29_Oct_2026_APT_CSV.zip", os.path.join(self.root, "x"),
                             get=self.get, max_bytes=10)

    def test_the_zip_names_follow_the_faas_spelling(self):
        self.assertEqual(refresh.zip_names("2026-01-01")[0], "01_Jan_2026_APT_CSV.zip")

    def test_two_builds_are_kept(self):
        for cycle in ("2026/10/01", "2026/10/29", "2026/11/26"):
            folder = tempfile.mkdtemp(dir=self.source)
            global CYCLE
            saved, CYCLE = CYCLE, cycle
            try:
                refresh.build_from(write_zips(folder), self.root)
            finally:
                CYCLE = saved
        builds = sorted(n for n in os.listdir(self.root) if n.endswith(".sqlite"))
        self.assertEqual(builds, ["places-2026-10-29.s2.sqlite", "places-2026-11-26.s2.sqlite"])


class PlaceFixtureTests(unittest.TestCase):
    """``contracts/fixtures/places/``: the index built from real NASR and GNIS rows, and what it answers, which Android
    is held to (contracts/scripts/place_fixtures.py)."""

    def test_the_committed_index_and_answers_are_what_the_code_makes_today(self):
        import importlib.util
        script = BACKEND_DIR.parent / "contracts" / "scripts" / "place_fixtures.py"
        spec = importlib.util.spec_from_file_location("place_fixtures", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.check(), [], "run: python contracts/scripts/place_fixtures.py write, and read the diff")


class PlaceRouteTests(NativeAuthCase):
    extra_blueprints = (place_bp,)

    def setUp(self):
        super().setUp()
        clear_rate_limits()
        self.root = tempfile.mkdtemp()
        source = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root, ignore_errors=True))
        self.addCleanup(lambda: __import__("shutil").rmtree(source, ignore_errors=True))
        with patch.dict(refresh.MINIMUM, {"airport": 1, "heliport": 1, "fix": 1}, clear=True), \
                patch.dict(refresh.MINIMUM_GNIS, {"populated_place": 1}, clear=True):
            refresh.build_from(write_zips(source), self.root, gnis_zip=write_gnis(source))
        patcher = patch.dict(os.environ, {"PLACES_DIR": self.root})
        patcher.start()
        self.addCleanup(patcher.stop)
        store.forget()
        self.addCleanup(store.forget)
        self.make_account()
        self.head = {"Authorization": f"Bearer {self.login()['access_token']}"}
        with self.app.app_context():
            user = User.query.filter_by(email="pilot@example.com").one()
            user.features = {"place_search": True}
            db.session.commit()

    def ask(self, body, head=None):
        return self.client.post("/api/places/search", json=body, headers=head or self.head)

    def test_it_answers_the_places_nearest_first(self):
        response = self.ask({"q": "blairs", "near": {"lat": 34.8, "lon": -83.9}, "limit": 2})
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["available"], True)
        self.assertEqual((body["nasrCycle"], body["gnisDate"]), ("2026-10-29", "2026-11-02"))
        self.assertEqual([p["ident"] or p["name"] for p in body["places"]], ["Blairsville", "DZJ"])
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_near_is_rounded_to_a_tenth_of_a_degree(self):
        with patch.object(store, "search", wraps=store.search) as spy:
            self.ask({"q": "blairs", "near": {"lat": 34.8312, "lon": -83.9671}})
        self.assertEqual(spy.call_args.args[1], (34.8, -84.0))

    def test_it_needs_the_feature(self):
        with self.app.app_context():
            user = User.query.filter_by(email="pilot@example.com").one()
            user.features = {}
            db.session.commit()
        response = self.ask({"q": "blairs"})
        self.assertEqual((response.status_code, response.get_json()["code"]), (403, "feature_disabled"))

    def test_it_needs_a_session(self):
        self.assertEqual(self.client.post("/api/places/search", json={"q": "blairs"}).status_code, 401)

    def test_a_bad_request_is_refused_in_words(self):
        for body, code in (({}, "invalid_query"), ({"q": 3}, "invalid_query"), ({"q": "x" * 101}, "invalid_query"),
                           ({"q": "a", "limit": 0}, "invalid_limit"), ({"q": "a", "limit": True}, "invalid_limit"),
                           ({"q": "a", "limit": 26}, "invalid_limit")):
            response = self.ask(body)
            self.assertEqual((response.status_code, response.get_json()["code"]), (400, code), body)

    def test_with_no_index_it_says_so(self):
        with patch.dict(os.environ, {"PLACES_DIR": tempfile.mkdtemp()}):
            store.forget()
            body = self.ask({"q": "blairs"}).get_json()
        self.assertEqual(body, {"available": False, "places": []})

    def test_too_many_at_once_wait(self):
        for _ in range(10):
            self.assertEqual(self.ask({"q": "blairs"}).status_code, 200)
        response = self.ask({"q": "blairs"})
        self.assertEqual(response.status_code, 429)
        self.assertIn("Retry-After", response.headers)

    def test_what_was_searched_for_is_never_logged(self):
        with self.assertLogs(level=logging.DEBUG) as logs:
            logging.getLogger("places").debug("start")
            self.ask({"q": "blairsville secret lz"})
            with patch.object(search, "search", side_effect=sqlite3.OperationalError("near blairsville")):
                failed = self.ask({"q": "blairsville secret lz"})
        self.assertEqual(failed.status_code, 500)
        self.assertNotIn("blairsville", json.dumps(logs.output).lower())
        self.assertIn("OperationalError", json.dumps(logs.output))


if __name__ == "__main__":
    unittest.main()
