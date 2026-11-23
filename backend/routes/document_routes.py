"""Documents in the Library: ``/api/documents``, synced like a saved LZ (docs/DOCUMENTS.md §6).

A document is a PDF and its page text, kept by their hashes in the document store (``documents.files``); the record holds the JSON
that names them (``doc_data``). The server makes every document: an upload is read and, for an Office file, turned into a PDF here,
so a client never sends a document's content, only the file, a name, a rename or a deletion. ``If-Match`` and ``Idempotency-Key`` work
as for every saved record, a ``client_uuid`` makes an upload repeatable, and a deletion is a tombstone. Every route needs
``documents``; a write needs ``cloud_save`` too, as any saved record does.

The file routes are shared with the pack's (``serve``), so a document reads the same wherever it is kept.
"""

import re

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import get_jwt_identity, jwt_required
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from werkzeug.exceptions import RequestEntityTooLarge

import sync_support as sync
from documents import core, files
from entitlements import require_feature
from models import SavedDocument, db

documents_bp = Blueprint('documents', __name__)

MAX_NAME = 100
# A multipart body is the file and a few short fields: anything much past the file's limit is refused before it is read.
_MAX_BODY = core.MAX_UPLOAD_BYTES + 64 * 1024
_UNSAFE_FILENAME = re.compile(r'[^A-Za-z0-9 ._()-]+')


def _error(message, status, code, **extra):
    return jsonify({'error': message, 'code': code, **extra}), status


def refusal(error):
    """A DocumentError as the API answers it."""
    return _error(str(error), error.status, error.code)


def parse_form():
    """The refusal for a multipart body too large to read, or None once ``request.form`` and ``request.files`` are read. The limit is
    set on this request before the body is touched, so a body with no length (chunked) is cut off at it too."""
    request.max_content_length = _MAX_BODY
    try:
        request.form, request.files  # noqa: B018 - reading them parses the body, under the limit
    except RequestEntityTooLarge:
        return _error('A document can be at most 25 MB.', 413, 'file_too_large')
    return None


def read_upload():
    """``(data, filename, refusal)``: the uploaded file's bytes, bounded before they are read, or why there are none."""
    refused = parse_form()
    if refused:
        return None, None, refused
    upload = request.files.get('file')
    if upload is None:
        return None, None, _error('Send the document as the "file" part.', 400, 'missing_file')
    data = upload.stream.read(core.MAX_UPLOAD_BYTES + 1)
    if len(data) > core.MAX_UPLOAD_BYTES:
        return None, None, _error('A document can be at most 25 MB.', 413, 'file_too_large')
    return data, upload.filename or '', None


def name_from(value, filename):
    """``(name, refusal)``: the name sent, else the file's name less its extension."""
    if value is None:
        return core.default_name(filename), None
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_NAME:
        return None, _error(f'The name must be 1 to {MAX_NAME} characters.', 400, 'invalid_name')
    return value, None


def serve(digest, title, content_type):
    """One of a document's files, to whoever the caller has decided may read it (docs/DOCUMENTS.md §6).

    ``private, no-cache`` with the ETag Flask gives a file: a new version is a new file under the same address, so a cached copy is
    always asked about, and a 304 costs nothing. ``nosniff`` and a ``sandbox`` policy, so a PDF opened at its own address in a browser
    runs nothing of its own. ``?download=1`` makes it an attachment."""
    path = files.path_of(digest) if digest else None
    if path is None:
        return _error('That file is not on this server.', 404, 'file_missing')
    extension = 'pdf' if content_type == 'application/pdf' else 'json'
    filename = (_UNSAFE_FILENAME.sub(' ', title or '').strip() or 'document')[:100] + f'.{extension}'
    response = send_file(path, mimetype=content_type, conditional=True, etag=True, max_age=None,
                         as_attachment=request.args.get('download') == '1', download_name=filename)
    response.cache_control.public = False
    response.cache_control.private = True
    response.cache_control.no_cache = True
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Content-Security-Policy'] = 'sandbox'
    return response


def _summary(d):
    return {
        'id': d.id,
        'name': d.name,
        'pages': d.page_count,
        'bytes': d.pdf_bytes,
        'source_type': d.source_type,
        'has_text': d.text_sha256 is not None,
        'created_at': d.created_at.isoformat(),
        'updated_at': d.updated_at.isoformat(),
        **sync.sync_fields(d),
    }


def _full(d):
    return {**_summary(d), 'data': d.doc_data}


