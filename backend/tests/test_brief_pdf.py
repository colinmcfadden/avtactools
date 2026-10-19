"""The mission brief's PDF (``POST /api/brief/pdf``): figures, pages and the Gotenberg request, with the tiles and
Gotenberg stood in for. The model is the web's own, from contracts/fixtures/brief/compile.json."""

import copy
import json
import sys
from pathlib import Path
from unittest import mock

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from PIL import Image  # noqa: E402

from auth_harness import NativeAuthCase  # noqa: E402
from brief import figures, gotenberg, pages  # noqa: E402
from models import SavedBrief, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.brief_routes import brief_bp  # noqa: E402

FIXTURE = json.loads((BACKEND_DIR.parent / 'contracts' / 'fixtures' / 'brief' / 'compile.json').read_text(encoding='utf-8'))
MODEL = FIXTURE['cases'][0]['expected']          # OP DK, air movement brief
HASTY = FIXTURE['cases'][1]['expected']


def _tile(*_args, **_kwargs):
    return Image.new('RGB', (1024, 1024), (60, 90, 60))


class FakeAnswer:
    def __init__(self, status=200, content=b'%PDF-1.7\n%fake\n'):
        self.status_code = status
        self.content = content


def test_world_pixels_of_the_middle_of_the_map():
    assert figures.world_px(0, 0, 0, 256) == (128.0, 128.0)
    x, y = figures.world_px(0, 180, 1, 256)
    assert (x, y) == (512.0, 256.0)


def test_the_zoom_is_the_most_detailed_where_the_bounds_fit():
    bounds = [[34.54, -84.12], [34.56, -84.10]]
    zoom = figures.choose_zoom(bounds, 1500, 940, 1024, 19)
    x0, y0 = figures.world_px(34.56, -84.12, zoom, 1024)
    x1, y1 = figures.world_px(34.54, -84.10, zoom, 1024)
    assert abs(x1 - x0) <= 1500 and abs(y1 - y0) <= 940
    x0, y0 = figures.world_px(34.56, -84.12, zoom + 1, 1024)
    x1, y1 = figures.world_px(34.54, -84.10, zoom + 1, 1024)
    assert abs(x1 - x0) > 1500 or abs(y1 - y0) > 940
    # The sectional chart has no tiles past 12: a small area is drawn at 12, wider than asked.
    assert figures.choose_zoom(bounds, 1500, 940, 256, 12) == 12


def test_the_middle_of_the_bounds_is_the_middle_of_the_figure():
    frame = figures.Frame([[34.0, -85.0], [35.0, -84.0]], 'satellite')
    x, y = frame.project(34.5, -84.5)
    assert abs(x - frame.width / 2) < 3 and abs(y - frame.height / 2) < 3
    assert frame.metres_per_px() > 0


def test_a_scale_bar_is_a_round_distance():
    assert figures._nice_scale(734) == 500
    assert figures._nice_scale(2900) == 2000
    assert figures._nice_scale(1) == 1


def test_labels_in_a_figure_are_escaped():
    frame = figures.Frame([[34.0, -85.0], [35.0, -84.0]], 'topo')
    geometry = {'routes': {}, 'stops': {'lz:x': {'name': '<script>alert(1)</script>', 'role': 'LZ', 'lat': 34.5, 'lon': -84.5}},
                'threats': [{'name': 'ZSU & "co"', 'lat': 34.4, 'lon': -84.4}], 'rally': []}
    svg = figures.overlay_svg(frame, {'kind': 'overview', 'stops': ['lz:x']}, geometry)
    assert '<script>' not in svg
    assert '&lt;script&gt;' in svg
    assert 'ZSU &amp; "co"' in svg


def test_a_figure_says_how_many_tiles_it_could_not_fetch():
    frame = figures.Frame([[34.0, -85.0], [35.0, -84.0]], 'satellite')
    svg = figures.overlay_svg(frame, {'kind': 'overview'}, {}, missing_tiles=2)
    assert '2 map tiles could not be fetched' in svg


def test_bounds_that_name_no_area_draw_no_figure():
    assert figures.draw({'kind': 'overview', 'bounds': None}, {}, 'satellite') is None
    assert figures.draw({'kind': 'overview', 'bounds': [[35, -84], [34, -85]]}, {}, 'satellite') is None


def _build(model=MODEL):
    with mock.patch.object(figures, '_fetch_tile', side_effect=_tile):
        return pages.build(copy.deepcopy(model))


def test_every_page_is_laid_out_with_its_figure():
    html, header, footer, files, paper = _build()
    assert paper == ('8.5', '11')
    # The cover stands on its own page, the first step after it, so one more page than the model plans.
    assert html.count('class="page"') == len(MODEL['pages']) + 1
    assert '<th>Page</th>' not in html                       # never a page number the printing could make wrong
    assert 'UNCLASSIFIED' in header and 'UNCLASSIFIED' in footer
    assert 'class="pageNumber"' in footer and 'class="totalPages"' in footer
    for step in MODEL['steps']:
        assert step['title'].split(' · ')[0].replace("'", '&#39;') in html
    # Each distinct figure once: the overview is shared by several steps.
    names = [f'fig-{i}.jpg' for i in range(1, len(files) + 1)]
    assert sorted(files) == sorted(names)
    assert all(data[:2] == b'\xff\xd8' for data in files.values())
    for name in names:
        assert f'src="{name}"' in html
    assert 'ALDERAAN' in html and '16.3 nm' in html          # the nav log's first leg


