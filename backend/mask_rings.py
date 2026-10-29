"""
Outlines of a terrain mask as simple polygon rings, for the threat KMZ.

ForeFlight hangs on a KMZ whose polygons cross themselves or pass twice through
a point (seen 2026-10-28: tapping a threat spun for ever, while the same file
with those rings taken out opened at once; Google Earth draws either). Tracing
a mask's pixels gives such rings in two ways: a one-pixel neck or two areas
touching at a corner makes the tracer pass the same pixel twice, and
simplifying a ring can fold one stretch across another. So every ring here is
split where it meets itself and only kept simplified if it stayed simple.

numpy and OpenCV only, so the tests need nothing of Flask.
"""

import cv2
import numpy as np


def mask_rings(vis, min_area_px=8, epsilon_px=1.5):
    """
    The outer outlines of the True areas of a boolean grid, as lists of
    (col, row) pixel points, each a simple ring: no point twice, no edge
    crossing or touching another. Rings are open (the first point is not
    repeated at the end). Areas under min_area_px are left out, as before.
    """
    contours, _ = cv2.findContours(vis.astype(np.uint8), cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_NONE)
    rings = []
    for c in contours:
        if cv2.contourArea(c) < min_area_px:
            continue
        points = [(int(x), int(y)) for x, y in c[:, 0, :]]
        # Every piece of an area big enough is kept, however small the piece:
        # a speckled edge splits into many, and dropping them drops the area.
        for loop in _split_at_repeats(points):
            if _area(loop) != 0:
                rings.append(_simplified(loop, epsilon_px))
    return rings


def _split_at_repeats(points):
    """
    Cuts a traced outline into loops wherever it comes back to a point it has
    passed. A spike out and back (a loop of two points) is dropped.
    """
    loops, path, seen = [], [], {}
    for p in points:
        i = seen.get(p)
        if i is None:
            seen[p] = len(path)
            path.append(p)
            continue
        loop = path[i:]
        for q in loop[1:]:
            del seen[q]
        del path[i + 1:]
        if len(loop) >= 3:
            loops.append(loop)
    if len(path) >= 3:
        loops.append(path)
    return loops


def _simplified(loop, epsilon_px):
    """The loop simplified as far as it stays simple; the loop itself otherwise."""
    shape = np.array(loop, dtype=np.int32).reshape(-1, 1, 2)
    for eps in (epsilon_px, epsilon_px / 2, epsilon_px / 4):
        approx = [(int(x), int(y)) for x, y in cv2.approxPolyDP(shape, eps, True)[:, 0, :]]
        if len(approx) >= 3 and is_simple(approx):
            return approx
    return loop


def _area(ring):
    a = np.asarray(ring, dtype=float)
    x, y = a[:, 0], a[:, 1]
    return 0.5 * float(np.dot(x, np.roll(y, -1)) - np.dot(np.roll(x, -1), y))


def is_simple(ring):
    """True when no point repeats and no edge meets another but its neighbours."""
    n = len(ring)
    if n < 3 or len(set(map(tuple, ring))) != n:
        return False
    if n == 3:
        return _area(ring) != 0
    a = np.asarray(ring, dtype=float)
    b = np.roll(a, -1, axis=0)

    def orient(p, q, r):
        return np.sign((q[..., 0] - p[..., 0]) * (r[..., 1] - p[..., 1])
                       - (q[..., 1] - p[..., 1]) * (r[..., 0] - p[..., 0]))

    def on(p, q, r):
        # r lies within the box of segment pq (used where the three are collinear)
        return ((np.minimum(p[..., 0], q[..., 0]) <= r[..., 0]) & (r[..., 0] <= np.maximum(p[..., 0], q[..., 0]))
                & (np.minimum(p[..., 1], q[..., 1]) <= r[..., 1]) & (r[..., 1] <= np.maximum(p[..., 1], q[..., 1])))

    p1, q1 = a[:, None, :], b[:, None, :]
    p2, q2 = a[None, :, :], b[None, :, :]
    o1, o2 = orient(p1, q1, p2), orient(p1, q1, q2)
    o3, o4 = orient(p2, q2, p1), orient(p2, q2, q1)
    meet = ((o1 * o2 < 0) & (o3 * o4 < 0)
            | (o1 == 0) & on(p1, q1, p2) | (o2 == 0) & on(p1, q1, q2)
            | (o3 == 0) & on(p2, q2, p1) | (o4 == 0) & on(p2, q2, q1))
    i, j = np.indices((n, n))
    gap = (j - i) % n
    others = (gap > 1) & (gap < n - 1)
    return not bool((meet & others).any())
