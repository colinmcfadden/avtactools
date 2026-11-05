"""What the Data tab's map draws: the data the server holds, as GeoJSON (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10.3).

Admins only. Where the streamed cache and the built tilesets are says where crews have opened 3D, so those two are
counts in 0.1° cells (about 10 km, D16), never pieces or squares. The walks of the caches are kept for 10 minutes.
"""

from __future__ import annotations

import json
import math
import os
import re
import threading
import time
from pathlib import Path

CELL_DEG = 0.1
LIDAR_TILE_CELLS_ABOVE = 20_000
LIDAR_CELL_DEG = 0.05
KEEP_S = 600
# A cached piece wider than a cell would be counted into one cell for ground it does not lie on: only the survey's
# totals count it.
MAX_CELL_PIECE_M = 10_000
MAX_PIECES_READ = 300_000

_HEX16 = re.compile(r"^[0-9a-f]{16}$")
_PIECE = re.compile(r"^([0-9a-f]{16})\.pnts$")
_LAZ = re.compile(r"^(\d+-\d+-\d+-\d+)\.laz$")
_LPC_PROJECT = re.compile(r"^USGS_LPC_(.+?)_(?:e|x)?\d+[n_]", re.IGNORECASE)

_kept = {}
_kept_lock = threading.Lock()
_making = {}     # name: the lock its one walk at a time holds


def _remember(name, make, *, fresh=False, stamp=None):
    """``make()``'s answer, kept 10 minutes (or until ``stamp`` changes). One walk at a time for each name: a request that
    comes while one runs waits for it and takes its answer, rather than walking the same cache again on another of the
    API's eight threads, and *Refresh* (``fresh``) asked while a walk runs takes that walk's answer too."""
    asked = time.monotonic()
    with _kept_lock:
        found = _kept.get(name)
        if found and found[2] == stamp and not fresh and asked - found[0] < KEEP_S:
            return found[1]
        lock = _making.setdefault(name, threading.Lock())
    with lock:
        with _kept_lock:
            found = _kept.get(name)
            if found and found[2] == stamp and (found[0] >= asked or (not fresh and asked - found[0] < KEEP_S)):
                return found[1]
        value = make()
        with _kept_lock:
            _kept[name] = (time.monotonic(), value, stamp)
        return value


def forget():
    with _kept_lock:
        _kept.clear()


def _size(item) -> int:
    """A file's size, or 0 for one let go of since the folder was listed (the streamer prunes and renames as it works)."""
    try:
        return item.stat().st_size
    except OSError:
        return 0


def _box(west, south, east, north):
    return {"type": "Polygon", "coordinates": [[[west, south], [east, south], [east, north], [west, north],
                                                [west, south]]]}


def _cell_feature(key, size, properties):
    row, column = key
    return {"type": "Feature", "geometry": _box(column * size, row * size, (column + 1) * size, (row + 1) * size),
            "properties": properties}


def _cell_of(lat, lon, size=CELL_DEG):
    return math.floor(lat / size), math.floor(lon / size)


def dem_roots(environ=os.environ):
    return [root for root in (environ.get("TERRAIN_DATA_DIR") or "").split(os.pathsep) if root.strip()]


def relative(path, environ=os.environ) -> str:
    """A DEM's path as the admin reads it: inside its root, never the server's whole path."""
    for root in dem_roots(environ):
        try:
            return str(Path(path).resolve().relative_to(Path(root).resolve())).replace("\\", "/")
        except ValueError:
            continue
    return Path(path).name


# -- DEMs -------------------------------------------------------------------

def dems() -> dict:
    from terrain_provider import LOCAL_CATALOG, _env_float

    slope_limit = _env_float("TERRAIN_LOCAL_HIGHRES_MAX_M", 15.0)
    features = []
    for entry in LOCAL_CATALOG.entries():
        south, west, north, east = entry.bounds_latlon
        try:
            stat = os.stat(entry.path)
            size, modified = stat.st_size, int(stat.st_mtime)
        except OSError:
            size = modified = None
        datum = entry.vertical_datum or ""
        features.append({"type": "Feature", "geometry": _box(west, south, east, north), "properties": {
            "name": relative(entry.path), "kind": entry.kind, "resolution_m": round(entry.resolution_m, 2),
            "datum": datum, "navd88": "NAVD88" in datum.upper().replace(" ", ""), "bytes": size, "modified": modified,
            "slope": entry.kind == "dted2" or entry.resolution_m <= slope_limit,
        }})
    return {"type": "FeatureCollection", "features": features}


# -- the streamed LiDAR cache ---------------------------------------------------

