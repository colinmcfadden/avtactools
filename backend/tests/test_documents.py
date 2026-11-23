"""Documents (docs/DOCUMENTS.md): an upload read and kept as a PDF with its page text, in the Library and in packs, and its files kept
by hash until nothing names them."""

import io
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
TESTS_DIR = Path(__file__).resolve().parent
for folder in (BACKEND_DIR, TESTS_DIR):
    if str(folder) not in sys.path:
        sys.path.insert(0, str(folder))

from app_config import build_config  # noqa: E402
from auth_harness import PASSWORD, NativeAuthCase  # noqa: E402
from document_samples import FakeResponse, encrypted, ole, ooxml, pdf_with  # noqa: E402
from brief import gotenberg  # noqa: E402
from documents import core, files  # noqa: E402
from models import MissionPackItem, SavedDocument, User, db  # noqa: E402
from openapi_check import check_response, load_spec  # noqa: E402
from routes.document_routes import documents_bp  # noqa: E402
from routes.sync_routes import sync_bp  # noqa: E402
from test_packs import PackCase  # noqa: E402

SPEC = load_spec()
OPORD = pdf_with('OPORD 24-03 (OPERATION DARK KNIGHT)', 'Annex C: LZ HAWK at 16S GC 31204 58811', 'Annex D: PZ CROW')
CHANGE_1 = pdf_with('OPORD 24-03 CHANGE 1', 'Annex C: LZ HAWK moves to LZ FALCON')
SCAN = pdf_with('', '')
DOCX_AS_PDF = pdf_with('Converted by LibreOffice')


class DocumentStore:
    """A document store of its own for each test (``DOCUMENTS_DIR``), removed after."""

    def use_a_store(self):
        self.store_dir = tempfile.mkdtemp(prefix='ezpz-documents-test-')
        environ = patch.dict(os.environ, {'DOCUMENTS_DIR': self.store_dir, 'GOTENBERG_URL': 'http://gotenberg.test'})
        environ.start()
        self.addCleanup(environ.stop)
        self.addCleanup(shutil.rmtree, self.store_dir, True)
        self.gotenberg = []

        def post(url, files=None, data=None, timeout=None, stream=False):
            self.gotenberg.append({'url': url, 'files': files, 'data': data, 'stream': stream})
            return self.gotenberg_answer

        self.gotenberg_answer = FakeResponse(200, DOCX_AS_PDF)
        self.gotenberg_version = FakeResponse(200, text='8.34.0')
        poster = patch('brief.gotenberg.requests.post', side_effect=post)
        poster.start()
        self.addCleanup(poster.stop)
        getter = patch('brief.gotenberg.requests.get', side_effect=lambda url, timeout=None: self.gotenberg_version)
        getter.start()
        self.addCleanup(getter.stop)
        forget_gotenberg_version()
        self.addCleanup(forget_gotenberg_version)

    def stored(self):
        return sorted(name for _folder, _dirs, names in os.walk(self.store_dir) for name in names)


def forget_gotenberg_version():
    """Gotenberg's version is kept for a while; each test asks its own (fake) Gotenberg afresh."""
    gotenberg._version.update(base=None, value=None, until=0.0)


