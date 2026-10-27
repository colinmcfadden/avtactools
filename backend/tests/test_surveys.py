"""LZ surveys over HTTP (``/api/surveys``, ``/api/survey-books``; docs/LZ_SURVEYS.md §7, §8): the survey books' key on every route,
If-Match on every save, archived never deleted, measured over a synthetic DEM, photos stripped of their metadata. Who may read and edit
which book is test_survey_books.py's."""

import io
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from PIL import Image  # noqa: E402

from auth_harness import NativeAuthCase  # noqa: E402
from models import LzSurvey, LzSurveyAsset, SurveyBook, User, db  # noqa: E402
from routes.survey_routes import survey_bp  # noqa: E402
from survey import assets as asset_store  # noqa: E402
from test_survey_measure import DONOVAN, TARGET, Terrain, square  # noqa: E402

DIAGRAM = {
    'schemaVersion': 2, 'id': 'lz-donovan', 'name': 'LZ DONOVAN',
    'target': {'lat': TARGET[0], 'lon': TARGET[1], 'mgrs': '16S GD 6341 3199'},
    'analysis': {'detectedLZ': square(50), 'customLZ': None, 'terrainData': {'overlay': 'data:image/png;base64,AAAA'}},
    'graphics': {'doghouses': []}, 'view': {'mapStyle': 'satellite'},
}


def jpeg_with_exif(size=(3000, 2000), orientation=6):
    """A JPEG a phone might send: EXIF with a GPS position, a camera serial and an orientation that turns it on its side."""
    image = Image.new('RGB', size, (90, 140, 60))
    image.paste((200, 40, 40), (0, 0, 300, 100))              # a red block at the top left, to see the turn
    exif = Image.Exif()
    exif[0x0112] = orientation                                 # Orientation
    exif[0xA431] = 'SERIAL-12345'                              # BodySerialNumber
    exif[0x010F] = 'PhoneMaker'                                # Make
    gps = exif.get_ifd(0x8825)
    gps[1], gps[2], gps[3], gps[4] = 'N', (34.0, 35.0, 34.7), 'W', (84.0, 7.0, 24.1)
    out = io.BytesIO()
    image.save(out, 'JPEG', exif=exif.tobytes(), quality=90)
    return out.getvalue()


class SurveyCase(NativeAuthCase):
    extra_blueprints = (survey_bp,)

    def setUp(self):
        super().setUp()
        self.make_account('admin@example.com')
        self.make_account('pilot@example.com')
        with self.app.app_context():
            admin = User.query.filter_by(email='admin@example.com').one()
            admin.role, admin.name = 'admin', 'CW3 Admin'
            db.session.commit()
        self.admin = self.auth(self.login(email='admin@example.com'))
        self.pilot = self.auth(self.login(email='pilot@example.com'))
        store = tempfile.TemporaryDirectory()
        self.addCleanup(store.cleanup)
        self.store_dir = store.name
        env = patch.dict(os.environ, {'SURVEY_ASSETS_DIR': store.name}, clear=False)
        env.start()
        self.addCleanup(env.stop)
        made = self.client.post('/api/survey-books', headers=self.admin, json={'name': 'AASF 1 HLZ SURVEY BOOK'})
        self.assertEqual(made.status_code, 201, made.get_data(as_text=True))
        self.book = made.get_json()

    def auth(self, session, revision=None):
        head = {'Authorization': f'Bearer {session["access_token"]}'}
        return head

    def at(self, revision, head=None):
        return {**(head or self.admin), 'If-Match': f'"{revision}"'}

    def new_survey(self, **body):
        response = self.client.post('/api/surveys', headers=self.admin,
                                    json={'book': self.book['uuid'], 'name': DONOVAN['name'], 'hlz_id': DONOVAN['hlzId'],
                                          'field': DONOVAN['field'], 'diagram': DIAGRAM, **body})
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()

    def patch_survey(self, survey, body, revision=None, head=None):
        return self.client.patch(f'/api/surveys/{survey["uuid"]}', headers=self.at(revision or survey['revision'], head), json=body)

    def listing(self, archived=False):
        response = self.client.get(f'/api/survey-books/{self.book["uuid"]}' + ('?archived=1' if archived else ''), headers=self.admin)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response


