"""Books of LZ surveys, who reads and edits them, and publishing (docs/LZ_SURVEYS.md §3.4, §7; the owner's decision of 2026-10-26):
site admins everything; with ``lz_surveys``, a team's owner and admins edit its books and its members read their published pages, and
everyone reads those of a book for no team. A published revision keeps what was signed, and its PDF is printed once (Gotenberg and
the map's tiles stood in for)."""

import os
import sys
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from PIL import Image  # noqa: E402

import pack_support  # noqa: E402
from models import LzSurveyRevision, SurveyBook, Team, TeamMember, User, db  # noqa: E402
from routes.admin_routes import admin_bp  # noqa: E402
from survey import assets as asset_store  # noqa: E402
from test_survey_measure import DONOVAN, Terrain  # noqa: E402
from test_surveys import DIAGRAM, SurveyCase  # noqa: E402


def _tile(*_args, **_kwargs):
    return Image.new('RGB', (1024, 1024), (60, 90, 60))


class FakeAnswer:
    def __init__(self, status=200, content=b'%PDF-1.7\n%survey\n'):
        self.status_code = status
        self.content = content


class BookCase(SurveyCase):
    """The admin from SurveyCase, and five more: a team's owner, its admin and a member, all with the key; a member without it; and a
    key holder in no team."""

    def setUp(self):
        super().setUp()
        for email in ('owner@example.com', 'second@example.com', 'member@example.com', 'nokey@example.com', 'outsider@example.com'):
            self.make_account(email)
        with self.app.app_context():
            team = Team(name='B CO 1-171 AVN')
            other = Team(name='A CO 2-151 AVN')
            db.session.add_all([team, other])
            db.session.flush()
            self.team_id, self.other_team_id = team.id, other.id
            for email, role in (('owner@example.com', 'owner'), ('second@example.com', 'admin'), ('member@example.com', 'member'),
                                ('nokey@example.com', 'member')):
                user = User.query.filter_by(email=email).one()
                user.name = email.split('@')[0].title()
                db.session.add(TeamMember(team_id=team.id, user_id=user.id, role=role))
                if email != 'nokey@example.com':
                    user.features = {'lz_surveys': True}
            User.query.filter_by(email='outsider@example.com').one().features = {'lz_surveys': True}
            db.session.commit()
        self.owner = self.auth(self.login(email='owner@example.com'))
        self.second = self.auth(self.login(email='second@example.com'))
        self.member = self.auth(self.login(email='member@example.com'))
        self.nokey = self.auth(self.login(email='nokey@example.com'))
        self.outsider = self.auth(self.login(email='outsider@example.com'))

    def team_book(self, head=None, **body):
        response = self.client.post('/api/survey-books', headers=head or self.admin,
                                    json={'name': 'B CO HLZ BOOK', 'team_id': self.team_id, **body})
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()

    def survey_in(self, book, head=None, **body):
        response = self.client.post('/api/surveys', headers=head or self.admin,
                                    json={'book': book['uuid'], 'name': DONOVAN['name'], 'hlz_id': DONOVAN['hlzId'],
                                          'field': DONOVAN['field'], 'diagram': DIAGRAM, **body})
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()

    def books(self, head):
        return {b['name']: b for b in self.client.get('/api/survey-books', headers=head).get_json()['books']}

    def measured(self, survey, head=None):
        with Terrain(east_deg=4.0):
            response = self.client.post(f'/api/surveys/{survey["uuid"]}/measure', headers=self.at(survey['revision'], head),
                                        json={'apply': True})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response.get_json()['survey']

    def publish(self, survey, head=None, **body):
        head = head or self.admin
        submitted = self.client.post(f'/api/surveys/{survey["uuid"]}/submit', headers=self.at(survey['revision'], head))
        self.assertEqual(submitted.status_code, 200, submitted.get_data(as_text=True))
        survey = submitted.get_json()
        signoff = {'name': 'J. Doe', 'rank': 'CW4', 'title': 'Standardization IP', 'date': '27 OCT 2026'}
        response = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(survey['revision'], head),
                                    json={'signoff': signoff, **body})
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()


