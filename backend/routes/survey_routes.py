"""LZ surveys and the books they are kept in (docs/LZ_SURVEYS.md): ``/api/survey-books`` and ``/api/surveys``.

Web only, so these routes are not in contracts/openapi.yaml and nothing syncs. Who may do what is ``survey/access.py`` (§7): site admins
everything; with ``lz_surveys``, a team's owner and admins edit that team's books, its members read their published pages, and
everyone reads those of a book for no team. A draft is an editor's alone. It is checked here, on every route: the web hiding what a
person may not do is not what holds. A book or survey a person cannot read is "not found", so its existence is not told either.
No answer is cached by anything between (``Cache-Control: private``, AGENTS.md §14).

A save names the revision it was made on (``If-Match: "3"``): two people editing one survey or book get a 409 on the older one, with
the server's copy, and nothing is overwritten. A survey's revision is checked again as it is written (LzSurvey's version counter), so a
save that lands while another request measures or publishes is not undone by it; a book's writers take its row first. A book and a
survey are archived, never deleted; so is a photo taken off a survey.
"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import math
import re
import uuid as uuid_lib
from datetime import datetime
from functools import wraps

from flask import Blueprint, current_app, g, jsonify, make_response, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm.exc import StaleDataError
from werkzeug.exceptions import RequestEntityTooLarge

import terrain_tiles
from brief import gotenberg
from entitlements import is_admin
from models import LzSurvey, LzSurveyAsset, LzSurveyImport, LzSurveyRevision, SurveyBook, SurveyBookPrint, Team, User, db
from survey import access
from survey import book as book_pdf
from survey import assets as asset_store
from survey import changes as change_lines
from survey import compare, fields, importing, measure, pages
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


def _saved(survey_uuid):
    """Commits a change to a survey: None, or the refusal when another save got there first since this request read it (the revision
    is checked as it is written, not only when the request began)."""
    try:
        db.session.commit()
    except StaleDataError:
        db.session.rollback()
        return _error('Someone saved this since you opened it. Nothing was overwritten.', 409, 'revision_conflict',
                      server=_full(_survey(survey_uuid)))
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

def _book_by_uuid(book_uuid, lock=False):
    """The book, or None. ``lock``: for a request that changes it, which takes its row until it commits, so two writers of one book
    (two surveys made at once, a rename and a new page order) go one after the other rather than one undoing the other."""
    if not isinstance(book_uuid, str) or not book_uuid:
        return None
    query = SurveyBook.query.filter_by(uuid=book_uuid)
    return (query.with_for_update().populate_existing() if lock else query).first()


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
    else:
        # A reader learns nothing of the drafts: the book's revision and its last change move with them. When it last published is theirs.
        for key in ('revision', 'updated_by', 'updated_at'):
            body.pop(key)
        last = (db.session.query(db.func.max(LzSurveyRevision.published_at))
                .join(LzSurvey, LzSurvey.id == LzSurveyRevision.survey_id)
                .filter(LzSurvey.book_id == book.id, LzSurvey.archived_at.is_(None)).scalar())
        body['published_at'] = _iso(last)
    return body


def _readable_book(book_uuid, lock=False):
    """The book a person may read or edit, or None (told as not found)."""
    book = _book_by_uuid(book_uuid, lock)
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
        records = _import_records(surveys)
        importing.MEASURER.resume(current_app._get_current_object(), records.values())
        return _reply({'book': _book_json(book), 'surveys': [_summary(s, records.get(s.id)) for s in surveys]})
    listed = []
    for survey in _book_surveys(book):
        latest = _latest(survey)
        if latest is not None:
            listed.append(_reader_summary(survey, latest))
    return _reply({'book': _book_json(book), 'surveys': listed})


@survey_bp.route('/api/survey-books/<book_uuid>', methods=['PATCH'])
@signed_in
def update_book(book_uuid):
    book = _readable_book(book_uuid, lock=True)
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
    book = _readable_book(book_uuid, lock=True)
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
    book = _book_by_uuid(book_uuid, lock=True)
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

def _import_refusal(book):
    if book is None:
        return _not_found('book')
    if not access.can_edit(_me(), book):
        return _not_editor()
    if book.archived_at is not None:
        return _error('This book is archived: restore it to import into it.', 409, 'book_archived')
    return None


def _import_plan(book, bundle):
    """Which of the bundle's pages to make, and why each other one is left as it is: a page of the same book imported before (so
    importing twice, or again after an answer that was lost, adds nothing), a page the bundle has twice, and an HLZ ID the book has
    already, archived or not, or the bundle has on an earlier page. In page order."""
    surveys = LzSurvey.query.filter_by(book_id=book.id).all()
    taken = {s.hlz_id.upper(): s.archived_at is not None for s in surveys if s.hlz_id}
    done = {(record.source, record.page) for record in
            LzSurveyImport.query.join(LzSurvey, LzSurvey.id == LzSurveyImport.survey_id).filter(LzSurvey.book_id == book.id)}
    make, skipped, pages_seen, ids_seen = [], [], set(), {}

    def skip(page, reason):
        skipped.append({'page': page['page'], 'hlz_id': page['hlzId'], 'name': page['name'], 'reason': reason})

    for page in bundle.pages:
        number, hlz_id = page['page'], page['hlzId']
        if bundle.source and (bundle.source, number) in done:
            skip(page, f'Page {number} of {bundle.source} was imported into this book already: left as it is.')
        elif number in pages_seen:
            skip(page, f'Page {number} is in the bundle twice: imported once.')
        elif hlz_id and hlz_id in taken:
            skip(page, f'{hlz_id} is in the book already, archived: restore it there.' if taken[hlz_id]
                 else f'{hlz_id} is in the book already: left as it is.')
        elif hlz_id and hlz_id in ids_seen:
            skip(page, f'{hlz_id} is on page {ids_seen[hlz_id]} of the bundle too: imported from that page.')
        else:
            make.append(page)
        pages_seen.add(number)
        if hlz_id:
            ids_seen.setdefault(hlz_id, number)
    return make, skipped


def _import_photos(bundle, page, store):
    """The page's photos read one at a time, made ready as any upload is (no metadata) and kept in the store; what is made of each,
    or a warning on the page for one that cannot be used."""
    warnings, kept = page['notes']['warnings'], []
    for photo in page['photos']:
        words = importing.photo_words(photo['kind'])
        try:
            data = bundle.photo(photo)
        except importing.Damaged:
            warnings.append(f'The {words} is damaged in the bundle and was left out.')
            continue
        if data is None:
            warnings.append(f'The {words} is larger than 20 MB and was left out.')
            continue
        try:
            prepared = asset_store.prepare_photo(data)
        except asset_store.AssetError as problem:
            # A picture that cannot be read refuses nothing but itself.
            warnings.append(f'A photo could not be read ({problem}) and was left out.')
            continue
        store.put(prepared.key, prepared.data)
        kept.append({**photo, 'content_type': prepared.content_type, 'width': prepared.width, 'height': prepared.height,
                     'size_bytes': len(prepared.data), 'sha256': prepared.key})
    return kept


@survey_bp.route('/api/survey-books/<book_uuid>/import', methods=['POST'])
@signed_in
def import_pages(book_uuid):
    """The old book's pages as drafts in this book (docs/LZ_SURVEYS.md §6), from the bundle tools/import_survey_book.py writes. A page
    imported into the book before, or whose HLZ ID the book has already, is left out, never written over. Each draft is then
    measured in the background, one at a time.

    Everything slow is done before the book is locked: the upload is read, the bundle checked and every photo made ready and kept,
    one at a time. Only then is the book taken (two imports, or an import and a new page, go one after the other), what to make
    worked out again, and the drafts written."""
    book = _readable_book(book_uuid)
    refused = _import_refusal(book)
    if refused:
        return refused
    limit = importing.MAX_BUNDLE_BYTES + 64 * 1024
    # Refused before the form is read: the file would otherwise be spooled whole first. A request that gives no length (chunked) is
    # held to the same size as it is read.
    if request.content_length is not None and request.content_length > limit:
        return _error(importing.TOO_LARGE, 413, 'bundle_too_large')
    request.max_content_length = limit
    try:
        upload = request.files.get('bundle')
    except RequestEntityTooLarge:
        return _error(importing.TOO_LARGE, 413, 'bundle_too_large')
    if upload is None:
        return _error('Send the bundle as the form\'s "bundle".', 400, 'missing_file')
    store = asset_store.store()
    try:
        with importing.read_bundle(upload.stream.read(importing.MAX_BUNDLE_BYTES + 1)) as bundle:
            make, _skipped = _import_plan(book, bundle)
            # Nothing is held while the photos are made ready: they take seconds, and the database's connection goes back meanwhile.
            db.session.rollback()
            photos = {page['page']: _import_photos(bundle, page, store) for page in make}
    except importing.BundleError as problem:
        return _error(str(problem), 413 if problem.code == 'bundle_too_large' else 400, problem.code)

    book = _readable_book(book_uuid, lock=True)
    refused = _import_refusal(book)
    if refused:
        return refused
    # Another import may have taken some of these pages meanwhile: their photos stay in the store unused. One that was not to be
    # made a moment ago (an HLZ ID let go of meanwhile) has had no photos made ready, and waits for the next import.
    make, skipped = _import_plan(book, bundle)
    for page in [page for page in make if page['page'] not in photos]:
        make.remove(page)
        skipped.append({'page': page['page'], 'hlz_id': page['hlzId'], 'name': page['name'],
                        'reason': 'The book changed while the bundle was read: import it again to take this page.'})
    user, who = _me(), _who()
    made = []
    for page in make:
        place, note = importing.place_of(page['field'])
        warnings = ([note] if note else []) + page['notes']['warnings']
        survey = LzSurvey(
            uuid=str(uuid_lib.uuid4()), book_id=book.id, hlz_id=page['hlzId'], name=page['name'], status='draft', revision=1,
            field=page['field'], overrides={}, diagram=importing.diagram_at(place[0], place[1], page['name']) if place else None,
            snapshot=None, created_by=user.id, created_by_name=who, updated_by=user.id, updated_by_name=who,
        )
        db.session.add(survey)
        db.session.flush()
        for photo in photos.get(page['page'], []):
            db.session.add(LzSurveyAsset(
                survey_id=survey.id, kind=photo['kind'], caption=f"From page {page['page']} of the old book",
                heading_deg=photo['heading_deg'], taken_on=photo['taken_on'], content_type=photo['content_type'],
                width=photo['width'], height=photo['height'], size_bytes=photo['size_bytes'], sha256=photo['sha256'],
                created_by=user.id, created_by_name=who,
            ))
        db.session.add(LzSurveyImport(
            survey_id=survey.id, source=bundle.source, page=page['page'], notes={**page['notes'], 'warnings': warnings},
            state='queued' if place else 'no_target',
            reason=None if place else "Set the LZ's target on the map, then measure it.",
            imported_by=user.id, imported_by_name=who,
        ))
        made.append(survey)
    if made:
        book.page_order = list(book.page_order or []) + [s.uuid for s in made]
        book.revision += 1
        book.updated_by_name = who
    db.session.commit()
    importing.MEASURER.enqueue(current_app._get_current_object(), [s.id for s in made if s.diagram is not None])
    records = _import_records(made)
    return _reply({
        'source': bundle.source,
        'created': [_summary(s, records.get(s.id)) for s in made],
        'skipped': skipped,
        'book': _book_json(book),
    }, 201)


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


_LOOK = object()


def _import_records(surveys):
    """Each survey's import record, by survey id, read in one query."""
    ids = [s.id for s in surveys]
    if not ids:
        return {}
    return {r.survey_id: r for r in LzSurveyImport.query.filter(LzSurveyImport.survey_id.in_(ids)).all()}


