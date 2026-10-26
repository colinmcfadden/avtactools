"""USGS's Entwine octree, read as 3D Tiles.

Every 3DEP survey on AWS is published as Entwine Point Tiles (EPT): an octree
of LAZ pieces by level of detail, its index split into JSON pages. That is
already the structure a 3D Tiles point cloud is, so a survey's tileset is
written straight from the index instead of building a new octree per LZ
(docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C):

- a node ``D-X-Y-Z`` is a cube: the survey's cubic ``bounds`` halved D times,
  X, Y and Z counting cubes from its west, south and lowest corner;
- its points are ``ept-data/D-X-Y-Z.laz``, about ``span``³ cells' worth, so its
  spacing is its width over ``span``;
- refinement is additive: a child adds points between its parent's;
- the index lists every node with points, by its count; ``-1`` says the node's
  subtree is in a page of its own, ``ept-hierarchy/D-X-Y-Z.json``.

A tileset JSON holds ``LEVELS_PER_TILESET`` levels and joins the next level as
external tilesets, so a view reads a few small files, never the tens of
thousands of entries of a statewide survey. Positions in EPT are Web Mercator
metres with NAVD88 heights (every survey checked: the plan, *What was
measured*); regions are WGS84 radians with ellipsoidal heights, so a geoid
separation is added.

Pure: the network and the geoid come in as functions.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

# Four levels per file: a statewide survey's root file then names a few dozen
# tiles, and a 782 m cube's subtree (depth 8 in north Georgia) is one file.
LEVELS_PER_TILESET = 4

# EPSG:3857's sphere.
MERCATOR_RADIUS = 6378137.0

# The cube's own heights are padded by this once the geoid is added, so a
# separation read at the node's centre still holds at its corners: the geoid
# varies by centimetres across a piece and a metre or two across a state.
REGION_HEIGHT_PAD_M = 2.0

_NODE = re.compile(r"^(\d{1,2})-(\d{1,7})-(\d{1,7})-(\d{1,7})$")
MAX_DEPTH = 19


class EptError(ValueError):
    """An index or address this module will not read."""


@dataclass(frozen=True, order=True)
class Node:
    depth: int
    x: int
    y: int
    z: int

    @classmethod
    def parse(cls, text: str) -> "Node":
        match = _NODE.match(text or "")
        if not match:
            raise EptError(f"not a node address: {text!r}")
        node = cls(*(int(part) for part in match.groups()))
        if not node.valid:
            raise EptError(f"not a node address: {text!r}")
        return node

    @property
    def valid(self) -> bool:
        side = 1 << self.depth if 0 <= self.depth <= MAX_DEPTH else 0
        return (0 <= self.depth <= MAX_DEPTH
                and all(0 <= value < side for value in (self.x, self.y, self.z)))

    def __str__(self) -> str:
        return f"{self.depth}-{self.x}-{self.y}-{self.z}"

    def children(self):
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    yield Node(self.depth + 1, self.x * 2 + dx, self.y * 2 + dy,
                               self.z * 2 + dz)


ROOT = Node(0, 0, 0, 0)


@dataclass(frozen=True)
class Info:
    """What a tileset needs of a survey's ``ept.json``."""
    bounds: tuple            # xmin, ymin, zmin, xmax, ymax, zmax: a cube
    conforming: tuple        # the points' own extent, same order
    span: int

    @classmethod
    def read(cls, document) -> "Info":
        try:
            bounds = tuple(float(v) for v in document["bounds"])
            conforming = tuple(float(v) for v in document.get("boundsConforming") or bounds)
            span = int(document["span"])
            data_type = document.get("dataType")
            horizontal = str((document.get("srs") or {}).get("horizontal") or "")
        except (KeyError, TypeError, ValueError) as error:
            raise EptError(f"unreadable ept.json: {error}") from None
        if len(bounds) != 6 or len(conforming) != 6 or span <= 0:
            raise EptError("unreadable ept.json: bounds or span")
        if not all(math.isfinite(v) for v in bounds + conforming):
            raise EptError("unreadable ept.json: bounds are not finite")
        if data_type != "laszip":
            raise EptError(f"pieces are {data_type!r}, not LAZ")
        # Positions are read as Web Mercator. A survey in another frame would
        # be put somewhere else entirely (Albers read as Mercator is Nigeria).
        if horizontal != "3857":
            raise EptError(f"survey is in EPSG:{horizontal or '?'}, not 3857")
        return cls(bounds=bounds, conforming=conforming, span=span)

    def cube(self, node: Node) -> tuple:
        """A node's cube, in the survey's own coordinates."""
        xmin, ymin, zmin, xmax, _ymax, _zmax = self.bounds
        width = (xmax - xmin) / (1 << node.depth)
        return (xmin + node.x * width, ymin + node.y * width, zmin + node.z * width,
                xmin + (node.x + 1) * width, ymin + (node.y + 1) * width,
                zmin + (node.z + 1) * width)

    def width(self, node: Node) -> float:
        return (self.bounds[3] - self.bounds[0]) / (1 << node.depth)

    def spacing(self, node: Node) -> float:
        """How far apart a node's points are: what drawing it alone costs."""
        return self.width(node) / self.span


def mercator_to_lonlat(x: float, y: float) -> tuple:
    """EPSG:3857 metres to degrees: the sphere's inverse, exactly."""
    lon = math.degrees(x / MERCATOR_RADIUS)
    lat = math.degrees(2.0 * math.atan(math.exp(y / MERCATOR_RADIUS)) - math.pi / 2.0)
    return lon, lat


