"""File locks for the chart volume (docs/VFR_CHARTS.md §5.1, §5.5): ``flock``, which holds across processes and across the two
containers of a redeploy, since both mount the same volume.

A lock is on an open file, not a process: it lasts while that open file is open, and ``release`` lets it go for every descriptor of
it. A descriptor here is not inherited (Python opens them so), and a build's worker processes are spawned, not forked: they hold no
lock, which is why the API kills a build process's whole group after it ends abnormally, before it looks at the lock
(``charts.job._after``). Two opens of one file in one process are two holders, so threads can stand in for processes in a test.
A process is never judged alive by its pid, which means nothing in another container.

POSIX only. On Windows (a developer's machine) chart builds are refused in words (``supported``).
"""
from __future__ import annotations

import os
from contextlib import contextmanager

try:
    import fcntl
except ImportError:          # Windows
    fcntl = None


class Unsupported(RuntimeError):
    """This server cannot lock a file the way chart builds need."""


def supported() -> bool:
    return fcntl is not None


def _need():
    if fcntl is None:
        raise Unsupported("Chart builds need a Linux or macOS server: this one cannot lock a file across processes.")


def try_lock(path) -> int | None:
    """An open descriptor holding an exclusive lock on ``path`` (made if missing), or None when another holds it. Never waits."""
    _need()
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        return None
    except BaseException:
        os.close(fd)
        raise
    return fd


def release(fd: int | None) -> None:
    if fd is None:
        return
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def held(path) -> bool:
    """Whether another open file holds ``path``'s lock now. A missing file is held by nobody, and is not made."""
    _need()
    if not os.path.exists(path):
        return False
    fd = try_lock(path)
    if fd is None:
        return True
    release(fd)
    return False


@contextmanager
def locked(path):
    """Waits for, and holds, an exclusive lock on ``path`` (made if missing)."""
    _need()
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)
