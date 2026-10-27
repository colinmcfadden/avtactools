"""What stands on and round an LZ, from the USGS LiDAR (docs/LZ_SURVEYS.md §4, step L4).

The survey the 3D view streams for the LZ's place (``lidar.stream``: the newest covering it) is read over just what is measured: the LZ
and 100 m round it, a corridor 30 m each side of the best approach and of the best exit out to 1,000 m past the LZ's edge (§11 R5), and
the slope profile's line through the LZ. Every piece of USGS's octree that meets them is read, at every level (a piece holds points
between its parent's), each fetched from AWS once and kept beside the streamed pieces as USGS publishes it (``Streamer.laz``): the
streamed ``.pnts`` keep neither a point's class nor its height above the ground, which is what is wanted here.

Every point that is not ground, noise or water and has another within 3 m (a lone return is a bird or a glint, not something standing)
is measured above the ground the survey's own ground returns make, between them a triangle at a time, and from those:

- the clearance along the approach and the exit: the highest thing in each 10 m of the corridor (the ground included, where it rises),
  and the angle from where the line leaves the LZ that clears the steepest of them with no margin (a margin is doctrine's, not EZ/PZ's:
  AGENTS.md §13), with that obstacle's height above the ground, its distance and what it is; and the tallest in the corridor. Each point
  is measured from the edge's ground at its own place across the corridor, as if flown down its own line: ground that only rises to one
  side of the track (a hillside the corridor runs along) needs no angle, where measured from the track's own edge a metre past it
  rose half a metre and asked for 30°;
- the tallest point inside the boundary, and within 100 m round it, with its bearing and distance from the touchdown point;
- the points standing more than 2 m inside the boundary, counted, and the 2 m cells they stand in, to draw;
- the trees inside the boundary, one by one: the canopy's highest point within 3 m round, standing 5 m or more;
- the tallest point in each 2 m cell round the LZ, as a picture for the editor's map;
- the tallest thing standing in each step of the slope profile's line along the best approach, above its own ground, for the
  profile's treetop line: laid on the ground the profile draws, so a DEM a little off the LiDAR's ground moves no tree.

Nothing is made up where the survey has nothing (AGENTS.md §14): a stretch with no points has no top, and a survey from before about
2015, which classifies only the ground, gives no obstacle numbers at all, only why. Every number names its survey and year: trees grow
and fall, wires and poles are seldom in the survey, and the laser seldom hits a treetop's very top.
"""

from __future__ import annotations

import base64
import concurrent.futures
import io
import logging
import math

import numpy as np

import terrain_provider as tp
from lidar import ept

log = logging.getLogger(__name__)

FEET_PER_METRE = tp.FEET_PER_METRE
METRES_PER_DEGREE = tp.METRES_PER_DEGREE

# §11 R5: the corridor along the best approach and the best exit, from the LZ's edge.
CORRIDOR_HALF_WIDTH_M = 30.0
CORRIDOR_LENGTH_M = 1000.0
BIN_M = 10.0
# §4: round the LZ.
AROUND_M = 100.0
STANDING_M = 2.0
CELL_M = 2.0
TREE_MIN_M = 5.0
TREE_RADIUS_M = 3.0
# A point no other is within this far of (in 3D) is a stray return (a bird, a glint), not something standing.
ALONE_M = 3.0
# Ground returns are read this far past what is measured, so the ground under its edges is between returns, not guessed.
GROUND_MARGIN_M = 20.0
# The ground under a point is taken from the returns round it; one with none within this far has no ground known.
GROUND_REACH_M = 15.0
# What is not something standing: ground, noise (low and high), water, ground a survey marks as ignored.
NOT_STANDING = (2, 7, 9, 18, 20)
GROUND = 2
KINDS = {1: "unclassified", 3: "vegetation", 4: "vegetation", 5: "vegetation", 6: "building", 13: "wire", 14: "wire",
         15: "tower", 16: "wire", 17: "bridge"}
