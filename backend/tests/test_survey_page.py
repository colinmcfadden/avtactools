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
    """``snapshot`` with what survey/obstacles.py would find: on the approach (flown north, so its lane lies south) a 98 ft tree 200 m out
    that sets its angle, a 131 ft one 600 m out past its descent from 100 ft AGL; a 39 ft tree in the LZ, a building 50 m outside it,
    and a treetop line along the profile."""
    from survey import obstacles

    bins = [{'groundFt': 2200.0, 'topFt': 2200.0, 'tallestFt': None} for _ in range(100)]
    bins[20] = {'groundFt': 2200.0, 'topFt': 2298.4, 'tallestFt': 98.4}
    bins[60] = {'groundFt': 2200.0, 'topFt': 2331.2, 'tallestFt': 131.2}
    spot = {'lat': TARGET[0], 'lon': TARGET[1]}
    steps = len(snapshot['terrain']['profiles']['lines'][0]['elevationsFt'])
    above = [None] * steps
    above[3], above[4] = 98.4, 2.0                                                 # a tree line, and a bush under a metre
    tree = {**spot, 'heightFt': 98.4, 'topFt': 2298.4, 'bearingDeg': 180.0, 'distanceM': 200.0, 'kind': 'vegetation',
            'fromTargetM': 200.0, 'offsetM': 4.0, 'riseFt': 98.4, 'baseFt': 2200.0}
    tall = {'fromM': 600.0, 'toM': 610.0, 'overFt': 131.2,
            'obstacle': {**spot, 'heightFt': 131.2, 'topFt': 2331.2, 'bearingDeg': 180.0, 'distanceM': 600.0, 'kind': 'vegetation',
                         'fromTargetM': 600.0, 'offsetM': -3.0}}
    found = {
        'status': 'measured', 'words': None, 'survey': {'name': 'GA_Statewide_B3_2018', 'year': 2018}, 'points': 1000, 'pieces': 12,
        'approach': {'headingDeg': 360.0, 'bearingDeg': 180.0, 'start': {'groundFt': 2200.0},
                     'edge': {**spot, 'fromTargetM': 60.0, 'groundFt': 2200.0},
                     'lengthM': 1000.0, 'halfWidthM': 10.0, 'binM': 10.0, 'bins': bins, 'covered': 100,
                     'clearance': {'angleDeg': 8.5, 'obstacle': tree},
                     'descent': obstacles.descent_point(8.5, 2200.0, [b['groundFt'] for b in bins]),
                     'tall': [tall], 'missing': None},
        'exit': None,
        'inside': {**spot, 'heightFt': 39.4, 'topFt': 2240.0, 'bearingDeg': 33.7, 'distanceM': 36.1, 'kind': 'vegetation'},
        'around': {**spot, 'heightFt': 26.2, 'topFt': 2226.0, 'bearingDeg': 0.0, 'distanceM': 110.0, 'kind': 'building', 'fromEdgeM': 50.0},
        'standing': {'overM': 2.0, 'count': 40},
        'trees': [{**spot, 'heightFt': 39.4}],
        'heightMap': None,
        'profileTops': {'heading': 0, 'aboveFt': above},
    }
    return {**snapshot, 'obstacles': {**found, **over}}


