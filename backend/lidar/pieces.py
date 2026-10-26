"""One octree piece from USGS, as a 3D Tiles ``.pnts``.

A piece arrives as LAZ in Web Mercator with NAVD88 heights. It leaves as
quantized earth-centred positions (``POSITION_QUANTIZED``, 6 bytes a point,
plus 3 of colour), with the rules a build keeps (``lidar.pipeline``):

- noise is dropped, and of the rest only the obstruction classes are kept for
  a survey that classifies vegetation (2015 on), the surface classes before;
- each point is coloured as the place looks (``NATURAL_STOPS``): the ground
  tan and brown, what stands on it greens that darken with its height above
  the nearest ground return in the same piece, or by its class where the piece
  holds no ground. Then it is shaded by its LiDAR intensity within the
  survey's range (``intensity_range``), which gives the ground and the crowns
  their texture. The build's bands (``HEIGHT_BANDS``, orange above 15 m) came
  out orange over nearly every crown of north Georgia's hardwoods, which
  stand taller than that (the owner's screenshot at Ellijay, 2026-10-26);
- the geoid separation is read once at the piece's centre: it changes by
  millimetres across a 195 m piece. The caller refuses a separation that says
  the grids are missing, as builds do, rather than put the ground 30 m out.

``laspy``, ``lazrs`` and ``scipy`` are imported only here, so the API's routes
load without them.
"""

from __future__ import annotations

import io
import json
import struct

import numpy as np

from . import pipeline

NOISE_CLASSES = (pipeline.NOISE, 18)

# WGS84, for geodetic -> earth-centred.
_A = 6378137.0
_F = 1.0 / 298.257223563
_E2 = _F * (2.0 - _F)

_QUANT = 65535

# The look of streamed pieces. A new one is a new survey key (lidar.names), so
# no browser or app keeps a piece of the old look under the same URL for its
# week of caching, and the server's cache starts afresh beside the old.
LOOK = 2

# Height above ground (m) to colour, interpolated between: bare ground, dry
# grass, grass and scrub, brush, canopy, tall canopy. Red over green over blue
# for the ground; green over both for anything standing.
NATURAL_STOPS = (
    (0.0, (150, 126, 92)),
    (0.5, (126, 122, 78)),
    (2.0, (98, 118, 62)),
    (5.0, (74, 106, 54)),
    (12.0, (56, 90, 44)),
    (25.0, (40, 72, 34)),
)

# A piece with no ground return has no heights above ground: its classes say
# about how tall it stands.
CLASS_HEIGHTS = {
    pipeline.GROUND: 0.0,
    pipeline.LOW_VEGETATION: 1.0,
    pipeline.MEDIUM_VEGETATION: 4.0,
    pipeline.HIGH_VEGETATION: 15.0,
}

# Intensity shading: the survey's weakest returns at this share of the colour,
# its strongest at the other. Wide enough to show texture, never black.
SHADE_LOW = 0.55
SHADE_HIGH = 1.15

# The middle of a survey's intensities, so a few glints do not wash it out.
INTENSITY_PERCENTILES = (2.0, 98.0)


class PieceError(ValueError):
    """A piece that cannot be read; the message is for the log, not a person."""


def kept_classes(classified: bool) -> tuple:
    return pipeline.OBSTRUCTION_CLASSES if classified else pipeline.SURFACE_CLASSES


def read_laz(data: bytes):
    """x, y, z (doubles), classification and intensity of every point in a
    LAZ piece."""
    import laspy

    try:
        las = laspy.read(io.BytesIO(data))
    except Exception as error:  # noqa: BLE001 — laspy raises many kinds
        raise PieceError(f"unreadable LAZ: {error}") from None
    return (np.asarray(las.x, dtype=np.float64), np.asarray(las.y, dtype=np.float64),
            np.asarray(las.z, dtype=np.float64),
            np.asarray(las.classification, dtype=np.uint8),
            np.asarray(las.intensity, dtype=np.float64))


def heights_above_ground(x, y, z, classification) -> np.ndarray:
    """Each point's height above the piece's nearest ground return, or what its
    class says of it where the piece has none."""
    ground = classification == pipeline.GROUND
    if not ground.any():
        height = np.zeros(x.size)
        for code, metres in CLASS_HEIGHTS.items():
            height[classification == code] = metres
        return height

    from scipy.spatial import cKDTree

    tree = cKDTree(np.column_stack((x[ground], y[ground])))
    _distance, nearest = tree.query(np.column_stack((x, y)), k=1)
    height = z - z[ground][nearest]
    height[ground] = 0.0
    return height