# Past this many pieces an LZ's reading is not what was asked for (a survey indexed far finer than 3DEP's).
MAX_PIECES = 600
FETCH_THREADS = 8
MAX_CELLS = 4000
MAX_TREES = 200
# The tree-height picture's colours, by the height above the ground in metres: nothing under the first, then four bands.
HEIGHT_BANDS = (
    (1.0, (255, 236, 160, 150)),
    (5.0, (255, 170, 60, 175)),
    (15.0, (232, 74, 40, 195)),
    (30.0, (150, 20, 92, 215)),
)

# The words of a measurement that could not be made, by why.
NO_SERVER = "This server does not read the USGS LiDAR, so nothing standing is measured."
NO_SURVEY = "No USGS LiDAR covers this LZ, so nothing standing is measured."
NO_GROUND = "The LiDAR here has no ground returns round the LZ, so nothing standing is measured."
FAILED = "The USGS LiDAR could not be read: measure the LZ again later."
TOO_MUCH = "The LiDAR here is indexed too finely to read for one LZ."


def _ground_only(survey):
    year = f" ({survey.year})" if survey.year else ""
    return (f"The LiDAR here, {survey.name}{year}, classifies only the ground (a survey from before about 2015), so no obstacle numbers "
            "are given from it.")


def default_source():
    """The process's streamer (``lidar.stream``), or None where the server streams nothing."""
    from lidar import stream

    return stream.streamer()


# -- The ground's frame -----------------------------------------------------------------------------------------------

class Frame:
    """Metres east and north of the touchdown point, as the terrain provider's own frame measures them (its profiles, its boundary)."""

    def __init__(self, lat, lon):
        self.lat, self.lon = lat, lon
        self.east_per_degree = METRES_PER_DEGREE * math.cos(math.radians(lat))

    def local(self, lats, lons):
        return (np.asarray(lons, dtype=np.float64) - self.lon) * self.east_per_degree, \
               (np.asarray(lats, dtype=np.float64) - self.lat) * METRES_PER_DEGREE

    def latlon(self, east, north):
        return self.lat + north / METRES_PER_DEGREE, self.lon + east / self.east_per_degree

    def from_mercator(self, x, y):
        lon = np.degrees(np.asarray(x, dtype=np.float64) / ept.MERCATOR_RADIUS)
        lat = np.degrees(2.0 * np.arctan(np.exp(np.asarray(y, dtype=np.float64) / ept.MERCATOR_RADIUS)) - np.pi / 2.0)
        return self.local(lat, lon)

    def to_mercator(self, east, north):
        lat, lon = self.latlon(east, north)
        return ept.lonlat_to_mercator(lon, lat)


def _unit(bearing_deg):
    radians = math.radians(bearing_deg)
    return np.array([math.sin(radians), math.cos(radians)])


class Strip:
    """A rectangle in the frame: from ``start`` along ``bearing`` for ``length`` metres, ``half_width`` each side."""

    def __init__(self, origin, bearing_deg, start, length, half_width):
        self.origin = np.asarray(origin, dtype=np.float64)
        self.along = _unit(bearing_deg)
        self.across = np.array([self.along[1], -self.along[0]])   # to the right, looking along it
        self.start, self.length, self.half_width = start, length, half_width

    def corners(self, margin=0.0):
        a0, a1 = self.start - margin, self.start + self.length + margin
        w = self.half_width + margin
        return np.array([self.origin + self.along * a + self.across * c for a, c in ((a0, -w), (a1, -w), (a1, w), (a0, w))])

    def holds(self, east, north, margin=0.0):
        a = (east - self.origin[0]) * self.along[0] + (north - self.origin[1]) * self.along[1]
        c = (east - self.origin[0]) * self.across[0] + (north - self.origin[1]) * self.across[1]
        return (a >= self.start - margin) & (a <= self.start + self.length + margin) & (np.abs(c) <= self.half_width + margin)


def _box_strip(ring, pad):
    """The box round ``ring`` (in the frame) and ``pad`` metres more, as a strip running north."""
    east_lo, north_lo = ring.min(axis=0) - pad
    east_hi, north_hi = ring.max(axis=0) + pad
    return Strip(((east_lo + east_hi) / 2.0, north_lo), 0.0, 0.0, north_hi - north_lo, (east_hi - east_lo) / 2.0)


