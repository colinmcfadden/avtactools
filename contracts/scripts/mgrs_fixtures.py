"""Golden MGRS fixtures, computed by PyGeodesy.

PyGeodesy is the reference the backend itself uses (`routes/location_routes.py`),
so these files say what the server would answer. The web (`utils/mgrs.js`), the
Android app and the iOS app each convert on the device and must agree with them.

    python contracts/scripts/mgrs_fixtures.py write   # regenerate the JSON
    python contracts/scripts/mgrs_fixtures.py check   # fail if the JSON drifted

Generation is deterministic (fixed seed, no clock), so `check` is exact and the
diff of a regenerated file only ever shows a real change in PyGeodesy or here.

Needs `pygeodesy`, pinned in backend/requirements.txt.
"""

import json
import random
import sys
from pathlib import Path

from pygeodesy import mgrs
from pygeodesy.ellipsoidalExact import LatLon

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "mgrs"
SEED = 20261002

# Tolerance for lat/lon answers. Both sides are Krueger series good to
# nanometres; 1e-8 degrees (~1 mm) is the loosest we promise anyone.
TOLERANCE_DEG = 1e-8

BANDS = "CDEFGHJKLMNPQRSTUVWX"
COLUMN_SETS = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"]
ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"


def to_mgrs(lat, lon, digits=5):
    """Forward conversion exactly as /api/convert-to-mgrs does it (5 digits, 1 m).

    PyGeodesy's `prec` counts digits beyond 1 m, so 5 digits is `prec=0`, and a
    coarser grid is a negative `prec`. Those truncate, as MGRS defines (52959 at
    10 m is 5295, not 5296), which matches `toMgrs(..., { digits })` on the web.
    """
    grid = LatLon(lat, lon).toMgrs()
    return grid.toStr(prec=digits - 5, sep=" ")


def to_latlon(grid):
    """Inverse exactly as /api/convert-grid does it: the square's centre."""
    ll = mgrs.parseMGRS(grid.replace(" ", "").upper()).toLatLon()
    return ll.lat, ll.lon


def r6(value):
    return round(value, 6)


# -- Forward ---------------------------------------------------------------

# Hand-picked points from frontend/src/utils/mgrs.test.js, each there for a
# reason. Kept here so the fixture stands alone and the names travel with it.
NAMED = [
    ("an LZ in north Georgia", 34.783817, -84.08219),
    ("another, in the next square east", 34.596407, -84.128098),
    ("UTM zone 17, east of Georgia's zone line", 34.64815, -83.8613),
    ("the southern hemisphere", -33.8688, 151.2093),
    ("just north of the equator", 0.0001, 36.8219),
    ("just south of the equator", -0.0001, 36.8219),
    ("a single-digit zone, padded", 21.3069, -157.8583),
    ("Norway's widened zone 32", 60.39, 5.32),
    ("Svalbard's zone 33", 78.2232, 15.6267),
    ("Svalbard's zone 31", 79.0, 5.0),
    ("Svalbard's zone 35", 79.0, 25.0),
    ("Svalbard's zone 37", 80.0, 35.0),
    ("the edge of the UTM range, 80S", -79.9999, 10.0),
    ("the antimeridian", 52.0, 179.9999),
    ("just across it", 52.0, -179.9999),
    ("a zone boundary, west side", 34.5, -84.0001),
    ("a zone boundary, east side", 34.5, -84.0),
    ("a band boundary, below", 39.9999, -105.0),
    ("a band boundary, above", 40.0, -105.0),
]

# Points the SPA and the apps answer null for: MGRS covers the polar caps with
# UPS, which no landing zone here needs.
NO_ANSWER = [[84.0, 0.0], [-80.0001, 0.0], [90.0, 0.0], [-90.0, 0.0], [85.5, 100.0]]


def edge_points():
    """Every place a zone, band or exception boundary could be mishandled."""
    pts = []
    eps = 0.0001
    # Every 6 degree zone line, at three latitudes, on and either side of it.
    for zone_line in range(-180, 181, 6):
        for lat in (0.5, 34.5, -34.5, 66.0):
            for dlon in (-eps, 0.0, eps):
                lon = zone_line + dlon
                if -180 <= lon < 180:
                    pts.append((lat, lon))
    # Every band boundary, at several longitudes.
    for k in range(0, 21):
        lat_edge = -80 + 8 * k
        for lon in (-120.5, -84.08, 0.5, 100.25):
            for dlat in (-eps, 0.0, eps):
                lat = lat_edge + dlat
                if -80 <= lat < 84:
                    pts.append((lat, lon))
    # Norway's widened zone 32 and Svalbard's four odd zones.
    for lat in (55.9999, 56.0, 63.9999, 64.0):
        for lon in (2.9999, 3.0, 11.9999, 12.0):
            pts.append((lat, lon))
    for lat in (71.9999, 72.0, 83.9999):
        for lon in (-0.0001, 0.0, 8.9999, 9.0, 20.9999, 21.0, 32.9999, 33.0, 41.9999, 42.0):
            pts.append((lat, lon))
    # The antimeridian and the equator, both sides.
    for lat in (-0.0001, 0.0, 0.0001, 52.0):
        for lon in (179.9999, -180.0, -179.9999, 0.0, 3.0):
            pts.append((lat, lon))
    return [(r6(a), r6(b)) for a, b in pts]


