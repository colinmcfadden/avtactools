/**
 * The MGRS grid for one map view, as plain data: the lines to draw and the labels to put on them.
 *
 * Every line is worked out in its own UTM zone and clipped to that zone, so it agrees with the
 * cursor readout (utils/mgrs toMgrs) wherever it is drawn: a point on the 66 km easting line of
 * 16S GD reads 66000. That matters most where the zones are not plain 6° strips — Norway's 32V
 * and Svalbard's 31X–37X — and at a zone's edge, where the neighbouring zone's lines stop.
 *
 * The map is Web Mercator, where a line of constant easting or northing is a curve. Each line is
 * sampled until the polyline is within `tolerancePx` of the true curve at the view's scale, and
 * its ends are found on the zone's or the view's edge by bisection, so the lines of two
 * neighbouring zones meet on their boundary and never cross it.
 *
 * Nothing here touches Leaflet or the DOM; MgrsGridLayer draws what this returns.
 */
import {
  bandFor,
  centralMeridian,
  fromUtm,
  squareLetters,
  toUtmInZone,
  zoneFor,
} from "../../utils/mgrs";

const SOUTH_LIMIT = -80;                 // UTM's range; the polar caps are UPS, not drawn
const NORTH_LIMIT = 84;
const MERCATOR_LIMIT = 85.0511287798;    // where Web Mercator's square world ends
const EARTH_RADIUS = 6378137;            // Web Mercator's sphere
const SQUARE_M = 100000;
const FALSE_NORTHING_SOUTH = 10000000;
const RAD = Math.PI / 180;
const MAX_COPIES = 8;                    // copies of the world side by side when zoomed far out
const MAX_DEPTH = 14;                    // halvings of a line while sampling it
const EDGE_M = 1e-3;                     // a line's end is found on an edge to a millimetre

/** Line spacings, finest first. Finer than 100 m is never drawn. */
export const GRID_SPACINGS = [100, 1000, 10000, 100000];
/** Neighbouring lines are at least this far apart on screen. */
export const MIN_LINE_GAP_PX = 70;
/** Narrower than this on screen, zones are not drawn at all: the map would be all lines. */
export const MIN_ZONE_PX = 12;

const DEFAULTS = {
  pad: 0,             // lines also cover this fraction of the view's size round it
  avoid: [],          // [x0, y0, x1, y1] boxes, pixels from the view's top-left, labels keep off
  maxLines: 1500,
  maxPoints: 60000,
  maxLabels: 250,
  tolerancePx: 0.25,
};

// Labels are drawn as 11 px monospace pills (mgrsGrid.css); these sizes keep them apart.
const LABEL = { charPx: 7, padPx: 10, heightPx: 17, gapPx: 3, edgePx: 4, cornerPx: 36, sidePx: 40 };
const PRINCIPAL_DIGITS = { 10000: 1, 1000: 2, 100: 3 };

const clamp = (value, lo, hi) => Math.min(hi, Math.max(lo, value));

/** The finest spacing whose lines are at least MIN_LINE_GAP_PX apart, or null for zones only. */
export const gridSpacing = (metersPerPixel) => {
  if (!Number.isFinite(metersPerPixel) || metersPerPixel <= 0) return null;
  return GRID_SPACINGS.find((spacing) => spacing / metersPerPixel >= MIN_LINE_GAP_PX) ?? null;
};

/** A line's value in the digits a grid reference gives it at this spacing: 66 km at 1 km is "66". */
export const principalDigits = (metres, spacing) => {
  const within = ((metres % SQUARE_M) + SQUARE_M) % SQUARE_M;
  return String(Math.floor(within / spacing)).padStart(PRINCIPAL_DIGITS[spacing], "0");
};

// -- Grid zones -------------------------------------------------------------------------------

// Band edges: 8° from 80°S, with band X running 72–84°N.
const BAND_EDGES = [...Array.from({ length: 20 }, (_, i) => -80 + 8 * i), 84];

/**
 * Every grid zone cell (a zone within one latitude band) with its longitude range, read off
 * zoneFor itself so the Norway and Svalbard exceptions are exactly the readout's. Their edges
 * all fall on whole degrees, and within a band zoneFor depends only on longitude.
 */
