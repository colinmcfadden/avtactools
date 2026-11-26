"""Where documents' files are kept, and when they go (docs/DOCUMENTS.md §5).

A PDF and its page text are each kept under their own SHA-256 in an asset store (the LZ surveys' ``LocalAssetStore``, under
``DOCUMENTS_DIR``), never in the database: one PDF uploaded twice, or copied into three packs, is one file. A file goes once no live
Library record and no live pack item names it.

Writing a file and committing the row that names it, and deciding a file is named by nothing and deleting it, each happen under one
lock (``held``), so a delete can never take a file an upload is about to name: an upload of bytes already kept writes nothing, and
without the lock a delete of the old record could remove the file between the upload's write and its commit. The lock is the
process's, which holds while the API runs one worker (AGENTS.md §9); a second worker would need it in the database.
"""

from __future__ import annotations

import os
import tempfile
import threading
import time

from survey.assets import LocalAssetStore, check_key

from . import core

# A file younger than this is never swept: an upload writes its files a moment before its row commits.
SWEEP_MIN_AGE_S = 3600

_lock = threading.RLock()


def held():
    """The lock a write of files and their row, or a release, holds (see the module's docstring)."""
    return _lock


def root() -> str:
    """``DOCUMENTS_DIR``, else a folder in the system's temp directory (a bare local run: lost on reboot, so a deployment sets it)."""
    return os.environ.get('DOCUMENTS_DIR') or os.path.join(tempfile.gettempdir(), 'ezpz-documents')


def store() -> LocalAssetStore:
    return LocalAssetStore(root())


def keep(prepared: core.Prepared) -> None:
    """Write an upload's PDF and text. The caller holds ``held()`` until the row naming them is committed."""
    target = store()
    target.put(prepared.pdf_sha256, prepared.pdf)
    if prepared.text is not None:
        target.put(prepared.text_sha256, prepared.text)


def path_of(digest) -> str | None:
    try:
        return store().path(check_key(digest))
    except ValueError:
        return None


def _named(digests=None) -> set[str]:
    """The hashes live documents name, of ``digests`` (or of all of them)."""
    from models import MissionPackItem, SavedDocument

    named = set()
    query = SavedDocument.query.filter(SavedDocument.deleted_at.is_(None))
    if digests is not None:
        query = query.filter((SavedDocument.pdf_sha256.in_(digests)) | (SavedDocument.text_sha256.in_(digests)))
    for record in query.with_entities(SavedDocument.pdf_sha256, SavedDocument.text_sha256):
        named.update(d for d in record if d)
    # A pack's documents are its live items of the kind: few, and their JSON is small.
    items = MissionPackItem.query.filter(MissionPackItem.kind == 'document', MissionPackItem.deleted_at.is_(None))
    for (data,) in items.with_entities(MissionPackItem.data):
        named.update(core.hashes_of(data))
    return named if digests is None else named & set(digests)


def release(digests) -> int:
    """Delete each file of ``digests`` that nothing live names any more; how many went. Called after the commit that let them go."""
    digests = {d for d in digests if isinstance(d, str)}
    if not digests:
        return 0
    gone = 0
    with _lock:
        target = store()
        for digest in digests - _named(digests):
            try:
                if target.has(check_key(digest)):
                    target.delete(digest)
                    gone += 1
            except ValueError:
                continue
    return gone


def sweep(min_age_s=SWEEP_MIN_AGE_S, now=None) -> int:
    """Delete every file nothing live names, older than ``min_age_s``, and any half-written one left by a crash; how many went."""
    now = time.time() if now is None else now
    base = root()
    if not os.path.isdir(base):
        return 0
    gone = 0
    with _lock:
        named = _named()
        for folder, _dirs, names in os.walk(base):
            for name in names:
                path = os.path.join(folder, name)
                try:
                    if now - os.path.getmtime(path) < min_age_s:
                        continue
                    if name.startswith('.incoming-') or (len(name) == 64 and name not in named):
                        os.unlink(path)
                        gone += 1
                except OSError:
                    continue
    return gone


def hashes_held_by(user_id) -> set[str]:
    """Every file the account's documents name: its Library's, and those of the packs it owns (which go with it when nobody else is
    in them). Read before the account is deleted, and released after: a file still named (a pack handed on, a copy) stays."""
    from models import MissionPack, MissionPackItem, SavedDocument

    found = set()
    for record in SavedDocument.query.filter_by(user_id=user_id).with_entities(SavedDocument.pdf_sha256, SavedDocument.text_sha256):
        found.update(d for d in record if d)
    items = (MissionPackItem.query.join(MissionPack, MissionPack.id == MissionPackItem.pack_id)
             .filter(MissionPack.owner_id == user_id, MissionPackItem.kind == 'document')
             .with_entities(MissionPackItem.data))
    for (data,) in items:
        found.update(core.hashes_of(data))
    return found


def pack_hashes(pack_id) -> set[str]:
    """Every file a pack's documents name, read before the pack is deleted."""
    from models import MissionPackItem

    found = set()
    for (data,) in MissionPackItem.query.filter_by(pack_id=pack_id, kind='document').with_entities(MissionPackItem.data):
        found.update(core.hashes_of(data))
    return found


SWEEP_EVERY_S = 24 * 3600
_FIRST_SWEEP_AFTER_S = 600


def start_sweeping(app):
    """Sweep once ten minutes after startup and then every day, where a deployment keeps documents (``DOCUMENTS_DIR``)."""
    if not os.environ.get('DOCUMENTS_DIR'):
        return None

    def run():
        time.sleep(_FIRST_SWEEP_AFTER_S)
        while True:
            try:
                with app.app_context():
                    gone = sweep()
                if gone:
                    app.logger.info('documents: swept %d file(s) nothing names', gone)
            except Exception:  # noqa: BLE001 - a sweep that fails is tried again tomorrow
                app.logger.exception('documents: the sweep failed')
            time.sleep(SWEEP_EVERY_S)

    thread = threading.Thread(target=run, name='documents-sweep', daemon=True)
    thread.start()
    return thread
