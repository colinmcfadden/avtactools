"""Map overlays kept in the Library (``/api/overlays``): each person's own files, web only and unsynced."""

import io
import sys
import unittest
import zipfile
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from auth_harness import NativeAuthCase  # noqa: E402
from models import SavedOverlay, User, db  # noqa: E402
from routes.overlay_routes import MAX_FILE_BYTES, MAX_PER_ACCOUNT, overlay_bp  # noqa: E402


def kmz():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('doc.kml', '<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><Point><coordinates>-84,34</coordinates></Point></Placemark></kml>')
    return buffer.getvalue()


class OverlayCase(NativeAuthCase):
    extra_blueprints = (overlay_bp,)

    def setUp(self):
        super().setUp()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}

    def upload(self, name='OBJ PEACH', data=None, file_name='peach.kmz', summary='3 features', head=None):
        form = {'name': name, 'summary': summary}
        if data is not False:
            form['file'] = (io.BytesIO(kmz() if data is None else data), file_name)
        return self.client.post('/api/overlays', headers=head or self.head, data=form, content_type='multipart/form-data')

    def made(self, **kwargs):
        response = self.upload(**kwargs)
        self.assertEqual(response.status_code, 201, response.get_data(as_text=True))
        return response.get_json()

    def other_head(self):
        self.make_account(email='other@example.com')
        return {'Authorization': f'Bearer {self.login(email="other@example.com")["access_token"]}'}


class Keeping(OverlayCase):
    def test_keeps_a_file_and_gives_it_back_byte_for_byte(self):
        saved = self.made()
        listed = self.client.get('/api/overlays', headers=self.head)
        self.assertEqual([one['name'] for one in listed.get_json()], ['OBJ PEACH'])
        self.assertNotIn('file', listed.get_json()[0], 'a list carries no file')
        self.assertIn('private', listed.headers['Cache-Control'])
        file = self.client.get(f"/api/overlays/{saved['id']}/file", headers=self.head)
        self.assertEqual(file.status_code, 200)
        self.assertEqual(file.get_data(), kmz())
        self.assertIn('private', file.headers['Cache-Control'])

    def test_takes_a_bare_kml(self):
        self.made(data=b'<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"></kml>', file_name='peach.kml')

    def test_renames(self):
        saved = self.made()
        response = self.client.put(f"/api/overlays/{saved['id']}", headers=self.head, json={'name': 'OBJ PLUM'})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()['name'], 'OBJ PLUM')

    def test_duplicates_with_the_file(self):
        saved = self.made()
        copy = self.client.post(f"/api/overlays/{saved['id']}/duplicate", headers=self.head, json={'name': 'OBJ PEACH 2'})
        self.assertEqual(copy.status_code, 201)
        self.assertEqual(self.client.get(f"/api/overlays/{copy.get_json()['id']}/file", headers=self.head).get_data(), kmz())

    def test_deletes(self):
        saved = self.made()
        self.assertEqual(self.client.delete(f"/api/overlays/{saved['id']}", headers=self.head).status_code, 200)
        self.assertEqual(self.client.get('/api/overlays', headers=self.head).get_json(), [])


class Refusing(OverlayCase):
    def code_of(self, response, status, code):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.assertEqual(response.get_json()['code'], code)

    def test_needs_a_file(self):
        self.code_of(self.upload(data=False), 400, 'missing_file')

    def test_needs_a_name(self):
        self.code_of(self.upload(name='   '), 400, 'invalid_name')

    def test_refuses_what_is_no_overlay(self):
        self.code_of(self.upload(data=b'GIF89a not an overlay'), 400, 'not_an_overlay')

    def test_refuses_too_large(self):
        self.code_of(self.upload(data=b'PK\x03\x04' + b'x' * MAX_FILE_BYTES), 413, 'too_large')

    def test_refuses_past_the_limit(self):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            for number in range(MAX_PER_ACCOUNT):
                db.session.add(SavedOverlay(user_id=user.id, name=f'O{number}', file=b'x', file_name='x.kmz', file_size=1, summary=''))
            db.session.commit()
        self.code_of(self.upload(), 409, 'too_many_overlays')

    def test_needs_cloud_save_to_write(self):
        with self.app.app_context():
            User.query.filter_by(email='pilot@example.com').one().features = {'cloud_save': False}
            db.session.commit()
        self.code_of(self.upload(), 403, 'feature_disabled')

    def test_needs_a_sign_in(self):
        self.assertEqual(self.client.get('/api/overlays').status_code, 401)


class EachPersonsOwn(OverlayCase):
    def test_another_account_sees_and_changes_nothing(self):
        saved = self.made()
        other = self.other_head()
        self.assertEqual(self.client.get('/api/overlays', headers=other).get_json(), [])
        for response in (
            self.client.get(f"/api/overlays/{saved['id']}/file", headers=other),
            self.client.put(f"/api/overlays/{saved['id']}", headers=other, json={'name': 'MINE'}),
            self.client.post(f"/api/overlays/{saved['id']}/duplicate", headers=other, json={'name': 'MINE'}),
            self.client.delete(f"/api/overlays/{saved['id']}", headers=other),
        ):
            self.assertEqual(response.status_code, 404)
        self.assertEqual(len(self.client.get('/api/overlays', headers=self.head).get_json()), 1)


if __name__ == '__main__':
    unittest.main()