def _live(user_id, document_id, fresh=False):
    """The caller's live document, or None. ``fresh`` reads its row again even when this request holds it, and locks it until the
    commit (``FOR UPDATE`` in Postgres): a write that did slow work (a conversion) decides again by the record as it is now, and a
    rename or a delete waits for it rather than writing over it with what it read before."""
    query = SavedDocument.query.filter_by(id=document_id, user_id=user_id, deleted_at=None)
    if fresh:
        query = query.execution_options(populate_existing=True).with_for_update()
    return query.first()


def _deleted():
    # An upload asked again after its answer was lost, for a document deleted since: it was made, and is gone.
    return _error('That document was uploaded and has since been deleted.', 410, 'document_deleted')


def _fill(record, prepared, filename, source_bytes):
    data = core.describe(prepared, filename, source_bytes)
    record.doc_data = data
    record.pdf_sha256 = prepared.pdf_sha256
    record.text_sha256 = prepared.text_sha256
    record.page_count = prepared.pages
    record.pdf_bytes = len(prepared.pdf)
    record.source_type = prepared.kind


def library_use(user_id):
    """``(documents, bytes)`` of the caller's live Library."""
    count, used = (db.session.query(func.count(SavedDocument.id), func.coalesce(func.sum(SavedDocument.pdf_bytes), 0))
                   .filter(SavedDocument.user_id == user_id, SavedDocument.deleted_at.is_(None)).one())
    return count, int(used or 0)


def _library_full():
    return _error('Your Library has no room for this document: delete one first.', 413, 'library_full')


def _old_hashes(record):
    return {d for d in (record.pdf_sha256, record.text_sha256) if d}


@documents_bp.route('/api/documents', methods=['GET'])
@jwt_required()
@require_feature('documents')
def list_documents():
    user_id = int(get_jwt_identity())
    saved = (SavedDocument.query.filter_by(user_id=user_id, deleted_at=None)
             .order_by(SavedDocument.updated_at.desc()).all())
    return jsonify([_summary(d) for d in saved])


@documents_bp.route('/api/documents', methods=['POST'])
@jwt_required()
@require_feature('documents')
@require_feature('cloud_save')
def create_document():
    """Upload a document: multipart ``file``, optional ``name`` and ``client_uuid``. A heavy request: an Office file is converted."""
    user_id = int(get_jwt_identity())
    refused = parse_form()
    if refused:
        return refused
    client_uuid = None
    if request.form.get('client_uuid') is not None:
        client_uuid = sync.valid_client_uuid(request.form.get('client_uuid'))
        if client_uuid is None:
            return _error('client_uuid must be a UUID', 400, 'invalid_client_uuid')
        # Asked before anything is read: an upload repeated after a lost answer is the first, and costs no second conversion.
        existing = SavedDocument.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
        if existing:
            if existing.deleted_at is not None:
                return _deleted()
            return sync.with_etag(jsonify(_summary(existing)), existing)

    data, filename, refused = read_upload()
    if refused:
        return refused
    name, refused = name_from(request.form.get('name'), filename)
    if refused:
        return refused
    count, used = library_use(user_id)
    if count >= core.LIBRARY_MAX_DOCUMENTS:
        return _library_full()
    try:
        prepared = core.prepare(data, filename)
    except core.DocumentError as error:
        return refusal(error)
    if used + len(prepared.pdf) > core.LIBRARY_MAX_BYTES:
        return _library_full()

    record = SavedDocument(user_id=user_id, name=name, client_uuid=client_uuid or sync.new_client_uuid())
    _fill(record, prepared, filename, len(data))
    with files.held():
        # Asked again under the lock: uploads side by side each passed the check above while the other converted.
        count, used = library_use(user_id)
        if count >= core.LIBRARY_MAX_DOCUMENTS or used + len(prepared.pdf) > core.LIBRARY_MAX_BYTES:
            return _library_full()
        files.keep(prepared)
        db.session.add(record)
        try:
            sync.stamp(record, user_id, sync.idempotency_key(request))
            db.session.commit()
        except IntegrityError:
            # A concurrent upload with the same client_uuid won the race; its files are these or its own.
            db.session.rollback()
            existing = SavedDocument.query.filter_by(user_id=user_id, client_uuid=client_uuid).first()
            if existing is None:
                raise
            if existing.deleted_at is not None:
                return _deleted()
            return sync.with_etag(jsonify(_summary(existing)), existing)
    return sync.with_etag(jsonify(_summary(record)), record), 201


@documents_bp.route('/api/documents/<int:document_id>', methods=['GET'])
@jwt_required()
@require_feature('documents')
def get_document(document_id):
    saved = _live(int(get_jwt_identity()), document_id)
    if not saved:
        return _error('Not found', 404, 'not_found')
    return sync.with_etag(jsonify(_full(saved)), saved)


