"""Builders for synthetic LAS tiles, shared by the LiDAR tests."""

import struct
from pathlib import Path


def las_header(*, bounds, points=1000, version=(1, 2), geokeys=None,
               ascii_params=b"", wkt=None):
    """A LAS public header block, optionally followed by CRS VLRs."""
    minx, miny, maxx, maxy = bounds
    header_size = 227 if version < (1, 4) else 375

    head = bytearray(header_size)
    head[0:4] = b"LASF"
    head[24] = version[0]
    head[25] = version[1]
    struct.pack_into("<H", head, 94, header_size)
    struct.pack_into("<I", head, 107, points if version < (1, 4) else 0)
    # maxx, minx, maxy, miny, maxz, minz
    struct.pack_into("<6d", head, 179, maxx, minx, maxy, miny, 400.0, 300.0)
    if version >= (1, 4):
        struct.pack_into("<Q", head, 247, points)

    vlrs = bytearray()
    count = 0

    def add_vlr(record_id, payload):
        nonlocal count
        record = bytearray(54)
        record[2:18] = b"LASF_Projection".ljust(16, b"\x00")
        struct.pack_into("<H", record, 18, record_id)
        struct.pack_into("<H", record, 20, len(payload))
        vlrs.extend(record)
        vlrs.extend(payload)
        count += 1

    if geokeys:
        body = bytearray(struct.pack("<4H", 1, 1, 0, len(geokeys)))
        for key_id, (location, size, value) in sorted(geokeys.items()):
            body.extend(struct.pack("<4H", key_id, location, size, value))
        add_vlr(34735, bytes(body))
    if ascii_params:
        add_vlr(34737, ascii_params)
    if wkt is not None:
        add_vlr(2112, wkt.encode("latin-1") + b"\x00")

    struct.pack_into("<I", head, 100, count)
    return bytes(head) + bytes(vlrs)


def write_tile(directory, name, **kwargs):
    path = Path(directory) / name
    path.write_bytes(las_header(**kwargs))
    return path