export const GRID_ZONES = (() => {
  const cells = [];
  for (let b = 0; b < 20; b += 1) {
    const south = BAND_EDGES[b];
    const north = BAND_EDGES[b + 1];
    const middle = (south + north) / 2;
    let west = -180;
    let zone = zoneFor(middle, -179.5);
    for (let lon = -179; lon <= 180; lon += 1) {
      const next = lon < 180 ? zoneFor(middle, lon + 0.5) : null;
      if (next !== zone) {
        cells.push({ zone, band: bandFor(middle), south, north, west, east: lon });
        west = lon;
        zone = next;
      }
    }
  }
  return cells;
})();

// A zone's cells merged where the longitude range runs unchanged from band to band: the
// stretches a grid line can run along without leaving its zone.
const STRIPS = (() => {
  const strips = [];
  const last = new Map();
  GRID_ZONES.forEach((cell) => {
    const previous = last.get(cell.zone);
    if (previous && previous.north === cell.south && previous.west === cell.west
      && previous.east === cell.east) {
      previous.north = cell.north;
      return;
    }
    const strip = { zone: cell.zone, south: cell.south, north: cell.north, west: cell.west, east: cell.east };
    strips.push(strip);
    last.set(cell.zone, strip);
  });
  return strips;
})();

// The meridians that are zone boundaries in each band.
const BAND_MERIDIANS = BAND_EDGES.slice(0, -1).map((south) => {
  const wests = GRID_ZONES.filter((cell) => cell.south === south).map((cell) => cell.west);
  return [...new Set([...wests, 180])].sort((a, b) => a - b);
});

// -- The view ---------------------------------------------------------------------------------

const mercatorY = (lat) => Math.log(Math.tan(Math.PI / 4 + (clamp(lat, -MERCATOR_LIMIT, MERCATOR_LIMIT) * RAD) / 2));
const mercatorLat = (y) => (2 * Math.atan(Math.exp(y)) - Math.PI / 2) / RAD;

/**
 * Pixels for the view, from its bounds and its ground scale at the centre: Web Mercator keeps a
 * degree of longitude the same width everywhere, so the scale at the centre fixes them all.
 */
const viewFrame = ({ south, west, north, east }, metersPerPixel) => {
  const top = mercatorY(north);
  const bottom = mercatorY(south);
  const centreLat = mercatorLat((top + bottom) / 2);
  const pxPerRad = (EARTH_RADIUS * Math.cos(centreLat * RAD)) / metersPerPixel;
  const pxPerDeg = pxPerRad * RAD;
  return {
    pxPerRad,
    pxPerDeg,
    width: (east - west) * pxPerDeg,
    height: (top - bottom) * pxPerRad,
    x: (lon) => (lon - west) * pxPerDeg,
    y: (lat) => (top - mercatorY(lat)) * pxPerRad,
    lon: (x) => west + x / pxPerDeg,
    lat: (y) => mercatorLat(top - y / pxPerRad),
    // Map pixels for a point anywhere, for measuring how far a polyline strays from its curve.
    px: (lat, lon) => [lon * pxPerDeg, -mercatorY(lat) * pxPerRad],
  };
};

/**
 * Leaflet's bounds run past ±180 once the view takes in the antimeridian or more than one
 * world. Each copy of the world in view is worked out in -180..180 and moved by its offset.
 */
const worldPieces = (west, east) => {
  const middle = (west + east) / 2;
  const from = Math.max(west, middle - 180 * MAX_COPIES);
  const to = Math.min(east, middle + 180 * MAX_COPIES);
  const pieces = [];
  for (let k = Math.floor((from + 180) / 360); k <= Math.floor((to + 180) / 360); k += 1) {
    const offset = 360 * k;
    const pieceWest = Math.max(from, offset - 180) - offset;
    const pieceEast = Math.min(to, offset + 180) - offset;
    if (pieceEast > pieceWest) pieces.push({ west: pieceWest, east: pieceEast, offset });
  }
  return pieces;
};