@documents_bp.route('/api/documents/<int:document_id>', methods=['PUT'])
@jwt_required()
@require_feature('documents')
@require_feature('cloud_save')
def rename_document(document_id):
    """JSON ``{name}``: the one thing about a document a person changes. Its content changes only by a new version."""
    user_id = int(get_jwt_identity())
    saved = _live(user_id, document_id, fresh=True)
    if not saved:
        return _error('Not found', 404, 'not_found')
    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early
    body = request.get_json(silent=True)
    body = body if isinstance(body, dict) else {}
    if 'name' not in body:
        return _error('Send the new name.', 400, 'missing_fields')
    if not isinstance(body['name'], str):
        return _error(f'The name must be 1 to {MAX_NAME} characters.', 400, 'invalid_name')
    name, refused = name_from(body['name'], None)
    if refused:
        return refused
    saved.name = name
    sync.stamp(saved, user_id, sync.idempotency_key(request))
    db.session.commit()
    return sync.with_etag(jsonify(_summary(saved)), saved)


@documents_bp.route('/api/documents/<int:document_id>/version', methods=['POST'])
@jwt_required()
@require_feature('documents')
@require_feature('cloud_save')
def new_version(document_id):
    """A new version of the paper, under the same name and identity: multipart ``file`` as an upload, with ``If-Match``."""
    user_id = int(get_jwt_identity())
    saved = _live(user_id, document_id)
    if not saved:
        return _error('Not found', 404, 'not_found')
    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early
    data, filename, refused = read_upload()
    if refused:
        return refused
    try:
        prepared = core.prepare(data, filename)
    except core.DocumentError as error:
        return refusal(error)
    _count, used = library_use(user_id)
    if used - saved.pdf_bytes + len(prepared.pdf) > core.LIBRARY_MAX_BYTES:
        return _library_full()

    with files.held():
        # The conversion can take minutes: the document may have been renamed or deleted meanwhile, so it is read again (and
        # locked) and the request decided again by it, or the write would undo what was done and give two states one revision.
        saved = _live(user_id, document_id, fresh=True)
        if not saved:
            return _error('Not found', 404, 'not_found')
        early = sync.check_precondition(saved, request, _full)
        if early is not None:
            return early
        _count, used = library_use(user_id)
        if used - saved.pdf_bytes + len(prepared.pdf) > core.LIBRARY_MAX_BYTES:
            return _library_full()
        before = _old_hashes(saved)
        files.keep(prepared)
        _fill(saved, prepared, filename, len(data))
        sync.stamp(saved, user_id, sync.idempotency_key(request))
        db.session.commit()
    files.release(before - _old_hashes(saved))
    return sync.with_etag(jsonify(_summary(saved)), saved)


@documents_bp.route('/api/documents/<int:document_id>', methods=['DELETE'])
@jwt_required()
@require_feature('documents')
def delete_document(document_id):
    user_id = int(get_jwt_identity())
    saved = (SavedDocument.query.filter_by(id=document_id, user_id=user_id)
             .execution_options(populate_existing=True).with_for_update().first())
    if not saved:
        return _error('Not found', 404, 'not_found')
    if saved.deleted_at is not None:
        return jsonify({'status': 'deleted'})        # deleting twice is not an error
    early = sync.check_precondition(saved, request, _full)
    if early is not None:
        return early

    before = _old_hashes(saved)
    # A tombstone, so a device that was offline learns of the deletion, with the content gone; the files go once nothing names them.
    saved.doc_data = {}
    saved.pdf_sha256 = saved.text_sha256 = saved.source_type = None
    saved.page_count = saved.pdf_bytes = 0
    saved.name = ''
    sync.stamp(saved, user_id, sync.idempotency_key(request), deleted=True)
    db.session.commit()
    files.release(before)
    return jsonify({'status': 'deleted', 'revision': saved.revision})


@documents_bp.route('/api/documents/<int:document_id>/pdf', methods=['GET'])
@jwt_required()
@require_feature('documents')
def document_pdf(document_id):
    saved = _live(int(get_jwt_identity()), document_id)
    if not saved:
        return _error('Not found', 404, 'not_found')
    return serve(saved.pdf_sha256, saved.name, 'application/pdf')


@documents_bp.route('/api/documents/<int:document_id>/text', methods=['GET'])
@jwt_required()
@require_feature('documents')
def document_text(document_id):
    saved = _live(int(get_jwt_identity()), document_id)
    if not saved:
        return _error('Not found', 404, 'not_found')
    if saved.text_sha256 is None:
        return _error('This document has no text to search (a scan?).', 404, 'no_text')
    return serve(saved.text_sha256, saved.name, 'application/json')