class WhoMakesBooksTests(BookCase):
    def test_a_site_admin_makes_a_book_for_any_team_or_none(self):
        self.assertEqual(self.team_book()['team']['name'], 'B CO 1-171 AVN')
        other = self.client.post('/api/survey-books', headers=self.admin, json={'name': 'A CO', 'team_id': self.other_team_id})
        self.assertEqual(other.status_code, 201)
        listed = self.client.get('/api/survey-books', headers=self.admin).get_json()
        self.assertTrue(listed['can_make_for_no_team'])
        self.assertEqual({t['name'] for t in listed['teams']}, {'B CO 1-171 AVN', 'A CO 2-151 AVN'})

    def test_a_team_s_owner_and_admin_make_books_for_their_team_only(self):
        for head in (self.owner, self.second):
            made = self.client.post('/api/survey-books', headers=head, json={'name': 'B CO', 'team_id': self.team_id})
            self.assertEqual(made.status_code, 201, made.get_data(as_text=True))
            self.assertTrue(made.get_json()['can_edit'])
            for team_id in (self.other_team_id, None, 999):
                refused = self.client.post('/api/survey-books', headers=head, json={'name': 'X', 'team_id': team_id})
                self.assertEqual((refused.status_code, refused.get_json()['code']), (403, 'not_editor'))
        listed = self.client.get('/api/survey-books', headers=self.owner).get_json()
        self.assertEqual([t['name'] for t in listed['teams']], ['B CO 1-171 AVN'])
        self.assertFalse(listed['can_make_for_no_team'])

    def test_a_member_or_a_key_holder_in_no_team_makes_none(self):
        for head in (self.member, self.outsider):
            refused = self.client.post('/api/survey-books', headers=head, json={'name': 'X', 'team_id': self.team_id})
            self.assertEqual(refused.status_code, 403)
        self.assertEqual(self.client.post('/api/survey-books', headers=self.nokey, json={'name': 'X'}).get_json()['code'],
                         'feature_disabled')


