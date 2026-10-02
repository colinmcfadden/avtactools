"""Golden SQLite fixtures: AMPS `.LPS` local points and `.ths` threat files.

Both file types are SQLite databases. The native apps read them with a small
reader of their own (the web does the same, `sqliteReader.js`), so the reader
needs a reference that is not itself: Python's `sqlite3`, SQLite's own library,
says what every table in these files contains (`tables.json`). The web reader and
the Kotlin reader are both held to that.

Three kinds of file are written to `contracts/fixtures/sqlite/`:

* `local-points.lps`, `local-points-large.lps` — synthetic `.LPS` files: a Points
  table with SpatiaLite POINT blobs, including the rows a reader must skip. The
  large one has a b-tree with interior pages and a value that spills onto
  overflow pages. No real mission data; a real, unclassified `.LPS` from the owner
  would be the better test and is added to this folder when there is one.
* `local-points-unreadable.lps`, `threats-empty.ths` — files with a table and nothing a
  reader can use, for the "no readable points / threats" errors.
* `threats.ths` — what the backend's own exporter (`backend/ths_export.py`) writes
  for the threats in `threats/export.json`, so the reader is tried on real
  exporter output, and the native exporters are held to its rows.
* `values.db` — one value of every storage class and integer width, positive and negative.
* `utf16le.db`, `utf16be.db` — text stored as UTF-16. The web's reader assumes UTF-8 and cannot read
  them; the native readers can (`frontend/src/contracts/sqliteFixtures.test.js` skips them).
* `deep-tree.db` — a table three b-tree levels deep.

    python contracts/scripts/sqlite_fixtures.py write   # regenerate everything
    python contracts/scripts/sqlite_fixtures.py check   # fail on drift

`check` compares content, not bytes: the bytes of a SQLite file carry the library
version that wrote them, so they differ between machines while meaning the same.
`tables.json` is checked against the committed files, and the committed files'
sources (this script, `ths_export.py`) are checked against `tables.json`.
"""

import json
import sqlite3
import struct
import sys
import tempfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures" / "sqlite"
THREAT_FIXTURES = ROOT / "fixtures" / "threats"
sys.path.insert(0, str(ROOT.parent / "backend"))

import ths_export  # noqa: E402  (stdlib only: no Flask, numpy or terrain source needed)

# What AMPS writes as a threat's DATE_TIME (DDHHMMSSMMYYYY), fixed so the file is reproducible.
NOW = datetime(2026, 10, 2, 12, 34, 56)

LPS_SCHEMA = (
    "CREATE TABLE Points ("
    "Pedigree INTEGER PRIMARY KEY, ID TEXT, Description TEXT, GroupName TEXT, "
    "IconName TEXT, Elevation REAL, Coordinate BLOB)"
)


# -- SpatiaLite geometry -------------------------------------------------------

def spatialite_point(lon, lat, little_endian=True, srid=4326, geometry_class=1):
    """A SpatiaLite POINT blob: [0]=0x00 [1]=endian [2..5]=SRID [6..37]=MBR [38]=0x7C [39..42]=class [43..58]=x,y [59]=0xFE."""
    e = "<" if little_endian else ">"
    return (
        b"\x00" + (b"\x01" if little_endian else b"\x00")
        + struct.pack(e + "i", srid)
        + struct.pack(e + "4d", lon, lat, lon, lat)
        + b"\x7c" + struct.pack(e + "I", geometry_class)
        + struct.pack(e + "2d", lon, lat)
        + b"\xfe"
    )


# -- The files -----------------------------------------------------------------

