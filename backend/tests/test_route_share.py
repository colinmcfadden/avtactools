"""Route share links (`POST /api/route-share`, `/r/<token>`): a route's GPX and flight plan, or a whole route set's GPX alone."""

import sys
import unittest
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from route_share_store import RouteShareStore  # noqa: E402
from routes import route_share_routes  # noqa: E402
from routes.route_share_routes import route_share_bp  # noqa: E402

GPX = '<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1"><rte><name>ARIEL</name></rte></gpx>\n'
SET_GPX = '<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1"><rte><name>ARIEL</name></rte><rte><name>BEEMIN</name></rte></gpx>\n'
FPL = '<?xml version="1.0" encoding="utf-8"?>\n<flight-plan><route><route-name>ARIEL</route-name></route></flight-plan>\n'


class RouteShareCase(NativeAuthCase):
    extra_blueprints = (route_share_bp,)

    def setUp(self):
        super().setUp()
        route_share_routes._store.clear()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}

    def share(self, body, head=None):
        return self.client.post('/api/route-share', headers=self.head if head is None else head, json=body)

    def made(self, body):
        response = self.share(body)
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        self.assertEqual(response.headers['Cache-Control'], 'no-store')
        answer = response.get_json()
        self.assertEqual(sorted(answer), ['expiresInSeconds', 'sharePath'])
        self.assertEqual(answer['expiresInSeconds'], 1800)
        return answer['sharePath']


class ARoute(RouteShareCase):
    def test_a_route_is_shared_as_its_gpx_and_its_flight_plan(self):
        path = self.made({'name': 'ARIEL', 'gpx': GPX, 'fpl': FPL})
        page = self.client.get(path)
        self.assertEqual(page.status_code, 200)
        html = page.get_data(as_text=True)
        # The page a route has always had, to the letter.
        self.assertIn('<title>ARIEL — route</title>', html)
        self.assertIn(f'''  <h1>ARIEL</h1>
  <p class="sub">Download a route file, then import it into ForeFlight.</p>
  <a class="btn gpx" href="{path}/route.gpx" download>Download GPX (recommended)</a>
  <a class="btn fpl" href="{path}/route.fpl" download>Download Garmin .fpl</a>
  <p class="sub">To import on the iPad:</p>
  <ol>
    <li>Tap a download above.</li>
    <li>Open the file (Files app or the download banner).</li>
    <li>Share &rarr; <b>ForeFlight</b> (or &ldquo;Copy to ForeFlight&rdquo;).</li>
    <li>Find the route under <b>Flights</b>; the waypoints load into <b>User Waypoints</b>.</li>
  </ol>
  <p class="sub" style="font-size:.75rem">This link expires in 30 minutes.</p>''', html)

        gpx = self.client.get(f'{path}/route.gpx')
        self.assertEqual(gpx.get_data(as_text=True), GPX)
        self.assertEqual(gpx.mimetype, 'application/gpx+xml')
        self.assertIn('ARIEL.gpx', gpx.headers['Content-Disposition'])
        fpl = self.client.get(f'{path}/route.fpl')
        self.assertEqual(fpl.status_code, 200)
        self.assertEqual(fpl.get_data(as_text=True), FPL)
        self.assertIn('ARIEL.fpl', fpl.headers['Content-Disposition'])

    def test_what_is_not_a_route_is_refused_as_it_always_was(self):
        for body in (
            {'name': 'X', 'fpl': FPL},                    # no GPX
            {'name': 'X', 'gpx': '', 'fpl': FPL},
            {'name': 'X', 'gpx': 7, 'fpl': FPL},
            {'name': 'X', 'gpx': GPX, 'fpl': ''},         # a flight plan given, and empty
            {'name': 'X', 'gpx': GPX, 'fpl': 7},
            {'name': 'X', 'gpx': GPX, 'fpl': [FPL]},
            {},
        ):
            self.assertEqual(self.share(body).status_code, 400, body)

    def test_a_token_is_needed_to_make_a_link_and_none_to_use_it(self):
        self.assertEqual(self.share({'name': 'X', 'gpx': GPX, 'fpl': FPL}, head={}).status_code, 401)
        self.assertEqual(self.share({'name': 'X', 'gpx': SET_GPX}, head={}).status_code, 401)
        path = self.made({'name': 'X', 'gpx': SET_GPX})
        self.assertEqual(self.client.get(path).status_code, 200)


