"""A survey's one-page PDF, short of Chromium (docs/LZ_SURVEYS.md §5): the page model from LZ DONOVAN's field survey and a measurement over
a synthetic DEM, the figures (the map's tiles stood in for), the CHANGES line, and the slope profile held to the web's own fixture."""

import json
import math
import sys
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import pytest  # noqa: E402
from PIL import Image  # noqa: E402

from survey import changes, compile as page, figures, measure, pages, profile  # noqa: E402
from test_survey_measure import DONOVAN, TARGET, Terrain, square  # noqa: E402

PROFILE = json.loads((BACKEND_DIR.parent / 'contracts' / 'fixtures' / 'planning' / 'profile.json').read_text(encoding='utf-8'))
DIAGRAM = {'schemaVersion': 2, 'target': {'lat': TARGET[0], 'lon': TARGET[1]}, 'analysis': {'detectedLZ': square(60)}}
BOOK = {'name': 'AASF 1 HLZ Survey Book', 'unitLine': 'AASF 1'}


def _tile(*_args, **_kwargs):
    return Image.new('RGB', (1024, 1024), (60, 90, 60))


@pytest.fixture(scope='module')
def snapshot():
    with Terrain(east_deg=4.0):
        return measure.measure(DIAGRAM, DONOVAN['field'], 'CW3 Admin')


def source(snapshot, **changes_):
    return {'hlzId': DONOVAN['hlzId'], 'name': DONOVAN['name'], 'status': 'draft', 'nextRevision': 1, 'field': DONOVAN['field'],
            'overrides': {}, 'diagram': DIAGRAM, 'snapshot': snapshot,
            'assets': [{'id': 7, 'kind': 'photo_north', 'taken_on': 'OCT 2023'}, {'id': 8, 'kind': 'photo_approach', 'heading_deg': 360}],
            **changes_}


def _same(got, expected, path=''):
    if isinstance(expected, float) or isinstance(got, float):
        assert got is not None and expected is not None, path
        assert math.isclose(got, expected, rel_tol=1e-9, abs_tol=1e-9), (path, got, expected)
    elif isinstance(expected, dict):
        assert set(got) == set(expected), path
        for key in expected:
            _same(got[key], expected[key], f'{path}.{key}')
    elif isinstance(expected, list):
        assert len(got) == len(expected), path
        for i, (a, b) in enumerate(zip(got, expected)):
            _same(a, b, f'{path}[{i}]')
    else:
        assert got == expected, (path, got, expected)


@pytest.mark.parametrize('case', PROFILE['cases'], ids=lambda c: c['name'])
def test_the_profile_is_the_line_the_web_draws(case):
    ground = PROFILE['ground'].get(case['ground']) if isinstance(case['ground'], str) else case['ground']
    _same(profile.slope_profile_for(ground, case['heading']), case['expected'])


@pytest.mark.parametrize('case', PROFILE['line'], ids=lambda c: f"{c['ground']} {c['heading']}")
def test_one_line_is_the_one_the_web_reads(case):
    _same(profile.profile_line(PROFILE['ground'][case['ground']], case['heading']), case['expected'])


def test_javascript_s_rounding_is_kept():
    assert profile.to_fixed(0.25, 1) == '0.3'          # toFixed rounds a tie up; Python's format would give 0.2
    assert profile.js_round(2.5) == 3 and profile.js_round(-2.5) == -2


