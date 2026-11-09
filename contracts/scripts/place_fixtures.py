"""Golden place-search fixtures: a small index built from real NASR and GNIS rows, and what it answers.

The server builds the places index (``backend/places``) and answers ``POST /api/places/search`` from it; Android
is to search its own copy of the same file on the device (docs/PLACE_SEARCH.md §7). Both must give the same places
in the same order, so the fixture is the index itself and the answers the server's code gives over it:

* ``sources/`` — real rows, unchanged, from the FAA's NASR CSV extract of the 10-29-2026 cycle (``APT_BASE.csv``,
  ``NAV_BASE.csv``, ``FIX_BASE.csv``: north Georgia round Blairsville, and a few airports, navaids and towns
  elsewhere) and USGS's GNIS national file of 2026-11-02 (``DomesticNames_<ST>.txt``: the names round Blairsville,
  Civil and Census rows among them, a river listed in two states, and towns named alike in nine states). Public
  data, unclassified.
* ``places.sqlite`` — the index ``backend/places/index.py`` builds from them, with a fixed build time.
* ``queries.json`` — each query (text, where the map is, how many), and the places answered, in order, as the API
  says them (``search.as_answer``).

    python contracts/scripts/place_fixtures.py write   # rebuild the index and the answers
    python contracts/scripts/place_fixtures.py check   # fail on drift

``check`` rebuilds the index from ``sources/`` and asks every query of the rebuilt index and of the committed one;
both must answer as ``queries.json`` says. It compares answers and the index's meta, never bytes: a SQLite file's
bytes carry the library version that wrote it.
"""

import json
import os
import shutil
import sqlite3
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures" / "places"
SOURCES = FIXTURES / "sources"
sys.path.insert(0, str(ROOT.parent / "backend"))

from places import gnis, index, nasr, search  # noqa: E402  (stdlib only: no Flask or requests needed)

CYCLE = "2026-10-29"
GNIS_DATE = (2026, 11, 2)
BUILT_AT = "2026-11-05T00:00:00Z"
NASR_ZIPS = {"APT_BASE.csv": "29_Oct_2026_APT_CSV.zip", "NAV_BASE.csv": "29_Oct_2026_NAV_CSV.zip",
             "FIX_BASE.csv": "29_Oct_2026_FIX_CSV.zip"}

BLAIRSVILLE = [34.8, -83.9]
QUERIES = [
    # An identifier typed whole comes first, whatever the distance; its ICAO too.
    {"q": "dzj", "near": BLAIRSVILLE},
    {"q": "DZJ", "near": None},
    {"q": "katl", "near": BLAIRSVILLE},
    {"q": "atl", "near": BLAIRSVILLE},
    {"q": "kozr", "near": BLAIRSVILLE},
    {"q": "1ga3", "near": BLAIRSVILLE},
    # A name: near before far, then the name's own matches before a city's.
    {"q": "blairs", "near": BLAIRSVILLE},
    {"q": "blairs", "near": None},
    {"q": "blairsville", "near": BLAIRSVILLE},
    {"q": "blairsville", "near": [40.9, -75.0]},
    {"q": "union general", "near": BLAIRSVILLE},
    {"q": "atlanta", "near": BLAIRSVILLE},
    {"q": "cairns", "near": BLAIRSVILLE},
    # GNIS's names round Blairsville: terrain, water, a gap; a historical name after the places that are.
    {"q": "brasstown", "near": BLAIRSVILLE},
    {"q": "neel", "near": BLAIRSVILLE},
    {"q": "neel gap", "near": BLAIRSVILLE},
    {"q": "levelland", "near": BLAIRSVILLE},
    {"q": "nottely", "near": BLAIRSVILLE},
    {"q": "hiwassee", "near": BLAIRSVILLE},
    # GNIS's Civil and Census areas are left out: their towns are found, never twice.
    {"q": "city of blairsville", "near": BLAIRSVILLE},
    # Towns named alike: the nearest first, from wherever the map is.
    {"q": "mount pleasant", "near": BLAIRSVILLE, "limit": 25},
    {"q": "mount pleasant", "near": [40.5, -79.5], "limit": 5},
    {"q": "mount pleasant", "near": None, "limit": 5},
    {"q": "mt pleasant", "near": BLAIRSVILLE},
    # Elsewhere: a parish, a borough, a city of its own, a territory, an installation.
    {"q": "fort polk", "near": None},
    {"q": "nome", "near": None},
    {"q": "suffolk", "near": None},
    {"q": "ponce", "near": None},
    {"q": "fort novosel", "near": BLAIRSVILLE},
    # Too little text, or a fix's identifier not yet long enough: nothing, or no fixes.
    {"q": "b", "near": BLAIRSVILLE},
    {"q": " . ", "near": BLAIRSVILLE},
    {"q": "ga", "near": BLAIRSVILLE, "limit": 3},
    {"q": "zzzq", "near": BLAIRSVILLE},
]


