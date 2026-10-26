"""LZ surveys and the HLZ survey book (docs/LZ_SURVEYS.md): ``/api/surveys`` and ``/api/survey-book``.

Web only, so these routes are not in contracts/openapi.yaml and nothing syncs. Every route needs an admin or the super-admin
(``entitlements.is_admin``), checked here: readers' access (the ``lz_surveys`` key, published surveys only) comes with
publishing, in step L5. Every answer is one account's view of unpublished work, so none is cached by anything between
(``Cache-Control: private``, AGENTS.md §14).

A save names the revision it was made on (``If-Match: "3"``): two admins editing one survey get a 409 on the older one, with
the server's copy, and nothing is overwritten. A survey is archived, never deleted; so is a photo taken off it.
"""

from __future__ import annotations

import io
import json
import re
import uuid as uuid_lib
from datetime import datetime
from functools import wraps

from flask import Blueprint, g, jsonify, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required

import terrain_tiles
from entitlements import is_admin
from models import LzSurvey, LzSurveyAsset, SurveyBook, User, db
from survey import assets as asset_store
from survey import compare, fields, measure

survey_bp = Blueprint('surveys', __name__)

DEFAULT_BOOK_NAME = 'HLZ SURVEY BOOK'
MAX_NAME = 100
MAX_HLZ_ID = 40
MAX_BOOK_TEXT = 4000
MAX_CAPTION = 200
MAX_TAKEN_ON = 40
# A saved diagram is a few kilobytes; its slope raster is never saved with it (the snapshot keeps the survey's own).
MAX_DIAGRAM_BYTES = 1_000_000
PHOTO_KINDS = ('photo_north', 'photo_approach', 'photo_other')
CAPTURE_KINDS = ('view_3d',)
ASSET_KINDS = PHOTO_KINDS + CAPTURE_KINDS
_IF_MATCH = re.compile(r'^(?:W/)?"?(\d+)"?$')


# -- Helpers --------------------------------------------------------------------

def _reply(body, status=200):
    response = jsonify(body)
    response.status_code = status
    response.headers['Cache-Control'] = 'private, no-store'
    return response


def _error(message, status, code, **extra):
    return _reply({'error': message, 'code': code, **extra}, status)


def admin_only(view):
    """JWT and an admin, or 403 ``admin_required``. The web hides the survey book from everyone else; this is what holds."""
    @wraps(view)
    @jwt_required()
    def wrapped(*args, **kwargs):
        try:
            user = db.session.get(User, int(get_jwt_identity()))
        except (TypeError, ValueError):
            user = None
        if not is_admin(user):
            return _error('Only admins can work on LZ surveys.', 403, 'admin_required')
        g.survey_user = user
        return view(*args, **kwargs)
    return wrapped


def _me():
    return g.survey_user


def _body():
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else {}


def _iso(value):
    return value.isoformat() + 'Z' if value else None


def _if_match():
    """The revision the client based its change on, None when absent, or False when malformed."""
    raw = request.headers.get('If-Match')
    if raw is None:
        return None
    match = _IF_MATCH.match(raw.strip())
    return int(match.group(1)) if match else False


def _precondition(record, serialize):
    """The refusal for a change not based on ``record``'s revision, or None to go ahead. The header is required: a survey is
    shared by every admin, so there is no last-writer-wins."""
    expected = _if_match()
    if expected is None:
        return _error('Say which revision this change is based on (If-Match).', 428, 'if_match_required')
    if expected is False:
        return _error('If-Match must be the revision, e.g. "3".', 400, 'invalid_if_match')
    if expected != record.revision:
        return _error('Someone saved this since you opened it. Nothing was overwritten.', 409, 'revision_conflict',
                      server=serialize(record))
    return None


def _book():
    """The one book, made on first use."""
    book = SurveyBook.query.order_by(SurveyBook.id).first()
    if book is None:
        book = SurveyBook(name=DEFAULT_BOOK_NAME, page_order=[])
        db.session.add(book)
        db.session.flush()
    return book