def test_what_a_person_typed_is_escaped():
    model = copy.deepcopy(MODEL)
    model['steps'][2]['script'] = [[{'text': '<img src=x onerror=alert(1)>'}]]
    model['mission']['name'] = '<b>OP</b>'
    html, header, *_ = _build(model)
    assert '<img src=x' not in html
    assert '&lt;img src=x onerror=alert(1)&gt;' in html
    assert '&lt;b&gt;OP&lt;/b&gt;' in header


def test_a4_and_the_topographic_map_when_asked():
    model = copy.deepcopy(MODEL)
    model['options'] = {**model['options'], 'paper': 'a4', 'figureMap': 'topo'}
    calls = []
    with mock.patch.object(figures, '_fetch_tile', side_effect=lambda source, *a: calls.append(source) or _tile()):
        _html, _h, _f, _files, paper = pages.build(model)
    assert paper == ('8.27', '11.69')
    assert set(calls) == {'topo'}


def test_a_body_that_is_not_a_brief_model_is_refused():
    for bad in (None, {}, {'format': 'ezpz-brief-model', 'steps': [], 'pages': [1]}, {'format': 'other', 'steps': [{}], 'pages': [{}]}):
        try:
            pages.build(bad)
        except pages.BadModel:
            continue
        raise AssertionError(f'{bad!r} was laid out')


class PdfRouteTests(NativeAuthCase):
    extra_blueprints = (brief_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        session = self.login()
        self.head = {'Authorization': f'Bearer {session["access_token"]}'}
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = {'mission_brief': True}
            db.session.commit()
        self.env = mock.patch.dict('os.environ', {'GOTENBERG_URL': 'http://gotenberg.internal:3000'})
        self.env.start()
        self.addCleanup(self.env.stop)
        tiles = mock.patch.object(figures, '_fetch_tile', side_effect=_tile)
        tiles.start()
        self.addCleanup(tiles.stop)

    def post(self, body):
        response = self.client.post('/api/brief/pdf', headers=self.head, json=body)
        if response.mimetype == 'application/json':
            problems = check_response(load_spec(), '/api/brief/pdf', 'POST', response.status_code, response.get_json())
            self.assertEqual(problems, [], response.status_code)
        return response

    def test_the_pdf_comes_back_and_nothing_is_kept(self):
        with mock.patch.object(gotenberg.requests, 'post', return_value=FakeAnswer()) as sent:
            response = self.post({'model': MODEL})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        self.assertEqual(response.mimetype, 'application/pdf')
        self.assertTrue(response.data.startswith(b'%PDF'))
        self.assertEqual(response.headers['Cache-Control'], 'no-store')
        self.assertIn('OP DK brief.pdf', response.headers['Content-Disposition'])
        url = sent.call_args.args[0]
        self.assertEqual(url, 'http://gotenberg.internal:3000/forms/chromium/convert/html')
        names = [part[1][0] for part in sent.call_args.kwargs['files']]
        self.assertEqual(names[:3], ['index.html', 'header.html', 'footer.html'])
        self.assertTrue(all(name.startswith('fig-') for name in names[3:]) and len(names) > 3)
        self.assertEqual(sent.call_args.kwargs['data']['paperWidth'], '8.5')
        with self.app.app_context():
            self.assertEqual(SavedBrief.query.count(), 0)

    def test_the_hasty_brief_prints_too(self):
        with mock.patch.object(gotenberg.requests, 'post', return_value=FakeAnswer()):
            self.assertEqual(self.post({'model': HASTY}).status_code, 200)

    def test_without_gotenberg_the_pdf_is_unavailable(self):
        with mock.patch.dict('os.environ', {'GOTENBERG_URL': ''}):
            response = self.post({'model': MODEL})
        self.assertEqual((response.status_code, response.get_json()['code']), (503, 'pdf_unavailable'))

    def test_gotenberg_failing_is_said_as_such(self):
        with mock.patch.object(gotenberg.requests, 'post', return_value=FakeAnswer(500, b'oops')):
            response = self.post({'model': MODEL})
        self.assertEqual((response.status_code, response.get_json()['code']), (502, 'pdf_failed'))
        with mock.patch.object(gotenberg.requests, 'post', side_effect=gotenberg.requests.ConnectionError('down')):
            response = self.post({'model': MODEL})
        self.assertEqual(response.status_code, 502)
        self.assertNotIn('ConnectionError', response.get_json()['error'])

    def test_a_body_that_is_not_a_brief_is_refused(self):
        with mock.patch.object(gotenberg.requests, 'post', return_value=FakeAnswer()) as sent:
            response = self.post({'model': {'format': 'nope'}})
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_brief'))
        sent.assert_not_called()

    def test_without_the_feature_there_is_no_pdf(self):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = {}
            db.session.commit()
        response = self.post({'model': MODEL})
        self.assertEqual((response.status_code, response.get_json()['code']), (403, 'feature_disabled'))

    def test_a_brief_too_large_is_refused_before_anything_is_drawn(self):
        big = {'model': {**MODEL, 'padding': 'x' * (5 * 1024 * 1024)}}
        response = self.client.post('/api/brief/pdf', headers={**self.head, 'Content-Type': 'application/json'},
                                    data=json.dumps(big))
        self.assertEqual(response.status_code, 413)

