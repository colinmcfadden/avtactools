"""LZ surveys and the books they are kept in (docs/LZ_SURVEYS.md): ``/api/survey-books`` and ``/api/surveys``.

Web only, so these routes are not in contracts/openapi.yaml and nothing syncs. Who may do what is ``survey/access.py`` (§7): site admins
everything; with ``lz_surveys``, a team's owner and admins edit that team's books, its members read their published pages, and
everyone reads those of a book for no team. A draft is an editor's alone. It is checked here, on every route: the web hiding what a
person may not do is not what holds. A book or survey a person cannot read is "not found", so its existence is not told either.
No answer is cached by anything between (``Cache-Control: private``, AGENTS.md §14).

A save names the revision it was made on (``If-Match: "3"``): two people editing one survey or book get a 409 on the older one, with
the server's copy, and nothing is overwritten. A book and a survey are archived, never deleted; so is a photo taken off a survey.
"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import re
import uuid as uuid_lib
from datetime import datetime
from functools import wraps

from flask import Blueprint, g, jsonify, make_response, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy.exc import IntegrityError

import terrain_tiles
from brief import gotenberg
from entitlements import is_admin
from models import LzSurvey, LzSurveyAsset, LzSurveyRevision, SurveyBook, Team, User, db
from survey import access
from survey import assets as asset_store
from survey import changes as change_lines
from survey import compare, fields, measure, pages
from survey.compile import as_date, compile_page
from version import __version__

survey_bp = Blueprint('surveys', __name__)
log = logging.getLogger(__name__)

MAX_NAME = 100
MAX_BOOK_NAME = 200
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
# The page preview runs nothing and fetches nothing: its pictures are in it.
PREVIEW_POLICY = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'"


# -- Helpers --------------------------------------------------------------------

def _reply(body, status=200):
    response = jsonify(body)
    response.status_code = status
    response.headers['Cache-Control'] = 'private, no-store'
    return response


def _error(message, status, code, **extra):
    return _reply({'error': message, 'code': code, **extra}, status)


def _not_found(what='survey'):
    return _error(f'There is no such {what}.', 404, 'not_found')


def _not_editor():
    return _error("Only the book's editors can change it.", 403, 'not_editor')


def signed_in(view):
    """JWT and the survey books' key (an admin always has it), or 403. What a person may do in a given book is asked per route."""
    @wraps(view)
    @jwt_required()
    def wrapped(*args, **kwargs):
        try:
            user = db.session.get(User, int(get_jwt_identity()))
        except (TypeError, ValueError):
            user = None
        if not access.has_key(user):
            return _error('Your account cannot open the LZ survey books.', 403, 'feature_disabled')
        g.survey_user = user
        return view(*args, **kwargs)
    return wrapped


def _me():
    return g.survey_user


def _who():
    user = _me()
    return user.name or user.email


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
    """The refusal for a change not based on ``record``'s revision, or None to go ahead. The header is required: a survey and a book
    are shared by their editors, so there is no last-writer-wins."""
    expected = _if_match()
    if expected is None:
        return _error('Say which revision this change is based on (If-Match).', 428, 'if_match_required')
    if expected is False:
        return _error('If-Match must be the revision, e.g. "3".', 400, 'invalid_if_match')
    if expected != record.revision:
        return _error('Someone saved this since you opened it. Nothing was overwritten.', 409, 'revision_conflict',
                      server=serialize(record))
    return None


def _clean_text(value, limit, label, required=False):
    if value is None:
        value = ''
    if not isinstance(value, str) or len(value) > limit or any(ord(c) < 32 for c in value):
        raise ValueError(f'{label} is text of at most {limit} characters, on one line')
    value = value.strip()
    if required and not value:
        raise ValueError(f'{label} cannot be blank')
    return value


# -- Books ------------------------------------------------------------------------

def _book_by_uuid(book_uuid):
    return SurveyBook.query.filter_by(uuid=book_uuid).first() if isinstance(book_uuid, str) and book_uuid else None


def _team_json(team_id):
    if team_id is None:
        return None
    team = db.session.get(Team, team_id)
    return {'id': team_id, 'name': team.name if team else None, 'gone': team is None}