def _database(build, page_size=None):
    """Runs `build(connection)` on a fresh database and returns its bytes."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "x.db"
        con = sqlite3.connect(path)
        if page_size:
            con.execute(f"PRAGMA page_size = {page_size}")
        build(con)
        con.commit()
        con.close()
        return path.read_bytes()


def local_points():
    def build(con):
        con.execute(LPS_SCHEMA)
        rows = [
            # A normal point; the elevation is feet.
            (1, "3MILE", "Three mile marker", "Default", "Waypoint", 921, spatialite_point(-84.481, 33.948)),
            # Text is trimmed; a missing description is empty.
            (2, "  PAD 7 ", None, "Default", "Helipad", 1180.5, spatialite_point(-84.1, 34.5)),
            # A blank-but-present group stays blank (only an *empty* one becomes "Default"); an empty icon stays empty.
            (3, "BLANKGRP", "", "   ", "", None, spatialite_point(-84.2, 34.6)),
            # An empty group name becomes "Default".
            (4, "NOGROUP", "", "", "Waypoint", 12, spatialite_point(-84.3, 34.7)),
            # A big-endian geometry reads the same.
            (5, "BIGENDIAN", "", "Default", "", 100, spatialite_point(-83.5, 33.25, little_endian=False)),
            # Rows that are not a point, or not readable, are skipped.
            (6, "LINE", "", "Default", "", 0, spatialite_point(-84.0, 34.0, geometry_class=2)),
            (7, "NOCOORD", "", "Default", "", 0, None),
            (8, "SHORT", "", "Default", "", 0, spatialite_point(-84.0, 34.0)[:58]),
            (9, "BADLEAD", "", "Default", "", 0, b"\x01" + spatialite_point(-84.0, 34.0)[1:]),
            # An elevation stored as text is not a number, so it reads as no elevation. (Digits would not do:
            # the column is REAL, so SQLite turns "921" into 921.0 on the way in.)
            (10, "TEXTELEV", "", "Default", "", "n/a", spatialite_point(-84.4, 34.1)),
            # A point with no name is still a point.
            (11, None, None, None, None, None, spatialite_point(-84.5, 34.2)),
            # Unicode, newlines and tabs survive.
            (12, "ÀÉÎ ⛰", "Line one\nLine\ttwo 😀", "Grp ☃", "Icon", -12.5, spatialite_point(0.0, 0.0)),
            # The edges of the globe.
            (13, "EDGE", "", "Default", "", 29029, spatialite_point(179.99999, -89.99999)),
        ]
        con.executemany("INSERT INTO Points VALUES (?,?,?,?,?,?,?)", rows)
    return _database(build)


def local_points_large():
    """A b-tree with interior pages (small pages, many rows) and a value that spills onto overflow pages."""
    def build(con):
        con.execute(LPS_SCHEMA)
        rows = []
        for i in range(1, 421):
            lon = -85.0 + (i % 40) * 0.025
            lat = 33.0 + (i // 40) * 0.05
            rows.append((i, f"PT{i:04d}", f"Point number {i}", "Default" if i % 3 else "Alt", "Waypoint",
                         900 + i, spatialite_point(round(lon, 6), round(lat, 6))))
        # A description far bigger than a page: its value lives on a chain of overflow pages.
        rows.append((421, "SPILL", "x" * 5000 + " end of a long description", "Default", "", 1, spatialite_point(-84.9, 34.9)))
        rows.append((422, "AFTER", "", "Default", "", 2, spatialite_point(-84.8, 34.8)))
        con.executemany("INSERT INTO Points VALUES (?,?,?,?,?,?,?)", rows)
    return _database(build, page_size=1024)


def local_points_unreadable():
    """A Points table with nothing a reader can use: every row is skipped."""
    def build(con):
        con.execute(LPS_SCHEMA)
        con.executemany("INSERT INTO Points VALUES (?,?,?,?,?,?,?)", [
            (1, "NOCOORD", "", "Default", "", 0, None),
            (2, "LINE", "", "Default", "", 0, spatialite_point(-84.0, 34.0, geometry_class=2)),
        ])
    return _database(build)


def value_types():
    """One value of every storage class and integer width, either sign, in a column with no type affinity."""
    def build(con):
        con.execute("CREATE TABLE Mixed (ID INTEGER PRIMARY KEY, KIND TEXT, V)")
        values = [("null", None)]
        # Integers either side of every width SQLite stores them in (1, 2, 3, 4, 6 and 8 bytes), and 0 and 1,
        # which have record types of their own. Nothing past 2**53, which a JavaScript number cannot hold.
        for bound in (7, 15, 23, 31, 47, 52):
            edge = 2 ** bound
            values += [("int", v) for v in (edge - 1, -edge, edge, -edge - 1) if abs(v) < 2 ** 53]
        values += [("int", v) for v in (0, 1, -1, 2, -2, 9007199254740991, -9007199254740991)]
        # Reals, including ones that are whole numbers (stored as integers on disk) and both zeros.
        values += [("real", v) for v in (0.5, -0.5, 3.141592653589793, -2.718281828459045, 1e300, -1e300, 5e-324,
                                         921.0, -86.0, -282.0, 1180.5, 0.0, -0.0)]
        values += [("text", v) for v in ("", "a", "Hello, world", "ÀÉÎ ⛰ 😀", "tab\tnew\nline", "x" * 300)]
        values += [("blob", v) for v in (b"", b"\x00", b"\x00\x01\xfe\xff", bytes(range(256)))]
        con.executemany("INSERT INTO Mixed (KIND, V) VALUES (?, ?)", values)
    return _database(build)


def utf16(order):
    """A database whose text is stored as UTF-16 (`order` is "le" or "be"), which SQLite supports and the web's reader does not."""
    def build(con):
        con.execute(f"PRAGMA encoding = 'UTF-16{order}'")
        con.execute("CREATE TABLE Words (ID INTEGER PRIMARY KEY, W TEXT)")
        con.executemany("INSERT INTO Words (W) VALUES (?)", [("hello",), ("ÀÉÎ ⛰",), ("emoji 😀 pair",), ("",)])
    return _database(build)


