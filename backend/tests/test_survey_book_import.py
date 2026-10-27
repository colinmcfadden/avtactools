"""The old book's pages imported as drafts (``POST /api/survey-books/<uuid>/import``; docs/LZ_SURVEYS.md §6), and each measured in the
background (survey/importing.py): over a synthetic DEM, with the model's boundary stood in for (SAM's weights are not needed here)."""

import io
import json
import math
import sys
import uuid
import zipfile
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from PIL import Image  # noqa: E402

from models import LzSurvey, LzSurveyAsset, LzSurveyImport, Team, TeamMember, User, db  # noqa: E402
from survey import assets as asset_store  # noqa: E402
from survey import importing  # noqa: E402
from test_survey_measure import DONOVAN, TARGET, Terrain, square  # noqa: E402
from test_surveys import SurveyCase, jpeg_with_exif  # noqa: E402

FIXTURE = json.loads((BACKEND_DIR.parent / 'contracts' / 'fixtures' / 'surveys' / 'imported_diagram.json').read_text(encoding='utf-8'))
BIG_STAMP = {
    'grid': '16S GD 5973 3631', 'latLong': 'N 34 38.149 W084 09.996', 'maxAcDay': '2', 'maxAcNight': '2', 'approachDeg': '290',
    'exitDeg': '110', 'elevationFt': '2140', 'surface': '', 'surveyedOn': 'OCT 2023', 'surveyedBy': 'CW3 J. SIMPSON, ASO',
    'surveyUnit': 'AASF 1',
}


def around(lat, lon, half_m=50.0):
    """A square ``half_m`` each way round (lat, lon), as the model might find an LZ."""
    north, east = half_m / 111320.0, half_m / (111320.0 * math.cos(math.radians(lat)))
    return [[lat + north, lon - east], [lat + north, lon + east], [lat - north, lon + east], [lat - north, lon - east]]


def page(number, hlz_id, name, field, photos=(), unread=(), blank=(), warnings=()):
    return {'page': number, 'hlzId': hlz_id, 'name': name, 'field': field, 'unread': list(unread), 'blank': list(blank),
            'warnings': list(warnings), 'photos': [
                {'file': f'photos/p{number:03d}-{"north" if kind == "photo_north" else "approach"}.jpg', 'kind': kind,
                 'heading_deg': heading, 'taken_on': 'OCT 2023'} for kind, heading in photos]}


