"""The mission brief's PDF (``POST /api/brief/pdf``): figures, pages and the Gotenberg request, with the tiles and
Gotenberg stood in for. The model is the web's own, from contracts/fixtures/brief/compile.json."""

import copy
import io
import json
import math
import re
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
# The strip charts' MGRS grid, as the web adds it to the model when it asks for the PDF (feature/brief/stripGrid.js).
STRIP_GRIDS = json.loads((BACKEND_DIR.parent / 'contracts' / 'fixtures' / 'brief' / 'strip-grid.json').read_text(encoding='utf-8'))


def _with_grids(model, case_name):
    """A compiled model with its strips' grids, as the web sends it."""
    model = copy.deepcopy(model)
    grids = next(c for c in STRIP_GRIDS['cases'] if c['name'] == case_name)
    by_id = {s['id']: s['grid'] for s in grids['strips']}
    for strip in model['strips']:
        strip['grid'] = by_id[strip['id']]
    return model


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


# -- Strip charts -----------------------------------------------------------------------------------------------------

STRIP = MODEL['strips'][0]          # ALDERAAN 1/6: 3MILE up the first leg, 031°


def _strip(**changes):
    strip = copy.deepcopy(STRIP)
    strip.update(changes)
    return strip


def test_a_strip_is_drawn_at_its_scale_three_to_a_page_course_up():
    frame = figures.StripFrame(STRIP, 'topo')
    # 5.5 by 21.5 cm at 200 px to the inch, and the ground the scale gives it.
    assert (frame.width, frame.height) == (433, 1693)
    assert abs(frame.metres_per_px * frame.height - 21500) < 1e-6
    # Its centre is the middle; a kilometre up the course is straight up, one to the right straight right.
    cx, cy = frame.project(*STRIP['centre'])
    assert abs(cx - frame.width / 2) < 0.01 and abs(cy - frame.height / 2) < 0.01
    lat, lon = STRIP['centre']
    import math
    c = math.radians(STRIP['course'])
    up = (lat + 1000 * math.cos(c) / 111320, lon + 1000 * math.sin(c) / (111320 * math.cos(math.radians(lat))))
    x, y = frame.project(*up)
    assert abs(x - frame.width / 2) < 1.5 and abs((frame.height / 2 - y) * frame.metres_per_px - 1000) < 12
    right = (lat - 1000 * math.sin(c) / 111320, lon + 1000 * math.cos(c) / (111320 * math.cos(math.radians(lat))))
    x, y = frame.project(*right)
    assert abs(y - frame.height / 2) < 1.5 and abs((x - frame.width / 2) * frame.metres_per_px - 1000) < 12
    # The route starts near the bottom and runs up the strip.
    start = frame.project(*STRIP['path'][0])
    end = frame.project(*STRIP['path'][-1])
    assert abs(start[0] - frame.width / 2) < 30 and start[1] > frame.height * 0.9
    assert end[1] < start[1] and abs(end[0] - frame.width / 2) < frame.width * 0.3


