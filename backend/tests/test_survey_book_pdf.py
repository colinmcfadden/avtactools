"""The whole book's PDF (``/api/survey-books/<uuid>/print`` and ``…/pdf``; docs/LZ_SURVEYS.md §5, step L6): its cover with a map of
every LZ, its index, and every published page numbered in the book, printed in the background and kept until a page in it changes.
Gotenberg and the map's tiles are stood in for; the printer runs in the test's own thread."""

import os
import re
import sys
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from models import SurveyBookPrint, db  # noqa: E402
from survey import assets as asset_store  # noqa: E402
from survey import book as book_pdf  # noqa: E402
from survey import pages  # noqa: E402
from test_survey_books import BookCase, FakeAnswer, _tile  # noqa: E402


class BookPdfCase(BookCase):
    def setUp(self):
        super().setUp()
        self.book = self.team_book()
        self.posted = []
        self.answer = lambda: FakeAnswer(content=b'%PDF-1.7\n%book ' + str(len(self.posted)).encode())
        printer = book_pdf.Printer()
        printer.inline = True
        self.printer = printer
        for p in (patch('brief.figures._fetch_tile', _tile), patch.object(book_pdf, 'PRINTER', printer),
                  patch.dict(os.environ, {'GOTENBERG_URL': 'http://gotenberg:3000'}),
                  patch('brief.gotenberg.requests.post', self.gotenberg)):
            p.start()
            self.addCleanup(p.stop)

    def gotenberg(self, url, files=None, data=None, timeout=None):
        named = {part[1][0]: part[1][1] for part in files}
        html = named.pop('index.html').decode('utf-8')
        if 'class="sheet cover"' not in html:
            return FakeAnswer(content=b'%PDF-1.7\n%one page')                    # a page printed as it is published
        self.posted.append({'html': html, 'files': sorted(named)})
        return self.answer()

    def published(self, name='LZ DONOVAN', hlz_id='GA 034'):
        return self.publish(self.measured(self.survey_in(self.book, name=name, hlz_id=hlz_id)))['survey']

    def status(self, head=None):
        response = self.client.get(f'/api/survey-books/{self.book["uuid"]}/print', headers=head or self.admin)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        return response.get_json()

    def ask(self, head=None):
        return self.client.post(f'/api/survey-books/{self.book["uuid"]}/print', headers=head or self.admin)

    def printed(self, head=None):
        asked = self.ask(head)
        self.assertEqual(asked.status_code, 202, asked.get_data(as_text=True))
        self.assertEqual(asked.get_json()['state'], 'printing')
        self.printer.run_pending(self.app)
        return self.status(head)

    def pdf(self, head=None):
        return self.client.get(f'/api/survey-books/{self.book["uuid"]}/pdf', headers=head or self.admin)