class WhoReadsTests(BookCase):
    def setUp(self):
        super().setUp()
        self.book = self.team_book()
        self.everyone = self.client.post('/api/survey-books', headers=self.admin, json={'name': 'EVERYONE'}).get_json()
        self.survey = self.survey_in(self.book)

    def test_the_team_s_editors_see_its_drafts_and_its_members_nothing_until_published(self):
        for head in (self.owner, self.second):
            self.assertTrue(self.books(head)['B CO HLZ BOOK']['can_edit'])
            self.assertEqual(self.client.get(f'/api/surveys/{self.survey["uuid"]}', headers=head).get_json()['status'], 'draft')
        self.assertFalse(self.books(self.member)['B CO HLZ BOOK']['can_edit'])
        draft = self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.member).get_json()
        self.assertEqual(draft['surveys'], [])                                   # nothing published yet
        self.assertNotIn('page_order', draft['book'])                             # which would name the drafts
        for path in (f'/api/surveys/{self.survey["uuid"]}', f'/api/surveys/{self.survey["uuid"]}/page'):
            self.assertEqual(self.client.get(path, headers=self.member).status_code, 404)

    def test_who_reads_a_team_s_book_and_a_book_for_no_team(self):
        # SurveyCase's own book is for no team too.
        self.assertEqual(set(self.books(self.member)), {'B CO HLZ BOOK', 'EVERYONE', 'AASF 1 HLZ SURVEY BOOK'})
        self.assertEqual(set(self.books(self.outsider)), {'EVERYONE', 'AASF 1 HLZ SURVEY BOOK'})   # the key, but not in the team
        self.assertEqual(self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.outsider).status_code, 404)
        self.assertEqual(self.client.get('/api/survey-books', headers=self.nokey).get_json()['code'], 'feature_disabled')

    def test_nobody_but_an_editor_changes_a_book_or_its_surveys(self):
        book_path = f'/api/survey-books/{self.book["uuid"]}'
        survey_path = f'/api/surveys/{self.survey["uuid"]}'
        for head, status in ((self.member, 403), (self.outsider, 404)):
            with self.subTest(head=head):
                self.assertEqual(self.client.patch(book_path, headers=self.at(1, head), json={'name': 'X'}).status_code, status)
                self.assertEqual(self.client.patch(survey_path, headers=self.at(1, head), json={'name': 'X'}).status_code, status)
                self.assertEqual(self.client.post(f'{survey_path}/measure', headers=head, json={}).status_code, status)
                made = self.client.post('/api/surveys', headers=head, json={'book': self.book['uuid'], 'name': 'LZ X'})
                self.assertEqual(made.status_code, status)
        renamed = self.client.patch(survey_path, headers=self.at(1, self.second), json={'name': 'LZ DONOVAN NORTH'})
        self.assertEqual((renamed.status_code, renamed.get_json()['updated_by']), (200, 'Second'))

    def test_only_a_site_admin_gives_a_book_to_another_team(self):
        path = f'/api/survey-books/{self.book["uuid"]}'
        revision = self.client.get(path, headers=self.owner).get_json()['book']['revision']
        for team_id in (self.other_team_id, None):
            refused = self.client.patch(path, headers=self.at(revision, self.owner), json={'team_id': team_id})
            self.assertEqual((refused.status_code, refused.get_json()['code']), (403, 'not_editor'))
        same = self.client.patch(path, headers=self.at(revision, self.owner), json={'team_id': self.team_id, 'unit_line': 'B CO'})
        self.assertEqual(same.status_code, 200)
        moved = self.client.patch(path, headers=self.at(same.get_json()['revision']), json={'team_id': self.other_team_id})
        self.assertEqual(moved.get_json()['team']['name'], 'A CO 2-151 AVN')
        self.assertNotIn('B CO HLZ BOOK', self.books(self.owner))

    def test_an_archived_book_is_a_site_admin_s_until_restored(self):
        archived = self.client.post(f'/api/survey-books/{self.book["uuid"]}/archive', headers=self.owner)
        self.assertTrue(archived.get_json()['archived'])
        for head in (self.owner, self.member):
            self.assertNotIn('B CO HLZ BOOK', self.books(head))
            self.assertEqual(self.client.get(f'/api/surveys/{self.survey["uuid"]}', headers=head).status_code, 404)
        self.assertEqual(self.client.post(f'/api/survey-books/{self.book["uuid"]}/restore', headers=self.owner).status_code, 404)
        listed = self.client.get('/api/survey-books?archived=1', headers=self.admin).get_json()['books']
        self.assertTrue(next(b for b in listed if b['name'] == 'B CO HLZ BOOK')['archived'])
        self.client.post(f'/api/survey-books/{self.book["uuid"]}/restore', headers=self.admin)
        self.assertIn('B CO HLZ BOOK', self.books(self.owner))

    def test_a_deleted_team_s_books_are_archived_never_everyone_s(self):
        with self.app.app_context():
            pack_support.delete_team(db.session.get(Team, self.team_id), User.query.filter_by(email='owner@example.com').one())
            db.session.commit()
            book = SurveyBook.query.filter_by(uuid=self.book['uuid']).one()
            self.assertIsNotNone(book.archived_at)
        self.assertNotIn('B CO HLZ BOOK', self.books(self.outsider))
        self.client.post(f'/api/survey-books/{self.book["uuid"]}/restore', headers=self.admin)
        restored = self.books(self.admin)['B CO HLZ BOOK']
        self.assertEqual(restored['team'], {'id': self.team_id, 'name': None, 'gone': True})
        self.assertNotIn('B CO HLZ BOOK', self.books(self.outsider))          # its team is gone: nobody's but the admins'

    def test_a_survey_moves_only_between_books_its_editor_edits(self):
        mine = self.team_book(head=self.owner, name='B CO SECOND BOOK')
        path = f'/api/surveys/{self.survey["uuid"]}'
        refused = self.client.patch(path, headers=self.at(1, self.owner), json={'book': self.everyone['uuid']})
        self.assertEqual(refused.status_code, 403)
        moved = self.client.patch(path, headers=self.at(1, self.owner), json={'book': mine['uuid']})
        self.assertEqual(moved.get_json()['book']['uuid'], mine['uuid'])
        with self.app.app_context():
            self.assertEqual(SurveyBook.query.filter_by(uuid=self.book['uuid']).one().page_order, [])
            self.assertEqual(SurveyBook.query.filter_by(uuid=mine['uuid']).one().page_order, [self.survey['uuid']])


