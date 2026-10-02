"""PDAL pipeline construction for turning 3DEP tiles into Cesium-ready points.

A pipeline is plain JSON, so it is built and tested as data here and handed to
PDAL to execute. That keeps the part with the decisions in it — which points to
keep, which frame to land in — verifiable without the toolchain installed.

The output is WGS84 geocentric metres, which is what 3D Tiles positions are
expressed in.
"""

from __future__ import annotations

import json
from pathlib import Path

from .aoi import WEB_MERCATOR_NAVD88
from .aoi import _horizontal as horizontal_crs
from .crs import ECEF

# ASPRS classification codes present in 3DEP tiles.
GROUND = 2
LOW_VEGETATION = 3
MEDIUM_VEGETATION = 4
HIGH_VEGETATION = 5
NOISE = 7

CREATED = 0
UNCLASSIFIED = 1

# What an LZ assessment needs from a fully classified product: the surface, and
# anything standing on it.
OBSTRUCTION_CLASSES = (GROUND, LOW_VEGETATION, MEDIUM_VEGETATION, HIGH_VEGETATION)

# Older products classify ground and nothing else. In ARRA_GA_LAKELANIER_2010,
# 46% of returns are class 0 and 30% class 1 against 24% ground — so the trees
# are in there, just unlabelled, and filtering to OBSTRUCTION_CLASSES discards
# three quarters of the survey and leaves a bare terrain sheet.
SURFACE_CLASSES = (CREATED, UNCLASSIFIED, GROUND)

# 8-bit RGB per class. Ground reads as bare earth and vegetation darkens with
# height, so canopy stands out against the surface an aircraft would touch on.
CLASSIFICATION_COLORS = {
    GROUND: (138, 127, 106),
    LOW_VEGETATION: (111, 143, 82),
    MEDIUM_VEGETATION: (79, 125, 60),
    HIGH_VEGETATION: (47, 107, 50),
}

# Colour by height above ground rather than by class. This is what makes an
# unclassified survey usable, and it is the more direct reading for an LZ
# anyway: the question on approach is how tall the obstruction is, not what
# species it belongs to. Bands are (upper_bound_m, rgb); the last is open-ended.
HEIGHT_BANDS = (
    (0.5, (138, 127, 106)),   # the surface itself
    (2.0, (111, 143, 82)),    # grass, scrub
    (5.0, (79, 125, 60)),     # brush, saplings
    (15.0, (47, 107, 50)),    # tree canopy
    (None, (196, 96, 58)),    # above 15 m — a hazard on short final
)

HAG_DIMENSION = "HeightAboveGround"

# Readers with a spatial index, which can honour a `bounds` argument. The plain
# LAS reader has none and errors on `bounds` rather than ignoring it.
INDEXED_READERS = frozenset({"readers.ept", "readers.copc"})


def bounds_expression(bbox) -> str:
    """PDAL's 2D bounds syntax: ``([xmin, xmax], [ymin, ymax])``."""
    xmin, ymin, xmax, ymax = bbox
    return f"([{xmin}, {xmax}], [{ymin}, {ymax}])"


def classification_limits(classes) -> str:
    """A ``filters.range`` expression keeping only the given classes."""
    return ",".join(f"Classification[{c}:{c}]" for c in sorted(classes))


# LAS defines RGB as a 16-bit field. 8-bit values stored there read as
# near-black to any reader that treats the field correctly; 257 maps 0->0 and
# 255->65535 exactly.
RGB_SCALE = 257


def to_16_bit(channel: int) -> int:
    """An 8-bit channel widened to the 16-bit field LAS actually defines.

    Writing 8-bit values into a 16-bit field is the quiet version of this bug:
    PDAL stores them verbatim and reports them back unchanged, so the LAS looks
    right, but a reader that correctly treats the field as 16-bit scales 138
    down to 0 and the whole cloud renders black. Multiplying by 257 maps 0 to 0
    and 255 to 65535 exactly.
    """
    return channel * RGB_SCALE


def _paint(rgb, where: str) -> list:
    red, green, blue = (to_16_bit(c) for c in rgb)
    return [f"Red = {red} WHERE {where}",
            f"Green = {green} WHERE {where}",
            f"Blue = {blue} WHERE {where}"]