def _book_surveys(book, archived=False):
    query = LzSurvey.query.filter_by(book_id=book.id)
    if not archived:
        query = query.filter(LzSurvey.archived_at.is_(None))
    return sorted(query.all(), key=_order_key(book))


def _order_key(book):
    order = {u: i for i, u in enumerate(book.page_order or [])}
    return lambda s: (order.get(s.uuid, len(order)), s.hlz_id or '', s.name or '')


def _book_json(book):
    editing = access.can_edit(_me(), book)
    surveys = _book_surveys(book)
    published = sum(1 for s in surveys if _latest_number(s))
    body = {
        'uuid': book.uuid,
        'name': book.name,
        'unit_line': book.unit_line,
        'cover_text': book.cover_text,
        'team': _team_json(book.team_id),
        'archived': book.archived_at is not None,
        'can_edit': editing,
        'published_count': published,
        'revision': book.revision,
        'updated_by': book.updated_by_name,
        'updated_at': _iso(book.updated_at),
    }
    if editing:
        body.update(page_order=list(book.page_order or []), survey_count=len(surveys), created_by=book.created_by_name)
    return body


def _readable_book(book_uuid):
    """The book a person may read or edit, or None (told as not found)."""
    book = _book_by_uuid(book_uuid)
    user = _me()
    if book is None or not (access.can_read(user, book) or access.can_edit(user, book)):
        return None
    return book


def _clean_team(value):
    if value in (None, ''):
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError('team_id is a team, or null for a book for no team')
    return value


@survey_bp.route('/api/survey-books', methods=['GET'])
@signed_in
def list_books():
    """The books this person reads or edits, and the teams they may make a book for."""
    user = _me()
    missing = SurveyBook.query.filter(SurveyBook.uuid.is_(None)).all()
    for book in missing:                                             # step L1's one book, made before books had a uuid
        book.uuid = str(uuid_lib.uuid4())
    if missing:
        db.session.commit()
    show_archived = request.args.get('archived') in ('1', 'true') and is_admin(user)
    books = [b for b in SurveyBook.query.order_by(SurveyBook.name).all()
             if (show_archived or b.archived_at is None) and (access.can_read(user, b) or access.can_edit(user, b))]
    return _reply({
        'books': [_book_json(b) for b in books],
        'teams': [{'id': t.id, 'name': t.name} for t in access.teams_for_books(user)],
        'can_make_for_no_team': is_admin(user),
    })


@survey_bp.route('/api/survey-books', methods=['POST'])
@signed_in
def create_book():
    data = _body()
    try:
        name = _clean_text(data.get('name'), MAX_BOOK_NAME, 'The name', required=True)
        unit_line = _clean_text(data.get('unit_line'), 200, 'The unit line')
        team_id = _clean_team(data.get('team_id'))
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_book')
    if not access.can_make_book_for(_me(), team_id):
        if team_id is None:
            return _error('Only a site admin can make a book for no team: everyone with LZ surveys reads it.', 403, 'not_editor')
        return _error("You can make books only for a team you own or run.", 403, 'not_editor')
    book = SurveyBook(uuid=str(uuid_lib.uuid4()), name=name, unit_line=unit_line, team_id=team_id, page_order=[],
                      created_by=_me().id, created_by_name=_who(), updated_by_name=_who())
    db.session.add(book)
    db.session.commit()
    return _reply(_book_json(book), 201)


@survey_bp.route('/api/survey-books/<book_uuid>', methods=['GET'])
@signed_in
def get_book(book_uuid):
    """The book and its surveys: every one for an editor (archived ones too with ``?archived=1``), the published ones for a reader."""
    book = _readable_book(book_uuid)
    if book is None:
        return _not_found('book')
    if access.can_edit(_me(), book):
        surveys = _book_surveys(book, archived=request.args.get('archived') in ('1', 'true'))
        return _reply({'book': _book_json(book), 'surveys': [_summary(s) for s in surveys]})
    listed = []
    for survey in _book_surveys(book):
        latest = _latest(survey)
        if latest is not None:
            listed.append(_reader_summary(survey, latest))
    return _reply({'book': _book_json(book), 'surveys': listed})


