"""The index the API searches: the build ``current.json`` names under ``PLACES_DIR``.

``PLACES_DIR/`` holds ``places-<build>.sqlite`` (written by ``refresh.py``, never changed after) and
``current.json``, ``{"build", "file", "nasr_cycle"}``, which is replaced whole to switch builds. The file is
opened read-only and immutable, once per build; a new ``current.json`` is seen at the next search.

With no ``PLACES_DIR``, or nothing built there yet, there is no index and a search answers that it has none.
"""

import json
import os
import sqlite3
import threading

from . import search as searching
from .index import SCHEMA, read_meta

CURRENT = "current.json"

_lock = threading.Lock()
_open = None   # (current.json's mtime and size, the build's name, its connection, its meta)


def places_dir():
    """The folder the index is kept in, from ``PLACES_DIR``, or None."""
    root = (os.environ.get("PLACES_DIR") or "").strip()
    return root or None


def read_current(root):
    """``current.json`` under ``root`` as a dict, or None where there is none or it cannot be read."""
    try:
        with open(os.path.join(root, CURRENT), encoding="utf-8") as handle:
            current = json.load(handle)
    except (OSError, ValueError):
        return None
    if not isinstance(current, dict) or not isinstance(current.get("file"), str):
        return None
    # Only a file of ours beside it, never a path that leaves the folder.
    if os.path.basename(current["file"]) != current["file"] or not current["file"].startswith("places-"):
        return None
    return current


def _connect(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    return connection


def _index():
    """The current build's (name, connection, meta), opening it if it changed; None with no usable index.

    Called under ``_lock``.
    """
    global _open
    root = places_dir()
    if not root:
        return None
    try:
        stat = os.stat(os.path.join(root, CURRENT))
    except OSError:
        return None
    stamp = (stat.st_mtime_ns, stat.st_size)
    if _open and _open[0] == stamp:
        return _open[1:]
    current = read_current(root)
    if current is None:
        return None
    try:
        connection = _connect(os.path.join(root, current["file"]))
        meta = read_meta(connection)
    except sqlite3.Error:
        return None
    if meta.get("schema") != str(SCHEMA):
        connection.close()
        return None
    if _open:
        _open[2].close()
    _open = (stamp, current.get("build") or current["file"], connection, meta)
    return _open[1:]


def status():
    """What is being served: ``{"available": False}``, or the build, its NASR cycle and its count."""
    with _lock:
        found = _index()
        if not found:
            return {"available": False}
        build, _connection, meta = found
        return {"available": True, "build": build, "nasrCycle": meta.get("nasr_cycle"), "count": int(meta.get("count", 0))}


def search(text, near=None, limit=8):
    """``(status, places)``: the current build's answer for ``text`` (search.py), or no index's."""
    with _lock:
        found = _index()
        if not found:
            return {"available": False}, []
        build, connection, meta = found
        rows = searching.search(connection, text, near, limit)
        return {"available": True, "build": build, "nasrCycle": meta.get("nasr_cycle")}, [
            searching.as_answer(row) for row in rows]


def forget():
    """Close the open index (tests, and a refresh that removed it)."""
    global _open
    with _lock:
        if _open:
            _open[2].close()
        _open = None