def random_points(rng):
    pts = []
    # The world, within MGRS's UTM range.
    for _ in range(2400):
        pts.append((rng.uniform(-80, 84 - 1e-6), rng.uniform(-180, 180 - 1e-6)))
    # CONUS and the Caribbean, where the users are.
    for _ in range(1600):
        pts.append((rng.uniform(24, 50), rng.uniform(-125, -66)))
    # North Georgia, where the owner flies (Ellijay, Dahlonega, Fort Benning...).
    for _ in range(600):
        pts.append((rng.uniform(32.0, 35.0), rng.uniform(-85.5, -83.0)))
    return [(r6(a), r6(b)) for a, b in pts]


def forward_cases():
    rng = random.Random(SEED)
    seen = set()
    points = []
    for _, lat, lon in NAMED:
        points.append((lat, lon))
    points += edge_points()
    points += random_points(rng)
    cases = []
    for lat, lon in points:
        if (lat, lon) in seen:
            continue
        seen.add((lat, lon))
        cases.append([lat, lon, to_mgrs(lat, lon)])
    return cases


def forward_document():
    named = []
    for name, lat, lon in NAMED:
        named.append({"name": name, "lat": lat, "lon": lon, "mgrs": to_mgrs(lat, lon)})
    return {
        "description": "Latitude/longitude to a 1 m MGRS grid (digits are truncated, "
                       "never rounded; the zone is zero-padded). Computed by PyGeodesy, "
                       "as /api/convert-to-mgrs does.",
        "reference": "pygeodesy.ellipsoidalExact.LatLon.toMgrs().toStr(sep=' ')",
        "generator": "contracts/scripts/mgrs_fixtures.py",
        "seed": SEED,
        "columns": ["lat", "lon", "mgrs"],
        "named": named,
        "noAnswer": NO_ANSWER,
        "cases": forward_cases(),
    }


# -- Inverse ---------------------------------------------------------------

def valid_grid(rng):
    """A random, internally consistent grid at 1..5 digits."""
    while True:
        lat = rng.uniform(-80, 84 - 1e-6)
        lon = rng.uniform(-180, 180 - 1e-6)
        digits = rng.randint(1, 5)
        return to_mgrs(r6(lat), r6(lon), digits)


def inconsistent_grid(rng):
    """Zone, band and square letters that need not describe the same place.

    The server never checks that a band agrees with the northing, so the answer
    depends on exactly how the 2,000 km row cycle is resolved. Porting that is
    the point of these cases.
    """
    zone = rng.randint(1, 60)
    band = rng.choice(BANDS)
    column = rng.choice(COLUMN_SETS[(zone - 1) % 3])
    row = rng.choice(ROW_LETTERS)
    digits = rng.randint(1, 5)
    e = "".join(rng.choice("0123456789") for _ in range(digits))
    n = "".join(rng.choice("0123456789") for _ in range(digits))
    return f"{zone:02d}{band} {column}{row} {e} {n}"


# Strings the server refuses. The native apps return "no answer" for each.
INVALID = [
    "",
    "16S",
    "16SGD",                 # no digits: not enough to place a point
    "16SGD6",                # odd digit count
    "16SGD123",
    "16SGD12345678901",      # odd digit count again, at the long end
    "00SGD12341234",         # zone 0
    "61SGD12341234",         # zone 61
    "16ISD12341234",         # band I is never used
    "16OGD12341234",         # band O is never used
    "16SID12341234",         # square letter I is never used
    "16SGO12341234",         # square letter O is never used
    "16SJD12341234",         # column J does not exist in zone 16's set
    "not a grid",
    "16S GD 6699 5294 7",
    "016SGD66995294",        # a zone is one or two digits
    "+16SGD66995294",
    "16SGD-66995294",
]

# Accepted by the server and so by the apps: spacing and case are forgiven, and
# any even digit count works. Past ten digits the grid reads to a tenth of a
# metre (12 digits) or a hundredth (14), which PyGeodesy does not refuse.
LENIENT = [
    "16sgd66995294",
    "16S GD 66 52",
    "  16S  GD  66995  52949  ",
    "16SGD6699352949",
    "16SGD123456789012",
    "16SGD12345678901234",
    # One- and two-digit zones are the same zone; the zone is read as typed.
    "4QFJ12345678",
    "04QFJ12345678",
    "1SGD66995294",
    "16 S GD 6699 5294",
    "16S GD66 99 5294",
    # Squares either side of the antimeridian: PyGeodesy folds longitude into +-180.
    "60UYC0600065200",
    "01UBT9400065200",
    # The corners of a 100 km square.
    "16SGD0000000000",
    "16SGD9999999999",
]