# -- Polygons ---------------------------------------------------------------------------------------------------------

def inside(ring, east, north):
    """Which points are inside the polygon ``ring`` (n, 2), by crossings (a point on an edge may fall either way)."""
    east, north = np.asarray(east, dtype=np.float64), np.asarray(north, dtype=np.float64)
    result = np.zeros(east.shape, dtype=bool)
    count = len(ring)
    for i in range(count):
        (x1, y1), (x2, y2) = ring[i], ring[(i + 1) % count]
        if y1 == y2:
            continue
        crosses = (north >= min(y1, y2)) & (north < max(y1, y2))
        at = x1 + (north - y1) * (x2 - x1) / (y2 - y1)
        result ^= crosses & (east < at)
    return result


def distance_to_edge(ring, east, north):
    """Each point's distance to the polygon's nearest edge, in metres."""
    east, north = np.asarray(east, dtype=np.float64), np.asarray(north, dtype=np.float64)
    best = np.full(east.shape, np.inf)
    count = len(ring)
    for i in range(count):
        (x1, y1), (x2, y2) = ring[i], ring[(i + 1) % count]
        dx, dy = x2 - x1, y2 - y1
        length2 = dx * dx + dy * dy
        t = np.zeros(east.shape) if length2 == 0 else np.clip(((east - x1) * dx + (north - y1) * dy) / length2, 0.0, 1.0)
        best = np.minimum(best, np.hypot(east - (x1 + t * dx), north - (y1 + t * dy)))
    return best


def leaves(ring, direction):
    """How far from the touchdown point (the frame's origin) a line along ``direction`` (a unit vector) leaves the polygon for the last
    time: the LZ's edge on that side. 0 where it never crosses (a touchdown point outside its boundary)."""
    furthest = 0.0
    dx, dy = direction
    count = len(ring)
    for i in range(count):
        a, b = ring[i], ring[(i + 1) % count]
        ex, ey = b[0] - a[0], b[1] - a[1]
        det = -dx * ey + dy * ex
        if abs(det) < 1e-12:
            continue
        t = (-a[0] * ey + a[1] * ex) / det
        s = (dx * a[1] - dy * a[0]) / det
        if t >= 0 and 0 <= s <= 1:
            furthest = max(furthest, t)
    return furthest


def _meets(corners, box):
    """Whether a convex polygon (its corners) meets an axis-aligned box (xmin, ymin, xmax, ymax), by separating axes."""
    xmin, ymin, xmax, ymax = box
    if corners[:, 0].max() < xmin or corners[:, 0].min() > xmax or corners[:, 1].max() < ymin or corners[:, 1].min() > ymax:
        return False
    square = np.array([[xmin, ymin], [xmax, ymin], [xmax, ymax], [xmin, ymax]])
    for i in range(len(corners)):
        edge = corners[(i + 1) % len(corners)] - corners[i]
        axis = np.array([-edge[1], edge[0]])
        a, b = corners @ axis, square @ axis
        if a.max() < b.min() or b.max() < a.min():
            return False
    return True


# -- The octree -------------------------------------------------------------------------------------------------------

def nodes_over(info, hierarchy, shapes):
    """Every piece of the octree with points whose column meets one of ``shapes`` (convex polygons in the survey's Mercator metres),
    from the root down, following the index into its pages. Raises ValueError past ``MAX_PIECES``."""
    found = []
    frontier = [(ept.ROOT, ept.ROOT)]
    while frontier:
        node, page = frontier.pop()
        count = hierarchy.count(node, page_root=page)
        if count == 0:
            continue
        if count < 0:
            page = node
            if hierarchy.count(node, page_root=page) == 0:
                continue
        xmin, ymin, _zmin, xmax, ymax, _zmax = info.cube(node)
        if not any(_meets(shape, (xmin, ymin, xmax, ymax)) for shape in shapes):
            continue
        found.append(node)
        if len(found) > MAX_PIECES:
            raise ValueError("too many pieces")
        if node.depth < ept.MAX_DEPTH:
            frontier.extend((child, page) for child in node.children())
    return found