def deep_tree():
    """3000 rows in 512-byte pages: the root is an interior page whose children are interior pages."""
    def build(con):
        con.execute("CREATE TABLE Numbers (ID INTEGER PRIMARY KEY, SQUARE INTEGER, LABEL TEXT)")
        con.executemany(
            "INSERT INTO Numbers VALUES (?,?,?)",
            [(i, i * i, f"n{i:05d}") for i in range(1, 3001)],
        )
    data = _database(build, page_size=512)
    return data


# The threats the .ths is built from, in the shape the web sends (`threatToPayload`).
def _bands(colors):
    return [
        {"altFt": alt, "color": "#aaaaaa", "alpha": 0.3, "colorIndex": c, "viewable": True}
        for alt, c in zip((50, 250, 500), colors)
    ]


def _radar(kind, range_nmi, bands, antenna=20, agl=True, mask=True, rings=True):
    return {
        "type": kind, "rangeNmi": range_nmi, "antennaHeightFt": antenna, "aglNotMsl": agl,
        "showMask": mask, "showRangeRings": rings, "bands": bands,
    }


def export_threats():
    emoji_name = "A" * 49 + "😀" + "tail"            # the 50th character is an emoji
    return [
        # What the web makes by default.
        {
            "name": "SA-6 Gainful", "milstdId": "SHGPEWMAI------", "lat": 34.5, "lon": -84.25,
            "information": "Default threat", "source": "SOF", "showThreat": True,
            "radars": [
                _radar(0, 25, _bands((1, 3, 5))),
                _radar(1, 15, _bands((2, 4, 0))),
            ],
        },
        # Long text is cut where the AMPS columns end: 50 characters, 15, 255 and 32 — characters, not UTF-16 units.
        {
            "name": emoji_name, "milstdId": "SHGPEWMAI------EXTRA", "lat": 33.123456789, "lon": -83.987654321,
            "information": "é" * 300, "source": "S" * 40, "showThreat": True,
            "radars": [_radar(1, 8.5, _bands((2, 4, 0)), antenna=33.7, agl=False)],
        },
        # Empty name, id and source take their defaults ("Threat 3", the generic id, "SOF").
        {
            "name": "", "milstdId": "", "lat": -12.5, "lon": 130.75, "information": "", "source": "",
            "showThreat": False,
            "radars": [_radar(0, 12, _bands((1, 3, 5)), mask=False, rings=False)],
        },
        # Two bands only: the exporter pads the colours it did not get, and what that does to the third
        # colour is part of the file. A fractional altitude is cut toward zero, a band can be hidden.
        {
            "name": "Two bands", "milstdId": "SHGPEWMAI------", "lat": 35.0, "lon": -85.0,
            "information": "", "source": "SOF", "showThreat": True,
            "radars": [_radar(1, 10, [
                {"altFt": 250.9, "color": "#ff0000", "alpha": 0.4, "colorIndex": 7, "viewable": False},
                {"altFt": -0.4, "color": "#00ff00", "alpha": 0.4, "colorIndex": 8, "viewable": True},
            ])],
        },
        # Four bands: the fourth is ignored. No bands at all: the defaults.
        {
            "name": "Many bands", "milstdId": "SHGPEWMAI------", "lat": 36.0, "lon": -86.0,
            "information": "", "source": "SOF", "showThreat": True,
            "radars": [
                _radar(0, 30, [
                    {"altFt": 100, "color": "#111111", "alpha": 0.2, "colorIndex": 9, "viewable": True},
                    {"altFt": 200, "color": "#222222", "alpha": 0.2, "colorIndex": 10, "viewable": False},
                    {"altFt": 300, "color": "#333333", "alpha": 0.2, "colorIndex": 11, "viewable": True},
                    {"altFt": 400, "color": "#444444", "alpha": 0.2, "colorIndex": 12, "viewable": False},
                ]),
                _radar(1, 5, []),
            ],
        },
        # No radars: it is a threat marker with nothing to mask.
        {
            "name": "Marker only", "milstdId": "SHGPUCI----K---", "lat": 37.0, "lon": -87.0,
            "information": "No radar", "source": "SOF", "showThreat": True, "radars": [],
        },
        # Spaces are text: only an *empty* name, id or source takes its default, so these are written as they are.
        {
            "name": "   ", "milstdId": " ", "lat": 38.0, "lon": -88.0,
            "information": " ", "source": " ", "showThreat": True,
            "radars": [_radar(0, 5, _bands((1, 3, 5)))],
        },
    ]