def sha(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


# -- What an upload becomes ------------------------------------------------------------------------------------------------------

class KindTests(unittest.TestCase):
    def test_a_file_is_read_by_its_content(self):
        for data, name, kind in (
            (OPORD, 'whatever.docx', 'pdf'),
            (b'   ' + OPORD, None, 'pdf'),                          # a little before the header, as PDF readers allow
            (ooxml('docx'), 'opord.pdf', 'docx'),
            (ooxml('xlsx'), None, 'xlsx'),
            (ooxml('pptx'), 'brief', 'pptx'),
            (ole('Root Entry', 'WordDocument'), None, 'doc'),
            (ole('Root Entry', 'PowerPoint Document'), None, 'ppt'),
            (ole('Root Entry', 'Workbook'), None, 'xls'),
            (ole('Root Entry'), 'old.xls', 'xls'),                  # streams this did not find: the name settles a legacy file
        ):
            with self.subTest(kind=kind, name=name):
                self.assertEqual(core.kind_of(data, name), kind)

    def test_anything_else_is_refused(self):
        for data, name in ((b'just text', 'notes.pdf'), (ole('Root Entry'), 'mail.msg'), (b'\x89PNG\r\n\x1a\n', 'map.png')):
            with self.subTest(name=name), self.assertRaises(core.DocumentError) as raised:
                core.kind_of(data, name)
            self.assertEqual((raised.exception.code, raised.exception.status), ('unsupported_type', 415))

    def test_a_zip_that_is_not_an_office_file_is_refused(self):
        import io
        import zipfile
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive:
            archive.writestr('points.xml', '<points/>')                 # an AMPS mission's part
        with self.assertRaises(core.DocumentError) as raised:
            core.kind_of(out.getvalue(), 'mission.docx')
        self.assertEqual(raised.exception.code, 'unsupported_type')

    def test_a_password_protected_office_file_is_refused_before_it_is_sent(self):
        with self.assertRaises(core.DocumentError) as raised:
            core.kind_of(ole('Root Entry', 'EncryptionInfo', 'EncryptedPackage'), 'opord.docx')
        self.assertEqual(raised.exception.code, 'password_protected')

    def test_a_broken_zip_is_refused(self):
        with self.assertRaises(core.DocumentError) as raised:
            core.kind_of(b'PK\x03\x04 and then nothing', 'opord.docx')
        self.assertEqual(raised.exception.code, 'unreadable')


class PdfTests(unittest.TestCase):
    def test_pages_and_their_text(self):
        pages, texts, truncated = core.read_pdf(OPORD)
        self.assertEqual(pages, 3)
        self.assertEqual(texts[1], 'Annex C: LZ HAWK at 16S GC 31204 58811')
        self.assertFalse(truncated)

    def test_a_pdf_that_needs_a_password_is_refused_and_one_that_only_restricts_is_taken(self):
        with self.assertRaises(core.DocumentError) as raised:
            core.read_pdf(encrypted(OPORD, 'secret'))
        self.assertEqual(raised.exception.code, 'password_protected')
        self.assertEqual(core.read_pdf(encrypted(OPORD, ''))[0], 3)

    def test_a_damaged_pdf_is_refused(self):
        for data in (b'%PDF-1.4 and nothing else', OPORD[:200], b'%PDF-' + os.urandom(2000)):
            with self.subTest(size=len(data)), self.assertRaises(core.DocumentError) as raised:
                core.read_pdf(data)
            self.assertIn(raised.exception.code, ('unreadable', 'no_pages'))

    def test_the_page_limit(self):
        with patch.object(core, 'MAX_PAGES', 2), self.assertRaises(core.DocumentError) as raised:
            core.read_pdf(OPORD)
        self.assertEqual((raised.exception.code, raised.exception.status), ('too_many_pages', 413))
        self.assertIn('this one has 3', str(raised.exception))

    def test_the_text_limit_cuts_and_says_so(self):
        with patch.object(core, 'MAX_TEXT_CHARS', 40):
            _pages, texts, truncated = core.read_pdf(OPORD)
        self.assertTrue(truncated)
        self.assertEqual(sum(len(t) for t in texts), 40)
        self.assertEqual(texts[2], '')

    def test_white_space_is_folded(self):
        self.assertEqual(core._fold('  LZ\u00a0HAWK\n\n at \t 16S\x00GC  '), 'LZ HAWK at 16S GC')

    def test_a_scan_has_no_text_file(self):
        _pages, texts, truncated = core.read_pdf(SCAN)
        self.assertIsNone(core.text_file(texts, truncated))

    def test_the_text_file(self):
        import json
        body = json.loads(core.text_file(['one', '', 'three'], False))
        self.assertEqual(body, {'version': 1, 'pages': ['one', '', 'three'], 'truncated': False})


class PrepareTests(DocumentStore, unittest.TestCase):
    def setUp(self):
        self.use_a_store()

    def test_a_pdf_is_kept_as_sent(self):
        prepared = core.prepare(OPORD, 'OPORD 24-03.pdf')
        self.assertEqual((prepared.pdf, prepared.kind, prepared.pages), (OPORD, 'pdf', 3))
        self.assertEqual(self.gotenberg, [])

    def test_an_office_file_is_converted_by_libreoffice(self):
        prepared = core.prepare(ooxml('docx'), 'OPORD.docx')
        self.assertEqual((prepared.pdf, prepared.kind), (DOCX_AS_PDF, 'docx'))
        call = self.gotenberg[0]
        self.assertEqual(call['url'], 'http://gotenberg.test/forms/libreoffice/convert')
        self.assertEqual(call['files'][0][1][0], 'document.docx')
        self.assertEqual((call['data']['exportNotes'], call['data']['exportHiddenSlides']), ('false', 'false'))
        self.assertNotIn('singlePageSheets', call['data'])

    def test_a_spreadsheet_is_one_page_a_sheet(self):
        core.prepare(ooxml('xlsx'), 'SYNC MATRIX.xlsx')
        self.assertEqual(self.gotenberg[0]['data']['singlePageSheets'], 'true')

    def test_what_gotenberg_answers(self):
        for status, content, code, http in (
            (400, b'', 'conversion_refused', 422),
            (503, b'', 'converter_busy', 503),
            (500, b'', 'conversion_failed', 502),
            (200, b'not a pdf', 'conversion_failed', 502),
        ):
            with self.subTest(status=status):
                self.gotenberg_answer = FakeResponse(status, content)
                with self.assertRaises(core.DocumentError) as raised:
                    core.prepare(ooxml('pptx'), 'brief.pptx')
                self.assertEqual((raised.exception.code, raised.exception.status), (code, http))

    def test_a_gotenberg_too_old_converts_nothing(self):
        self.gotenberg_version = FakeResponse(200, text='8.21.1')
        self.assertEqual(core.prepare(OPORD, 'x.pdf').kind, 'pdf')
        with self.assertRaises(core.DocumentError) as raised:
            core.prepare(ooxml('docx'), 'x.docx')
        self.assertEqual((raised.exception.code, raised.exception.status), ('conversion_unavailable', 503))
        self.assertEqual(self.gotenberg, [])

    def test_a_conversion_is_read_only_as_far_as_a_pdf_may_be(self):
        self.gotenberg_answer = FakeResponse(200, b'%PDF-1.7\n' + b'0' * 5000)
        with patch.object(core, 'MAX_PDF_BYTES', 4096), self.assertRaises(core.DocumentError) as raised:
            core.prepare(ooxml('docx'), 'x.docx')
        self.assertEqual((raised.exception.code, raised.exception.status), ('pdf_too_large', 413))
        self.assertTrue(self.gotenberg[0]['stream'])

    def test_a_zips_directory_is_read_from_its_end_record_first(self):
        import struct
        import zipfile as zf

        def with_end(data, entries, size):
            at = data.rfind(b'PK\x05\x06')
            return data[:at + 10] + struct.pack('<HI', entries, size) + data[at + 16:]

        docx = ooxml('docx')
        self.assertEqual(core.kind_of(docx), 'docx')
        for entries, size in ((core._MAX_ZIP_ENTRIES + 1, 1000), (3, core._MAX_ZIP_DIRECTORY + 1)):
            with self.subTest(entries=entries, size=size), self.assertRaises(core.DocumentError) as raised:
                core.kind_of(with_end(docx, entries, size))
            self.assertEqual(raised.exception.code, 'unreadable')
        with self.assertRaises(core.DocumentError) as raised:
            core.kind_of(docx.replace(b'PK\x05\x06', b'PK\x00\x00'))
        self.assertEqual(raised.exception.code, 'unreadable')
        # Zip64 keeps its counts in a record of its own, which is read in their place.
        buffer = io.BytesIO()
        with zf.ZipFile(buffer, 'w') as archive:
            for name in ('[Content_Types].xml', 'word/document.xml'):
                archive.writestr(name, '<x/>')
        plain = buffer.getvalue()
        self.assertEqual(core._zip_directory(plain), (2, plain.rfind(b'PK\x05\x06') - plain.find(b'PK\x01\x02')))
        at = plain.rfind(b'PK\x05\x06')
        directory_at = plain.find(b'PK\x01\x02')
        record = (b'PK\x06\x06' + struct.pack('<QHHII', 44, 45, 45, 0, 0)
                  + struct.pack('<QQQQ', 2, 2, at - directory_at, directory_at))
        locator = b'PK\x06\x07' + struct.pack('<IQI', 0, at, 1)
        end = plain[at:at + 10] + struct.pack('<HII', 0xFFFF, 0xFFFFFFFF, 0xFFFFFFFF) + plain[at + 20:]
        zip64 = plain[:at] + record + locator + end
        self.assertEqual(core._zip_directory(zip64), (2, at - directory_at))
        self.assertIsNone(core._zip_directory(plain[:at] + locator[:4] + b'\x00' * 16 + end))

    def test_only_so_many_uploads_are_read_at_once(self):
        for _ in range(core.READING_AT_ONCE):
            self.assertTrue(core._reading.acquire(timeout=1))
        try:
            with patch.object(core, 'READ_WAIT_S', 0.05), self.assertRaises(core.DocumentError) as raised:
                core.prepare(OPORD, 'x.pdf')
            self.assertEqual((raised.exception.code, raised.exception.status), ('converter_busy', 503))
        finally:
            for _ in range(core.READING_AT_ONCE):
                core._reading.release()
        self.assertEqual(core.prepare(OPORD, 'x.pdf').kind, 'pdf')
        with self.assertRaises(core.DocumentError):
            core.prepare(b'not a document', 'x.pdf')
        # A refusal gives its place back.
        self.assertEqual(core.prepare(OPORD, 'x.pdf').kind, 'pdf')

    def test_without_gotenberg_a_pdf_is_taken_and_an_office_file_is_not(self):
        with patch.dict(os.environ, {'GOTENBERG_URL': ''}):
            self.assertEqual(core.prepare(OPORD, 'x.pdf').kind, 'pdf')
            with self.assertRaises(core.DocumentError) as raised:
                core.prepare(ooxml('docx'), 'x.docx')
        self.assertEqual((raised.exception.code, raised.exception.status), ('conversion_unavailable', 503))

    def test_the_size_limits(self):
        with patch.object(core, 'MAX_UPLOAD_BYTES', 100), self.assertRaises(core.DocumentError) as raised:
            core.prepare(OPORD, 'x.pdf')
        self.assertEqual((raised.exception.code, raised.exception.status), ('file_too_large', 413))
        with self.assertRaises(core.DocumentError) as raised:
            core.prepare(b'', 'x.pdf')
        self.assertEqual(raised.exception.code, 'empty_file')

    def test_what_describes_it(self):
        prepared = core.prepare(OPORD, 'C:\\plans\\OPORD 24-03.pdf')
        data = core.describe(prepared, 'C:\\plans\\OPORD 24-03.pdf', len(OPORD))
        self.assertEqual(data['pdf'], {'sha256': sha(OPORD), 'bytes': len(OPORD), 'pages': 3})
        self.assertEqual(data['text']['sha256'], prepared.text_sha256)
        self.assertEqual(data['source'], {'type': 'pdf', 'name': 'OPORD 24-03.pdf', 'bytes': len(OPORD)})
        self.assertRegex(data['added_at'], r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$')
        self.assertEqual(core.hashes_of(data), {sha(OPORD), prepared.text_sha256})
        self.assertIsNone(core.describe(core.prepare(SCAN, 's.pdf'), 's.pdf', len(SCAN))['text'])

    def test_a_name_from_a_file_name(self):
        for filename, name in (('OPORD 24-03.docx', 'OPORD 24-03'), ('C:\\x\\annex.c.pdf', 'annex.c'), ('', 'DOCUMENT'),
                               (None, 'DOCUMENT'), ('.pdf', '.pdf'), ('a' * 150 + '.pdf', 'a' * 100), ('bad\x00name.pdf', 'badname')):
            with self.subTest(filename=filename):
                self.assertEqual(core.default_name(filename), name)


class ConfigTests(unittest.TestCase):
    def test_conversion_is_said_ahead_of_time(self):
        def services(**kwargs):
            return build_config({}, server_version='1', lidar_builds=False, **kwargs)['services']
        self.assertTrue(services(document_conversion=True)['documentConversion'])
        self.assertFalse(services(document_conversion=False)['documentConversion'])
        self.assertFalse(services()['documentConversion'])


class GotenbergVersionTests(unittest.TestCase):
    """Office files are converted only by a Gotenberg of 8.34.0 or later (its LibreOffice fetched linked content before that), as it
    says itself at GET /version. The brief prints through older ones, so GOTENBERG_URL alone is not enough."""

    def setUp(self):
        forget_gotenberg_version()
        self.addCleanup(forget_gotenberg_version)
        environ = patch.dict(os.environ, {'GOTENBERG_URL': 'http://gotenberg.test/'})
        environ.start()
        self.addCleanup(environ.stop)
        self.asked = []
        self.answer = FakeResponse(200, text='8.34.0')

        def get(url, timeout=None):
            self.asked.append(url)
            if isinstance(self.answer, Exception):
                raise self.answer
            return self.answer

        getter = patch('brief.gotenberg.requests.get', side_effect=get)
        getter.start()
        self.addCleanup(getter.stop)

    def test_what_it_says(self):
        for answer, version, converts in (
            (FakeResponse(200, text='8.34.0'), (8, 34, 0), True),
            (FakeResponse(200, text='8.35.2\n'), (8, 35, 2), True),
            (FakeResponse(200, text='9.0.0-beta'), (9, 0, 0), True),
            (FakeResponse(200, text='8.21.1'), (8, 21, 1), False),
            (FakeResponse(200, text='8.33.9'), (8, 33, 9), False),
            (FakeResponse(404, text='Not Found'), None, False),
            (FakeResponse(200, text='Gotenberg'), None, False),
        ):
            with self.subTest(text=answer.text, status=answer.status_code):
                forget_gotenberg_version()
                self.answer = answer
                self.assertEqual(gotenberg.version(), version)
                forget_gotenberg_version()
                self.assertEqual(gotenberg.converts_office(), converts)
        self.assertEqual(self.asked[-1], 'http://gotenberg.test/version')

    def test_kept_a_while_and_asked_again_sooner_after_no_answer(self):
        self.assertTrue(gotenberg.converts_office(now=1000.0))
        self.assertTrue(gotenberg.converts_office(now=1000.0 + gotenberg.VERSION_KEPT_S - 1))
        self.assertEqual(len(self.asked), 1)
        self.answer = __import__('requests').ConnectionError('refused')
        self.assertFalse(gotenberg.converts_office(now=1000.0 + gotenberg.VERSION_KEPT_S))
        self.assertFalse(gotenberg.converts_office(now=1000.0 + gotenberg.VERSION_KEPT_S + gotenberg.VERSION_RETRY_S - 1))
        self.assertEqual(len(self.asked), 2)
        self.answer = FakeResponse(200, text='8.34.0')
        self.assertTrue(gotenberg.converts_office(now=1000.0 + gotenberg.VERSION_KEPT_S + gotenberg.VERSION_RETRY_S))
        self.assertEqual(len(self.asked), 3)

    def test_without_gotenberg_nothing_is_asked(self):
        with patch.dict(os.environ, {'GOTENBERG_URL': ''}):
            self.assertIsNone(gotenberg.version())
            self.assertFalse(gotenberg.converts_office())
        self.assertEqual(self.asked, [])


# -- The Library -------------------------------------------------------------------------------------------------------------------

class LibraryCase(DocumentStore, NativeAuthCase):
    extra_blueprints = (documents_bp, sync_bp)

    def setUp(self):
        super().setUp()
        self.use_a_store()
        self.make_account()
        self.head = {'Authorization': f'Bearer {self.login()["access_token"]}'}
        self.turn(documents=True)

    def turn(self, **features):
        with self.app.app_context():
            user = User.query.filter_by(email='pilot@example.com').one()
            user.features = features
            db.session.commit()

    def conforms(self, response, method, template):
        problems = check_response(SPEC, template, method, response.status_code, response.get_json(silent=True))
        self.assertEqual(problems, [], f'{method} {template} {response.status_code}')
        return response

    def upload(self, data=OPORD, filename='OPORD 24-03.pdf', status=201, **fields):
        response = self.client.post('/api/documents', headers=self.head, content_type='multipart/form-data',
                                    data={'file': (__import__('io').BytesIO(data), filename), **fields})
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        self.conforms(response, 'POST', '/api/documents')
        return response.get_json()

    def here(self, saved, tail=''):
        return f"/api/documents/{saved['id']}{tail}"

    def feed(self, since=0):
        response = self.client.get(f'/api/sync/changes?since={since}', headers=self.head)
        self.conforms(response, 'GET', '/api/sync/changes')
        return response.get_json()


class LibraryTests(LibraryCase):
    def test_off_until_it_launches(self):
        self.assertIn('documents', __import__('entitlements').DEFAULT_OFF)
        saved = self.upload()
        self.turn()
        for method, url in (('get', '/api/documents'), ('post', '/api/documents'), ('get', self.here(saved)),
                            ('put', self.here(saved)), ('delete', self.here(saved)), ('get', self.here(saved, '/pdf')),
                            ('get', self.here(saved, '/text')), ('post', self.here(saved, '/version'))):
            response = getattr(self.client, method)(url, headers=self.head)
            self.assertEqual(response.status_code, 403, f'{method} {url}')
            self.assertEqual(response.get_json()['code'], 'feature_disabled')

    def test_cloud_save_off_refuses_writes_and_leaves_reading(self):
        saved = self.upload()
        self.turn(documents=True, cloud_save=False)
        self.assertEqual(self.client.get(self.here(saved, '/pdf'), headers=self.head).status_code, 200)
        self.assertEqual(self.client.put(self.here(saved), headers=self.head, json={'name': 'X'}).status_code, 403)

    def test_a_pdf_is_uploaded_listed_and_read(self):
        saved = self.upload()
        self.assertEqual((saved['name'], saved['pages'], saved['bytes'], saved['source_type'], saved['has_text']),
                         ('OPORD 24-03', 3, len(OPORD), 'pdf', True))
        listed = self.conforms(self.client.get('/api/documents', headers=self.head), 'GET', '/api/documents').get_json()
        self.assertEqual([d['id'] for d in listed], [saved['id']])
        full = self.conforms(self.client.get(self.here(saved), headers=self.head), 'GET', '/api/documents/{id}').get_json()
        self.assertEqual(full['data']['pdf']['sha256'], sha(OPORD))

        pdf = self.client.get(self.here(saved, '/pdf'), headers=self.head)
        self.assertEqual((pdf.status_code, pdf.data, pdf.mimetype), (200, OPORD, 'application/pdf'))
        self.assertIn('private', pdf.headers['Cache-Control'])
        self.assertIn('no-cache', pdf.headers['Cache-Control'])
        self.assertNotIn('public', pdf.headers['Cache-Control'])
        self.assertEqual(pdf.headers['X-Content-Type-Options'], 'nosniff')
        self.assertEqual(pdf.headers['Content-Security-Policy'], 'sandbox')
        self.assertTrue(pdf.headers['Content-Disposition'].startswith('inline'))
        download = self.client.get(self.here(saved, '/pdf?download=1'), headers=self.head)
        self.assertTrue(download.headers['Content-Disposition'].startswith('attachment'))

        text = self.client.get(self.here(saved, '/text'), headers=self.head)
        self.assertEqual(text.get_json()['pages'][1], 'Annex C: LZ HAWK at 16S GC 31204 58811')

    def test_a_part_of_the_pdf_and_a_copy_already_held(self):
        saved = self.upload()
        part = self.client.get(self.here(saved, '/pdf'), headers={**self.head, 'Range': 'bytes=0-99'})
        self.assertEqual((part.status_code, part.data), (206, OPORD[:100]))
        first = self.client.get(self.here(saved, '/pdf'), headers=self.head)
        again = self.client.get(self.here(saved, '/pdf'), headers={**self.head, 'If-None-Match': first.headers['ETag']})
        self.assertEqual(again.status_code, 304)

    def test_an_office_file_is_kept_as_its_pdf_only(self):
        saved = self.upload(ooxml('docx'), 'OPORD 24-03.docx')
        self.assertEqual(saved['source_type'], 'docx')
        self.assertEqual(self.client.get(self.here(saved, '/pdf'), headers=self.head).data, DOCX_AS_PDF)
        self.assertNotIn(sha(ooxml('docx')), self.stored())     # the original is not kept

    def test_a_scan_has_no_text(self):
        saved = self.upload(SCAN, 'scan.pdf')
        self.assertFalse(saved['has_text'])
        response = self.client.get(self.here(saved, '/text'), headers=self.head)
        self.assertEqual((response.status_code, response.get_json()['code']), (404, 'no_text'))

    def test_a_name_given(self):
        self.assertEqual(self.upload(name='ANNEX C')['name'], 'ANNEX C')
        for bad in ('', '   ', 'x' * 101):
            with self.subTest(name=bad):
                self.assertEqual(self.upload(name=bad, status=400)['code'], 'invalid_name')

    def test_refusals(self):
        self.assertEqual(self.upload(b'plain text', 'notes.pdf', status=415)['code'], 'unsupported_type')
        self.assertEqual(self.upload(encrypted(OPORD, 'pw'), 'locked.pdf', status=422)['code'], 'password_protected')
        response = self.client.post('/api/documents', headers=self.head, content_type='multipart/form-data', data={'name': 'X'})
        self.assertEqual((response.status_code, response.get_json()['code']), (400, 'missing_file'))
        self.assertEqual(self.stored(), [])

    def test_a_body_too_large_is_refused_before_it_is_read(self):
        with patch('routes.document_routes._MAX_BODY', 1000):
            self.assertEqual(self.upload(status=413)['code'], 'file_too_large')

    def test_the_library_limits(self):
        self.upload()
        with patch.object(core, 'LIBRARY_MAX_DOCUMENTS', 1):
            self.assertEqual(self.upload(CHANGE_1, status=413)['code'], 'library_full')
        with patch.object(core, 'LIBRARY_MAX_BYTES', len(OPORD) + 10):
            self.assertEqual(self.upload(CHANGE_1, status=413)['code'], 'library_full')

    def test_an_upload_asked_again_is_the_first(self):
        client_uuid = str(uuid.uuid4())
        first = self.upload(client_uuid=client_uuid)
        again = self.upload(CHANGE_1, status=200, client_uuid=client_uuid)
        self.assertEqual((again['id'], again['pages']), (first['id'], 3))
        self.assertEqual(self.upload(client_uuid='nope', status=400)['code'], 'invalid_client_uuid')

    def test_a_rename_with_if_match(self):
        saved = self.upload()
        renamed = self.client.put(self.here(saved), headers={**self.head, 'If-Match': f'"{saved["revision"]}"'},
                                  json={'name': 'OPORD 24-03 (DK)'})
        self.conforms(renamed, 'PUT', '/api/documents/{id}')
        self.assertEqual(renamed.get_json()['name'], 'OPORD 24-03 (DK)')
        stale = self.client.put(self.here(saved), headers={**self.head, 'If-Match': f'"{saved["revision"]}"'}, json={'name': 'X'})
        self.conforms(stale, 'PUT', '/api/documents/{id}')
        self.assertEqual((stale.status_code, stale.get_json()['server']['name']), (409, 'OPORD 24-03 (DK)'))
        self.assertEqual(self.client.put(self.here(saved), headers=self.head, json={}).get_json()['code'], 'missing_fields')

    def test_a_new_version_replaces_the_files(self):
        saved = self.upload()
        old_text = self.client.get(self.here(saved), headers=self.head).get_json()['data']['text']['sha256']
        response = self.client.post(self.here(saved, '/version'), content_type='multipart/form-data',
                                    headers={**self.head, 'If-Match': f'"{saved["revision"]}"'},
                                    data={'file': (__import__('io').BytesIO(CHANGE_1), 'OPORD 24-03 CHANGE 1.pdf')})
        self.conforms(response, 'POST', '/api/documents/{id}/version')
        body = response.get_json()
        self.assertEqual((body['id'], body['name'], body['pages'], body['revision']), (saved['id'], 'OPORD 24-03', 2, saved['revision'] + 1))
        self.assertEqual(self.client.get(self.here(saved, '/pdf'), headers=self.head).data, CHANGE_1)
        self.assertNotIn(sha(OPORD), self.stored())                 # the old version's files went
        self.assertNotIn(old_text, self.stored())

    def test_a_deletion_is_a_tombstone_and_its_files_go(self):
        saved = self.upload()
        self.assertEqual(len(self.stored()), 2)
        response = self.client.delete(self.here(saved), headers=self.head)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.stored(), [])
        self.assertEqual(self.client.get(self.here(saved, '/pdf'), headers=self.head).status_code, 404)
        self.assertEqual(self.client.delete(self.here(saved), headers=self.head).get_json(), {'status': 'deleted'})
        with self.app.app_context():
            row = db.session.get(SavedDocument, saved['id'])
            self.assertEqual((row.doc_data, row.name, row.pdf_sha256), ({}, '', None))

    def test_the_same_file_twice_is_one_file_kept_while_either_names_it(self):
        one = self.upload()
        two = self.upload(filename='copy.pdf')
        self.assertEqual(len(self.stored()), 2)
        self.client.delete(self.here(one), headers=self.head)
        self.assertEqual(len(self.stored()), 2)
        self.client.delete(self.here(two), headers=self.head)
        self.assertEqual(self.stored(), [])

    def test_the_change_feed_carries_documents(self):
        saved = self.upload()
        change = [c for c in self.feed()['changes'] if c['type'] == 'document'][0]
        self.assertEqual((change['client_uuid'], change['name'], change['data']['pdf']['pages']), (saved['client_uuid'], 'OPORD 24-03', 3))
        cursor = self.feed()['cursor']
        self.client.delete(self.here(saved), headers=self.head)
        gone = self.feed(cursor)['changes'][0]
        self.assertEqual((gone['type'], gone['deleted'], gone['data']), ('document', True, {}))

    def test_another_persons_document_is_not_found(self):
        saved = self.upload()
        self.make_account('other@example.com')
        other = {'Authorization': f'Bearer {self.login(email="other@example.com")["access_token"]}'}
        with self.app.app_context():
            User.query.filter_by(email='other@example.com').one().features = {'documents': True}
            db.session.commit()
        for tail in ('', '/pdf', '/text'):
            self.assertEqual(self.client.get(self.here(saved, tail), headers=other).status_code, 404)


class SweepTests(LibraryCase):
    def test_only_files_nothing_names_and_old_enough_go(self):
        saved = self.upload()
        stray = 'f' * 64
        folder = os.path.join(self.store_dir, 'ff')
        os.makedirs(folder, exist_ok=True)
        for name in (stray, '.incoming-x'):
            with open(os.path.join(folder, name), 'wb') as out:
                out.write(b'left by a crash')
        with self.app.app_context():
            self.assertEqual(files.sweep(), 0)                      # all of it younger than an hour
            self.assertEqual(files.sweep(now=time.time() + 7200), 2)
        self.assertEqual(sorted(self.stored()), sorted([sha(OPORD), self.client.get(self.here(saved), headers=self.head)
                                                        .get_json()['data']['text']['sha256']]))


class AccountDeletionTests(LibraryCase):
    def test_deleting_the_account_lets_its_files_go(self):
        self.upload()
        response = self.client.delete('/api/auth/me', headers=self.head, json={'password': PASSWORD, 'confirm': 'DELETE'})
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        self.assertEqual(self.stored(), [])
        with self.app.app_context():
            self.assertEqual(SavedDocument.query.count(), 0)


# -- Packs -------------------------------------------------------------------------------------------------------------------------

class PackDocumentCase(DocumentStore, PackCase):
    extra_blueprints = PackCase.extra_blueprints + (documents_bp,)

    def setUp(self):
        super().setUp()
        self.use_a_store()
        for who in (self.colin, self.sam, self.alex):
            with self.app.app_context():
                db.session.get(User, who['id']).features = {'mission_packs': True, 'documents': True}
                db.session.commit()
        self.uuid = self.new_pack()['uuid']
        self.member(self.uuid, self.sam)
        self.member(self.uuid, self.alex, role='viewer')

    def upload_to_pack(self, who=None, data=OPORD, filename='OPORD 24-03.pdf', **fields):
        return self.call(who or self.colin, 'post', f'/api/packs/{self.uuid}/documents', content_type='multipart/form-data',
                         data={'file': (__import__('io').BytesIO(data), filename), **fields})

    def conforms(self, response, method, template, status):
        body = self.ok(response, status)
        self.assertEqual(check_response(SPEC, template, method, status, body), [])
        return body


class PackDocumentTests(PackDocumentCase):
    def test_a_document_is_uploaded_into_the_pack(self):
        body = self.conforms(self.upload_to_pack(item='doc-1'), 'post', '/api/packs/{uuid}/documents', 201)
        item = body['item']
        self.assertEqual((item['uuid'], item['kind'], item['name'], item['data']['pdf']['pages']), ('doc-1', 'document', 'OPORD 24-03', 3))
        self.assertEqual(body['event']['summary'], 'Colin added the document "OPORD 24-03".')
        self.assertEqual(body['event']['op']['type'], 'item.create')
        self.assertEqual(self.pack(self.uuid)['item_counts']['document'], 1)

    def test_an_upload_asked_again_is_the_same_item(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        head = self.pack(self.uuid)['head_seq']
        again = self.ok(self.upload_to_pack(item='doc-1', data=CHANGE_1), 200)
        self.assertEqual((again['event'], again['item']['data']['pdf']['pages']), (None, 3))
        self.assertEqual(self.pack(self.uuid)['head_seq'], head)
        self.assertEqual(self.ok(self.upload_to_pack(who=self.sam, item='doc-1'), 409)['code'], 'item_exists')

    def test_every_member_reads_it_in_every_state_of_the_pack(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/finish'))
        for who in (self.colin, self.sam, self.alex):
            pdf = self.call(who, 'get', f'/api/packs/{self.uuid}/items/doc-1/pdf')
            self.assertEqual((pdf.status_code, pdf.data), (200, OPORD))
            self.assertEqual(self.call(who, 'get', f'/api/packs/{self.uuid}/items/doc-1/text').get_json()['pages'][2],
                             'Annex D: PZ CROW')

    def test_reading_needs_membership_not_the_feature(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        with self.app.app_context():
            db.session.get(User, self.alex['id']).features = {'mission_packs': True}
            db.session.commit()
        self.assertEqual(self.call(self.alex, 'get', f'/api/packs/{self.uuid}/items/doc-1/pdf').status_code, 200)
        outsider = self.person('dana@example.com', 'Dana')
        self.assertEqual(self.call(outsider, 'get', f'/api/packs/{self.uuid}/items/doc-1/pdf').status_code, 404)

    def test_a_viewer_and_a_finished_pack_add_nothing(self):
        self.assertEqual(self.ok(self.upload_to_pack(who=self.alex), 403)['code'], 'pack_read_only')
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/finish'))
        self.assertEqual(self.upload_to_pack().status_code, 423)
        self.assertEqual(self.stored(), [])

    def test_uploading_needs_the_feature(self):
        with self.app.app_context():
            db.session.get(User, self.sam['id']).features = {'mission_packs': True}
            db.session.commit()
        self.assertEqual(self.ok(self.upload_to_pack(who=self.sam), 403)['code'], 'feature_disabled')

    def test_a_client_renames_and_removes_a_document_and_nothing_else(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        create = self.op('item.create', 'doc-2', kind='document', name='MINE', data={'version': 1, 'pdf': {'sha256': 'a' * 64}})
        refused = self.ok(self.send(self.uuid, create), 400)
        self.assertEqual((refused['code'], refused['reason']), ('invalid_op', 'bad_kind'))
        for op in (self.op('set', 'doc-1', path=['pdf', 'sha256'], value='b' * 64),
                   self.op('patch', 'doc-1', path=[], value={'text': None})):
            with self.subTest(op=op['type']):
                refused = self.ok(self.send(self.uuid, op), 400)
                self.assertEqual((refused['code'], refused['reason'], refused['index']), ('invalid_op', 'bad_kind', 0))
        self.ok(self.send(self.uuid, self.op('item.rename', 'doc-1', name='OPORD 24-03 (DK)')))
        self.assertEqual(self.item(self.pack(self.uuid), 'doc-1')['name'], 'OPORD 24-03 (DK)')
        self.assertEqual(len(self.stored()), 2)
        self.ok(self.send(self.uuid, self.op('item.delete', 'doc-1'), who=self.sam))
        self.assertEqual(self.stored(), [])

    def test_a_new_version_in_the_pack(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        url = f'/api/packs/{self.uuid}/items/doc-1/version'
        body = self.conforms(self.call(self.sam, 'post', url, content_type='multipart/form-data',
                                       data={'file': (__import__('io').BytesIO(CHANGE_1), 'change1.pdf')}),
                             'post', '/api/packs/{uuid}/items/{item}/version', 200)
        self.assertEqual((body['item']['name'], body['item']['data']['pdf']['pages']), ('OPORD 24-03', 2))
        self.assertEqual(body['event']['op']['type'], 'item.replace')
        self.assertEqual(body['event']['summary'], 'Sam replaced "OPORD 24-03" with a new version.')
        self.assertNotIn(sha(OPORD), self.stored())
        again = self.ok(self.call(self.sam, 'post', url, content_type='multipart/form-data',
                                  data={'file': (__import__('io').BytesIO(CHANGE_1), 'change1.pdf')}))
        self.assertIsNone(again['event'])                               # the same file again changes nothing
        self.assertEqual(self.ok(self.call(self.alex, 'post', url, content_type='multipart/form-data',
                                           data={'file': (__import__('io').BytesIO(OPORD), 'x.pdf')}), 403)['code'], 'pack_read_only')

    def test_copied_in_from_the_library_and_saved_back_with_the_same_files(self):
        mine = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                 data={'file': (__import__('io').BytesIO(OPORD), 'OPORD 24-03.pdf')}), 201)
        copied = self.conforms(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                                         json={'item': 'doc-1', 'source': {'kind': 'document', 'id': mine['id']}}),
                               'post', '/api/packs/{uuid}/items', 201)
        self.assertEqual((copied['item']['kind'], copied['item']['source']['original']), ('document', 'same'))
        self.assertEqual(len(self.stored()), 2)                         # no bytes copied

        self.ok(self.call(self.colin, 'delete', f'/api/documents/{mine["id"]}'))
        self.assertEqual(len(self.stored()), 2)                         # the pack still names them

        saved = self.ok(self.call(self.sam, 'post', f'/api/packs/{self.uuid}/items/doc-1/library', json={}), 201)
        self.assertEqual(saved['kind'], 'document')
        self.ok(self.send(self.uuid, self.op('item.delete', 'doc-1')))
        self.assertEqual(len(self.stored()), 2)                         # Sam's Library still names them
        self.ok(self.call(self.sam, 'delete', f'/api/documents/{saved["id"]}'))
        self.assertEqual(self.stored(), [])

    def test_an_update_from_the_original_takes_its_new_version(self):
        mine = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                 data={'file': (__import__('io').BytesIO(OPORD), 'OPORD 24-03.pdf')}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                          json={'item': 'doc-1', 'source': {'kind': 'document', 'id': mine['id']}}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/documents/{mine["id"]}/version', content_type='multipart/form-data',
                          data={'file': (__import__('io').BytesIO(CHANGE_1), 'c1.pdf')}))
        self.assertEqual(self.item(self.pack(self.uuid), 'doc-1')['source']['original'], 'changed')
        self.assertIn(sha(OPORD), self.stored())                        # the pack's copy still names the first version
        body = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/doc-1/update-from-original'))
        self.assertEqual(body['item']['data']['pdf']['sha256'], sha(CHANGE_1))
        self.assertNotIn(sha(OPORD), self.stored())

    def test_a_copy_in_needs_the_feature(self):
        mine = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                 data={'file': (__import__('io').BytesIO(OPORD), 'x.pdf')}), 201)
        with self.app.app_context():
            db.session.get(User, self.colin['id']).features = {'mission_packs': True}
            db.session.commit()
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                             json={'source': {'kind': 'document', 'id': mine['id']}})
        self.assertEqual(self.ok(response, 403)['code'], 'feature_disabled')

    def test_the_pack_limits(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        with patch.object(core, 'PACK_MAX_DOCUMENTS', 1):
            self.assertEqual(self.ok(self.upload_to_pack(item='doc-2', data=CHANGE_1), 413)['code'], 'pack_full')
            # A new version replaces, so it fits where one more would not.
            self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/doc-1/version', content_type='multipart/form-data',
                              data={'file': (__import__('io').BytesIO(CHANGE_1), 'c1.pdf')}))
        with patch.object(core, 'PACK_MAX_BYTES', len(CHANGE_1) + 10):
            self.assertEqual(self.ok(self.upload_to_pack(item='doc-3', data=OPORD), 413)['code'], 'pack_full')

    def test_deleting_the_pack_lets_its_files_go(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}'))
        self.assertEqual(self.stored(), [])

    def test_a_duplicated_pack_names_the_same_files(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        copy_uuid = self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/duplicate', json={}), 201)['uuid']
        self.ok(self.call(self.colin, 'delete', f'/api/packs/{self.uuid}'))
        self.assertEqual(len(self.stored()), 2)
        self.assertEqual(self.call(self.colin, 'get', f'/api/packs/{copy_uuid}/items/doc-1/pdf').data, OPORD)

    def test_a_deleted_accounts_pack_documents_go_with_the_pack(self):
        lone = self.new_pack(who=self.sam, name='SAM ALONE')['uuid']
        self.ok(self.call(self.sam, 'post', f'/api/packs/{lone}/documents', content_type='multipart/form-data',
                          data={'file': (__import__('io').BytesIO(CHANGE_1), 'c1.pdf'), 'item': 'doc-9'}), 201)
        self.ok(self.call(self.sam, 'delete', '/api/auth/me', json={'password': PASSWORD, 'confirm': 'DELETE'}))
        self.assertNotIn(sha(CHANGE_1), self.stored())
        with self.app.app_context():
            self.assertEqual(MissionPackItem.query.filter_by(uuid='doc-9').count(), 0)

    def test_an_item_that_is_not_a_document_has_no_files(self):
        self.add_lz(self.uuid)
        self.assertEqual(self.call(self.colin, 'get', f'/api/packs/{self.uuid}/items/lz-1/pdf').status_code, 404)
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/lz-1/version', content_type='multipart/form-data',
                             data={'file': (__import__('io').BytesIO(OPORD), 'x.pdf')})
        self.assertEqual(self.ok(response, 400)['code'], 'not_a_document')



