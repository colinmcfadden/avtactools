"""Build a Cesium point cloud tileset for one landing point.

    python -m lidar --lat 34.5916 --lon -84.1282 --out /tiles/lz_ariel

Reads from the Entwine-indexed copy of 3DEP on AWS by default, which is
spatially indexed: an area of interest costs a bounded query rather than a tile
download. Pass --source a local .laz or .copc.laz to work offline instead.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import aoi
from .tiles import DISPLAY_SPACING_M, TileBuildError, build_pointcloud_tiles

# Entwine-indexed 3DEP on AWS Open Data. Each survey block is its own index, so
# the right one depends on where the target is; north Georgia is B3. Choosing a
# block from a coverage index is a later problem — for now it is explicit.
EPT_BASE = "https://s3-us-west-2.amazonaws.com/usgs-lidar-public"
DEFAULT_EPT_PROJECT = "GA_Statewide_B3_2018"


def ept_url(project: str) -> str:
    return f"{EPT_BASE}/{project}/ept.json"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--radius", type=float, default=aoi.DEFAULT_RADIUS_M,
                        help="half-width of the area in ground metres "
                             f"(default {aoi.DEFAULT_RADIUS_M:.0f})")
    parser.add_argument("--out", type=Path, required=True,
                        help="directory to write the tileset into")
    parser.add_argument("--source", default=None,
                        help="local .laz/.copc.laz; defaults to the AWS EPT index")
    parser.add_argument("--project", default=DEFAULT_EPT_PROJECT,
                        help="EPT survey block, when reading from AWS")
    parser.add_argument("--spacing", type=float, default=DISPLAY_SPACING_M,
                        help="thin to roughly one point per this many metres; "
                             "0 keeps full density for measurement")
    args = parser.parse_args(argv)

    if args.source:
        source = args.source
        # A local tile is published in Albers; the AWS index is reprojected to
        # Web Mercator. Both carry NAVD88 heights, so the geoid transform is
        # the same either way.
        source_srs = aoi.ALBERS_NAVD88
    else:
        source = ept_url(args.project)
        source_srs = aoi.WEB_MERCATOR_NAVD88

    bbox = aoi.bbox_for(args.lat, args.lon, radius_m=args.radius,
                        source_srs=source_srs)
    extent = aoi.ground_extent_m(bbox, source_srs=source_srs)

    print(f"target  : {args.lat:.6f}, {args.lon:.6f}")
    print(f"area    : {extent:.0f} m across")
    print(f"source  : {source}")

    started = time.time()
    try:
        tileset = build_pointcloud_tiles(
            source, args.out, bbox=bbox, source_srs=source_srs,
            thin_spacing_m=args.spacing or None,
        )
    except TileBuildError as error:
        print(f"\nfailed: {error}", file=sys.stderr)
        return 1

    size = sum(f.stat().st_size for f in args.out.rglob("*") if f.is_file())
    print(f"\nbuilt in {time.time() - started:.0f}s")
    print(f"  {tileset}")
    print(f"  {size / 1048576:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