/** The parts of each zone's strips inside a view, one per world copy. */
const regionsIn = (view, pieces) => {
  const regions = [];
  pieces.forEach(({ west, east, offset }) => {
    STRIPS.forEach((strip) => {
      const region = {
        zone: strip.zone,
        offset,
        south: Math.max(strip.south, view.south),
        north: Math.min(strip.north, view.north),
        west: Math.max(strip.west, west),
        east: Math.min(strip.east, east),
        zoneEast: strip.east,
      };
      if (region.north > region.south && region.east > region.west) regions.push(region);
    });
  });
  return regions;
};

// -- UTM, with a signed northing ---------------------------------------------------------------

// Grid lines run across the equator, so they are worked out with the northing signed (south
// negative); a southern line's value adds the false northing back.
const project = (zone, lat, lon) => {
  const utm = toUtmInZone(lat, lon, zone);
  return { e: utm.easting, y: lat < 0 ? utm.northing - FALSE_NORTHING_SOUTH : utm.northing };
};

const unproject = (zone, e, y) => {
  const point = fromUtm(zone, y < 0 ? "S" : "N", e, y < 0 ? y + FALSE_NORTHING_SOUTH : y);
  // fromUtm folds into ±180; keep the longitude on its zone's side of the antimeridian.
  const meridian = centralMeridian(zone);
  let { lon } = point;
  if (lon - meridian > 180) lon -= 360;
  else if (lon - meridian < -180) lon += 360;
  return { lat: point.lat, lon };
};

const northingValue = (y) => (y < 0 ? y + FALSE_NORTHING_SOUTH : y);

/**
 * The eastings and northings a region spans. For a fixed latitude easting and northing only
 * grow away from the central meridian, and for a fixed longitude only change one way with
 * latitude (easting peaking at the equator), so the corners and those points bound them.
 */
const utmBox = (region) => {
  const { zone, south, north, west, east } = region;
  const meridian = clamp(centralMeridian(zone), west, east);
  const equator = clamp(0, south, north);
  const box = { eMin: Infinity, eMax: -Infinity, yMin: Infinity, yMax: -Infinity };
  [
    [south, west], [south, east], [north, west], [north, east],
    [south, meridian], [north, meridian], [equator, west], [equator, east],
  ].forEach(([lat, lon]) => {
    const { e, y } = project(zone, lat, lon);
    box.eMin = Math.min(box.eMin, e);
    box.eMax = Math.max(box.eMax, e);
    box.yMin = Math.min(box.yMin, y);
    box.yMax = Math.max(box.yMax, y);
  });
  return box;
};

const multiplesIn = (lo, hi, step) => {
  const values = [];
  for (let k = Math.ceil(lo / step); k * step <= hi; k += 1) values.push(k * step);
  return values;
};

// -- Tracing a line ----------------------------------------------------------------------------

const EDGES = [["lat", "south", 1], ["lat", "north", -1], ["lon", "west", 1], ["lon", "east", -1]];

/**
 * Where a function changes sign, between `a` (f >= 0) and `b` (f < 0), to within `within`:
 * the Illinois form of false position, which keeps the root bracketed like bisection but takes
 * a handful of steps on these smooth, nearly straight curves rather than thirty-odd. Returns
 * the end on the f >= 0 side.
 */
const crossing = (f, a0, fa0, b0, fb0, within) => {
  let a = a0;
  let fa = fa0;
  let b = b0;
  let fb = fb0;
  let kept = 0;
  for (let i = 0; i < 100 && Math.abs(b - a) > within; i += 1) {
    let m = (a * fb - b * fa) / (fb - fa);
    if (!(m > Math.min(a, b) && m < Math.max(a, b))) m = (a + b) / 2;
    const fm = f(m);
    if (fm === 0) return m;
    if (fm >= 0) {
      a = m;
      fa = fm;
      if (kept === 1) fb /= 2;
      kept = 1;
    } else {
      b = m;
      fb = fm;
      if (kept === -1) fa /= 2;
      kept = -1;
    }
  }
  return a;
};

/**
 * The part of a stretch of line inside a region. On a stretch where latitude and longitude each
 * only rise or only fall, that part is one interval, and each of its ends is found on the edge
 * that cuts it. Null when no part is inside.
 */