# -- Requests side by side (the review of D1) -------------------------------------------------------------------------------------
#
# Each test runs a competing request in another thread at the moment the first is between reading what it copies and committing,
# as two of the API's eight threads would. A database file, so each thread has a connection of its own. Postgres's row locks are not
# tried here: SQLite takes the whole database for a write, so only the interleavings in which the first request has not yet written
# can be made. The rest hold by the same rule (read, and commit, under the files lock; read again once the slow work is done).

THIRD = pdf_with('OPORD 24-03 CHANGE 2', 'Annex C: LZ FALCON is now LZ OWL')


class FileDatabase:
    def setUp(self):
        handle, self.database_file = tempfile.mkstemp(suffix='.db')
        os.close(handle)
        self.addCleanup(os.unlink, self.database_file)
        self.database_uri = 'sqlite:///' + self.database_file
        super().setUp()


def beside(request):
    """Runs ``request`` in another thread and waits for it: ``(answer, None)``."""
    box = {}
    thread = threading.Thread(target=lambda: box.setdefault('answer', request()))
    thread.start()
    thread.join(20)
    assert not thread.is_alive(), 'the request beside never finished'
    return box['answer']


class UntilRelease:
    """Runs a request in another thread until its write is committed and it reaches ``files.release``, which waits for the lock the
    first request holds; ``finish()`` waits for the rest of it once the first has let the lock go."""

    def __init__(self, test, request):
        self.reached = threading.Event()
        real = files.release

        def release(digests):
            self.reached.set()
            return real(digests)

        patcher = patch('documents.files.release', side_effect=release)
        patcher.start()
        test.addCleanup(patcher.stop)
        self.box = {}
        self.thread = threading.Thread(target=lambda: self.box.setdefault('answer', request()))
        self.thread.start()
        # It reaches its release, or ends without one (a refusal), which the test then sees in its answer.
        deadline = time.monotonic() + 20
        while not self.reached.wait(0.02) and self.thread.is_alive():
            assert time.monotonic() < deadline, 'the request beside never reached its release'

    def finish(self):
        self.thread.join(20)
        assert not self.thread.is_alive(), 'the request beside never finished'
        return self.box['answer']