class ARouteSet(RouteShareCase):
    def test_a_set_is_shared_as_its_one_gpx_and_no_flight_plan(self):
        path = self.made({'name': '260818 Multi', 'gpx': SET_GPX})
        page = self.client.get(path)
        self.assertEqual(page.status_code, 200)
        self.assertEqual(page.headers['Cache-Control'], 'no-store')
        html = page.get_data(as_text=True)
        self.assertIn('<title>260818 Multi — routes</title>', html)
        self.assertIn('<h1>260818 Multi</h1>', html)
        self.assertIn('Every route of the set is in the one GPX file.', html)
        self.assertIn(f'<a class="btn gpx" href="{path}/route.gpx" download>Download GPX</a>', html)
        # Nothing on the page offers or speaks of a flight plan, or of where one route lands.
        body = html.split('</style>')[1]
        self.assertNotIn('fpl', body)
        self.assertNotIn('Flights', body)
        self.assertEqual(body.count('<a '), 1)

        gpx = self.client.get(f'{path}/route.gpx')
        self.assertEqual(gpx.status_code, 200)
        self.assertEqual(gpx.get_data(as_text=True), SET_GPX)
        self.assertEqual(gpx.mimetype, 'application/gpx+xml')
        self.assertIn('attachment', gpx.headers['Content-Disposition'])
        self.assertIn('260818_Multi.gpx', gpx.headers['Content-Disposition'])

        fpl = self.client.get(f'{path}/route.fpl')
        self.assertEqual(fpl.status_code, 404)
        self.assertEqual(fpl.headers['Cache-Control'], 'no-store')
        self.assertEqual(fpl.get_json(), {'error': 'This link has no .fpl file'})

    def test_a_null_flight_plan_is_none(self):
        path = self.made({'name': 'S', 'gpx': SET_GPX, 'fpl': None})
        self.assertNotIn('route.fpl', self.client.get(path).get_data(as_text=True))
        self.assertEqual(self.client.get(f'{path}/route.fpl').status_code, 404)

    def test_a_set_s_name_is_escaped_on_its_page(self):
        path = self.made({'name': '<script>alert(1)</script> & "x"', 'gpx': SET_GPX})
        html = self.client.get(path).get_data(as_text=True)
        self.assertNotIn('<script>alert(1)</script>', html)
        self.assertIn('&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;', html)

    def test_a_set_with_no_name_is_called_route(self):
        path = self.made({'gpx': SET_GPX})
        self.assertIn('<h1>Route</h1>', self.client.get(path).get_data(as_text=True))
        self.assertIn('Route.gpx', self.client.get(f'{path}/route.gpx').headers['Content-Disposition'])

    def test_a_set_too_large_is_refused(self):
        big = '<gpx>' + 'x' * (512 * 1024) + '</gpx>'
        self.assertEqual(self.share({'name': 'BIG', 'gpx': big}).status_code, 413)
        # As large as a route's may be, and no more.
        fits = '<gpx>' + 'x' * (512 * 1024 - 11) + '</gpx>'
        self.assertEqual(len(fits.encode()), 512 * 1024)
        self.made({'name': 'FITS', 'gpx': fits})

    def test_a_link_that_is_gone_says_so(self):
        path = self.made({'name': 'S', 'gpx': SET_GPX})
        route_share_routes._store.clear()
        self.assertEqual(self.client.get(path).status_code, 410)
        self.assertEqual(self.client.get(f'{path}/route.gpx').status_code, 410)
        self.assertEqual(self.client.get(f'{path}/route.fpl').status_code, 410)
        self.assertEqual(self.client.get(f'{path}/route.kml').status_code, 404)


class TheStore(unittest.TestCase):
    def test_a_share_with_no_flight_plan_is_kept_and_counted_by_its_gpx(self):
        store = RouteShareStore(max_entry_bytes=10, max_total_bytes=20)
        token = store.create('S', b'12345678')
        share = store.get(token)
        self.assertEqual((share.name, share.gpx, share.fpl), ('S', b'12345678', None))
        with self.assertRaises(ValueError):
            store.create('S', b'12345678901')
        # Two fit the total; a third puts the oldest out.
        second = store.create('T', b'1234567890')
        self.assertIsNotNone(store.get(token))
        third = store.create('U', b'123')
        self.assertIsNone(store.get(token))
        self.assertIsNotNone(store.get(second))
        self.assertIsNotNone(store.get(third))

    def test_a_route_s_share_is_what_it_was(self):
        store = RouteShareStore()
        share = store.get(store.create('R', b'gpx', b'fpl'))
        self.assertEqual((share.name, share.gpx, share.fpl), ('R', b'gpx', b'fpl'))


if __name__ == '__main__':
    unittest.main()