def threats_ths():
    return ths_export.build_ths_bytes(export_threats(), now=NOW)


def threats_empty():
    """The exporter's output for no threats: the schema and nothing else."""
    return ths_export.build_ths_bytes([], now=NOW)


FILES = {
    "local-points.lps": local_points,
    "local-points-large.lps": local_points_large,
    "local-points-unreadable.lps": local_points_unreadable,
    "threats.ths": threats_ths,
    "threats-empty.ths": threats_empty,
    "values.db": value_types,
    "utf16le.db": lambda: utf16("le"),
    "utf16be.db": lambda: utf16("be"),
    "deep-tree.db": deep_tree,
}


# -- Reading them back with SQLite itself --------------------------------------

def _json_value(value):
    if isinstance(value, bytes):
        return {"hex": value.hex()}
    return value


def dump(data):
    """Every ordinary table of a database as `{table: {columns, rows}}`, as SQLite reads it."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "x.db"
        path.write_bytes(data)
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        tables = {}
        names = [
            name for (name, sql) in con.execute("SELECT name, sql FROM sqlite_master WHERE type = 'table'")
            if not name.startswith("sqlite_") and not (sql or "").upper().startswith("CREATE VIRTUAL")
        ]
        for name in sorted(names):
            columns = [row[1] for row in con.execute(f'PRAGMA table_info("{name}")')]
            rows = [[_json_value(v) for v in row] for row in con.execute(f'SELECT * FROM "{name}" ORDER BY rowid')]
            tables[name] = {"columns": columns, "rows": rows}
        con.close()
        return tables


def page_size(data):
    size = int.from_bytes(data[16:18], "big")
    return 65536 if size == 1 else size


def table_depth(data, table):
    """How many page levels a table's b-tree has (1 = a single leaf), read straight from the pages."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "x.db"
        path.write_bytes(data)
        con = sqlite3.connect(path)
        (root,) = con.execute("SELECT rootpage FROM sqlite_master WHERE name = ?", (table,)).fetchone()
        con.close()
    size = page_size(data)
    depth = 1
    while True:
        page = data[(root - 1) * size:root * size]
        if page[0] == 13:
            return depth
        assert page[0] == 5, f"unexpected page type {page[0]}"
        root = int.from_bytes(page[8:12], "big")              # the right-most child
        depth += 1


