"""What stands on and round an LZ, from the USGS LiDAR (docs/LZ_SURVEYS.md §4, step L4).

The survey the 3D view streams for the LZ's place (``lidar.stream``: the newest covering it) is read over just what is measured: the LZ
and 100 m round it, a lane 10 m each side of the best approach from the touchdown point out to 1,000 m (§11 R5; the exit is neither
measured nor shown, the owner's call of 2026-10-27), and the slope profile's line through the LZ. Every piece of USGS's octree that
meets them is read, at every level (a piece holds points between its parent's), each fetched from AWS once and kept beside the streamed
pieces as USGS publishes it (``Streamer.laz``): the streamed ``.pnts`` keep neither a point's class nor its height above the ground,
which is what is wanted here. A reading is bounded: at most ``MAX_POINTS`` returns (a 1 km LZ at 3DEP's density), ``FETCH_DEADLINE_S``
to fetch them, and the first piece that cannot be fetched or read ends it.

Every point that is not ground, noise or water is measured above the ground the survey's own ground returns make (the lowest in each
2 m, between them a triangle at a time), and counts as standing unless it is a lone return, a bird or a glint with no other within 3 m
(what the survey marks as a wire, a tower or a bridge excepted: a conductor's returns are often further apart). An **obstacle** is what
stands 2 m or more above its own ground (grass, a crop or a low bush is none). From them:

- along the approach, from the touchdown point: the shallowest angle that clears everything in the way in the lane with
  no margin (a margin is doctrine's, not EZ/PZ's: AGENTS.md §13), and the thing that sets it, its height, its rise over the touchdown
  point's ground, its distance and what it is. In the way is what stands 5 m or more above its own ground (2 m for what the survey marks
  as built: a building, a wire, a tower or a bridge), and the ground itself where it rises: the median of its returns in each 10 m
  square of the lane, where that is more than 0.3 m over the touchdown point's ground (a ground return scatters by a few centimetres,
  and the steepest of thousands of them a centimetre out once read 86-89° on flat ground). Each is measured from the touchdown point's
  ground on its own line across the lane, so a slope across it takes nothing from the angle. With it: what stands highest in each of
  the lane's 10 m steps, from which the page works out where a descent from the survey's approach height above the highest obstacle
  begins (``descent_point``; the plan itself, at 30° where what stands allows, is survey/flightpath.py's), and how many of the steps
  the survey has returns in;
- the tallest obstacle inside the boundary, and within 100 m round it, with its bearing and distance from the touchdown point;
- the points standing more than 2 m inside the boundary, counted;
- the trees inside the boundary, one by one: the canopy's highest point within 3 m round, standing 5 m or more (a building, a wire, a
  tower or a bridge is no tree);
- the tallest point in each 2 m cell round the LZ, as a picture for the editor's map;
- the tallest thing standing in each step of the slope profile's line along the best approach, above its own ground, for the profile's
  treetop line (within the LZ's span, only what stands in the LZ): laid on the ground the profile draws, so a DEM a little off the
  LiDAR's ground moves no tree.

Nothing is made up where the survey has nothing (AGENTS.md §14): a stretch with no points has no top, and a survey that classifies only
the ground (one from before about 2015, or one whose name gives no year and whose returns hold no vegetation or building) gives no
obstacle numbers at all, only why. Every number names its survey and year: trees grow and fall, wires and poles are seldom in the
survey, and the laser seldom hits a treetop's very top.
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

# §11 R5: the lane along the best approach, from the touchdown point: a UH-60's rotor (16.4 m) and a little each
# side of the track. Tried against the old book's 31 survey profiles (docs/LZ_SURVEYS.md §16), from the touchdown point along this lane
# the LiDAR read a median 7.3° from the field's angle, where the book's grid is clear of the trees; from the LZ's edge across 60 m it read
# 87-90° on every LZ, taking the tree line beside the path as standing in it.
LANE_HALF_WIDTH_M = 10.0
LANE_LENGTH_M = 1000.0
BIN_M = 10.0
# In the lane, what stands this far above its own ground is in the way: a tree, not the brush round an LZ, which the field's surveys
# leave out too (2 m read 9.3° from the field's angle, 5 m 7.3°). What the survey marks as built is in the way from ``STANDING_M``: few
# buildings, wires, towers or bridges stand taller, and a wire seldom shows at all.
LANE_LEAST_M = 5.0
# The approach is flown this far above the highest obstacle under it (the treetops, or the ground where nothing stands) until its
# descent begins, where the survey gives no height of its own (the owner, 2026-10-27: over the treetops, not the ground, which in the old
# book's timber put every approach at or under the canopy).
AHO_FT = 100.0
# The slope profile's treetop line is what stands within this of its line.
PROFILE_HALF_WIDTH_M = 30.0
# §4: round the LZ.
AROUND_M = 100.0
# What stands this far above its own ground is an obstacle: grass, a crop or a low bush is not.
STANDING_M = 2.0
# The ground is an obstacle where a 10 m square of it rises more than this over the LZ's edge: a ground return scatters by a few
# centimetres (3DEP's ground is within 10 cm, RMSE), and the steepest of thousands of them a centimetre past the edge read 86-89° on
# flat ground. A square's ground is the median of its returns, where it has this many.
GROUND_RISE_M = 0.3
GROUND_SQUARE_RETURNS = 5
CELL_M = 2.0
# The ground's own grid: the lowest ground return in each square this wide.
GROUND_CELL_M = 2.0
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
# What a survey marks as a wire, a tower or a bridge stands even alone: a conductor's returns are often more than 3 m apart.
NEVER_ALONE = (13, 14, 15, 16, 17)
# What a survey marks as built (a building, a wire, a tower, a bridge): no tree, whatever its height, and in a lane's way from 2 m.
BUILT = (6, 13, 14, 15, 16, 17)
# A survey whose name gives no year classifies more than the ground where its returns hold one of these.
STANDING_CLASSES = (3, 4, 5, 6)
KINDS = {1: "unclassified", 3: "vegetation", 4: "vegetation", 5: "vegetation", 6: "building", 13: "wire", 14: "wire",
         15: "tower", 16: "wire", 17: "bridge"}
# Past this many pieces an LZ's reading is not what was asked for (a survey indexed far finer than 3DEP's).
MAX_PIECES = 600
# A reading holds at most this many returns: a 1 km LZ and its lanes at 3DEP's density (about 3.5 a square metre), measured in
# about 7 s and 0.7 GB. Unbounded, a 1 km LZ took 1.6 GB of the API's one worker, and a 2 km one (SAM's boundary on a field) twice that.
MAX_POINTS = 6_000_000
FETCH_THREADS = 8
# The reading's pieces are fetched within this: what came is kept, so measuring again goes on from there.
FETCH_DEADLINE_S = 60.0
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
SLOW = "The USGS LiDAR was too slow to read: measure the LZ again (what was read is kept, so it is quicker)."
TOO_MUCH = "The LiDAR here is indexed too finely to read for one LZ."
TOO_LARGE = ("The LZ and 100 m round it hold more of the LiDAR than one measure reads: draw its boundary closer round the landing "
             "area.")
UNREADABLE = "USGS's index of the LiDAR here cannot be read, so nothing standing is measured."
# Why a way has no angle.
MISSING = ("no_returns", "no_target_ground")


def _ground_only(survey):
    if survey.year is None:
        return f"The LiDAR here, {survey.name}, classifies only the ground, so no obstacle numbers are given from it."
    return (f"The LiDAR here, {survey.name} ({survey.year}), classifies only the ground (a survey from before about 2015), so no "
            "obstacle numbers are given from it.")


class TooMany(Exception):
    """More of the octree, or of its returns, than one LZ's reading holds: ``words`` says which."""

    def __init__(self, words):
        super().__init__(words)
        self.words = words


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
    """Which points are inside the polygon ``ring`` (n, 2), by crossings (a point on an edge may fall either way). Only the points in
    the ring's box are looked at: a reading holds millions, and a boundary SAM drew hundreds of corners."""
    east, north = np.asarray(east, dtype=np.float64), np.asarray(north, dtype=np.float64)
    result = np.zeros(east.shape, dtype=bool)
    (east_lo, north_lo), (east_hi, north_hi) = ring.min(axis=0), ring.max(axis=0)
    near = np.flatnonzero((east >= east_lo) & (east <= east_hi) & (north >= north_lo) & (north <= north_hi))
    if not near.size:
        return result
    e, n = east.ravel()[near], north.ravel()[near]
    found = np.zeros(near.shape, dtype=bool)
    count = len(ring)
    for i in range(count):
        (x1, y1), (x2, y2) = ring[i], ring[(i + 1) % count]
        if y1 == y2:
            continue
        crosses = (n >= min(y1, y2)) & (n < max(y1, y2))
        at = x1 + (n - y1) * (x2 - x1) / (y2 - y1)
        found ^= crosses & (e < at)
    result.ravel()[near] = found
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