def _book_json(book):
    return {
        'name': book.name,
        'unit_line': book.unit_line,
        'cover_text': book.cover_text,
        'page_order': list(book.page_order or []),
        'revision': book.revision,
        'updated_by': book.updated_by_name,
        'updated_at': _iso(book.updated_at),
    }


def _assets(survey):
    return (LzSurveyAsset.query.filter_by(survey_id=survey.id, removed_at=None)
            .order_by(LzSurveyAsset.id).all())


def _asset_json(asset, survey_uuid):
    return {
        'id': asset.id,
        'kind': asset.kind,
        'caption': asset.caption,
        'heading_deg': asset.heading_deg,
        'taken_on': asset.taken_on,
        'pose': asset.pose,
        'content_type': asset.content_type,
        'width': asset.width,
        'height': asset.height,
        'size_bytes': asset.size_bytes,
        'sha256': asset.sha256,
        'created_by': asset.created_by_name,
        'created_at': _iso(asset.created_at),
        # Relative to the API's base (it already ends in /api): AGENTS.md §14.
        'url': f'/surveys/{survey_uuid}/assets/{asset.id}',
    }


def _surveyed_on(survey):
    return (survey.field or {}).get('surveyedOn') or ''


def _summary(survey):
    snapshot = survey.snapshot or {}
    return {
        'uuid': survey.uuid,
        'hlz_id': survey.hlz_id,
        'name': survey.name,
        'status': survey.status,
        'revision': survey.revision,
        'disagreements': compare.open_count(survey.field, survey.snapshot, survey.overrides),
        'surveyed_on': _surveyed_on(survey),
        'grid': snapshot.get('grid') or (survey.field or {}).get('grid') or '',
        'measured_at': snapshot.get('measuredAt'),
        'updated_by': survey.updated_by_name,
        'updated_at': _iso(survey.updated_at),
        'archived': survey.archived_at is not None,
    }


def _full(survey):
    asset_rows = _assets(survey)
    return {
        **_summary(survey),
        'field': survey.field or {},
        'overrides': survey.overrides or {},
        'diagram': survey.diagram,
        'snapshot': survey.snapshot,
        'comparisons': compare.compare(survey.field, survey.snapshot, survey.overrides),
        'asset_ids': [a.id for a in asset_rows],
        'assets': [_asset_json(a, survey.uuid) for a in asset_rows],
        'created_by': survey.created_by_name,
        'created_at': _iso(survey.created_at),
    }


def _survey(survey_uuid):
    return LzSurvey.query.filter_by(uuid=survey_uuid).first()


def _clean_text(value, limit, label, required=False):
    if value is None:
        value = ''
    if not isinstance(value, str) or len(value) > limit or any(ord(c) < 32 for c in value):
        raise ValueError(f'{label} is text of at most {limit} characters, on one line')
    value = value.strip()
    if required and not value:
        raise ValueError(f'{label} cannot be blank')
    return value


def _clean_diagram(value):
    """The LZ/PZ diagram to store, the web's schema-2 document, with any runtime slope raster taken out; or raise ValueError."""
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValueError('diagram must be an LZ/PZ diagram document')
    value = dict(value)
    analysis = value.get('analysis')
    if isinstance(analysis, dict) and analysis.get('terrainData') is not None:
        value['analysis'] = {**analysis, 'terrainData': None}
    if len(json.dumps(value)) > MAX_DIAGRAM_BYTES:
        raise ValueError('The diagram is too large to keep with a survey')
    return value


def _touch(survey):
    user = _me()
    survey.revision = (survey.revision or 0) + 1
    survey.updated_by = user.id
    survey.updated_by_name = user.name or user.email
    # Any change to a survey under review or published makes it a draft again; the last published revision stays what readers see.
    if survey.status != 'draft':
        survey.status = 'draft'


def _order_key(book):
    order = {u: i for i, u in enumerate(book.page_order or [])}
    return lambda s: (order.get(s.uuid, len(order)), s.hlz_id or '', s.name or '')


