"""Searching the places index by name or identifier (docs/PLACE_SEARCH.md §2.2, *Ranking*).

1. **An identifier typed whole** (``KATL``, ``ATL``, ``DZJ``, ``1GA3``, ``BOBBY``) comes first, whatever the distance.
2. **Then names**: equal to the text; then starting with it; then an identifier starting with it (after the
   names, so a fix's ``ATLEE`` never pushes *Atlanta* away); then every word typed starting a word of the name,
   identifier or city, so ``gap`` finds *Neel Gap* and ``blairs`` the heliports of Blairsville.
3. **Near before far.** Every match but an identifier typed whole is put in one of two bands, within
   ``NEAR_KM`` of ``near`` or beyond it, and the groups above order each band: so ``blairs`` lists *Blairsville*
   in Georgia (a name starting with it, nearby) before the two *Blairs* of Alabama and Virginia (named it exactly,
   far off). With GNIS's 900,000 names an exact match somewhere in the country is the rule, not the exception.
4. **Within each group**: open before closed or historical, nearest ``near`` first, then ``rank`` (``nasr.RANK_*``,
   then ``gnis.RANK_*``: an airfield before a town, a town before a summit, a summit before a lake or a stream),
   then by name.

Fixes have only an identifier and are many (70,000), so they are matched only from three characters. Nothing
here logs what was asked.
"""

import math

from .text import fold

MIN_CHARS = 2
FIX_MIN_CHARS = 3
MAX_LIMIT = 25
# How far "near" reaches: about the ground a flight plans over (80 nm).
NEAR_KM = 150
_KM_PER_DEGREE = 111.2

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
    # Flat distance in squared degrees, longitude shrunk by the latitude's cosine: enough to order places a few
    # hundred km apart. With no ``near`` every place is as near as the next (0), so rank and name decide.
    squash = math.cos(math.radians(lat)) ** 2 if near else 0.0
    band = (NEAR_KM / _KM_PER_DEGREE) ** 2
    fixes_too = len(squeezed) >= FIX_MIN_CHARS
    sql = f"""
        SELECT p.*,
          CASE
            WHEN p.norm_ident = :squeezed OR p.norm_icao = :squeezed THEN 0
            WHEN p.norm_name = :whole THEN 1
            WHEN p.norm_name LIKE :prefix THEN 2
            WHEN p.norm_ident LIKE :ident_prefix THEN 3
            ELSE 4
          END AS grp,
          (((p.lat - :lat) * (p.lat - :lat) + (p.lon - :lon) * (p.lon - :lon) * :squash) * :near) AS d
        FROM place p
        WHERE (p.rowid IN (SELECT docid FROM place_fts WHERE place_fts MATCH :match)
               OR p.norm_ident = :squeezed OR p.norm_icao = :squeezed)
          AND (:fixes_too OR p.kind NOT IN ({", ".join(f"'{k}'" for k in _FIX_KINDS)}))
        ORDER BY grp > 0, d > :band, grp, p.status <> 'open', d, p.rank, coalesce(p.norm_name, ''),
                 coalesce(p.norm_ident, ''), p.id
        LIMIT :limit
    """
    rows = connection.execute(sql, {
        "squeezed": squeezed, "whole": whole, "prefix": f"{whole}%", "ident_prefix": f"{squeezed}%",
        "match": match, "lat": lat, "lon": lon, "squash": squash, "near": 1 if near else 0, "band": band,
        "fixes_too": 1 if fixes_too else 0, "limit": limit,
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
        "county": row["county"],
        "state": row["state"],
        "lat": row["lat"],
        "lon": row["lon"],
        "elevationFt": row["elev_ft"],
        "use": row["use"],
        "status": row["status"],
        "military": row["military"],
    }


__all__ = ["search", "as_answer", "query_words"]