class LibrarySideBySideTests(FileDatabase, LibraryCase):
    def new_version(self, saved, data=CHANGE_1, headers=None):
        return self.client.post(self.here(saved, '/version'), headers={**self.head, **(headers or {})},
                                content_type='multipart/form-data', data={'file': (io.BytesIO(data), 'c1.pdf')})

    def during_the_conversion(self, request):
        real = core.prepare
        answers = []

        def prepare(data, filename=None):
            prepared = real(data, filename)
            answers.append(beside(request))
            return prepared

        return patch('routes.document_routes.core.prepare', side_effect=prepare), answers

    def test_a_document_deleted_while_its_new_version_converts_stays_deleted(self):
        saved = self.upload()
        converting, beside_answers = self.during_the_conversion(lambda: self.client.delete(self.here(saved), headers=self.head))
        with converting:
            response = self.new_version(saved)
        self.assertEqual(beside_answers[0].status_code, 200)
        self.assertEqual((response.status_code, response.get_json()['code']), (404, 'not_found'))
        with self.app.app_context():
            row = db.session.get(SavedDocument, saved['id'])
            self.assertEqual((row.doc_data, row.pdf_sha256, row.name, row.revision), ({}, None, '', 2))
        self.assertEqual(self.stored(), [])
        self.assertEqual([(c['deleted'], c['revision'], c['data']) for c in self.feed()['changes']], [(True, 2, {})])

    def test_a_document_renamed_while_its_new_version_converts(self):
        saved = self.upload()
        rename = lambda: self.client.put(self.here(saved), headers=self.head, json={'name': 'RENAMED'})  # noqa: E731
        converting, beside_answers = self.during_the_conversion(rename)
        with converting:
            response = self.new_version(saved, headers={'If-Match': f'"{saved["revision"]}"'})
        self.assertEqual(beside_answers[0].get_json()['revision'], 2)
        # Sent for revision 1, which the rename moved on from: the server's copy, nothing overwritten.
        self.assertEqual((response.status_code, response.get_json()['code']), (409, 'revision_conflict'))
        self.assertEqual(response.get_json()['server']['name'], 'RENAMED')
        self.assertEqual(sorted(self.stored()), sorted(core.hashes_of(self.client.get(self.here(saved), headers=self.head).get_json()['data'])))
        # Without If-Match the new version goes on top of the rename, a revision of its own.
        converting, beside_answers = self.during_the_conversion(
            lambda: self.client.put(self.here(saved), headers=self.head, json={'name': 'RENAMED AGAIN'}))
        with converting:
            response = self.new_version(saved)
        body = self.ok_json(response)
        self.assertEqual((body['name'], body['revision'], beside_answers[0].get_json()['revision']), ('RENAMED AGAIN', 4, 3))

    def ok_json(self, response, status=200):
        self.assertEqual(response.status_code, status, response.get_data(as_text=True))
        return response.get_json()