# -- The book ------------------------------------------------------------------

@survey_bp.route('/api/survey-book', methods=['GET'])
@admin_only
def get_book():
    book = _book()
    db.session.commit()
    return _reply(_book_json(book))


@survey_bp.route('/api/survey-book', methods=['PATCH'])
@admin_only
def update_book():
    book = _book()
    db.session.commit()
    refused = _precondition(book, _book_json)
    if refused:
        return refused
    data = _body()
    try:
        if 'name' in data:
            book.name = _clean_text(data['name'], 200, 'The name', required=True)
        if 'unit_line' in data:
            book.unit_line = _clean_text(data['unit_line'], 200, 'The unit line')
        if 'cover_text' in data:
            text = data['cover_text'] if data['cover_text'] is not None else ''
            if not isinstance(text, str) or len(text) > MAX_BOOK_TEXT:
                raise ValueError(f'The cover text is at most {MAX_BOOK_TEXT} characters')
            book.cover_text = text
        if 'page_order' in data:
            order = data['page_order']
            if not isinstance(order, list) or not all(isinstance(u, str) for u in order) or len(set(order)) != len(order):
                raise ValueError('page_order is a list of survey ids, each once')
            known = {s.uuid for s in LzSurvey.query.filter(LzSurvey.uuid.in_(order)).all()} if order else set()
            if known != set(order):
                raise ValueError('page_order names a survey that is not in the book')
            book.page_order = order
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_book')
    user = _me()
    book.revision += 1
    book.updated_by_name = user.name or user.email
    db.session.commit()
    return _reply(_book_json(book))


# -- Surveys -------------------------------------------------------------------

@survey_bp.route('/api/surveys', methods=['GET'])
@admin_only
def list_surveys():
    book = _book()
    db.session.commit()
    query = LzSurvey.query.filter_by(book_id=book.id)
    if request.args.get('archived') not in ('1', 'true'):
        query = query.filter(LzSurvey.archived_at.is_(None))
    surveys = sorted(query.all(), key=_order_key(book))
    return _reply({'book': _book_json(book), 'surveys': [_summary(s) for s in surveys]})


@survey_bp.route('/api/surveys', methods=['POST'])
@admin_only
def create_survey():
    data = _body()
    try:
        name = _clean_text(data.get('name'), MAX_NAME, 'The name', required=True)
        hlz_id = _clean_text(data.get('hlz_id'), MAX_HLZ_ID, 'The HLZ ID')
        field = fields.clean_field(data.get('field') or {})
        diagram = _clean_diagram(data.get('diagram'))
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_survey')
    user = _me()
    book = _book()
    survey = LzSurvey(
        uuid=str(uuid_lib.uuid4()), book_id=book.id, hlz_id=hlz_id, name=name, status='draft', revision=1,
        field=field, overrides={}, diagram=diagram, snapshot=None,
        created_by=user.id, created_by_name=user.name or user.email,
        updated_by=user.id, updated_by_name=user.name or user.email,
    )
    db.session.add(survey)
    book.page_order = list(book.page_order or []) + [survey.uuid]
    book.revision += 1
    db.session.commit()
    return _reply(_full(survey), 201)


