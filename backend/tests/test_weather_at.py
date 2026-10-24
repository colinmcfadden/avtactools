"""``/api/weather?at=``: the weather at a planned time, for a brief (docs/MISSION_BRIEF.md §2.5).

aviationweather.gov and the FAA's NOTAM search are other people's services, so what they answer is stood in for;
which TAF period is chosen, what a forecast says and leaves out, and that an answer without ``at`` is unchanged are
the server's own.
"""

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes import weather_routes  # noqa: E402
from routes.weather_routes import weather_bp  # noqa: E402

SPEC = load_spec()
NOW = 1_790_000_000                         # 2026-09-21T14:13:20Z: "now" for every test here
HOUR = 3600
HERE = {'lat': '34.0', 'lng': '-84.6'}

METARS = [
    {'icaoId': 'KRYY', 'name': 'Cobb County Airport', 'lat': 34.01, 'lon': -84.6, 'temp': 18, 'dewp': 9, 'wspd': 12, 'wdir': 270,
     'wgst': 20, 'visib': 10, 'altim': 1015.9, 'fltcat': 'VFR', 'rawOb': 'KRYY 211255Z 27012G20KT 10SM CLR 18/09 A3000',
     'obsTime': NOW - 1800},
]
# KRYY is nearest but its TAF ends six hours from now; KMGE is farther and runs a day.
TAFS = [
    {'icaoId': 'KRYY', 'name': 'Cobb County Airport', 'lat': 34.01, 'lon': -84.6, 'rawTAF': 'TAF KRYY ...', 'issueTime': NOW - HOUR,
     'fcsts': [{'timeFrom': NOW - HOUR, 'timeTo': NOW + 6 * HOUR, 'wdir': 250, 'wspd': 8, 'visib': '6+',
                'clouds': [{'cover': 'SCT', 'base': 4000}]}]},
    {'icaoId': 'KMGE', 'name': 'Dobbins ARB', 'lat': 33.92, 'lon': -84.52, 'rawTAF': 'TAF KMGE ...', 'issueTime': '2026-09-21T12:00:00Z',
     'fcsts': [
         {'timeFrom': NOW - HOUR, 'timeTo': NOW + 10 * HOUR, 'fcstChange': None, 'wdir': 300, 'wspd': 15, 'wgst': 25, 'visib': '6+',
          'clouds': [{'cover': 'BKN', 'base': 2500}, {'cover': 'OVC', 'base': 5000}], 'altim': 1012.0},
         {'timeFrom': NOW + 2 * HOUR, 'timeTo': NOW + 5 * HOUR, 'fcstChange': 'TEMPO', 'wdir': 0, 'wspd': 0, 'visib': 1,
          'clouds': [{'cover': 'OVC', 'base': 300}]},
         {'timeFrom': NOW + 8 * HOUR, 'timeTo': NOW + 10 * HOUR, 'fcstChange': 'BECMG', 'wspd': 6, 'visib': 2,
          'clouds': None},
         {'timeFrom': NOW + 10 * HOUR, 'timeTo': NOW + 24 * HOUR, 'fcstChange': 'FM', 'wdir': 'VRB', 'wspd': 3, 'visib': '6+',
          'clouds': [{'cover': 'FEW', 'base': 8000}]},
     ]},
]


class Reply:
    def __init__(self, body, status=200):
        self.status_code, self._body = status, body

    def json(self):
        return self._body


