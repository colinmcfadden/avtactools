"""Find the LAZ tiles to download for an area, from the best survey covering it.

    python tools/find_lidar.py --lat 34.5964 --lon -84.1281 --radius 5000
    python tools/find_lidar.py --grid "16S GD 63386 32036" --radius 10000 -o urls.txt

Two distributions of USGS 3DEP carry the same points:

* the Entwine index on AWS, which is queried per area of interest and needs no
  download — what `python -m lidar` reads by default;
* the published LAZ tiles on USGS rockyweb, which is what this lists.

Downloading is worth it when builds are being run repeatedly, or over a slow
link to AWS: reading a local tile is disk-speed, and the remote fetch has been
the dominant cost of every build so far. It is not worth it for a survey older
than about 2015, which will have ground classified and nothing else — see
lidar.coverage, which ranks by vintage for exactly that reason.

Feed the output to lidar/fetch.py to download, then build with
`--collection <directory>`.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "backend"))

from lidar.coverage import CLASSIFIED_FROM_YEAR  # noqa: E402 — needs the path above

# A survey names the year it was flown; the API reports when it was published.
_YEAR = re.compile(r"(19|20)\d{2}")

TNM_API = "https://tnmaccess.nationalmap.gov/api/v1/products"
LPC_DATASET = "Lidar Point Cloud (LPC)"

# The API pages, and a wide area easily runs to thousands of tiles.
PAGE_SIZE = 100
MAX_PAGES = 200

USER_AGENT = "avtactools-lidar/1.0"

# Older projects are filed under a shared "legacy" folder, so the segment after
# /Projects/ is the folder rather than the survey. Taking it at face value
# groups every ARRA-era project in a state under one name.
_GROUPING_FOLDERS = {"legacy", "Legacy", "LEGACY"}


def bbox_around(lat: float, lon: float, radius_m: float):
    """A degree bounding box around a point, as the API wants it."""
    dlat = radius_m / 111_320.0
    dlon = radius_m / (111_320.0 * math.cos(math.radians(lat)))
    return (lon - dlon, lat - dlat, lon + dlon, lat + dlat)


def query(bbox, *, page_size=PAGE_SIZE):
    """Every LPC product intersecting a bounding box."""
    west, south, east, north = bbox
    items, offset = [], 0
    for _ in range(MAX_PAGES):
        params = urllib.parse.urlencode({
            "datasets": LPC_DATASET,
            "bbox": f"{west},{south},{east},{north}",
            "max": page_size,
            "offset": offset,
        })
        request = urllib.request.Request(f"{TNM_API}?{params}",
                                         headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request) as response:
            document = json.loads(response.read())

        page = document.get("items", [])
        items.extend(page)
        total = document.get("total", 0)
        offset += len(page)
        if not page or offset >= total:
            break
    return items


def _survey_name(url: str) -> str:
    """The survey a published tile belongs to."""
    parts = url.split("/Projects/", 1)
    if len(parts) != 2:
        return "unknown"
    segments = [seg for seg in parts[1].split("/") if seg]
    if segments and segments[0] in _GROUPING_FOLDERS and len(segments) > 1:
        return segments[1]
    return segments[0] if segments else "unknown"


def survey_year(name: str, published=None):
    """When a survey was flown, which is not when it was published.

    ARRA_GA_LakeLanier_2010 carries a 2023 publication date, and ranking on
    that puts a survey with no vegetation classification above the 2018 one
    that has it. The year in the name is the collection year, and that is what
    predicts whether the data is usable — USGS required vegetation classes from
    around 2015.
    """
    found = _YEAR.search(name)
    if found:
        return int(found.group())
    return int(published[:4]) if published else None


def group_by_survey(items) -> dict:
    """Products keyed by the survey they belong to.

    An area is commonly covered several times over, and the surveys are not
    equivalent — mixing two into one directory would give the collection
    reader two coordinate systems and two classification standards.
    """
    surveys = {}
    for item in items:
        url = item.get("downloadURL") or ""
        if not url.lower().endswith(".laz"):
            continue
        name = _survey_name(url)
        entry = surveys.setdefault(name, {"urls": [], "bytes": 0, "dates": set()})
        entry["urls"].append(url)
        entry["bytes"] += int(item.get("sizeInBytes") or 0)
        if item.get("publicationDate"):
            entry["dates"].add(item["publicationDate"][:4])
        entry["year"] = survey_year(name, item.get("publicationDate"))
    return surveys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    where = parser.add_mutually_exclusive_group(required=True)
    where.add_argument("--grid", help='MGRS, e.g. "16S GD 63386 32036"')
    where.add_argument("--lat", type=float, help="with --lon")
    parser.add_argument("--lon", type=float)
    parser.add_argument("--radius", type=float, default=5000.0,
                        help="half-width of the area in metres (default 5000)")
    parser.add_argument("-o", "--out", type=Path, default=None,
                        help="write the chosen survey's URLs here, one per line")
    parser.add_argument("--survey", default=None,
                        help="pick a survey by name instead of the newest")
    args = parser.parse_args(argv)

    if args.grid:
        from build_lz import parse_grid  # noqa: PLC0415 — same directory

        lat, lon = parse_grid(args.grid)
    else:
        if args.lon is None:
            parser.error("--lat requires --lon")
        lat, lon = args.lat, args.lon

    bbox = bbox_around(lat, lon, args.radius)
    print(f"target : {lat:.6f}, {lon:.6f}  radius {args.radius:.0f} m")
    print(f"bbox   : {bbox[0]:.4f},{bbox[1]:.4f},{bbox[2]:.4f},{bbox[3]:.4f}")
    print("querying The National Map...\n")

    surveys = group_by_survey(query(bbox))
    if not surveys:
        print("no LiDAR published for this area", file=sys.stderr)
        return 1

    # Flown-newest first: vintage predicts whether vegetation is classified.
    ordered = sorted(surveys.items(),
                     key=lambda kv: (kv[1].get("year") or 0, len(kv[1]["urls"])),
                     reverse=True)

    print(f"{'survey':<40} {'flown':>6} {'tiles':>6} {'size':>9}  classification")
    for name, entry in ordered:
        year = entry.get("year")
        classified = ("vegetation" if year and year >= CLASSIFIED_FROM_YEAR
                      else "GROUND ONLY")
        print(f"{name:<40} {str(year or '?'):>6} {len(entry['urls']):>6} "
              f"{entry['bytes'] / 1073741824:>7.1f} GB  {classified}")

    chosen = args.survey or ordered[0][0]
    if chosen not in surveys:
        print(f"\nno survey named {chosen!r} here", file=sys.stderr)
        return 1

    entry = surveys[chosen]
    print(f"\nchosen : {chosen}")
    print(f"         {len(entry['urls'])} tiles, "
          f"{entry['bytes'] / 1073741824:.1f} GB")

    if args.out:
        args.out.write_text("\n".join(sorted(entry["urls"])) + "\n",
                            encoding="utf-8")
        print(f"\nwrote {args.out}")
        print("\nDownload, then build against it:")
        print(f"  python backend/lidar/fetch.py {args.out} /data/lidar")
        print("  python tools/build_lz.py --lat ... --lon ... "
              "--collection /data/lidar")
    else:
        print("\nRe-run with -o urls.txt to write the download list.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
