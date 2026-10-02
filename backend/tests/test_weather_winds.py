"""Route winds: choosing a reporting station and METAR or TAF for each point.

Written before the helpers moved out of the route module. No network: the
aviationweather.gov fetch is replaced.
"""

import sys
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.routes import weather as weather_routes  # noqa: E402
from app.routes.weather import weather_bp  # noqa: E402
from app.services.weather import (  # noqa: E402
    FORECAST_THRESHOLD_SEC,
    get_distance,
    nearest_report,
    num,
    select_taf_fcst,
    to_epoch,
    wind_from_fields,
)
from support import make_app  # noqa: E402


class ParsingTests(unittest.TestCase):
    def test_numbers_that_are_not_numbers_are_none(self):
        self.assertEqual(num("12.5"), 12.5)
        self.assertIsNone(num("VRB"))
        self.assertIsNone(num(None))

    def test_times_come_as_epoch_seconds_or_iso_text(self):
        self.assertEqual(to_epoch(1_700_000_000), 1_700_000_000.0)
        self.assertEqual(to_epoch("2026-10-02T14:30:00Z"),
                         datetime(2026, 10, 2, 14, 30, tzinfo=timezone.utc).timestamp())
        self.assertEqual(to_epoch("2026-10-02T14:30:00"),   # no zone: taken as UTC
                         datetime(2026, 10, 2, 14, 30, tzinfo=timezone.utc).timestamp())
        self.assertIsNone(to_epoch("soon"))
        self.assertIsNone(to_epoch(None))

    def test_variable_wind_has_no_direction(self):
        self.assertEqual(wind_from_fields("270", "12"),
                         {"dirTrue": 270, "speedKts": 12.0, "variable": False})
        self.assertEqual(wind_from_fields("VRB", "4"),
                         {"dirTrue": 0, "speedKts": 4.0, "variable": True})
        self.assertEqual(wind_from_fields(None, None),
                         {"dirTrue": 0, "speedKts": 0, "variable": True})


class StationChoiceTests(unittest.TestCase):
    REPORTS = [{"icaoId": "KFAR", "lat": 35.5, "lon": -84.0},
               {"icaoId": "KNEAR", "lat": 34.7, "lon": -84.1},
               {"icaoId": "KBROKEN", "lat": None, "lon": -84.1},
               {"icaoId": "KNOLON", "lat": 34.79}]

    def test_the_nearest_station_wins_and_unlocated_ones_are_ignored(self):
        report, distance = nearest_report(34.7, -84.1, self.REPORTS)
        self.assertEqual(report["icaoId"], "KNEAR")
        self.assertEqual(distance, 0.0)

    def test_no_usable_report_is_no_report(self):
        self.assertEqual(nearest_report(34.7, -84.1, []), (None, float("inf")))

    def test_distance_is_in_degrees(self):
        self.assertAlmostEqual(get_distance(0, 0, 3, 4), 5.0)


class TafSelectionTests(unittest.TestCase):
    def fcst(self, start, end, **extra):
        return {"timeFrom": start, "timeTo": end, **extra}

    def test_the_period_covering_the_time_is_chosen(self):
        taf = {"fcsts": [self.fcst(100, 200, wdir=1), self.fcst(200, 300, wdir=2)]}
        self.assertEqual(select_taf_fcst(taf, 250)["wdir"], 2)
        self.assertEqual(select_taf_fcst(taf, 100)["wdir"], 1)     # start is inclusive
        self.assertEqual(select_taf_fcst(taf, 200)["wdir"], 2)     # end is exclusive

    def test_base_groups_beat_tempo_and_probability_groups(self):
        taf = {"fcsts": [self.fcst(100, 300, wdir="base"),
                         self.fcst(150, 250, fcstChange="TEMPO", wdir="tempo"),
                         self.fcst(150, 250, probability=30, wdir="prob")]}
        self.assertEqual(select_taf_fcst(taf, 200)["wdir"], "base")

    def test_only_a_tempo_group_covering_the_time_is_better_than_nothing(self):
        taf = {"fcsts": [self.fcst(100, 150, wdir="early"),
                         self.fcst(150, 250, fcstChange="TEMPO", wdir="tempo")]}
        self.assertEqual(select_taf_fcst(taf, 200)["wdir"], "tempo")

    def test_past_the_end_of_the_taf_the_closest_start_is_used(self):
        taf = {"fcsts": [self.fcst(100, 200, wdir=1), self.fcst(200, 300, wdir=2)]}
        self.assertEqual(select_taf_fcst(taf, 900)["wdir"], 2)
        self.assertEqual(select_taf_fcst(taf, 0)["wdir"], 1)

    def test_a_taf_without_periods_gives_nothing(self):
        self.assertIsNone(select_taf_fcst({"fcsts": []}, 100))
        self.assertIsNone(select_taf_fcst({}, 100))


