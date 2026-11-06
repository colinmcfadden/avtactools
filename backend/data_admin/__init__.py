"""The admin's Data tab: what terrain and LiDAR the server holds, and DEMs added from USGS.

docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10. Admin only: nothing here is reachable with a JWT, because where the server
holds data (and where its cache has streamed) says where crews plan (AGENTS.md §15).

    DATA_ADMIN              off (default) | read (the map) | write (downloads too: the production API only, since
                            staging mounts the same volumes)
    DATA_ADMIN_RESERVE_GB   free space every write leaves on a volume (default: the larger of 20 GB and 10 %)
    DATA_ADMIN_SAME_DISK    allow: write where the container's own disk is (a local run); production refuses it
"""

import os

MODES = ("off", "read", "write")


def mode(environ=os.environ) -> str:
    value = (environ.get("DATA_ADMIN") or "off").strip().lower()
    return value if value in MODES else "off"


def can_read(environ=os.environ) -> bool:
    return mode(environ) in ("read", "write")


def can_write(environ=os.environ) -> bool:
    return mode(environ) == "write"
