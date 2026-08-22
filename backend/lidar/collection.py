"""Indexing a directory of downloaded 3DEP tiles so a target can find its data.

The published tiles are plain LAZ: no spatial index, no manifest, and named by
a sequence number that says nothing about where they are. Building a tileset
for a landing point therefore starts by working out which of a few thousand
files actually cover it.

Bounds come from the LAS public header block, which sits uncompressed at the
front of a LAZ file at fixed offsets. Reading it costs a few hundred bytes per
tile rather than the tens of megabytes a point read would, so a whole
collection indexes in seconds and can be re-indexed freely as a download fills
in.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

# Offsets into the LAS public header block, identical across 1.0-1.4 for the
# fields used here. The bounds are six doubles in max/min pairs.
_SIGNATURE = b"LASF"
_VERSION_OFFSET = 24
_HEADER_SIZE_OFFSET = 94
_LEGACY_COUNT_OFFSET = 107
_BOUNDS_OFFSET = 179
_POINT_COUNT_1_4_OFFSET = 247

# Enough for every field read here, including the 1.4 point count.
_HEADER_READ_BYTES = 375

# VLR record ids carrying the coordinate system.
_GEOKEY_DIRECTORY = 34735
_GEOASCII_PARAMS = 34737
_WKT_RECORD = 2112

# GeoTIFF key ids. The vertical one matters most by its absence.
_PROJECTED_CS = 3072
_GEOGRAPHIC_CS = 2048
_VERTICAL_CS = 4096
_VERTICAL_CITATION = 4097

_VLR_HEADER_BYTES = 54

# VLR text fields are fixed-width and NUL-padded, so every string read out of
# one has to be trimmed before it means anything.
_NUL = chr(0)

# 3DEP is published against NAVD88 throughout. Tiles that name it only in a
# citation string still carry NAVD88 heights, so this is the datum to apply —
# but it has to be applied explicitly, because PDAL cannot read a sentence.
DEFAULT_VERTICAL = "EPSG:5703"

# PROJ's compound syntax takes a bare code after the plus: "EPSG:26917+5703",
# not "EPSG:26917+EPSG:5703", which it rejects.
_EPSG_PREFIX = "EPSG:"

INDEX_FILENAME = "tile_index.json"


class CollectionError(RuntimeError):
    """A collection could not be read or holds nothing usable."""


@dataclass(frozen=True)
class TileCrs:
    """What a tile says about its own coordinate system.

    ``vertical_declared`` records whether a VerticalCSTypeGeoKey is actually
    present. USGS tiles routinely name their datum only in a citation string —
    ARRA_GA_LAKELANIER_2010 says "NAVD88 - Geoid09 (Meters)" and sets no
    vertical key at all.

    PDAL infers NAVD88 from the surrounding GeoTIFF keys on those files: forcing
    ``EPSG:26917+5703`` and letting PDAL read the file were measured to give an
    identical ECEF Z. Forcing it is kept anyway, because the cost is nothing and
    a file that PDAL cannot infer from would otherwise have its heights passed
    through unreprojected — about 30 m off the ellipsoid in Georgia, which looks
    entirely plausible until it is measured.
    """

    horizontal: str
    vertical_declared: bool
    citation: str = ""

    def compound(self, vertical: str = DEFAULT_VERTICAL) -> str:
        """The CRS string to hand PDAL, with the vertical datum forced on.

        A tile that already declares its vertical axis is returned unchanged —
        appending a second one produces a CRS PROJ refuses.
        """
        if self.vertical_declared:
            return self.horizontal
        code = vertical[len(_EPSG_PREFIX):] if vertical.startswith(_EPSG_PREFIX) else vertical
        return f"{self.horizontal}+{code}"


@dataclass(frozen=True)
class Tile:
    """One LAZ file and the ground it covers."""

    path: Path
    bounds: tuple  # (xmin, ymin, xmax, ymax) in the collection's own CRS
    points: int

    def intersects(self, bbox) -> bool:
        """Whether this tile overlaps an area of interest.

        Touching edges count: a target on a tile boundary needs both tiles, and
        excluding one would cut the point cloud in half along a straight line.
        """
        xmin, ymin, xmax, ymax = bbox
        txmin, tymin, txmax, tymax = self.bounds
        return not (txmax < xmin or txmin > xmax or tymax < ymin or tymin > ymax)


def read_header(path) -> Tile:
    """Bounds and point count for one LAS/LAZ file, from its header alone."""
    path = Path(path)
    with path.open("rb") as handle:
        head = handle.read(_HEADER_READ_BYTES)

    if len(head) < _BOUNDS_OFFSET + 48 or head[:4] != _SIGNATURE:
        raise CollectionError(f"{path.name} is not a LAS/LAZ file")

    major, minor = head[_VERSION_OFFSET], head[_VERSION_OFFSET + 1]
    maxx, minx, maxy, miny, _maxz, _minz = struct.unpack_from("<6d", head, _BOUNDS_OFFSET)

    # 1.4 moved the point count to a 64-bit field and leaves the legacy one at
    # zero for files that overflow it.
    points = struct.unpack_from("<I", head, _LEGACY_COUNT_OFFSET)[0]
    header_size = struct.unpack_from("<H", head, _HEADER_SIZE_OFFSET)[0]
    if (major, minor) >= (1, 4) and header_size >= _POINT_COUNT_1_4_OFFSET + 8:
        wide = struct.unpack_from("<Q", head, _POINT_COUNT_1_4_OFFSET)[0]
        if wide:
            points = wide

    return Tile(path=path, bounds=(minx, miny, maxx, maxy), points=points)


def _vlrs(data, header_size, count):
    """Walk the VLRs, yielding ``(record_id, payload)``."""
    offset = header_size
    for _ in range(count):
        if offset + _VLR_HEADER_BYTES > len(data):
            return
        record_id = struct.unpack_from("<H", data, offset + 18)[0]
        length = struct.unpack_from("<H", data, offset + 20)[0]
        body = data[offset + _VLR_HEADER_BYTES:offset + _VLR_HEADER_BYTES + length]
        yield record_id, body
        offset += _VLR_HEADER_BYTES + length


def _geokeys(body) -> dict:
    """The GeoTIFF key directory as ``{key_id: (tag_location, count, value)}``."""
    if len(body) < 8:
        return {}
    _version, _revision, _minor, count = struct.unpack_from("<4H", body, 0)
    keys = {}
    for index in range(count):
        start = 8 + index * 8
        if start + 8 > len(body):
            break
        key_id, location, size, value = struct.unpack_from("<4H", body, start)
        keys[key_id] = (location, size, value)
    return keys


def read_crs(path, *, probe_bytes=65536) -> TileCrs:
    """The coordinate system a tile declares, read from its VLRs.

    Handles both forms: GeoTIFF keys (LAS 1.0-1.3) and embedded WKT (1.4).
    """
    path = Path(path)
    with path.open("rb") as handle:
        data = handle.read(probe_bytes)

    if len(data) < 100 or data[:4] != _SIGNATURE:
        raise CollectionError(f"{path.name} is not a LAS/LAZ file")

    header_size = struct.unpack_from("<H", data, _HEADER_SIZE_OFFSET)[0]
    vlr_count = struct.unpack_from("<I", data, 100)[0]

    keys, ascii_params, wkt = {}, "", ""
    for record_id, body in _vlrs(data, header_size, vlr_count):
        if record_id == _GEOKEY_DIRECTORY:
            keys = _geokeys(body)
        elif record_id == _GEOASCII_PARAMS:
            ascii_params = body.decode("latin-1")
        elif record_id == _WKT_RECORD:
            wkt = body.decode("latin-1").rstrip(_NUL)

    if wkt:
        # A 1.4 file states its CRS outright, vertical axis included.
        return TileCrs(horizontal=wkt,
                       vertical_declared="VERT_CS" in wkt.upper()
                                         or "VERTCRS" in wkt.upper(),
                       citation=ascii_params.strip(_NUL))

    epsg = None
    for key in (_PROJECTED_CS, _GEOGRAPHIC_CS):
        entry = keys.get(key)
        # tag_location 0 means the value is inline rather than in a side array.
        if entry and entry[0] == 0 and entry[2] not in (0, 32767):
            epsg = entry[2]
            break
    if epsg is None:
        raise CollectionError(f"{path.name} declares no readable horizontal CRS")

    vertical = keys.get(_VERTICAL_CS)
    declared = bool(vertical and vertical[0] == 0 and vertical[2] not in (0, 32767))

    citation = ""
    cite = keys.get(_VERTICAL_CITATION)
    if cite and cite[0] == _GEOASCII_PARAMS:
        _location, size, value = cite
        citation = ascii_params[value:value + size].strip(_NUL + "|")

    return TileCrs(horizontal=f"EPSG:{epsg}",
                   vertical_declared=declared,
                   citation=citation)


def scan(directory, *, pattern="*.laz") -> list:
    """Index every tile in a directory, skipping anything unreadable.

    A download in progress leaves partial files behind, and a truncated file
    has a valid header — so it indexes fine and simply covers less than the
    finished one will. Re-scanning after the download completes corrects it.
    """
    directory = Path(directory)
    if not directory.is_dir():
        raise CollectionError(f"{directory} is not a directory")

    tiles = []
    for path in sorted(directory.glob(pattern)):
        try:
            tiles.append(read_header(path))
        except (CollectionError, OSError, struct.error):
            continue  # not a tile, or still arriving
    return tiles


def tiles_for(tiles, bbox) -> list:
    """The tiles covering an area of interest, largest overlap first.

    Ordering by overlap keeps the file holding most of the target first in the
    pipeline, which is the one worth reading even if the others are dropped.
    """
    covering = [tile for tile in tiles if tile.intersects(bbox)]
    return sorted(covering, key=lambda tile: -_overlap_area(tile.bounds, bbox))


def _overlap_area(bounds, bbox) -> float:
    xmin = max(bounds[0], bbox[0])
    ymin = max(bounds[1], bbox[1])
    xmax = min(bounds[2], bbox[2])
    ymax = min(bounds[3], bbox[3])
    return max(0.0, xmax - xmin) * max(0.0, ymax - ymin)


def save_index(tiles, path) -> Path:
    """Cache an index so repeated builds do not re-read every header."""
    path = Path(path)
    path.write_text(json.dumps({
        "tiles": [
            {"path": str(tile.path), "bounds": list(tile.bounds), "points": tile.points}
            for tile in tiles
        ],
    }, indent=2), encoding="utf-8")
    return path


def load_index(path) -> list:
    """Read a cached index, dropping entries whose file has since gone."""
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    tiles = []
    for entry in document.get("tiles", []):
        tile_path = Path(entry["path"])
        if tile_path.exists():
            tiles.append(Tile(path=tile_path,
                              bounds=tuple(entry["bounds"]),
                              points=entry.get("points", 0)))
    return tiles


def index_for(directory, *, refresh=False, pattern="*.laz") -> list:
    """The collection's index, cached beside the tiles.

    ``refresh`` re-reads every header, which is what to do while a download is
    still adding files.
    """
    directory = Path(directory)
    cache = directory / INDEX_FILENAME
    if not refresh and cache.exists():
        tiles = load_index(cache)
        if tiles:
            return tiles

    tiles = scan(directory, pattern=pattern)
    if not tiles:
        raise CollectionError(f"no readable LAZ tiles in {directory}")
    save_index(tiles, cache)
    return tiles
