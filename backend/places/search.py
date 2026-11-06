"""Searching the places index by name or identifier (docs/PLACE_SEARCH.md §2.2, *Ranking*).

1. **An identifier typed whole** (``KATL``, ``ATL``, ``DZJ``, ``1GA3``, ``BOBBY``) comes first, whatever the distance.
2. **Then names**: equal to the text; then starting with it; then an identifier starting with it (after the
   names, so a fix's ``ATLEE`` never pushes *Atlanta* away); then every word typed starting a word of the name,
   identifier or city, so ``gap`` finds *Neel Gap* and ``blairs`` the heliports of Blairsville.
3. **Within each group**: open before closed, nearest ``near`` first, then ``rank`` (``nasr.RANK_*``), then by name.

Fixes have only an identifier and are many (70,000), so they are matched only from three characters. Nothing
here logs what was asked.
"""

import math

from .text import fold

MIN_CHARS = 2
FIX_MIN_CHARS = 3
MAX_LIMIT = 25
# Matches read before ranking: a two-letter prefix can match tens of thousands, and every match is ranked.
CANDIDATES = 50_000

_FIX_KINDS = ("fix", "vfr_waypoint")


def query_words(text):
    """The words of ``text`` as the index holds them, or [] when it is too short to look up."""
    folded = fold(text)
    if len(folded.replace(" ", "")) < MIN_CHARS:
        return []
    return folded.split()


def search(connection, text, near=None, limit=8):
    """The places ``text`` could name, best first: a list of ``place`` rows as dicts (``sqlite3.Row`` keys).

    ``near`` is ``(lat, lon)`` or None; ``limit`` is clamped to 1..MAX_LIMIT.
    """
    words = query_words(text)
    if not words:
        return []
    limit = max(1, min(int(limit), MAX_LIMIT))
    whole = " ".join(words)
    squeezed = whole.replace(" ", "")
    # Every word a prefix: the folded text holds only [a-z0-9 ], so the words need no quoting.
    match = " ".join(f"{word}*" for word in words)
    lat, lon = near if near else (0.0, 0.0)
    # Flat distance, longitude shrunk by the latitude's cosine: enough to order places a few hundred km apart.
    # With no ``near`` every place is as near as the next (0), so rank and name decide.
    squash = math.cos(math.radians(lat)) ** 2 if near else 0.0
    fixes_too = len(squeezed) >= FIX_MIN_CHARS
    sql = f"""
        SELECT p.*,
          CASE
            WHEN p.norm_ident = :squeezed OR (p.norm_icao <> '' AND p.norm_icao = :squeezed) THEN 0
            WHEN p.norm_name = :whole THEN 1
            WHEN p.norm_name LIKE :prefix THEN 2
            WHEN p.norm_ident LIKE :ident_prefix THEN 3
            ELSE 4
          END AS grp,
          (((p.lat - :lat) * (p.lat - :lat) + (p.lon - :lon) * (p.lon - :lon) * :squash) * :near) AS d
        FROM place p
        WHERE (p.rowid IN (SELECT docid FROM place_fts WHERE place_fts MATCH :match LIMIT {CANDIDATES})
               OR p.norm_ident = :squeezed OR (p.norm_icao <> '' AND p.norm_icao = :squeezed))
          AND (:fixes_too OR p.kind NOT IN ({", ".join(f"'{k}'" for k in _FIX_KINDS)}))
        ORDER BY grp, p.status = 'closed', d, p.rank, coalesce(p.norm_name, ''), p.norm_ident
        LIMIT :limit
    """
    rows = connection.execute(sql, {
        "squeezed": squeezed, "whole": whole, "prefix": f"{whole}%", "ident_prefix": f"{squeezed}%",
        "match": match, "lat": lat, "lon": lon, "squash": squash, "near": 1 if near else 0, "fixes_too": 1 if fixes_too else 0, "limit": limit,
    }).fetchall()
    return [dict(row) for row in rows]


def as_answer(row):
    """One place as the API answers it (``POST /api/places/search``)."""
    return {
        "id": row["id"],
        "source": row["source"],
        "kind": row["kind"],
        "kindWords": row["detail"],
        "name": row["name"],
        "ident": row["ident"],
        "icao": row["icao"],
        "city": row["city"],
        "state": row["state"],
        "lat": row["lat"],
        "lon": row["lon"],
        "elevationFt": row["elev_ft"],
        "use": row["use"],
        "status": row["status"],
        "military": row["military"],
    }


__all__ = ["search", "as_answer", "query_words"]