class BookPdfTests(BookPdfCase):
    def test_a_book_with_nothing_published_has_nothing_to_print(self):
        self.survey_in(self.book)                                                 # a draft
        self.assertEqual(self.status()['state'], 'empty')
        refused = self.ask()
        self.assertEqual((refused.status_code, refused.get_json()['code']), (409, 'nothing_published'))
        self.assertEqual((self.pdf().status_code, self.pdf().get_json()['code']), (409, 'not_printed'))
        self.assertEqual(self.posted, [])

    def test_the_book_is_its_published_pages_in_order_numbered_in_the_book(self):
        self.published()
        self.survey_in(self.book, name='LZ DRAFT ONLY', hlz_id='GA 999')       # never in the book until published
        self.published(name='LZ BIG STAMP', hlz_id='NGA 030')
        self.assertEqual(self.status()['state'], 'none')
        done = self.printed()
        self.assertEqual((done['state'], done['pages'], done['error']), ('ready', 2, None))
        self.assertTrue(done['printed_at'].endswith('Z'))
        (sent,) = self.posted
        html = re.sub(r'\s+', ' ', sent['html'])
        self.assertIn('HLZ SURVEY BOOK', html)
        self.assertIn('B CO HLZ BOOK', html)                                      # the cover's name
        self.assertIn('2 LZ SURVEYS · PRINTED', html)
        self.assertIn('EZ/PZ · 1 OF 2', html)
        self.assertIn('EZ/PZ · 2 OF 2', html)
        self.assertNotIn('LZ DRAFT ONLY', html)
        self.assertNotIn('GA 999', html)
        self.assertLess(html.index('LZ DONOVAN'), html.index('LZ BIG STAMP'))       # the book's order
        self.assertEqual(html.count('UNCLASSIFIED'), 2 * 4)                        # the cover, the index and two pages each have two
        self.assertNotIn('DRAFT</div>', html)                                     # a published page carries no mark
        self.assertEqual(sent['files'][:1], ['overview.jpg'])
        self.assertIn('p001-plan.jpg', sent['files'])
        self.assertIn('p002-plan.jpg', sent['files'])
        pdf = self.pdf()
        self.assertEqual((pdf.status_code, pdf.mimetype, pdf.data), (200, 'application/pdf', b'%PDF-1.7\n%book 1'))
        self.assertEqual(pdf.headers['Cache-Control'], 'private, no-store')
        download = self.client.get(f'/api/survey-books/{self.book["uuid"]}/pdf?download=1', headers=self.admin)
        self.assertIn('attachment', download.headers['Content-Disposition'])
        self.assertIn('B CO HLZ BOOK', download.headers['Content-Disposition'])

    def test_asking_again_prints_nothing_new(self):
        self.published()
        self.printed()
        again = self.ask()
        self.assertEqual((again.status_code, again.get_json()['state']), (200, 'ready'))
        self.printer.run_pending(self.app)
        self.assertEqual(len(self.posted), 1)

    def test_a_reader_prints_and_downloads_the_book_and_an_outsider_does_not_see_it(self):
        self.published()
        self.assertEqual(self.printed(head=self.member)['state'], 'ready')
        self.assertEqual(self.pdf(head=self.member).status_code, 200)
        self.assertEqual(self.ask(head=self.outsider).status_code, 404)
        self.assertEqual(self.pdf(head=self.outsider).status_code, 404)
        self.assertEqual(self.client.get(f'/api/survey-books/{self.book["uuid"]}/print', headers=self.nokey).status_code, 403)

    def test_a_page_published_again_makes_the_book_print_again_and_lets_the_old_one_go(self):
        survey = self.published()
        self.printed()
        with self.app.app_context():
            old_pdf = SurveyBookPrint.query.one().pdf_sha256
        changed = self.patch_survey(survey, {'field': {**survey['field'], 'surface': 'Gravel'}}, head=self.admin)
        self.publish(changed.get_json())
        self.assertEqual(self.status()['state'], 'none')                          # the book holds a new revision now
        self.assertEqual(self.pdf().get_json()['code'], 'not_printed')
        self.assertEqual(self.printed()['state'], 'ready')
        self.assertEqual(self.pdf().data, b'%PDF-1.7\n%book 2')
        with self.app.app_context():
            self.assertEqual(SurveyBookPrint.query.count(), 1)
            self.assertIsNone(asset_store.store().get(old_pdf))

    def test_without_gotenberg_the_book_is_not_printed(self):
        self.published()
        with patch.dict(os.environ, {'GOTENBERG_URL': ''}):
            self.assertFalse(self.status()['available'])
            refused = self.ask()
        self.assertEqual((refused.status_code, refused.get_json()['code']), (503, 'pdf_unavailable'))

    def test_a_printer_that_fails_is_said_and_the_book_can_be_asked_for_again(self):
        self.published()
        self.answer = lambda: FakeAnswer(status=500, content=b'oops')
        failed = self.printed()
        self.assertEqual((failed['state'], failed['error']), ('failed', 'The printer could not print the book. Try again in a moment.'))
        self.answer = lambda: FakeAnswer(content=b'%PDF-1.7\n%now')
        self.assertEqual(self.printed()['state'], 'ready')
        self.assertEqual(self.pdf().data, b'%PDF-1.7\n%now')

    def test_a_restart_prints_what_was_asked_for(self):
        self.published()
        self.assertEqual(self.ask().status_code, 202)
        fresh = book_pdf.Printer()                                                # the thread that held the job is gone
        fresh.inline = True
        with patch.object(book_pdf, 'PRINTER', fresh):
            self.assertEqual(self.status()['state'], 'printing')                   # the look queues it again
            fresh.run_pending(self.app)
        self.assertEqual(self.status()['state'], 'ready')
        self.assertEqual(len(self.posted), 1)

    def test_a_print_that_fails_unforeseen_is_said_and_not_printed_again_by_every_look(self):
        self.published()
        with patch.object(pages, 'print_book', side_effect=ValueError('a page that cannot be drawn')), \
                self.assertLogs('routes.survey_routes', level='ERROR'):
            failed = self.printed()
        self.assertEqual((failed['state'], failed['error']), ('failed', 'The book could not be printed. Try again in a moment.'))
        self.status()
        self.printer.run_pending(self.app)                                        # a look queues nothing again
        self.assertEqual(self.posted, [])
        self.assertEqual(self.printed()['state'], 'ready')

    def test_a_print_a_restart_left_behind_is_let_go_once_the_book_prints(self):
        survey = self.published()
        self.assertEqual(self.ask().status_code, 202)                             # asked for, then the server restarts
        fresh = book_pdf.Printer()
        fresh.inline = True
        with patch.object(book_pdf, 'PRINTER', fresh):
            changed = self.patch_survey(survey, {'field': {**survey['field'], 'surface': 'Gravel'}}, head=self.admin)
            self.publish(changed.get_json())                                      # the book moves on: nothing looks for the old print
            asked = self.ask()
            self.assertEqual(asked.status_code, 202)
            fresh.run_pending(self.app)
            self.assertEqual(self.status()['state'], 'ready')
        with self.app.app_context():
            self.assertEqual([r.state for r in SurveyBookPrint.query.all()], ['ready'])

    def test_progress_is_said_page_by_page(self):
        self.published()
        self.published(name='LZ BIG STAMP', hlz_id='NGA 030')
        seen = []

        def answer():
            with self.app.app_context():
                seen.append(SurveyBookPrint.query.one().done_pages)
            return FakeAnswer(content=b'%PDF-1.7\n%x')
        self.answer = answer
        self.printed()
        self.assertEqual(seen, [2])                                                # every page drawn before it is printed

    def test_a_long_index_takes_more_than_one_sheet(self):
        self.published()
        self.published(name='LZ BIG STAMP', hlz_id='NGA 030')
        with patch.object(pages, 'INDEX_ROWS', 1):
            self.printed()
        html = self.posted[0]['html']
        self.assertIn('INDEX · 1 OF 2', html)
        self.assertIn('INDEX · 2 OF 2', html)
        self.assertEqual(html.count('UNCLASSIFIED'), 2 * 5)


class OverviewTests(BookPdfCase):
    def test_the_overview_numbers_every_point_and_keeps_its_labels_apart(self):
        from survey import figures
        drawn = figures.overview({'points': [{'n': 1, 'lat': 34.596, 'lon': -84.1278}, {'n': 2, 'lat': 34.5961, 'lon': -84.1279},
                                             {'n': 3, 'lat': 'x', 'lon': -84.0}]})
        self.assertTrue(drawn['jpeg'].startswith(b'\xff\xd8'))
        labels = re.findall(r'<rect x="([-0-9.]+)" y="([-0-9.]+)" width="([0-9.]+)" height="30"', drawn['svg'])
        self.assertEqual(len(labels), 2)                                           # the point with no position is not drawn
        (x1, y1, w1), (x2, y2, w2) = [(float(a), float(b), float(c)) for a, b, c in labels]
        self.assertTrue(x1 + w1 < x2 or x2 + w2 < x1 or abs(y1 - y2) >= 30)         # two LZs side by side: their numbers do not overlap
        self.assertIsNone(figures.overview({'points': []}))
        self.assertIsNone(figures.overview(None))
