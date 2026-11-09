"""The places index: one SQLite file with an FTS4 index, built here and only ever read after (docs/PLACE_SEARCH.md §4.3).

``place`` holds each place as the client shows it, from NASR (``nasr.py``) and GNIS (``gnis.py``) alike;
``place_fts`` indexes its folded name, identifier and city (``text.fold``; a GNIS feature has a county, which is
shown and not searched), as an FTS4 table whose content is ``place`` itself. FTS4 with the ``simple`` tokenizer, not
FTS5 or ``unicode61``: Android's own SQLite (3.22 on API 29) has FTS4 on every version the app runs on, and the
text is folded before it is stored, so neither side's Unicode support decides what matches.

The grid is not stored: every client works it out from ``lat`` and ``lon``, so the index never disagrees with
the field's own grids.

A million rows, so what serves no query is left out (measured on NASR 2026-10-29 and GNIS 2026-11-02): ``id`` has
no unique index (``nasr.read`` and ``gnis.read`` give each id once, and nothing looks a place up by it), the
identifier and ICAO indexes hold only the places that have one (a folded value that would be empty is NULL), and
FTS4 keeps no per-document sizes (``matchinfo=fts3``: nothing ranks by them).
"""

import json
import os
import sqlite3
from datetime import datetime, timezone

from .nasr import PLACE_FIELDS
from .text import compact, fold

# Bumped when the tables change, so a reader of another schema knows not to read the file (meta's ``schema``).
SCHEMA = 2

_CREATE = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE place (
    rowid INTEGER PRIMARY KEY,
    id TEXT NOT NULL,
    source TEXT NOT NULL,
    kind TEXT NOT NULL,
    detail TEXT NOT NULL,
    name TEXT,
    ident TEXT,
    icao TEXT,
    city TEXT,
    county TEXT,
    state TEXT,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    elev_ft INTEGER,
    use TEXT,
    status TEXT NOT NULL,
    military TEXT,
    rank INTEGER NOT NULL,
    norm_name TEXT,
    norm_ident TEXT,
    norm_city TEXT,
    norm_icao TEXT
);
CREATE INDEX place_norm_ident ON place (norm_ident) WHERE norm_ident IS NOT NULL;
CREATE INDEX place_norm_icao ON place (norm_icao) WHERE norm_icao IS NOT NULL;
CREATE VIRTUAL TABLE place_fts USING fts4(content="place", norm_name, norm_ident, norm_city, prefix="2,3", matchinfo="fts3");
"""


def build(places, path, meta):
    """Write ``places`` (an iterable of dicts of ``nasr.PLACE_FIELDS``) to a new index at ``path``, with ``meta``.

    ``path`` must not exist: an index is written once, then read. ``meta`` may set ``built_at`` itself, so a
    fixture made twice is the same file. Returns the number of places written.
    """
    if os.path.exists(path):
        raise FileExistsError(path)
    connection = sqlite3.connect(path)
    try:
        connection.executescript(_CREATE)
        columns = PLACE_FIELDS + ("norm_name", "norm_ident", "norm_city", "norm_icao")
        rows = (
            tuple(place.get(field) for field in PLACE_FIELDS)
            + (fold(place.get("name")) or None, compact(place.get("ident")) or None, fold(place.get("city")) or None,
               compact(place.get("icao")) or None)
            for place in places
        )
        connection.executemany(
            f"INSERT INTO place ({', '.join(columns)}) VALUES ({', '.join('?' for _ in columns)})", rows)
        connection.execute("INSERT INTO place_fts (docid, norm_name, norm_ident, norm_city) "
                           "SELECT rowid, norm_name, norm_ident, norm_city FROM place")
        connection.execute("INSERT INTO place_fts (place_fts) VALUES ('optimize')")
        count = connection.execute("SELECT count(*) FROM place").fetchone()[0]
        kinds = dict(connection.execute("SELECT kind, count(*) FROM place GROUP BY kind ORDER BY kind"))
        stamped = {
            "schema": str(SCHEMA),
            "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "count": str(count),
            "kinds": json.dumps(kinds, sort_keys=True),
            **{str(k): str(v) for k, v in meta.items()},
        }
        connection.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", sorted(stamped.items()))
        connection.commit()
        connection.execute("VACUUM")
    finally:
        connection.close()
    return count


def build_name(cycle, gnis_date=None):
    """``2026-10-29.g20261102.s2``: the NASR cycle, GNIS's date when it is built with, and this schema."""
    middle = f".g{gnis_date.replace('-', '')}" if gnis_date else ""
    return f"{cycle}{middle}.s{SCHEMA}"


def read_meta(connection):
    """The index's ``meta`` table as a dict."""
    return dict(connection.execute("SELECT key, value FROM meta"))