def classification_colorization(classes=OBSTRUCTION_CLASSES) -> dict:
    """A ``filters.assign`` stage painting each class its map colour.

    3D Tiles carries per-point RGB but no classification, so a viewer styling
    on ``${Classification}`` matches nothing and paints every point its
    fallback colour — a uniformly grey cloud where the ground and the treeline
    are indistinguishable. Baking the palette into RGB here puts the
    information somewhere the format can actually carry it.
    """
    assignments = []
    for code in sorted(classes):
        if code in CLASSIFICATION_COLORS:
            assignments.extend(_paint(CLASSIFICATION_COLORS[code],
                                      f"Classification == {code}"))
    return {"type": "filters.assign", "value": assignments}


def height_colorization(bands=HEIGHT_BANDS) -> dict:
    """A ``filters.assign`` stage painting each point by its height above ground."""
    assignments, lower = [], None
    for upper, rgb in bands:
        if lower is None:
            where = f"{HAG_DIMENSION} < {upper}"
        elif upper is None:
            where = f"{HAG_DIMENSION} >= {lower}"
        else:
            where = f"{HAG_DIMENSION} >= {lower} && {HAG_DIMENSION} < {upper}"
        assignments.extend(_paint(rgb, where))
        lower = upper
    return {"type": "filters.assign", "value": assignments}


def height_above_ground() -> dict:
    """Derive height above ground from the ground-classified returns.

    Nearest-neighbour rather than the Delaunay variant: it is markedly faster
    on a dense tile and an LZ only needs the height to within a fraction of a
    metre. Requires class 2 to be present, which is the one thing even a
    minimally classified survey provides.
    """
    return {"type": "filters.hag_nn", "allow_extrapolation": True}


COLOR_BY_CLASSIFICATION = "classification"
COLOR_BY_HEIGHT = "height"
COLOR_BY_IMAGERY = "imagery"
COLOR_MODES = (COLOR_BY_CLASSIFICATION, COLOR_BY_HEIGHT, COLOR_BY_IMAGERY, None)


def imagery_colorization(raster: str) -> dict:
    """Sample real colour per point from an aerial image.

    This is what makes a point cloud read as a photograph of a place rather
    than a diagram of one: trees come out the colour of those trees, a gravel
    pad the colour of that pad. A synthetic palette can only ever say what
    class a point is, which is a weaker claim than what it looks like.

    ``raster`` is anything GDAL can open, including a GDAL_WMS service
    description pointing at the same satellite tiles the 2D map uses, so no
    imagery has to be downloaded ahead of time.
    """
    return {"type": "filters.colorization", "raster": raster,
            # band:scale per channel. LAS RGB is 16-bit and the imagery is
            # 8-bit, so each channel is widened by the same 257 the synthetic
            # palettes use — otherwise every point renders near-black.
            "dimensions": (f"Red:1:{RGB_SCALE}, "
                           f"Green:2:{RGB_SCALE}, "
                           f"Blue:3:{RGB_SCALE}")}


# The bundled imagery (mapbox_imagery.xml) is Web Mercator, and a custom
# LIDAR_IMAGERY raster must be too: points are moved into it before colouring.
IMAGERY_EPSG = 3857


def in_imagery_frame(srs: str) -> bool:
    """Whether points in ``srs`` can be looked up in the imagery as they are."""
    return horizontal_crs(srs).to_epsg() == IMAGERY_EPSG