def _stream_walk() -> dict:
    from lidar import ept, names, stream

    root = stream.cache_dir()
    if root is None or not root.is_dir():
        return {"enabled": stream.enabled(), "surveys": [], "cells": [], "bytes": 0, "older_bytes": 0,
                "cap_bytes": stream._cap_bytes()}
    streamer = stream.streamer()
    try:
        by_key = streamer._by_key()
    except Exception:  # noqa: BLE001 — no coverage index yet: every folder is unknown
        by_key = {}
    secret = streamer._secret_now()
    cells, surveys, older, total, read = {}, [], 0, 0, 0
    for folder in sorted(p for p in root.iterdir() if p.is_dir() and _HEX16.match(p.name)):
        known = by_key.get(folder.name)
        info = None
        if known is not None:
            try:
                info = ept.Info.read(json.loads((folder / "ept.json").read_bytes()))
            except (OSError, ValueError, ept.EptError):
                info = None
        summary = {"name": known[0].name if known else None, "year": known[0].year if known else None,
                   "pieces": 0, "photographic": 0, "laz": 0, "bytes": 0}

        def count(node, size, *, is_laz):
            nonlocal read
            summary["bytes"] += size
            if is_laz:
                summary["laz"] += 1
            else:
                summary["pieces"] += 1
            if info is None or node is None or read >= MAX_PIECES_READ or info.width(node) > MAX_CELL_PIECE_M:
                return
            read += 1
            xmin, ymin, _, xmax, ymax, _ = info.cube(node)
            lon, lat = ept.mercator_to_lonlat((xmin + xmax) / 2, (ymin + ymax) / 2)
            cell = cells.setdefault(_cell_of(lat, lon), {"pieces": 0, "laz": 0, "bytes": 0, "surveys": set()})
            cell["laz" if is_laz else "pieces"] += 1
            cell["bytes"] += size
            cell["surveys"].add(summary["name"])

        with os.scandir(folder) as found:
            for item in found:
                if item.name.startswith(".") or not item.is_file():
                    continue
                match = _PIECE.match(item.name)
                if match:
                    node = names.piece_node(known[0].name, match.group(1), secret=secret) if known else None
                    count(node, _size(item), is_laz=False)
                elif item.name.endswith(".pnts.photo"):
                    summary["photographic"] += 1
                else:
                    summary["bytes"] += _size(item)
        laz = folder / "laz"
        if laz.is_dir():
            with os.scandir(laz) as found:
                for item in found:
                    match = _LAZ.match(item.name)
                    if item.is_file() and match:
                        try:
                            node = ept.Node.parse(match.group(1))
                        except ept.EptError:
                            node = None
                        count(node, _size(item), is_laz=True)
        hierarchy = folder / "h"
        if hierarchy.is_dir():
            with os.scandir(hierarchy) as found:
                summary["bytes"] += sum(_size(item) for item in found if item.is_file())
        total += summary["bytes"]
        if known is None:
            older += summary["bytes"]     # a survey key of an older look (pieces.LOOK): never served again
        else:
            surveys.append(summary)
    surveys.sort(key=lambda s: -s["bytes"])
    return {
        "enabled": True, "surveys": surveys, "bytes": total, "older_bytes": older, "cap_bytes": stream._cap_bytes(),
        "cells": [_cell_feature(key, CELL_DEG, {"pieces": c["pieces"], "laz": c["laz"], "bytes": c["bytes"],
                                                 "surveys": sorted(n for n in c["surveys"] if n)})
                  for key, c in sorted(cells.items())],
        "complete": read < MAX_PIECES_READ,
    }


def stream_cache(*, fresh=False) -> dict:
    return _remember("stream", _stream_walk, fresh=fresh)


# -- built tilesets -----------------------------------------------------------------

def _tileset_walk() -> dict:
    from lidar import catalog

    root = catalog.tiles_dir()
    cells, count = {}, 0
    if root.is_dir():
        for key in catalog.available(root=root):
            place = catalog.read_manifest(root / key)
            if place is None:
                continue
            count += 1
            key = _cell_of(place[0], place[1])
            cells[key] = cells.get(key, 0) + 1
    return {"count": count, "cells": [_cell_feature(key, CELL_DEG, {"tilesets": n}) for key, n in sorted(cells.items())]}


def tilesets(*, fresh=False) -> dict:
    return _remember("tilesets", _tileset_walk, fresh=fresh)


# -- downloaded LiDAR (the build service's collection, where the API can see it) ---------------------------------

def _survey_of(path: str) -> str:
    name = Path(path).name
    match = _LPC_PROJECT.match(name)
    if match:
        return match.group(1)
    return Path(path).parent.name or "?"