class LibraryEdgeTests(LibraryCase):
    def test_an_upload_asked_again_for_a_document_deleted_since(self):
        client_uuid = str(uuid.uuid4())
        saved = self.upload(client_uuid=client_uuid)
        self.assertEqual(self.client.delete(self.here(saved), headers=self.head).status_code, 200)
        response = self.client.post('/api/documents', headers=self.head, content_type='multipart/form-data',
                                    data={'file': (io.BytesIO(OPORD), 'x.pdf'), 'client_uuid': client_uuid})
        self.assertEqual((response.status_code, response.get_json()['code']), (410, 'document_deleted'))
        self.conforms(response, 'POST', '/api/documents')
        self.assertEqual(self.stored(), [])

    def test_a_name_that_is_not_text_is_refused(self):
        saved = self.upload()
        for name in (None, 7, ['OPORD'], {'name': 'x'}, '', '   ', 'x' * 101):
            with self.subTest(name=name):
                response = self.client.put(self.here(saved), headers=self.head, json={'name': name})
                self.assertEqual((response.status_code, response.get_json()['code']), (400, 'invalid_name'))
        self.assertEqual(self.client.get(self.here(saved), headers=self.head).get_json()['name'], 'OPORD 24-03')

    def test_uploads_side_by_side_still_keep_to_the_librarys_limits(self):
        self.upload()
        real = core.prepare

        def prepare(data, filename=None):
            prepared = real(data, filename)
            # Another upload passed its check too, and lands first, while this one converts.
            if not getattr(prepare, 'done', False):
                prepare.done = True
                self.upload(data=CHANGE_1, filename='c1.pdf')
            return prepared

        with patch.object(core, 'LIBRARY_MAX_DOCUMENTS', 2), patch('routes.document_routes.core.prepare', side_effect=prepare):
            response = self.client.post('/api/documents', headers=self.head, content_type='multipart/form-data',
                                        data={'file': (io.BytesIO(THIRD), 'c2.pdf')})
        self.assertEqual((response.status_code, response.get_json()['code']), (413, 'library_full'))
        self.assertEqual(len(self.client.get('/api/documents', headers=self.head).get_json()), 2)
        self.assertNotIn(sha(THIRD), self.stored())