def bundle(pages, files=None, **manifest):
    """A bundle as tools/import_survey_book.py writes it, with a photo for each one a page names unless ``files`` says otherwise."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('manifest.json', json.dumps({'format': 'ezpz-survey-import', 'version': 1,
                                                      'source': 'LZ-SURVEYS-OCT-2023.pdf', 'surveys': pages, **manifest}))
        if files is None:
            files = {photo['file']: jpeg_with_exif(size=(600, 400)) for p in pages for photo in p.get('photos', [])}
        for name, data in files.items():
            archive.writestr(name, data)
    return out.getvalue()


def two_pages():
    return [
        page(5, 'GA 034', 'LZ DONOVAN', DONOVAN['field'], photos=(('photo_north', 0.0), ('photo_approach', 0.0))),
        page(1, 'NGA 030', 'LZ BIG STAMP', BIG_STAMP, photos=(('photo_north', 0.0), ('photo_approach', 290.0)), blank=['Surface Condition']),
    ]


class ImportCase(SurveyCase):
    def setUp(self):
        super().setUp()
        self.found = []
        self.boundary = around
        measurer = importing.Measurer(find_boundary=self.find)
        measurer.inline = True
        swap = patch.object(importing, 'MEASURER', measurer)
        swap.start()
        self.addCleanup(swap.stop)
        self.measurer = measurer

    def find(self, lat, lon):
        self.found.append((round(lat, 6), round(lon, 6)))
        return self.boundary(lat, lon)

    def send(self, data, head=None, book=None):
        return self.client.post(f'/api/survey-books/{(book or self.book)["uuid"]}/import', headers=head or self.admin,
                                data={'bundle': (io.BytesIO(data), 'book-bundle.zip')}, content_type='multipart/form-data')

    def imported(self, data=None):
        response = self.send(data or bundle(two_pages()))
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()

    def measure_all(self):
        with Terrain():
            self.measurer.run_pending(self.app)

    def survey(self, uuid_):
        response = self.client.get(f'/api/surveys/{uuid_}', headers=self.admin)
        self.assertEqual(response.status_code, 200)
        return response.get_json()


class ImportTests(ImportCase):
    def test_the_pages_become_drafts_with_their_photos_in_page_order(self):
        body = self.imported()
        self.assertEqual(body['source'], 'LZ-SURVEYS-OCT-2023.pdf')
        self.assertEqual(body['skipped'], [])
        big_stamp, donovan = body['created']
        self.assertEqual((big_stamp['hlz_id'], donovan['hlz_id']), ('NGA 030', 'GA 034'))   # page 1 first, as in the book
        self.assertEqual(body['book']['page_order'], [big_stamp['uuid'], donovan['uuid']])
        self.assertEqual(donovan['imported'], {'source': 'LZ-SURVEYS-OCT-2023.pdf', 'page': 5, 'state': 'queued', 'reason': None,
                                               'to_check': 1, 'checked': False})
        survey = self.survey(donovan['uuid'])
        self.assertEqual((survey['status'], survey['revision'], survey['field']), ('draft', 1, DONOVAN['field']))
        self.assertEqual(survey['imported']['notes']['warnings'],
                         ["The book's grid and lat/long are 527 m apart: the target is at the grid. Check which is right."])
        self.assertEqual(survey['imported']['imported_by'], 'CW3 Admin')
        target = survey['diagram']['target']
        self.assertEqual((round(target['lat'], 7), round(target['lon'], 7), target['mgrs']), (34.5960169, -84.1277903, '16S GD 63415 31995'))
        self.assertEqual(survey['diagram']['status'], 'targeted')
        north, approach = survey['assets']
        self.assertEqual((north['kind'], north['heading_deg'], north['taken_on'], north['caption']),
                         ('photo_north', 0.0, 'OCT 2023', 'From page 5 of the old book'))
        self.assertEqual(approach['kind'], 'photo_approach')
        with self.app.app_context():
            stored = LzSurveyAsset.query.filter_by(id=north['id']).one()
            picture = Image.open(io.BytesIO(asset_store.store().get(stored.sha256)))
        self.assertEqual(len(picture.getexif()), 0)                           # the phone's GPS and serial are gone
        big = self.survey(big_stamp['uuid'])
        self.assertEqual(big['imported']['notes'], {'unread': [], 'blank': ['Surface Condition'], 'warnings': []})

    def test_each_draft_is_measured_in_the_background_one_at_a_time(self):
        body = self.imported()
        self.assertEqual(self.found, [])                                     # nothing measured while the request was answered
        self.measure_all()
        self.assertEqual(len(self.found), 2)
        listing = self.listing().get_json()['surveys']
        self.assertEqual([row['imported']['state'] for row in listing], ['done', 'done'])
        donovan = self.survey(body['created'][1]['uuid'])
        self.assertEqual(donovan['revision'], 3)                              # its boundary, then its snapshot
        self.assertEqual(donovan['diagram']['status'], 'analyzed')
        self.assertEqual(len(donovan['diagram']['analysis']['detectedLZ']), 4)
        snapshot = donovan['snapshot']
        self.assertEqual((snapshot['measuredBy'], snapshot['grid']), ('CW3 Admin', '16S GD 63415 31995'))
        self.assertIsNotNone(snapshot['elevation'])
        self.assertIsNotNone(snapshot['slope'])
        self.assertEqual(donovan['updated_by'], 'CW3 Admin')
        self.assertEqual(donovan['status'], 'draft')

    def test_a_page_whose_hlz_id_is_in_the_book_is_left_as_it_is(self):
        mine = self.new_survey()
        body = self.imported()
        self.assertEqual([s['hlz_id'] for s in body['created']], ['NGA 030'])
        self.assertEqual(body['skipped'], [{'page': 5, 'hlz_id': 'GA 034', 'name': 'LZ DONOVAN',
                                            'reason': 'GA 034 is in the book already: left as it is.'}])
        self.assertEqual(self.survey(mine['uuid'])['revision'], 1)
        again = self.imported()                                              # importing twice adds nothing
        self.assertEqual((again['created'], len(again['skipped'])), ([], 2))

    def test_no_boundary_found_is_said_and_nothing_is_measured(self):
        self.boundary = lambda lat, lon: None
        body = self.imported()
        self.measure_all()
        donovan = self.survey(body['created'][1]['uuid'])
        self.assertEqual(donovan['imported']['state'], 'no_boundary')
        self.assertIn('draw it on the map', donovan['imported']['reason'])
        self.assertIsNone(donovan['snapshot'])
        self.assertEqual(donovan['revision'], 1)

    def test_imagery_that_cannot_be_had_fails_in_words(self):
        def unavailable(lat, lon):
            raise RuntimeError('The imagery the model looks at could not be fetched. Find the boundary in the editor later.')
        self.boundary = unavailable
        body = self.imported()
        self.measure_all()
        state = self.survey(body['created'][0]['uuid'])['imported']
        self.assertEqual((state['state'], state['reason']),
                         ('failed', 'The imagery the model looks at could not be fetched. Find the boundary in the editor later.'))

    def test_a_fault_while_measuring_one_survey_does_not_stop_the_rest(self):
        calls = []

        def broken(lat, lon):
            calls.append(lat)
            if len(calls) == 1:
                raise ZeroDivisionError('model fault')
            return square(50)
        self.boundary = broken
        body = self.imported()
        self.measure_all()
        first, second = (self.survey(s['uuid'])['imported'] for s in body['created'])
        self.assertEqual((first['state'], first['reason']), ('failed', 'It could not be measured here: measure it in the editor.'))
        self.assertEqual(second['state'], 'done')

    def test_an_editor_who_saves_meanwhile_keeps_their_work(self):
        body = self.imported(bundle([two_pages()[0]]))
        survey = self.survey(body['created'][0]['uuid'])

        def editor_draws_first(lat, lon):
            drawn = {**survey['diagram'], 'analysis': {**survey['diagram']['analysis'], 'customLZ': square(30)}}
            saved = self.patch_survey(survey, {'diagram': drawn})
            self.assertEqual(saved.status_code, 200)
            return square(50)
        self.boundary = editor_draws_first
        self.measure_all()
        after = self.survey(survey['uuid'])
        self.assertEqual(after['imported']['state'], 'changed')
        self.assertEqual(after['diagram']['analysis']['customLZ'], square(30))     # the editor's boundary, not the model's
        self.assertIsNone(after['diagram']['analysis']['detectedLZ'])
        self.assertIsNone(after['snapshot'])

    def test_a_survey_measured_in_the_editor_first_is_left_alone(self):
        body = self.imported(bundle([two_pages()[0]]))
        survey = self.survey(body['created'][0]['uuid'])
        with Terrain():
            drawn = self.patch_survey(survey, {'diagram': {**survey['diagram'], 'analysis': {'detectedLZ': square(40)}}}).get_json()
            measured = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(drawn['revision']),
                                        json={'apply': True})
            self.assertEqual(measured.status_code, 200)
            self.assertEqual(self.listing().get_json()['surveys'][0]['imported']['state'], 'done')   # measured is done
            self.measurer.run_pending(self.app)
        self.assertEqual(self.found, [])
        self.assertEqual(self.survey(survey['uuid'])['revision'], drawn['revision'] + 1)

    def test_a_page_with_no_place_waits_for_its_target(self):
        field = {**BIG_STAMP, 'grid': 'SEE MAP', 'latLong': ''}
        body = self.imported(bundle([page(1, 'NGA 030', 'LZ BIG STAMP', field)]))
        (made,) = body['created']
        self.assertEqual((made['imported']['state'], made['imported']['reason']),
                         ('no_target', "Set the LZ's target on the map, then measure it."))
        survey = self.survey(made['uuid'])
        self.assertIsNone(survey['diagram'])
        self.assertEqual(survey['imported']['notes']['warnings'],
                         ["Neither the book's grid nor its lat/long could be read: set the target on the map."])
        self.measure_all()
        self.assertEqual(self.found, [])

    def test_a_page_with_only_a_lat_long_is_placed_there(self):
        field = {**BIG_STAMP, 'grid': ''}
        body = self.imported(bundle([page(1, 'NGA 030', 'LZ BIG STAMP', field)]))
        survey = self.survey(body['created'][0]['uuid'])
        self.assertEqual(survey['imported']['notes']['warnings'], ['The book has no grid: the target is at its lat/long.'])
        self.assertAlmostEqual(survey['diagram']['target']['lat'], 34 + 38.149 / 60, places=7)

    def test_a_restart_measures_what_was_waiting(self):
        body = self.imported()
        fresh = importing.Measurer(find_boundary=self.find)                  # the thread that held the queue is gone
        fresh.inline = True
        with patch.object(importing, 'MEASURER', fresh):
            self.listing()                                                    # the book's next look queues them again
            with Terrain():
                fresh.run_pending(self.app)
        self.assertEqual([self.survey(s['uuid'])['imported']['state'] for s in body['created']], ['done', 'done'])

    def test_marking_the_notes_checked_changes_nothing_else(self):
        body = self.imported()
        donovan = body['created'][1]
        response = self.client.post(f'/api/surveys/{donovan["uuid"]}/import/checked', headers=self.admin)
        self.assertEqual(response.status_code, 200)
        checked = response.get_json()
        self.assertEqual((checked['imported']['to_check'], checked['imported']['checked'], checked['imported']['checked_by']),
                         (0, True, 'CW3 Admin'))
        self.assertEqual(len(checked['imported']['notes']['warnings']), 1)            # kept, only no longer counted
        self.assertEqual((checked['revision'], checked['status']), (1, 'draft'))
        mine = self.new_survey(hlz_id='GA 999')
        refused = self.client.post(f'/api/surveys/{mine["uuid"]}/import/checked', headers=self.admin)
        self.assertEqual((refused.status_code, refused.get_json()['code']), (404, 'not_imported'))
        self.assertIsNone(mine['imported'])


class BundleTests(ImportCase):
    def refused(self, data, status=400, code='invalid_bundle'):
        response = self.send(data)
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(response.get_json()['code'], code)
        with self.app.app_context():
            self.assertEqual(LzSurvey.query.count(), 0)
        return response.get_json()['error']

    def test_what_is_not_a_bundle_is_refused_in_words(self):
        self.assertIn('not a zip', self.refused(b'%PDF-1.4 not a zip'))
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive:
            archive.writestr('readme.txt', 'hello')
        self.assertIn('no manifest.json', self.refused(out.getvalue()))
        self.assertIn('not a bundle', self.refused(bundle(two_pages(), format='something-else')))
        self.assertIn('another version', self.refused(bundle(two_pages(), version=2)))
        self.assertIn('no pages', self.refused(bundle([])))

    def test_a_page_is_checked_as_the_editor_checks_a_survey(self):
        bad = page(7, 'GA 036', 'LZ FINNEY', {**BIG_STAMP, 'altimeter': 'KJZP'})
        self.assertIn("Page 7: field has no value called 'altimeter'", self.refused(bundle([bad])))
        self.assertIn('Page 7: maxAcDay must be text', self.refused(bundle([page(7, 'GA 036', 'LZ FINNEY', {'maxAcDay': 2})])))
        self.assertIn('no page number', self.refused(bundle([{**two_pages()[0], 'page': 'five'}])))
        self.assertIn('HLZ ID is longer', self.refused(bundle([{**two_pages()[0], 'hlzId': 'X' * 41}])))
        heading = two_pages()[0]
        heading['photos'][0]['heading_deg'] = 400
        self.assertIn("heading is not 0 to 360", self.refused(bundle([heading])))

    def test_a_photo_must_be_one_the_bundle_holds(self):
        pages = two_pages()
        self.assertIn('names no file', self.refused(bundle(pages, files={})))
        pages[0]['photos'][0]['file'] = '../../etc/passwd'
        self.assertIn('names no file', self.refused(bundle(pages, files={'../../etc/passwd': b'x'})))

    def test_a_photo_too_large_or_unreadable_is_left_out_and_said(self):
        pages = [two_pages()[0]]
        files = {'photos/p005-north.jpg': jpeg_with_exif(size=(600, 400)), 'photos/p005-approach.jpg': b'not a picture'}
        with patch.object(importing, 'MAX_UPLOAD_BYTES', 40_000):
            files['photos/p005-north.jpg'] = b'\xff' * 50_000
            body = self.imported(bundle(pages, files=files))
        survey = self.survey(body['created'][0]['uuid'])
        self.assertEqual(survey['assets'], [])
        warnings = survey['imported']['notes']['warnings']
        self.assertIn('The north photo is larger than 20 MB and was left out.', warnings)
        self.assertTrue(any(w.startswith('A photo could not be read') for w in warnings), warnings)

    def test_a_bundle_that_inflates_past_an_import_is_refused(self):
        with patch.object(importing, 'MAX_INFLATED_BYTES', 10_000):
            self.refused(bundle(two_pages()), 413, 'bundle_too_large')

    def test_a_request_larger_than_an_import_is_refused_before_it_is_read(self):
        with patch.object(importing, 'MAX_BUNDLE_BYTES', 1000):
            self.refused(bundle(two_pages()), 413, 'bundle_too_large')

    def test_a_request_without_the_bundle_is_refused(self):
        response = self.client.post(f'/api/survey-books/{self.book["uuid"]}/import', headers=self.admin, data={},
                                    content_type='multipart/form-data')
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'missing_file'))


class WhoImportsTests(ImportCase):
    def test_a_reader_cannot_import_and_an_archived_book_takes_nothing(self):
        with self.app.app_context():
            pilot = User.query.filter_by(email='pilot@example.com').one()
            pilot.features = {'lz_surveys': True}
            team = Team(name='B CO 1-171 AVN', created_by=pilot.id)
            db.session.add(team)
            db.session.flush()
            db.session.add(TeamMember(team_id=team.id, user_id=pilot.id, role='member'))
            db.session.commit()
            team_id = team.id
        team_book = self.client.post('/api/survey-books', headers=self.admin, json={'name': 'B CO BOOK', 'team_id': team_id}).get_json()
        refused = self.send(bundle(two_pages()), head=self.pilot, book=team_book)
        self.assertEqual((refused.status_code, refused.get_json()['code']), (403, 'not_editor'))
        self.client.post(f'/api/survey-books/{self.book["uuid"]}/archive', headers=self.admin)
        archived = self.send(bundle(two_pages()))
        self.assertEqual((archived.status_code, archived.get_json()['code']), (409, 'archived'))
        with self.app.app_context():
            self.assertEqual((LzSurvey.query.count(), LzSurveyImport.query.count()), (0, 0))


class PlaceTests(ImportCase):
    def test_the_book_s_grids_and_lat_longs_are_read(self):
        self.assertEqual(importing.latlon_of_text('N 34 35.579 W084 07.402'), (34 + 35.579 / 60, -(84 + 7.402 / 60)))
        self.assertEqual(importing.latlon_of_text("S 12° 30.5' E 045° 07.25'"), (-(12 + 30.5 / 60), 45 + 7.25 / 60))
        self.assertEqual(importing.latlon_of_text('34.5930, -84.1234'), (34.593, -84.1234))
        for text in ('N 34 61.000 W084 07.402', 'N 95 10.0 W084 07.402', '', None, 'see grid'):
            self.assertIsNone(importing.latlon_of_text(text), text)
        lat, lon = importing.latlon_of_grid('16S GD 6341 3199')
        self.assertEqual((round(lat, 8), round(lon, 8)), (round(TARGET[0], 8), round(TARGET[1], 8)))    # the square's centre
        for text in ('16S GD 634 3199', 'XYZ', '16SGD', '', None, '99Z ZZ 1234 1234'):
            self.assertIsNone(importing.latlon_of_grid(text), text)

    def test_a_grid_and_lat_long_that_agree_say_nothing(self):
        place, note = importing.place_of(BIG_STAMP)
        self.assertIsNone(note)
        self.assertLess(importing.distance_m(place, importing.latlon_of_text(BIG_STAMP['latLong'])), importing.GRID_AGREES_M)
        _place, note = importing.place_of({'grid': '16S GD 5941 4456', 'latLong': 'N 34 42.662 W084 10.078'})   # LZ RICO
        self.assertEqual(note, "The book's grid and lat/long are 97 m apart: the target is at the grid. Check which is right.")
        _place, note = importing.place_of({'grid': 'GD 5941 4456', 'latLong': 'N 34 42.662 W084 10.078'})
        self.assertEqual(note, "The book's grid could not be read: the target is at its lat/long.")

    def test_the_diagram_is_the_web_s_own_document(self):
        lat, lon = importing.latlon_of_grid(FIXTURE['grid'])
        with patch.object(importing.uuid_lib, 'uuid4', return_value=uuid.UUID('00000000-0000-4000-8000-000000000005')), \
                patch.object(importing, '_now_iso', return_value='2026-10-26T12:00:00Z'):
            targeted = importing.diagram_at(lat, lon, 'LZ DONOVAN')
            self.assertEqual(targeted, FIXTURE['targeted'])
            self.assertEqual(importing.with_boundary(targeted, FIXTURE['analyzed']['analysis']['detectedLZ']), FIXTURE['analyzed'])
