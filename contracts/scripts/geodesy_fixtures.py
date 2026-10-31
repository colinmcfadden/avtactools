"""WGS84 geodetic to geocentric (ECEF) points for the 3D view's local frame, from PROJ.

The 3D view places everything in one east-north-up frame at the LZ (docs/ANDROID_3D_PLAN.md): the tilesets' points come in
geocentric metres, the routes and the terrain as latitude, longitude and height above the ellipsoid. A port of that arithmetic
needs a reference that is not itself, as the MGRS port has PyGeodesy: this is PROJ, through pyproj as the backend pins it
(requirements.txt), EPSG:4979 (geodetic, ellipsoidal height) to EPSG:4978 (geocentric).

    python contracts/scripts/geodesy_fixtures.py          # writes contracts/fixtures/viewer3d/geodesy.json
    python contracts/scripts/geodesy_fixtures.py check    # fails if the committed file differs

Run it with the backend's Python (the venv that has pyproj).
"""

import json
import math
import sys
from pathlib import Path

from pyproj import Transformer

OUT = Path(__file__).resolve().parents[1] / "fixtures" / "viewer3d" / "geodesy.json"

# Places the 3D view meets, and the edges an implementation gets wrong: the LZ tests' target, the equator and the prime meridian,
# both poles, the antimeridian either side, the southern and western hemispheres, below the ellipsoid (a geoid of -30 m and a
# sea-level LZ), and high above it (a camera framing a long route).
PLACES = [
    (34.591552, -84.128225, 369.65), (34.591552, -84.128225, 0.0), (34.6, -84.1, 457.2), (34.62, -84.08, 5000.0),
    (0.0, 0.0, 0.0), (0.0, 90.0, 0.0), (0.0, 180.0, 0.0), (0.0, -180.0, 0.0), (0.0, 179.999999, 10.0), (0.0, -179.999999, 10.0),
    (90.0, 0.0, 0.0), (-90.0, 0.0, 0.0), (89.9999, 45.0, 100.0), (-89.9999, -135.0, 100.0),
    (-33.8688, 151.2093, 58.0), (64.8378, -147.7164, 136.0), (51.4779, -0.0015, 45.0), (-54.8019, -68.303, 10.0),
    (34.591552, -84.128225, -30.35), (27.9881, 86.925, 8848.86), (35.0, -117.0, 100000.0), (12.5, -70.0, -100.0),
]


def build():
    to_ecef = Transformer.from_crs("EPSG:4979", "EPSG:4978", always_xy=True)
    cases = []
    for lat, lon, height in PLACES:
        x, y, z = to_ecef.transform(lon, lat, height)
        cases.append({"lat": lat, "lon": lon, "height": height, "x": x, "y": y, "z": z})
    import pyproj
    return {"generatedBy": "contracts/scripts/geodesy_fixtures.py", "reference": f"PROJ {pyproj.proj_version_str} via pyproj {pyproj.__version__}",
            "ellipsoid": {"a": 6378137.0, "f": 1 / 298.257223563}, "ecef": cases}


def dumps(document):
    lines = ["{"]
    for key in ("generatedBy", "reference", "ellipsoid"):
        lines.append(f"  {json.dumps(key)}: {json.dumps(document[key])},")
    lines.append('  "ecef": [')
    rows = document["ecef"]
    for i, row in enumerate(rows):
        lines.append("    " + json.dumps(row) + ("," if i < len(rows) - 1 else ""))
    lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    document = build()
    if len(sys.argv) > 1 and sys.argv[1] == "check":
        committed = json.loads(OUT.read_text(encoding="utf-8"))
        for old, new in zip(committed["ecef"], document["ecef"]):
            for axis in "xyz":
                if not math.isclose(old[axis], new[axis], rel_tol=0, abs_tol=1e-6):
                    sys.exit(f"{OUT.name}: {axis} of {old['lat']}, {old['lon']}, {old['height']} is {new[axis]}, not {old[axis]}")
        if len(committed["ecef"]) != len(document["ecef"]):
            sys.exit(f"{OUT.name}: {len(committed['ecef'])} places, not {len(document['ecef'])}")
        print(f"{OUT.name} is current")
        return
    OUT.write_text(dumps(document), encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