class PublishingTests(BookCase):
    def setUp(self):
        super().setUp()
        self.book = self.team_book()
        patcher = patch('brief.figures._fetch_tile', _tile)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_a_survey_is_measured_reviewed_and_signed_off_by_its_team(self):
        survey = self.survey_in(self.book, head=self.owner)
        refused = self.client.post(f'/api/surveys/{survey["uuid"]}/submit', headers=self.at(1, self.owner))
        self.assertEqual((refused.status_code, refused.get_json()['code']), (409, 'not_measured'))
        survey = self.measured(survey, head=self.owner)
        not_yet = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(survey['revision'], self.owner),
                                   json={'signoff': {'name': 'J. Doe', 'date': '27 OCT 2026'}})
        self.assertEqual((not_yet.status_code, not_yet.get_json()['code']), (409, 'invalid_status'))
        published = self.publish(survey, head=self.second)
        revision = published['revision']
        self.assertEqual((revision['number'], revision['published_by'], revision['signoff']['rank']), (1, 'Second', 'CW4'))
        self.assertTrue(revision['changes'].startswith('New format.'))
        self.assertFalse(revision['printed'])                                # no Gotenberg here: printed when first asked for
        self.assertEqual(published['survey']['status'], 'published')
        self.assertEqual(published['survey']['published_revision'], 1)

        # The members read it; a change after it is a draft the members never see.
        listed = self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.member).get_json()['surveys']
        self.assertEqual([(s['name'], s['published_revision']) for s in listed], [('LZ DONOVAN', 1)])
        edited = self.client.patch(f'/api/surveys/{survey["uuid"]}', headers=self.at(published['survey']['revision'], self.owner),
                                   json={'name': 'LZ DONOVAN NORTH'})
        self.assertEqual(edited.get_json()['status'], 'draft')
        seen = self.client.get(f'/api/surveys/{survey["uuid"]}', headers=self.member).get_json()
        self.assertEqual((seen['name'], seen['published_revision'], seen['status']), ('LZ DONOVAN', 1, 'published'))
        self.assertNotIn('field', seen)

    def test_a_signoff_needs_a_name_and_a_date(self):
        survey = self.measured(self.survey_in(self.book))
        submitted = self.client.post(f'/api/surveys/{survey["uuid"]}/submit', headers=self.at(survey['revision'])).get_json()
        for signoff in (None, {}, {'name': 'J. Doe'}, {'date': '27 OCT 2026'}, {'name': 'x' * 121, 'date': 'today'}):
            with self.subTest(signoff=signoff):
                response = self.client.post(f'/api/surveys/{survey["uuid"]}/publish', headers=self.at(submitted['revision']),
                                            json={'signoff': signoff})
                self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_publish'))
        back = self.client.post(f'/api/surveys/{survey["uuid"]}/draft', headers=self.at(submitted['revision']))
        self.assertEqual(back.get_json()['status'], 'draft')

    def test_the_next_revision_says_what_changed(self):
        survey = self.measured(self.survey_in(self.book))
        first = self.publish(survey)['survey']
        edited = self.client.patch(f'/api/surveys/{survey["uuid"]}', headers=self.at(first['revision']),
                                   json={'field': {**DONOVAN['field'], 'surface': 'Gravel', 'maxAcDay': '3'}}).get_json()
        self.assertEqual(edited['suggested_changes'], 'Max A/C and surface changed.')
        self.assertEqual(edited['next_revision'], 2)
        second = self.publish(edited, changes='Surface regraded.')
        self.assertEqual((second['revision']['number'], second['revision']['changes']), (2, 'Surface regraded.'))
        with self.app.app_context():
            revisions = LzSurveyRevision.query.order_by(LzSurveyRevision.number).all()
            self.assertEqual([r.field['surface'] for r in revisions], ['Grass', 'Gravel'])     # each keeps what was signed
            self.assertEqual(revisions[0].page['bookName'], 'B CO HLZ BOOK')
            self.assertEqual((revisions[0].page['pageN'], revisions[0].page['pageOf']), (1, 1))

    def test_the_pdf_is_printed_once_and_kept(self):
        survey = self.measured(self.survey_in(self.book))
        posted = []

        def gotenberg(url, files=None, data=None, timeout=None):
            posted.append([part[1][0] for part in files])
            return FakeAnswer(content=b'%PDF-1.7\n%' + str(len(posted)).encode())

        with patch.dict(os.environ, {'GOTENBERG_URL': 'http://gotenberg:3000'}), patch('brief.gotenberg.requests.post', gotenberg):
            published = self.publish(survey)
            self.assertTrue(published['revision']['printed'])
            self.assertEqual(posted[0][:2], ['index.html', 'plan.jpg'])           # its own header and footer: Chromium's are not sent
            for head in (self.admin, self.member):
                pdf = self.client.get(f'/api/surveys/{survey["uuid"]}/pdf?rev=1', headers=head)
                self.assertEqual((pdf.status_code, pdf.mimetype, pdf.data), (200, 'application/pdf', b'%PDF-1.7\n%1'))
            self.assertEqual(len(posted), 1)                                       # printed once, at publishing
            reader = self.client.get(f'/api/surveys/{survey["uuid"]}/pdf', headers=self.member)
            self.assertEqual(reader.data, b'%PDF-1.7\n%1')                          # a reader gets the latest published
            draft = self.client.get(f'/api/surveys/{survey["uuid"]}/pdf?download=1', headers=self.owner)
            self.assertEqual(draft.data, b'%PDF-1.7\n%2')                           # an editor's "now" is printed each time, kept nowhere
            self.assertIn('attachment', draft.headers['Content-Disposition'])
            self.assertIn('GA 034 LZ DONOVAN PUBLISHED.pdf', draft.headers['Content-Disposition'])
        with self.app.app_context():
            kept = LzSurveyRevision.query.one().pdf_sha256
            self.assertEqual(asset_store.store().get(kept), b'%PDF-1.7\n%1')

    def test_without_gotenberg_the_pdf_is_unavailable_and_the_preview_shows_the_page(self):
        survey = self.measured(self.survey_in(self.book))
        unavailable = self.client.get(f'/api/surveys/{survey["uuid"]}/pdf', headers=self.admin)
        self.assertEqual((unavailable.status_code, unavailable.get_json()['code']), (503, 'pdf_unavailable'))
        page = self.client.get(f'/api/surveys/{survey["uuid"]}/page', headers=self.admin)
        self.assertEqual(page.status_code, 200)
        self.assertEqual(page.mimetype, 'text/html')
        self.assertIn("default-src 'none'", page.headers['Content-Security-Policy'])
        html = page.get_data(as_text=True)
        self.assertIn('LZ DONOVAN', html)
        self.assertIn('DRAFT', html)
        self.assertIn('src="data:image/jpeg;base64,', html)                       # its pictures are in it
        self.assertNotIn('src="plan.jpg"', html)
        self.assertEqual(self.client.get(f'/api/surveys/{survey["uuid"]}/page?rev=1', headers=self.admin).status_code, 404)
        self.assertEqual(self.client.get(f'/api/surveys/{survey["uuid"]}/page?rev=one', headers=self.admin).status_code, 400)

    def test_what_a_person_typed_is_escaped_on_the_page(self):
        survey = self.survey_in(self.book, field={**DONOVAN['field'], 'hazards': '<script>alert(1)</script> wires'},
                                name='LZ <b>X</b>')
        html = self.client.get(f'/api/surveys/{survey["uuid"]}/page', headers=self.admin).get_data(as_text=True)
        self.assertNotIn('<script>', html)
        self.assertIn('&lt;script&gt;', html)
        self.assertNotIn('<b>X</b>', html)

    def test_an_archived_survey_is_not_a_reader_s(self):
        survey = self.measured(self.survey_in(self.book))
        self.publish(survey)
        self.client.post(f'/api/surveys/{survey["uuid"]}/archive', headers=self.admin)
        self.assertEqual(self.client.get(f'/api/survey-books/{self.book["uuid"]}', headers=self.member).get_json()['surveys'], [])
        self.assertEqual(self.client.get(f'/api/surveys/{survey["uuid"]}/page', headers=self.member).status_code, 404)