def test_the_lidar_s_angle_is_printed_in_place_of_the_survey_profile(snapshot):
    model = page.compile_page(source(lidar(snapshot)), book=BOOK)
    clearance = model['clearance']
    assert (clearance['status'], clearance['survey'], clearance['printed']) == ('measured', 'GA_STATEWIDE_B3_2018', 'computed')
    approach = clearance['approach']
    descent = round(30.48 / math.tan(math.radians(8.5)), 1)
    assert (approach['angle'], approach['climb'], approach['what']) == (
        'APCH 360° · 8.5°', 'DESCEND FROM 100′ AGL AT 204 M', '98′ TREE AT 200 M')
    assert approach['words'] == 'APCH 360° · 8.5° · DESCEND FROM 100′ AGL AT 204 M · 98′ TREE AT 200 M'
    assert (approach['angleDeg'], approach['lidarDeg'], approach['typed'], approach['descentM']) == (8.5, 8.5, False, descent)
    # The path from the touchdown point to its descent, then 100 ft over the track's ground; the 131 ft tree past it, over that.
    assert approach['path'] == [[0.0, 2200.0], [descent, 2300.0]]
    assert approach['level'][0] == [descent, 2300.0] and approach['level'][-1] == [995.0, 2300.0]
    assert approach['tall'] == [{'fromTargetM': 600.0, 'overFt': 131.2, 'topFt': 2331.2, 'kind': 'TREE'}]
    assert approach['obstacle'] == {'fromTargetM': 200.0, 'riseFt': 98.4, 'kind': 'vegetation'}
    assert clearance['exit'] is None
    assert clearance['inside'] == '39′ TREE AT 034° 36 M'
    assert clearance['around'] == '26′ BUILDING AT 360° 110 M (50 M OUT)'
    assert (clearance['standing'], clearance['trees']) == (40, 1)
    assert model['hazards'][0] == {
        'text': 'LiDAR: 39 ft tree in the LZ, 36 m at 034° from the touchdown point; 1 tree of 5 m or more.', 'from': 'computed'}
    # What stands over 100 ft past the descent is a note: the hazards' box holds three lines, and the field's own come last.
    assert "LiDAR: past the approach's descent from 100 ft AGL, a tree 600 m out stands 131 ft above the track's ground." in model['notes']
    assert not any('100 ft AGL' in h['text'] for h in model['hazards'])
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'CLEARANCE · 2018 · GA_STATEWIDE_B3_2018' in html and 'SURVEY PROFILE' not in html       # the year before a name cut short
    assert 'class="clearance"' in html and 'NO LIDAR RETURNS' not in html
    assert 'DESCEND FROM 100′ AGL AT 204 M' in html
    assert 'Within 100 m: 26′ BUILDING AT 360° 110 M (50 M OUT)' in html


def test_an_angle_the_editor_typed_is_printed_as_theirs(snapshot):
    from survey import compare

    found = lidar(snapshot)
    row = next(r for r in compare.compare(DONOVAN['field'], found, {}) if r['key'] == 'approach')
    choice = {'approach': {'choice': 'typed', 'value': 12.0, 'reason': 'The tree line 200 m out was cut in 2024.', 'by': 'CW3 Admin',
                           'at': '2026-10-27T12:00:00Z', 'field': row['field'], 'computed': row['computed']}}
    model = page.compile_page(source(found, overrides=choice), book=BOOK)
    assert model['clearance']['printed'] == 'typed'
    approach = model['clearance']['approach']
    assert (approach['angle'], approach['climb'], approach['what']) == (
        'APCH 360° · 12.0°', 'DESCEND FROM 100′ AGL AT 143 M', "EDITOR'S · LIDAR 8.5°")
    assert (approach['angleDeg'], approach['lidarDeg'], approach['typed']) == (12.0, 8.5, True)
    assert approach['descentM'] == round(30.48 / math.tan(math.radians(12.0)), 1)
    assert approach['obstacle'] == {'fromTargetM': 200.0, 'riseFt': 98.4, 'kind': 'vegetation'}   # the LiDAR's still drawn
    assert [t['fromTargetM'] for t in approach['tall']] == [600.0]
    assert 'set by the editor' in changes.first(DONOVAN['field'], found, choice)
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'APCH 360° · 12.0°' in html and "EDITOR'S · LIDAR 8.5°" in html and 'SURVEY PROFILE' not in html
    # Measured again to another angle, the editor's no longer stands: the LiDAR's is printed, and it is to be looked at again.
    again = lidar(snapshot)
    again['obstacles']['approach']['clearance']['angleDeg'] = 9.0
    row = next(r for r in compare.compare(DONOVAN['field'], again, choice) if r['key'] == 'approach')
    assert (row['printed'], row['overrideStands'], row['open'], row['disagrees']) == ('computed', False, True, False)
    assert page.compile_page(source(again, overrides=choice), book=BOOK)['clearance']['approach']['angle'] == 'APCH 360° · 9.0°'


