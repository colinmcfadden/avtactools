"""Where generated tilesets live, and how a target maps to one.

Tilesets are built ahead of time and served as static files. This module owns
the naming, so the builder and the API agree without either importing the
other's machinery.

Two properties matter:

**Keys are opaque.** A URL like ``/api/lidar/tilesets/34.5916_-84.1282`` tells
anyone who sees it where a crew is planning to land. The key is a hash instead,
so the address reveals nothing about the place. It is a *keyed* hash (an HMAC
under ``LIDAR_KEY_SECRET``): a plain SHA-256 of a rounded target can be turned
back into its place by hashing every rounded place in a region, which one GPU
does for CONUS in seconds. The API and the build service must share the secret.
Tilesets built before it keep their unkeyed names; they are found by coverage,
not by key, so nothing needs renaming.

**Keys are stable.** The same target must resolve to the same tileset every
time, or every rebuild orphans the last one. Coordinates are rounded before
hashing, which buckets targets into a grid.

Rounding alone is not enough to *find* a tileset, though, and assuming it was
is a mistake this module used to make. Two coordinates a tenth of a metre apart
land in different cells whenever they straddle a boundary — 34.64815 rounds to
34.6482, while the same point arriving via an MGRS round-trip as 34.64814884
rounds to 34.6481. The tileset existed and the lookup reported "not generated".

So a lookup is a coverage test, not a key match: each tileset records the area
it was built for, and a target resolves to any tileset whose area contains it,
nearest centre first. The key remains how a tileset is addressed once found.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
import re
from pathlib import Path

# ~11 m cells at this latitude: distinct landing points get distinct tilesets,
# while repeated requests for the same point resolve to one.
COORDINATE_PRECISION = 4

KEY_PATTERN = re.compile(r"^[0-9a-f]{16}$")

DEFAULT_TILES_DIR = "/data/tiles"

# Written beside tileset.json so the store can be searched by position.
MANIFEST_FILENAME = "area.json"

# A wider, thinned tileset built beside the core, for landscape context.
CONTEXT_DIRNAME = "context"

# A target this close to the edge of a built area has almost no context on one
# side, so it is treated as uncovered and worth its own build.
USABLE_FRACTION = 0.75

METRES_PER_DEGREE_LAT = 111_320.0


def tiles_dir() -> Path:
    """Root of the generated tileset store."""
    return Path(os.environ.get("LIDAR_TILES_DIR", DEFAULT_TILES_DIR))


# A bare local run's secret. Public, so it is refused wherever the process is a
# deployment: keys made with it can be hashed back into places.
DEV_KEY_SECRET = "dev-lidar-key-secret-change-me"

KEY_SECRET_MIN_LENGTH = 32


def key_secret(environ=os.environ) -> str:
    """The secret tileset keys are made with, or RuntimeError on a deployment
    without one.

    The production signals are the API's (``security_config.is_production``),
    repeated here because the build service's image carries only this package,
    plus ``LIDAR_BUILDER_TOKEN``, which every deployed API and build service
    has. Called at startup by both, so a missing secret stops a deploy rather
    than the first build.
    """
    secret = environ.get("LIDAR_KEY_SECRET") or ""
    deployed = (environ.get("FLY_APP_NAME")
                or (environ.get("TRUSTED_PROXY") or "").strip()
                or (environ.get("APP_ENV") or "").strip().lower() == "production"
                or (environ.get("LIDAR_BUILDER_TOKEN") or "").strip())
    if deployed and len(secret) < KEY_SECRET_MIN_LENGTH:
        raise RuntimeError(
            "LIDAR_KEY_SECRET must be configured on a deployment and contain at "
            f"least {KEY_SECRET_MIN_LENGTH} characters (openssl rand -hex 32); "
            "the API and the build service must share it")
    return secret or DEV_KEY_SECRET


def key_for(lat: float, lon: float, *, radius_m: float) -> str:
    """Opaque, stable identifier for the tileset covering a target."""
    seed = (
        f"{round(float(lat), COORDINATE_PRECISION)}:"
        f"{round(float(lon), COORDINATE_PRECISION)}:"
        f"{round(float(radius_m))}"
    )
    return hmac.new(key_secret().encode(), seed.encode(),
                    hashlib.sha256).hexdigest()[:16]


def is_valid_key(key: str) -> bool:
    """Whether a key is well-formed.

    Checked before the value reaches the filesystem: a key arrives from a URL,
    and ``..`` in a path segment is how a static file server becomes a way to
    read the rest of the disk.
    """
    return bool(KEY_PATTERN.match(key or ""))


def path_for(key: str, *, root: Path | None = None) -> Path | None:
    """Directory holding a tileset, or None if the key is malformed."""
    if not is_valid_key(key):
        return None
    return (root or tiles_dir()) / key


def exists(key: str, *, root: Path | None = None) -> bool:
    directory = path_for(key, root=root)
    return bool(directory and (directory / "tileset.json").is_file())


def available(*, root: Path | None = None) -> list[str]:
    """Keys with a complete tileset on disk."""
    directory = root or tiles_dir()
    if not directory.is_dir():
        return []
    return sorted(
        child.name for child in directory.iterdir()
        if child.is_dir() and is_valid_key(child.name)
        and (child / "tileset.json").is_file()
    )


def write_manifest(directory, lat: float, lon: float, *, radius_m: float,
                   survey: str | None = None) -> Path:
    """Record the area a tileset was built for, so it can be found by position."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / MANIFEST_FILENAME
    path.write_text(json.dumps({
        "lat": lat, "lon": lon, "radius_m": radius_m,
        "survey": survey,
    }, indent=2), encoding="utf-8")
    return path