def _dumps(value):
    """One row per line, so a regenerated file diffs row by row."""
    out = ["{"]
    names = list(value)
    for i, name in enumerate(names):
        table = value[name]
        out.append(f'  {json.dumps(name)}: {{')
        for j, (tname, t) in enumerate(table.items()):
            out.append(f'    {json.dumps(tname)}: {{')
            out.append(f'      "columns": {json.dumps(t["columns"])},')
            out.append('      "rows": [')
            for k, row in enumerate(t["rows"]):
                out.append("        " + json.dumps(row, ensure_ascii=False) + ("," if k < len(t["rows"]) - 1 else ""))
            out.append("      ]")
            out.append("    }" + ("," if j < len(table) - 1 else ""))
        out.append("  }" + ("," if i < len(names) - 1 else ""))
    out.append("}")
    return "\n".join(out) + "\n"


def write():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    THREAT_FIXTURES.mkdir(parents=True, exist_ok=True)
    contents = {}
    for name, make in FILES.items():
        data = make()
        (FIXTURES / name).write_bytes(data)
        contents[name] = dump(data)

    assert table_depth((FIXTURES / "deep-tree.db").read_bytes(), "Numbers") >= 3, "deep-tree.db is not deep enough"
    assert table_depth((FIXTURES / "local-points-large.lps").read_bytes(), "Points") >= 2
    assert max(len(r[2]) for r in contents["local-points-large.lps"]["Points"]["rows"]) > 5000   # the spill row

    (FIXTURES / "tables.json").write_text(_dumps(contents), encoding="utf-8")
    (THREAT_FIXTURES / "export.json").write_text(
        json.dumps(
            {"nowUtc": NOW.isoformat() + "Z", "dtg": ths_export._amps_dtg(NOW), "threats": export_threats(),
             "tables": "sqlite/tables.json, key threats.ths"},
            ensure_ascii=False, indent=1,
        ) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {len(FILES)} databases to {FIXTURES}")


def check():
    committed = json.loads((FIXTURES / "tables.json").read_text(encoding="utf-8"))

    # 1. The committed databases hold what tables.json says, as SQLite itself reads them.
    for name in FILES:
        actual = dump((FIXTURES / name).read_bytes())
        if actual != committed[name]:
            raise AssertionError(f"{name}: SQLite reads something other than tables.json says")

    # 2. The sources still produce that content (not those bytes: they carry the SQLite version).
    for name, make in FILES.items():
        if dump(make()) != committed[name]:
            raise AssertionError(f"{name}: the generator no longer produces what tables.json says")

    # 3. The exporter's inputs are the ones committed for the native exporters.
    export = json.loads((THREAT_FIXTURES / "export.json").read_text(encoding="utf-8"))
    if (export["threats"] != export_threats() or export["dtg"] != ths_export._amps_dtg(NOW)
            or export["nowUtc"] != NOW.isoformat() + "Z"):
        raise AssertionError("threats/export.json no longer matches the exporter's inputs")

    # 4. The files are what they claim to be.
    assert table_depth((FIXTURES / "deep-tree.db").read_bytes(), "Numbers") >= 3
    assert table_depth((FIXTURES / "local-points-large.lps").read_bytes(), "Points") >= 2
    print("sqlite fixtures are consistent with SQLite and the exporter")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    if command == "write":
        write()
    elif command == "check":
        check()
    else:
        sys.exit(f"usage: {sys.argv[0]} write|check")
