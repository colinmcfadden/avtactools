"""What a signed page is held to (docs/LZ_SURVEYS.md §3.3, §3.4; step L5's review): a survey is measured again before it is sent for
review or published once its target, boundary or best approach has moved on from its snapshot; a published revision keeps its pictures
as they were signed; a photo changed on a survey under review or published makes it a draft again; a save that lands while another
request measures is not undone by it; and what a person types cannot make the page fail."""

import io
import os
import re
import sys
import tempfile
import threading
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from models import LzSurvey, LzSurveyRevision, db  # noqa: E402
from survey import measure  # noqa: E402
from test_survey_books import BookCase, _tile  # noqa: E402
from test_survey_measure import DONOVAN, TARGET, Terrain, local, square  # noqa: E402
from test_surveys import DIAGRAM, jpeg_with_exif  # noqa: E402

SIGNOFF = {'name': 'J. Doe', 'rank': 'CW4', 'title': 'Standardization IP', 'date': '27 OCT 2026'}


class RevisionCase(BookCase):
    def setUp(self):
        super().setUp()
        self.book = self.team_book()
        tiles = patch('brief.figures._fetch_tile', _tile)
        tiles.start()
        self.addCleanup(tiles.stop)

    def get(self, survey, head=None):
        response = self.client.get(f'/api/surveys/{survey["uuid"]}', headers=head or self.admin)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response.get_json()

    def page(self, survey, rev=None, head=None):
        response = self.client.get(f'/api/surveys/{survey["uuid"]}/page' + (f'?rev={rev}' if rev else ''), headers=head or self.admin)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return re.sub(r'\s+', ' ', response.get_data(as_text=True))

    def upload(self, survey, **form):
        return self.client.post(f'/api/surveys/{survey["uuid"]}/assets', headers=self.admin, content_type='multipart/form-data',
                                data={'file': (io.BytesIO(jpeg_with_exif(size=(800, 600))), 'photo.jpg'), **form})

    def submit(self, survey):
        return self.client.post(f'/api/surveys/{survey["uuid"]}/submit', headers=self.at(survey['revision']))


class StaleTests(RevisionCase):
    def test_a_turned_approach_must_be_measured_again_before_review(self):
        survey = self.measured(self.survey_in(self.book, field={**DONOVAN['field'], 'approachDeg': '090'}))   # ground rising east
        self.assertEqual(survey['stale'], [])
        self.assertGreater(survey['snapshot']['slope']['noseUp']['p95'], 3.0)
        turned = self.patch_survey(survey, {'field': {**survey['field'], 'approachDeg': '270'}}).get_json()
        self.assertEqual(turned['stale'], ['The best approach changed after it was measured: measured at 090°, now 270°.'])
        refused = self.submit(turned)
        self.assertEqual((refused.status_code, refused.get_json()['code']), (409, 'measure_again'))
        self.assertEqual(refused.get_json()['reasons'], turned['stale'])
        # The page says so, and its slope is labelled with the heading it was measured at, never the approach typed since.
        page = self.page(turned)
        self.assertIn('The best approach changed after it was measured: measured at 090°, now 270°. Measure it again.', page)
        self.assertIn('Slope at 090°', page)
        self.assertNotIn('Slope at 270°', page)
        again = self.measured(turned)
        self.assertEqual(again['stale'], [])
        slope = again['snapshot']['slope']
        self.assertEqual(slope['headingDeg'], 270.0)
        self.assertGreater(slope['noseDown']['p95'], 3.0)                                # the same ground, flown the other way
        self.assertEqual(slope['noseUp']['p95'], 0.0)
        self.assertEqual(self.submit(again).status_code, 200)

    def test_a_moved_target_or_boundary_must_be_measured_again_before_publishing(self):
        survey = self.measured(self.survey_in(self.book))
        submitted = self.submit(survey).get_json()
        moved = {**submitted['diagram'], 'target': {'lat': TARGET[0] + 0.001, 'lon': TARGET[1], 'mgrs': ''}}
        changed = self.patch_survey(submitted, {'diagram': moved}).get_json()
        self.assertEqual(changed['status'], 'draft')
        self.assertEqual(changed['stale'], ['The target moved after it was measured.'])
        drawn = {**changed['diagram'], 'target': submitted['diagram']['target'],
                 'analysis': {**changed['diagram']['analysis'], 'detectedLZ': square(40)}}
        redrawn = self.patch_survey(changed, {'diagram': drawn}).get_json()
        self.assertEqual(redrawn['stale'], ['The boundary changed after it was measured.'])
        self.assertEqual(self.submit(redrawn).get_json()['code'], 'measure_again')

    def test_a_snapshot_from_before_this_version_is_measured_again(self):
        survey = self.measured(self.survey_in(self.book))
        with self.app.app_context():
            row = LzSurvey.query.filter_by(uuid=survey['uuid']).one()
            old = {k: v for k, v in row.snapshot.items() if k not in ('boundary', 'approachDeg')}
            row.snapshot = {**old, 'version': 1}
            db.session.commit()
        self.assertEqual(self.get(survey)['stale'], ['It was measured by an earlier version of EZ/PZ.'])
        self.assertIn('It was measured by an earlier version of EZ/PZ. Measure it again.', self.page(survey))
        self.assertEqual(measure.stale(None, DIAGRAM, {}), [])
        self.assertEqual(measure.stale({}, DIAGRAM, {}), [])

    def test_a_signed_page_never_says_it_should_be_measured_again(self):
        survey = self.measured(self.survey_in(self.book))
        self.publish(survey)
        with self.app.app_context():
            revision = LzSurveyRevision.query.one()
            old = {k: v for k, v in revision.snapshot.items() if k not in ('boundary', 'approachDeg')}
            revision.snapshot = {**old, 'version': 1}                              # a revision signed before snapshots said what they were for
            db.session.commit()
        for head in (self.admin, self.member):
            self.assertNotIn('Measure it again', self.page(survey, rev=1, head=head))


