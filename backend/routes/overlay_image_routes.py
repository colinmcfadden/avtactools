"""
POST /api/overlay-image: a mission overlay (the map's planning layers as one transparent PNG the web captured) made
into an MBTiles or GeoTIFF file for ForeFlight (`overlay_image.py`). Web only: not in contracts/openapi.yaml, so
nothing an app may rely on. Nothing is kept; the answer is the file.
"""

import io
import re
import threading

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import jwt_required

import terrain_tiles
from entitlements import require_feature
from overlay_image import OverlayError, build_geotiff, build_mbtiles

overlay_image_bp = Blueprint('overlay_image', __name__)

MAX_UPLOAD_BYTES = 48 * 1024 * 1024  # a 6,400-pixel PNG of planning layers is a few MB; a busy one far less than this
MAX_NAME = 60
# One at a time: a large overlay holds a few hundred MB while it is tiled, and the server has one worker (AGENTS.md §9).
_build_lock = threading.Lock()
LOCK_WAIT_SECONDS = 90

FORMATS = {
    'mbtiles': (build_mbtiles, 'mbtiles', 'application/octet-stream'),
    'geotiff': (build_geotiff, 'tif', 'image/tiff'),
}


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
    """Multipart: `image` (PNG), `north`, `south`, `east`, `west` (its corners, degrees), `format`
    (`mbtiles` or `geotiff`) and `name`. Answers the file."""
    if request.content_length and request.content_length > MAX_UPLOAD_BYTES:
        return _refused('too_large', 'The overlay image is too large to send.', 413)
    kind = request.form.get('format', '')
    if kind not in FORMATS:
        return _refused('bad_format', 'Choose MBTiles or GeoTIFF.')
    upload = request.files.get('image')
    if upload is None:
        return _refused('no_image', 'No overlay image was sent.')
    data = upload.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        return _refused('too_large', 'The overlay image is too large to send.', 413)
    build, extension, mimetype = FORMATS[kind]
    name = _layer_name(request.form.get('name'))
    corners = {key: request.form.get(key) for key in ('north', 'south', 'east', 'west')}

    if not _build_lock.acquire(timeout=LOCK_WAIT_SECONDS):
        return _refused('busy', 'Another overlay is being made. Try again in a minute.', 503)
    try:
        built = build(data, corners, name)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, 413 if refused.code == 'too_large' else 400)
    finally:
        _build_lock.release()

    response = send_file(io.BytesIO(built), mimetype=mimetype, as_attachment=True,
                         download_name=f'{name}.{extension}', max_age=0)
    response.headers['Cache-Control'] = 'no-store'
    return response
