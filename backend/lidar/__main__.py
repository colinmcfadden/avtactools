"""Build a Cesium point cloud tileset for one landing point.

    python -m lidar --lat 34.5916 --lon -84.1282 --out /tiles/lz_ariel

Reads from the Entwine-indexed copy of 3DEP on AWS by default, which is
spatially indexed: an area of interest costs a bounded query rather than a tile
download.

To work offline from downloaded tiles, point --collection at the directory
holding them. The covering tiles are found by indexing the LAS headers, and
their coordinate system is read from the files rather than assumed:

    python -m lidar --lat 34.6481 --lon -83.8613 --collection /data/lidar --out /data/tiles/lz_lanier
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import aoi, collection, coverage, pipeline
from .tiles import DISPLAY_SPACING_M, TileBuildError, build_pointcloud_tiles

# Entwine-indexed 3DEP on AWS Open Data. Each survey is its own index, and
# which one to read is resolved per target by lidar.coverage — most ground is
# covered several times over by surveys of very different quality.
EPT_BASE = "https://s3-us-west-2.amazonaws.com/usgs-lidar-public"


def ept_url(project: str) -> str:
    return f"{EPT_BASE}/{project}/ept.json"


def _from_collection(args):
    """Pick the tiles covering the target out of a downloaded collection.

    The CRS is read from the tiles rather than assumed. Published products
    differ — the statewide LAZ is Albers, the Lake Lanier project is UTM 17N —
    and reading one as the other crops an area that contains nothing.
    """
    tiles = collection.index_for(args.collection, refresh=args.reindex)

    # Every tile in a collection comes from one product, so the first one
    # speaks for all of them; a mismatch further in is caught below.
    tile_crs = collection.read_crs(tiles[0].path)
    source_srs = tile_crs.compound()
    if not tile_crs.vertical_declared:
        print(f"note    : vertical datum not machine-readable "
              f"({tile_crs.citation or 'no citation'}); forcing "
              f"{collection.DEFAULT_VERTICAL}")

    bbox = aoi.bbox_for(args.lat, args.lon, radius_m=args.radius,
                        source_srs=source_srs)
    covering = collection.tiles_for(tiles, bbox)
    if not covering:
        raise collection.CollectionError(
            f"none of the {len(tiles)} tiles in {args.collection} cover "
            f"{args.lat:.5f}, {args.lon:.5f}")

    return [str(tile.path) for tile in covering], source_srs, bbox


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
                                 pipeline.COLOR_BY_HEIGHT, "none"],
                        help="height reads obstruction height directly and "
                             "works on an unclassified survey")
    parser.add_argument("--spacing", type=float, default=DISPLAY_SPACING_M,
                        help="thin to roughly one point per this many metres; "
                             "0 keeps full density for measurement")
    args = parser.parse_args(argv)

    classes = (tuple(int(c) for c in args.classes.split(","))
               if args.classes else pipeline.OBSTRUCTION_CLASSES)
    color_by = None if args.color_by == "none" else args.color_by

    if args.collection:
        try:
            source, source_srs, bbox = _from_collection(args)
        except collection.CollectionError as error:
            print(f"failed: {error}", file=sys.stderr)
            return 1
    else:
        if args.source:
            source = args.source
            # A published tile carries its own CRS, so a lone --source is the
            # one case where it has to be assumed. Albers is what the statewide
            # products use; a collection reads the real value per tile.
            source_srs = aoi.ALBERS_NAVD88
        elif args.project:
            source = ept_url(args.project)
            source_srs = aoi.WEB_MERCATOR_NAVD88
        else:
            try:
                survey = coverage.best_survey(args.lat, args.lon)
            except coverage.CoverageError as error:
                print(f"failed: {error}", file=sys.stderr)
                return 1
            source = survey.url
            source_srs = aoi.WEB_MERCATOR_NAVD88
            print(f"survey  : {survey.name} ({survey.year})")
            if not survey.likely_classified:
                # Worth saying plainly: the build will still succeed, it will
                # just have no vegetation in it.
                print("warning : this survey predates the 3DEP classification "
                      "spec — expect ground only. Try --color-by height.")
        bbox = aoi.bbox_for(args.lat, args.lon, radius_m=args.radius,
                            source_srs=source_srs)

    extent = aoi.ground_extent_m(bbox, source_srs=source_srs)

    print(f"target  : {args.lat:.6f}, {args.lon:.6f}")
    print(f"area    : {extent:.0f} m across")
    print(f"srs     : {source_srs}")
    print(f"classes : {','.join(str(c) for c in classes)}  "
          f"colour by {color_by or 'nothing'}")
    if isinstance(source, list):
        print(f"source  : {len(source)} tile(s) from {args.collection}")
        for path in source:
            print(f"          {Path(path).name}")
    else:
        print(f"source  : {source}")

    started = time.time()
    try:
        tileset = build_pointcloud_tiles(
            source, args.out, bbox=bbox, source_srs=source_srs,
            thin_spacing_m=args.spacing or None,
            classes=classes, color_by=color_by,
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
