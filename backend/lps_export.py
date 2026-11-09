"""Writing an AMPS ``.LPS`` local points file.

An ``.LPS`` is a SQLite database with one ``Points`` table, each point's place a
SpatiaLite POINT blob. The export copies ``lps_template.lps`` and inserts a row per
point, as ``ths_export`` does for threats.

The template is the table alone, made from the schema the readers are held to
(``contracts/scripts/sqlite_fixtures.py``), because no real AMPS file was to hand
when it was written. A real file with its data removed should replace it, as
``threat_template.ths`` is: that it opens in AMPS has not been tried.

Standard library only, so it can be checked without Flask.
"""

import math
import os
import shutil
import sqlite3
import struct
import tempfile

TEMPLATE_PATH = os.path.join(os.path.dirname(__file__), 'lps_template.lps')

MAX_POINTS = 10000


class LpsExportError(ValueError):
    """What was asked for cannot be written, in words a person can be shown."""


def spatialite_point(lon, lat):
    """A SpatiaLite POINT blob, little-endian, WGS84: MBR first, then the point."""
    return (
        b'\x00\x01'
        + struct.pack('<i', 4326)
        + struct.pack('<4d', lon, lat, lon, lat)
        + b'\x7c'
        + struct.pack('<I', 1)
        + struct.pack('<2d', lon, lat)
        + b'\xfe'
    )


def _number(value):
    # bool is an int in Python, and a place made of True is a place nobody chose.
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        number = float(value)
    except OverflowError:
        return None
    return number if math.isfinite(number) else None


# Slicing a str counts characters, so an emoji at the limit is never split.
def _text(value, limit):
    return value.strip()[:limit] if isinstance(value, str) else ''


def _row(point, index):
    where = f'Point {index}'
    if not isinstance(point, dict):
        raise LpsExportError(f'{where} is not a point.')
    lat = _number(point.get('lat'))
    lon = _number(point.get('lon'))
    if lat is None or lon is None or abs(lat) > 90 or abs(lon) > 180:
        raise LpsExportError(f'{where} has no valid position.')
    return (
        _text(point.get('name'), 50),
        _text(point.get('description'), 255),
        _text(point.get('group'), 50) or 'Default',
        _text(point.get('icon'), 50),
        # No elevation stays NULL: a 0 would say the point is at sea level.
        _number(point.get('elevationFt')),
        spatialite_point(lon, lat),
    )


def build_lps_bytes(points):
    """An ``.LPS`` holding ``points``, each in the shape a saved point set holds them."""
    if not isinstance(points, list) or not points:
        raise LpsExportError('No points to export.')
    if len(points) > MAX_POINTS:
        raise LpsExportError(f'At most {MAX_POINTS:,} points can be exported at once.')
    rows = [_row(point, index) for index, point in enumerate(points, start=1)]

    # A directory of its own, so the database's journal goes with it whatever happens.
    with tempfile.TemporaryDirectory(prefix='lps-export-') as folder:
        path = os.path.join(folder, 'points.lps')
        shutil.copy(TEMPLATE_PATH, path)
        connection = sqlite3.connect(path)
        try:
            connection.execute('DELETE FROM Points')
            connection.executemany(
                'INSERT INTO Points (ID, Description, GroupName, IconName, Elevation, Coordinate) VALUES (?,?,?,?,?,?)',
                rows,
            )
            connection.commit()
        finally:
            connection.close()
        with open(path, 'rb') as file:
            return file.read()
