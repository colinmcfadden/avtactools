import { mgrsGrid, principalDigits } from "../mgrsGrid/mgrsGrid";
import { toMgrs } from "../../utils/mgrs";

/*
 * The MGRS 1 km grid on a brief's strip charts (docs/MISSION_BRIEF.md §2.6), worked out with the grid code the map draws
 * (mgrsGrid.js; Android's MgrsGrid) and added to the compiled brief only when the PDF is asked for: compileBrief stays
 * exact and cheap, and the server only draws. Held to contracts/fixtures/brief/strip-grid.json, which Android's PDF (B8)
 * follows within the grid fixture's own tolerances.
 */

const R_METRES = 3440.065 * 1852; // compile.js's sphere
const RAD = Math.PI / 180;
// Any ground scale between 100/70 and 1000/70 m a pixel makes mgrsGrid draw its 1 km lines; with this tolerance a line
// keeps within 2 m of its true course.
const GRID_MPP = 10;
const GRID_OPTIONS = { maxLines: 4000, maxPoints: 400000, maxLabels: 0, tolerancePx: 0.2 };
// The squares a strip's route crosses are found from a point on it every this many metres.
const SQUARE_STEP_M = 500;

const round7 = (v) => Math.round(v * 1e7) / 1e7;

/** The place `along` metres up a strip's course and `across` to its right of its centre. */
const offsetFrom = (strip, along, across) => {
  const [lat0, lon0] = strip.centre;
  const c = strip.course * RAD;
  const x = along * Math.sin(c) + across * Math.cos(c);
  const y = along * Math.cos(c) - across * Math.sin(c);
  const lat = lat0 + y / R_METRES / RAD;
  const mid = ((lat0 + lat) / 2) * RAD;
  return [lat, lon0 + x / (R_METRES * Math.max(1e-9, Math.cos(mid))) / RAD];
};

/** A strip's four corners, bottom left first and round to the right, as [lat, lon]. */
export const stripCorners = (strip) => {
  const l = strip.length / 2;
  const w = strip.width / 2;
  return [[-l, -w], [l, -w], [l, w], [-l, w]].map(([along, across]) => offsetFrom(strip, along, across));
};

const metres = ([lat1, lon1], [lat2, lon2]) => {
  const h = Math.sin(((lat2 - lat1) * RAD) / 2) ** 2 + Math.cos(lat1 * RAD) * Math.cos(lat2 * RAD) * Math.sin(((lon2 - lon1) * RAD) / 2) ** 2;
  return 2 * R_METRES * Math.asin(Math.sqrt(h));
};

/** The 100 km squares ("16S GD") a path crosses, in the order it crosses them. */
export const squaresAlong = (path) => {
  const squares = [];
  const note = ([lat, lon]) => {
    const grid = toMgrs(lat, lon);
    const square = grid ? grid.split(" ").slice(0, 2).join(" ") : null;
    if (square && squares[squares.length - 1] !== square) squares.push(square);
  };
  (path ?? []).forEach((p, i) => {
    if (i > 0) {
      const from = path[i - 1];
      const steps = Math.floor(metres(from, p) / SQUARE_STEP_M);
      for (let k = 1; k <= steps; k += 1) {
        const t = k / (steps + 1);
        note([from[0] + (p[0] - from[0]) * t, from[1] + (p[1] - from[1]) * t]);
      }
    }
    note(p);
  });
  return squares;
};

/**
 * The 100 km squares on a strip that its route does not cross (a strip is 5.5 or 13.75 km wide, and may reach over a zone
 * or square line beside the route): the digits printed there belong to them, so the strip must name them too. Found from
 * points over the strip, a kilometre or so apart, in the order they are met from its bottom.
 */
export const squaresBeside = (strip, along) => {
  const seen = new Set(along);
  const others = [];
  const steps = Math.max(2, Math.ceil(strip.length / 1000));
  const across = Math.max(2, Math.ceil(strip.width / 1000));
  for (let i = 0; i <= steps; i += 1) {
    for (let j = 0; j <= across; j += 1) {
      const [lat, lon] = offsetFrom(strip, -strip.length / 2 + (strip.length * i) / steps, -strip.width / 2 + (strip.width * j) / across);
      const grid = toMgrs(lat, lon);
      const square = grid ? grid.split(" ").slice(0, 2).join(" ") : null;
      if (square && !seen.has(square)) {
        seen.add(square);
        others.push(square);
      }
    }
  }
  return others;
};

/**
 * The 1 km grid over a strip: every easting and northing line crossing the box round its corners (the server clips them
 * to the strip as it turns it), with its principal digits ("66" for 766 000) and the zone it belongs to, the boundaries of
 * grid zones as `zone` lines with no digits, the squares its route crosses (`squares`) and any other square on it (`others`).
 */
export const stripGrid = (strip) => {
  const corners = stripCorners(strip);
  const lats = corners.map((c) => c[0]);
  const lons = corners.map((c) => c[1]);
  const bounds = { south: Math.min(...lats), north: Math.max(...lats), west: Math.min(...lons), east: Math.max(...lons) };
  const grid = mgrsGrid(bounds, GRID_MPP, GRID_OPTIONS);
  const lines = grid.spacing === 1000
    ? grid.lines.map((line) => ({
      kind: line.kind,
      axis: line.axis,
      zone: line.zone ?? null,
      value: line.value,
      text: line.kind === "zone" ? null : principalDigits(line.value, 1000),
      points: line.points.map(([lat, lon]) => [round7(lat), round7(lon)]),
    }))
    : [];
  const squares = squaresAlong(strip.path);
  return { lines, squares, others: squaresBeside(strip, squares) };
};

/** The compiled brief with its strips' grids added, for the PDF. Nothing else changes. */
export const withStripGrids = (model) => {
  if (!Array.isArray(model?.strips) || model.strips.length === 0) return model;
  return { ...model, strips: model.strips.map((strip) => ({ ...strip, grid: stripGrid(strip) })) };
};
