"""Build the 3D point cloud for one landing point, ready for the app to serve.

The app can only show a tileset that already exists, and until now making one
meant assembling a long docker command by hand and then working out where to
put the output. This does both:

    python tools/build_lz.py --grid "17S KU 37750 37750"
    python tools/build_lz.py --lat 34.6477 --lon -83.8635

It resolves the target, runs the toolchain image, and writes the result into
the tileset store under the key the API looks for, with the manifest that lets
a nearby target find it. Nothing else is needed — reload the 3D view and it is
there.

Requires Docker and the toolchain image (backend/lidar/Dockerfile):

    docker build -t avtac-lidar:dev backend/lidar
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BACKEND = REPO / "backend"
DEFAULT_IMAGE = "avtac-lidar:dev"
DEFAULT_TILES_DIR = REPO / "data" / "tiles"
COVERAGE_URL = "https://usgs.entwine.io/boundaries/resources.geojson"

sys.path.insert(0, str(BACKEND))


def parse_grid(grid: str):
    """MGRS string to latitude/longitude.

    Accepts the spaced form crews actually write as well as the compact one.
    """
    import mgrs

    compact = grid.replace(" ", "").upper()
    lat, lon = mgrs.MGRS().toLatLon(compact)
    return float(lat), float(lon)


def ensure_coverage_index(cache_dir: Path) -> Path:
    """The USGS survey index, downloaded once and reused.

    About 8 MB. Kept outside the container so a rebuild does not re-fetch it.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / "usgs_ept_coverage.geojson"
    if path.exists() and path.stat().st_size > 1_000_000:
        return path

    print(f"fetching survey coverage index -> {path}")
    # An explicit User-Agent is required: the default urllib one is refused
    # with a 403.
    request = urllib.request.Request(
        COVERAGE_URL, headers={"User-Agent": "avtactools-lidar/1.0"})
    with urllib.request.urlopen(request) as response, path.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    return path


def docker_available() -> bool:
    try:
        subprocess.run(["docker", "version"], capture_output=True, check=True)
        return True
    except (OSError, subprocess.CalledProcessError):
        return False


def build(lat, lon, *, radius_m, tiles_dir, image, colour, classes,
          collection=None, spacing=None, context=None):
    from lidar import catalog

    key = catalog.key_for(lat, lon, radius_m=radius_m)
    destination = Path(tiles_dir) / key
    cache = ensure_coverage_index(Path(tempfile.gettempdir()) / "avtac-lidar")

    print(f"target : {lat:.6f}, {lon:.6f}  radius {radius_m:.0f} m")
    print(f"key    : {key}")
    print(f"output : {destination}")

    with tempfile.TemporaryDirectory(prefix="lz-build-") as staging:
        command = [
            "docker", "run", "--rm",
            "-v", f"{BACKEND / 'lidar'}:/opt/lidar-src:ro",
            "-v", f"{cache.parent}:/cache:ro",
            "-v", f"{staging}:/out",
        ]
        if collection:
            command += ["-v", f"{Path(collection).resolve()}:/data/lidar:ro"]

        inner = [
            "mkdir -p /work/lidar",
            "cp /opt/lidar-src/*.py /opt/lidar-src/*.xml /work/lidar/",
            "cp /cache/usgs_ept_coverage.geojson /tmp/",
            "cd /work",
            # The tileset is written to a temp mount and moved into place only
            # on success, so a failed build never leaves a half-written
            # directory being served.
            "LIDAR_CACHE_DIR=/tmp python -m lidar"
            f" --lat {lat} --lon {lon} --radius {radius_m}"
            f" --color-by {colour}"
            + (f" --classes {classes}" if classes else "")
            + (f" --spacing {spacing}" if spacing is not None else "")
            + (f" --context {context}" if context else "")
            + (" --collection /data/lidar" if collection else "")
            + " --imagery /work/lidar/mapbox_imagery.xml"
            " --out /out/tileset",
        ]
        command += [image, "bash", "-lc", " && ".join(inner)]

        print("\nbuilding (first run downloads survey data; allow a minute)...\n")
        result = subprocess.run(command)
        if result.returncode != 0:
            return 1

        produced = Path(staging) / "tileset"
        if not (produced / "tileset.json").exists():
            print("\nbuild produced no tileset.json", file=sys.stderr)
            return 1

        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(str(produced), str(destination))

    catalog.write_manifest(destination, lat, lon, radius_m=radius_m)
    total = sum(f.stat().st_size for f in destination.rglob("*") if f.is_file())
    print(f"\ndone: {total / 1048576:.1f} MB in {destination}")
    print("Reload the 3D view for this target.")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    where = parser.add_mutually_exclusive_group(required=True)
    where.add_argument("--grid", help='MGRS, e.g. "17S KU 37750 37750"')
    where.add_argument("--lat", type=float, help="with --lon")
    parser.add_argument("--lon", type=float)
    parser.add_argument("--radius", type=float, default=250.0,
                        help="half-width in ground metres (default 250)")
    parser.add_argument("--tiles-dir", default=os.environ.get(
        "LIDAR_TILES_DIR", str(DEFAULT_TILES_DIR)),
        help="tileset store the API serves from")
    parser.add_argument("--image", default=DEFAULT_IMAGE)
    parser.add_argument("--color-by", default="imagery",
                        choices=["imagery", "classification", "height", "none"])
    parser.add_argument("--classes", default=None,
                        help="ASPRS classes to keep; older surveys need 0,1,2")
    parser.add_argument("--collection", default=None,
                        help="directory of downloaded LAZ, instead of AWS")
    parser.add_argument("--context", type=float, default=None,
                        help="also build a thinned landscape ring out to this "
                             "radius; the core keeps full density")
    parser.add_argument("--spacing", type=float, default=None)
    args = parser.parse_args(argv)

    if args.grid:
        try:
            lat, lon = parse_grid(args.grid)
        except Exception as error:  # noqa: BLE001
            print(f"could not read grid {args.grid!r}: {error}", file=sys.stderr)
            return 2
    else:
        if args.lon is None:
            parser.error("--lat requires --lon")
        lat, lon = args.lat, args.lon

    if not docker_available():
        print("Docker is not running. Start Docker Desktop and try again.",
              file=sys.stderr)
        return 2

    return build(lat, lon, radius_m=args.radius, tiles_dir=args.tiles_dir,
                 image=args.image, colour=args.color_by, classes=args.classes,
                 collection=args.collection, spacing=args.spacing,
                 context=args.context)


if __name__ == "__main__":
    sys.exit(main())