def test_only_what_stands_over_100_ft_past_the_descent_is_flagged(snapshot):
    from survey import compare

    # An editor's 3.0°, shallower than the LiDAR's: its descent from 100 ft AGL begins 582 m out, so the 131 ft tree 600 m out is past
    # it and one 300 m out is under its path (which the LiDAR's own angle clears), not in the way at 100 ft AGL.
    found = lidar(snapshot)
    lane = found['obstacles']['approach']
    lane['tall'] = [{**lane['tall'][0], 'fromM': 300.0, 'toM': 310.0, 'obstacle': {**lane['tall'][0]['obstacle'], 'fromTargetM': 300.0}},
                    lane['tall'][0]]
    row = next(r for r in compare.compare(DONOVAN['field'], found, {}) if r['key'] == 'approach')
    choice = {'approach': {'choice': 'typed', 'value': 3.0, 'reason': 'Flown so by the 2-58th', 'field': row['field'],
                           'computed': row['computed']}}
    model = page.compile_page(source(found, overrides=choice), book=BOOK)
    assert model['clearance']['approach']['climb'] == 'DESCEND FROM 100′ AGL AT 582 M'
    assert [t['fromTargetM'] for t in model['clearance']['approach']['tall']] == [600.0]
    assert [n for n in model['notes'] if '100 ft AGL' in n] == [
        "LiDAR: past the approach's descent from 100 ft AGL, a tree 600 m out stands 131 ft above the track's ground."]


def test_a_descent_past_the_lane_is_said_so(snapshot):
    found = lidar(snapshot)
    lane = found['obstacles']['approach']
    lane['clearance']['angleDeg'] = 1.5
    model = page.compile_page(source(found), book=BOOK)
    approach = model['clearance']['approach']
    assert approach['climb'] == 'DESCEND FROM 100′ AGL PAST 1,000 M'
    assert approach['path'] == [[0.0, 2200.0], [1000.0, 2200.0 + 1000.0 * math.tan(math.radians(1.5)) * 3.28084]]
    assert (approach['level'], approach['tall'], approach['descentM']) == ([], [], None)   # all of the lane is under the path
    assert not any('100 ft AGL' in n for n in model['notes'])


def test_what_stands_over_100_ft_past_the_exit_s_climb_is_a_note(snapshot):
    from survey import obstacles

    found = lidar(snapshot)
    lane = json.loads(json.dumps(found['obstacles']['approach']))
    lane.update({'headingDeg': 180.0, 'bearingDeg': 180.0})
    lane['tall'] = [{'fromM': 450.0, 'toM': 470.0, 'overFt': 164.7,
                     'obstacle': {**lane['tall'][0]['obstacle'], 'kind': 'tower', 'heightFt': 164.0, 'fromTargetM': 459.0}},
                    {**lane['tall'][0], 'fromM': 900.0, 'toM': 910.0}]
    lane['descent'] = obstacles.descent_point(8.5, 2200.0, [b['groundFt'] for b in lane['bins']])
    found['obstacles']['exit'] = lane
    model = page.compile_page(source(found), book=BOOK)
    assert model['clearance']['exit']['climb'] == 'CLIMB TO 100′ AGL AT 204 M'
    assert "LiDAR: past where the exit is 100 ft AGL, a tower 459 m out stands 165 ft above the track's ground; 1 more place." in model['notes']


