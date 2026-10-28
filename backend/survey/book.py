"""The whole book's PDF (docs/LZ_SURVEYS.md §5, step L6): its cover with a map of every LZ, its index, then every published survey's
latest revision in the book's order, each page as it prints alone but numbered "n OF N" in the book.

A book is printed in the background (``Printer``), one book at a time: its pages fetch their maps' tiles and Chromium prints dozens of
them, longer than a request may wait behind Cloudflare. What it prints is named by a fingerprint of what it holds (``fingerprint``:
the cover's words and every page's survey and revision, in order, and the EZ/PZ version), so a PDF is kept until a page in it is
published again, moved, archived or added, and two people asking for the same book get the same file. A page in the book carries the
book's name and unit line as the book has them now, as it carries its place in the book: a survey moved from another book prints as
this book's page.
"""

from __future__ import annotations

import collections
import hashlib
import json
import logging
import textwrap
import threading
from datetime import datetime

from survey.compile import long_date, short_date

log = logging.getLogger(__name__)

BOOK_FORMAT = "ezpz-survey-book"
# Part of every fingerprint: a change to how the book is laid out prints it again.
LAYOUT = 2
# The cover's text at most: as many lines as fit under its map at 9 pt, each about as long as the cover is wide.
COVER_LINES = 7
COVER_LINE_CHARS = 118


def cover_of(book):
    """The book's words its PDF is printed with, read once: what the fingerprint names is then what the cover and every page show,
    whatever is saved while the pages are drawn."""
    return {"name": book.name or "", "unitLine": book.unit_line or "", "coverText": book.cover_text or ""}


def fingerprint(cover, entries, version):
    """What a book's PDF holds, as one key: the cover's words (``cover_of``), every page's survey and revision in order, the EZ/PZ
    version and the layout. ``entries``: [(survey, revision)]."""
    data = {
        "layout": LAYOUT,
        "version": version,
        "name": cover["name"],
        "unitLine": cover["unitLine"],
        "coverText": cover["coverText"],
        "pages": [[survey.uuid, revision.number, revision.id] for survey, revision in entries],
    }
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode("utf-8")).hexdigest()


def cover_text(text):
    """The cover's text as it fits under the map: (lines, cut). Past ``COVER_LINES`` it is cut, and the cover says so."""
    lines = []
    for paragraph in (text or "").splitlines() or [""]:
        lines.extend(textwrap.wrap(paragraph, COVER_LINE_CHARS) or [""])
    while lines and not lines[-1].strip():
        lines.pop()
    if len(lines) <= COVER_LINES:
        return lines, False
    return lines[:COVER_LINES - 1] + [lines[COVER_LINES - 1].rstrip() + " …"], True


def _signoff_words(signoff):
    if not isinstance(signoff, dict):
        return "—"
    who = " ".join(str(signoff.get(k) or "").strip().upper() for k in ("rank", "name") if str(signoff.get(k) or "").strip())
    return who or "—"


def compile_book(cover, pages, printed_on, version):
    """The book's own model: the cover's words (``cover_of``), the index and the overview map's points. ``pages``: [(page model,
    revision)] in the book's order, each page model numbered in the book already."""
    index, points = [], []
    for n, (model, revision) in enumerate(pages, start=1):
        facts = model.get("facts") or {}
        index.append({
            "n": n,
            "hlzId": model.get("hlzId") or "—",
            "name": model.get("name") or "—",
            "grid": ((facts.get("grid") or {}).get("value")) or "—",
            "revision": model.get("revision") or "—",
            "publishedOn": short_date(revision.published_at) if revision.published_at else "—",
            "signoff": _signoff_words(revision.signoff),
        })
        target = (model.get("plan") or {}).get("target")
        if isinstance(target, dict):
            points.append({"n": n, "lat": target.get("lat"), "lon": target.get("lon")})
    lines, cut = cover_text(cover["coverText"])
    name = cover["name"].upper()
    return {
        "format": BOOK_FORMAT,
        "book": {"name": name, "unitLine": cover["unitLine"].upper(), "coverLines": lines, "coverCut": cut,
                 # The cover's name in three sizes, so a long one keeps to its lines (the field takes 200 characters).
                 "nameSize": "long" if len(name) > 64 else "mid" if len(name) > 30 else "short"},
        "printedOn": short_date(printed_on),
        "printedLong": long_date(printed_on),
        "version": version,
        "count": len(pages),
        "index": index,
        "overview": {"points": points} if points else None,
    }


class Printer:
    """One thread printing books one at a time, in the order asked. Nothing is lost when the server restarts: the next look at a book
    still marked as printing (``resume``) asks again."""

    def __init__(self):
        # Tests: what is asked for waits for run_pending(), in the test's own thread, instead of a thread of its own.
        self.inline = False
        # Prints still marked as printing from before this are a restart's: their thread is gone.
        self.started = datetime.utcnow()
        self._queue = collections.deque()
        self._known = set()
        self._lock = threading.Lock()
        self._thread = None

    def enqueue(self, app, print_id, job):
        """Prints ``print_id`` with ``job(print_id)``, run inside the app's context, unless it is queued already; and starts the thread
        if there is work and none is running (one that ended for any reason is replaced)."""
        with self._lock:
            if print_id not in self._known:
                self._known.add(print_id)
                self._queue.append((print_id, job))
            if self.inline or not self._queue or (self._thread is not None and self._thread.is_alive()):
                return
            self._thread = threading.Thread(target=self._work, args=(app,), name="survey-book-print", daemon=True)
            self._thread.start()

    resume = enqueue

    def knows(self, print_id):
        with self._lock:
            return print_id in self._known

    def _next(self):
        with self._lock:
            if not self._queue:
                self._thread = None
                return None
            return self._queue.popleft()

    def _one(self, app, print_id, job):
        try:
            with app.app_context():
                job(print_id)
        except Exception:  # noqa: BLE001 - the job says its own failures; this is for one that escaped it
            log.exception("surveys: printing book print %s failed", print_id)
        finally:
            with self._lock:
                self._known.discard(print_id)

    def _work(self, app):
        try:
            while True:
                item = self._next()
                if item is None:
                    return
                self._one(app, *item)
        finally:
            with self._lock:
                if self._thread is threading.current_thread():
                    self._thread = None

    def run_pending(self, app):
        """Prints what is queued, in the caller's thread (tests)."""
        while True:
            item = self._next()
            if item is None:
                return
            self._one(app, *item)


PRINTER = Printer()