def _import_brief(record, survey):
    """How an imported survey stands: where it came from, its measuring (done once it is measured, whoever measured it), and how many
    notes are left to check."""
    if record is None:
        return None
    notes = record.notes or {}
    measured = survey.snapshot is not None
    return {
        'source': record.source,
        'page': record.page,
        'state': 'done' if measured else record.state,
        'reason': None if measured else record.reason,
        'to_check': 0 if record.checked_at else sum(len(notes.get(key) or []) for key in ('unread', 'blank', 'warnings')),
        'checked': record.checked_at is not None,
    }


def _import_full(record, survey):
    if record is None:
        return None
    notes = record.notes or {}
    return {
        **_import_brief(record, survey),
        'notes': {key: list(notes.get(key) or []) for key in ('unread', 'blank', 'warnings')},
        'imported_by': record.imported_by_name,
        'imported_at': _iso(record.imported_at),
        'checked_by': record.checked_by_name,
        'checked_at': _iso(record.checked_at),
    }


def _summary(survey, record=_LOOK):
    snapshot = survey.snapshot or {}
    if record is _LOOK:
        record = LzSurveyImport.query.filter_by(survey_id=survey.id).first()
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
        'imported': _import_brief(record, survey),
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
    record = LzSurveyImport.query.filter_by(survey_id=survey.id).first()
    return {
        **_summary(survey, record),
        'imported': _import_full(record, survey),
        'book': {'uuid': book.uuid, 'name': book.name} if book else None,
        'revisions': [_revision_json(r) for r in revisions],
        'next_revision': (revisions[-1].number if revisions else 0) + 1,
        'suggested_changes': _suggested_changes(survey, revisions[-1] if revisions else None),
        'field': survey.field or {},
        'overrides': survey.overrides or {},
        'diagram': survey.diagram,
        'snapshot': survey.snapshot,
        'stale': measure.stale(survey.snapshot, survey.diagram, survey.field),
        'comparisons': compare.compare(survey.field, survey.snapshot, survey.overrides),
        'asset_ids': [a.id for a in asset_rows],
        'assets': [_asset_json(a, survey.uuid) for a in asset_rows],
        'created_by': survey.created_by_name,
        'created_at': _iso(survey.created_at),
    }