def lonlat_to_mercator(lon: float, lat: float) -> tuple:
    x = math.radians(lon) * MERCATOR_RADIUS
    y = MERCATOR_RADIUS * math.log(math.tan(math.pi / 4.0 + math.radians(lat) / 2.0))
    return x, y


def node_at(info: Info, lon: float, lat: float, depth: int) -> Node | None:
    """The node of a depth whose column holds a place, at the conforming
    heights' middle; None outside the survey's cube."""
    x, y = lonlat_to_mercator(lon, lat)
    xmin, ymin, zmin, xmax, ymax, _zmax = info.bounds
    if not (xmin <= x < xmax and ymin <= y < ymax):
        return None
    width = (xmax - xmin) / (1 << depth)
    z = (info.conforming[2] + info.conforming[5]) / 2.0
    return Node(depth, int((x - xmin) // width), int((y - ymin) // width),
                min((1 << depth) - 1, max(0, int((z - zmin) // width))))


class Hierarchy:
    """A survey's index, read page by page as it is needed.

    ``fetch_page(node)`` returns the parsed JSON of ``ept-hierarchy/<node>.json``.
    Pages are kept by the caller (they never change); this only walks them.
    """

    def __init__(self, fetch_page):
        self._fetch_page = fetch_page
        self._pages = {}

    def _page(self, root: Node) -> dict:
        page = self._pages.get(root)
        if page is None:
            document = self._fetch_page(root)
            if not isinstance(document, dict):
                raise EptError(f"hierarchy page {root} is not an object")
            page = {}
            for key, count in document.items():
                try:
                    node = Node.parse(key)
                    count = int(count)
                except (EptError, TypeError, ValueError):
                    continue
                if count != 0:
                    page[node] = count
            self._pages[root] = page
        return page

    def count(self, node: Node, *, page_root: Node = ROOT) -> int:
        """A node's points (``-1`` for "its own page"), 0 when it has none."""
        return self._page(page_root).get(node, 0)

    def subtree(self, top: Node, levels: int, *, page_root: Node) -> dict:
        """Every node from ``top`` down ``levels`` levels, by its count, and the
        page each one is listed in.

        A node listed as ``-1`` is followed into its own page, so the counts
        returned are real (or ``-1`` only at the last level, which is enough to
        know a child exists).
        """
        found = {}
        frontier = [(top, page_root)]
        for level in range(levels + 1):
            following = []
            for node, page in frontier:
                count = self.count(node, page_root=page)
                if count == 0:
                    continue
                if count < 0 and level < levels:
                    page = node
                    count = self.count(node, page_root=page)
                    if count == 0:
                        continue
                found[node] = (count, page)
                if level < levels:
                    following.extend((child, page) for child in node.children())
            frontier = following
        return found


def region(info: Info, node: Node, geoid_m: float) -> list:
    """A node's 3D Tiles region: radians west, south, east, north, and
    ellipsoidal metres low and high, the cube clamped to the points' heights."""
    xmin, ymin, zmin, xmax, ymax, zmax = info.cube(node)
    west, south = mercator_to_lonlat(xmin, ymin)
    east, north = mercator_to_lonlat(xmax, ymax)
    low = max(zmin, info.conforming[2])
    high = min(zmax, info.conforming[5])
    if low > high:
        low, high = high, low
    return [math.radians(west), math.radians(south), math.radians(east),
            math.radians(north), low + geoid_m - REGION_HEIGHT_PAD_M,
            high + geoid_m + REGION_HEIGHT_PAD_M]


def centre_lonlat(info: Info, node: Node) -> tuple:
    xmin, ymin, _zmin, xmax, ymax, _zmax = info.cube(node)
    return mercator_to_lonlat((xmin + xmax) / 2.0, (ymin + ymax) / 2.0)


def tileset(info: Info, hierarchy: Hierarchy, top: Node, *, page_root: Node,
            name, geoid, levels: int = LEVELS_PER_TILESET) -> tuple:
    """The tileset JSON rooted at ``top``, and the external tilesets it joins.

    ``name(node)`` is the opaque name a node is published under (its piece is
    ``<name>.pnts``, its own tileset ``<name>.json``); ``geoid(lons, lats)`` the
    separation at each place, in metres. Returns ``(document, joined)``, where
    ``joined`` maps each node joined as an external tileset to the page that
    lists it, so the caller can hand it out later.
    """
    nodes = hierarchy.subtree(top, levels, page_root=page_root)
    if top not in nodes:
        raise EptError(f"{top} has no points")
    ordered = sorted(nodes, key=lambda n: (n.depth, n.x, n.y, n.z))
    centres = [centre_lonlat(info, node) for node in ordered]
    separations = geoid([lon for lon, _lat in centres], [lat for _lon, lat in centres])
    regions = {node: region(info, node, float(separation))
               for node, separation in zip(ordered, separations)}

    joined = {}

    def tile(node):
        children = [child for child in node.children() if child in nodes]
        has_children = bool(children)
        body = {
            "boundingVolume": {"region": regions[node]},
            "geometricError": info.spacing(node) if has_children else 0.0,
        }
        if node.depth == top.depth + levels:
            # The next file starts here; whether it holds children is its own
            # business, so its error stays the spacing.
            body["geometricError"] = info.spacing(node)
            body["content"] = {"uri": f"{name(node)}.json"}
            joined[node] = nodes[node][1]
            return body
        body["content"] = {"uri": f"{name(node)}.pnts"}
        if has_children:
            body["children"] = [tile(child) for child in children]
        return body

    root = tile(top)
    root["refine"] = "ADD"
    document = {
        "asset": {"version": "1.0", "generator": "ezpz lidar stream"},
        "geometricError": info.spacing(top) * 2.0,
        "root": root,
    }
    return document, joined