@survey_bp.route('/api/survey-books/<book_uuid>', methods=['PATCH'])
@signed_in
def update_book(book_uuid):
    book = _readable_book(book_uuid)
    if book is None:
        return _not_found('book')
    if not access.can_edit(_me(), book):
        return _not_editor()
    refused = _precondition(book, _book_json)
    if refused:
        return refused
    data = _body()
    try:
        if 'name' in data:
            book.name = _clean_text(data['name'], MAX_BOOK_NAME, 'The name', required=True)
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
            known = {s.uuid for s in LzSurvey.query.filter(LzSurvey.uuid.in_(order), LzSurvey.book_id == book.id).all()} if order else set()
            if known != set(order):
                raise ValueError('page_order names a survey that is not in the book')
            book.page_order = order
        if 'team_id' in data:
            team_id = _clean_team(data['team_id'])
            if team_id != book.team_id:
                # Only a site admin gives a book to another team, or to none: that changes who reads it.
                if not is_admin(_me()):
                    db.session.rollback()
                    return _error("Only a site admin can give a book to another team.", 403, 'not_editor')
                if team_id is not None and db.session.get(Team, team_id) is None:
                    raise ValueError('There is no such team')
                book.team_id = team_id
    except ValueError as problem:
        db.session.rollback()
        return _error(str(problem), 400, 'invalid_book')
    book.revision += 1
    book.updated_by_name = _who()
    db.session.commit()
    return _reply(_book_json(book))


@survey_bp.route('/api/survey-books/<book_uuid>/archive', methods=['POST'])
@signed_in
def archive_book(book_uuid):
    book = _readable_book(book_uuid)
    if book is None:
        return _not_found('book')
    if not access.can_edit(_me(), book):
        return _not_editor()
    if book.archived_at is None:
        book.archived_at = datetime.utcnow()
        book.revision += 1
        book.updated_by_name = _who()
        db.session.commit()
    return _reply(_book_json(book))


@survey_bp.route('/api/survey-books/<book_uuid>/restore', methods=['POST'])
@signed_in
def restore_book(book_uuid):
    book = _book_by_uuid(book_uuid)
    # An archived book is a site admin's alone, until one restores it.
    if book is None or not is_admin(_me()):
        return _not_found('book')
    if book.archived_at is not None:
        book.archived_at = None
        book.revision += 1
        book.updated_by_name = _who()
        db.session.commit()
    return _reply(_book_json(book))


# -- Surveys -----------------------------------------------------------------------

def _survey(survey_uuid):
    return LzSurvey.query.filter_by(uuid=survey_uuid).first()


def _book_of(survey):
    return db.session.get(SurveyBook, survey.book_id) if survey is not None else None


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


def _latest_number(survey):
    latest = db.session.query(db.func.max(LzSurveyRevision.number)).filter(LzSurveyRevision.survey_id == survey.id).scalar()
    return int(latest) if latest else None


def _revisions(survey):
    return LzSurveyRevision.query.filter_by(survey_id=survey.id).order_by(LzSurveyRevision.number).all()


def _latest(survey):
    return LzSurveyRevision.query.filter_by(survey_id=survey.id).order_by(LzSurveyRevision.number.desc()).first()


def _revision(survey, number):
    return LzSurveyRevision.query.filter_by(survey_id=survey.id, number=number).first()


def _revision_json(revision):
    page = revision.page or {}
    return {
        'number': revision.number,
        'published_at': _iso(revision.published_at),
        'published_by': revision.published_by_name,
        'signoff': revision.signoff,
        'changes': revision.changes,
        'hlz_id': page.get('hlzId'),
        'name': page.get('name'),
        'printed': bool(revision.pdf_sha256),
    }