@survey_bp.route('/api/surveys/<survey_uuid>', methods=['GET'])
@admin_only
def get_survey(survey_uuid):
    survey = _survey(survey_uuid)
    if survey is None:
        return _error('There is no such survey.', 404, 'not_found')
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>', methods=['PATCH'])
@admin_only
def update_survey(survey_uuid):
    survey = _survey(survey_uuid)
    if survey is None:
        return _error('There is no such survey.', 404, 'not_found')
    refused = _precondition(survey, _full)
    if refused:
        return refused
    if survey.archived_at is not None:
        return _error('This survey is archived: restore it to change it.', 409, 'archived')
    data = _body()
    user = _me()
    try:
        name = _clean_text(data['name'], MAX_NAME, 'The name', required=True) if 'name' in data else survey.name
        hlz_id = _clean_text(data['hlz_id'], MAX_HLZ_ID, 'The HLZ ID') if 'hlz_id' in data else survey.hlz_id
        field = fields.clean_field(data['field']) if 'field' in data else survey.field
        diagram = _clean_diagram(data['diagram']) if 'diagram' in data else survey.diagram
        # Overrides are judged against the field values this same change leaves.
        overrides = (compare.clean_overrides(data['overrides'], survey.overrides, field, survey.snapshot, user.name or user.email)
                     if 'overrides' in data else survey.overrides)
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_survey')
    changed = (name, hlz_id, field, diagram, overrides) != (survey.name, survey.hlz_id, survey.field, survey.diagram, survey.overrides)
    if changed:
        survey.name, survey.hlz_id, survey.field, survey.diagram, survey.overrides = name, hlz_id, field, diagram, overrides
        _touch(survey)
        db.session.commit()
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>/archive', methods=['POST'])
@admin_only
def archive_survey(survey_uuid):
    return _set_archived(survey_uuid, True)


@survey_bp.route('/api/surveys/<survey_uuid>/restore', methods=['POST'])
@admin_only
def restore_survey(survey_uuid):
    return _set_archived(survey_uuid, False)


def _set_archived(survey_uuid, archived):
    survey = _survey(survey_uuid)
    if survey is None:
        return _error('There is no such survey.', 404, 'not_found')
    if (survey.archived_at is not None) != archived:
        survey.archived_at = datetime.utcnow() if archived else None
        user = _me()
        survey.revision += 1
        survey.updated_by, survey.updated_by_name = user.id, user.name or user.email
        db.session.commit()
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>/measure', methods=['POST'])
@admin_only
@terrain_tiles.pauses_warming
def measure_survey(survey_uuid):
    """Measure the survey's saved diagram. Without ``apply`` nothing is stored: the answer is the new snapshot and what it would change.
    With ``apply`` (and If-Match) the survey takes it."""
    survey = _survey(survey_uuid)
    if survey is None:
        return _error('There is no such survey.', 404, 'not_found')
    apply = bool(_body().get('apply'))
    if apply:
        refused = _precondition(survey, _full)
        if refused:
            return refused
        if survey.archived_at is not None:
            return _error('This survey is archived: restore it to change it.', 409, 'archived')
    user = _me()
    try:
        snapshot = measure.measure(survey.diagram, survey.field, user.name or user.email)
    except ValueError as problem:
        return _error(str(problem), 400, 'no_target')
    changes = measure.differences(survey.snapshot, snapshot)
    if apply:
        survey.snapshot = snapshot
        _touch(survey)
        db.session.commit()
    return _reply({
        'applied': apply,
        'snapshot': snapshot,
        'changes': changes,
        'comparisons': compare.compare(survey.field, snapshot, survey.overrides),
        'survey': _full(survey) if apply else None,
    })


# -- Photos and captures -------------------------------------------------------

def _float_or_none(value, label):
    if value in (None, ''):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise ValueError(f'{label} is a number of degrees')
    if not 0 <= number <= 360:
        raise ValueError(f'{label} is 0 to 360 degrees')
    return number % 360.0


def _asset_fields(source, partial):
    """caption, heading_deg, taken_on and kind from a form or JSON body; only those present when ``partial``."""
    out = {}
    if not partial or 'kind' in source:
        kind = source.get('kind') or ('photo_other' if not partial else None)
        if kind not in ASSET_KINDS:
            raise ValueError(f'kind is one of {", ".join(ASSET_KINDS)}')
        out['kind'] = kind
    if not partial or 'caption' in source:
        out['caption'] = _clean_text(source.get('caption'), MAX_CAPTION, 'The caption')
    if not partial or 'heading_deg' in source:
        out['heading_deg'] = _float_or_none(source.get('heading_deg'), 'The heading')
    if not partial or 'taken_on' in source:
        out['taken_on'] = _clean_text(source.get('taken_on'), MAX_TAKEN_ON, 'When it was taken')
    return out


