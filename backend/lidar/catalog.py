"""Where generated tilesets live, and how a target maps to one.

Tilesets are built ahead of time and served as static files. This module owns
the naming, so the builder and the API agree without either importing the
other's machinery.

Two properties matter:

**Keys are opaque.** A URL like ``/api/lidar/tilesets/34.5916_-84.1282`` tells
anyone who sees it where a crew is planning to land. The key is a hash instead,
so the address reveals nothing about the place.

**Keys are stable.** The same target must resolve to the same tileset every
time, or every rebuild orphans the last one. Coordinates are rounded before
hashing, which buckets targets into a grid — two points in the same cell share
a tileset, though a small move across a cell boundary still produces a new key.
That costs a rebuild, not correctness: the area of interest extends well beyond
the cell, so the existing tileset still covers a target that shifts slightly.
"""

from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path

# ~11 m cells at this latitude: distinct landing points get distinct tilesets,
# while repeated requests for the same point resolve to one.
COORDINATE_PRECISION = 4

KEY_PATTERN = re.compile(r"^[0-9a-f]{16}$")

DEFAULT_TILES_DIR = "/data/tiles"


def tiles_dir() -> Path:
    """Root of the generated tileset store."""
    return Path(os.environ.get("LIDAR_TILES_DIR", DEFAULT_TILES_DIR))


def key_for(lat: float, lon: float, *, radius_m: float) -> str:
    """Opaque, stable identifier for the tileset covering a target."""
    seed = (
        f"{round(float(lat), COORDINATE_PRECISION)}:"
        f"{round(float(lon), COORDINATE_PRECISION)}:"
        f"{round(float(radius_m))}"
    )
    return hashlib.sha256(seed.encode()).hexdigest()[:16]


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
