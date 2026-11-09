"""Measure where ArcGIS's VFR service has tiles outside CONUS, for the chart fallback (docs/VFR_CHARTS.md §4.6).

    python contracts/scripts/arcgis_vfr.py measure    # asks ArcGIS, writes contracts/fixtures/charts/arcgis_vfr.json

The web and Android draw that service where the server's build has no charts: one layer per rectangle of the config's
`fallback`, each within its own bounds. The server's table of rectangles (`charts/serve.py`, `FALLBACK_TILES`) is pinned
to this file by `backend/tests/test_chart_serve.py`, so a region the service gains or loses shows as a failing test once
this is run again.

How: the service's tilemap says which level-12 tiles exist in a window, one 128-tile bundle at most (a window crossing a
bundle's edge comes back cut to it; one with none answers 422). Every bundle round each region is asked for, the tiles
are joined into regions (not across the antimeridian, so Alaska is two), each region is named by a place in it, and then
the tile in the middle of each edge and the one just past it are fetched: the first must answer 200 and the second 404.
Level 8 is no use for edges: its tilemap marks blocks of 8 by 8 tiles, and Alaska's reaches -90° over Canada.

Standard library only. Nothing here reads a tile's picture, only whether it is there.
"""
from __future__ import annotations

import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SERVICE = "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer"
LEVEL = 12
N = 1 << LEVEL
BUNDLE = 128
OUT = Path(__file__).resolve().parents[1] / "fixtures" / "charts" / "arcgis_vfr.json"

# The bundles asked for, as (first row, last row, first column, last column) of bundles. Each holds its region with a
# bundle to spare on every side but the antimeridian's (the measurement fails if a region reaches the edge of what was
# asked, so a region that grows is noticed rather than cut).
AREAS = {
    "alaska": (6, 10, 0, 5),
    "western-aleutians": (9, 10, 30, 31),
    "hawaii": (13, 14, 1, 2),
    "marianas": (13, 14, 28, 29),
    "american-samoa": (17, 17, 0, 1),
}

# A place in each region, (lat, lon): the region is the one holding its tile.
SEEDS = {
    "alaska": (61.2181, -149.9003),            # Anchorage
    "western-aleutians": (52.7123, 174.1136),  # Shemya
    "hawaii": (21.3069, -157.8583),            # Honolulu
    "marianas": (13.4443, 144.7937),           # Guam
    "american-samoa": (-14.2756, -170.7020),   # Pago Pago
}


def tile_of(lat: float, lon: float) -> tuple[int, int]:
    x = int((lon + 180) / 360 * N)
    s = math.sin(math.radians(lat))
    y = int((0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * N)
    return x, y


def lon_of(x: int) -> float:
    return x / N * 360 - 180


def lat_of(y: int) -> float:
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / N))))


def get(url: str) -> tuple[int, bytes]:
    request = urllib.request.Request(url, headers={"User-Agent": "ezpz-chart-fallback-measure"})
    time.sleep(0.2)
    try:
        with urllib.request.urlopen(request, timeout=30) as answer:
            return answer.status, answer.read(4 << 20)
    except urllib.error.HTTPError as error:
        return error.code, b""


