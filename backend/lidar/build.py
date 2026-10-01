"""Building the point cloud for one landing point, start to finish.

One function shared by the command line (``python -m lidar``) and the build
service (``lidar.worker``), so the two cannot drift apart: choosing a source,
building the core tileset, recording its manifest, and optionally the wider
context ring.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import aoi, catalog, collection, coverage, pipeline
from .catalog import CONTEXT_DIRNAME
from .tiles import (TileBuildError, build_pointcloud_tiles, context_spacing,
                    estimated_size_mb, spacing_for_radius, _run)

# Entwine-indexed 3DEP on AWS Open Data. Each survey is its own index, and
# which one to read is resolved per target by lidar.coverage — most ground is
# covered several times over by surveys of very different quality.
EPT_BASE = "https://s3-us-west-2.amazonaws.com/usgs-lidar-public"

# Below this a downloaded collection is treated as not covering the target. A
# target on the edge of a download intersects its outer tiles, and building
# from them alone produces a point cloud with one side cut off.
MIN_COLLECTION_COVERAGE = 0.95


def ept_url(project: str) -> str:
    return f"{EPT_BASE}/{project}/ept.json"


class BuildError(RuntimeError):
    """A build could not run; the message is fit to show an operator."""


@dataclass(frozen=True)
class BuildRequest:
    lat: float
    lon: float
    radius_m: float = aoi.DEFAULT_RADIUS_M
    # A thinned landscape ring out to this radius, built beside the core.
    context_m: float | None = None
    classes: tuple = pipeline.OBSTRUCTION_CLASSES
    color_by: str | None = pipeline.COLOR_BY_CLASSIFICATION
    imagery: str | None = None
    # None picks a spacing from the radius; 0 forces full density.
    spacing_m: float | None = None
    # Source overrides, most specific first. With none set the best survey on
    # AWS is chosen from the USGS coverage index.
    source: str | None = None
    project: str | None = None
    collection: Path | None = None
    reindex: bool = False
    # Fall back to AWS when the collection does not cover the target, rather
    # than failing. The build service sets this; the CLI leaves it off so an
    # operator who named a collection gets that collection or an error.
    collection_fallback: bool = False


@dataclass
class Source:
    data: object          # a URL, a local path, or a list of local tiles
    srs: str
    bbox: tuple
    label: str
    notes: list = field(default_factory=list)


def _quiet(_message: str) -> None:
    return None


def _from_collection(request: BuildRequest) -> Source | None:
    """The covering tiles from a downloaded collection, or None if it falls short.

    A collection is not one survey. A download over one area routinely holds
    several — the 2018 Georgia statewide product in Albers alongside the ARRA
    Lake Lanier project in UTM 17N — and their tile bounds are then numbers in
    different frames. So each coordinate system is tried on its own, and the
    group that covers the target most completely wins.
    """
    tiles = collection.index_for(request.collection, refresh=request.reindex)
    groups = collection.by_crs(tiles)

    best = None
    for srs, group in groups.items():
        if not srs:
            continue
        bbox = aoi.bbox_for(request.lat, request.lon, radius_m=request.radius_m,
                            source_srs=srs)
        covering = collection.tiles_for(group, bbox)
        if not covering:
            continue
        fraction = collection.coverage_fraction(covering, bbox)
        if best is None or fraction > best[3]:
            best = (srs, bbox, covering, fraction)

    if best is None:
        return None
    srs, bbox, covering, fraction = best

    notes = []
    if len(groups) > 1:
        notes.append(f"{len(groups)} coordinate systems in the collection; using {srs}")
    tile_crs = collection.read_crs(covering[0].path)
    if not tile_crs.vertical_declared:
        notes.append(f"vertical datum not machine-readable "
                     f"({tile_crs.citation or 'no citation'}); forcing "
                     f"{collection.DEFAULT_VERTICAL}")
    if fraction < MIN_COLLECTION_COVERAGE:
        if request.collection_fallback:
            return None
        notes.append(f"collection covers only {fraction:.0%} of the area — "
                     f"expect a cut-off edge")

    return Source(data=[str(tile.path) for tile in covering], srs=srs, bbox=bbox,
                  label=f"{len(covering)} tile(s) from {request.collection}",
                  notes=notes)


def resolve_source(request: BuildRequest) -> Source:
    """Where this build reads its points from."""
    if request.collection:
        try:
            found = _from_collection(request)
        except collection.CollectionError as error:
            if not request.collection_fallback:
                raise BuildError(str(error)) from error
            found = None
        if found is not None:
            return found
        if not request.collection_fallback:
            raise BuildError(f"no tiles in {request.collection} cover "
                             f"{request.lat:.5f}, {request.lon:.5f}")

    notes = []
    if request.source:
        # A published tile carries its own CRS, so a lone source is the one
        # case where it has to be assumed. Albers is what the statewide
        # products use; a collection reads the real value per tile.
        data, srs, label = request.source, aoi.ALBERS_NAVD88, request.source
    elif request.project:
        data, srs, label = ept_url(request.project), aoi.WEB_MERCATOR_NAVD88, request.project
    else:
        try:
            survey = coverage.best_survey(request.lat, request.lon)
        except coverage.CoverageError as error:
            raise BuildError(str(error)) from error
        data, srs = survey.url, aoi.WEB_MERCATOR_NAVD88
        label = f"{survey.name} ({survey.year}) on AWS"
        if not survey.likely_classified:
            notes.append("this survey predates the 3DEP classification spec — "
                         "expect ground only")

    bbox = aoi.bbox_for(request.lat, request.lon, radius_m=request.radius_m,
                        source_srs=srs)
    return Source(data=data, srs=srs, bbox=bbox, label=label, notes=notes)


def build_for_target(request: BuildRequest, out_dir: Path, *,
                     log: Callable[[str], None] = _quiet,
                     stage: Callable[[str], None] = _quiet,
                     runner=None) -> dict:
    """Build the core tileset, its manifest, and the optional context ring.

    ``stage`` receives short phase names as the work moves between tools, for
    a progress display. ``runner`` executes the external tools and exists so
    tests can stand in for PDAL and py3dtiles.
    """
    out_dir = Path(out_dir)
    run = runner or _run

    if request.context_m is not None and request.context_m <= request.radius_m:
        raise BuildError("the context radius must be larger than the core radius")

    def staged_run(command):
        tool = Path(command[0]).name
        stage({"pdal": "processing points",
               "py3dtiles": "building tiles"}.get(tool, tool))
        return run(command)

    stage("locating survey")
    source = resolve_source(request)
    for note in source.notes:
        log(f"note    : {note}")

    thinning = (spacing_for_radius(request.radius_m) if request.spacing_m is None
                else (request.spacing_m or None))
    log(f"area    : {aoi.ground_extent_m(source.bbox, source_srs=source.srs):.0f} m across")
    log(f"srs     : {source.srs}")
    log(f"classes : {','.join(str(c) for c in request.classes)}  "
        f"colour by {request.color_by or 'nothing'}")
    estimate = estimated_size_mb(request.radius_m, thinning)
    log(f"density : {f'{thinning:.1f} m spacing' if thinning else 'full'}"
        f"  (~{estimate:.0f} MB)")
    log(f"source  : {source.label}")

    try:
        tileset = build_pointcloud_tiles(
            source.data, out_dir, bbox=source.bbox, source_srs=source.srs,
            thin_spacing_m=thinning, classes=request.classes,
            color_by=request.color_by, imagery_raster=request.imagery,
            runner=staged_run,
        )
    except TileBuildError as error:
        raise BuildError(str(error)) from error

    # Recorded so the API finds this tileset by position, not by an exact
    # coordinate match.
    catalog.write_manifest(out_dir, request.lat, request.lon,
                           radius_m=request.radius_m,
                           survey=None if isinstance(source.data, list) else str(source.data))

    context_built = False
    if request.context_m:
        # Separate from the core: one pipeline has one spacing, so widening it
        # thins the centre too. Two tiers keep the landing point at full
        # density and pay for range only where nothing is being measured.
        stage("building context")
        spacing = context_spacing(request.context_m)
        log(f"context : {request.context_m:.0f} m radius at {spacing:.1f} m spacing")
        context_bbox = aoi.bbox_for(request.lat, request.lon,
                                    radius_m=request.context_m,
                                    source_srs=source.srs)
        try:
            build_pointcloud_tiles(
                source.data, out_dir / CONTEXT_DIRNAME, bbox=context_bbox,
                source_srs=source.srs, thin_spacing_m=spacing,
                classes=request.classes, color_by=request.color_by,
                imagery_raster=request.imagery, runner=staged_run,
            )
            context_built = True
        except TileBuildError as error:
            # The core is built and usable; a missing ring is a smaller loss
            # than discarding it.
            log(f"context failed (core is still usable): {error}")

    size = sum(f.stat().st_size for f in out_dir.rglob("*") if f.is_file())
    return {"tileset": str(tileset), "bytes": size, "source": source.label,
            "context": context_built}