def _editable(survey_uuid):
    """(survey, book, None) for a survey this person edits, else (None, None, the refusal): "only editors" to a reader of its published
    page, "not found" to anyone else, so a draft or an archived survey is not told to exist."""
    survey = _survey(survey_uuid)
    book = _book_of(survey)
    user = _me()
    if survey is not None and book is not None and access.can_edit(user, book):
        return survey, book, None
    if (survey is not None and book is not None and survey.archived_at is None and access.can_read(user, book)
            and _latest_number(survey)):
        return None, None, _not_editor()
    return None, None, _not_found()


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
    book = _readable_book(data.get('book'), lock=True)
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
        # Both books' page orders change: their rows are taken, in one order, so a move each way at once cannot wait on each other.
        (SurveyBook.query.filter(SurveyBook.id.in_([book.id, target.id])).order_by(SurveyBook.id)
         .with_for_update().populate_existing().all())
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
        refused = _saved(survey_uuid)
        if refused:
            return refused
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
        refused = _saved(survey_uuid)
        if refused:
            return refused
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
        # Measuring takes seconds: a save that landed meanwhile wins, and this measurement (of the copy before it) is not kept.
        refused = _saved(survey_uuid)
        if refused:
            return refused
    return _reply({
        'applied': apply,
        'snapshot': snapshot,
        'changes': changes,
        'comparisons': compare.compare(survey.field, snapshot, survey.overrides),
        'survey': _full(survey) if apply else None,
    })


