"""
Mission overlays made in the background (docs/USER_GUIDE.md, *Mission overlay*; `overlay_image.py` makes the file).

An MBTiles file at the USGS topo map's own level 16 over a whole operation is tens of thousands of USGS tiles: ten
minutes or more of fetching, where Cloudflare gives a request 100 s. So the web makes a job (`create`), sends the pieces
it drew with something in them in as many requests as their size needs (`add_pieces`), starts it (`start`), asks how
it stands every few seconds (`status`) and, once it is made, downloads the file from a link of its own (`file_for`),
which a browser can follow with no token, so a file of a gigabyte goes straight to disk rather than through the page.

Things to keep true:
- **One at a time**, on one thread, under the same lock as `/api/overlay-image` (`BUILD_LOCK`): a build holds a few
  hundred MB at most, but its numpy work shares the interpreter with every request, and it waits while an analysis
  runs (`terrain_tiles.wait_while_analysing`). The store is in this process, which is why the server has one worker
  (AGENTS.md §9).
- **Nobody's work is done for nobody**: a job nobody has asked about for POLL_TIMEOUT is stopped and let go of (a closed
  tab), and so is one never started; each person has one job, a new one stopping the last.
- **Nothing is kept**: the pieces and the file are in a folder of the job's own under the system's temporary directory,
  removed when the job is (FILE_KEPT after it is made, at the latest). The link is a random token for that one file, and
  no place is in a URL.
"""

import os
import secrets
import shutil
import tempfile
import threading
import time
from collections import deque
from dataclasses import dataclass, field

import terrain_tiles
from overlay_image import OverlayError, build, looks_like_piece, piece_key, pyramid

BUILD_LOCK = threading.Lock()   # one overlay made at a time, here or by /api/overlay-image
MAX_JOBS = 8                    # jobs alive at once, everyone's
MAX_QUEUED = 4                  # waiting to be made
MAX_PIECE_BYTES = 24 * 1024 * 1024
MAX_JOB_BYTES = 1024 * 1024 * 1024   # what a job's pieces may come to on disk
UNSTARTED_SECONDS = 15 * 60     # a job never started is let go of after this
POLL_TIMEOUT = 120              # one nobody has asked about for this long is stopped
FILE_KEPT = 30 * 60             # a file made is kept this long for its link
ENDED_KEPT = 10 * 60            # a failure or a cancel is told for this long
ROOM_RESERVE = 2 * 1024 ** 3    # free space a build leaves on the temporary directory's disk
BYTES_PER_BASE_TILE = 40 * 1024 # a generous guess at a stored tile on the topo map (measured about 15 KB)

_lock = threading.Lock()        # guards everything below
_jobs = {}                      # id -> Job
_queue = deque()                # ids waiting, oldest first
_running = None                 # the id being made
_worker = None


class Stopped(Exception):
    """A job's build was stopped: cancelled, or nobody is asking about it any more."""


@dataclass
class Job:
    id: str
    user_id: str
    kind: str
    base: str
    name: str
    area: object
    folder: str
    state: str = 'receiving'     # receiving, queued, running, done, failed, cancelled
    stage: str = None            # fetching, writing
    done: int = 0
    total: int = 0
    code: str = None
    message: str = None
    path: str = None
    token: str = None
    pieces: dict = field(default_factory=dict)   # (column, row) -> path
    sizes: dict = field(default_factory=dict)    # (column, row) -> bytes on disk
    touched: float = field(default_factory=time.monotonic)   # created, sent to or asked about
    ended: float = None
    stop: bool = False


def _remove(job):
    """Lets go of a job and everything it has on disk. Under _lock."""
    _jobs.pop(job.id, None)
    if job.id in _queue:
        _queue.remove(job.id)
    shutil.rmtree(job.folder, ignore_errors=True)
    if job.path:
        try:
            os.unlink(job.path)
        except OSError:
            pass


