"""A small LiDAR tileset for the 3D view's tests, made by py3dtiles itself.

The tilesets the server serves are py3dtiles's output, unchanged (backend/lidar/tiles.py). The apps read that
layout directly, so the fixture is that layout too: a synthetic point cloud near the LiDAR tests' target, written
as the build writes it (LAS in WGS84 geocentric metres, millimetre scale, 16-bit colour) and converted with the
build image's py3dtiles, 12.1.1, as `tiles.py` calls it. Nothing here is edited by hand.

It is shaped to hold every kind of tile py3dtiles writes for a real LZ: a root with a preview and a transform to
the cloud's centre (replaced), the octree's root with a scaling transform (replaced), additive nodes with and
without children (a dense cluster makes one node split, at py3dtiles's 20,000 points a node), and leaves with no
geometric error. A thinned ring beside it stands in for the context tileset (`context/`).

Run with py3dtiles 12.1.1 (the LiDAR image has it; or a venv: pip install "py3dtiles[las]==12.1.1"):

    python contracts/scripts/tileset_fixture.py

It writes contracts/fixtures/viewer3d/tileset/ (and its context/), replacing what is there. py3dtiles stamps
each tileset.json with the time it was made, so a regenerated fixture differs there; the points do not.
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import laspy
import numpy as np
import py3dtiles
from pyproj import Transformer

PY3DTILES = "12.1.1"
OUT = Path(__file__).resolve().parents[1] / "fixtures" / "viewer3d" / "tileset"

# The LiDAR API tests' target (backend/tests/test_lidar_api.py), in the Chattahoochee National Forest.
LAT, LON = 34.591552, -84.128225
# Ellipsoidal ground there: about 400 m above sea level, less the geoid's 30.35 m.
GROUND_M = 400.0 - 30.35


def cloud(rng, uniform, clustered, *, half_m, cluster_at=(8.0, -5.0), cluster_sd_m=0.6):
    """East, north and height above the ellipsoid of a patch of ground with trees on it, and each point's colour."""
    east = np.concatenate([rng.uniform(-half_m, half_m, uniform), rng.normal(cluster_at[0], cluster_sd_m, clustered)])
    north = np.concatenate([rng.uniform(-half_m, half_m, uniform), rng.normal(cluster_at[1], cluster_sd_m, clustered)])
    count = uniform + clustered
    ground = GROUND_M + 0.05 * east + 3.0 * np.sin(north / 15.0)
    canopy = (rng.random(count) < 0.4) * rng.uniform(5.0, 25.0, count)
    colour = np.where(canopy[:, None] > 0, [40, 110, 40], [150, 130, 100])
    return east, north, ground + canopy, colour


def write_las(path, east, north, height, colour):
    """The points as the build's PDAL stage writes them: geocentric, millimetre scale, 16-bit RGB (8-bit times 257)."""
    lat = LAT + north / 111_320.0
    lon = LON + east / (111_320.0 * np.cos(np.radians(LAT)))
    x, y, z = Transformer.from_crs("EPSG:4979", "EPSG:4978", always_xy=True).transform(lon, lat, height)
    header = laspy.LasHeader(point_format=3, version="1.2")
    header.scales = [0.001, 0.001, 0.001]
    header.offsets = [np.floor(x.min()), np.floor(y.min()), np.floor(z.min())]
    las = laspy.LasData(header)
    las.x, las.y, las.z = x, y, z
    las.red, las.green, las.blue = ((colour[:, i] * 257).astype(np.uint16) for i in range(3))
    las.write(str(path))


def convert(las, out):
    """py3dtiles as backend/lidar/tiles.py runs it, on one process so the points land in the same order each time."""
    subprocess.run([sys.executable, "-m", "py3dtiles.command_line", "convert", str(las), "--out", str(out),
                    "--srs_in", "4978", "--srs_out", "4978", "--overwrite", "--jobs", "1"],
                   check=True, stdout=subprocess.DEVNULL)


def main():
    if py3dtiles.__version__ != PY3DTILES:
        sys.exit(f"py3dtiles {PY3DTILES} is the build image's; this is {py3dtiles.__version__}")
    rng = np.random.default_rng(7)
    with tempfile.TemporaryDirectory(prefix="tileset-fixture-") as scratch:
        scratch = Path(scratch)
        write_las(scratch / "core.las", *cloud(rng, 4_000, 30_000, half_m=30.0))
        write_las(scratch / "context.las", *cloud(rng, 1_500, 0, half_m=90.0))
        convert(scratch / "core.las", scratch / "core")
        convert(scratch / "context.las", scratch / "context")
        if OUT.exists():
            shutil.rmtree(OUT)
        shutil.copytree(scratch / "core", OUT)
        shutil.copytree(scratch / "context", OUT / "context")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