@survey_bp.route('/api/surveys/<survey_uuid>/import/checked', methods=['POST'])
@signed_in
def import_checked(survey_uuid):
    """The import's notes on a survey have been looked at. They are kept, and the book's page no longer counts them. Not a change to
    the survey: its revision and status stay."""
    survey, _book, refused = _editable(survey_uuid)
    if refused:
        return refused
    record = LzSurveyImport.query.filter_by(survey_id=survey.id).first()
    if record is None:
        return _error('This survey was not imported.', 404, 'not_imported')
    if record.checked_at is None:
        record.checked_at = datetime.utcnow()
        record.checked_by_name = _who()
        db.session.commit()
    return _reply(_full(survey))


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
    if to == 'in_review':
        refused = _stale_refusal(survey, 'sending it for review')
        if refused:
            return refused
    survey.status = to
    _stamp(survey)
    refused = _saved(survey_uuid)
    if refused:
        return refused
    return _reply(_full(survey))


def _stale_refusal(survey, doing):
    """The refusal for a survey whose snapshot it has moved on from (the target moved, the boundary or best approach changed): its
    grid, size and slope would print beside a plan and an approach they were not measured for."""
    reasons = measure.stale(survey.snapshot, survey.diagram, survey.field)
    if not reasons:
        return None
    return _error(f'Measure the LZ again before {doing}. ' + ' '.join(reasons), 409, 'measure_again', reasons=reasons)


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
        number = float(value)
    except (TypeError, ValueError):
        return default
    if not math.isfinite(number):
        return default
    return max(10, min(100, int(round(number))))


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
    refused = _stale_refusal(survey, 'publishing it')
    if refused:
        return refused
    data = _body()
    previous = _latest(survey)
    try:
        signoff = _clean_signoff(data.get('signoff'))
        typed = _clean_text(data.get('changes'), change_lines.MAX_CHANGES, 'The CHANGES line')
    except ValueError as problem:
        return _error(str(problem), 400, 'invalid_publish')
    now = datetime.utcnow()
    position = _position(survey, book)
    asset_rows = _assets(survey)
    revision = LzSurveyRevision(
        survey_id=survey.id, number=(previous.number if previous else 0) + 1,
        field=dict(survey.field or {}), overrides=dict(survey.overrides or {}), diagram=survey.diagram, snapshot=survey.snapshot,
        asset_ids=[a.id for a in asset_rows], signoff=signoff,
        changes=typed or _suggested_changes(survey, previous),
        page={
            'hlzId': survey.hlz_id, 'name': survey.name, 'bookName': book.name, 'unitLine': book.unit_line,
            'pageN': position['n'] if position else None, 'pageOf': position['of'] if position else None,
            'printedOn': now.date().isoformat(), 'version': __version__, 'opacity': _opacity(data.get('opacity')),
            # The pictures as they were signed: what each is, its caption, heading and date. A photo changed later is the next
            # revision's; this one's page never moves (its bytes are named by their hash and never change).
            'assets': _asset_source(asset_rows),
        },
        published_by=_me().id, published_by_name=_who(), published_at=now,
    )
    db.session.add(revision)
    survey.status = 'published'
    _stamp(survey)
    try:
        db.session.commit()
    except (IntegrityError, StaleDataError):
        # Someone published or saved the same survey a moment before: its revision, checked as it is written, or the unique number
        # caught it.
        db.session.rollback()
        return _error('Someone changed or published this survey a moment ago. Nothing was overwritten.', 409, 'revision_conflict',
                      server=_full(_survey(survey_uuid)))
    if gotenberg.url():
        try:
            _keep_pdf(survey, revision)
        except (gotenberg.Unavailable, gotenberg.Failed) as problem:
            log.warning('surveys: REV %s of %s will be printed when first asked for: %s', revision.number, survey.uuid, problem)
        except Exception:  # noqa: BLE001 - it is published: its PDF is printed when first asked for
            db.session.rollback()
            log.exception('surveys: REV %s of %s could not be printed now', revision.number, survey.uuid)
    return _reply({'survey': _full(survey), 'revision': _revision_json(revision)}, 201)


