"""
POST /api/overlay-image: a mission overlay (the map's planning layers, drawn by the web in pieces) made into an MBTiles
or GeoTIFF file for ForeFlight, with or without USGS's topo map under it (`overlay_image.py`). Web only: not in
contracts/openapi.yaml, so nothing an app may rely on. Nothing is kept; the answer is the file.
"""

import os
import re
import threading

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import jwt_required

import terrain_tiles
from entitlements import require_feature
from overlay_image import BASES, FORMATS, OverlayError, build, read_area, read_pieces

overlay_image_bp = Blueprint('overlay_image', __name__)

# Under Cloudflare's 100 MB for a request; the web checks its pieces against it first. Pieces of planning layers are
# mostly transparent and small; a terrain mask's tint fills them.
MAX_UPLOAD_BYTES = 90 * 1024 * 1024
MAX_NAME = 60
# One at a time: an overlay holds a few hundred MB while it is made, and the server has one worker (AGENTS.md §9). The
# wait is short because Cloudflare gives a request 100 s, and the overlay being made takes up to about a minute.
_build_lock = threading.Lock()
LOCK_WAIT_SECONDS = 20
STATUS = {'too_large': 413, 'base_unavailable': 502}


def _layer_name(raw):
    """What ForeFlight lists the layer as, and the file's name: letters, digits, spaces and a few marks."""
    name = re.sub(r'[^\w .()-]+', ' ', str(raw or ''), flags=re.UNICODE)
    name = re.sub(r'\s+', ' ', name).strip(' .')[:MAX_NAME].strip(' .')
    return name or 'MISSION OVERLAY'


def _refused(code, message, status=400):
    response = jsonify({'code': code, 'error': message})
    response.headers['Cache-Control'] = 'no-store'
    return response, status


@overlay_image_bp.route('/api/overlay-image', methods=['POST'])
@jwt_required()
@require_feature('exports')
@terrain_tiles.pauses_warming
def overlay_image():
    """Multipart: `format` (`mbtiles` or `geotiff`), `base` (`none` or `usgs-topo`), `name`, where the area lies
    (`zoom`, a tenth at most, in device pixels; `left`, `top`, `width`, `height` in that zoom's global pixels) and
    its pieces, `piece-<column>-<row>`, each a PNG of overlay_image.PIECE pixels a side. Answers the file."""
    if request.content_length and request.content_length > MAX_UPLOAD_BYTES:
        return _refused('too_large', 'The overlay is too large to send. Choose less detail.', 413)
    request.max_content_length = MAX_UPLOAD_BYTES
    kind = request.form.get('format', '')
    if kind not in FORMATS:
        return _refused('bad_format', 'Choose MBTiles or GeoTIFF.')
    base = request.form.get('base') or 'none'
    if base not in BASES:
        return _refused('bad_base', 'Choose no base map or the USGS topo map.')
    name = _layer_name(request.form.get('name'))
    try:
        area = read_area(request.form, kind, base)
        pieces = read_pieces(request.files, area)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, STATUS.get(refused.code, 400))

    if not _build_lock.acquire(timeout=LOCK_WAIT_SECONDS):
        return _refused('busy', 'Another overlay is being made. Try again in a minute.', 503)
    try:
        path = build(kind, area, pieces, base, name)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, STATUS.get(refused.code, 400))
    finally:
        _build_lock.release()

    _, extension, mimetype = FORMATS[kind]
    built = open(path, 'rb')  # noqa: SIM115 — closed by the response
    os.unlink(path)           # gone once the response closes it
    response = send_file(built, mimetype=mimetype, as_attachment=True, download_name=f'{name}.{extension}', max_age=0)
    response.headers['Cache-Control'] = 'no-store'
    return response