class PackSideBySideTests(FileDatabase, PackDocumentCase):
    def test_a_library_document_deleted_while_it_is_copied_in(self):
        record = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                   data={'file': (io.BytesIO(OPORD), 'opord.pdf')}), 201)
        import routes.pack_routes as pack_routes
        real = pack_routes._lock
        racing = []

        def lock(pack, user_id, need=None, touch=True):
            # The record is read; it is deleted now, and the delete waits for the files lock before it lets the files go.
            racing.append(UntilRelease(self, lambda: self.call(self.colin, 'delete', f"/api/documents/{record['id']}")))
            return real(pack, user_id, need, touch)

        with patch('routes.pack_routes._lock', side_effect=lock):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                                 json={'source': {'kind': 'document', 'id': record['id']}, 'item': 'copy-1'})
        self.ok(response, 201)
        self.assertEqual(racing[0].finish().status_code, 200)
        pdf = self.call(self.sam, 'get', f'/api/packs/{self.uuid}/items/copy-1/pdf')
        self.assertEqual((pdf.status_code, pdf.data), (200, OPORD))

    def test_a_pack_document_removed_while_it_is_saved_to_a_library(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        import routes.pack_routes as pack_routes
        real = pack_routes._document_record_refusal
        racing = []

        def refusal(user_id, data):
            remove = self.op('item.delete', 'doc-1')
            racing.append(UntilRelease(self, lambda: self.send(self.uuid, remove, who=self.sam)))
            return real(user_id, data)

        with patch('routes.pack_routes._document_record_refusal', side_effect=refusal):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/doc-1/library', json={})
        saved = self.ok(response, 201)
        self.ok(racing[0].finish())
        pdf = self.call(self.colin, 'get', f"/api/documents/{saved['id']}/pdf")
        self.assertEqual((pdf.status_code, pdf.data), (200, OPORD))

    def test_two_new_versions_at_once(self):
        self.ok(self.upload_to_pack(item='doc-1'), 201)
        import routes.pack_routes as pack_routes
        real = pack_routes.document_core.prepare
        inner = []

        def prepare(data, filename=None):
            prepared = real(data, filename)
            if not inner:
                inner.append(None)                       # the one beside converts through here too, and must not start another
                inner[0] = beside(lambda: self.call(self.sam, 'post', f'/api/packs/{self.uuid}/items/doc-1/version',
                                                    content_type='multipart/form-data',
                                                    data={'file': (io.BytesIO(CHANGE_1), 'c1.pdf')}))
            return prepared

        with patch('routes.pack_routes.document_core.prepare', side_effect=prepare):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/doc-1/version',
                                 content_type='multipart/form-data', data={'file': (io.BytesIO(THIRD), 'c2.pdf')})
        outer = self.ok(response)
        self.assertEqual((self.ok(inner[0])['item']['revision'], outer['item']['revision']), (2, 3))
        # The one written last is the item, and nothing else is kept: the first version's files and the second's both went.
        self.assertEqual(outer['item']['data']['pdf']['sha256'], sha(THIRD))
        self.assertEqual(sorted(self.stored()), sorted(core.hashes_of(outer['item']['data'])))
        self.assertIn('replaced "OPORD 24-03"', outer['event']['summary'])

    def test_an_update_from_the_original_keeps_to_the_packs_room(self):
        record = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                   data={'file': (io.BytesIO(OPORD), 'opord.pdf')}), 201)
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                          json={'source': {'kind': 'document', 'id': record['id']}, 'item': 'copy-1'}), 201)
        self.ok(self.call(self.colin, 'post', f"/api/documents/{record['id']}/version", content_type='multipart/form-data',
                          data={'file': (io.BytesIO(THIRD), 'c2.pdf')}))
        with patch.object(document_core_module(), 'PACK_MAX_BYTES', 100):
            response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/copy-1/update-from-original', json={})
        self.assertEqual(self.ok(response, 413)['code'], 'pack_full')
        self.ok(self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items/copy-1/update-from-original', json={}))

    def test_a_copy_of_a_document_whose_file_is_gone_is_refused(self):
        record = self.ok(self.call(self.colin, 'post', '/api/documents', content_type='multipart/form-data',
                                   data={'file': (io.BytesIO(OPORD), 'opord.pdf')}), 201)
        os.unlink(files.path_of(sha(OPORD)))
        response = self.call(self.colin, 'post', f'/api/packs/{self.uuid}/items',
                             json={'source': {'kind': 'document', 'id': record['id']}, 'item': 'copy-1'})
        self.assertEqual(self.ok(response, 409)['code'], 'file_missing')


def document_core_module():
    return core


if __name__ == '__main__':
    unittest.main()