const clipStretch = (pointAt, t0, t1, region) => {
  const p0 = pointAt(t0);
  const p1 = pointAt(t1);
  let lo = t0;
  let hi = t1;
  let loEdge = null;
  let hiEdge = null;
  for (const [coord, side, sign] of EDGES) {
    const bound = region[side];
    const inside = (p) => sign * (p[coord] - bound);
    const in0 = inside(p0) >= 0;
    const in1 = inside(p1) >= 0;
    if (!in0 && !in1) return null;
    if (in0 !== in1) {
      const a = crossing(
        (t) => inside(pointAt(t)),
        in0 ? t0 : t1, inside(in0 ? p0 : p1),
        in0 ? t1 : t0, inside(in0 ? p1 : p0),
        EDGE_M,
      );
      if (in0 && a < hi) {
        hi = a;
        hiEdge = [coord, bound];
      } else if (in1 && a > lo) {
        lo = a;
        loEdge = [coord, bound];
      }
    }
  }
  return hi > lo ? { lo, hi, loEdge, hiEdge } : null;
};

// Pixels from a point to a segment.
const offSegment = ([x, y], [x0, y0], [x1, y1]) => {
  const dx = x1 - x0;
  const dy = y1 - y0;
  const length2 = dx * dx + dy * dy;
  const t = length2 === 0 ? 0 : clamp(((x - x0) * dx + (y - y0) * dy) / length2, 0, 1);
  return Math.hypot(x - (x0 + t * dx), y - (y0 + t * dy));
};

/** Points along [lo, hi], halving each piece until its middle is within tolerance of the chord. */
const sampleStretch = (pointAt, lo, hi, frame, tolerancePx) => {
  const toPx = (p) => frame.px(p.lat, p.lon);
  const first = pointAt(lo);
  const last = pointAt(hi);
  const points = [first];
  const refine = (t0, q0, t1, q1, depth) => {
    const tm = (t0 + t1) / 2;
    const pm = pointAt(tm);
    const qm = toPx(pm);
    // Always halve once, so a gently curving line is never judged by its ends alone.
    if (depth > 0 && (depth >= MAX_DEPTH || offSegment(qm, q0, q1) <= tolerancePx)) return;
    refine(t0, q0, tm, qm, depth + 1);
    points.push(pm);
    refine(tm, qm, t1, q1, depth + 1);
  };
  refine(lo, toPx(first), hi, toPx(last), 0);
  points.push(last);
  return points;
};

/**
 * One easting or northing line in a region, as polylines of [lat, lon]. A line of constant
 * easting turns back in longitude at the equator, and one of constant northing in latitude at
 * the central meridian, so each is traced as two stretches either side and joined again.
 */
const traceLine = (region, axis, value, box, frame, tolerancePx) => {
  const easting = axis === "easting";
  const pointAt = easting
    ? (t) => unproject(region.zone, value, t)
    : (t) => unproject(region.zone, t, value);
  const [from, to] = easting ? [box.yMin, box.yMax] : [box.eMin, box.eMax];
  const turn = easting ? 0 : 500000;
  const stretches = turn > from && turn < to ? [[from, turn], [turn, to]] : [[from, to]];

  const pieces = [];
  stretches.forEach(([t0, t1]) => {
    const clip = clipStretch(pointAt, t0, t1, region);
    if (!clip) return;
    const points = sampleStretch(pointAt, clip.lo, clip.hi, frame, tolerancePx);
    // The ends sit exactly on the edge that cut them.
    if (clip.loEdge) points[0][clip.loEdge[0]] = clip.loEdge[1];
    if (clip.hiEdge) points[points.length - 1][clip.hiEdge[0]] = clip.hiEdge[1];
    const previous = pieces[pieces.length - 1];
    if (previous && previous.hi === clip.lo) {
      previous.points.push(...points.slice(1));
      previous.hi = clip.hi;
    } else {
      pieces.push({ hi: clip.hi, points });
    }
  });
  return pieces.map(({ points }) => points.map(({ lat, lon }) => [lat, lon + region.offset]));
};

/**
 * Zone 31's central meridian is its east edge in band V (Norway's 32V takes 3–12°E), so its
 * 500 km easting runs along the boundary, which is zone 32's: the readout puts 3°E in 32. The
 * boundary is drawn as one; the line is not.
 */
const alongEastEdge = (zone, zoneEast, easting) => easting === 500000 && centralMeridian(zone) === zoneEast;