@survey_bp.route('/api/surveys/<survey_uuid>/assets', methods=['POST'])
@admin_only
def upload_asset(survey_uuid):
    survey = _survey(survey_uuid)
    if survey is None:
        return _error('There is no such survey.', 404, 'not_found')
    # Refused before the form is read: the file would otherwise be spooled whole first.
    if request.content_length is not None and request.content_length > asset_store.MAX_UPLOAD_BYTES + 64 * 1024:
        return _error('A photo can be at most 20 MB.', 413, 'too_large')
    upload = request.files.get('file')
    if upload is None:
        return _error('Send the picture as the form\'s "file".', 400, 'missing_file')
    try:
        meta = _asset_fields(request.form, partial=False)
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_asset')
    data = upload.stream.read(asset_store.MAX_UPLOAD_BYTES + 1)
    try:
        prepared = (asset_store.prepare_capture(data) if meta['kind'] in CAPTURE_KINDS else asset_store.prepare_photo(data))
    except asset_store.AssetError as problem:
        return _error(str(problem), 413 if problem.code == 'too_large' else 400, problem.code)
    key = prepared.key
    asset_store.store().put(key, prepared.data)
    user = _me()
    asset = LzSurveyAsset(
        survey_id=survey.id, kind=meta['kind'], caption=meta['caption'], heading_deg=meta['heading_deg'],
        taken_on=meta['taken_on'], content_type=prepared.content_type, width=prepared.width, height=prepared.height,
        size_bytes=len(prepared.data), sha256=key, created_by=user.id, created_by_name=user.name or user.email,
    )
    db.session.add(asset)
    db.session.commit()
    return _reply(_asset_json(asset, survey.uuid), 201)


def _asset(survey_uuid, asset_id):
    survey = _survey(survey_uuid)
    if survey is None:
        return None, None
    return survey, LzSurveyAsset.query.filter_by(id=asset_id, survey_id=survey.id).first()


@survey_bp.route('/api/surveys/<survey_uuid>/assets/<int:asset_id>', methods=['GET'])
@admin_only
def get_asset(survey_uuid, asset_id):
    # A removed asset is still served: a published revision may show it.
    survey, asset = _asset(survey_uuid, asset_id)
    if asset is None:
        return _error('There is no such picture.', 404, 'not_found')
    data = asset_store.store().get(asset.sha256)
    if data is None:
        return _error('The picture is missing from the store.', 404, 'asset_missing')
    response = send_file(io.BytesIO(data), mimetype=asset.content_type, max_age=0)
    # Its bytes never change (they are named by their hash), but they are one survey's: never cached by anything shared.
    response.headers['Cache-Control'] = 'private, max-age=86400, immutable'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


@survey_bp.route('/api/surveys/<survey_uuid>/assets/<int:asset_id>', methods=['PATCH'])
@admin_only
def update_asset(survey_uuid, asset_id):
    survey, asset = _asset(survey_uuid, asset_id)
    if asset is None or asset.removed_at is not None:
        return _error('There is no such picture.', 404, 'not_found')
    try:
        changes = _asset_fields(_body(), partial=True)
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_asset')
    if 'kind' in changes and (changes['kind'] in CAPTURE_KINDS) != (asset.kind in CAPTURE_KINDS):
        return _error('A photo stays a photo and a 3D capture a capture.', 400, 'invalid_asset')
    for key, value in changes.items():
        setattr(asset, key, value)
    db.session.commit()
    return _reply(_asset_json(asset, survey.uuid))


@survey_bp.route('/api/surveys/<survey_uuid>/assets/<int:asset_id>', methods=['DELETE'])
@admin_only
def remove_asset(survey_uuid, asset_id):
    """Takes the picture off the survey. It is kept (and its bytes): a published revision may show it."""
    survey, asset = _asset(survey_uuid, asset_id)
    if asset is None:
        return _error('There is no such picture.', 404, 'not_found')
    if asset.removed_at is None:
        asset.removed_at = datetime.utcnow()
        db.session.commit()
    return _reply({'removed': asset.id})