class BulkTickTests(SurveyCase):
    """The admin dashboard gives the survey books' key to many people at once (§7, R1), and takes it away leaving nothing stored."""

    extra_blueprints = SurveyCase.extra_blueprints + (admin_bp,)

    def setUp(self):
        super().setUp()
        for email in ('a@example.com', 'b@example.com', 'c@example.com'):
            self.make_account(email)
        self.app.secret_key = 'test-only-admin-session-key'
        self.app.root_path = str(BACKEND_DIR)        # so the dashboard's templates are found, as in production
        with self.app.app_context():
            admin_id = User.query.filter_by(email='admin@example.com').one().id
            self.ids = {u.email: u.id for u in User.query.all()}
            User.query.filter_by(email='c@example.com').one().features = {'cloud_save': False}
            db.session.commit()
        self.dashboard = self.app.test_client()
        with self.dashboard.session_transaction() as sess:
            sess['admin_uid'] = admin_id
            sess['csrf'] = 'token'

    def features(self, email):
        with self.app.app_context():
            return User.query.filter_by(email=email).one().features or {}

    def test_the_list_ticks_people_for_a_feature(self):
        page = self.dashboard.get('/admin/').get_data(as_text=True)
        self.assertIn('name="uid"', page)
        self.assertIn('<option value="lz_surveys" selected>LZ surveys</option>', page)

    def test_given_to_everyone_ticked_and_taken_away_leaving_nothing(self):
        ticked = [self.ids['a@example.com'], self.ids['c@example.com']]
        response = self.dashboard.post('/admin/users/features/bulk', data={'csrf': 'token', 'feature': 'lz_surveys', 'action': 'grant',
                                                                          'uid': [str(i) for i in ticked]})
        self.assertEqual(response.status_code, 302)
        self.assertEqual(self.features('a@example.com'), {'lz_surveys': True})
        self.assertEqual(self.features('c@example.com'), {'cloud_save': False, 'lz_surveys': True})   # what else they had stays
        self.assertEqual(self.features('b@example.com'), {})
        self.dashboard.post('/admin/users/features/bulk', data={'csrf': 'token', 'feature': 'lz_surveys', 'action': 'revoke',
                                                                'uid': [str(i) for i in ticked]})
        # Not launched yet: unticked is its default, so nothing is stored that would keep them off once it launches.
        self.assertEqual(self.features('a@example.com'), {})
        self.assertEqual(self.features('c@example.com'), {'cloud_save': False})

    def test_nothing_ticked_or_no_token_changes_nothing(self):
        self.dashboard.post('/admin/users/features/bulk', data={'csrf': 'token', 'feature': 'lz_surveys', 'action': 'grant'})
        self.dashboard.post('/admin/users/features/bulk', data={'csrf': 'token', 'feature': 'nonsense', 'action': 'grant',
                                                                'uid': [str(self.ids['a@example.com'])]})
        refused = self.dashboard.post('/admin/users/features/bulk', data={'feature': 'lz_surveys', 'action': 'grant',
                                                                          'uid': [str(self.ids['a@example.com'])]})
        self.assertEqual(refused.status_code, 400)
        self.assertEqual(self.features('a@example.com'), {})
