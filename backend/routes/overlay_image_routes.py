"""
A mission overlay (the map's planning layers, drawn by the web in pieces) made into an MBTiles or GeoTIFF file for
ForeFlight, with or without USGS's topo map under it (`overlay_image.py`). Web only: not in contracts/openapi.yaml, so
nothing an app may rely on.

- `/api/overlay-jobs` makes it in the background (`overlay_jobs.py`): made, sent its pieces, started, asked about, and
  its file downloaded from a link of its own. What the web uses since the topo map's level 16 over a whole operation.
- `POST /api/overlay-image` makes it while the request waits, within Cloudflare's 100 s: a web page from before the
  jobs still calls it. Nothing is kept; the answer is the file.
"""

import os
import re

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required

import overlay_jobs
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
_build_lock = overlay_jobs.BUILD_LOCK
LOCK_WAIT_SECONDS = 20
STATUS = {'too_large': 413, 'base_unavailable': 502, 'busy': 503, 'no_room': 503, 'started': 409, 'not_found': 404}


def _layer_name(raw):
    """What ForeFlight lists the layer as, and the file's name: letters, digits, spaces and a few marks."""
    name = re.sub(r'[^\w .()-]+', ' ', str(raw or ''), flags=re.UNICODE)
    name = re.sub(r'\s+', ' ', name).strip(' .')[:MAX_NAME].strip(' .')
    return name or 'MISSION OVERLAY'


def _refused(code, message, status=400):
    response = jsonify({'code': code, 'error': message})
    response.headers['Cache-Control'] = 'no-store'
    return response, status


def _answer(body, status=200):
    response = jsonify(body)
    response.headers['Cache-Control'] = 'no-store'
    return response, status


def _read_order(background):
    """(kind, base, name, area) from the request's fields, or raises OverlayError."""
    kind = request.form.get('format', '')
    if kind not in FORMATS:
        raise OverlayError('bad_format', 'Choose MBTiles or GeoTIFF.')
    base = request.form.get('base') or 'none'
    if base not in BASES:
        raise OverlayError('bad_base', 'Choose no base map or the USGS topo map.')
    return kind, base, _layer_name(request.form.get('name')), read_area(request.form, kind, base, background)


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
    try:
        kind, base, name, area = _read_order(background=False)
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


# ------------------------------------------------------------------------------------------------- in the background


def _job_or_refused(job_id):
    job = overlay_jobs.find(get_jwt_identity(), job_id)
    if job is None:
        raise OverlayError('not_found', 'That overlay is no longer being made. Make it again.')
    return job


@overlay_image_bp.route('/api/overlay-jobs', methods=['POST'])
@jwt_required()
@require_feature('exports')
def create_overlay_job():
    """The overlay's fields as `/api/overlay-image` takes them, without its pieces: 201 with how the job stands."""
    try:
        kind, base, name, area = _read_order(background=True)
        job = overlay_jobs.create(get_jwt_identity(), kind, base, name, area)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, STATUS.get(refused.code, 400))
    return _answer(overlay_jobs.status(job), 201)


@overlay_image_bp.route('/api/overlay-jobs/<job_id>/pieces', methods=['POST'])
@jwt_required()
@require_feature('exports')
def add_overlay_pieces(job_id):
    """Multipart `piece-<column>-<row>` PNGs, as many as fit under MAX_UPLOAD_BYTES a request; a piece not sent is
    transparent."""
    if request.content_length and request.content_length > MAX_UPLOAD_BYTES:
        return _refused('too_large', 'Too much of the overlay was sent at once.', 413)
    request.max_content_length = MAX_UPLOAD_BYTES
    try:
        job = _job_or_refused(job_id)
        overlay_jobs.add_pieces(job, request.files)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, STATUS.get(refused.code, 400))
    return _answer(overlay_jobs.status(job))


@overlay_image_bp.route('/api/overlay-jobs/<job_id>/start', methods=['POST'])
@jwt_required()
@require_feature('exports')
def start_overlay_job(job_id):
    try:
        job = _job_or_refused(job_id)
        overlay_jobs.start(job)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, STATUS.get(refused.code, 400))
    return _answer(overlay_jobs.status(job), 202)


@overlay_image_bp.route('/api/overlay-jobs/<job_id>', methods=['GET'])
@jwt_required()
@require_feature('exports')
def overlay_job(job_id):
    """How the job stands; asked every few seconds, which keeps it wanted."""
    try:
        job = _job_or_refused(job_id)
    except OverlayError as refused:
        return _refused(refused.code, refused.message, 404)
    return _answer(overlay_jobs.status(job))


@overlay_image_bp.route('/api/overlay-jobs/<job_id>', methods=['DELETE'])
@jwt_required()
@require_feature('exports')
def cancel_overlay_job(job_id):
    """Stops the job and lets go of everything it has."""
    job = overlay_jobs.find(get_jwt_identity(), job_id)
    if job is not None:
        overlay_jobs.cancel(job)
    return _answer({'cancelled': True})


@overlay_image_bp.route('/api/overlay-jobs/<job_id>/file/<token>', methods=['GET'])
def overlay_job_file(job_id, token):
    """The file made, for its link's token: no bearer token, so a browser can follow the link straight to disk.
    Ranges are answered, so a download cut off can go on."""
    found = overlay_jobs.file_for(job_id, token)
    if found is None:
        return _refused('gone', 'That overlay is no longer here. Make it again.', 404)
    path, kind, name = found
    _, extension, mimetype = FORMATS[kind]
    response = send_file(path, mimetype=mimetype, as_attachment=True, download_name=f'{name}.{extension}',
                         max_age=0, conditional=True)
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response