def _read(data):
    from lidar import pieces

    x, y, z, classification, _intensity = pieces.read_laz(data)
    return x, y, z, classification


class Points:
    """The survey's points over what is measured, in the frame: east, north, height (metres, the survey's own vertical datum, NAVD88 for
    3DEP) and class; and, for those standing, the height above the ground."""

    def __init__(self, east, north, z, classification):
        self.east, self.north, self.z, self.cls = east, north, z, classification
        self.is_ground = classification == GROUND
        self.standing = ~np.isin(classification, NOT_STANDING)
        self.ground_z = None
        self.above = np.full(z.shape, np.nan)

    @classmethod
    def read(cls, frame, pieces, shapes):
        """The points of ``pieces`` (LAZ bytes) that fall in one of ``shapes`` (strips), every piece read and let go of in turn."""
        parts = []
        for data in pieces:
            x, y, z, classification = _read(data)
            east, north = frame.from_mercator(x, y)
            keep = np.zeros(east.shape, dtype=bool)
            for shape in shapes:
                keep |= shape.holds(east, north, GROUND_MARGIN_M)
            parts.append((east[keep], north[keep], z[keep], classification[keep]))
        if not parts:
            return cls(np.zeros(0), np.zeros(0), np.zeros(0), np.zeros(0, dtype=np.uint8))
        return cls(*(np.concatenate([part[i] for part in parts]) for i in range(4)))

    def measure_ground(self):
        """Every standing point's height above the ground, and the ground's height anywhere (``ground_at``). False with no ground."""
        from scipy.interpolate import LinearNDInterpolator
        from scipy.spatial import cKDTree

        if self.is_ground.sum() < 3:
            return False
        ge, gn, gz = self.east[self.is_ground], self.north[self.is_ground], self.z[self.is_ground]
        # The lowest return in each metre: a ground class that took a low branch or a bush does not lift the ground.
        cell = np.floor(ge).astype(np.int64) * 1_000_003 + np.floor(gn).astype(np.int64)
        order = np.lexsort((gz, cell))
        first = np.ones(order.size, dtype=bool)
        first[1:] = cell[order][1:] != cell[order][:-1]
        keep = order[first]
        ge, gn, gz = ge[keep], gn[keep], gz[keep]
        tree = cKDTree(np.column_stack((ge, gn)))
        try:
            between = LinearNDInterpolator(np.column_stack((ge, gn)), gz)
        except Exception:  # noqa: BLE001 - Qhull refuses ground all on one line: the nearest return stands in
            between = None

        def ground_at(east, north):
            east, north = np.atleast_1d(np.asarray(east, dtype=np.float64)), np.atleast_1d(np.asarray(north, dtype=np.float64))
            heights = between(east, north) if between is not None else np.full(east.shape, np.nan)
            distance, nearest = tree.query(np.column_stack((east, north)), k=1)
            heights = np.where(np.isnan(heights), gz[nearest], heights)
            return np.where(distance <= GROUND_REACH_M, heights, np.nan)

        self.ground_at = ground_at
        standing = self.standing
        if standing.any():
            self.above[standing] = self.z[standing] - ground_at(self.east[standing], self.north[standing])
        return True

    def drop_lone(self):
        """A standing point with no other standing point within ``ALONE_M`` is not counted as standing: a bird, a glint."""
        from scipy.spatial import cKDTree

        index = np.flatnonzero(self.standing)
        if index.size < 2:
            self.standing[index] = False
            return
        cloud = np.column_stack((self.east[index], self.north[index], self.z[index]))
        distance, _nearest = cKDTree(cloud).query(cloud, k=2)
        self.standing[index[distance[:, 1] > ALONE_M]] = False


# -- What is measured -------------------------------------------------------------------------------------------------

def _ft(metres):
    return None if metres is None or not math.isfinite(metres) else round(metres * FEET_PER_METRE, 1)