const levelValues = (regions, boxes, spacing, skipSquares) => regions.map((region, i) => {
  const keep = (value) => !(skipSquares && value % SQUARE_M === 0);
  return {
    eastings: multiplesIn(boxes[i].eMin, boxes[i].eMax, spacing)
      .filter((e) => keep(e) && !alongEastEdge(region.zone, region.zoneEast, e)),
    northings: multiplesIn(boxes[i].yMin, boxes[i].yMax, spacing).filter(keep),
  };
});

const traceLevel = (regions, boxes, values, kind, frame, tolerancePx) => {
  const lines = [];
  regions.forEach((region, i) => {
    values[i].eastings.forEach((e) => {
      traceLine(region, "easting", e, boxes[i], frame, tolerancePx).forEach((points) => {
        lines.push({ kind, zone: region.zone, axis: "easting", value: e, points });
      });
    });
    values[i].northings.forEach((y) => {
      traceLine(region, "northing", y, boxes[i], frame, tolerancePx).forEach((points) => {
        lines.push({ kind, zone: region.zone, axis: "northing", value: northingValue(y), points });
      });
    });
  });
  return lines;
};

/** Band edges as parallels across the view, and zone edges as meridians in each band. */
const zoneLines = (view, pieces) => {
  const lines = [];
  BAND_EDGES.forEach((lat) => {
    if (lat >= view.south && lat <= view.north) {
      lines.push({ kind: "zone", axis: "parallel", value: lat, points: [[lat, view.west], [lat, view.east]] });
    }
  });
  pieces.forEach(({ west, east, offset }) => {
    const spans = new Map();
    BAND_MERIDIANS.forEach((meridians, b) => {
      const lo = Math.max(BAND_EDGES[b], view.south);
      const hi = Math.min(BAND_EDGES[b + 1], view.north);
      if (hi <= lo) return;
      meridians.forEach((lon) => {
        if (lon < west || lon > east) return;
        const list = spans.get(lon) || [];
        const previous = list[list.length - 1];
        if (previous && previous[1] === lo) previous[1] = hi;
        else list.push([lo, hi]);
        spans.set(lon, list);
      });
    });
    spans.forEach((list, lon) => list.forEach(([lo, hi]) => {
      lines.push({ kind: "zone", axis: "meridian", value: lon, points: [[lo, lon + offset], [hi, lon + offset]] });
    }));
  });
  return lines;
};

// -- Labels -----------------------------------------------------------------------------------

const zoneText = (zone, lat) => `${String(zone).padStart(2, "0")}${bandFor(lat)}`;
const labelWidth = (text) => text.length * LABEL.charPx + LABEL.padPx;

// Where a line of one value crosses a parallel or a meridian (to 1e-10°, about 0.01 mm):
// easting only grows eastward along a parallel, and northing only grows northward along a
// meridian, so the line crosses once.
const solve = (measure, lo, hi, target) => {
  const f = (x) => measure(x) - target;
  const flo = f(lo);
  if (flo >= 0) return lo;
  const fhi = f(hi);
  if (fhi <= 0) return hi;
  return crossing(f, hi, fhi, lo, flo, 1e-10);
};

const stripsAtLatitude = (lat) => STRIPS.filter((strip) => (
  (lat >= strip.south && lat < strip.north) || (lat === NORTH_LIMIT && strip.north === NORTH_LIMIT)
));