class PermissionTests(SurveyCase):
    def routes(self, survey):
        base = f'/api/surveys/{survey["uuid"]}'
        book = f'/api/survey-books/{self.book["uuid"]}'
        return (('get', '/api/survey-books', None), ('post', '/api/survey-books', {'name': 'X'}),
                ('get', book, None), ('patch', book, {'name': 'X'}), ('post', f'{book}/archive', None),
                ('post', f'{book}/restore', None), ('post', f'{book}/import', None),
                ('get', f'{book}/print', None), ('post', f'{book}/print', None), ('get', f'{book}/pdf', None),
                ('post', '/api/surveys', {'book': self.book['uuid'], 'name': 'LZ X'}),
                ('get', base, None), ('patch', base, {'name': 'X'}), ('post', f'{base}/archive', None),
                ('post', f'{base}/restore', None), ('post', f'{base}/measure', {}),
                ('post', f'{base}/submit', None), ('post', f'{base}/draft', None), ('post', f'{base}/publish', {}),
                ('post', f'{base}/import/checked', None),
                ('get', f'{base}/pdf', None), ('get', f'{base}/page', None),
                ('post', f'{base}/assets', None), ('get', f'{base}/assets/1', None),
                ('patch', f'{base}/assets/1', {'caption': 'x'}), ('delete', f'{base}/assets/1', None))

    def test_every_route_refuses_an_account_without_the_key(self):
        survey = self.new_survey()
        for method, path, body in self.routes(survey):
            with self.subTest(route=f'{method} {path}'):
                response = getattr(self.client, method)(path, headers={**self.pilot, 'If-Match': '"1"'}, json=body)
                self.assertEqual(response.status_code, 403)
                self.assertEqual(response.get_json()['code'], 'feature_disabled')
                self.assertIn('private', response.headers['Cache-Control'])
                self.assertEqual(self.client.open(path, method=method.upper(), json=body).status_code, 401)
        with self.app.app_context():
            (row,) = LzSurvey.query.all()
            self.assertEqual((row.name, row.revision, row.archived_at), ('LZ DONOVAN', 1, None))

    def test_the_super_admin_is_an_admin(self):
        with patch.dict(os.environ, {'SUPER_ADMIN_EMAIL': 'pilot@example.com'}):
            self.assertEqual(self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.pilot).status_code, 200)