def inverse_document():
    rng = random.Random(SEED + 1)
    grids = []
    for _, lat, lon in NAMED:
        for digits in (1, 2, 3, 4, 5):
            grids.append(to_mgrs(lat, lon, digits))
    for lat, lon in edge_points():
        grids.append(to_mgrs(lat, lon, rng.randint(1, 5)))
    for _ in range(1500):
        grids.append(valid_grid(rng))
    for _ in range(800):
        grids.append(inconsistent_grid(rng))
    grids += LENIENT

    cases = []
    seen = set()
    for grid in grids:
        key = grid.replace(" ", "").upper()
        if key in seen:
            continue
        seen.add(key)
        try:
            lat, lon = to_latlon(grid)
        except Exception:  # an inconsistent draw PyGeodesy refuses
            continue
        cases.append([grid, lat, lon])

    invalid = []
    for grid in INVALID:
        try:
            to_latlon(grid)
        except Exception:
            invalid.append(grid)
        else:
            raise AssertionError(f"expected PyGeodesy to refuse {grid!r}")

    return {
        "description": "MGRS grid to the latitude/longitude of the centre of the named "
                       "square, as /api/convert-grid answers. Spaces and case are ignored; "
                       "the digits split evenly into easting and northing (two or more, "
                       "even), and each extra pair past ten refines the square tenfold.",
        "reference": "pygeodesy.mgrs.parseMGRS(grid).toLatLon()  (centre=True)",
        "generator": "contracts/scripts/mgrs_fixtures.py",
        "seed": SEED + 1,
        "toleranceDeg": TOLERANCE_DEG,
        "columns": ["mgrs", "lat", "lon"],
        "invalid": invalid,
        "cases": cases,
    }


# -- IO --------------------------------------------------------------------

def dumps(document):
    """One case per line, so a regenerated fixture diffs line by line."""
    head = {k: v for k, v in document.items() if k not in ("cases", "named")}
    lines = ["{"]
    for key, value in head.items():
        lines.append(f"  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)},")
    if "named" in document:
        lines.append('  "named": [')
        lines.append(",\n".join("    " + json.dumps(n, ensure_ascii=False) for n in document["named"]))
        lines.append("  ],")
    lines.append('  "cases": [')
    lines.append(",\n".join("    " + json.dumps(c) for c in document["cases"]))
    lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def documents():
    return {"forward.json": forward_document(), "inverse.json": inverse_document()}


def write():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    for name, doc in documents().items():
        (FIXTURES / name).write_text(dumps(doc), encoding="utf-8")
        print(f"wrote {name}: {len(doc['cases'])} cases")


def check():
    """Raises AssertionError naming the first fixture that no longer matches."""
    for name, doc in documents().items():
        path = FIXTURES / name
        if not path.exists():
            raise AssertionError(f"{path} is missing; run `write`")
        if path.read_text(encoding="utf-8") != dumps(doc):
            raise AssertionError(
                f"{path} differs from what PyGeodesy produces; "
                "regenerate with `python contracts/scripts/mgrs_fixtures.py write` "
                "and review the diff"
            )


def verify_sample(step=12):
    """Recompute every `step`-th committed case with PyGeodesy and compare.

    Cheaper than `check` (which regenerates everything), so it can run with the
    backend's own tests: it proves the committed values are what PyGeodesy says
    today, without proving the generator would write the same file.
    Returns the number of cases verified.
    """
    forward = json.loads((FIXTURES / "forward.json").read_text(encoding="utf-8"))
    inverse = json.loads((FIXTURES / "inverse.json").read_text(encoding="utf-8"))
    checked = 0
    for lat, lon, grid in forward["cases"][::step]:
        actual = to_mgrs(lat, lon)
        if actual != grid:
            raise AssertionError(f"forward {lat}, {lon}: fixture {grid!r}, PyGeodesy {actual!r}")
        checked += 1
    tolerance = inverse["toleranceDeg"]
    for grid, lat, lon in inverse["cases"][::step]:
        actual_lat, actual_lon = to_latlon(grid)
        if abs(actual_lat - lat) > tolerance or abs(actual_lon - lon) > tolerance:
            raise AssertionError(
                f"inverse {grid!r}: fixture {lat}, {lon}; PyGeodesy {actual_lat}, {actual_lon}"
            )
        checked += 1
    for grid in inverse["invalid"]:
        try:
            to_latlon(grid)
        except Exception:
            checked += 1
        else:
            raise AssertionError(f"PyGeodesy now accepts {grid!r}, which the fixture lists as invalid")
    return checked


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "check"
    if command == "write":
        write()
    elif command == "check":
        check()
        print("mgrs fixtures match PyGeodesy")
    else:
        sys.exit(f"usage: {sys.argv[0]} write|check")