class WeatherAtTests(NativeAuthCase):
    extra_blueprints = (weather_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}
        self.asked = []
        self.metars, self.tafs = METARS, TAFS
        for target, value in (('routes.weather_routes.requests.get', self.get),
                              ('routes.weather_routes.requests.post', lambda url, **kw: Reply({'notamList': []})),
                              ('routes.weather_routes._now', lambda: NOW)):
            patcher = patch(target, side_effect=value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def get(self, url, **_kw):
        self.asked.append(url.rsplit('/', 1)[-1])
        if url.endswith('/taf'):
            return Reply(self.tafs)
        return Reply(self.metars)

    def weather(self, **query):
        response = self.client.get('/api/weather', headers=self.head, query_string={**HERE, **query})
        if response.get_json() is not None and response.status_code in (200, 400):
            self.assertEqual(check_response(SPEC, '/api/weather', 'GET', response.status_code, response.get_json()), [])
        return response

    def at(self, epoch):
        return weather_routes._iso(epoch)

    def test_without_at_the_answer_is_as_it_always_was(self):
        body = self.weather().get_json()
        self.assertEqual(set(body), {'station_id', 'name', 'temp_c', 'dewp_c', 'wind_spd_kts', 'wind_dir', 'wind_gust_kts', 'vis_sm',
                                     'pressure', 'flight_category', 'raw_metar', 'distance_miles', 'notams'})
        self.assertEqual((body['station_id'], body['wind_dir'], body['temp_c']), ('KRYY', 270, 18))
        self.assertEqual(self.asked, ['metar'])                       # no TAF is asked for

    def test_a_time_soon_or_past_is_the_observation(self):
        for epoch in (NOW, NOW + 1800, NOW - 2 * HOUR):
            with self.subTest(at=self.at(epoch)):
                body = self.weather(at=self.at(epoch)).get_json()
                self.assertEqual((body['kind'], body['at'], body['station_id'], body['temp_c']), ('observation', self.at(epoch), 'KRYY', 18))
                self.assertEqual(body['observed'], self.at(NOW - 1800))
                self.assertNotIn('raw_taf', body)
        self.assertNotIn('taf', self.asked)

    def test_a_time_ahead_is_the_taf_period_covering_it(self):
        body = self.weather(at=self.at(NOW + 7 * HOUR)).get_json()
        # KRYY's TAF ends before then, so the nearest that covers it is KMGE's.
        self.assertEqual((body['kind'], body['station_id'], body['name']), ('forecast', 'KMGE', 'Dobbins ARB'))
        self.assertEqual((body['wind_dir'], body['wind_spd_kts'], body['wind_gust_kts'], body['vis_sm']), (300, 15, 25, '6+'))
        self.assertEqual((body['flight_category'], body['pressure']), ('MVFR', 29.88))
        self.assertEqual((body['valid_from'], body['valid_to']), (self.at(NOW - HOUR), self.at(NOW + 10 * HOUR)))
        self.assertEqual((body['issued'], body['raw_taf']), ('2026-09-21T12:00:00Z', 'TAF KMGE ...'))
        self.assertEqual(body['distance_miles'], round(((34.0 - 33.92) ** 2 + (-84.6 + 84.52) ** 2) ** 0.5 * 69, 1))
        # Nothing a TAF does not give is taken from the observation.
        self.assertEqual((body['temp_c'], body['dewp_c'], body['raw_metar']), (None, None, None))
        self.assertNotIn('observed', body)

    def test_the_nearest_taf_that_covers_it_wins(self):
        body = self.weather(at=self.at(NOW + 3 * HOUR)).get_json()
        self.assertEqual((body['station_id'], body['wind_dir'], body['flight_category'], body['pressure']), ('KRYY', 250, 'VFR', '--'))

    def test_a_tempo_group_is_not_the_forecast(self):
        body = self.weather(at=self.at(NOW + 7 * HOUR)).get_json()
        self.assertNotEqual(body['vis_sm'], 1)
        self.tafs = [TAFS[1]]
        body = self.weather(at=self.at(NOW + 3 * HOUR)).get_json()
        self.assertEqual((body['wind_spd_kts'], body['vis_sm'], body['flight_category']), (15, '6+', 'MVFR'))

    def test_a_becoming_group_changes_only_what_it_gives(self):
        body = self.weather(at=self.at(NOW + 9 * HOUR)).get_json()
        self.assertEqual((body['station_id'], body['wind_dir'], body['wind_spd_kts'], body['vis_sm']), ('KMGE', 300, 6, 2))
        self.assertEqual((body['valid_from'], body['valid_to']), (self.at(NOW + 8 * HOUR), self.at(NOW + 10 * HOUR)))
        self.assertEqual(body['flight_category'], 'IFR')              # the base period's ceiling, the new visibility

    def test_a_from_group_starts_afresh(self):
        body = self.weather(at=self.at(NOW + 12 * HOUR)).get_json()
        self.assertEqual((body['wind_dir'], body['wind_spd_kts'], body['wind_gust_kts'], body['flight_category']), ('VRB', 3, None, 'VFR'))

    def test_a_time_no_taf_covers_is_the_observation(self):
        for epoch in (NOW + 30 * HOUR, NOW + 400 * HOUR):
            with self.subTest(at=self.at(epoch)):
                body = self.weather(at=self.at(epoch)).get_json()
                self.assertEqual((body['kind'], body['station_id'], body['temp_c']), ('observation', 'KRYY', 18))

    def test_no_taf_service_is_the_observation(self):
        self.tafs = []
        self.assertEqual(self.weather(at=self.at(NOW + 7 * HOUR)).get_json()['kind'], 'observation')
        self.tafs = [{'icaoId': 'KXXX'}, 'not a taf', {'icaoId': 'KYYY', 'lat': 34.0, 'lon': -84.6, 'fcsts': 'nonsense'}]
        self.assertEqual(self.weather(at=self.at(NOW + 7 * HOUR)).get_json()['kind'], 'observation')

    def test_no_station_at_all(self):
        self.metars, self.tafs = [], []
        body = self.weather(at=self.at(NOW)).get_json()
        self.assertEqual((body['kind'], body['station_id'], body['observed']), ('observation', 'TIMEOUT', None))

    def test_times_with_an_offset_or_none(self):
        self.assertEqual(self.weather(at='2026-09-21T17:13:20+03:00').get_json()['at'], self.at(NOW))
        self.assertEqual(self.weather(at='2026-09-21T14:13:20').get_json()['at'], self.at(NOW))     # no offset: UTC
        self.assertEqual(self.weather(at='2026-09-21T21:13:20.000Z').get_json()['kind'], 'forecast')

    def test_a_time_that_is_not_one_is_refused(self):
        for value in ('', 'tomorrow', '2026-13-01T00:00:00Z', '1' * 41, '2026-09-21T25:00:00Z'):
            with self.subTest(at=value):
                response = self.weather(at=value)
                self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_at'))
        self.assertEqual(self.asked, [])                              # refused before anyone is asked


class FlightCategoryTests(unittest.TestCase):
    """aviationweather.gov's bands, and no category when the forecast does not say both."""

    def test_the_bands(self):
        category = weather_routes._flight_category
        ceiling = lambda base: [{'cover': 'BKN', 'base': base}]  # noqa: E731
        for clouds, vis, expected in (
            (ceiling(3100), 6, 'VFR'), (ceiling(3000), 6, 'MVFR'), (ceiling(1000), 6, 'MVFR'), (ceiling(999), 6, 'IFR'),
            (ceiling(500), 6, 'IFR'), (ceiling(499), 6, 'LIFR'), ([], 5, 'MVFR'), ([], 3, 'MVFR'), ([], 2.99, 'IFR'),
            ([], 1, 'IFR'), ([], 0.75, 'LIFR'), ([{'cover': 'SCT', 'base': 400}], '6+', 'VFR'),
            ([{'cover': 'VV', 'base': 200}], '1/4', 'LIFR'), ([{'cover': 'FEW', 'base': 900}, {'cover': 'OVC', 'base': 1200}], 'P6SM', 'MVFR'),
            (None, 6, None), ([], None, None), ([{'cover': 'OVC'}], 6, None), ([], 'unknown', None),
        ):
            with self.subTest(clouds=clouds, vis=vis):
                self.assertEqual(category(clouds, vis), expected)

    def test_visibilities(self):
        for value, expected in ((8, 8.0), ('6+', 6.0), ('P6SM', 6.0), ('1 1/2', 1.5), ('1/4SM', 0.25), ('10+', 10.0),
                                (True, None), ('1/0', None), ('', None), (None, None)):
            with self.subTest(value=value):
                self.assertEqual(weather_routes._visibility_sm(value), expected)