def _spread(values):
    """The low 16 bits of each value spread to the even bits of a 32-bit one: half a Z-curve's code."""
    v = values.astype(np.uint64) & np.uint64(0xFFFF)
    for shift, mask in ((8, 0x00FF00FF), (4, 0x0F0F0F0F), (2, 0x33333333), (1, 0x55555555)):
        v = (v | (v << np.uint64(shift))) & np.uint64(mask)
    return v


def near_first(east, north, cell=8.0):
    """An order of the points that keeps neighbours together (a Z-curve over 8 m squares). The ground's triangles are found by walking
    from the one found last, and in the order the pieces come in the walk crossed the whole reading for each point: a million found in a
    minute, against a third of a second in this order."""
    i = np.floor((east - east.min()) / cell)
    j = np.floor((north - north.min()) / cell)
    return np.argsort(_spread(i) | (_spread(j) << np.uint64(1)), kind="stable")


# -- The octree -------------------------------------------------------------------------------------------------------

def nodes_over(info, hierarchy, shapes):
    """Every piece of the octree with points whose column meets one of ``shapes`` (convex polygons in the survey's Mercator metres),
    from the root down, following the index into its pages. Raises TooMany past ``MAX_PIECES``."""
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
            raise TooMany(TOO_MUCH)
        if node.depth < ept.MAX_DEPTH:
            frontier.extend((child, page) for child in node.children())
    return found