class SurveyTests(SurveyCase):
    def test_a_new_survey_is_a_draft_in_the_book(self):
        survey = self.new_survey()
        self.assertEqual((survey['status'], survey['revision'], survey['hlz_id'], survey['name']), ('draft', 1, 'GA 034', 'LZ DONOVAN'))
        self.assertEqual(survey['field'], DONOVAN['field'])
        self.assertIsNone(survey['snapshot'])
        self.assertIsNone(survey['diagram']['analysis']['terrainData'])      # a runtime raster is never kept
        self.assertEqual((survey['created_by'], survey['updated_by']), ('CW3 Admin', 'CW3 Admin'))
        listing = self.listing()
        self.assertEqual(listing.headers['Cache-Control'], 'private, no-store')
        body = listing.get_json()
        self.assertEqual(body['book']['page_order'], [survey['uuid']])
        self.assertEqual(body['book']['name'], 'AASF 1 HLZ SURVEY BOOK')
        self.assertEqual(survey['book'], {'uuid': self.book['uuid'], 'name': 'AASF 1 HLZ SURVEY BOOK'})
        (row,) = body['surveys']
        self.assertEqual((row['uuid'], row['surveyed_on'], row['grid'], row['disagreements']), (survey['uuid'], 'OCT 2023', '16S GD 6341 3199', 0))

    def test_what_cannot_be_kept_is_refused(self):
        for body in ({'name': ''}, {'name': 'x' * 101}, {'name': 'LZ', 'field': {'colour': 'red'}}, {'name': 'LZ', 'diagram': []},
                     {'name': 'LZ', 'hlz_id': 'GA\n034'}):
            with self.subTest(body=body):
                response = self.client.post('/api/surveys', headers=self.admin, json={'book': self.book['uuid'], **body})
                self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_survey'))
        no_book = self.client.post('/api/surveys', headers=self.admin, json={'name': 'LZ X'})
        self.assertEqual((no_book.status_code, no_book.get_json()['code']), (400, 'book_required'))
        nowhere = self.client.post('/api/surveys', headers=self.admin, json={'book': 'not-a-book', 'name': 'LZ X'})
        self.assertEqual(nowhere.status_code, 404)

    def test_a_diagram_that_is_not_json_is_refused(self):
        # Python reads NaN as JSON; Postgres does not, and would answer a 500.
        survey = self.new_survey()
        bad = {**DIAGRAM, 'target': {'lat': float('nan'), 'lon': TARGET[1]}}
        response = self.patch_survey(survey, {'diagram': bad})
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_survey'))

    def test_a_save_names_the_revision_it_was_made_on(self):
        survey = self.new_survey()
        missing = self.client.patch(f'/api/surveys/{survey["uuid"]}', headers=self.admin, json={'name': 'LZ D'})
        self.assertEqual((missing.status_code, missing.get_json()['code']), (428, 'if_match_required'))
        bad = self.client.patch(f'/api/surveys/{survey["uuid"]}', headers={**self.admin, 'If-Match': 'yesterday'}, json={'name': 'LZ D'})
        self.assertEqual(bad.status_code, 400)
        saved = self.patch_survey(survey, {'field': {**DONOVAN['field'], 'surface': 'Grass, short'}})
        self.assertEqual(saved.status_code, 200)
        self.assertEqual((saved.get_json()['revision'], saved.get_json()['field']['surface']), (2, 'Grass, short'))
        # A second admin, still on revision 1: refused, with the server's copy, and nothing overwritten.
        stale = self.patch_survey(survey, {'field': {**DONOVAN['field'], 'surface': 'Gravel'}}, revision=1)
        self.assertEqual((stale.status_code, stale.get_json()['code']), (409, 'revision_conflict'))
        self.assertEqual(stale.get_json()['server']['field']['surface'], 'Grass, short')
        with self.app.app_context():
            self.assertEqual(LzSurvey.query.one().field['surface'], 'Grass, short')

    def test_a_save_that_changes_nothing_moves_nothing(self):
        survey = self.new_survey()
        response = self.patch_survey(survey, {'name': survey['name'], 'field': survey['field']})
        self.assertEqual(response.get_json()['revision'], 1)

    def test_any_change_makes_a_published_survey_a_draft_again(self):
        survey = self.new_survey()
        with self.app.app_context():
            LzSurvey.query.one().status = 'published'
            db.session.commit()
        self.assertEqual(self.patch_survey(survey, {'name': 'LZ DONOVAN NORTH'}).get_json()['status'], 'draft')

    def test_archived_never_deleted(self):
        survey = self.new_survey()
        archived = self.client.post(f'/api/surveys/{survey["uuid"]}/archive', headers=self.admin).get_json()
        self.assertTrue(archived['archived'])
        self.assertEqual(self.listing().get_json()['surveys'], [])
        self.assertEqual(len(self.listing(archived=True).get_json()['surveys']), 1)
        refused = self.patch_survey(survey, {'name': 'X'}, revision=archived['revision'])
        self.assertEqual((refused.status_code, refused.get_json()['code']), (409, 'archived'))
        restored = self.client.post(f'/api/surveys/{survey["uuid"]}/restore', headers=self.admin).get_json()
        self.assertFalse(restored['archived'])
        self.assertEqual(self.client.delete(f'/api/surveys/{survey["uuid"]}', headers=self.admin).status_code, 405)

    def test_the_book_s_name_and_order(self):
        first, second = self.new_survey(), self.new_survey(name='LZ EAGLE', hlz_id='GA 035')
        path = f'/api/survey-books/{self.book["uuid"]}'
        book = self.listing().get_json()['book']
        self.assertEqual(book['page_order'], [first['uuid'], second['uuid']])
        renamed = self.client.patch(path, headers=self.at(book['revision']),
                                    json={'name': 'AASF 1 HLZ BOOK', 'unit_line': 'GA ARNG', 'page_order': [second['uuid'], first['uuid']]})
        self.assertEqual(renamed.status_code, 200, renamed.get_data(as_text=True))
        listing = self.listing().get_json()
        self.assertEqual([s['uuid'] for s in listing['surveys']], [second['uuid'], first['uuid']])
        self.assertEqual(listing['book']['name'], 'AASF 1 HLZ BOOK')
        stale = self.client.patch(path, headers=self.at(book['revision']), json={'name': 'Z'})
        self.assertEqual(stale.status_code, 409)
        unknown = self.client.patch(path, headers=self.at(renamed.get_json()['revision']), json={'page_order': ['nope']})
        self.assertEqual(unknown.status_code, 400)
        with self.app.app_context():
            self.assertEqual(SurveyBook.query.count(), 1)


