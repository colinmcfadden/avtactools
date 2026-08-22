"""PDAL pipeline construction for turning 3DEP tiles into Cesium-ready points.

A pipeline is plain JSON, so it is built and tested as data here and handed to
PDAL to execute. That keeps the part with the decisions in it — which points to
keep, which frame to land in — verifiable without the toolchain installed.

The output is WGS84 geocentric metres, which is what 3D Tiles positions are
expressed in.
"""

from __future__ import annotations

import json

from .crs import ECEF

# ASPRS classification codes present in 3DEP tiles.
GROUND = 2
LOW_VEGETATION = 3
MEDIUM_VEGETATION = 4
HIGH_VEGETATION = 5
NOISE = 7

# What an LZ assessment actually needs: the surface, and anything standing on
# it. Unclassified returns are dropped — in the sample tile they were 22% of
# points and contribute noise rather than structure.
OBSTRUCTION_CLASSES = (GROUND, LOW_VEGETATION, MEDIUM_VEGETATION, HIGH_VEGETATION)

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


def build(source: str, destination: str, *, bbox=None, source_srs: str,
          classes=OBSTRUCTION_CLASSES, thin_spacing_m: float | None = None) -> list:
    """Pipeline stages for one area of interest.

    ``source`` may be a local file or an EPT/COPC URL — PDAL reads a remote
    index and fetches only the points inside ``bbox``, so an area of interest
    costs a query rather than a download.

    ``thin_spacing_m`` decimates to roughly one point per that spacing. The
    sample tile carries 4.3 points/m², which is far more than a view needs; the
    full density is worth keeping only when measuring.
    """
    reader_type = _reader_for(source)
    reader = {"type": reader_type, "filename": source}
    stages = [reader]

    if bbox is not None:
        bounds = bounds_expression(bbox)
        if reader_type in INDEXED_READERS:
            # EPT and COPC carry a spatial index, so the reader fetches only
            # the requested extent — an area of interest costs a query rather
            # than a whole tile.
            reader["bounds"] = bounds
        else:
            # Plain LAS/LAZ has no index. It must be read in full and cropped,
            # and it rejects a `bounds` argument outright.
            stages.append({"type": "filters.crop", "bounds": bounds})

    # Noise first: a stray low point would otherwise drag the ground surface
    # down and make an obstruction look taller than it is.
    stages.append({"type": "filters.range",
                   "limits": classification_limits(classes)})

    if thin_spacing_m:
        stages.append({"type": "filters.sample", "radius": thin_spacing_m})

    # The vertical half of this is the whole reason crs.py exists.
    stages.append({"type": "filters.reprojection",
                   "in_srs": source_srs, "out_srs": ECEF})

    # ECEF values are ~5e6 m, so millimetre scaling with an auto offset keeps
    # the LAS integer encoding from quantising away real detail.
    stages.append({"type": "writers.las", "filename": destination,
                   "scale_x": 0.001, "scale_y": 0.001, "scale_z": 0.001,
                   "offset_x": "auto", "offset_y": "auto", "offset_z": "auto",
                   "a_srs": ECEF})
    return stages


def _reader_for(source: str) -> str:
    lowered = source.lower()
    if lowered.startswith("ept://") or lowered.endswith("ept.json"):
        return "readers.ept"
    if lowered.endswith(".copc.laz"):
        return "readers.copc"
    return "readers.las"


def to_json(stages, *, indent=2) -> str:
    return json.dumps({"pipeline": stages}, indent=indent)