const placeLabels = ({ view, pieces, frame, spacing, metersPerPixel, options }) => {
  const placed = options.avoid.map((box) => [...box]);
  const labels = [];
  const place = (label, box) => {
    if (labels.length >= options.maxLabels) return false;
    const [x0, y0, x1, y1] = box;
    if (x0 < 0 || y0 < 0 || x1 > frame.width || y1 > frame.height) return false;
    const g = LABEL.gapPx;
    if (placed.some(([a0, b0, a1, b1]) => x0 < a1 + g && x1 + g > a0 && y0 < b1 + g && y1 + g > b0)) {
      return false;
    }
    placed.push(box);
    labels.push({ ...label, box });
    return true;
  };
  const centredBox = (x, y, text) => {
    const w = labelWidth(text);
    return [x - w / 2, y - LABEL.heightPx / 2, x + w / 2, y + LABEL.heightPx / 2];
  };
  const minor = spacing !== null && spacing < SQUARE_M;

  // Grid zone designators, largest cells first, while the squares are not yet labelled with them.
  if (!minor) {
    const cells = [];
    pieces.forEach(({ west, east, offset }) => {
      GRID_ZONES.forEach((cell) => {
        const s = Math.max(cell.south, view.south);
        const n = Math.min(cell.north, view.north);
        const w = Math.max(cell.west, west);
        const e = Math.min(cell.east, east);
        if (n <= s || e <= w) return;
        const x0 = frame.x(w + offset);
        const x1 = frame.x(e + offset);
        const y0 = frame.y(n);
        const y1 = frame.y(s);
        cells.push({ cell, x0, x1, y0, y1, area: (x1 - x0) * (y1 - y0) });
      });
    });
    cells.sort((a, b) => b.area - a.area).forEach(({ cell, x0, x1, y0, y1 }) => {
      const text = `${String(cell.zone).padStart(2, "0")}${cell.band}`;
      if (x1 - x0 < labelWidth(text) + 24 || y1 - y0 < LABEL.heightPx + 24) return;
      const x = (x0 + x1) / 2;
      const y = (y0 + y1) / 2;
      place({ kind: "gzd", text, zone: cell.zone, lat: frame.lat(y), lon: frame.lon(x) }, centredBox(x, y, text));
    });
  }

  if (minor) {
    // Eastings where each line crosses one edge of the view. Returns the lines whose label did not
    // fit there, so they can try the opposite edge; `only` limits the run to those.
    const eastingsAlong = (lat, labelTop, only) => {
      const missed = new Set();
      pieces.forEach(({ west, east, offset }) => {
        stripsAtLatitude(lat).forEach((strip) => {
          const a = Math.max(strip.west, west);
          const b = Math.min(strip.east, east);
          if (b <= a) return;
          const eastingAt = (lon) => project(strip.zone, lat, lon).e;
          multiplesIn(eastingAt(a), eastingAt(b), spacing).forEach((value) => {
            if (alongEastEdge(strip.zone, strip.east, value)) return;
            const key = `${offset}:${strip.zone}:${value}`;
            if (only && !only.has(key)) return;
            const lon = solve(eastingAt, a, b, value);
            const text = principalDigits(value, spacing);
            const x = frame.x(lon + offset);
            const w = labelWidth(text);
            const placedHere = place(
              { kind: "easting", text, zone: strip.zone, value, lat, lon: lon + offset },
              [x - w / 2, labelTop, x + w / 2, labelTop + LABEL.heightPx],
            );
            if (!placedHere) missed.add(key);
          });
        });
      });
      return missed;
    };
    // Along the top first; a line whose label is blocked there (on a phone the search bar covers
    // that strip) is labelled along the bottom instead, so both halves of a reference can be read.
    const missedTop = eastingsAlong(view.north, frame.y(view.north) + LABEL.edgePx);
    if (missedTop.size) {
      eastingsAlong(view.south, frame.y(view.south) - LABEL.edgePx - LABEL.heightPx, missedTop);
    }

    // Northings down one side of the view, the same way: the left first, then the right.
    const northingsAlong = (piece, lon, labelLeft, only) => {
      const missed = new Set();
      if (!piece) return missed;
      STRIPS.filter((strip) => lon >= strip.west && lon < strip.east).forEach((strip) => {
        const s = Math.max(strip.south, view.south);
        const n = Math.min(strip.north, view.north);
        if (n <= s) return;
        const northingAt = (lat) => project(strip.zone, lat, lon).y;
        multiplesIn(northingAt(s), northingAt(n), spacing).forEach((y) => {
          const value = northingValue(y);
          // By value only: the two sides of the view can be in different zones, and what the
          // fallback must give is the row's digits.
          const key = `${value}`;
          if (only && !only.has(key)) return;
          const lat = solve(northingAt, s, n, y);
          const text = principalDigits(value, spacing);
          const py = frame.y(lat);
          const x = labelLeft(text);
          const placedHere = place(
            { kind: "northing", text, zone: strip.zone, value, lat, lon: lon + piece.offset },
            [x, py - LABEL.heightPx / 2, x + labelWidth(text), py + LABEL.heightPx / 2],
          );
          if (!placedHere) missed.add(key);
        });
      });
      return missed;
    };
    // The first piece holds the view's left edge, the last its right edge.
    const left = pieces[0];
    const leftEdge = left && Math.abs(left.west + left.offset - view.west) < 1e-9 ? left : null;
    const missedLeft = northingsAlong(leftEdge, leftEdge?.west, () => LABEL.edgePx);
    const right = pieces[pieces.length - 1];
    const rightEdge = right && Math.abs(right.east + right.offset - view.east) < 1e-9 ? right : null;
    if (rightEdge && (missedLeft.size || !leftEdge)) {
      // A hair inside the edge, so the strip it falls in is the one the view ends in.
      northingsAlong(rightEdge, rightEdge.east - 1e-9, (text) => frame.width - LABEL.edgePx - labelWidth(text),
        leftEdge ? missedLeft : null);
    }
  }

  // 100 km squares: at their middle while they are small, else in a corner with the zone too,
  // so a full reference can be read off the screen.
  if (spacing !== null) {
    const squares = [];
    regionsIn(view, pieces).forEach((region) => {
      const box = utmBox(region);
      for (let c = Math.floor(box.eMin / SQUARE_M); c * SQUARE_M < box.eMax; c += 1) {
        for (let r = Math.floor(box.yMin / SQUARE_M); r * SQUARE_M < box.yMax; r += 1) {
          const square = {
            e0: c * SQUARE_M, e1: (c + 1) * SQUARE_M, y0: r * SQUARE_M, y1: (r + 1) * SQUARE_M,
          };
          const clipped = {
            e0: Math.max(square.e0, box.eMin),
            e1: Math.min(square.e1, box.eMax),
            y0: Math.max(square.y0, box.yMin),
            y1: Math.min(square.y1, box.yMax),
          };
          // Only the columns a zone has (A–H and so on): the rest is outside every zone.
          if (c >= 1 && c <= 8 && clipped.e1 > clipped.e0 && clipped.y1 > clipped.y0) {
            squares.push({ region, square, clipped, area: (clipped.e1 - clipped.e0) * (clipped.y1 - clipped.y0) });
          }
        }
      }
    });

    // A label belongs to its square only if all of it is in the square and in the region.
    const fits = ({ region, square }, box) => [[box[0], box[1]], [box[2], box[1]], [box[0], box[3]], [box[2], box[3]]]
      .every(([x, y]) => {
        const lat = frame.lat(y);
        const lon = frame.lon(x) - region.offset;
        if (lat < region.south || lat > region.north || lon < region.west || lon > region.east) return false;
        const { e, y: northing } = project(region.zone, lat, lon);
        return e >= square.e0 && e <= square.e1 && northing >= square.y0 && northing <= square.y1;
      });

    squares.sort((a, b) => b.area - a.area).forEach((entry) => {
      const { region, clipped } = entry;
      const middle = { e: (clipped.e0 + clipped.e1) / 2, y: (clipped.y0 + clipped.y1) / 2 };
      const letters = squareLetters(region.zone, middle.e, northingValue(middle.y));
      const toScreen = ({ e, y }) => {
        const point = unproject(region.zone, e, y);
        return [frame.x(point.lon + region.offset), frame.y(point.lat)];
      };
      const candidates = [];
      if (!minor) {
        candidates.push(toScreen(middle));
      } else {
        // Clear of the line labels along the top and left: first the view's own corners, top
        // right first, then the corners of the square's part of the view, then its middle.
        const halfW = labelWidth(`${zoneText(region.zone, 0)} ${letters}`) / 2 + LABEL.sidePx;
        const halfH = LABEL.heightPx / 2 + LABEL.cornerPx;
        const dx = halfW * metersPerPixel;
        const dy = halfH * metersPerPixel;
        candidates.push(
          [frame.width - halfW, halfH],
          [frame.width - halfW, frame.height - halfH],
          [halfW, halfH],
          [halfW, frame.height - halfH],
          ...[
            { e: clipped.e1 - dx, y: clipped.y1 - dy },
            { e: clipped.e0 + dx, y: clipped.y1 - dy },
            { e: clipped.e1 - dx, y: clipped.y0 + dy },
            { e: clipped.e0 + dx, y: clipped.y0 + dy },
            middle,
          ].map(toScreen),
        );
      }
      candidates.some(([x, y]) => {
        const lat = frame.lat(y);
        const text = minor ? `${zoneText(region.zone, lat)} ${letters}` : letters;
        const box = centredBox(x, y, text);
        if (!fits(entry, box)) return false;
        return place({ kind: "square", text, zone: region.zone, lat, lon: frame.lon(x) }, box);
      });
    });
  }

  return labels;
};