def test_the_page_prints_the_measured_values_and_the_field_s_judgements(snapshot):
    model = page.compile_page(source(snapshot), book=BOOK, page={'n': 5, 'of': 44}, version='1.7.6')
    facts = model['facts']
    assert (facts['grid']['value'], facts['grid']['from']) == (snapshot['grid'], 'computed')
    assert facts['latLong']['value'].startswith('N34°') and facts['latLong']['from'] == 'computed'
    assert facts['elevation'] == {'value': f"{snapshot['elevation']['ft']:,}′", 'from': 'computed'}
    assert (facts['approach'], facts['exit'], facts['maxAc']) == ('360°', '360°', '2 DAY / 2 NIGHT')
    assert (facts['pprWho'], facts['ppr']) == ('5th RTB Ops', '706-864-3327 x5233')
    assert facts['surface'] == 'GRASS'
    assert facts['slope']['from'] == 'computed' and '(P95)' in facts['slope']['value']
    assert (model['mark'], model['revision'], model['page'], model['book']['name']) == ('DRAFT', 'REV 1', {'n': 5, 'of': 44},
                                                                                        'AASF 1 HLZ SURVEY BOOK')
    assert model['hazards'][-1] == {'text': 'Tall dead trees in center of LZ.', 'from': 'field'}
    assert model['notes'][0].startswith('To reduce the possibility')            # the field's warning first
    assert any(note.startswith('Area fits ') for note in model['notes'])
    assert model['surveyProfile'] == {'obstacleFt': 100, 'distanceM': 200, 'angleDeg': 8.7, 'who': 'OCT 2023 · CW3 J. SIMPSON, ASO'}
    assert model['profiles']['along']['title'].startswith('PROFILE ALONG 360°')
    assert model['profiles']['across']['start'] == '270°' and model['profiles']['across']['end'] == '090°'
    assert model['terrain']['rows'][0]['label'] == '95th percentile'
    assert [p['caption'] for p in model['photos']] == ['NORTH · OCT 23', 'APCH 360° · OCT 23']


def test_a_choice_of_the_survey_s_value_is_printed_and_said_so(snapshot):
    rows = {r['key']: r for r in page.compare.compare(DONOVAN['field'], snapshot, {})}
    choice = {'elevation': {'choice': 'field', 'reason': 'GPS', 'field': rows['elevation']['field'],
                            'computed': rows['elevation']['computed']}}
    model = page.compile_page(source(snapshot, overrides=choice), book=BOOK)
    assert model['facts']['elevation'] == {'value': '1,850′', 'from': 'field'}


def test_an_unmeasured_survey_prints_the_field_s_values_and_says_so():
    model = page.compile_page(source(None), book=BOOK)
    assert model['measured'] is False
    assert (model['facts']['grid']['value'], model['facts']['grid']['from']) == ('16S GD 6341 3199', 'field')
    assert model['facts']['axis'] == '—'
    assert model['profiles'] is None and model['terrain'] is None


def test_a_published_page_carries_its_signoff_and_no_mark(snapshot):
    signoff = {'name': 'J. Doe', 'rank': 'cw4', 'title': 'Standardization IP', 'date': '27 OCT 2026'}
    model = page.compile_page(source(snapshot, status='published', revision=3, signoff=signoff, changes='Grid refined.'), book=BOOK)
    assert (model['mark'], model['revision']) == (None, 'REV 3')
    assert model['footer'] == {'changes': 'Grid refined.', 'signoff': 'APPROVED: CW4 J. DOE, STANDARDIZATION IP · 27 OCT 2026'}


@pytest.mark.parametrize('typed, words', [('360', '360°'), ('0', '360°'), ('045°', '045°'), ('270 deg', '270°'), ('north', None),
                                          ('400', None)])
def test_a_heading_as_the_page_prints_it(typed, words):
    assert page.heading_words(typed) == words


@pytest.mark.parametrize('typed, who, number', [
    ('5th RTB Ops 706-864-3327 x5233', '5th RTB Ops', '706-864-3327 x5233'),
    ('5th RTB Ops: (706) 864-3327', '5th RTB Ops', '(706) 864-3327'),
    ('Call range control on 38.90', '', 'Call range control on 38.90'),
])
def test_a_ppr_line_is_split_into_who_and_the_number(typed, who, number):
    assert page.split_ppr(typed) == (who, number)


def test_the_first_changes_line_is_against_the_old_book(snapshot):
    line = changes.first(DONOVAN['field'], snapshot, {})
    assert line == 'New format. Grid refined; lat/long recomputed; elevation, size and slope from LZ/PZ.'
    assert changes.first(DONOVAN['field'], None, {}) == 'New format.'


def test_the_plan_figure_is_drawn_over_the_map(snapshot):
    model = page.compile_page(source(snapshot), book=BOOK)
    with patch('brief.figures._fetch_tile', _tile):
        drawn = figures.plan(model['plan'])
    assert drawn['jpeg'][:2] == b'\xff\xd8'
    for words in ('APCH 360°', 'EXIT 360°', 'DONOVAN', 'stroke-dasharray="16 10"'):
        assert words in drawn['svg']
    assert figures.plan({'target': None}) is None