def _bearing(east, north):
    return round(math.degrees(math.atan2(east, north)) % 360.0, 1)


def _kind(code):
    return KINDS.get(int(code), "other")


def _spot(frame, points, i, extra=None):
    """One point as the snapshot keeps it: where it is, its height above the ground and its top (ft MSL), bearing and distance from the
    touchdown point, and what it is."""
    east, north = float(points.east[i]), float(points.north[i])
    lat, lon = frame.latlon(east, north)
    out = {
        "lat": round(lat, 7), "lon": round(lon, 7),
        "heightFt": _ft(float(points.above[i])) if points.standing[i] else 0.0,
        "topFt": _ft(float(points.z[i])),
        "bearingDeg": _bearing(east, north),
        "distanceM": round(math.hypot(east, north), 1),
        "kind": _kind(points.cls[i]) if points.standing[i] else "terrain",
    }
    return {**out, **(extra or {})}


def corridor(frame, points, ring, heading_deg, *, approach):
    """The clearance along one way in or out: ``heading_deg`` is the way the aircraft flies (towards the LZ on the approach, away from it
    on the exit), so the corridor lies behind the LZ for the approach and ahead of it for the exit."""
    bearing = (heading_deg + 180.0) % 360.0 if approach else heading_deg % 360.0
    direction = _unit(bearing)
    right = _unit((heading_deg + 90.0) % 360.0)              # the pilot's right, flying the heading
    edge = leaves(ring, direction)
    strip = Strip((0.0, 0.0), bearing, edge, CORRIDOR_LENGTH_M, CORRIDOR_HALF_WIDTH_M)
    along = points.east * direction[0] + points.north * direction[1]
    offset = points.east * right[0] + points.north * right[1]
    held = strip.holds(points.east, points.north) & (along > edge) & ~inside(ring, points.east, points.north)
    held &= points.is_ground | (points.standing & np.isfinite(points.above))
    edge_point = direction * edge
    edge_ground = points.ground_at(edge_point[0], edge_point[1])[0]
    bins = int(round(CORRIDOR_LENGTH_M / BIN_M))
    index = np.flatnonzero(held)
    which = np.minimum(((along[index] - edge) // BIN_M).astype(int), bins - 1)
    centre = edge + (np.arange(bins) + 0.5) * BIN_M
    ground = points.ground_at(direction[0] * centre, direction[1] * centre)
    rows = []
    for k in range(bins):
        here = index[which == k]
        row = {"groundFt": _ft(float(ground[k])), "topFt": None, "tallestFt": None}
        if here.size:
            row["topFt"] = _ft(float(points.z[here].max()))
            standing = here[points.standing[here]]
            if standing.size:
                row["tallestFt"] = _ft(float(np.nanmax(points.above[standing])))
        rows.append(row)
    result = {
        "headingDeg": round(heading_deg % 360.0, 1),
        "bearingDeg": round(bearing, 1),
        "edge": {**dict(zip(("lat", "lon"), (round(v, 7) for v in frame.latlon(*edge_point)))), "fromTargetM": round(edge, 1),
                 "groundFt": _ft(float(edge_ground))},
        "lengthM": CORRIDOR_LENGTH_M, "halfWidthM": CORRIDOR_HALF_WIDTH_M, "binM": BIN_M,
        "bins": rows,
        "covered": sum(1 for row in rows if row["topFt"] is not None),
        "clearance": None,
        "fromTarget": None,
        "tallest": None,
    }
    if not index.size or not math.isfinite(edge_ground):
        return result

    def steepest(start):
        """The steepest climb, from ``start`` metres along the line at each point's own place across it, to the points: (angle, which).
        None where the ground there is not known."""
        across = offset[index]
        base = points.ground_at(direction[0] * start + right[0] * across, direction[1] * start + right[1] * across)
        known = np.isfinite(base)
        if not known.any():
            return None
        angles = np.where(known, np.arctan2(points.z[index] - base, along[index] - start), -np.inf)
        k = int(np.argmax(angles))
        return round(math.degrees(float(angles[k])), 1), k

    found = steepest(edge)
    if found is not None:
        angle, k = found
        i = int(index[k])
        result["clearance"] = {"angleDeg": angle, "obstacle": _spot(frame, points, i, {"fromEdgeM": round(float(along[i] - edge), 1),
                                                                                       "offsetM": round(float(offset[i]), 1)})}
    # The same from the touchdown point, which an approach is flown to, for the editor (§11 R5 prints the edge's).
    found = steepest(0.0)
    if found is not None:
        angle, k = found
        i = int(index[k])
        result["fromTarget"] = {"angleDeg": angle, "obstacle": _spot(frame, points, i, {"offsetM": round(float(offset[i]), 1)})}
    standing = index[points.standing[index]]
    if standing.size:
        j = int(standing[np.nanargmax(points.above[standing])])
        result["tallest"] = _spot(frame, points, j, {"fromEdgeM": round(float(along[j] - edge), 1), "offsetM": round(float(offset[j]), 1)})
    return result


def _cells(frame, points, chosen):
    """The 2 m cells ``chosen`` points stand in, their centres as [lat, lon], at most ``MAX_CELLS`` (the tallest first)."""
    if not chosen.size:
        return []
    keys = np.stack((np.floor(points.east[chosen] / CELL_M), np.floor(points.north[chosen] / CELL_M)), axis=1).astype(np.int64)
    order = np.argsort(-points.above[chosen])
    seen, out = set(), []
    for k in order:
        key = (int(keys[k, 0]), int(keys[k, 1]))
        if key in seen:
            continue
        seen.add(key)
        lat, lon = frame.latlon((key[0] + 0.5) * CELL_M, (key[1] + 0.5) * CELL_M)
        out.append([round(lat, 7), round(lon, 7)])
        if len(out) >= MAX_CELLS:
            break
    return out


def _trees(frame, points, ring, inner):
    """The trees inside the boundary, one by one: a cell of the canopy's 1 m grid that is the highest within ``TREE_RADIUS_M`` round
    and stands ``TREE_MIN_M`` or more, one to a crown however flat its top."""
    from scipy import ndimage

    if not inner.size:
        return []
    east, north, above = points.east[inner], points.north[inner], points.above[inner]
    e0, n0 = math.floor(east.min()) - 4, math.floor(north.min()) - 4
    width, height = int(math.ceil(east.max() - e0)) + 5, int(math.ceil(north.max() - n0)) + 5
    canopy = np.zeros((height, width))
    np.maximum.at(canopy, ((north - n0).astype(int), (east - e0).astype(int)), above)
    radius = int(TREE_RADIUS_M)
    yy, xx = np.mgrid[-radius:radius + 1, -radius:radius + 1]
    highest = ndimage.maximum_filter(canopy, footprint=(xx * xx + yy * yy) <= radius * radius, mode="constant", cval=0.0)
    peaks = (canopy == highest) & (canopy >= TREE_MIN_M)
    labels, count = ndimage.label(peaks)
    found = []
    for rows, cols in ndimage.find_objects(labels):
        r, c = (rows.start + rows.stop - 1) / 2.0, (cols.start + cols.stop - 1) / 2.0
        ce, cn = e0 + c + 0.5, n0 + r + 0.5
        if not inside(ring, np.array([ce]), np.array([cn]))[0]:
            continue
        lat, lon = frame.latlon(ce, cn)
        found.append({"lat": round(lat, 7), "lon": round(lon, 7), "heightFt": _ft(float(canopy[rows, cols].max()))})
    found.sort(key=lambda tree: -tree["heightFt"])
    return found[:MAX_TREES]


def height_map(frame, points, box):
    """The tallest point above the ground in each 2 m cell of ``box`` (a strip running north), as a PNG the editor's map lays on the
    LZ (``overlay``, ``bounds`` as [[south, west], [north, east]]): clear where nothing stands a metre, then four bands."""
    from PIL import Image

    east_lo = box.origin[0] - box.half_width
    north_lo = box.origin[1]
    width = max(1, int(math.ceil(2 * box.half_width / CELL_M)))
    height = max(1, int(math.ceil(box.length / CELL_M)))
    tallest = np.zeros((height, width))
    chosen = np.flatnonzero(points.standing & np.isfinite(points.above))
    if chosen.size:
        col = ((points.east[chosen] - east_lo) // CELL_M).astype(int)
        row = ((points.north[chosen] - north_lo) // CELL_M).astype(int)
        keep = (col >= 0) & (col < width) & (row >= 0) & (row < height)
        np.maximum.at(tallest, (row[keep], col[keep]), points.above[chosen][keep])
    rgba = np.zeros((height, width, 4), dtype=np.uint8)
    for floor, colour in HEIGHT_BANDS:
        rgba[tallest >= floor] = colour
    image = Image.fromarray(rgba[::-1], "RGBA")                 # the picture's first row is the north
    out = io.BytesIO()
    image.save(out, "PNG", optimize=True)
    south, west = frame.latlon(east_lo, north_lo)
    north_, east_ = frame.latlon(east_lo + width * CELL_M, north_lo + height * CELL_M)
    return {"overlay": "data:image/png;base64," + base64.b64encode(out.getvalue()).decode("ascii"),
            "bounds": [[round(south, 7), round(west, 7)], [round(north_, 7), round(east_, 7)]],
            "cellM": CELL_M, "bands": [floor for floor, _colour in HEIGHT_BANDS]}


def profile_tops(frame, points, profiles, approach_deg):
    """The tallest thing standing within 30 m each side of the slope profile's line along the best approach, at each of its steps, as
    its height above the ground (ft; None where nothing stands), in the order of the line the terrain measured (``lines[i].heading``,
    0..178): the page reads it backwards as it reads the ground, and lays it on the ground it draws."""
    from survey import profile as profile_lib

    p = profile_lib._readable(profiles)
    if p is None or approach_deg is None:
        return None
    step = p["headingStepDeg"]
    nearest = (profile_lib.js_round(approach_deg / step) * step) % 360
    base = nearest - 180 if nearest >= 180 else nearest
    line = next((l for l in p["lines"] if isinstance(l, dict) and l.get("heading") == base), None)
    if line is None or not isinstance(line.get("elevationsFt"), list):
        return None
    centre_e, centre_n = frame.local([p["center"][0]], [p["center"][1]])
    count = len(line["elevationsFt"])
    half = p["stepM"] * (count - 1) / 2.0
    direction = _unit(base)
    right = np.array([direction[1], -direction[0]])
    de, dn = points.east - centre_e[0], points.north - centre_n[0]
    along = de * direction[0] + dn * direction[1]
    across = de * right[0] + dn * right[1]
    held = (np.abs(across) <= CORRIDOR_HALF_WIDTH_M) & points.standing & np.isfinite(points.above)
    which = np.round((along + half) / p["stepM"]).astype(int)
    above = [None] * count
    index = np.flatnonzero(held & (which >= 0) & (which < count))
    if index.size:
        order = np.argsort(which[index])
        steps, starts = np.unique(which[index][order], return_index=True)
        for k, start in enumerate(starts):
            stop = starts[k + 1] if k + 1 < len(starts) else order.size
            above[int(steps[k])] = _ft(float(points.above[index[order[start:stop]]].max()))
    return {"heading": base, "aboveFt": above}


def measure_obstacles(target, boundary, approach_deg, exit_deg, profiles=None, *, source=None):
    """What stands on and round the LZ, as the snapshot keeps it (``obstacles``). Never raises for want of data: ``status`` is
    ``measured``; ``none`` where there is nothing to measure here (no survey, one that classifies only the ground, no ground); ``unavailable``
    where this server reads no LiDAR; ``failed`` where USGS could not be read (measure again), each with its ``words``."""
    source = default_source() if source is None else source
    if source is False or source is None:
        return {"status": "unavailable", "words": NO_SERVER}
    lat, lon = target
    try:
        found = source.survey_for(lat, lon)
    except Exception:  # noqa: BLE001 - the coverage index is fetched once from USGS: a failure is a measure to try again
        log.exception("surveys: the LiDAR's coverage could not be read")
        return {"status": "failed", "words": FAILED}
    if found is None:
        return {"status": "none", "words": NO_SURVEY}
    survey, key = found
    named = {"name": survey.name, "year": survey.year}
    if not survey.likely_classified:
        return {"status": "none", "words": _ground_only(survey), "survey": named}

    frame = Frame(lat, lon)
    ring = np.column_stack(frame.local([p[0] for p in boundary], [p[1] for p in boundary]))
    shapes = [_box_strip(ring, AROUND_M)]
    for heading, approach in ((approach_deg, True), (exit_deg, False)):
        if heading is None:
            continue
        bearing = (heading + 180.0) % 360.0 if approach else heading % 360.0
        shapes.append(Strip((0.0, 0.0), bearing, leaves(ring, _unit(bearing)), CORRIDOR_LENGTH_M, CORRIDOR_HALF_WIDTH_M))
    p = None
    if approach_deg is not None:
        from survey import profile as profile_lib

        p = profile_lib._readable(profiles)
        if p is not None:
            centre = frame.local([p["center"][0]], [p["center"][1]])
            base = profile_lib.js_round(approach_deg / p["headingStepDeg"]) * p["headingStepDeg"] % 180
            shapes.append(Strip((float(centre[0][0]), float(centre[1][0])), base, -p["halfLengthM"], 2 * p["halfLengthM"],
                                CORRIDOR_HALF_WIDTH_M))
    wanted = [np.array([frame.to_mercator(*corner) for corner in shape.corners(GROUND_MARGIN_M)]) for shape in shapes]

    try:
        info = source.info(key)
        nodes = nodes_over(info, source.hierarchy(key), wanted)
    except ValueError:
        return {"status": "none", "words": TOO_MUCH, "survey": named}
    except Exception:  # noqa: BLE001 - USGS's index could not be read: a measure to try again
        log.exception("surveys: the LiDAR's index could not be read for %s", survey.name)
        return {"status": "failed", "words": FAILED, "survey": named}
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=FETCH_THREADS) as pool:
            pieces = list(pool.map(lambda node: source.laz(key, node), nodes))
        points = Points.read(frame, pieces, shapes)
        del pieces
    except Exception:  # noqa: BLE001 - a piece that could not be fetched or read: a measure to try again
        log.exception("surveys: the LiDAR could not be read for %s", survey.name)
        return {"status": "failed", "words": FAILED, "survey": named}

    if not points.measure_ground():
        return {"status": "none", "words": NO_GROUND, "survey": named}
    points.drop_lone()
    measured = points.standing & np.isfinite(points.above)
    in_lz = inside(ring, points.east, points.north)
    inner = np.flatnonzero(measured & in_lz)
    near = np.flatnonzero(measured & ~in_lz)
    near = near[distance_to_edge(ring, points.east[near], points.north[near]) <= AROUND_M]

    def tallest(chosen, extra=None):
        if not chosen.size:
            return None
        i = int(chosen[np.argmax(points.above[chosen])])
        return _spot(frame, points, i, extra(i) if extra else None)

    up = inner[points.above[inner] > STANDING_M]
    return {
        "status": "measured",
        "words": None,
        "survey": named,
        "points": int(points.z.size),
        "pieces": len(nodes),
        "approach": corridor(frame, points, ring, approach_deg, approach=True) if approach_deg is not None else None,
        "exit": corridor(frame, points, ring, exit_deg, approach=False) if exit_deg is not None else None,
        "inside": tallest(inner),
        "around": tallest(near, lambda i: {"fromEdgeM": round(float(distance_to_edge(ring, points.east[i:i + 1], points.north[i:i + 1])[0]), 1)}),
        "standing": {"overM": STANDING_M, "count": int(up.size), "cells": _cells(frame, points, up)},
        "trees": _trees(frame, points, ring, inner),
        "heightMap": height_map(frame, points, shapes[0]),
        "profileTops": profile_tops(frame, points, profiles, approach_deg) if p is not None else None,
    }