# -- The page -----------------------------------------------------------------------

def _position(survey, book):
    uuids = [s.uuid for s in _book_surveys(book)]
    return {'n': uuids.index(survey.uuid) + 1, 'of': len(uuids)} if survey.uuid in uuids else None


def _asset_source(rows):
    return [{'id': a.id, 'kind': a.kind, 'caption': a.caption, 'heading_deg': a.heading_deg, 'taken_on': a.taken_on} for a in rows]


def _frozen_assets(value):
    """A revision's pictures as it kept them, or None for a revision published before it kept them."""
    if not isinstance(value, list):
        return None
    return [dict(a) for a in value if isinstance(a, dict) and isinstance(a.get('id'), int) and not isinstance(a.get('id'), bool)]


def _current_source(survey):
    return {
        'hlzId': survey.hlz_id, 'name': survey.name, 'status': survey.status, 'nextRevision': (_latest_number(survey) or 0) + 1,
        'field': survey.field or {}, 'overrides': survey.overrides or {}, 'diagram': survey.diagram, 'snapshot': survey.snapshot,
        'assets': _asset_source(_assets(survey)), 'signoff': None, 'changes': '',
    }


def _revision_source(survey, revision):
    page = revision.page or {}
    assets = _frozen_assets(page.get('assets'))
    if assets is None:
        ids = [i for i in revision.asset_ids or [] if isinstance(i, int)]
        rows = LzSurveyAsset.query.filter(LzSurveyAsset.survey_id == survey.id, LzSurveyAsset.id.in_(ids)).all() if ids else []
        by_id = {a.id: a for a in rows}
        assets = _asset_source([by_id[i] for i in ids if i in by_id])
    return {
        'hlzId': page.get('hlzId', survey.hlz_id), 'name': page.get('name', survey.name), 'status': 'published',
        'revision': revision.number, 'field': revision.field or {}, 'overrides': revision.overrides or {},
        'diagram': revision.diagram, 'snapshot': revision.snapshot,
        'assets': assets,
        'signoff': revision.signoff, 'changes': revision.changes,
    }


