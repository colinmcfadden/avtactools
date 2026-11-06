"""Where data lives on the server, and whether there is room to write there.

Coolify once went down when the VM's root disk filled. Two rules hold for every write the Data tab makes:

- **The mount check.** A destination on the same device as the container's own ``/`` is refused: that is what a missing
  Coolify mount looks like (the folder exists in the container's overlay, which lives on the VM's root disk). It does not
  see a host folder that is itself on the root disk, so the panel also shows each place's mount (``/proc/self/mountinfo``).
- **The reserve.** Nothing is written that would leave less than ``reserve_bytes`` free, checked before a file and again
  while it comes down.
"""

from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from pathlib import Path

import data_admin
from security_config import is_production

GB = 1_000_000_000
DEFAULT_RESERVE_GB = 20
RESERVE_FRACTION = 0.10


class NoRoom(RuntimeError):
    """A write would break one of the rules; the message says which, in words."""


@dataclass(frozen=True)
class Place:
    label: str          # what the panel calls it
    setting: str        # the variable that names it
    path: str


def places(environ=os.environ) -> list[Place]:
    """Every place the server keeps data, as its settings name them (only those that are set)."""
    found = []
    for root in (environ.get("TERRAIN_DATA_DIR") or "").split(os.pathsep):
        if root.strip():
            found.append(Place("DEMs", "TERRAIN_DATA_DIR", root.strip()))
    for label, setting, default in (
        ("Downloaded LiDAR", "LIDAR_COLLECTION", None),
        ("Streamed LiDAR cache", "LIDAR_STREAM_CACHE_DIR", None),
        ("Built tilesets", "LIDAR_TILES_DIR", "/data/tiles"),
        ("3D terrain tile cache", "TERRAIN_TILE_CACHE_DIR", None),
    ):
        value = (environ.get(setting) or default or "").strip()
        if value and value.lower() not in ("none", "off"):
            found.append(Place(label, setting, value))
    return found


def _existing(path) -> Path | None:
    """The path, or its nearest folder that exists (a destination may not be made yet)."""
    current = Path(path)
    while True:
        if current.exists():
            return current
        if current.parent == current:
            return None
        current = current.parent


def on_container_disk(path) -> bool:
    """Whether a path is on the same device as ``/``: a folder with no volume mounted over it."""
    existing = _existing(path)
    if existing is None:
        return True
    return os.stat(existing).st_dev == os.stat("/").st_dev


def same_disk_allowed(environ=os.environ) -> bool:
    return (environ.get("DATA_ADMIN_SAME_DISK") or "").strip().lower() == "allow" and not is_production(environ)


def reserve_bytes(total: int, environ=os.environ) -> int:
    raw = (environ.get("DATA_ADMIN_RESERVE_GB") or "").strip()
    if raw:
        try:
            return max(0, int(float(raw) * GB))
        except ValueError:
            pass
    return max(DEFAULT_RESERVE_GB * GB, int(total * RESERVE_FRACTION))


def mount_of(path, mountinfo="/proc/self/mountinfo") -> dict | None:
    """The mount a path is on, as Linux describes it (its mount point, file system and source), or None off Linux."""
    try:
        lines = Path(mountinfo).read_text().splitlines()
    except OSError:
        return None
    existing = _existing(path)
    if existing is None:
        return None
    target = os.path.realpath(existing)
    best = None
    for line in lines:
        left, _, right = line.partition(" - ")
        fields, tail = left.split(), right.split()
        if len(fields) < 5 or len(tail) < 2:
            continue
        point = fields[4].replace("\\040", " ")
        inside = target == point or target.startswith(point.rstrip("/") + "/")
        if inside and (best is None or len(point) > len(best["mount_point"])):
            best = {"mount_point": point, "device": fields[2], "fs": tail[0], "source": tail[1]}
    return best


def usage(path, environ=os.environ) -> dict:
    """Total, used and free space where ``path`` is, the reserve kept there, and whether it is a mounted volume."""
    existing = _existing(path)
    info = {"path": str(path), "exists": Path(path).exists(), "on_container_disk": on_container_disk(path),
            "mount": mount_of(path), "writable": bool(existing) and os.access(existing, os.W_OK)}
    if existing is None or not info["exists"]:
        # A folder that is not there has no space of its own to show: its parent's would mislead.
        return {**info, "total": None, "used": None, "free": None, "reserve": None}
    disk = shutil.disk_usage(existing)
    return {**info, "total": disk.total, "used": disk.used, "free": disk.free,
            "reserve": reserve_bytes(disk.total, environ), "stream_headroom": stream_headroom(path, environ)}


def stream_headroom(path, environ=os.environ) -> int:
    """How much the streamed LiDAR cache may still grow on the same disk as ``path``: its cap less what it holds (as walked for the
    map, kept 10 minutes). The cache prunes only by its own cap and never looks at what is free, so a
    download must leave it that room, or the two would fill the volume between them."""
    from lidar import stream

    root = stream.cache_dir()
    here, there = _existing(path), _existing(root) if root else None
    if here is None or there is None or os.stat(here).st_dev != os.stat(there).st_dev:
        return 0
    from . import layers

    held = layers.stream_cache().get("bytes") or 0
    return max(0, int(stream._cap_bytes()) - held)


def check_room(path, adding: int, environ=os.environ, *, label: str = "That folder") -> None:
    """Refuses, in words, a write of ``adding`` more bytes under ``path`` that breaks either rule. The words name the
    folder by ``label``, never by the server's own path."""
    if not data_admin.can_write(environ):
        raise NoRoom("Writes are off on this server (DATA_ADMIN is not write).")
    if on_container_disk(path) and not same_disk_allowed(environ):
        raise NoRoom(f"{label} is not a mounted volume here: writing would fill the server's own disk.")
    existing = _existing(path)
    if existing is None:
        raise NoRoom(f"{label} does not exist.")
    if not os.access(existing, os.W_OK):
        raise NoRoom(f"{label} is mounted read-only here.")
    disk = shutil.disk_usage(existing)
    reserve = reserve_bytes(disk.total, environ)
    # A side file or a look before starting adds nothing; only what comes down must leave the streamed cache its room.
    headroom = stream_headroom(path, environ) if adding > 0 else 0
    if disk.free - max(0, adding) < reserve + headroom:
        kept = f"{_gb(reserve)} is kept free"
        if headroom:
            kept += (f", and {_gb(headroom)} for the streamed LiDAR cache to grow into on the same disk "
                     "(LIDAR_STREAM_CACHE_MAX_GB)")
        raise NoRoom(f"Not enough room in {label.lower()}: {_gb(disk.free)} free, this needs {_gb(adding)}, and {kept}.")


def _gb(value: int) -> str:
    return f"{value / GB:.1f} GB"