def _end(job, state, code=None, message=None):
    job.state, job.code, job.message = state, code, message
    job.ended = time.monotonic()


def _sweep(now=None):
    """Stops and lets go of what nobody wants any more. Under _lock."""
    now = time.monotonic() if now is None else now
    for job in list(_jobs.values()):
        if job.state == 'receiving' and now - job.touched > UNSTARTED_SECONDS:
            _remove(job)
        elif job.state in ('queued', 'running') and now - job.touched > POLL_TIMEOUT:
            job.stop = True
            if job.state == 'queued':
                _remove(job)
        elif job.state == 'done' and now - job.ended > FILE_KEPT:
            _remove(job)
        elif job.state in ('failed', 'cancelled') and now - job.ended > ENDED_KEPT:
            _remove(job)


def create(user_id, kind, base, name, area):
    """A new job for a person, their last one stopped. OverlayError `busy` when too many are alive."""
    user_id = str(user_id)
    with _lock:
        _sweep()
        for job in [job for job in _jobs.values() if job.user_id == user_id]:
            _cancel(job)
        if len(_jobs) >= MAX_JOBS:
            raise OverlayError('busy', 'Too many overlays are being made. Try again in a few minutes.')
        job = Job(id=secrets.token_urlsafe(12), user_id=user_id, kind=kind, base=base, name=name, area=area,
                  folder=tempfile.mkdtemp(prefix='ezpz-overlay-'))
        _jobs[job.id] = job
        return job


def find(user_id, job_id):
    """A person's job by its id, or None (anyone else's is none)."""
    with _lock:
        _sweep()
        job = _jobs.get(str(job_id))
        if job is None or job.user_id != str(user_id):
            return None
        job.touched = time.monotonic()
        return job


def add_pieces(job, files):
    """Keeps the request's `piece-<column>-<row>` files on disk, each checked as a piece from its header. Answers how
    many the job has."""
    written = []
    for name in files:
        at = piece_key(name, job.area)
        if at is None:
            raise OverlayError('bad_pieces', 'The overlay was sent with something else in it.')
        data = files[name].read(MAX_PIECE_BYTES + 1)
        if len(data) > MAX_PIECE_BYTES:
            raise OverlayError('too_large', 'Part of the overlay is too large to send.')
        if not looks_like_piece(data):
            raise OverlayError('bad_image', 'Part of the overlay is not the size it should be.')
        written.append((at, data))
    with _lock:
        if job.state != 'receiving' or job.id not in _jobs:
            raise OverlayError('started', 'The overlay is already being made.')
        sizes = {**job.sizes, **{at: len(data) for at, data in written}}
        if sum(sizes.values()) > MAX_JOB_BYTES:
            raise OverlayError('too_large', 'The overlay is too large to send. Choose less detail or a smaller area.')
        for (i, j), data in written:
            path = os.path.join(job.folder, f'piece-{i}-{j}.png')
            with open(path, 'wb') as file:
                file.write(data)
            job.pieces[(i, j)] = path
        job.sizes = sizes
        job.touched = time.monotonic()
        return len(job.pieces)


def _room_needed(job):
    """A generous guess at what the file comes to on disk, with ROOM_RESERVE."""
    area = job.area
    if job.kind == 'mbtiles':
        tiles = sum(len(keys) for _, keys in pyramid(area))
        each = BYTES_PER_BASE_TILE if job.base == 'usgs-topo' else 16 * 1024
        return tiles * each + ROOM_RESERVE
    return area.width * area.height * 4 + ROOM_RESERVE


def start(job):
    """Queues a job to be made."""
    with _lock:
        if job.state != 'receiving':
            raise OverlayError('started', 'The overlay is already being made.')
        if job.base == 'none' and not job.pieces:
            raise OverlayError('empty', 'Nothing is drawn in the overlay’s area.')
        if len(_queue) >= MAX_QUEUED:
            raise OverlayError('busy', 'Too many overlays are waiting to be made. Try again in a few minutes.')
        if shutil.disk_usage(job.folder).free < _room_needed(job):
            raise OverlayError('no_room', 'The server has no room for an overlay this large now. Choose less detail.')
        job.state = 'queued'
        job.touched = time.monotonic()
        _queue.append(job.id)
        _wake()