def _read(data):
    from lidar import pieces

    x, y, z, classification, _intensity = pieces.read_laz(data)
    return x, y, z, classification


def read_points(source, key, nodes, frame, shapes):
    """The points of ``nodes`` that fall in one of ``shapes``, eight pieces fetched at a time and each read as it comes and let go of.
    The first piece that cannot be fetched or read ends the reading (one that cannot be read is let go of from the cache, to be fetched
    again), as do more than ``MAX_POINTS`` returns (TooMany) and ``FETCH_DEADLINE_S`` (TimeoutError): the pieces still waiting are not
    fetched, and those being fetched finish into the cache, so measuring again goes on from there."""
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=FETCH_THREADS)
    try:
        futures = {pool.submit(source.laz, key, node): k for k, node in enumerate(nodes)}
        parts, total = {}, 0
        for future in concurrent.futures.as_completed(futures, timeout=FETCH_DEADLINE_S):
            k = futures[future]
            data = future.result()
            try:
                part = Points.part(frame, data, shapes)
            except Exception:
                forget = getattr(source, "drop_laz", None)
                if forget is not None:
                    forget(key, nodes[k])
                raise
            total += part[0].size
            if total > MAX_POINTS:
                raise TooMany(TOO_LARGE)
            parts[k] = part
        # In the octree's order, not the order the pieces came in: the same reading measures the same.
        return Points.join([parts[k] for k in sorted(parts)])
    finally:
        pool.shutdown(wait=False, cancel_futures=True)