def natural_colours(height) -> np.ndarray:
    """Float RGB by height above ground, between ``NATURAL_STOPS``."""
    at = np.array([stop for stop, _rgb in NATURAL_STOPS])
    rgb = np.array([colour for _stop, colour in NATURAL_STOPS], dtype=np.float64)
    clamped = np.clip(height, at[0], at[-1])
    return np.column_stack([np.interp(clamped, at, rgb[:, channel]) for channel in range(3)])


def intensity_range(intensity) -> tuple | None:
    """The middle of a set of intensities, (low, high); None where there is no
    spread to shade by (a survey that recorded none)."""
    values = np.asarray(intensity, dtype=np.float64)
    values = values[values > 0]
    if values.size < 2:
        return None
    low, high = (float(v) for v in np.percentile(values, INTENSITY_PERCENTILES))
    return (low, high) if high > low else None


def shading(intensity, span) -> np.ndarray:
    """The share of its colour each point keeps, by its intensity within
    ``span``; 1 throughout with no span."""
    if span is None:
        return np.ones(np.asarray(intensity).size)
    low, high = span
    share = np.clip((np.asarray(intensity, dtype=np.float64) - low) / (high - low), 0.0, 1.0)
    return SHADE_LOW + (SHADE_HIGH - SHADE_LOW) * share


def survey_intensity_range(data: bytes) -> tuple | None:
    """A survey's intensity range from one of its pieces (its root: a sample
    across the whole survey), noise left out."""
    _x, _y, _z, classification, intensity = read_laz(data)
    return intensity_range(intensity[~np.isin(classification, NOISE_CLASSES)])


def mercator_to_ecef(x, y, height):
    """EPSG:3857 metres and ellipsoidal heights to WGS84 earth-centred metres."""
    lon = x / _A
    lat = 2.0 * np.arctan(np.exp(y / _A)) - np.pi / 2.0
    sin_lat, cos_lat = np.sin(lat), np.cos(lat)
    normal = _A / np.sqrt(1.0 - _E2 * sin_lat * sin_lat)
    return ((normal + height) * cos_lat * np.cos(lon),
            (normal + height) * cos_lat * np.sin(lon),
            (normal * (1.0 - _E2) + height) * sin_lat)


def _padded(blob: bytes, start: int, fill: bytes) -> bytes:
    """``blob`` padded so that what follows it starts on 8 bytes."""
    return blob + fill * ((8 - (start + len(blob)) % 8) % 8)


def pnts(positions: np.ndarray, colours: np.ndarray) -> bytes:
    """A 3D Tiles 1.0 point cloud of quantized positions and RGB."""
    count = int(positions.shape[0])
    if count:
        low = positions.min(axis=0)
        scale = np.maximum(positions.max(axis=0) - low, 1e-3)
        quantized = np.rint((positions - low) / scale * _QUANT).clip(0, _QUANT).astype("<u2")
    else:
        low, scale = np.zeros(3), np.ones(3)
        quantized = np.zeros((0, 3), dtype="<u2")
    position_bytes = quantized.tobytes()
    rgb_offset = len(position_bytes) + (-len(position_bytes)) % 4
    table = {
        "POINTS_LENGTH": count,
        "QUANTIZED_VOLUME_OFFSET": [float(v) for v in low],
        "QUANTIZED_VOLUME_SCALE": [float(v) for v in scale],
        "POSITION_QUANTIZED": {"byteOffset": 0},
        "RGB": {"byteOffset": rgb_offset},
    }
    header = 28
    table_json = _padded(json.dumps(table, separators=(",", ":")).encode(), header, b" ")
    binary = position_bytes + b"\0" * (rgb_offset - len(position_bytes))
    binary = _padded(binary + colours.astype(np.uint8).tobytes(), 0, b"\0")
    length = header + len(table_json) + len(binary)
    return (b"pnts" + struct.pack("<6I", 1, length, len(table_json), len(binary), 0, 0)
            + table_json + binary)


def convert(data: bytes, *, classified: bool, geoid_m: float,
            survey_range: tuple | None = None) -> tuple:
    """A LAZ piece as ``.pnts`` bytes, and how many points it kept.

    ``survey_range`` is the survey's intensities (``survey_intensity_range``), so pieces
    side by side are shaded alike; without one the piece shades by its own.
    """
    x, y, z, classification, intensity = read_laz(data)
    keep = np.isin(classification, kept_classes(classified)) & ~np.isin(classification, NOISE_CLASSES)
    span = survey_range if survey_range is not None else intensity_range(intensity[keep])
    colours = natural_colours(heights_above_ground(x, y, z, classification)[keep])
    colours *= shading(intensity[keep], span)[:, None]
    ex, ey, ez = mercator_to_ecef(x[keep], y[keep], z[keep] + geoid_m)
    return (pnts(np.column_stack((ex, ey, ez)), np.clip(np.rint(colours), 0, 255).astype(np.uint8)),
            int(keep.sum()))