def _suggested_changes(survey, previous):
    """The CHANGES line to offer at publishing. A suggestion: one that cannot be worked out (a page that will not compile) is none, and
    never stops the survey opening."""
    try:
        current = _current_source(survey)
        if previous is None:
            return change_lines.first(current['field'], current['snapshot'], current['overrides'])
        book = _book_of(survey)
        return change_lines.later(_revision_source(survey, previous), current, {'name': book.name if book else '', 'unitLine': ''})
    except Exception:  # noqa: BLE001
        log.exception('surveys: no CHANGES line could be suggested for %s', survey.uuid)
        return ''


def _page_model(survey, revision=None, opacity=None, page=None, in_book=None):
    """The page's model: a published revision's as it was signed, else the survey's as it is now. In the whole book's PDF, ``page``
    numbers it and ``in_book`` (``book.cover_of``) names the book, in place of the place and the book it was published in."""
    book = _book_of(survey)
    if revision is not None:
        stored = revision.page or {}
        named = ({'name': in_book['name'], 'unitLine': in_book['unitLine']} if in_book is not None
                 else {'name': stored.get('bookName') or book.name, 'unitLine': stored.get('unitLine') or ''})
        return compile_page(
            _revision_source(survey, revision),
            book=named,
            page=page or ({'n': stored['pageN'], 'of': stored.get('pageOf')} if isinstance(stored.get('pageN'), int) else None),
            version=stored.get('version') or __version__,
            printed_on=as_date(stored.get('printedOn')) or revision.published_at,
            opacity=_opacity(stored.get('opacity')),
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
        if not re.fullmatch(r'[0-9]{1,6}', raw):
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


# -- The whole book's PDF (L6) --------------------------------------------------

def _published(book):
    """The book's published surveys in its order, each with its latest revision: what its PDF holds."""
    entries = []
    for survey in _book_surveys(book):
        latest = _latest(survey)
        if latest is not None:
            entries.append((survey, latest))
    return entries


def _print_for(book, key):
    """The print that answers for the book as it stands (``key``, its fingerprint): one printed whose file is there, else one on its
    way, else the last that failed. A print whose file has gone (a redeploy with no SURVEY_ASSETS_DIR, a restore) answers for
    nothing, so the book can be printed again; nor does a newer print that failed hide one that was printed."""
    store = asset_store.store()
    rows = SurveyBookPrint.query.filter_by(book_id=book.id, fingerprint=key).order_by(SurveyBookPrint.id.desc()).all()
    return (next((r for r in rows if r.state == 'ready' and r.pdf_sha256 and store.has(r.pdf_sha256)), None)
            or next((r for r in rows if r.state == 'printing'), None)
            or next((r for r in rows if r.state == 'failed'), None))


def _print_json(row, entries):
    ready = row is not None and row.state == 'ready'
    return {
        'state': 'empty' if not entries else row.state if row is not None else 'none',
        'pages': len(entries),
        'done_pages': row.done_pages if row is not None and row.state == 'printing' else 0,
        'printed_at': _iso(row.finished_at) if ready else None,
        'size_bytes': row.size_bytes if ready else None,
        'error': row.error if row is not None and row.state == 'failed' else None,
        'available': bool(gotenberg.url()),
    }


@survey_bp.route('/api/survey-books/<book_uuid>/print', methods=['GET', 'POST'])
@signed_in
def book_print(book_uuid):
    """The book's PDF: how it stands (GET), or printed if it is not (POST). Everyone who reads the book may print it: it holds only its
    published pages, each its latest revision. Printing is done in the background (survey/book.py) and the PDF kept until a page in it
    changes; asking again meanwhile changes nothing."""
    book = _readable_book(book_uuid, lock=request.method == 'POST')
    if book is None:
        return _not_found('book')
    entries = _published(book)
    key = book_pdf.fingerprint(book_pdf.cover_of(book), entries, __version__)
    row = _print_for(book, key)
    app = current_app._get_current_object()
    if request.method == 'POST':
        if book.archived_at is not None:
            return _error('This book is archived: restore it to print it.', 409, 'book_archived')
        if not entries:
            return _error('Nothing in the book is published yet: its PDF holds the published pages.', 409, 'nothing_published')
        if not gotenberg.url():
            return _error('The PDF is not available on this server.', 503, 'pdf_unavailable')
        if row is None or row.state == 'failed':
            row = SurveyBookPrint(book_id=book.id, fingerprint=key, state='printing', pages=len(entries), done_pages=0,
                                  requested_by=_me().id, requested_by_name=_who(), started_at=datetime.utcnow())
            db.session.add(row)
            db.session.commit()
    if row is not None and row.state == 'printing':
        # Queued again when nothing holds it: after a restart its thread is gone.
        book_pdf.PRINTER.resume(app, row.id, _print_book)
    status = 202 if request.method == 'POST' and row is not None and row.state == 'printing' else 200
    return _reply(_print_json(row, entries), status)


@survey_bp.route('/api/survey-books/<book_uuid>/pdf', methods=['GET'])
@signed_in
def book_pdf_file(book_uuid):
    """The book's PDF as it was printed for the book as it stands; 409 ``not_printed`` until it is. Sent from the disk where the store
    keeps it there, never read whole into memory (a book is tens of megabytes)."""
    book = _readable_book(book_uuid)
    if book is None:
        return _not_found('book')
    entries = _published(book)
    row = _print_for(book, book_pdf.fingerprint(book_pdf.cover_of(book), entries, __version__))
    store = asset_store.store()
    if row is None or row.state != 'ready':
        return _error('The book has not been printed as it stands: print it first.', 409, 'not_printed')
    words = re.sub(r'[^A-Za-z0-9 _.-]+', '', f'{book.name} {row.finished_at:%Y-%m-%d}').strip() or 'survey book'
    on_disk = store.path(row.pdf_sha256)
    data = None if on_disk else store.get(row.pdf_sha256)
    if on_disk is None and data is None:
        return _error('The book has not been printed as it stands: print it first.', 409, 'not_printed')
    response = send_file(on_disk or io.BytesIO(data), mimetype='application/pdf', as_attachment=request.args.get('download') == '1',
                         download_name=f'{words}.pdf', conditional=False, etag=False)
    response.headers['Cache-Control'] = 'private, no-store'
    return response


def _print_book(print_id):
    """Prints one book in the printer's thread: every published page with the book's numbering, the cover and the index, through
    Gotenberg; then keeps the PDF and lets go of the book's older prints. Says how far it has got as it goes (``done_pages``).

    A print that fails in a way nobody foresaw is marked failed, never left printing: the next look at the book would queue it again,
    and a page polling every 3 s would print the whole book again and again."""
    try:
        _print_book_now(print_id)
    except Exception:  # noqa: BLE001 - said on the print, which can be asked for again
        log.exception('surveys: book print %s failed', print_id)
        db.session.rollback()
        row = db.session.get(SurveyBookPrint, print_id)
        if row is not None and row.state == 'printing':
            row.state, row.error, row.finished_at = 'failed', 'The book could not be printed. Try again in a moment.', datetime.utcnow()
            db.session.commit()


def _print_book_now(print_id):
    row = db.session.get(SurveyBookPrint, print_id)
    if row is None or row.state != 'printing':
        return
    book = db.session.get(SurveyBook, row.book_id)

    def failed(words):
        row.state, row.error, row.finished_at = 'failed', words[:300], datetime.utcnow()
        db.session.commit()

    if book is None or book.archived_at is not None:
        return failed('The book was archived before it was printed.')
    entries = _published(book)
    if not entries:
        return failed('Nothing in the book is published any more.')
    # The book may have moved on since it was asked for: what is printed is named for what it holds, its words read once.
    cover = book_pdf.cover_of(book)
    key = book_pdf.fingerprint(cover, entries, __version__)
    store = asset_store.store()
    done = SurveyBookPrint.query.filter(SurveyBookPrint.book_id == book.id, SurveyBookPrint.fingerprint == key,
                                        SurveyBookPrint.id != row.id, SurveyBookPrint.state == 'ready').all()
    if any(other.pdf_sha256 and store.has(other.pdf_sha256) for other in done):
        # Printed meanwhile, by a print asked for the book as it stood then: nothing is printed twice.
        db.session.delete(row)
        db.session.commit()
        return
    row.fingerprint, row.pages = key, len(entries)
    db.session.commit()
    total = len(entries)
    pages_in = [(_page_model(survey, revision, page={'n': n, 'of': total}, in_book=cover), _asset_reader(survey))
                for n, (survey, revision) in enumerate(entries, start=1)]
    model = book_pdf.compile_book(cover, [(m, rev) for (m, _read), (_s, rev) in zip(pages_in, entries)], datetime.utcnow(), __version__)

    def progress(n):
        row.done_pages = n
        db.session.commit()

    try:
        with terrain_tiles.analysis_running():
            pdf = pages.print_book(model, pages_in, progress)
    except gotenberg.Unavailable:
        return failed('PDFs are not printed on this server.')
    except gotenberg.Failed as problem:
        log.warning('surveys: the book %s could not be printed: %s', book.uuid, problem)
        return failed('The printer could not print the book. Try again in a moment.')
    pdf_key = hashlib.sha256(pdf).hexdigest()
    store.put(pdf_key, pdf)
    row.state, row.pdf_sha256, row.size_bytes, row.done_pages, row.finished_at = 'ready', pdf_key, len(pdf), total, datetime.utcnow()
    row.error = None
    db.session.commit()
    # The book's older prints are let go: only the newest is ever served, and a book is tens of megabytes. A print's file is the
    # book's alone (its bytes name the book on their cover), so nothing else refers to it. One still marked as printing from before
    # this process started, that the printer does not hold, was left by a restart for a book that has moved on since: nothing will
    # look for it. (One started since may be on its way to the printer.)
    for old in SurveyBookPrint.query.filter(SurveyBookPrint.book_id == book.id, SurveyBookPrint.id != row.id).all():
        if old.state == 'printing' and (book_pdf.PRINTER.knows(old.id) or old.started_at >= book_pdf.PRINTER.started):
            continue
        if old.pdf_sha256 and old.pdf_sha256 != pdf_key:
            store.delete(old.pdf_sha256)
        db.session.delete(old)
    db.session.commit()


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


# How often a photo's change is tried again when an editor's save lands on the survey in between.
PHOTO_TRIES = 3


def _photos_changed(survey):
    """A photo added, changed or taken off a survey under review or published makes it a draft again, with a new revision: its page is
    no longer the one signed (or about to be), so a publish made from the copy before is refused. What the editor needs to follow:
    the survey's status and revision, and the revision it had (``was``), so a copy that held that one takes the new."""
    was = survey.revision
    if survey.status in ('in_review', 'published'):
        survey.status = 'draft'
        _stamp(survey)
    return {'status': survey.status, 'revision': survey.revision, 'was': was}


def _photo_saved():
    """Commits a photo's change with its survey's; False when an editor's save landed in between (try again)."""
    try:
        db.session.commit()
        return True
    except StaleDataError:
        db.session.rollback()
        return False


def _photo_conflict(survey_uuid):
    return _error('The survey kept changing while the photo was changed. Try again.', 409, 'revision_conflict',
                  server=_full(_survey(survey_uuid)))


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
    for _try in range(PHOTO_TRIES):
        survey, _book, refused = _editable(survey_uuid)
        if refused:
            return refused
        asset = LzSurveyAsset(
            survey_id=survey.id, kind=meta['kind'], caption=meta['caption'], heading_deg=meta['heading_deg'],
            taken_on=meta['taken_on'], content_type=prepared.content_type, width=prepared.width, height=prepared.height,
            size_bytes=len(prepared.data), sha256=key, created_by=user.id, created_by_name=_who(),
        )
        db.session.add(asset)
        moved = _photos_changed(survey)
        if _photo_saved():
            return _reply({**_asset_json(asset, survey.uuid), 'survey': moved}, 201)
    return _photo_conflict(survey_uuid)


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
    for _try in range(PHOTO_TRIES):
        survey, _book, refused = _editable(survey_uuid)
        if refused:
            return refused
        asset = _asset(survey, asset_id)
        if asset is None or asset.removed_at is not None:
            return _error('There is no such picture.', 404, 'not_found')
        if all(getattr(asset, key) == value for key, value in changes.items()):
            return _reply({**_asset_json(asset, survey.uuid), 'survey': {'status': survey.status, 'revision': survey.revision,
                                                                           'was': survey.revision}})
        for key, value in changes.items():
            setattr(asset, key, value)
        moved = _photos_changed(survey)
        if _photo_saved():
            return _reply({**_asset_json(asset, survey.uuid), 'survey': moved})
    return _photo_conflict(survey_uuid)


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
    for _try in range(PHOTO_TRIES):
        survey, _book, refused = _editable(survey_uuid)
        if refused:
            return refused
        asset = _asset(survey, asset_id)
        if asset.removed_at is not None:
            return _reply({'removed': asset.id, 'survey': {'status': survey.status, 'revision': survey.revision, 'was': survey.revision}})
        asset.removed_at = datetime.utcnow()
        moved = _photos_changed(survey)
        if _photo_saved():
            return _reply({'removed': asset.id, 'survey': moved})
    return _photo_conflict(survey_uuid)