class Points:
    """The survey's points over what is measured, in the frame: east, north, height (metres, the survey's own vertical datum, NAVD88 for
    3DEP) and class; and, for those standing, the height above the ground."""

    def __init__(self, east, north, z, classification):
        self.east, self.north, self.z, self.cls = east, north, z, classification
        self.is_ground = classification == GROUND
        self.standing = ~np.isin(classification, NOT_STANDING)
        self.ground_at = None
        self.above = np.full(z.shape, np.nan)

    @staticmethod
    def part(frame, data, shapes):
        """A piece's points (LAZ bytes) that fall in one of ``shapes`` (strips), as (east, north, z, class)."""
        x, y, z, classification = _read(data)
        east, north = frame.from_mercator(x, y)
        keep = np.zeros(east.shape, dtype=bool)
        for shape in shapes:
            keep |= shape.holds(east, north, GROUND_MARGIN_M)
        return east[keep], north[keep], z[keep], classification[keep]

    @classmethod
    def join(cls, parts):
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
        # The lowest return in each 2 m square: a ground class that took a low branch or a bush does not lift the ground, and the
        # triangles are a quarter of a metre's grid's (a 1 km LZ's ground took 14 s and a gigabyte at a metre).
        cell = (np.floor(ge / GROUND_CELL_M).astype(np.int64) * 1_000_003
                + np.floor(gn / GROUND_CELL_M).astype(np.int64))
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
            order = near_first(east, north) if east.size > 64 else None
            if order is not None:
                east, north = east[order], north[order]
            heights = between(east, north) if between is not None else np.full(east.shape, np.nan)
            distance, nearest = tree.query(np.column_stack((east, north)), k=1, workers=-1)
            heights = np.where(np.isnan(heights), gz[nearest], heights)
            heights = np.where(distance <= GROUND_REACH_M, heights, np.nan)
            if order is None:
                return heights
            out = np.empty_like(heights)
            out[order] = heights
            return out

        self.ground_at = ground_at
        standing = self.standing
        if standing.any():
            self.above[standing] = self.z[standing] - ground_at(self.east[standing], self.north[standing])
        return True

    def drop_lone(self):
        """A standing point with no other standing point within ``ALONE_M`` is not counted as standing (a bird, a glint), unless the
        survey marks it as a wire, a tower or a bridge."""
        from scipy.spatial import cKDTree

        index = np.flatnonzero(self.standing)
        if index.size < 2:
            self.standing[index[~np.isin(self.cls[index], NEVER_ALONE)]] = False
            return
        cloud = np.column_stack((self.east[index], self.north[index], self.z[index]))
        distance, _nearest = cKDTree(cloud).query(cloud, k=2, distance_upper_bound=ALONE_M * 1.001, workers=-1)
        lone = (distance[:, 1] > ALONE_M) & ~np.isin(self.cls[index], NEVER_ALONE)
        self.standing[index[lone]] = False

    def obstacles(self):
        """Which points are obstacles: standing, their ground known, ``STANDING_M`` or more above it."""
        above = np.where(self.standing, self.above, np.nan)
        return np.isfinite(above) & (np.nan_to_num(above, nan=-1.0) >= STANDING_M)


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


def _ground_spot(frame, east, north, z, extra):
    """A square of rising ground as the snapshot keeps an obstacle: its middle, its ground (ft MSL), and that it is the ground."""
    lat, lon = frame.latlon(east, north)
    return {"lat": round(lat, 7), "lon": round(lon, 7), "heightFt": 0.0, "topFt": _ft(z), "bearingDeg": _bearing(east, north),
            "distanceM": round(math.hypot(east, north), 1), "kind": "terrain", **extra}