class MeasureRouteTests(SurveyCase):
    def test_measuring_shows_the_difference_before_it_replaces_the_snapshot(self):
        survey = self.new_survey()
        with Terrain(east_deg=5.0):
            preview = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.admin, json={})
            self.assertEqual(preview.status_code, 200, preview.get_data(as_text=True))
            body = preview.get_json()
            self.assertFalse(body['applied'])
            self.assertIsNone(body['survey'])
            self.assertTrue(body['changes'])
            self.assertEqual(body['snapshot']['measuredBy'], 'CW3 Admin')
            with self.app.app_context():
                self.assertIsNone(LzSurvey.query.one().snapshot)        # nothing kept yet

            missing = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.admin, json={'apply': True})
            self.assertEqual(missing.status_code, 428)
            applied = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(1), json={'apply': True}).get_json()
        self.assertTrue(applied['applied'])
        kept = applied['survey']
        self.assertEqual(kept['revision'], 2)
        self.assertEqual(kept['snapshot']['elevation']['dem'], ['donovan.tif'])
        self.assertEqual(kept['snapshot']['slope']['headingDeg'], 0.0)
        rows = {row['key']: row for row in kept['comparisons']}
        self.assertTrue(rows['latLong']['disagrees'])
        self.assertTrue(rows['elevation']['disagrees'])         # the plane's 600 m is nothing like 1850 ft
        listed = self.listing().get_json()['surveys'][0]
        self.assertEqual(listed['disagreements'], sum(1 for row in kept['comparisons'] if row['open']))
        self.assertEqual(listed['grid'], kept['snapshot']['grid'])

    def test_overrides_are_stamped_and_settle_a_disagreement(self):
        survey = self.new_survey()
        with Terrain(east_deg=5.0):
            measured = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(1), json={'apply': True}).get_json()['survey']
        before = measured['disagreements']
        response = self.patch_survey(measured, {'overrides': {'elevation': {'choice': 'field', 'reason': 'Surveyed by GPS'}}})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        survey = response.get_json()
        entry = survey['overrides']['elevation']
        self.assertEqual((entry['by'], entry['field'], entry['choice']), ('CW3 Admin', '1850', 'field'))
        self.assertEqual(survey['disagreements'], before - 1)
        rows = {row['key']: row for row in survey['comparisons']}
        self.assertEqual(rows['elevation']['printed'], 'field')
        no_reason = self.patch_survey(survey, {'overrides': {'latLong': {'choice': 'field'}}})
        self.assertEqual(no_reason.status_code, 400)

    def test_an_angle_is_typed_only_over_the_lidar_s(self):
        survey = self.new_survey()
        with Terrain(east_deg=5.0):
            measured = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(1), json={'apply': True}).get_json()['survey']
        typed = {'overrides': {'approach': {'choice': 'typed', 'value': 12, 'reason': 'Tree line cut in 2024'}}}
        response = self.patch_survey(measured, typed)                     # this server reads no LiDAR: no angle to type over
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_survey'))
        self.assertIn('no angle here', response.get_json()['error'])
        # Where the LiDAR found an angle, the editor's is kept with who typed it and printed in its place.
        lane = {'start': {'groundFt': 1850.0},
                'clearance': {'angleDeg': 8.5, 'obstacle': {'heightFt': 98.4, 'kind': 'vegetation', 'fromTargetM': 200.0}}}
        with self.app.app_context():
            row = LzSurvey.query.one()
            row.snapshot = {**row.snapshot, 'obstacles': {'status': 'measured', 'approach': lane}}
            row.revision += 1
            db.session.commit()
        response = self.patch_survey(measured, typed, revision=measured['revision'] + 1)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        survey = response.get_json()
        entry = survey['overrides']['approach']
        self.assertEqual((entry['choice'], entry['value'], entry['by'], entry['computed']),
                         ('typed', 12.0, 'CW3 Admin', '8.5° (LiDAR: 98 ft tree 200 m out)'))
        row = next(r for r in survey['comparisons'] if r['key'] == 'approach')
        self.assertEqual((row['printed'], row['open']), ('typed', False))

    def test_a_survey_with_no_target_is_not_measured(self):
        survey = self.new_survey(diagram={'schemaVersion': 2, 'analysis': {}})
        response = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.admin, json={})
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'no_target'))

    def test_a_stale_measure_is_refused(self):
        survey = self.new_survey()
        self.patch_survey(survey, {'name': 'LZ D'})
        with Terrain():
            response = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(1), json={'apply': True})
        self.assertEqual(response.status_code, 409)


