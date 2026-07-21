"""Route share links for the ForeFlight QR fallback.

  POST /api/route-share   (auth)  store a route's GPX + FPL, return a token URL
  GET  /r/<token>         (public) landing page offering both downloads
  GET  /r/<token>/route.gpx | route.fpl  (public) the files themselves

Minting a link requires sign-in; the token URL itself is public so it can be
scanned on an iPad that isn't signed in. Files are served as attachments (never
rendered), and the only place the route name reaches HTML is the landing page,
where it is escaped.
"""

import io
import re
import html

from flask import Blueprint, request, jsonify, send_file, url_for, current_app, Response
from flask_jwt_extended import jwt_required

from route_share_store import RouteShareStore
from entitlements import require_feature

route_share_bp = Blueprint('route_share', __name__)

SHARE_TTL_SECONDS = 1800
_MAX_XML_BYTES = 512 * 1024

_store = RouteShareStore(ttl_seconds=SHARE_TTL_SECONDS)


def _safe_filename(name, fallback='route'):
    base = re.sub(r'[^\w-]+', '_', (name or '').strip()) or fallback
    return base[:60]


def _no_store(response):
    response.headers['Cache-Control'] = 'no-store'
    return response


@route_share_bp.route('/api/route-share', methods=['POST'])
@jwt_required()
@require_feature('exports')
def create_route_share():
    body = request.get_json(silent=True) or {}
    name = body.get('name')
    gpx = body.get('gpx')
    fpl = body.get('fpl')

    if not isinstance(gpx, str) or not isinstance(fpl, str) or not gpx or not fpl:
        return jsonify({'error': 'Both gpx and fpl route content are required'}), 400

    gpx_bytes = gpx.encode('utf-8')
    fpl_bytes = fpl.encode('utf-8')
    if len(gpx_bytes) > _MAX_XML_BYTES or len(fpl_bytes) > _MAX_XML_BYTES:
        return jsonify({'error': 'Route is too large to share'}), 413

    # Keep the readable name (spaces intact) for the landing page; the filename
    # is sanitized only when a file is actually served.
    display_name = (name.strip()[:60] if isinstance(name, str) and name.strip() else 'Route')
    try:
        token = _store.create(display_name, gpx_bytes, fpl_bytes)
    except ValueError:
        return jsonify({'error': 'Route is too large to share'}), 413

    response = jsonify({
        'sharePath': url_for('route_share.share_landing', token=token),
        'expiresInSeconds': SHARE_TTL_SECONDS,
    })
    return _no_store(response), 201


_EXPIRED_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Link expired</title></head>
<body style="font-family:-apple-system,system-ui,sans-serif;background:#101317;color:#e7eaee;
display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
<div><h2>Link expired</h2><p style="color:#97a0ab">This route share link is no longer
available. Generate a new QR code from the planner.</p></div></body></html>"""


@route_share_bp.route('/r/<token>', methods=['GET'])
def share_landing(token):
    share = _store.get(token)
    if share is None:
        return _no_store(Response(_EXPIRED_PAGE, mimetype='text/html')), 410

    name = html.escape(share.name)
    gpx_url = url_for('route_share.share_file', token=token, kind='gpx')
    fpl_url = url_for('route_share.share_file', token=token, kind='fpl')
    page = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{name} — route</title>
<style>
  body{{font-family:-apple-system,system-ui,sans-serif;background:#101317;color:#e7eaee;
    margin:0;padding:24px;display:flex;justify-content:center}}
  .card{{max-width:420px;width:100%}}
  h1{{font-size:1.2rem;letter-spacing:.02em}}
  .sub{{color:#97a0ab;font-size:.9rem;line-height:1.5}}
  a.btn{{display:block;text-align:center;margin:12px 0;padding:15px;border-radius:10px;
    text-decoration:none;font-weight:700;font-size:1.05rem}}
  a.gpx{{background:#3d84b3;color:#fff}}
  a.fpl{{background:#21262f;color:#e7eaee;border:1px solid rgba(255,255,255,.16)}}
  ol{{color:#97a0ab;font-size:.85rem;line-height:1.6;padding-left:1.2em}}
</style></head>
<body><div class="card">
  <h1>{name}</h1>
  <p class="sub">Download a route file, then import it into ForeFlight.</p>
  <a class="btn gpx" href="{gpx_url}" download>Download GPX (recommended)</a>
  <a class="btn fpl" href="{fpl_url}" download>Download Garmin .fpl</a>
  <p class="sub">To import on the iPad:</p>
  <ol>
    <li>Tap a download above.</li>
    <li>Open the file (Files app or the download banner).</li>
    <li>Share &rarr; <b>ForeFlight</b> (or &ldquo;Copy to ForeFlight&rdquo;).</li>
    <li>Find the route under <b>Flights</b>; the waypoints load into <b>User Waypoints</b>.</li>
  </ol>
  <p class="sub" style="font-size:.75rem">This link expires in 30 minutes.</p>
</div></body></html>"""
    return _no_store(Response(page, mimetype='text/html'))


@route_share_bp.route('/r/<token>/route.<kind>', methods=['GET'])
def share_file(token, kind):
    if kind not in ('gpx', 'fpl'):
        return jsonify({'error': 'Unknown file type'}), 404
    share = _store.get(token)
    if share is None:
        return jsonify({'error': 'This download link has expired'}), 410

    if kind == 'gpx':
        data, mimetype = share.gpx, 'application/gpx+xml'
    else:
        data, mimetype = share.fpl, 'application/xml'

    response = send_file(
        io.BytesIO(data),
        mimetype=mimetype,
        as_attachment=True,
        download_name=f'{_safe_filename(share.name)}.{kind}',
        max_age=0,
    )
    return _no_store(response)
