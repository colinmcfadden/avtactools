"""Printing a brief's pages through Gotenberg (docs/MISSION_BRIEF.md §2.7).

Gotenberg is Chromium behind an HTTP API, run beside the API as an internal service with no public hostname, at
``GOTENBERG_URL``. It must run with JavaScript off and the network refused (``--chromium-disable-javascript=true``,
``--chromium-deny-list=.*`` with ``--chromium-allow-list=^file:///tmp/.*``), so nothing typed into a brief can make it
fetch anything: every figure goes to it as a file of the same request.
"""

import os

import requests

TIMEOUT_S = 150


class Unavailable(RuntimeError):
    """No Gotenberg is configured on this server."""


class Failed(RuntimeError):
    """Gotenberg answered, but not with a PDF."""


def url():
    return (os.environ.get('GOTENBERG_URL') or '').strip().rstrip('/')


def print_pdf(html, header, footer, files, paper):
    """The PDF Chromium prints from the page, or Unavailable / Failed."""
    base = url()
    if not base:
        raise Unavailable('GOTENBERG_URL is not set')
    width, height = paper
    parts = [
        ('files', ('index.html', html.encode('utf-8'), 'text/html')),
        ('files', ('header.html', header.encode('utf-8'), 'text/html')),
        ('files', ('footer.html', footer.encode('utf-8'), 'text/html')),
    ] + [('files', (name, data, 'image/jpeg')) for name, data in files.items()]
    form = {
        'paperWidth': width, 'paperHeight': height,
        'marginTop': '0.62', 'marginBottom': '0.62', 'marginLeft': '0.5', 'marginRight': '0.5',
        'printBackground': 'true', 'preferCssPageSize': 'false', 'skipNetworkIdleEvent': 'true',
    }
    try:
        response = requests.post(f'{base}/forms/chromium/convert/html', files=parts, data=form, timeout=TIMEOUT_S)
    except requests.RequestException as exc:
        raise Failed(f'Gotenberg did not answer: {type(exc).__name__}') from exc
    if response.status_code != 200 or not response.content.startswith(b'%PDF'):
        raise Failed(f'Gotenberg answered {response.status_code}')
    return response.content