def _medians(keys, values):
    """Each key's median value (the lower of two middles) and how many it has: (keys, medians, counts)."""
    order = np.lexsort((values, keys))
    keys, values = keys[order], values[order]
    unique, start, count = np.unique(keys, return_index=True, return_counts=True)
    return unique, values[start + (count - 1) // 2], count


def _finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def descent_point(angle_deg, start_ft, tops_ft, ground_ft, height_ft=AHO_FT, bin_m=BIN_M):
    """Where a path at ``angle_deg`` from the touchdown point is ``height_ft`` above the highest obstacle under it for good: the
    outermost place, going out along the track, where it stands no higher than that over what stands there (the treetops, or the ground
    where nothing stands), so that past it the path is more than that above everything. Coming in at that height above the highest
    obstacle (AHO), the approach meets its path there and descends on it. The outermost, not the first going out: a gap in the trees
    nearer in is not where an aircraft over the canopy comes down. ``start_ft`` is the ground at the touchdown point, ``tops_ft`` and
    ``ground_ft`` what stands highest and the track's ground in the middle of each ``bin_m`` step out from it (a lane's ``bins``), in
    feet; a step where nothing is known is passed over. {fromTargetM, topFt, groundFt}: ``topFt`` what stands highest there,
    ``groundFt`` the ground (None where not known). ``fromTargetM`` None, with ``pastM``, where the path is still within that height of
    what stands at the last step known (the descent begins past it). None for a path that does not rise, no height above it, no ground
    at the touchdown point, or nothing known standing past it."""
    if not (_finite(angle_deg) and 0 < angle_deg < 90 and _finite(start_ft) and _finite(height_ft) and height_ft > 0):
        return None
    above_m = height_ft * 0.3048
    slope = math.tan(math.radians(angle_deg))
    steps = [(0.0, start_ft, start_ft)]
    for k, top in enumerate(tops_ft):
        if _finite(top):
            ground = ground_ft[k] if k < len(ground_ft) else None
            steps.append(((k + 0.5) * bin_m, top, ground if _finite(ground) else None))
    if len(steps) == 1:
        return None
    heights = [x * slope - (top - start_ft) / FEET_PER_METRE for x, top, _ground in steps]
    last = max(i for i, height in enumerate(heights) if height <= above_m)      # the touchdown point's own is 0: there is one
    if last == len(steps) - 1:
        return {"fromTargetM": None, "pastM": round(steps[-1][0] + bin_m / 2.0, 1), "topFt": None, "groundFt": None}
    (x0, t0, g0), (x1, t1, g1) = steps[last], steps[last + 1]
    t = (above_m - heights[last]) / (heights[last + 1] - heights[last])
    ground = round(g0 + t * (g1 - g0), 1) if g0 is not None and g1 is not None else None
    return {"fromTargetM": round(x0 + t * (x1 - x0), 1), "topFt": round(t0 + t * (t1 - t0), 1), "groundFt": ground}


def lane(frame, points, ring, heading_deg):
    """The approach, from the touchdown point: ``heading_deg`` is the way the aircraft flies in, towards the LZ, so the lane lies behind
    the touchdown point. Its ``clearance`` is the shallowest angle that clears everything in the way with no margin, and what sets it;
    ``bins`` the track's ground and what stands highest in the way, each 10 m out, for the page's figure and the approach planned over
    it (``descent_point``, survey/flightpath.py)."""
    bearing = (heading_deg + 180.0) % 360.0
    direction = _unit(bearing)
    right = _unit((heading_deg + 90.0) % 360.0)              # the pilot's right, flying the heading
    bins = int(round(LANE_LENGTH_M / BIN_M))
    squares = int(round(2 * LANE_HALF_WIDTH_M / BIN_M))      # 10 m squares across the lane

    # The lane's returns, every class, past the touchdown point: in the LZ or out of it, what stands in the way is in the way.
    box = Strip((0.0, 0.0), bearing, 0.0, LANE_LENGTH_M, LANE_HALF_WIDTH_M).corners()
    (east_lo, north_lo), (east_hi, north_hi) = box.min(axis=0), box.max(axis=0)
    index = np.flatnonzero((points.east >= east_lo) & (points.east <= east_hi) & (points.north >= north_lo) & (points.north <= north_hi))
    along = points.east[index] * direction[0] + points.north[index] * direction[1]
    offset = points.east[index] * right[0] + points.north[index] * right[1]
    held = (along > 0.0) & (along <= LANE_LENGTH_M) & (np.abs(offset) <= LANE_HALF_WIDTH_M)
    index, along, offset = index[held], along[held], offset[held]
    which = np.minimum((along // BIN_M).astype(int), bins - 1)
    covered = np.bincount(which, minlength=bins) > 0

    centre = (np.arange(bins) + 0.5) * BIN_M
    track = points.ground_at(direction[0] * centre, direction[1] * centre)
    start = float(points.ground_at(0.0, 0.0)[0])
    edge = leaves(ring, direction)
    edge_point = direction * edge

    # In the way: what stands 5 m or more above its own ground (2 m what is built), and the ground in each 10 m square where it rises,
    # each measured from the touchdown point's ground on its own line across the lane.
    above = points.above[index]
    least = np.where(np.isin(points.cls[index], BUILT), STANDING_M, LANE_LEAST_M)
    tall = points.standing[index] & np.isfinite(above) & (np.nan_to_num(above, nan=-1.0) >= least)
    s, s_along, s_offset, s_bin = index[tall], along[tall], offset[tall], which[tall]

    def bases(offsets):
        return points.ground_at(right[0] * offsets, right[1] * offsets)

    s_base = bases(s_offset)
    s_rise = points.z[s] - s_base

    # Each 10 m square's rise: the median of its ground returns' rises, each the ground under it less the touchdown point's ground on
    # its own line across, both from the triangles, so a slope across the lane, and the triangles' own lean (the lowest return in each
    # 2 m, under the median where the returns scatter), take nothing from it. Only squares with GROUND_SQUARE_RETURNS returns or more.
    g = points.is_ground[index]
    g_index, g_offset = index[g], offset[g]
    g_square = which[g] * squares + np.clip(((g_offset + LANE_HALF_WIDTH_M) // BIN_M).astype(int), 0, squares - 1)
    g_ground = points.ground_at(points.east[g_index], points.north[g_index])
    g_rise = g_ground - bases(g_offset)
    known = np.isfinite(g_rise)
    t_keys, t_rise, t_top = np.zeros(0, dtype=int), np.zeros(0), np.zeros(0)
    if known.any():
        t_keys, t_rise, counts = _medians(g_square[known], g_rise[known])
        _keys, t_top, _counts = _medians(g_square[known], g_ground[known])
        enough = counts >= GROUND_SQUARE_RETURNS
        t_keys, t_rise, t_top = t_keys[enough], t_rise[enough], t_top[enough]
    t_along = (t_keys // squares + 0.5) * BIN_M
    t_offset = -LANE_HALF_WIDTH_M + (t_keys % squares + 0.5) * BIN_M

    def steepest():
        """The shallowest angle that clears everything in the way, and what sets it: {angleDeg, obstacle} (no obstacle where nothing
        rises above the touchdown point's ground, at 0°). None where no ground near the touchdown point is known."""
        if not (np.isfinite(s_base).any() or t_rise.size or math.isfinite(start)):
            return None
        s_angle = np.where(np.isfinite(s_rise), np.arctan2(np.nan_to_num(s_rise, nan=0.0), s_along), -np.inf)
        t_angle = np.where(t_rise > GROUND_RISE_M, np.arctan2(t_rise, t_along), -np.inf)
        best_s = int(np.argmax(s_angle)) if s_angle.size else None
        best_t = int(np.argmax(t_angle)) if t_angle.size else None
        top_s = s_angle[best_s] if best_s is not None else -np.inf
        top_t = t_angle[best_t] if best_t is not None else -np.inf
        if max(top_s, top_t) <= 0:
            return {"angleDeg": 0.0, "obstacle": None}
        if top_s >= top_t:
            extra = {"fromTargetM": round(float(s_along[best_s]), 1), "offsetM": round(float(s_offset[best_s]), 1),
                     "riseFt": _ft(float(s_rise[best_s])), "baseFt": _ft(float(s_base[best_s]))}
            return {"angleDeg": round(math.degrees(float(top_s)), 1), "obstacle": _spot(frame, points, int(s[best_s]), extra)}
        east = direction[0] * t_along[best_t] + right[0] * t_offset[best_t]
        north = direction[1] * t_along[best_t] + right[1] * t_offset[best_t]
        extra = {"fromTargetM": round(float(t_along[best_t]), 1), "offsetM": round(float(t_offset[best_t]), 1),
                 "riseFt": _ft(float(t_rise[best_t])), "baseFt": _ft(float(t_top[best_t] - t_rise[best_t]))}
        return {"angleDeg": round(math.degrees(float(top_t)), 1),
                "obstacle": _ground_spot(frame, east, north, float(t_top[best_t]), extra)}

    # Each 10 m step for the descent and the page's figure: the track's ground, and over it the highest thing in the way as the angle sees
    # it (the touchdown point's ground and its rise over its own line's), so ground rising to one side draws nothing standing.
    rise = np.full(bins, -np.inf)
    tallest = np.full(bins, -np.inf)
    if s.size:
        np.maximum.at(rise, s_bin, np.where(np.isfinite(s_rise), s_rise, -np.inf))
        np.maximum.at(tallest, s_bin, points.above[s])
    rows = []
    for k in range(bins):
        row = {"groundFt": _ft(float(track[k])), "topFt": None, "tallestFt": None}
        if covered[k]:
            top = float(track[k])
            if math.isfinite(rise[k]) and math.isfinite(start):
                top = start + rise[k] if not math.isfinite(top) else max(top, start + rise[k])
            row["topFt"] = _ft(top)
        if math.isfinite(tallest[k]):
            row["tallestFt"] = _ft(float(tallest[k]))
        rows.append(row)
    result = {
        "headingDeg": round(heading_deg % 360.0, 1),
        "bearingDeg": round(bearing, 1),
        "start": {"groundFt": _ft(start)},
        "edge": {**dict(zip(("lat", "lon"), (round(v, 7) for v in frame.latlon(*edge_point)))), "fromTargetM": round(edge, 1),
                 "groundFt": _ft(float(points.ground_at(edge_point[0], edge_point[1])[0]))},
        "lengthM": LANE_LENGTH_M, "halfWidthM": LANE_HALF_WIDTH_M, "binM": BIN_M,
        "bins": rows,
        "covered": int(covered.sum()),
        "clearance": None,
        "missing": None,
    }
    if not index.size:
        result["missing"] = "no_returns"
        return result
    result["clearance"] = steepest()
    if result["clearance"] is None:
        result["missing"] = "no_target_ground"
    return result


def _trees(frame, points, ring, inner):
    """The trees inside the boundary, one by one: a cell of the canopy's 1 m grid that is the highest within ``TREE_RADIUS_M`` round
    and stands ``TREE_MIN_M`` or more, one to a crown however flat its top. A building, a wire, a tower or a bridge is no tree."""
    from scipy import ndimage

    inner = inner[~np.isin(points.cls[inner], BUILT)]
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


def profile_tops(frame, points, ring, profiles, approach_deg):
    """The tallest thing standing within 30 m each side of the slope profile's line along the best approach, at each of its steps, as
    its height above the ground (ft; None where nothing stands), in the order of the line the terrain measured (``lines[i].heading``,
    0..178): the page reads it backwards as it reads the ground, and lays it on the ground it draws. Within the LZ's span along the line,
    only what stands in the LZ: a tree beside a narrow LZ is no tree in it."""
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
    centre = np.array([centre_e[0], centre_n[0]])
    count = len(line["elevationsFt"])
    half = p["stepM"] * (count - 1) / 2.0
    direction = _unit(base)
    right = np.array([direction[1], -direction[0]])
    index = np.flatnonzero(points.standing & np.isfinite(points.above))
    de, dn = points.east[index] - centre[0], points.north[index] - centre[1]
    along = de * direction[0] + dn * direction[1]
    across = de * right[0] + dn * right[1]
    which = np.round((along + half) / p["stepM"]).astype(int)
    held = (np.abs(across) <= PROFILE_HALF_WIDTH_M) & (which >= 0) & (which < count)
    index, along, which = index[held], along[held], which[held]
    shifted = ring - centre
    within = (along < leaves(shifted, direction)) & (along > -leaves(shifted, -direction))
    keep = ~within
    if within.any():
        keep[within] = inside(ring, points.east[index[within]], points.north[index[within]])
    index, which = index[keep], which[keep]
    above = [None] * count
    if index.size:
        order = np.argsort(which, kind="stable")
        steps, starts = np.unique(which[order], return_index=True)
        for k, start in enumerate(starts):
            stop = starts[k + 1] if k + 1 < len(starts) else order.size
            above[int(steps[k])] = _ft(float(points.above[index[order[start:stop]]].max()))
    return {"heading": base, "aboveFt": above}


def measure_obstacles(target, boundary, approach_deg, profiles=None, *, source=None):
    """What stands on and round the LZ, as the snapshot keeps it (``obstacles``). Never raises for want of data: ``status`` is
    ``measured``; ``none`` where there is nothing to measure here (no survey, one that classifies only the ground, no ground, more than a
    reading holds, an index that cannot be read); ``unavailable`` where this server reads no LiDAR; ``failed`` where USGS could not be
    read (measure again), each with its ``words``."""
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
    # A survey known to be older classifies only the ground; one whose name gives no year is judged by its returns once read.
    if survey.year is not None and not survey.likely_classified:
        return {"status": "none", "words": _ground_only(survey), "survey": named}

    frame = Frame(lat, lon)
    ring = np.column_stack(frame.local([p[0] for p in boundary], [p[1] for p in boundary]))
    shapes = [_box_strip(ring, AROUND_M)]
    if approach_deg is not None:
        shapes.append(Strip((0.0, 0.0), (approach_deg + 180.0) % 360.0, 0.0, LANE_LENGTH_M, LANE_HALF_WIDTH_M))
    p = None
    if approach_deg is not None:
        from survey import profile as profile_lib

        p = profile_lib._readable(profiles)
        if p is not None:
            centre = frame.local([p["center"][0]], [p["center"][1]])
            base = profile_lib.js_round(approach_deg / p["headingStepDeg"]) * p["headingStepDeg"] % 180
            shapes.append(Strip((float(centre[0][0]), float(centre[1][0])), base, -p["halfLengthM"], 2 * p["halfLengthM"],
                                PROFILE_HALF_WIDTH_M))
    wanted = [np.array([frame.to_mercator(*corner) for corner in shape.corners(GROUND_MARGIN_M)]) for shape in shapes]

    try:
        info = source.info(key)
        nodes = nodes_over(info, source.hierarchy(key), wanted)
        points = read_points(source, key, nodes, frame, shapes)
    except TooMany as why:
        return {"status": "none", "words": why.words, "survey": named}
    except concurrent.futures.TimeoutError:
        log.warning("surveys: the LiDAR for %s took more than %g s to read", survey.name, FETCH_DEADLINE_S)
        return {"status": "failed", "words": SLOW, "survey": named}
    except Exception as error:  # noqa: BLE001 - USGS could not be read: a measure to try again, unless its index is unreadable
        if isinstance(error, ept.EptError) or getattr(error, "code", None) == "not_streamable":
            log.warning("surveys: the LiDAR's index for %s cannot be read: %s", survey.name, error)
            return {"status": "none", "words": UNREADABLE, "survey": named}
        log.exception("surveys: the LiDAR could not be read for %s", survey.name)
        return {"status": "failed", "words": FAILED, "survey": named}
    if survey.year is None and not np.isin(points.cls, STANDING_CLASSES).any():
        return {"status": "none", "words": _ground_only(survey), "survey": named}

    if not points.measure_ground():
        return {"status": "none", "words": NO_GROUND, "survey": named}
    points.drop_lone()
    return {
        "status": "measured",
        "words": None,
        "survey": named,
        "points": int(points.z.size),
        "pieces": len(nodes),
        "approach": lane(frame, points, ring, approach_deg) if approach_deg is not None else None,
        **round_the_lz(frame, points, ring),
        "heightMap": height_map(frame, points, shapes[0]),
        "profileTops": profile_tops(frame, points, ring, profiles, approach_deg) if p is not None else None,
    }


def round_the_lz(frame, points, ring):
    """What stands in the LZ and round it: the tallest obstacle inside the boundary (``inside``) and within 100 m of it (``around``),
    the points standing more than 2 m in it (``standing``), and its trees. Grass, a crop or a low bush is none of them."""
    obstacles = points.obstacles()
    in_lz = inside(ring, points.east, points.north)
    tall_in = np.flatnonzero(obstacles & in_lz)
    # Round the LZ: the obstacles within its box and 100 m, then those within 100 m of its edge.
    (east_lo, north_lo), (east_hi, north_hi) = ring.min(axis=0) - AROUND_M, ring.max(axis=0) + AROUND_M
    near = np.flatnonzero(obstacles & ~in_lz & (points.east >= east_lo) & (points.east <= east_hi)
                          & (points.north >= north_lo) & (points.north <= north_hi))
    near = near[distance_to_edge(ring, points.east[near], points.north[near]) <= AROUND_M]

    def tallest(chosen, extra=None):
        if not chosen.size:
            return None
        i = int(chosen[np.argmax(points.above[chosen])])
        return _spot(frame, points, i, extra(i) if extra else None)

    up = tall_in[points.above[tall_in] > STANDING_M]
    return {
        "inside": tallest(tall_in),
        "around": tallest(near, lambda i: {"fromEdgeM": round(float(distance_to_edge(ring, points.east[i:i + 1], points.north[i:i + 1])[0]), 1)}),
        "standing": {"overM": STANDING_M, "count": int(up.size)},
        "trees": _trees(frame, points, ring, np.flatnonzero(points.standing & np.isfinite(points.above) & in_lz)),
    }