def test_a_profile_with_no_ground_is_not_drawn():
    line = {'distancesFt': [0, 10], 'elevationsFt': [None, 100], 'lz': None, 'start': 'A', 'end': 'B'}
    assert figures.profile(line, 'side') is None


def test_the_page_escapes_what_was_typed_and_has_its_pictures(snapshot):
    model = page.compile_page(source(snapshot, name='LZ <b>X</b>', field={**DONOVAN['field'], 'hazards': '<img src=x onerror=1>'}),
                              book=BOOK)
    with patch('brief.figures._fetch_tile', _tile):
        html, files = pages.build(model, lambda asset_id: (b'\xff\xd8photo', 'image/jpeg') if asset_id == 7 else None)
    assert '<b>X</b>' not in html and '&lt;img src=x' in html
    assert sorted(files) == ['photo-1.jpg', 'plan.jpg']                              # the approach photo is gone from the store
    assert html.count('UNCLASSIFIED') == 2
    assert 'No photo.' in html


# -- What the LiDAR found (step L4) -----------------------------------------------------------------------------------

def lidar(snapshot, **over):
    """``snapshot`` with what survey/obstacles.py would find: a 98 ft tree line 198 m out on the approach, a 39 ft tree in the LZ, a
    building 50 m outside it, and a treetop line along the profile."""
    bins = [{'groundFt': 2200.0, 'topFt': 2200.0, 'tallestFt': None} for _ in range(100)]
    bins[19] = {'groundFt': 2200.0, 'topFt': 2298.4, 'tallestFt': 98.4}
    spot = {'lat': TARGET[0], 'lon': TARGET[1]}
    steps = len(snapshot['terrain']['profiles']['lines'][0]['elevationsFt'])
    above = [None] * steps
    above[3], above[4] = 98.4, 2.0                                                 # a tree line, and a bush under a metre
    found = {
        'status': 'measured', 'words': None, 'survey': {'name': 'GA_Statewide_B3_2018', 'year': 2018}, 'points': 1000, 'pieces': 12,
        'approach': {'headingDeg': 360.0, 'bearingDeg': 180.0, 'edge': {**spot, 'fromTargetM': 60.0, 'groundFt': 2200.0},
                     'lengthM': 1000.0, 'halfWidthM': 30.0, 'binM': 10.0, 'bins': bins, 'covered': 100,
                     'clearance': {'angleDeg': 8.6, 'obstacle': {**spot, 'heightFt': 98.4, 'topFt': 2298.4, 'bearingDeg': 180.0,
                                                                 'distanceM': 258.0, 'kind': 'vegetation', 'fromEdgeM': 198.0,
                                                                 'offsetM': 4.0}},
                     'fromTarget': None, 'tallest': None},
        'exit': None,
        'inside': {**spot, 'heightFt': 39.4, 'topFt': 2240.0, 'bearingDeg': 33.7, 'distanceM': 36.1, 'kind': 'vegetation'},
        'around': {**spot, 'heightFt': 26.2, 'topFt': 2226.0, 'bearingDeg': 0.0, 'distanceM': 110.0, 'kind': 'building', 'fromEdgeM': 50.0},
        'standing': {'overM': 2.0, 'count': 40, 'cells': [[TARGET[0], TARGET[1]]]},
        'trees': [{**spot, 'heightFt': 39.4}],
        'heightMap': None,
        'profileTops': {'heading': 0, 'aboveFt': above},
    }
    return {**snapshot, 'obstacles': {**found, **over}}


def test_the_lidar_s_clearance_is_printed_in_place_of_the_survey_profile(snapshot):
    model = page.compile_page(source(lidar(snapshot)), book=BOOK)
    clearance = model['clearance']
    assert (clearance['status'], clearance['survey'], clearance['printed']) == ('measured', 'GA_STATEWIDE_B3_2018', 'computed')
    assert clearance['approach']['words'] == 'APCH 360° · 8.6° · 98′ TREE AT 198 M'
    assert clearance['exit'] is None
    assert clearance['inside'] == '39′ TREE AT 034° 36 M'
    assert clearance['around'] == '26′ BUILDING AT 360° 110 M (50 M OUT)'
    assert (clearance['standing'], clearance['trees']) == (40, 1)
    assert {'text': 'LiDAR: 39 ft tree in the LZ, 36 m at 034° from the touchdown point; 1 tree of 5 m or more.',
            'from': 'computed'} in model['hazards']
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'CLEARANCE · GA_STATEWIDE_B3_2018' in html and 'SURVEY PROFILE' not in html
    assert 'class="clearance"' in html and 'NO LIDAR RETURNS' not in html
    assert 'Within 100 m: 26′ BUILDING AT 360° 110 M (50 M OUT)' in html