def build(source, destination: str, *, bbox=None, source_srs: str,
          classes=OBSTRUCTION_CLASSES, thin_spacing_m: float | None = None,
          color_by: str | None = COLOR_BY_CLASSIFICATION,
          imagery_raster: str | None = None) -> list:
    """Pipeline stages for one area of interest.

    ``source`` may be a local file, an EPT/COPC URL, or a list of local tiles.
    A remote index is read directly and only the points inside ``bbox`` are
    fetched, so an area of interest costs a query rather than a download.

    A list is how a downloaded collection is read: the published tiles are
    plain LAZ with no index, an area of interest near a tile edge spans
    several of them, and the readers have to be merged before anything
    downstream sees a single cloud.

    ``thin_spacing_m`` decimates to roughly one point per that spacing. The
    sample tile carries 4.3 points/m², which is far more than a view needs; the
    full density is worth keeping only when measuring.
    """
    sources = [source] if isinstance(source, (str, Path)) else list(source)
    if not sources:
        raise ValueError("no source tiles to read")

    bounds = bounds_expression(bbox) if bbox is not None else None
    stages, needs_crop = [], False

    for one in sources:
        reader_type = _reader_for(str(one))
        reader = {"type": reader_type, "filename": str(one)}
        if bounds is not None:
            if reader_type in INDEXED_READERS:
                # EPT and COPC carry a spatial index, so the reader fetches
                # only the requested extent — an area of interest costs a
                # query rather than a whole tile.
                reader["bounds"] = bounds
            else:
                # Plain LAS/LAZ has no index. It must be read in full and
                # cropped, and it rejects a `bounds` argument outright.
                needs_crop = True
        stages.append(reader)

    if len(stages) > 1:
        # Without an explicit merge, PDAL runs the rest of the pipeline once
        # per reader and the last one wins — the output would hold a single
        # tile's worth of the area rather than all of them.
        stages.append({"type": "filters.merge"})

    if needs_crop:
        stages.append({"type": "filters.crop", "bounds": bounds})

    # Noise first: a stray low point would otherwise drag the ground surface
    # down and make an obstruction look taller than it is.
    stages.append({"type": "filters.range",
                   "limits": classification_limits(classes)})

    if color_by not in COLOR_MODES:
        raise ValueError(f"unknown colour mode {color_by!r}")

    if color_by == COLOR_BY_HEIGHT:
        # Before thinning: the ground surface this measures against is built
        # from the ground returns, and sampling first would thin them out too.
        stages.append(height_above_ground())

    if thin_spacing_m:
        stages.append({"type": "filters.sample", "radius": thin_spacing_m})

    # The frame the points are in from here on.
    frame = source_srs
    if color_by == COLOR_BY_CLASSIFICATION:
        stages.append(classification_colorization(classes))
    elif color_by == COLOR_BY_HEIGHT:
        stages.append(height_colorization())
    elif color_by == COLOR_BY_IMAGERY:
        if not imagery_raster:
            raise ValueError("colour by imagery needs an imagery_raster")
        # filters.colorization looks each point up in the raster at the
        # point's own coordinates and does no reprojection, so the points
        # must already be in the imagery's frame. AWS data arrives in it. The
        # downloaded collection is in Albers, and Albers coordinates for north
        # Georgia read as Web Mercator land in Nigeria: the first build from
        # the collection came out coloured like savanna. Move the points over
        # first, keeping NAVD88 heights, so the geoid correction below is the
        # same one AWS builds get.
        if not in_imagery_frame(source_srs):
            stages.append({"type": "filters.reprojection",
                           "in_srs": source_srs, "out_srs": WEB_MERCATOR_NAVD88})
            frame = WEB_MERCATOR_NAVD88
        stages.append(imagery_colorization(imagery_raster))

    # The vertical half of this is the whole reason crs.py exists.
    stages.append({"type": "filters.reprojection",
                   "in_srs": frame, "out_srs": ECEF})

    # ECEF values are ~5e6 m, so millimetre scaling with an auto offset keeps
    # the LAS integer encoding from quantising away real detail. Format 3 is
    # the first that carries RGB; the default has nowhere to put the colours.
    writer = {"type": "writers.las", "filename": destination,
              "scale_x": 0.001, "scale_y": 0.001, "scale_z": 0.001,
              "offset_x": "auto", "offset_y": "auto", "offset_z": "auto",
              "a_srs": ECEF}
    if color_by:
        writer["dataformat_id"] = 3
    stages.append(writer)
    return stages


def _reader_for(source: str) -> str:
    # An Entwine index is named by its ept.json, local or remote — PDAL has no
    # URL scheme for it, and inventing one makes it reject the source outright.
    lowered = source.lower()
    if lowered.endswith("ept.json"):
        return "readers.ept"
    if lowered.endswith(".copc.laz"):
        return "readers.copc"
    return "readers.las"


def to_json(stages, *, indent=2) -> str:
    return json.dumps({"pipeline": stages}, indent=indent)