def test_a_lane_measured_by_an_earlier_ez_pz_is_not_printed_as_this_one(snapshot):
    from survey import compare

    found = lidar(snapshot)
    del found['obstacles']['approach']['start']                                  # a version 4 snapshot's, from the LZ's edge
    row = next(r for r in compare.compare(DONOVAN['field'], found, {}) if r['key'] == 'approach')
    assert (row['computed'], row['printed']) == ('', 'field')
    model = page.compile_page(source(found), book=BOOK)
    assert (model['clearance']['printed'], model['clearance']['note']) == ('field', 'LiDAR: APCH 360° · MEASURED BY AN EARLIER EZ/PZ')


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
    choice = {'approach': {'choice': 'field', 'reason': 'The tree line was cut in 2024.', 'field': '8.7° (100 ft at 200 m)',
                           'computed': '8.5° (LiDAR: 98 ft tree 200 m out)'}}
    model = page.compile_page(source(lidar(snapshot), overrides=choice), book=BOOK)
    assert model['clearance']['printed'] == 'field'
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'SURVEY PROFILE' in html and 'Printed over the LiDAR&#39;s by the survey&#39;s editor.' in html


def test_the_survey_profile_s_angle_is_held_against_the_lidar_s(snapshot):
    from survey import compare

    def approach(field, snap, overrides=None):
        return next(r for r in compare.compare(field, snap, overrides or {}) if r['key'] == 'approach')

    # The profile's "100 ft at 200 m": 100 ft up 200 m out, where the descent from 100 ft AGL begins, so 8.7°.
    row = approach(DONOVAN['field'], lidar(snapshot))
    assert (row['label'], row['field'], row['computed'], row['apart'], row['disagrees']) == (
        'Approach angle', '8.7° (100 ft at 200 m)', '8.5° (LiDAR: 98 ft tree 200 m out)', '0.2° apart', False)
    steeper = lidar(snapshot)
    steeper['obstacles']['approach']['clearance']['angleDeg'] = 14.0
    row = approach(DONOVAN['field'], steeper)
    assert (row['apart'], row['disagrees'], row['printed']) == ('5.3° apart', True, 'computed')
    row = approach({**DONOVAN['field'], 'profileObstacleFt': '0'}, lidar(snapshot))
    assert (row['field'], row['apart'], row['disagrees']) == ('0 ft at 200 m', 'cannot be read', True)
    row = approach(DONOVAN['field'], snapshot)
    assert (row['computed'], row['printed']) == ('', 'field')                      # no LiDAR here: the survey's own is printed


def test_with_no_approach_measured_the_field_s_profile_is_printed_and_why(snapshot):
    model = page.compile_page(source(lidar(snapshot, approach=None)), book=BOOK)
    clearance = model['clearance']
    assert (clearance['printed'], clearance['note']) == ('field', 'LiDAR: no best approach to measure along.')
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'SURVEY PROFILE' in html and 'LiDAR: no best approach to measure along.' in html
    assert {'text': 'LiDAR: 39 ft tree in the LZ, 36 m at 034° from the touchdown point; 1 tree of 5 m or more.',
            'from': 'computed'} in model['hazards']                                   # what stands in the LZ is still said
    # Without a profile of the field's either, the LiDAR's box says what stands in and round the LZ.
    bare = source(lidar(snapshot, approach=None))
    bare['field'] = {**bare['field'], 'profileObstacleFt': '', 'profileDistanceM': ''}
    model = page.compile_page(bare, book=BOOK)
    assert model['clearance']['printed'] == 'computed'
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'CLEARANCE · 2018 · GA_STATEWIDE_B3_2018' in html and 'In the LZ: 39′ TREE AT 034° 36 M' in html


