"""Build a Cesium point cloud tileset for one landing point.

    python -m lidar --lat 34.5916 --lon -84.1282 --out /tiles/lz_ariel

Reads from the Entwine-indexed copy of 3DEP on AWS by default, which is
spatially indexed: an area of interest costs a bounded query rather than a tile
download.

To work offline from downloaded tiles, point --collection at the directory
holding them. The covering tiles are found by indexing the LAS headers, and
their coordinate system is read from the files rather than assumed:

    python -m lidar --lat 34.6481 --lon -83.8613 --collection /data/lidar --out /data/tiles/lz_lanier

The build service (lidar.worker) runs the same build through lidar.build.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import aoi, pipeline
from .build import BuildError, BuildRequest, build_for_target
from .tiles import estimated_size_mb, spacing_for_radius


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
    parser.add_argument("--collection", type=Path, default=None,
                        help="directory of downloaded 3DEP tiles; the covering "
                             "tiles are selected automatically")
    parser.add_argument("--reindex", action="store_true",
                        help="re-read every tile header, for a collection that "
                             "is still downloading")
    parser.add_argument("--project", default=None,
                        help="EPT survey to read, overriding the automatic "
                             "choice; see lidar.coverage for what covers a point")
    parser.add_argument("--classes", default=None,
                        help="comma-separated ASPRS classes to keep; defaults "
                             "to ground and vegetation. Surveys that classify "
                             "only ground (most pre-2015 products) need "
                             "0,1,2 or their obstructions are discarded")
    parser.add_argument("--color-by", default=pipeline.COLOR_BY_CLASSIFICATION,
                        choices=[pipeline.COLOR_BY_CLASSIFICATION,
                                 pipeline.COLOR_BY_HEIGHT,
                                 pipeline.COLOR_BY_IMAGERY, "none"],
                        help="imagery samples real colour from --imagery and "
                             "looks photographic; height reads obstruction "
                             "height directly and works on an unclassified "
                             "survey")
    parser.add_argument("--imagery", default=None,
                        help="GDAL-readable aerial imagery for --color-by "
                             "imagery; a GDAL_WMS service description points "
                             "at tile servers without downloading anything")
    parser.add_argument("--context", type=float, default=None,
                        help="also build a thinned ring out to this radius, "
                             "for landscape around the landing point. Kept "
                             "separate so extending range never costs detail "
                             "at the centre")
    parser.add_argument("--spacing", type=float, default=None,
                        help="thin to roughly one point per this many metres. "
                             "Defaults to full density for an LZ-sized area, "
                             "scaling back for large radii; 0 forces full "
                             "density at any size")
    args = parser.parse_args(argv)

    request = BuildRequest(
        lat=args.lat, lon=args.lon, radius_m=args.radius,
        context_m=args.context,
        classes=(tuple(int(c) for c in args.classes.split(","))
                 if args.classes else pipeline.OBSTRUCTION_CLASSES),
        color_by=None if args.color_by == "none" else args.color_by,
        imagery=args.imagery, spacing_m=args.spacing,
        source=args.source, project=args.project,
        collection=args.collection, reindex=args.reindex,
    )

    print(f"target  : {args.lat:.6f}, {args.lon:.6f}")
    thinning = (spacing_for_radius(args.radius) if args.spacing is None
                else (args.spacing or None))
    if estimated_size_mb(args.radius, thinning) > 250:
        # Build time against a remote survey is the real cost, and it is easy
        # to ask for twenty minutes of it by accident.
        print("warning : a build this size takes a long while; consider a "
              "smaller --radius with --context for the surroundings")

    started = time.time()
    try:
        summary = build_for_target(request, args.out, log=print)
    except BuildError as error:
        print(f"\nfailed: {error}", file=sys.stderr)
        return 1

    print(f"\nbuilt in {time.time() - started:.0f}s")
    print(f"  {summary['tileset']}")
    print(f"  {summary['bytes'] / 1048576:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