def _summary(survey):
    snapshot = survey.snapshot or {}
    return {
        'uuid': survey.uuid,
        'hlz_id': survey.hlz_id,
        'name': survey.name,
        'status': survey.status,
        'revision': survey.revision,
        'disagreements': compare.open_count(survey.field, survey.snapshot, survey.overrides),
        'surveyed_on': (survey.field or {}).get('surveyedOn') or '',
        'grid': snapshot.get('grid') or (survey.field or {}).get('grid') or '',
        'measured_at': snapshot.get('measuredAt'),
        'published_revision': _latest_number(survey),
        'updated_by': survey.updated_by_name,
        'updated_at': _iso(survey.updated_at),
        'archived': survey.archived_at is not None,
    }


def _reader_summary(survey, latest):
    """A published survey as a reader sees it: its latest revision, never the draft that may be under way."""
    page = latest.page or {}
    return {
        'uuid': survey.uuid,
        'hlz_id': page.get('hlzId', survey.hlz_id),
        'name': page.get('name', survey.name),
        'status': 'published',
        'published_revision': latest.number,
        'published_at': _iso(latest.published_at),
        'signoff': latest.signoff,
        'changes': latest.changes,
    }


def _full(survey):
    asset_rows = _assets(survey)
    revisions = _revisions(survey)
    book = _book_of(survey)
    return {
        **_summary(survey),
        'book': {'uuid': book.uuid, 'name': book.name} if book else None,
        'revisions': [_revision_json(r) for r in revisions],
        'next_revision': (revisions[-1].number if revisions else 0) + 1,
        'suggested_changes': _suggested_changes(survey, revisions[-1] if revisions else None),
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


def _editable(survey_uuid):
    """(survey, book, None) for a survey this person edits, else (None, None, the refusal)."""
    survey = _survey(survey_uuid)
    book = _book_of(survey)
    user = _me()
    if survey is None or book is None or not (access.can_edit(user, book) or access.can_read(user, book)):
        return None, None, _not_found()
    if not access.can_edit(user, book):
        return None, None, _not_editor()
    return survey, book, None


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
    try:
        # NaN and Infinity read as JSON in Python but are not JSON, and Postgres refuses them.
        size = len(json.dumps(value, allow_nan=False))
    except ValueError:
        raise ValueError('The diagram holds a number that is not finite')
    if size > MAX_DIAGRAM_BYTES:
        raise ValueError('The diagram is too large to keep with a survey')
    return value


def _stamp(survey):
    survey.revision = (survey.revision or 0) + 1
    survey.updated_by = _me().id
    survey.updated_by_name = _who()


def _touch(survey):
    _stamp(survey)
    # Any change to a survey under review or published makes it a draft again; the last published revision stays what readers see.
    if survey.status != 'draft':
        survey.status = 'draft'


@survey_bp.route('/api/surveys', methods=['POST'])
@signed_in
def create_survey():
    data = _body()
    if not data.get('book'):
        return _error('Say which book the survey is for.', 400, 'book_required')
    book = _readable_book(data.get('book'))
    if book is None:
        return _not_found('book')
    if not access.can_edit(_me(), book):
        return _not_editor()
    try:
        name = _clean_text(data.get('name'), MAX_NAME, 'The name', required=True)
        hlz_id = _clean_text(data.get('hlz_id'), MAX_HLZ_ID, 'The HLZ ID')
        field = fields.clean_field(data.get('field') or {})
        diagram = _clean_diagram(data.get('diagram'))
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_survey')
    user = _me()
    survey = LzSurvey(
        uuid=str(uuid_lib.uuid4()), book_id=book.id, hlz_id=hlz_id, name=name, status='draft', revision=1,
        field=field, overrides={}, diagram=diagram, snapshot=None,
        created_by=user.id, created_by_name=_who(), updated_by=user.id, updated_by_name=_who(),
    )
    db.session.add(survey)
    book.page_order = list(book.page_order or []) + [survey.uuid]
    book.revision += 1
    db.session.commit()
    return _reply(_full(survey), 201)


@survey_bp.route('/api/surveys/<survey_uuid>', methods=['GET'])
@signed_in
def get_survey(survey_uuid):
    survey = _survey(survey_uuid)
    book = _book_of(survey)
    user = _me()
    if survey is not None and access.can_edit(user, book):
        return _reply(_full(survey))
    latest = _latest(survey) if survey is not None and survey.archived_at is None and access.can_read(user, book) else None
    if latest is None:
        return _not_found()
    return _reply({**_reader_summary(survey, latest), 'book': {'uuid': book.uuid, 'name': book.name},
                   'revisions': [_revision_json(r) for r in _revisions(survey)]})


@survey_bp.route('/api/surveys/<survey_uuid>', methods=['PATCH'])
@signed_in
def update_survey(survey_uuid):
    survey, book, refused = _editable(survey_uuid)
    if refused:
        return refused
    refused = _precondition(survey, _full)
    if refused:
        return refused
    if survey.archived_at is not None:
        return _error('This survey is archived: restore it to change it.', 409, 'archived')
    data = _body()
    try:
        name = _clean_text(data['name'], MAX_NAME, 'The name', required=True) if 'name' in data else survey.name
        hlz_id = _clean_text(data['hlz_id'], MAX_HLZ_ID, 'The HLZ ID') if 'hlz_id' in data else survey.hlz_id
        field = fields.clean_field(data['field']) if 'field' in data else survey.field
        diagram = _clean_diagram(data['diagram']) if 'diagram' in data else survey.diagram
        # Overrides are judged against the field values this same change leaves.
        overrides = (compare.clean_overrides(data['overrides'], survey.overrides, field, survey.snapshot, _who())
                     if 'overrides' in data else survey.overrides)
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_survey')
    target = book
    if 'book' in data and data['book'] != book.uuid:
        # Moved to another book: this person edits both.
        target = _readable_book(data['book'])
        if target is None:
            return _not_found('book')
        if not access.can_edit(_me(), target):
            return _not_editor()
    changed = ((name, hlz_id, field, diagram, overrides) != (survey.name, survey.hlz_id, survey.field, survey.diagram, survey.overrides)
               or target is not book)
    if changed:
        survey.name, survey.hlz_id, survey.field, survey.diagram, survey.overrides = name, hlz_id, field, diagram, overrides
        if target is not book:
            book.page_order = [u for u in book.page_order or [] if u != survey.uuid]
            book.revision += 1
            target.page_order = list(target.page_order or []) + [survey.uuid]
            target.revision += 1
            survey.book_id = target.id
        _touch(survey)
        db.session.commit()
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>/archive', methods=['POST'])
@signed_in
def archive_survey(survey_uuid):
    return _set_archived(survey_uuid, True)


@survey_bp.route('/api/surveys/<survey_uuid>/restore', methods=['POST'])
@signed_in
def restore_survey(survey_uuid):
    return _set_archived(survey_uuid, False)


def _set_archived(survey_uuid, archived):
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    if (survey.archived_at is not None) != archived:
        survey.archived_at = datetime.utcnow() if archived else None
        _stamp(survey)
        db.session.commit()
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>/measure', methods=['POST'])
@signed_in
@terrain_tiles.pauses_warming
def measure_survey(survey_uuid):
    """Measure the survey's saved diagram. Without ``apply`` nothing is stored: the answer is the new snapshot and what it would change.
    With ``apply`` (and If-Match) the survey takes it."""
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    apply = bool(_body().get('apply'))
    if apply:
        refused = _precondition(survey, _full)
        if refused:
            return refused
        if survey.archived_at is not None:
            return _error('This survey is archived: restore it to change it.', 409, 'archived')
    try:
        snapshot = measure.measure(survey.diagram, survey.field, _who())
    except measure.CannotMeasure as problem:
        return _error(str(problem), 400, problem.code)
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


# -- Review and publishing ------------------------------------------------------------

def _status_change(survey_uuid, allowed, to, refusal):
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    refused = _precondition(survey, _full)
    if refused:
        return refused
    if survey.archived_at is not None:
        return _error('This survey is archived: restore it to change it.', 409, 'archived')
    if survey.status not in allowed:
        return _error(refusal, 409, 'invalid_status')
    if to == 'in_review' and not survey.snapshot:
        return _error('Measure the LZ before sending its survey for review.', 409, 'not_measured')
    survey.status = to
    _stamp(survey)
    db.session.commit()
    return _reply(_full(survey))


@survey_bp.route('/api/surveys/<survey_uuid>/submit', methods=['POST'])
@signed_in
def submit_survey(survey_uuid):
    """A draft goes for review: the page as it stands is the one to be signed off."""
    return _status_change(survey_uuid, ('draft',), 'in_review', 'Only a draft is sent for review.')


@survey_bp.route('/api/surveys/<survey_uuid>/draft', methods=['POST'])
@signed_in
def back_to_draft(survey_uuid):
    return _status_change(survey_uuid, ('in_review',), 'draft', 'Only a survey under review goes back to being a draft.')


def _clean_signoff(value):
    if not isinstance(value, dict):
        raise ValueError("Say who signed it off: the approver's name, rank, title and the date.")
    return {
        'name': _clean_text(value.get('name'), 120, "The approver's name", required=True),
        'rank': _clean_text(value.get('rank'), 20, "The approver's rank"),
        'title': _clean_text(value.get('title'), 120, "The approver's title"),
        'date': _clean_text(value.get('date'), 40, 'The date it was signed off', required=True),
    }


def _opacity(value, default=55):
    try:
        number = int(round(float(value)))
    except (TypeError, ValueError):
        return default
    return max(10, min(100, number))


@survey_bp.route('/api/surveys/<survey_uuid>/publish', methods=['POST'])
@signed_in
@terrain_tiles.pauses_warming
def publish_survey(survey_uuid):
    """A survey under review is signed off and published (§3.4): a new revision, REV n, keeps everything the page is made from, with
    the sign-off and the CHANGES line, and its PDF is printed once, now where the server can, else the first time it is asked for."""
    survey, book, refused = _editable(survey_uuid)
    if refused:
        return refused
    refused = _precondition(survey, _full)
    if refused:
        return refused
    if survey.archived_at is not None:
        return _error('This survey is archived: restore it to change it.', 409, 'archived')
    if survey.status != 'in_review':
        return _error('Send the survey for review before it is published.', 409, 'invalid_status')
    if not survey.snapshot:
        return _error('Measure the LZ before publishing its survey.', 409, 'not_measured')
    data = _body()
    previous = _latest(survey)
    try:
        signoff = _clean_signoff(data.get('signoff'))
        typed = _clean_text(data.get('changes'), change_lines.MAX_CHANGES, 'The CHANGES line')
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_publish')
    now = datetime.utcnow()
    position = _position(survey, book)
    revision = LzSurveyRevision(
        survey_id=survey.id, number=(previous.number if previous else 0) + 1,
        field=dict(survey.field or {}), overrides=dict(survey.overrides or {}), diagram=survey.diagram, snapshot=survey.snapshot,
        asset_ids=[a.id for a in _assets(survey)], signoff=signoff,
        changes=typed or _suggested_changes(survey, previous),
        page={
            'hlzId': survey.hlz_id, 'name': survey.name, 'bookName': book.name, 'unitLine': book.unit_line,
            'pageN': position['n'] if position else None, 'pageOf': position['of'] if position else None,
            'printedOn': now.date().isoformat(), 'version': __version__, 'opacity': _opacity(data.get('opacity')),
        },
        published_by=_me().id, published_by_name=_who(), published_at=now,
    )
    db.session.add(revision)
    survey.status = 'published'
    _stamp(survey)
    try:
        db.session.commit()
    except IntegrityError:
        # Someone published the same survey a moment before: the If-Match should have caught it, the unique number does.
        db.session.rollback()
        return _error('Someone published this survey a moment ago. Nothing was overwritten.', 409, 'revision_conflict',
                      server=_full(_survey(survey_uuid)))
    if gotenberg.url():
        try:
            _keep_pdf(survey, revision)
        except (gotenberg.Unavailable, gotenberg.Failed) as problem:
            log.warning('surveys: REV %s of %s will be printed when first asked for: %s', revision.number, survey.uuid, problem)
    return _reply({'survey': _full(survey), 'revision': _revision_json(revision)}, 201)


# -- The page -----------------------------------------------------------------------

def _position(survey, book):
    uuids = [s.uuid for s in _book_surveys(book)]
    return {'n': uuids.index(survey.uuid) + 1, 'of': len(uuids)} if survey.uuid in uuids else None


def _asset_source(rows):
    return [{'id': a.id, 'kind': a.kind, 'caption': a.caption, 'heading_deg': a.heading_deg, 'taken_on': a.taken_on} for a in rows]


def _current_source(survey):
    return {
        'hlzId': survey.hlz_id, 'name': survey.name, 'status': survey.status, 'nextRevision': (_latest_number(survey) or 0) + 1,
        'field': survey.field or {}, 'overrides': survey.overrides or {}, 'diagram': survey.diagram, 'snapshot': survey.snapshot,
        'assets': _asset_source(_assets(survey)), 'signoff': None, 'changes': '',
    }


def _revision_source(survey, revision):
    page = revision.page or {}
    ids = [i for i in revision.asset_ids or [] if isinstance(i, int)]
    rows = LzSurveyAsset.query.filter(LzSurveyAsset.survey_id == survey.id, LzSurveyAsset.id.in_(ids)).all() if ids else []
    by_id = {a.id: a for a in rows}
    return {
        'hlzId': page.get('hlzId', survey.hlz_id), 'name': page.get('name', survey.name), 'status': 'published',
        'revision': revision.number, 'field': revision.field or {}, 'overrides': revision.overrides or {},
        'diagram': revision.diagram, 'snapshot': revision.snapshot,
        'assets': _asset_source([by_id[i] for i in ids if i in by_id]),
        'signoff': revision.signoff, 'changes': revision.changes,
    }


def _suggested_changes(survey, previous):
    current = _current_source(survey)
    if previous is None:
        return change_lines.first(current['field'], current['snapshot'], current['overrides'])
    book = _book_of(survey)
    return change_lines.later(_revision_source(survey, previous), current, {'name': book.name if book else '', 'unitLine': ''})


def _page_model(survey, revision=None, opacity=None):
    book = _book_of(survey)
    if revision is not None:
        page = revision.page or {}
        return compile_page(
            _revision_source(survey, revision),
            book={'name': page.get('bookName') or book.name, 'unitLine': page.get('unitLine') or ''},
            page={'n': page['pageN'], 'of': page.get('pageOf')} if isinstance(page.get('pageN'), int) else None,
            version=page.get('version') or __version__,
            printed_on=as_date(page.get('printedOn')) or revision.published_at,
            opacity=_opacity(page.get('opacity')),
        )
    return compile_page(_current_source(survey), book={'name': book.name, 'unitLine': book.unit_line},
                        page=_position(survey, book), version=__version__, opacity=_opacity(opacity))


def _asset_reader(survey):
    def read(asset_id):
        asset = LzSurveyAsset.query.filter_by(id=asset_id, survey_id=survey.id).first()
        if asset is None:
            return None
        data = asset_store.store().get(asset.sha256)
        return (data, asset.content_type) if data else None
    return read


def _keep_pdf(survey, revision):
    """A revision's PDF, printed once and kept in the asset store; the one kept when there is one."""
    if revision.pdf_sha256:
        kept = asset_store.store().get(revision.pdf_sha256)
        if kept:
            return kept
    pdf = pages.print_pdf(_page_model(survey, revision), _asset_reader(survey))
    key = hashlib.sha256(pdf).hexdigest()
    asset_store.store().put(key, pdf)
    revision.pdf_sha256 = key
    db.session.commit()
    return pdf


def _which_page(survey_uuid):
    """(survey, revision or None, None) for the page a request asks for, else (None, None, the refusal). An editor gets the survey as it
    is now unless ``?rev=n`` names a revision; a reader gets the latest published revision, or the one named."""
    survey = _survey(survey_uuid)
    book = _book_of(survey)
    user = _me()
    editing = survey is not None and access.can_edit(user, book)
    reading = survey is not None and survey.archived_at is None and access.can_read(user, book)
    if not (editing or reading):
        return None, None, _not_found()
    raw = request.args.get('rev')
    if raw not in (None, ''):
        if not raw.isdigit():
            return None, None, _error('rev is a revision number.', 400, 'invalid_revision')
        revision = _revision(survey, int(raw))
        if revision is None:
            return None, None, _error('There is no such revision.', 404, 'not_found')
        return survey, revision, None
    if editing:
        return survey, None, None
    latest = _latest(survey)
    if latest is None:
        return None, None, _not_found()
    return survey, latest, None


def _file_name(survey, revision):
    page = (revision.page or {}) if revision else {}
    words = ' '.join(t for t in (page.get('hlzId', survey.hlz_id), page.get('name', survey.name),
                                 f'REV {revision.number}' if revision else survey.status.replace('_', ' ').upper()) if t)
    return (re.sub(r'[^A-Za-z0-9 _.-]+', '', words).strip() or 'survey') + '.pdf'


@survey_bp.route('/api/surveys/<survey_uuid>/pdf', methods=['GET'])
@signed_in
@terrain_tiles.pauses_warming
def survey_pdf(survey_uuid):
    """The page as a PDF: a published revision's as it was printed for it, or (for an editor) the survey as it is now, marked DRAFT or
    IN REVIEW and kept nowhere. 503 where the server has no Gotenberg: the preview (``/page``) shows the page without it."""
    survey, revision, refused = _which_page(survey_uuid)
    if refused:
        return refused
    if not gotenberg.url():
        return _error('The PDF is not available on this server. The page preview shows it.', 503, 'pdf_unavailable')
    try:
        pdf = _keep_pdf(survey, revision) if revision is not None else pages.print_pdf(
            _page_model(survey, None, request.args.get('opacity')), _asset_reader(survey))
    except gotenberg.Unavailable:
        return _error('The PDF is not available on this server. The page preview shows it.', 503, 'pdf_unavailable')
    except gotenberg.Failed:
        return _error('The PDF could not be made. Try again in a moment.', 502, 'pdf_failed')
    response = send_file(io.BytesIO(pdf), mimetype='application/pdf', as_attachment=request.args.get('download') == '1',
                         download_name=_file_name(survey, revision))
    response.headers['Cache-Control'] = 'private, no-store'
    return response


@survey_bp.route('/api/surveys/<survey_uuid>/page', methods=['GET'])
@signed_in
@terrain_tiles.pauses_warming
def survey_page(survey_uuid):
    """The page as one HTML document with its pictures in it, for the editor's and a reader's preview: what the PDF prints, without
    Gotenberg. It runs nothing and fetches nothing (``Content-Security-Policy``)."""
    survey, revision, refused = _which_page(survey_uuid)
    if refused:
        return refused
    html = pages.preview_html(_page_model(survey, revision, request.args.get('opacity')), _asset_reader(survey))
    response = make_response(html)
    response.headers['Content-Type'] = 'text/html; charset=utf-8'
    response.headers['Content-Security-Policy'] = PREVIEW_POLICY
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Cache-Control'] = 'private, no-store'
    return response


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
@signed_in
def upload_asset(survey_uuid):
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
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
        size_bytes=len(prepared.data), sha256=key, created_by=user.id, created_by_name=_who(),
    )
    db.session.add(asset)
    db.session.commit()
    return _reply(_asset_json(asset, survey.uuid), 201)


def _asset(survey, asset_id):
    return LzSurveyAsset.query.filter_by(id=asset_id, survey_id=survey.id).first()


@survey_bp.route('/api/surveys/<survey_uuid>/assets/<int:asset_id>', methods=['GET'])
@signed_in
def get_asset(survey_uuid, asset_id):
    # A removed asset is still served: a published revision may show it.
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    asset = _asset(survey, asset_id)
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
@signed_in
def update_asset(survey_uuid, asset_id):
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    asset = _asset(survey, asset_id)
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
@signed_in
def remove_asset(survey_uuid, asset_id):
    """Takes the picture off the survey. It is kept (and its bytes): a published revision may show it."""
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    asset = _asset(survey, asset_id)
    if asset is None:
        return _error('There is no such picture.', 404, 'not_found')
    if asset.removed_at is None:
        asset.removed_at = datetime.utcnow()
        db.session.commit()
    return _reply({'removed': asset.id})