def _zips(folder):
    """The sources as the FAA and USGS publish them: one zip per NASR area, GNIS's national file."""
    paths = []
    for member, name in NASR_ZIPS.items():
        path = os.path.join(folder, name)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.write(SOURCES / member, member)
        paths.append(path)
    gnis_path = os.path.join(folder, "DomesticNames_AllStates_Text.zip")
    with zipfile.ZipFile(gnis_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for text in sorted(SOURCES.glob("DomesticNames_*.txt")):
            info = zipfile.ZipInfo(f"Text/{text.name}", date_time=(*GNIS_DATE, 12, 0, 0))
            archive.writestr(info, text.read_bytes(), zipfile.ZIP_DEFLATED)
    return paths, gnis_path


def build(path):
    """Build the fixture's index at ``path`` from ``sources/``, as the server's refresh builds its own."""
    folder = tempfile.mkdtemp()
    try:
        zips, gnis_zip = _zips(folder)
        cycle, gnis_date = nasr.effective_date(zips), gnis.file_date(gnis_zip)
        assert cycle == CYCLE, cycle

        def places():
            yield from nasr.read(zips)
            yield from gnis.read(gnis_zip)

        index.build(places(), path, {"nasr_cycle": cycle, "gnis_date": gnis_date,
                                     "build": index.build_name(cycle, gnis_date), "built_at": BUILT_AT})
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def _open(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def answers(path):
    """``queries.json``'s content for the index at ``path``."""
    connection = _open(path)
    try:
        cases = []
        for query in QUERIES:
            limit = query.get("limit", 8)
            near = tuple(query["near"]) if query["near"] else None
            rows = search.search(connection, query["q"], near, limit)
            cases.append({**query, "limit": limit, "places": [search.as_answer(row) for row in rows]})
        meta = index.read_meta(connection)
    finally:
        connection.close()
    return {
        "description": (
            "What the place search answers over places.sqlite (built from sources/ by contracts/scripts/"
            "place_fixtures.py), in order, as POST /api/places/search says each place. 'near' is [lat, lon] or null "
            "(the server rounds it to 0.1 degrees first; these are already round). Every client's search over the "
            "same index must give the same places in the same order."
        ),
        "meta": meta,
        "cases": cases,
    }


def _json(value):
    return json.dumps(value, indent=1, ensure_ascii=False, sort_keys=False) + "\n"


def write():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    target = FIXTURES / "places.sqlite"
    if target.exists():
        target.unlink()
    build(str(target))
    (FIXTURES / "queries.json").write_text(_json(answers(str(target))), encoding="utf-8")
    print(f"wrote {target.name} and queries.json")


def check():
    """Problems, in words; none when the committed files are what ``sources/`` and the code make today."""
    expected = json.loads((FIXTURES / "queries.json").read_text(encoding="utf-8"))
    problems = []
    folder = tempfile.mkdtemp()
    try:
        rebuilt = os.path.join(folder, "places.sqlite")
        build(rebuilt)
        for label, path in (("rebuilt from sources/", rebuilt), ("committed", str(FIXTURES / "places.sqlite"))):
            got = answers(path)
            if got["meta"] != expected["meta"]:
                problems.append(f"the {label} index's meta differs: {got['meta']}")
            for want, have in zip(expected["cases"], got["cases"]):
                if want != have:
                    names = [p["ident"] or p["name"] for p in have["places"]]
                    problems.append(f"the {label} index answers {want['q']!r} near {want['near']} with {names}")
            if len(expected["cases"]) != len(got["cases"]):
                problems.append(f"queries.json has {len(expected['cases'])} cases, the script {len(got['cases'])}")
    finally:
        shutil.rmtree(folder, ignore_errors=True)
    return problems


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    if command == "write":
        write()
    else:
        found = check()
        for problem in found:
            print(problem, file=sys.stderr)
        sys.exit(1 if found else 0)