def test_the_map_under_a_strip_is_turned_so_the_course_is_up():
    """North white, south black: course 000 puts white on top, 180 at the bottom, 090 (east up) on the left."""
    def halves(mosaic):
        frame_cy = mosaic['frame'].cy - mosaic['frame'].mosaic.top
        image = Image.new('RGB', (mosaic['frame'].mosaic.width, mosaic['frame'].mosaic.height), (0, 0, 0))
        image.paste((255, 255, 255), (0, 0, image.width, max(0, round(frame_cy))))
        return image, 0

    for course, (top, bottom, left, right) in ((0, (255, 0, None, None)), (180, (0, 255, None, None)), (90, (None, None, 255, 0))):
        frame = figures.StripFrame(_strip(course=course), 'topo')
        with mock.patch.object(figures, 'base_image', side_effect=lambda f, s, fr=frame: halves({'frame': fr})):
            image, missing = figures.strip_image(frame, 'topo')
        assert image.size == (frame.width, frame.height) and missing == 0
        w, h = image.size
        if top is not None:
            assert image.getpixel((w // 2, h // 6))[0] == top and image.getpixel((w // 2, h * 5 // 6))[0] == bottom
        else:
            assert image.getpixel((w // 6, h // 2))[0] == left and image.getpixel((w * 5 // 6, h // 2))[0] == right


def test_a_strip_says_its_checkpoints_legs_and_grid_course_up():
    strip = _with_grids(MODEL, FIXTURE['cases'][0]['name'])['strips'][0]
    frame = figures.StripFrame(strip, 'topo')
    svg = figures.strip_overlay_svg(frame, strip, 'topo')
    assert '3MILE' in svg
    assert '030° 16.3 nm' in svg and '0+07+19' in svg and '2,500 ft MSL' in svg
    assert f'rotate({-strip["course"]:.2f})' in svg              # the north arrow points to true north
    digits = {l['text'] for l in strip['grid']['lines'] if l.get('text')}
    shown = [d for d in digits if f'>{d}</text>' in svg]
    assert len(shown) >= 6                                         # the 1 km lines are numbered at the edges
    assert svg.count('<polyline') >= len(strip['grid']['lines'])


def test_strip_pages_lay_out_three_strips_each_on_the_strip_map():
    model = _with_grids(MODEL, FIXTURE['cases'][0]['name'])
    calls = []
    with mock.patch.object(figures, '_fetch_tile', side_effect=lambda source, *a: calls.append(source) or _tile()):
        html, _h, _f, files, _paper = pages.build(model)
    assert html.count('class="page"') == len(model['pages']) + 1
    assert html.count('Appendix · strip charts') == 4
    assert html.count('class="strip"') == len(model['strips']) == 12
    assert '031° · 1:100,000 · 1/6</b> ALDERAAN' in html
    assert 'MGRS 16S GC → 16S GD · eastings left, northings right, others marked E or N' in html
    # The figures on the brief's own map (satellite here), the strips on the strip map (topo unless asked).
    assert set(calls) == {'satellite', 'topo'}
    strip_files = [name for name in files if Image.open(io.BytesIO(files[name])).size == (433, 1693)]
    assert len(strip_files) == 12


def _contents(html):
    table = re.search(r'<table class="contents">(.*?)</table>', html, re.S).group(1)
    return re.findall(r'<tr><td>(.*?)</td>', table)


def test_a_strip_that_will_not_draw_is_left_out_and_not_promised():
    model = copy.deepcopy(MODEL)
    model['strips'][0]['course'] = float('nan')
    model['strips'][1]['path'] = [[34.0, -84.0]] * 5000
    model['strips'][2]['grid'] = {'lines': [{'points': [[34, -84]] * 10000}]}
    html, *_ = _build(model)
    assert html.count('class="strip"') == len(model['strips']) - 3
    # A page none of whose strips draw is not laid out, and the contents and the count do not name it.
    for strip in model['strips'][3:6]:
        strip['width'] = -1
    html, *_ = _build(model)
    assert 'Strip charts · ALDERAAN (1–3 of 6)' not in html
    assert 'Strip charts · ALDERAAN (4–6 of 6)' not in html
    assert len(_contents(html)) == len(model['pages']) - 2


def test_a_page_kind_this_server_does_not_know_is_not_promised():
    model = copy.deepcopy(MODEL)
    model['pages'].append({'n': len(model['pages']) + 1, 'kind': 'hologram', 'title': 'A page from the future'})
    html, *_ = _build(model)
    assert 'A page from the future' not in html
    assert len(_contents(html)) == len(MODEL['pages'])
    # The cover promises no count of pages: Chromium decides where a long page breaks (docs/MISSION_BRIEF.md §2.6).
    assert not re.search(r'\d+ pages', html)


def _gridded_strip(course, scale, lat0=34.5, lon0=-84.3):
    """A strip at any course and scale under a synthetic 1 km grid (easting and northing lines a kilometre apart round
    its centre), labelled with two digits as the real grid is."""
    width, length = 0.055 * scale, 0.215 * scale
    reach = math.hypot(width, length) / 2 + 2000

    def at(x, y):
        return [lat0 + y / 111320, lon0 + x / (111320 * math.cos(math.radians(lat0)))]

    n = int(reach // 1000)
    lines = []
    for i in range(-n, n + 1):
        lines.append({'kind': 'minor', 'axis': 'easting', 'text': f'{(i + 40) % 100:02d}', 'i': i,
                      'points': [at(i * 1000, -reach + k * reach / 6) for k in range(13)]})
        lines.append({'kind': 'minor', 'axis': 'northing', 'text': f'{(i + 70) % 100:02d}', 'i': i,
                      'points': [at(-reach + k * reach / 6, i * 1000) for k in range(13)]})
    return {'id': 'r#1', 'centre': [lat0, lon0], 'course': course, 'scale': scale, 'width': width, 'length': length,
            'part': 1, 'parts': 1, 'name': 'R', 'courseText': f'{course:03.0f}°', 'path': [at(0, -length / 3), at(0, length / 3)],
            'points': [], 'legs': [], 'stops': [], 'threats': [], 'grid': {'lines': lines, 'squares': [], 'others': []}}


def _inside_px(pts, width, height):
    total = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps = max(1, int(math.hypot(x1 - x0, y1 - y0) / 2))
        for k in range(steps):
            x, y = x0 + (x1 - x0) * (k + 0.5) / steps, y0 + (y1 - y0) * (k + 0.5) / steps
            if 0 <= x <= width and 0 <= y <= height:
                total += math.hypot(x1 - x0, y1 - y0) / steps
    return total


def test_the_grid_on_a_strip_is_numbered_whatever_its_course():
    """At both scales and every course, a line that runs more than 150 px (a corner's worth) across the strip is numbered
    somewhere: on its own edge, another with E or N, or on itself near an end. At most one goes without, never two
    neighbours, so any line's value can be read off the next."""
    for scale in (100000, 250000):
        for course in range(0, 360, 15):
            strip = _gridded_strip(course + 2.5, scale)
            frame = figures.StripFrame(strip, 'topo')
            numbered = set()
            for name in ('_edge_labels', '_inner_label'):
                real = getattr(figures, name)

                def spy(points, text, axis, *a, real=real, **k):
                    out = real(points, text, axis, *a, **k)
                    if out:
                        numbered.add((axis, text))
                    return out
                mock.patch.object(figures, name, side_effect=spy).start()
            try:
                svg = figures.strip_overlay_svg(frame, strip, 'topo')
            finally:
                mock.patch.stopall()
            bare = {(l['axis'], l['i']) for l in strip['grid']['lines'] if (l['axis'], l['text']) not in numbered
                    and _inside_px([frame.project(*p) for p in l['points']], frame.width, frame.height) > 150}
            assert len(bare) <= 1, (scale, course, bare)
            assert '<rect' not in svg                       # the 1 km squares are the scale: no bar in a corner


def test_a_strip_not_the_printed_size_or_of_the_wrong_types_is_not_drawn():
    assert figures.valid_strip(STRIP)
    for changes in ({'width': STRIP['width'] * 1.05}, {'length': STRIP['length'] * 4, 'width': STRIP['width'] * 4},
                    {'width': STRIP['length']}, {'part': 0}, {'parts': True}, {'part': 1.5}, {'name': ['ALDERAAN']},
                    {'courseText': 31}, {'stops': [{'name': 'X', 'role': ['lz']}]}, {'legs': ['030°']},
                    {'grid': {'lines': [{'kind': ['major'], 'points': []}]}}, {'grid': {'lines': [], 'squares': 'GC'}},
                    {'grid': {'lines': [], 'others': [7]}}, {'grid': {'lines': [{'points': [[34, -84]] * 600}] * 70}}):
        assert not figures.valid_strip(_strip(**changes)), changes
    # A scale the client does not offer still draws, so long as the strip is the printed strip at that scale.
    assert figures.valid_strip(_strip(scale=50000, width=2750, length=10750))


def test_strip_fields_of_the_wrong_type_never_fail_the_brief():
    for change in (lambda m: m.update(strips={'a': 1}), lambda m: m['options'].update(stripMap=['topo']),
                   lambda m: next(p for p in m['pages'] if p['kind'] == 'strips').update(strips='r#1'),
                   lambda m: m['strips'][0].update(grid={'lines': [], 'squares': 3})):
        model = copy.deepcopy(MODEL)
        change(model)
        html, *_ = _build(model)
        assert 'class="page"' in html


def test_a_strip_named_twice_is_laid_out_once():
    model = copy.deepcopy(MODEL)
    first, second = [p for p in model['pages'] if p['kind'] == 'strips'][:2]
    one = first['strips'][0]
    first['strips'] = [one, one, one]
    second['strips'] = [one] + second['strips'][1:]
    html, *_ = _build(model)
    # The first page shows it once (its other two are not named now), the second leaves it out: three fewer in all.
    assert html.count('class="strip"') == len(model['strips']) - 3


def test_a_tile_is_kept_as_it_came_and_fetched_once():
    png = io.BytesIO()
    Image.new('RGB', (256, 256), (10, 20, 30)).save(png, 'PNG')
    answer = mock.Mock(content=png.getvalue(), raise_for_status=lambda: None)
    figures._cache.clear()
    with mock.patch.object(figures._session, 'get', return_value=answer) as get:
        first = figures._fetch_tile('topo', 'https://tiles.test/{z}/{x}/{y}', 9, 1, 2)
        again = figures._fetch_tile('topo', 'https://tiles.test/{z}/{x}/{y}', 9, 1, 2)
    assert get.call_count == 1
    assert first.mode == 'RGB' and again.getpixel((5, 5)) == (10, 20, 30)
    assert figures._cache[('topo', 9, 1, 2)] == png.getvalue()        # the bytes, not a decoded image
    figures._cache.clear()


def test_a_route_colour_is_never_markup():
    model = copy.deepcopy(MODEL)
    model['geometry']['routes']['route:set-opdk/r-bespin']['color'] = '"/><text>INJECTED</text><x a="'
    html, *_ = _build(model)
    assert 'INJECTED' not in html
    assert figures.ROUTE_COLOUR in html
