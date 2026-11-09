"""The old HLZ survey book read into an import bundle (tools/import_survey_book.py; docs/LZ_SURVEYS.md §6), over two of its real pages
as poppler lays them out (tools/fixtures/survey_book_pages.xml: text and picture places, no PDF library and no poppler needed here)."""

import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from unittest.mock import patch

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools'))

import import_survey_book as book  # noqa: E402

PAGES = {int(p.get('number')): p for p in ET.parse(REPO / 'tools' / 'fixtures' / 'survey_book_pages.xml').getroot().iter('page')}
DONOVAN = json.loads((REPO / 'contracts' / 'fixtures' / 'surveys' / 'donovan.json').read_text(encoding='utf-8'))


def _page(lines, width=918):
    """A page of pdftohtml XML from (top, left, words) pieces of text and two photos down the right."""
    texts = ''.join(f'<text top="{top}" left="{left}" width="{len(words) * 7}" height="14" font="0">{words}</text>'
                    for top, left, words in lines)
    return ET.fromstring(f'<page number="9" width="{width}" height="1188"><fontspec id="0" size="14"/>'
                         f'<image top="188" left="355" width="530" height="400" src="p-1.jpg"/>'
                         f'<image top="650" left="355" width="530" height="400" src="p-2.jpg"/>{texts}</page>')


def test_lz_donovan_reads_as_the_book_has_it():
    page = book.read_page(PAGES[5], 5)
    field = page['field']
    assert (page['page'], page['hlzId'], page['name']) == (5, 'GA 034', 'LZ DONOVAN')
    assert (field['grid'], field['latLong']) == (DONOVAN['field']['grid'], DONOVAN['field']['latLong'])
    assert (field['maxAcDay'], field['maxAcNight'], field['approachDeg'], field['exitDeg']) == ('2', '2', '360', '360')
    assert (field['elevationFt'], field['surface'], field['slope']) == ('1850', 'grass', '-10° roll (left side low)')
    assert field['ppr'] == '5th RTB Ops: 706-864-3327 x5233'                     # a raised "th" joins its "5"
    assert field['hazards'] == DONOVAN['field']['hazards']
    assert field['warning'] == DONOVAN['field']['warning']
    assert (field['profileObstacleFt'], field['profileDistanceM']) == ('100', '200')
    assert (field['surveyedOn'], field['surveyUnit'], field['surveyedBy']) == ('OCT 2023', 'AASF1', 'CW3 J. SIMPSON, ASO')
    assert [(p['kind'], p['heading_deg']) for p in page['photos']] == [('photo_north', 0.0), ('photo_approach', 360.0)]
    assert page['unread'] == [] and page['warnings'] == []


def test_big_stamp_s_split_profile_height_and_its_blank_surface():
    page = book.read_page(PAGES[1], 1)
    field = page['field']
    assert (page['hlzId'], page['name']) == ('NGA 030', 'LZ BIG STAMP')
    assert (field['profileObstacleFt'], field['profileDistanceM']) == ('80', '75')    # "8" and a raised "0’"
    assert field['slope'] == '+9° roll (right side down) +5° pitch'                   # two lines
    assert field['remarks'].startswith('To reduce the possibility')                   # "Remarks: :" loses its second colon
    assert field['surface'] == '' and page['blank'] == ['Surface Condition']
    assert page['photos'][1]['heading_deg'] == 290.0                                  # "View 290°" on the photo


def test_pieces_join_into_the_words_on_the_line():
    pieces = [
        {'words': 'OCT 202', 'left': 165, 'right': 226, 'top': 1100, 'bottom': 1121, 'size': 15},
        {'words': '3', 'left': 225, 'right': 233, 'top': 1100, 'bottom': 1121, 'size': 15},
        {'words': 'CW3', 'left': 260, 'right': 290, 'top': 1100, 'bottom': 1121, 'size': 15},
    ]
    assert [line['text'] for line in book.lines_of(pieces)] == ['OCT 2023 CW3']


@pytest.mark.parametrize('typed, day, night, warned', [
    ('2 day / 2 night', '2', '2', False),
    ('3D / 2N', '3', '2', False),
    ('1 day', '1', '', True),
    ('N/A', 'N/A', None, True),
    ('**HOIST ONLY**', '**HOIST ONLY**', None, True),
])
def test_max_aircraft_as_the_book_writes_it(typed, day, night, warned):
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (331, 69, f'Max A/C: {typed}')]), 9)
    assert page['field']['maxAcDay'] == day
    assert page['field'].get('maxAcNight') == night
    assert bool(page['warnings']) == warned


def test_what_a_page_lacks_is_named_never_made_up():
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (1100, 69, 'Survey Date: OCT 2023')]), 9)
    assert 'Best Approach' in page['unread'] and 'Lat/Long' in page['unread'] and 'HLZ Name' in page['unread']
    assert 'approachDeg' not in page['field']
    assert page['field']['surveyedOn'] == 'OCT 2023'


def test_an_elevation_with_a_thousands_comma_is_its_number():
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (400, 69, 'Elevation: 1,850 ft msl')]), 9)
    assert page['field']['elevationFt'] == '1850'
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (400, 69, 'Elevation: UNK')]), 9)
    assert page['field']['elevationFt'] == 'UNK'
    assert page['warnings'] == ['Elevation is "UNK", not a number: kept as written.']


def test_the_footer_s_values_end_at_the_next_label():
    footer = 'Survey Date: OCT 2023 Surveyed by: AASF1, CW3 J. SIMPSON, ASO Checked: MAJ R. LEE'
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (1100, 69, footer)]), 9)
    field = page['field']
    assert (field['surveyedOn'], field['surveyUnit'], field['surveyedBy']) == ('OCT 2023', 'AASF1', 'CW3 J. SIMPSON, ASO')
    assert page['warnings'] == ['The footer\'s "Checked" is not one the importer reads: "MAJ R. LEE" was left out.']


def test_a_page_without_the_survey_profile_s_title_says_so():
    page = book.read_page(_page([(206, 69, 'Grid:'), (223, 69, '16S GD 6341 3199'), (1100, 69, 'Survey Date: OCT 2023')]), 9)
    assert 'Survey profile' in page['unread']
    assert 'Survey profile' not in book.read_page(PAGES[5], 5)['unread']


def test_the_bundle_holds_a_manifest_and_the_photos(tmp_path):
    photo = tmp_path / 'p.jpg'
    photo.write_bytes(b'\xff\xd8photo')
    surveys = [book.read_page(PAGES[5], 5)]
    out = tmp_path / 'bundle.zip'
    book.write_bundle(surveys, {'photos/p005-north.jpg': photo}, out, 'book.pdf')
    with zipfile.ZipFile(out) as bundle:
        manifest = json.loads(bundle.read('manifest.json'))
        assert (manifest['format'], manifest['version'], manifest['source']) == ('ezpz-survey-import', 1, 'book.pdf')
        assert manifest['surveys'][0]['name'] == 'LZ DONOVAN'
        assert bundle.read('photos/p005-north.jpg') == b'\xff\xd8photo'


def test_without_poppler_it_says_how_to_get_it(tmp_path, capsys):
    pdf = tmp_path / 'book.pdf'
    pdf.write_bytes(b'%PDF-1.4')
    with patch('shutil.which', return_value=None):
        assert book.main([str(pdf), str(tmp_path / 'out.zip')]) == 1
    assert 'brew install poppler' in capsys.readouterr().err
    assert book.main([str(tmp_path / 'missing.pdf'), str(tmp_path / 'out.zip')]) == 2
