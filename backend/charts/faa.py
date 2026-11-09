"""The FAA's chart files: where a build may fetch them from, and whether a cycle is posted (docs/VFR_CHARTS.md §5.2, §5.5).

The FAA answers the zips itself, at ``https://aeronav.faa.gov/visual/MM-DD-YYYY/<folder>/<zip>``, with their sizes and
``Accept-Ranges: bytes`` (checked 2026-11-05). A build fetches from that host alone, over https, and follows no redirect, whatever a
URL in a job's row says.
"""
from __future__ import annotations

import threading
import time
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlparse

from data_admin.jobs import Source

from . import catalog

HOST = "aeronav.faa.gov"
USER_AGENT = "avtactools-data-admin/1.0"
POSTED_KEEP_S = 600          # a cycle's answer is kept 10 minutes, as the tab's layers are
POSTED_TIMEOUT = (5, 10)

_posted: dict[date, tuple[float, bool]] = {}
_lock = threading.Lock()


def allowed_url(url: str) -> bool:
    """Whether ``url`` is one of the FAA's chart files: https, the FAA's host, its default port, no credentials, under ``/visual/``."""
    try:
        parsed = urlparse(url)
        port = parsed.port
    except ValueError:
        return False
    return (parsed.scheme == "https" and parsed.hostname == HOST and port in (None, 443) and not parsed.username
            and not parsed.password and parsed.path.startswith("/visual/") and ".." not in parsed.path)


SOURCE = Source(name="The FAA", allowed=allowed_url, user_agent=USER_AGENT,
                wrong_size="The FAA's file is not the size it gave when asked.")


def current_cycle(now: datetime | None = None) -> date:
    """The cycle in effect at ``now``: each takes effect at 0901Z on its date, so before then on that day it is the one before."""
    now = now or datetime.now(timezone.utc)
    cycle = catalog.cycle_on(now.date())
    if now < catalog.effective_instant(cycle):
        cycle = catalog.cycle_on(now.date() - timedelta(days=1))
    return cycle


def next_cycle(now: datetime | None = None) -> date:
    return current_cycle(now) + timedelta(days=catalog._CYCLE_DAYS)


def probe_url(cycle: date) -> str:
    """The file asked about to learn whether a cycle is posted: its first sectional's zip."""
    first = catalog.SECTIONALS[0]
    return catalog.zip_url(cycle, first.folder, first.zip)


def posted(cycle: date, http=None, *, fresh: bool = False) -> bool | None:
    """Whether the FAA has posted ``cycle``: True, False, or None when it could not be asked (kept for nothing, so asked again)."""
    now = time.monotonic()
    with _lock:
        kept = _posted.get(cycle)
        if kept and not fresh and now - kept[0] < POSTED_KEEP_S:
            return kept[1]
    if http is None:
        import requests
        http = requests
    try:
        response = http.head(probe_url(cycle), headers={"User-Agent": USER_AGENT}, timeout=POSTED_TIMEOUT,
                             allow_redirects=False)
        status = response.status_code
    except Exception:  # noqa: BLE001 — no answer is no answer, whatever the reason
        return None
    if status == 200:
        answer = True
    elif status in (403, 404):
        answer = False       # the FAA answers a cycle not posted yet with a 404 (its folder is not there)
    else:
        return None
    with _lock:
        _posted[cycle] = (now, answer)
    return answer
