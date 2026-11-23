"""Printing a brief's pages through Gotenberg (docs/MISSION_BRIEF.md §2.7), and turning an Office file into a PDF (docs/DOCUMENTS.md §4).

Gotenberg is Chromium behind an HTTP API, run beside the API as an internal service with no public hostname, at
``GOTENBERG_URL``. It must run with JavaScript off and the network refused (``--chromium-disable-javascript=true``,
``--chromium-deny-list=.*`` with ``--chromium-allow-list=^file:///tmp/.*``), so nothing typed into a brief can make it
fetch anything: every figure goes to it as a file of the same request.
"""

import os
import re
import threading
import time

import requests

TIMEOUT_S = 150


class Unavailable(RuntimeError):
    """No Gotenberg is configured on this server."""


class Failed(RuntimeError):
    """Gotenberg answered, but not with a PDF."""


class Refused(Failed):
    """Gotenberg says the file itself is at fault (its 400): it needs a password, or LibreOffice cannot read it. Sent again, it fails again."""


class Busy(Failed):
    """Gotenberg's LibreOffice queue is full (its 503): worth trying again in a minute."""


class TooLarge(Refused):
    """The PDF LibreOffice made is larger than a document may be."""


def url():
    return (os.environ.get('GOTENBERG_URL') or '').strip().rstrip('/')


# Top, bottom, left and right, in inches: the brief's, which leave room for Chromium's header and footer.
BRIEF_MARGINS = ('0.62', '0.62', '0.5', '0.5')
_TYPES = {'.png': 'image/png', '.svg': 'image/svg+xml'}


def _content_type(name):
    return _TYPES.get(os.path.splitext(name)[1].lower(), 'image/jpeg')


def print_pdf(html, header, footer, files, paper, margins=BRIEF_MARGINS):
    """The PDF Chromium prints from the page, or Unavailable / Failed. ``header`` and ``footer`` are Chromium's own (None for a page
    that prints its own, as an LZ survey's does); a figure's type is told by its file's name."""
    base = url()
    if not base:
        raise Unavailable('GOTENBERG_URL is not set')
    width, height = paper
    parts = [('files', ('index.html', html.encode('utf-8'), 'text/html'))]
    if header is not None:
        parts.append(('files', ('header.html', header.encode('utf-8'), 'text/html')))
    if footer is not None:
        parts.append(('files', ('footer.html', footer.encode('utf-8'), 'text/html')))
    parts += [('files', (name, data, _content_type(name))) for name, data in files.items()]
    top, bottom, left, right = margins
    form = {
        'paperWidth': width, 'paperHeight': height,
        'marginTop': top, 'marginBottom': bottom, 'marginLeft': left, 'marginRight': right,
        'printBackground': 'true', 'preferCssPageSize': 'false', 'skipNetworkIdleEvent': 'true',
    }
    try:
        response = requests.post(f'{base}/forms/chromium/convert/html', files=parts, data=form, timeout=TIMEOUT_S)
    except requests.RequestException as exc:
        raise Failed(f'Gotenberg did not answer: {type(exc).__name__}') from exc
    if response.status_code != 200 or not response.content.startswith(b'%PDF'):
        raise Failed(f'Gotenberg answered {response.status_code}')
    return response.content


# What LibreOffice is told, by the kind of file: every one of these is Gotenberg's default but singlePageSheets, sent anyway so a
# change of default never puts speaker notes, comments or hidden slides into a document everyone in a pack can read.
_OFFICE_FORM = {'exportNotes': 'false', 'exportNotesPages': 'false', 'exportHiddenSlides': 'false', 'updateIndexes': 'true'}
OFFICE_TYPES = {
    'doc': 'application/msword',
    'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'xls': 'application/vnd.ms-excel',
    'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'ppt': 'application/vnd.ms-powerpoint',
    'pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
}
OFFICE_TIMEOUT_S = 120

# Office files are converted only by a Gotenberg that says it is this or later: before 8.34.0 its LibreOffice fetched a document's
# linked content (other files on the machine, private addresses). The brief prints through older ones, so GOTENBERG_URL alone
# is not enough. Asked of Gotenberg's own GET /version and kept a while; no answer, or no version in it, is no conversion.
OFFICE_MIN_VERSION = (8, 34, 0)
VERSION_KEPT_S = 600
VERSION_RETRY_S = 60
_VERSION = re.compile(r'(\d+)\.(\d+)\.(\d+)')
_version_lock = threading.Lock()
_version = {'base': None, 'value': None, 'until': 0.0}


def version(now=None):
    """Gotenberg's version as ``(major, minor, patch)``, or None where there is none, it does not answer, or it gives no version."""
    base = url()
    if not base:
        return None
    now = time.monotonic() if now is None else now
    with _version_lock:
        if _version['base'] == base and now < _version['until']:
            return _version['value']
    value = None
    try:
        response = requests.get(f'{base}/version', timeout=3)
        found = _VERSION.search(response.text[:200]) if response.status_code == 200 else None
        value = tuple(int(part) for part in found.groups()) if found else None
    except requests.RequestException:
        value = None
    with _version_lock:
        _version.update(base=base, value=value, until=now + (VERSION_KEPT_S if value else VERSION_RETRY_S))
    return value


def converts_office(now=None):
    """Whether this server turns Office files into PDFs: a Gotenberg new enough (OFFICE_MIN_VERSION)."""
    found = version(now)
    return found is not None and found >= OFFICE_MIN_VERSION


def convert_office(data, kind, max_bytes=50 * 1024 * 1024):
    """The PDF LibreOffice makes of an Office file, or Unavailable, Refused, Busy or Failed. ``kind`` is the file's type as read from its
    content (``docx``, ``xls``…), which names the file for LibreOffice: it picks its import filter by the extension.

    A spreadsheet is laid out one page a sheet (``singlePageSheets``), so a sync matrix reads as one page to zoom into rather than forty
    cut at its print area. Gotenberg must be 8.34.0 or later, which drops a document's linked content rather than fetching it, and run
    with ``--libreoffice-deny-private-ips=true`` (docs/DOCUMENTS.md §4.1)."""
    base = url()
    if not base:
        raise Unavailable('GOTENBERG_URL is not set')
    if not converts_office():
        raise Unavailable(f'Gotenberg is not {".".join(map(str, OFFICE_MIN_VERSION))} or later (or did not say its version)')
    form = dict(_OFFICE_FORM)
    if kind in ('xls', 'xlsx'):
        form['singlePageSheets'] = 'true'
    parts = [('files', (f'document.{kind}', data, OFFICE_TYPES[kind]))]
    try:
        response = requests.post(f'{base}/forms/libreoffice/convert', files=parts, data=form, timeout=OFFICE_TIMEOUT_S, stream=True)
    except requests.RequestException as exc:
        raise Failed(f'Gotenberg did not answer: {type(exc).__name__}') from exc
    with response:
        if response.status_code == 400:
            raise Refused('Gotenberg answered 400')
        if response.status_code == 503:
            raise Busy('Gotenberg answered 503')
        if response.status_code != 200:
            raise Failed(f'Gotenberg answered {response.status_code}')
        # Read only as far as a PDF may be, so a document that converts to something huge is never held whole.
        body = bytearray()
        try:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                body += chunk
                if len(body) > max_bytes:
                    raise TooLarge(f'the PDF is over {max_bytes} bytes')
        except requests.RequestException as exc:
            raise Failed(f'Gotenberg did not finish answering: {type(exc).__name__}') from exc
    if not body.startswith(b'%PDF'):
        raise Failed('Gotenberg did not answer with a PDF')
    return bytes(body)
