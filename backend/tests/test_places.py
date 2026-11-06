"""The place search: NASR read into the index, the ranking, the cycle refresh and ``POST /api/places/search``
(docs/PLACE_SEARCH.md).

The NASR files here are written by the test, a handful of real rows in the FAA's own columns, so what is read,
matched and ranked is the code's own; the FAA's two hosts are stood in for.
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
from places import index, nasr, refresh, search, store  # noqa: E402
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

    def test_names_starting_with_the_text_nearest_first_then_a_city_match(self):
        # Blairsville's airport (GA) before Blairstown's (NJ); the hospital pad, found by its city, after both.
        self.assertEqual(self.idents("blairs"), ["DZJ", "1N7", "1GA3"])

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
            "ident": "DZJ", "icao": None, "city": "BLAIRSVILLE", "state": "GA", "lat": 34.85508, "lon": -83.99733,
            "elevationFt": 1907, "use": "public", "status": "open", "military": None,
        })

    def test_the_file_is_fts4_with_the_simple_tokenizer_which_android_reads(self):
        sql = self.connection.execute("SELECT sql FROM sqlite_master WHERE name = 'place_fts'").fetchone()[0]
        self.assertIn("fts4", sql.lower())
        self.assertNotIn("tokenize", sql.lower())
        self.assertEqual(index.read_meta(self.connection)["schema"], str(index.SCHEMA))


class Reply:
    def __init__(self, status=200, body=None, data=b""):
        self.status_code, self._body, self._data = status, body, data

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
        self.asked = []
        self.edition = "10/29/2026"
        # The real checks need a whole cycle; a handful of rows will do here.
        patcher = patch.dict(refresh.MINIMUM, {"airport": 1, "heliport": 1, "fix": 1}, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)

    def get(self, url, **kwargs):
        self.asked.append((url, kwargs.get("allow_redirects")))
        if url.startswith(refresh.APRA):
            return Reply(body={"edition": [{"editionDate": self.edition}]})
        name = url.rsplit("/", 1)[-1]
        return Reply(data=self.zips[name]) if name in self.zips else Reply(status=404)

    def test_the_current_cycle_is_fetched_built_and_served(self):
        self.assertEqual(refresh.refresh(self.root, get=self.get), "built")
        current = store.read_current(self.root)
        self.assertEqual(current, {"build": "2026-10-29.s1", "file": "places-2026-10-29.s1.sqlite", "nasr_cycle": "2026-10-29"})
        self.assertEqual([url.rsplit("/", 1)[-1] for url, _ in self.asked[1:]],
                         ["29_Oct_2026_APT_CSV.zip", "29_Oct_2026_NAV_CSV.zip", "29_Oct_2026_FIX_CSV.zip"])
        # No redirect is ever followed, and nothing but the FAA's two hosts is asked.
        self.assertTrue(all(follow is False for _, follow in self.asked))
        self.assertEqual(sorted(os.listdir(self.root)), [".lock", "current.json", "places-2026-10-29.s1.sqlite"])

    def test_a_cycle_already_built_is_not_fetched_again(self):
        refresh.refresh(self.root, get=self.get)
        self.asked.clear()
        self.assertEqual(refresh.refresh(self.root, get=self.get), "current")
        self.assertEqual(len(self.asked), 1)

    def test_a_failed_download_keeps_the_build_being_served(self):
        refresh.refresh(self.root, get=self.get)
        self.edition = "11/26/2026"
        with self.assertRaisesRegex(refresh.RefreshError, "26_Nov_2026_APT_CSV.zip: 404"):
            refresh.refresh(self.root, get=self.get)
        self.assertEqual(store.read_current(self.root)["nasr_cycle"], "2026-10-29")
        self.assertFalse([n for n in os.listdir(self.root) if n.startswith(".download-") or n.startswith(".staging-")])

    def test_files_of_another_cycle_are_refused(self):
        self.edition = "11/26/2026"
        self.zips = {name.replace("29_Oct", "26_Nov"): data for name, data in self.zips.items()}
        with self.assertRaisesRegex(refresh.RefreshError, "not the cycle 2026-11-26"):
            refresh.refresh(self.root, get=self.get)
        self.assertIsNone(store.read_current(self.root))

    def test_a_build_far_from_the_last_ones_counts_is_refused(self):
        with self.assertRaisesRegex(refresh.RefreshError, "airport: 4 against 100"):
            refresh.check(nasr.read(list(map(self._write, self.zips.items()))), {"airport": 100})

    def _write(self, item):
        path = os.path.join(self.source, item[0])
        Path(path).write_bytes(item[1])
        return path

    def test_only_the_faas_hosts_over_https(self):
        for url in ("http://nfdc.faa.gov/x.zip", "https://example.com/x.zip", "https://nfdc.faa.gov.evil.com/x.zip"):
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
        self.assertEqual(builds, ["places-2026-10-29.s1.sqlite", "places-2026-11-26.s1.sqlite"])


class PlaceRouteTests(NativeAuthCase):
    extra_blueprints = (place_bp,)

    def setUp(self):
        super().setUp()
        clear_rate_limits()
        self.root = tempfile.mkdtemp()
        source = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root, ignore_errors=True))
        self.addCleanup(lambda: __import__("shutil").rmtree(source, ignore_errors=True))
        with patch.dict(refresh.MINIMUM, {"airport": 1, "heliport": 1, "fix": 1}, clear=True):
            refresh.build_from(write_zips(source), self.root)
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
        self.assertEqual(body["nasrCycle"], "2026-10-29")
        self.assertEqual([p["ident"] for p in body["places"]], ["DZJ", "1N7"])
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
