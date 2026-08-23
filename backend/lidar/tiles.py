"""Generating a Cesium 3D Tiles point cloud for one area of interest.

Two steps, both run inside the toolchain image (see Dockerfile):

1. **PDAL** crops the source to the area, drops noise and unclassified returns,
   and reprojects into WGS84 geocentric metres — the frame 3D Tiles positions
   are expressed in.
2. **py3dtiles** converts that into the tileset Cesium loads.

Kept as an out-of-process job rather than an import: PDAL's stack is large, the
work is measured in tens of seconds, and the app should stay able to serve
tiles without being able to build them.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from pathlib import Path

from . import pipeline
from .crs import ECEF

# Thinning was costing more than it saved. A 500 m area of GA_Statewide_B3_2018
# holds 3.5 points/m²; sampling to one per metre threw away half of that and
# left visible gaps between points, which is what made the cloud read as
# scattered dots rather than a surface.
#
# The remaining thinning was guarding browser memory, and that guard was
# unnecessary: 3D Tiles streams by level of detail. A built tileset of 688,375
# points loads 2,670 of them when the camera is pulled back, and only reaches
# the full-detail leaves close in. Quadrupling the area does not quadruple what
# a viewer holds.
#
# So the real costs of a large build are the ones the operator pays — time
# against a remote survey, and disk on the server — not anything the crew
# sees. Full density therefore runs out to a radius wide enough for any
# approach, and thinning past it is gentle rather than protective.
FULL_DENSITY_RADIUS_M = 1000.0

# Roughly bytes per point through the pipeline, for warning about a build
# before it runs rather than after it has taken twenty minutes.
BYTES_PER_POINT = 15
TYPICAL_DENSITY_PER_M2 = 2.75


def estimated_size_mb(radius_m: float, spacing_m=None) -> float:
    """Rough tileset size, for telling an operator what they have asked for."""
    area = (2 * radius_m) ** 2
    density = TYPICAL_DENSITY_PER_M2 if not spacing_m else min(
        TYPICAL_DENSITY_PER_M2, 1.0 / (spacing_m ** 2))
    return area * density * BYTES_PER_POINT / 1048576


# A context ring is landscape, not obstruction data: it exists so the approach
# has somewhere to be, and nothing in it is measured. Thinning it hard is what
# makes a wide ring affordable to build.
CONTEXT_SPACING_DIVISOR = 250.0
MIN_CONTEXT_SPACING_M = 2.0

# No thinning. Kept as a name because callers pass it explicitly.
DISPLAY_SPACING_M = None


def context_spacing(radius_m: float) -> float:
    """Point spacing for the surrounding context ring."""
    return max(MIN_CONTEXT_SPACING_M, radius_m / CONTEXT_SPACING_DIVISOR)


def spacing_for_radius(radius_m: float) -> float | None:
    """Point spacing for a build of this radius.

    None means no thinning — every return the survey recorded, which is the
    answer for anything up to a kilometre.
    """
    if radius_m <= FULL_DENSITY_RADIUS_M:
        return None
    # Gentle: the square root keeps a 4 km radius at 2 m spacing rather than
    # the 10 m the old linear rule would have imposed.
    return (radius_m / FULL_DENSITY_RADIUS_M) ** 0.5


class TileBuildError(RuntimeError):
    """Tile generation failed; the message carries the tool's own output."""


def build_pointcloud_tiles(source, output_dir: Path, *, bbox,
                           source_srs: str, thin_spacing_m=DISPLAY_SPACING_M,
                           classes=pipeline.OBSTRUCTION_CLASSES,
                           color_by=pipeline.COLOR_BY_CLASSIFICATION,
                           imagery_raster=None, runner=None) -> Path:
    """Produce a 3D Tiles point cloud, returning the path to its tileset.json.

    ``source`` may be a local file, a remote EPT/COPC URL, or a list of local
    tiles covering the area — which is what a downloaded collection yields near
    a tile boundary. ``bbox`` is in the source's own coordinates, since that is
    what the tile index is written in.
    """
    output_dir = Path(output_dir)
    run = runner or _run

    with tempfile.TemporaryDirectory(prefix="lidar-") as scratch:
        scratch = Path(scratch)
        # Uncompressed .las deliberately: py3dtiles reads LAZ only with a
        # separate compression backend installed, and this file is a throwaway
        # inside a temp directory. Roughly 20 MB for an LZ-sized area.
        reprojected = scratch / "aoi_ecef.las"

        stages = pipeline.build(source, str(reprojected), bbox=bbox,
                                source_srs=source_srs, classes=classes,
                                thin_spacing_m=thin_spacing_m,
                                color_by=color_by,
                                imagery_raster=imagery_raster)
        pipeline_file = scratch / "pipeline.json"
        pipeline_file.write_text(pipeline.to_json(stages))

        run(["pdal", "pipeline", str(pipeline_file)])

        if not reprojected.exists() or reprojected.stat().st_size == 0:
            raise TileBuildError(
                "PDAL produced no output — the area of interest probably falls "
                "outside the source, or holds no points of the kept classes."
            )

        # py3dtiles writes a directory; build into scratch and move into place
        # so a failure never leaves a half-written tileset being served.
        # PDAL has already put the points in ECEF, so this is a declaration
        # rather than a conversion — py3dtiles still wants both stated.
        staged = scratch / "tiles"
        epsg = ECEF.split(":")[1]
        run(["py3dtiles", "convert", str(reprojected),
             "--out", str(staged), "--srs_in", epsg,
             "--srs_out", epsg, "--overwrite"])

        tileset = staged / "tileset.json"
        if not tileset.exists():
            raise TileBuildError("py3dtiles produced no tileset.json")

        if output_dir.exists():
            shutil.rmtree(output_dir)
        output_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staged), str(output_dir))

    return output_dir / "tileset.json"


def describe(tileset_path: Path) -> dict:
    """Point count and extent, for logging what a build actually produced."""
    data = json.loads(Path(tileset_path).read_text())
    root = data.get("root", {})
    return {
        "geometric_error": data.get("geometricError"),
        "root_geometric_error": root.get("geometricError"),
        "bounding_volume": root.get("boundingVolume"),
        "children": len(root.get("children", [])),
    }


def _run(command) -> str:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise TileBuildError(
            f"{command[0]} failed ({result.returncode}):\n"
            f"{(result.stderr or result.stdout or '').strip()[:2000]}"
        )
    return result.stdout