def window(row: int, col: int) -> set[tuple[int, int]]:
    status, body = get(f"{SERVICE}/tilemap/{LEVEL}/{row}/{col}/{BUNDLE}/{BUNDLE}?f=json")
    if status != 200:
        raise SystemExit(f"tilemap {row}/{col}: HTTP {status}")
    answer = json.loads(body)
    if "error" in answer:
        if answer["error"].get("code") != 422:
            raise SystemExit(f"tilemap {row}/{col}: {answer['error']}")
        return set()
    loc = answer["location"]
    if (loc["left"], loc["top"], loc["width"], loc["height"]) != (col, row, BUNDLE, BUNDLE):
        raise SystemExit(f"tilemap {row}/{col}: answered {loc}")
    return {(col + i % BUNDLE, row + i // BUNDLE) for i, there in enumerate(answer["data"]) if there}


def regions(tiles: set[tuple[int, int]]) -> list[set[tuple[int, int]]]:
    seen: set[tuple[int, int]] = set()
    found = []
    for start in sorted(tiles):
        if start in seen:
            continue
        seen.add(start)
        stack, region = [start], set()
        while stack:
            x, y = stack.pop()
            region.add((x, y))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    q = (x + dx, y + dy)
                    if q in tiles and q not in seen:
                        seen.add(q)
                        stack.append(q)
        found.append(region)
    return found


def tile_status(x: int, y: int) -> int:
    return get(f"{SERVICE}/tile/{LEVEL}/{y}/{x}")[0]


def measure() -> dict:
    windows, tiles = [], set()
    asked: set[tuple[int, int]] = set()
    for name, (r0, r1, c0, c1) in AREAS.items():
        for br in range(r0, r1 + 1):
            for bc in range(c0, c1 + 1):
                if (br, bc) in asked:
                    continue
                asked.add((br, bc))
                got = window(br * BUNDLE, bc * BUNDLE)
                windows.append([br * BUNDLE, bc * BUNDLE, len(got)])
                tiles |= got
    found = regions(tiles)
    out, others = [], []
    for region in found:
        names = [n for n, seed in SEEDS.items() if tile_of(*seed) in region]
        xs = [x for x, _ in region]
        ys = [y for _, y in region]
        box = [min(xs), max(xs), min(ys), max(ys)]
        if len(names) != 1:
            others.append({"columns": box[:2], "rows": box[2:], "tiles": len(region)})
            continue
        name = names[0]
        r0, r1, c0, c1 = AREAS[name]
        # A region at the edge of what was asked may go on past it; the antimeridian is the one edge it may meet.
        if (box[0] == c0 * BUNDLE and box[0] != 0) or (box[1] == (c1 + 1) * BUNDLE - 1 and box[1] != N - 1) \
                or box[2] == r0 * BUNDLE or box[3] == (r1 + 1) * BUNDLE - 1:
            raise SystemExit(f"{name} reaches the edge of the bundles asked for {box}: widen AREAS")
        probes = []
        for edge in ("west", "east", "north", "south"):
            if edge in ("west", "east"):
                x = box[0] if edge == "west" else box[1]
                if x in (0, N - 1):
                    continue  # the antimeridian: the tile past it is the other half's
                rows = sorted(y for (tx, y) in region if tx == x)
                y = rows[len(rows) // 2]
                inside, outside = (x, y), (x - 1 if edge == "west" else x + 1, y)
            else:
                y = box[2] if edge == "north" else box[3]
                cols = sorted(x for (x, ty) in region if ty == y)
                x = cols[len(cols) // 2]
                inside, outside = (x, y), (x, y - 1 if edge == "north" else y + 1)
            probes.append({"edge": edge, "inside": [*inside, tile_status(*inside)],
                           "outside": [*outside, tile_status(*outside)]})
        bad = [p for p in probes if p["inside"][2] != 200 or p["outside"][2] != 404]
        if bad:
            raise SystemExit(f"{name}: a tile at its edge does not answer as the tilemap says: {bad}")
        out.append({
            "name": name,
            "columns": box[:2],
            "rows": box[2:],
            "tiles": len(region),
            "bounds": [lon_of(box[0]), lat_of(box[3] + 1), lon_of(box[1] + 1), lat_of(box[2])],
            "probes": probes,
        })
    missing = set(SEEDS) - {r["name"] for r in out}
    if missing:
        raise SystemExit(f"no tiles found for {sorted(missing)}")
    out.sort(key=lambda r: list(SEEDS).index(r["name"]))
    return {
        "service": SERVICE,
        "measured": datetime.now(timezone.utc).date().isoformat(),
        "level": LEVEL,
        "note": "Level-12 tiles of the service's 256-px Web Mercator grid: columns from the west, rows from the north. "
                "Each probe is [column, row, HTTP status]. Windows are [first row, first column, tiles found].",
        "regions": out,
        "others": others,
        "windows": sorted(windows),
    }


def main(argv: list[str]) -> int:
    if argv[1:] != ["measure"]:
        print(__doc__)
        return 2
    result = measure()
    OUT.write_text(json.dumps(result, indent=1) + "\n")
    for r in result["regions"]:
        print(r["name"], r["columns"], r["rows"], r["tiles"], [round(v, 4) for v in r["bounds"]])
    for o in result["others"]:
        print("other", o)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
