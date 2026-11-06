"""The places index: one SQLite file with an FTS4 index, built here and only ever read after (docs/PLACE_SEARCH.md §4.3).

``place`` holds each place as the client shows it; ``place_fts`` indexes its folded name, identifier and city
(``text.fold``), as an FTS4 table whose content is ``place`` itself. FTS4 with the ``simple`` tokenizer, not
FTS5 or ``unicode61``: Android's own SQLite (3.22 on API 29) has FTS4 on every version the app runs on, and the
text is folded before it is stored, so neither side's Unicode support decides what matches.

The grid is not stored: every client works it out from ``lat`` and ``lon``, so the index never disagrees with
the field's own grids.
"""

import json
import os
import sqlite3
from datetime import datetime, timezone

from .nasr import PLACE_FIELDS
from .text import compact, fold

# Bumped when the tables change, so a reader of another schema knows not to read the file (meta's ``schema``).
SCHEMA = 1

_CREATE = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE place (
    rowid INTEGER PRIMARY KEY,
    id TEXT NOT NULL UNIQUE,
    source TEXT NOT NULL,
    kind TEXT NOT NULL,
    detail TEXT NOT NULL,
    name TEXT,
    ident TEXT,
    icao TEXT,
    city TEXT,
    state TEXT,
    lat REAL NOT NULL,
    lon REAL NOT NULL,
    elev_ft INTEGER,
    use TEXT,
    status TEXT NOT NULL,
    military TEXT,
    rank INTEGER NOT NULL,
    norm_name TEXT NOT NULL,
    norm_ident TEXT NOT NULL,
    norm_city TEXT NOT NULL,
    norm_icao TEXT NOT NULL
);
CREATE INDEX place_norm_ident ON place (norm_ident);
CREATE INDEX place_norm_icao ON place (norm_icao);
CREATE VIRTUAL TABLE place_fts USING fts4(content="place", norm_name, norm_ident, norm_city, prefix="2,3");
"""


def build(places, path, meta):
    """Write ``places`` (dicts of ``nasr.PLACE_FIELDS``) to a new index at ``path``, with ``meta`` (str → str).

    ``path`` must not exist: an index is written once, then read. Returns the number of places written.
    """
    if os.path.exists(path):
        raise FileExistsError(path)
    connection = sqlite3.connect(path)
    try:
        connection.executescript(_CREATE)
        columns = PLACE_FIELDS + ("norm_name", "norm_ident", "norm_city", "norm_icao")
        rows = (
            tuple(place.get(field) for field in PLACE_FIELDS)
            + (fold(place.get("name")), compact(place.get("ident")), fold(place.get("city")), compact(place.get("icao")))
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


def read_meta(connection):
    """The index's ``meta`` table as a dict."""
    return dict(connection.execute("SELECT key, value FROM meta"))