def test_the_profile_s_treetops_stand_on_the_ground_it_draws(snapshot):
    model = page.compile_page(source(lidar(snapshot)), book=BOOK)
    ground = model['profiles']['along']['elevationsFt']
    tops = model['clearance']['alongTreetopsFt']
    assert tops[3] == ground[3] + 98.4                                             # on the DEM's ground, whatever the LiDAR's is
    assert tops[4] is None and tops[0] is None                                     # under a metre, and nothing: no line
    backwards = page._along_tops({'heading': 0, 'aboveFt': [None, 50.0, None]},
                                 {'along': {'heading': 180, 'elevationsFt': [10.0, 20.0, 30.0]}})
    assert backwards == [None, 70.0, None]
    assert page._along_tops({'heading': 2, 'aboveFt': [50.0]}, {'along': {'heading': 0, 'elevationsFt': [10.0]}}) is None
    line = {'distancesFt': [0, 10, 20, 30, 40], 'elevationsFt': [100, 100, 100, 100, 100], 'lz': [10, 20], 'start': 'A', 'end': 'B'}
    drawn = figures.profile(line, 'side', treetops=[None, 150, 160, None, 140])
    assert drawn.count('stroke="#2e7d32"') == 2                                    # two stands, not one line across the gap


def test_without_lidar_numbers_the_field_s_profile_is_printed_and_why(snapshot):
    model = page.compile_page(source(snapshot), book=BOOK)                          # this server reads no LiDAR
    assert (model['clearance']['status'], model['clearance']['words']) == (
        'unavailable', 'This server does not read the USGS LiDAR, so nothing standing is measured.')
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'SURVEY PROFILE' in html and 'LiDAR: This server does not read the USGS LiDAR' in html


def test_an_editor_s_choice_of_the_survey_s_profile_is_printed(snapshot):
    choice = {'obstacle': {'choice': 'field', 'reason': 'The tree line was cut in 2024.', 'field': '100 ft at 200 m',
                           'computed': '98 ft at 198 m (LiDAR)'}}
    model = page.compile_page(source(lidar(snapshot), overrides=choice), book=BOOK)
    assert model['clearance']['printed'] == 'field'
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'SURVEY PROFILE' in html and "Printed over the LiDAR's by the survey's editor." in html


def test_the_survey_profile_s_obstacle_is_held_against_the_lidar_s(snapshot):
    from survey import compare

    row = next(r for r in compare.compare(DONOVAN['field'], lidar(snapshot), {}) if r['key'] == 'obstacle')
    assert (row['field'], row['computed'], row['apart'], row['disagrees']) == ('100 ft at 200 m', '98 ft at 198 m (LiDAR)', '2 ft, 2 m apart', False)
    taller = lidar(snapshot)
    taller['obstacles']['approach']['clearance']['obstacle']['heightFt'] = 150.0
    row = next(r for r in compare.compare(DONOVAN['field'], taller, {}) if r['key'] == 'obstacle')
    assert row['disagrees'] and row['printed'] == 'computed'
    row = next(r for r in compare.compare(DONOVAN['field'], snapshot, {}) if r['key'] == 'obstacle')
    assert (row['computed'], row['printed']) == ('', 'field')                      # no LiDAR here: the survey's own is printed


def test_with_no_approach_measured_the_lidar_s_box_still_says_what_stands(snapshot):
    model = page.compile_page(source(lidar(snapshot, approach=None)), book=BOOK)
    assert model['clearance']['printed'] == 'computed'                            # the survey's profile has nothing to stand against
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'CLEARANCE · GA_STATEWIDE_B3_2018' in html and 'In the LZ: 39′ TREE AT 034° 36 M' in html