// -- The grid ---------------------------------------------------------------------------------

/**
 * What to draw for a view: `bounds` {south, west, north, east} as Leaflet gives them (they may
 * run past ±180), and the ground scale at the view's centre in metres per pixel.
 *
 * Returns { spacing, lines, labels, dropped }: `spacing` is the finest line spacing drawn (null
 * for zone boundaries only); each line is { kind: "zone" | "major" (100 km) | "minor", points:
 * [[lat, lon], ...], axis, value, zone }; each label { kind: "gzd" | "square" | "easting" |
 * "northing", text, lat, lon, box } with `box` its pixels from the view's top-left. A level that
 * would pass the caps on lines or points is left out whole, finest first, and named in `dropped`:
 * the grid is then coarser, never wrong.
 */
export const mgrsGrid = (bounds, metersPerPixel, options = {}) => {
  const opts = { ...DEFAULTS, ...options };
  const result = { spacing: null, lines: [], labels: [], dropped: [] };
  const { south, west, north, east } = bounds || {};
  if (![south, west, north, east, metersPerPixel].every(Number.isFinite)) return result;
  if (metersPerPixel <= 0 || north <= south || east <= west) return result;

  const frame = viewFrame({ south, west, north, east }, metersPerPixel);
  if (6 * frame.pxPerDeg < MIN_ZONE_PX) return result;

  // Lines also cover a margin round the view, so a pan does not show bare map before the
  // redraw; labels go on the view itself.
  const padX = (east - west) * opts.pad;
  const padY = (mercatorY(north) - mercatorY(south)) * opts.pad;
  const lineView = {
    south: Math.max(SOUTH_LIMIT, mercatorLat(mercatorY(south) - padY)),
    north: Math.min(NORTH_LIMIT, mercatorLat(mercatorY(north) + padY)),
    west: west - padX,
    east: east + padX,
  };
  if (lineView.north <= lineView.south) return result;
  const linePieces = worldPieces(lineView.west, lineView.east);
  result.lines.push(...zoneLines(lineView, linePieces));

  const spacing = gridSpacing(metersPerPixel);
  const levels = [];
  if (spacing !== null) levels.push({ kind: "major", spacing: SQUARE_M, skipSquares: false });
  if (spacing !== null && spacing < SQUARE_M) levels.push({ kind: "minor", spacing, skipSquares: true });

  const regions = levels.length > 0 ? regionsIn(lineView, linePieces) : [];
  const boxes = regions.map(utmBox);
  let lineCount = result.lines.length;
  let pointCount = result.lines.length * 2;
  levels.forEach((level) => {
    if (result.dropped.length > 0) {
      result.dropped.push(level.kind);
      return;
    }
    const values = levelValues(regions, boxes, level.spacing, level.skipSquares);
    const count = values.reduce((sum, v) => sum + v.eastings.length + v.northings.length, 0);
    if (lineCount + count > opts.maxLines) {
      result.dropped.push(level.kind);
      return;
    }
    const lines = traceLevel(regions, boxes, values, level.kind, frame, opts.tolerancePx);
    const points = lines.reduce((sum, line) => sum + line.points.length, 0);
    if (pointCount + points > opts.maxPoints) {
      result.dropped.push(level.kind);
      return;
    }
    lineCount += lines.length;
    pointCount += points;
    result.lines.push(...lines);
    result.spacing = level.spacing;
  });

  const labelView = {
    south: Math.max(SOUTH_LIMIT, south),
    north: Math.min(NORTH_LIMIT, north),
    west,
    east,
  };
  if (labelView.north > labelView.south) {
    result.labels = placeLabels({
      view: labelView,
      pieces: worldPieces(west, east),
      frame,
      spacing: result.spacing,
      metersPerPixel,
      options: opts,
    });
  }
  return result;
};