def iso(offset_seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=offset_seconds)).isoformat()


class RouteWindsEndpointTests(unittest.TestCase):
    METARS = [{"icaoId": "KNEAR", "lat": 34.7, "lon": -84.1, "wdir": 270, "wspd": 12, "temp": 18.5},
              {"icaoId": "KFAR", "lat": 35.9, "lon": -84.0, "wdir": 90, "wspd": 3, "temp": 9}]

    def setUp(self):
        self.app, self.auth = make_app(weather_bp)
        self.client = self.app.test_client()

    def winds(self, points, metars=None, tafs=None):
        calls = []

        def fake_fetch(kind, bbox):
            calls.append(kind)
            return {"metar": self.METARS if metars is None else metars, "taf": tafs or []}[kind]

        with patch.object(weather_routes, "fetch_awc", side_effect=fake_fetch):
            response = self.client.post("/api/route-winds", json={"points": points}, headers=self.auth)
        return response, calls

    def test_the_requests_needs_a_token(self):
        self.assertEqual(self.client.post("/api/route-winds", json={}).status_code, 401)

    def test_a_current_point_takes_wind_from_the_nearest_metar(self):
        response, calls = self.winds([{"id": "p1", "lat": 34.71, "lon": -84.1}])
        wind = response.get_json()["winds"]["p1"]
        self.assertEqual((wind["dirTrue"], wind["speedKts"], wind["tempC"]), (270, 12.0, 18.5))
        self.assertEqual((wind["station"], wind["source"]), ("KNEAR", "METAR"))
        self.assertEqual(calls, ["metar"])                    # no forecast needed

    def test_a_point_well_in_the_future_uses_the_taf_for_wind_and_the_metar_for_temperature(self):
        later = time.time() + FORECAST_THRESHOLD_SEC + 7200
        start, end = iso(3600), iso(36000)
        taf = {"icaoId": "KTAF", "lat": 34.7, "lon": -84.1,
               "fcsts": [{"timeFrom": start, "timeTo": end, "wdir": 310, "wspd": 20}]}
        response, calls = self.winds([{"id": "p1", "lat": 34.7, "lon": -84.1, "time": iso(later - time.time())}],
                                     tafs=[taf])
        wind = response.get_json()["winds"]["p1"]
        self.assertEqual((wind["dirTrue"], wind["speedKts"]), (310, 20.0))
        self.assertEqual((wind["station"], wind["source"], wind["tempC"]), ("KTAF", "TAF", 18.5))
        self.assertEqual(calls, ["metar", "taf"])

    def test_with_no_forecast_available_the_metar_is_the_fallback(self):
        response, _ = self.winds([{"id": "p1", "lat": 34.7, "lon": -84.1, "time": iso(7200)}], tafs=[])
        self.assertEqual(response.get_json()["winds"]["p1"]["source"], "METAR")

    def test_distance_to_the_station_is_reported_in_miles(self):
        response, _ = self.winds([{"id": "p1", "lat": 34.8, "lon": -84.1}])
        self.assertEqual(response.get_json()["winds"]["p1"]["distanceMiles"], round(0.1 * 69, 1))

    def test_no_points_and_no_stations_give_empty_answers(self):
        self.assertEqual(self.winds([])[0].get_json(), {"winds": {}})
        self.assertEqual(self.winds([{"id": "p1", "lat": 34.7, "lon": -84.1}], metars=[])[0].get_json(),
                         {"winds": {}})

    def test_points_without_coordinates_are_skipped(self):
        response, _ = self.winds([{"id": "bad", "lat": "n/a", "lon": -84.1},
                                  {"id": "ok", "lat": 34.7, "lon": -84.1}])
        self.assertEqual(list(response.get_json()["winds"]), ["ok"])


if __name__ == "__main__":
    unittest.main()