def test_an_approach_with_no_clearance_prints_the_field_s_profile_and_says_why(snapshot):
    # The review's case: the approach was measured, but the LiDAR has no ground at the touchdown point to measure from. The page said
    # "no LiDAR returns in its corridor" and dropped the field's own profile.
    found = lidar(snapshot)
    found['obstacles']['approach'].update({'clearance': None, 'descent': None, 'missing': 'no_target_ground'})
    model = page.compile_page(source(found), book=BOOK)
    clearance = model['clearance']
    assert clearance['printed'] == 'field'
    assert clearance['note'] == 'LiDAR: APCH 360° · NO GROUND AT THE TOUCHDOWN POINT'
    with patch('brief.figures._fetch_tile', _tile):
        html, _files = pages.build(model, lambda _id: None)
    assert 'SURVEY PROFILE' in html and '100′' in html and 'LiDAR: APCH 360° · NO GROUND AT THE TOUCHDOWN POINT' in html


def test_nothing_in_the_way_is_said_and_held_against_the_field_s(snapshot):
    from survey import compare

    found = lidar(snapshot)
    found['obstacles']['approach'].update({'clearance': {'angleDeg': 0.0, 'obstacle': None}, 'descent': None, 'tall': []})
    model = page.compile_page(source(found), book=BOOK)
    assert model['clearance']['approach']['words'] == 'APCH 360° · 0.0° · NOTHING IN THE WAY'
    assert (model['clearance']['approach']['path'], model['clearance']['approach']['level']) == ([], [])
    row = next(r for r in compare.compare(DONOVAN['field'], found, {}) if r['key'] == 'approach')
    assert (row['computed'], row['apart'], row['disagrees']) == ('0.0° (LiDAR: nothing in the way)', '8.7° apart', True)


def test_a_lane_the_survey_covers_in_part_says_so(snapshot):
    found = lidar(snapshot)
    found['obstacles']['approach']['covered'] = 31
    model = page.compile_page(source(found), book=BOOK)
    assert model['clearance']['approach']['partial'] == 'LIDAR OVER 310 OF 1,000 M'
    assert 'LIDAR OVER 310 OF 1,000 M' in figures.clearance(model['clearance'])           # in its corner, in place of 1 KM


def test_low_vegetation_is_brush_and_the_figure_draws_the_obstacle_s_rise(snapshot):
    import re

    found = lidar(snapshot)
    found['obstacles']['inside'].update({'heightFt': 9.8})
    found['obstacles']['approach']['clearance']['obstacle'].update({'heightFt': 49.2, 'riseFt': 114.0})   # on ground 65 ft up
    model = page.compile_page(source(found), book=BOOK)
    assert model['clearance']['inside'] == '10′ BRUSH AT 034° 36 M'
    spec = model['clearance']['approach']
    assert spec['what'] == '49′ TREE AT 200 M'
    assert spec['obstacle'] == {'fromTargetM': 200.0, 'riseFt': 114.0, 'kind': 'vegetation'}
    drawn = figures.clearance(model['clearance'])
    # The triangle's tip stands over the touchdown point's ground and the obstacle's rise (2314 ft), where the angle sees it.
    values = [v for v in spec['groundFt'] + spec['topsFt'] + [spec['startFt']] if v is not None]
    values += [h for _m, h in spec['path'] + spec['level']]
    span = max(max(values) - min(values), 60.0)
    floor = min(values) - span * 0.06
    tip = float(re.search(r'<polygon points="[-0-9.]+,([-0-9.]+) ', drawn).group(1))
    assert abs(tip - ((104 - 14) - (2314.0 - floor) / (span * 1.06) * ((104 - 14) - 50) - 6)) < 0.1
    assert drawn.count('r="3"') == 1 and drawn.count('r="2.4"') == 1                 # the 131 ft tree past the descent; the descent
    # The LZ between the two ways, on the touchdown point's ground.
    lz_y = float(re.search(r'<rect x="[-0-9.]+" y="([-0-9.]+)"', drawn).group(1))
    assert abs(lz_y - ((104 - 14) - (2200.0 - floor) / (span * 1.06) * ((104 - 14) - 50) - 3)) < 0.1