class AssetTests(SurveyCase):
    def upload(self, data, survey=None, filename='photo.jpg', **form):
        survey = survey or self.survey
        return self.client.post(f'/api/surveys/{survey["uuid"]}/assets', headers=self.admin,
                                data={'file': (io.BytesIO(data), filename), **form}, content_type='multipart/form-data')

    def setUp(self):
        super().setUp()
        self.survey = self.new_survey()

    def test_a_photo_is_turned_shrunk_and_stripped(self):
        response = self.upload(jpeg_with_exif(), kind='photo_north', caption='Looking north', heading_deg='0', taken_on='OCT 2023')
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        asset = response.get_json()
        self.assertEqual((asset['kind'], asset['caption'], asset['heading_deg'], asset['taken_on']), ('photo_north', 'Looking north', 0.0, 'OCT 2023'))
        # Orientation 6 turns it a quarter: 3000 x 2000 stands up as 2000 x 3000, then shrinks to 2400 on its long side.
        self.assertEqual((asset['width'], asset['height']), (1600, 2400))
        self.assertEqual(asset['content_type'], 'image/jpeg')
        self.assertEqual(asset['url'], f'/surveys/{self.survey["uuid"]}/assets/{asset["id"]}')

        served = self.client.get(f'/api{asset["url"]}', headers=self.admin)
        self.assertEqual(served.status_code, 200)
        self.assertEqual(served.headers['Cache-Control'], 'private, max-age=86400, immutable')
        self.assertEqual(served.mimetype, 'image/jpeg')
        data = served.get_data()
        self.assertNotIn(b'SERIAL-12345', data)
        self.assertNotIn(b'PhoneMaker', data)
        self.assertNotIn(b'Exif', data)
        image = Image.open(io.BytesIO(data))
        self.assertEqual(len(image.getexif()), 0)
        self.assertNotIn('exif', image.info)
        self.assertNotIn('icc_profile', image.info)
        self.assertEqual(image.size, (1600, 2400))
        # The red block was at the top left; turned clockwise for viewing it is at the top right.
        self.assertGreater(image.getpixel((1590, 20))[0], 150)
        self.assertEqual(asset['sha256'], __import__('hashlib').sha256(data).hexdigest())

        full = self.client.get(f'/api/surveys/{self.survey["uuid"]}', headers=self.admin).get_json()
        self.assertEqual(full['asset_ids'], [asset['id']])

    def test_the_same_picture_is_stored_once(self):
        first = self.upload(jpeg_with_exif((800, 600), 1)).get_json()
        second = self.upload(jpeg_with_exif((800, 600), 1)).get_json()
        self.assertEqual(first['sha256'], second['sha256'])
        files = [p for p in Path(self.store_dir).rglob('*') if p.is_file()]
        self.assertEqual([p.name for p in files], [first['sha256']])

    def test_a_png_stays_a_png_without_its_text(self):
        image = Image.new('RGBA', (500, 300), (10, 20, 30, 128))
        from PIL import PngImagePlugin
        info = PngImagePlugin.PngInfo()
        info.add_text('Comment', 'secret place')
        out = io.BytesIO()
        image.save(out, 'PNG', pnginfo=info)
        asset = self.upload(out.getvalue(), filename='p.png').get_json()
        self.assertEqual((asset['content_type'], asset['kind']), ('image/png', 'photo_other'))
        data = self.client.get(f'/api{asset["url"]}', headers=self.admin).get_data()
        self.assertNotIn(b'secret place', data)
        self.assertEqual(Image.open(io.BytesIO(data)).mode, 'RGBA')

    def test_what_is_not_a_photo_is_refused(self):
        gif = io.BytesIO()
        Image.new('RGB', (10, 10)).save(gif, 'GIF')
        for data, code in ((b'not a picture', 'not_an_image'), (gif.getvalue(), 'unsupported_image')):
            with self.subTest(code=code):
                response = self.upload(data)
                self.assertEqual((response.status_code, response.get_json()['code']), (400, code))
        self.assertEqual(self.upload(jpeg_with_exif((10, 10), 1), kind='selfie').status_code, 400)
        self.assertEqual(self.upload(jpeg_with_exif((10, 10), 1), heading_deg='north').status_code, 400)
        missing = self.client.post(f'/api/surveys/{self.survey["uuid"]}/assets', headers=self.admin, data={}, content_type='multipart/form-data')
        self.assertEqual(missing.get_json()['code'], 'missing_file')
        with self.app.app_context():
            self.assertEqual(LzSurveyAsset.query.count(), 0)

    def test_twenty_megabytes_at_most(self):
        with patch.object(asset_store, 'MAX_UPLOAD_BYTES', 1000):
            response = self.upload(jpeg_with_exif((800, 600), 1))
        self.assertEqual((response.status_code, response.get_json()['code']), (413, 'too_large'))

    def test_a_capture_is_kept_as_sent(self):
        out = io.BytesIO()
        Image.new('RGB', (1600, 1000), (1, 2, 3)).save(out, 'PNG')
        asset = self.upload(out.getvalue(), filename='view.png', kind='view_3d').get_json()
        self.assertEqual((asset['width'], asset['height'], asset['size_bytes']), (1600, 1000, len(out.getvalue())))
        self.assertEqual(self.upload(jpeg_with_exif((10, 10), 1), kind='view_3d').get_json()['code'], 'unsupported_image')

    def test_caption_heading_and_removal(self):
        asset = self.upload(jpeg_with_exif((800, 600), 1), kind='photo_approach').get_json()
        path = f'/api/surveys/{self.survey["uuid"]}/assets/{asset["id"]}'
        changed = self.client.patch(path, headers=self.admin, json={'caption': 'Down the approach', 'heading_deg': 360}).get_json()
        self.assertEqual((changed['caption'], changed['heading_deg'], changed['kind']), ('Down the approach', 0.0, 'photo_approach'))
        self.assertEqual(self.client.patch(path, headers=self.admin, json={'kind': 'view_3d'}).status_code, 400)
        self.assertEqual(self.client.patch(path, headers=self.admin, json={'heading_deg': 400}).status_code, 400)
        self.assertEqual(self.client.delete(path, headers=self.admin).status_code, 200)
        full = self.client.get(f'/api/surveys/{self.survey["uuid"]}', headers=self.admin).get_json()
        self.assertEqual(full['assets'], [])
        # Kept, bytes and all: a published revision may show it.
        self.assertEqual(self.client.get(path, headers=self.admin).status_code, 200)

    def test_an_asset_of_another_survey_is_not_found(self):
        asset = self.upload(jpeg_with_exif((80, 60), 1)).get_json()
        other = self.new_survey(name='LZ EAGLE')
        self.assertEqual(self.client.get(f'/api/surveys/{other["uuid"]}/assets/{asset["id"]}', headers=self.admin).status_code, 404)


class StoreTests(SurveyCase):
    def test_keys_are_sha256_and_nothing_else(self):
        store = asset_store.LocalAssetStore(self.store_dir)
        for key in ('../etc/passwd', 'ABC', 'a' * 63, None, 'g' * 64):
            with self.subTest(key=key), self.assertRaises(ValueError):
                store.put(key, b'x')
        key = 'ab' + '0' * 62
        store.put(key, b'one')
        store.put(key, b'two')              # a key names its content: never written over
        self.assertEqual(store.get(key), b'one')
        self.assertTrue(store.exists(key))
        store.delete(key)
        self.assertIsNone(store.get(key))
        store.delete(key)                   # deleting what is gone is nothing
        self.assertEqual([p.name for p in Path(self.store_dir).rglob('.incoming-*')], [])