def _lidar_walk(index_path: Path) -> dict:
    from pyproj import Transformer

    try:
        document = json.loads(index_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"available": False, "tiles": 0, "surveys": [], "features": []}
    transformers, tiles, surveys = {}, [], {}
    for tile in document.get("tiles") or []:
        try:
            xmin, ymin, xmax, ymax = (float(v) for v in tile["bounds"])
            crs = str(tile.get("crs") or "")
        except (KeyError, TypeError, ValueError):
            continue
        if not crs:
            continue
        if crs not in transformers:
            try:
                transformers[crs] = Transformer.from_crs(crs, "EPSG:4326", always_xy=True)
            except Exception:  # noqa: BLE001 — a CRS PROJ cannot read: its tiles are left off the map
                transformers[crs] = None
        transformer = transformers[crs]
        if transformer is None:
            continue
        xs, ys = transformer.transform([xmin, xmax, xmax, xmin], [ymin, ymin, ymax, ymax])
        if not all(math.isfinite(v) for v in list(xs) + list(ys)):
            continue
        survey = _survey_of(tile.get("path") or "")
        found = re.search(r"(19|20)\d{2}", survey)
        surveys.setdefault(survey, {"name": survey, "year": int(found.group()) if found else None, "tiles": 0})
        surveys[survey]["tiles"] += 1
        tiles.append((min(xs), min(ys), max(xs), max(ys), survey))
    if len(tiles) > LIDAR_TILE_CELLS_ABOVE:
        cells = {}
        for west, south, east, north, survey in tiles:
            cell = cells.setdefault(_cell_of((south + north) / 2, (west + east) / 2, LIDAR_CELL_DEG),
                                    {"tiles": 0, "surveys": set()})
            cell["tiles"] += 1
            cell["surveys"].add(survey)
        features = [_cell_feature(key, LIDAR_CELL_DEG, {"tiles": c["tiles"], "surveys": sorted(c["surveys"]),
                                                         "year": max((surveys[s]["year"] or 0) for s in c["surveys"])})
                    for key, c in cells.items()]
    else:
        features = [{"type": "Feature", "geometry": _box(w, s, e, n),
                     "properties": {"survey": survey, "year": surveys[survey]["year"]}}
                    for w, s, e, n, survey in tiles]
    return {"available": True, "tiles": len(tiles), "surveys": sorted(surveys.values(), key=lambda s: s["name"]),
            "features": features, "aggregated": len(tiles) > LIDAR_TILE_CELLS_ABOVE}


def downloaded_lidar(environ=os.environ) -> dict:
    collection = (environ.get("LIDAR_COLLECTION") or "").strip()
    if not collection:
        return {"available": False, "reason": "LIDAR_COLLECTION is not set on the API.", "features": []}
    from lidar.collection import INDEX_FILENAME

    index_path = Path(collection) / INDEX_FILENAME
    try:
        stamp = index_path.stat().st_mtime_ns
    except OSError:
        return {"available": False, "reason": f"No {INDEX_FILENAME} in LIDAR_COLLECTION here.", "features": []}
    return _remember("lidar", lambda: _lidar_walk(index_path), stamp=(str(index_path), stamp))


# -- where USGS publishes LiDAR on AWS (where streaming has data) ----------------------------------------------

def _rounded(geometry):
    def ring(points):
        out = []
        for point in points:
            rounded = [round(point[0], 3), round(point[1], 3)]
            if not out or out[-1] != rounded:
                out.append(rounded)
        return out if len(out) >= 4 else None

    if not geometry:
        return None
    if geometry.get("type") == "Polygon":
        rings = [r for r in (ring(p) for p in geometry.get("coordinates") or []) if r]
        return {"type": "Polygon", "coordinates": rings} if rings else None
    if geometry.get("type") == "MultiPolygon":
        polygons = [[r for r in (ring(p) for p in polygon) if r] for polygon in geometry.get("coordinates") or []]
        polygons = [p for p in polygons if p]
        return {"type": "MultiPolygon", "coordinates": polygons} if polygons else None
    return None


def _aws_walk() -> dict:
    from lidar import coverage, stream

    root = stream.cache_dir()
    path = root / coverage.CACHE_FILENAME if root else coverage.cache_path()
    try:
        index = coverage.load_index(path, download=False)
    except (coverage.CoverageError, OSError, ValueError):
        return {"type": "FeatureCollection", "features": [], "available": False}
    features = []
    for survey, geometry in index:
        shape = _rounded(geometry)
        if shape:
            features.append({"type": "Feature", "geometry": shape,
                             "properties": {"name": survey.name, "year": survey.year, "points": survey.points}})
    return {"type": "FeatureCollection", "features": features, "available": True}


def aws_coverage() -> dict:
    return _remember("aws", _aws_walk)


def generations(*, fresh=False) -> list:
    """The 3D terrain cache's generations and their sizes (``terrain_tiles.generations``, a walk of the whole cache), kept."""
    import terrain_tiles

    return _remember("generations", terrain_tiles.generations, fresh=fresh)