def _cancel(job):
    """Under _lock."""
    if job.state == 'running':
        job.stop = True     # let go of by the worker once its build stops
        _end(job, 'cancelled')
    else:
        _remove(job)


def cancel(job):
    with _lock:
        _cancel(job)


def status(job):
    """How a job stands, for its owner: what the web shows and, once made, where it downloads it from."""
    with _lock:
        ahead = 0
        if job.state == 'queued':
            ahead = list(_queue).index(job.id) + (1 if _running else 0) if job.id in _queue else 0
        answer = {'id': job.id, 'state': job.state, 'stage': job.stage, 'done': job.done, 'total': job.total,
                  'ahead': ahead, 'pieces': len(job.pieces)}
        if job.state in ('failed', 'cancelled') and job.code:
            answer.update(code=job.code, error=job.message)
        if job.state == 'done':
            # Relative to the API's base, which ends in /api (AGENTS.md §14).
            answer['download'] = f'/overlay-jobs/{job.id}/file/{job.token}'
        return answer


def file_for(job_id, token):
    """(path, kind, name) of a job's file for its link's token, or None."""
    with _lock:
        _sweep()
        job = _jobs.get(str(job_id))
        if job is None or job.state != 'done' or not job.token or not secrets.compare_digest(job.token, str(token)):
            return None
        return job.path, job.kind, job.name


def _progress(job):
    def told(stage, done, total):
        if job.stop:
            raise Stopped()
        job.stage, job.done, job.total = stage, done, total
        # Crews first: an analysis shares this interpreter (AGENTS.md §9, *Heavy work goes first*).
        terrain_tiles.wait_while_analysing()
    return told


def _make(job):
    """Makes a job's file. A job stopped (cancelled, or nobody asking) is let go of, nothing of it kept."""
    try:
        path = build(job.kind, job.area, dict(job.pieces), job.base, job.name, progress=_progress(job), background=True)
    except Stopped:
        with _lock:
            _remove(job)
        return
    except OverlayError as refused:
        with _lock:
            _end(job, 'failed', refused.code, refused.message)
            shutil.rmtree(job.folder, ignore_errors=True)
        return
    except Exception:  # noqa: BLE001 — a fault of ours: the job says so, the worker goes on
        with _lock:
            _end(job, 'failed', 'failed', 'The overlay could not be made. Try again.')
            shutil.rmtree(job.folder, ignore_errors=True)
        return
    with _lock:
        if job.stop or job.id not in _jobs:
            os.unlink(path)
            _remove(job)
            return
        job.path, job.token = path, secrets.token_urlsafe(32)
        _end(job, 'done')
        job.stage, job.done = None, job.total
        shutil.rmtree(job.folder, ignore_errors=True)   # the pieces; the file is in the system's temporary directory


def _work():
    global _running, _worker
    while True:
        with _lock:
            _sweep()
            if not _queue:
                _worker = None
                return
            job = _jobs[_queue.popleft()]
            job.state = 'running'
            _running = job.id
        try:
            with BUILD_LOCK:
                if job.stop:
                    with _lock:
                        _remove(job)
                else:
                    _make(job)
        finally:
            with _lock:
                _running = None


def _wake():
    """Starts the worker if it is not running. Under _lock."""
    global _worker
    if _worker is None:
        _worker = threading.Thread(target=_work, name='overlay-jobs', daemon=True)
        _worker.start()


def reset():
    """Lets go of every job (tests)."""
    with _lock:
        for job in list(_jobs.values()):
            job.stop = True
            _remove(job)