class FrozenTests(RevisionCase):
    def test_a_revision_keeps_its_pictures_as_they_were_signed(self):
        survey = self.measured(self.survey_in(self.book))
        photo = self.upload(survey, kind='photo_other', caption='South edge', taken_on='OCT 2023').get_json()
        survey = self.get(survey)
        self.publish(survey, changes='First.')
        self.assertIn('SOUTH EDGE · OCT 23', self.page(survey, rev=1))
        changed = self.client.patch(f'/api/surveys/{survey["uuid"]}/assets/{photo["id"]}', headers=self.admin,
                                    json={'caption': 'Not what was signed', 'taken_on': 'JAN 2031'})
        self.assertEqual(changed.status_code, 200)
        for head in (self.admin, self.member):
            signed = self.page(survey, rev=1, head=head)
            self.assertIn('SOUTH EDGE · OCT 23', signed)
            self.assertNotIn('NOT WHAT WAS SIGNED', signed)
        self.assertIn('NOT WHAT WAS SIGNED · JAN 31', self.page(survey))                  # the draft has it


class PhotoStatusTests(RevisionCase):
    def test_a_photo_changed_after_publishing_makes_the_survey_a_draft_again(self):
        survey = self.measured(self.survey_in(self.book))
        published = self.publish(survey)['survey']
        self.assertEqual(published['status'], 'published')
        added = self.upload(published, kind='photo_other', caption='New')
        self.assertEqual(added.status_code, 201)
        self.assertEqual(added.get_json()['survey'],
                         {'status': 'draft', 'revision': published['revision'] + 1, 'was': published['revision']})
        now = self.get(published)
        self.assertEqual((now['status'], now['revision']), ('draft', published['revision'] + 1))
        self.assertIn('DRAFT', self.page(now))
        # The member still reads REV 1, and the change is publishable as REV 2.
        self.assertEqual(self.client.get(f'/api/surveys/{survey["uuid"]}', headers=self.member).get_json()['published_revision'], 1)
        self.assertEqual(self.publish(now)['revision']['number'], 2)

    def test_under_review_a_photo_change_sends_it_back_and_a_draft_stays_as_it_is(self):
        survey = self.measured(self.survey_in(self.book))
        draft_answer = self.upload(survey, kind='photo_north').get_json()
        self.assertEqual(draft_answer['survey'], {'status': 'draft', 'revision': survey['revision'], 'was': survey['revision']})
        submitted = self.submit(self.get(survey)).get_json()
        removed = self.client.delete(f'/api/surveys/{survey["uuid"]}/assets/{draft_answer["id"]}', headers=self.admin).get_json()
        self.assertEqual(removed['survey'], {'status': 'draft', 'revision': submitted['revision'] + 1, 'was': submitted['revision']})
        # The copy the reviewer held is no longer the one to publish.
        refused = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(submitted['revision']),
                                   json={'signoff': SIGNOFF})
        self.assertEqual(refused.status_code, 409)
        same = self.client.patch(f'/api/surveys/{survey["uuid"]}/assets/{draft_answer["id"]}', headers=self.admin, json={})
        self.assertEqual(same.status_code, 404)                                          # taken off: nothing to change