def read_manifest(directory):
    """The area a tileset covers, or None if it predates manifests."""
    path = Path(directory) / MANIFEST_FILENAME
    if not path.is_file():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        return (float(document["lat"]), float(document["lon"]),
                float(document["radius_m"]))
    except (ValueError, KeyError, TypeError, OSError):
        return None


# What a viewer fetches from a tileset; everything else in its folder (the
# manifest, logs a build left) stays on the server.
SERVED_SUFFIXES = (".json", ".pnts", ".b3dm", ".glb", ".subtree")


def is_served_file(path: str) -> bool:
    """Whether a path inside a tileset may be sent to a client.

    Only tile files, never ``area.json`` (the place itself: latitude, longitude
    and radius) and nothing under a dot (a build's ``.staging-*`` folder holds a
    manifest too). Compared without regard to case, because the store may be
    on a filesystem that ignores it.
    """
    parts = (path or "").split("/")
    if not parts or any(not part or part.startswith(".") for part in parts):
        return False
    name = parts[-1]
    return (name.endswith(SERVED_SUFFIXES)
            and name.casefold() != MANIFEST_FILENAME.casefold())


def has_context(key, *, root=None) -> bool:
    """Whether a thinned landscape ring was built beside the core tileset."""
    directory = path_for(key, root=root)
    return bool(directory
                and (directory / CONTEXT_DIRNAME / "tileset.json").is_file())


def offset_m(lat: float, lon: float, other_lat: float, other_lon: float):
    """North/east separation in metres, flat-earth — fine over a few hundred.

    Longitude degrees shrink towards the poles, so the east term is scaled by
    the cosine of the latitude; without it a target would appear closer than
    it is and match a tileset that does not really cover it.
    """
    north = (lat - other_lat) * METRES_PER_DEGREE_LAT
    east = ((lon - other_lon) * METRES_PER_DEGREE_LAT
            * math.cos(math.radians((lat + other_lat) / 2.0)))
    return north, east


def find_covering(lat: float, lon: float, *, root=None):
    """The key of a built tileset covering this target, nearest centre first.

    Returns None when nothing covers it, which is a normal answer — it means
    the target needs a build, not that anything failed.
    """
    candidates = []
    for key in available(root=root):
        area = read_manifest(path_for(key, root=root))
        if area is None:
            continue
        centre_lat, centre_lon, radius = area
        north, east = offset_m(lat, lon, centre_lat, centre_lon)
        reach = radius * USABLE_FRACTION
        # The built area is a square, so each axis is tested on its own.
        if abs(north) <= reach and abs(east) <= reach:
            candidates.append((math.hypot(north, east), key))

    if not candidates:
        return None
    return min(candidates)[1]