class TwoAtOnceTests(RevisionCase):
    """Two requests in two threads, so two sessions: a database both can see."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.database_uri = f'sqlite:///{directory.name}/surveys.db'
        super().setUp()

    def test_a_save_made_while_a_measurement_runs_is_not_undone_by_it(self):
        survey = self.survey_in(self.book)
        other = self.app.test_client()
        answers = []
        real = measure.measure

        def measuring(*args, **kwargs):
            def save():
                moved = {**survey['diagram'], 'target': {'lat': TARGET[0] + 0.0005, 'lon': TARGET[1], 'mgrs': ''}}
                answers.append(other.patch(f'/api/surveys/{survey["uuid"]}', headers=self.at(survey['revision']),
                                           json={'name': 'LZ DONOVAN WEST', 'diagram': moved}))
            thread = threading.Thread(target=save)
            thread.start()
            thread.join()
            return real(*args, **kwargs)

        with patch('routes.survey_routes.measure.measure', measuring), Terrain():
            applied = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(survey['revision']),
                                       json={'apply': True})
        (saved,) = answers
        self.assertEqual(saved.status_code, 200)
        self.assertEqual((applied.status_code, applied.get_json()['code']), (409, 'revision_conflict'))
        self.assertEqual(applied.get_json()['server']['name'], 'LZ DONOVAN WEST')
        stored = self.get(survey)
        self.assertEqual((stored['name'], stored['revision'], stored['snapshot']), ('LZ DONOVAN WEST', 2, None))
        self.assertAlmostEqual(stored['diagram']['target']['lat'], TARGET[0] + 0.0005)


class TypedTests(RevisionCase):
    def test_a_number_too_long_to_be_one_is_text_and_never_a_failure(self):
        survey = self.measured(self.survey_in(self.book))
        self.publish(survey)
        current = self.get(survey)
        long_number = '9' * 400
        field = {**current['field'], 'profileObstacleFt': long_number, 'profileDistanceM': '200', 'elevationFt': long_number,
                 'size': f'{long_number} x 300'}
        saved = self.patch_survey(current, {'field': field})
        self.assertEqual(saved.status_code, 200, saved.get_data(as_text=True))
        self.assertEqual(self.get(survey)['field']['elevationFt'], long_number)
        self.page(survey)

    def test_odd_query_values_are_refused_or_ignored_not_failures(self):
        survey = self.measured(self.survey_in(self.book))
        refused = self.client.get(f'/api/surveys/{survey["uuid"]}/page?rev=²', headers=self.admin)
        self.assertEqual((refused.status_code, refused.get_json()['code']), (400, 'invalid_revision'))
        for opacity in ('inf', 'nan', '1e999', 'x'):
            response = self.client.get(f'/api/surveys/{survey["uuid"]}/page?opacity={opacity}', headers=self.admin)
            self.assertEqual(response.status_code, 200, opacity)
        submitted = self.submit(survey).get_json()
        published = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(submitted['revision']),
                                     data=f'{{"signoff": {{"name": "J. Doe", "date": "27 OCT 2026"}}, "opacity": 1e999}}',
                                     content_type='application/json')
        self.assertEqual(published.status_code, 201, published.get_data(as_text=True))

    def test_a_print_that_fails_after_publishing_does_not_undo_it(self):
        survey = self.measured(self.survey_in(self.book))
        submitted = self.submit(survey).get_json()
        with patch.dict(os.environ, {'GOTENBERG_URL': 'http://gotenberg:3000'}), \
                patch('routes.survey_routes.pages.print_pdf', side_effect=KeyError('a figure')):
            published = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(submitted['revision']),
                                         json={'signoff': SIGNOFF})
        self.assertEqual(published.status_code, 201, published.get_data(as_text=True))
        self.assertEqual(self.get(survey)['status'], 'published')


class ReaderTests(RevisionCase):
    def test_a_reader_s_book_says_nothing_of_the_drafts(self):
        survey = self.measured(self.survey_in(self.book))
        reader = self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.member).get_json()['book']
        for key in ('revision', 'updated_by', 'updated_at', 'page_order'):
            self.assertNotIn(key, reader)
        self.assertIsNone(reader['published_at'])
        self.publish(survey)
        reader = self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.member).get_json()['book']
        self.assertTrue(reader['published_at'].endswith('Z'))
        editor = self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.owner).get_json()['book']
        self.assertIn('revision', editor)
        draft = self.survey_in(self.book, name='LZ OTHER', hlz_id='GA 999')
        self.assertEqual(self.client.get(f'/api/surveys/{draft["uuid"]}/page', headers=self.member).status_code, 404)
        self.assertEqual(self.client.post(f'/api/surveys/{draft["uuid"]}/submit', headers=self.at(1, self.member)).status_code, 404)

    def test_a_boundary_with_points_out_of_order_is_still_a_boundary(self):
        # The snapshot keeps the boundary as the diagram had it: the same corners in the same order are not a change.
        survey = self.measured(self.survey_in(self.book, diagram={**DIAGRAM, 'analysis': {'detectedLZ': square(50) + [square(50)[0]]}}))
        self.assertEqual(survey['stale'], [])
        self.assertEqual(len(survey['snapshot']['boundary']), 4)
        self.assertEqual(survey['snapshot']['boundary'][0], [round(local(-50, 50)[0], 7), round(local(-50, 50)[1], 7)])
