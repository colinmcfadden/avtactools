import { mgrsGrid } from "../feature/mgrsGrid/mgrsGrid";

const fs = require("fs");
const { UPDATE, compact, fixturePath, writeFixture } = require("./fixtureIO");

// The map's MGRS grid for one view, as mgrsGrid.js works it out: the lines (each in its own zone, clipped to it) and the labels (zone
// designators, 100 km squares, and the lines' principal digits along the edges, moved to the opposite edge where one is covered). The
// native apps draw the same grid and are held to every case: the spacing chosen, every line's kind, zone, axis and value and its
// polyline, every label's text and place, and the levels dropped at the caps.
//
// The views are the awkward ones: each spacing over north Georgia, Norway's 32V and Svalbard's 31X–37X, a view across the equator, one
// across the antimeridian (bounds past 180, as Leaflet gives them), several copies of the world side by side, edges covered so values
// go to the opposite edge, caps that drop a level, and inputs that draw nothing.

const GENERATED_BY = "frontend/src/contracts/mgrsGridFixtures.test.js (UPDATE_CONTRACTS=1)";
const NAME = "mgrs/grid.json";
const EARTH_RADIUS = 6378137;
const RAD = Math.PI / 180;

const mercatorY = (lat) => Math.log(Math.tan(Math.PI / 4 + (lat * RAD) / 2));
const mercatorLat = (y) => (2 * Math.atan(Math.exp(y)) - Math.PI / 2) / RAD;

// A view of `width` × `height` pixels centred on a point at a ground scale, as a map gives it: Web Mercator bounds, and the scale at the
// centre (the same arithmetic as the map's own, so the frame the grid works out is this size).
const view = (lat, lon, metersPerPixel, width, height) => {
  const pxPerRad = (EARTH_RADIUS * Math.cos(lat * RAD)) / metersPerPixel;
  const halfLon = (width / 2 / pxPerRad) / RAD;
  const y = mercatorY(lat);
  return {
    bounds: {
      south: mercatorLat(y - height / 2 / pxPerRad),
      west: lon - halfLon,
      north: mercatorLat(y + height / 2 / pxPerRad),
      east: lon + halfLon,
    },
    metersPerPixel,
  };
};

const CASES = [
  { name: "north Georgia, 100 m lines", ...view(34.62, -84.18, 1.2, 412, 700) },
  { name: "north Georgia, 1 km lines", ...view(34.62, -84.18, 12, 412, 700) },
  { name: "north Georgia, 10 km lines", ...view(34.62, -84.18, 120, 412, 700) },
  { name: "north Georgia, 100 km squares only", ...view(34.62, -84.18, 1200, 412, 700) },
  { name: "the zone boundary at 84 W, 1 km lines on both sides", ...view(33.9, -84.0, 25, 600, 500) },
  { name: "the band boundary at 32 N", ...view(32.0, -84.6, 60, 500, 500) },
  { name: "zones only, the eastern United States", ...view(36, -84, 9000, 500, 600) },
  { name: "too far out for zones", ...view(20, 0, 200000, 400, 400) },
  { name: "Norway: 31V and 32V", ...view(60, 6, 1500, 600, 600) },
  { name: "Norway: 100 km squares across 31V and 32V", ...view(60, 4.5, 900, 600, 600) },
  { name: "Norway: the 3 E boundary at 100 km", ...view(60.5, 3.0, 150, 500, 500) },
  { name: "Norway: the 3 E boundary at 10 km", ...view(60.5, 3.0, 100, 500, 500) },
  { name: "Svalbard: 31X to 37X", ...view(78, 20, 4000, 700, 500) },
  { name: "Svalbard: 100 km squares at the 33X and 35X boundary", ...view(78, 21, 900, 500, 500) },
  { name: "across the equator, Kenya", ...view(0.05, 37.5, 60, 500, 500) },
  { name: "across the equator at 100 km", ...view(0, 37.5, 900, 400, 500) },
  { name: "far south, Tierra del Fuego", ...view(-54.8, -68.3, 90, 400, 600) },
  { name: "across the antimeridian, Fiji, bounds past 180", ...view(-17.0, 179.95, 120, 500, 400) },
  { name: "across the antimeridian at 100 km", ...view(-17.0, 180.5, 900, 500, 400) },
  { name: "several copies of the world", ...view(10, 0, 40000, 2400, 300) },
  { name: "the top edge covered: eastings at the bottom", ...view(34.62, -84.18, 12, 412, 700), options: { avoid: [[0, 0, 412, 90]] } },
  { name: "the left edge covered: northings on the right", ...view(34.62, -84.18, 12, 412, 700), options: { avoid: [[0, 0, 60, 700]] } },
  { name: "lines also cover a margin round the view", ...view(34.62, -84.18, 12, 412, 700), options: { pad: 0.25 } },
  { name: "the line cap drops the minor level", ...view(34.62, -84.18, 1.2, 412, 700), options: { maxLines: 12 } },
  { name: "the point cap drops a level", ...view(34.62, -84.18, 12, 412, 700), options: { maxPoints: 30 } },
  { name: "the label cap", ...view(34.62, -84.18, 12, 412, 700), options: { maxLabels: 5 } },
  { name: "near 84 N, the top of band X", ...view(83.5, 15, 1500, 400, 400) },
  { name: "near 80 S, the bottom of band C", ...view(-79.6, -60, 1500, 400, 400) },
  { name: "10 km lines past 80 S, the top covered: squares placed lower down", ...view(-79.7, -60, 120, 412, 700), options: { avoid: [[0, 0, 412, 90]] } },
  { name: "across the equator, the left covered: northings on the right", ...view(0.05, 37.5, 60, 500, 500), options: { avoid: [[0, 0, 70, 500]] } },
  { name: "no bounds", bounds: null, metersPerPixel: 12 },
  { name: "no scale", bounds: { south: 34, west: -85, north: 35, east: -84 }, metersPerPixel: 0 },
  { name: "bounds the wrong way round", bounds: { south: 35, west: -84, north: 34, east: -85 }, metersPerPixel: 12 },
];

const settle = (document) => {
  const file = fixturePath(NAME);
  const text = `${compact(document)}\n`;
  if (UPDATE) {
    writeFixture(NAME, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${NAME} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(firstDifference(JSON.parse(fs.readFileSync(file, "utf8")), JSON.parse(text))).toBeNull();
};

// Where the recorded grid and today's differ, or null. A number is the same within a relative 1e-9: the fixture was written under
// Jest and the suite now runs under Vitest, and the grid's trigonometry comes out a few units different in the last digits.
const firstDifference = (recorded, now, at = "$") => {
  if (typeof recorded === "number" && typeof now === "number") {
    const scale = Math.max(1, Math.abs(recorded), Math.abs(now));
    return Math.abs(recorded - now) <= 1e-9 * scale ? null : `${at}: ${recorded} is now ${now}`;
  }
  if (Array.isArray(recorded) || Array.isArray(now)) {
    if (!Array.isArray(recorded) || !Array.isArray(now) || recorded.length !== now.length) return `${at}: not the same list`;
    for (let i = 0; i < recorded.length; i += 1) {
      const found = firstDifference(recorded[i], now[i], `${at}[${i}]`);
      if (found) return found;
    }
    return null;
  }
  if (recorded && now && typeof recorded === "object" && typeof now === "object") {
    const keys = Object.keys(recorded);
    if (keys.join() !== Object.keys(now).join()) return `${at}: not the same fields`;
    for (const key of keys) {
      const found = firstDifference(recorded[key], now[key], `${at}.${key}`);
      if (found) return found;
    }
    return null;
  }
  return Object.is(recorded, now) ? null : `${at}: ${JSON.stringify(recorded)} is now ${JSON.stringify(now)}`;
};

test("the MGRS grid of each view", () => {
  const cases = CASES.map(({ name, bounds, metersPerPixel, options = {} }) => {
    const grid = mgrsGrid(bounds, metersPerPixel, options);
    return { name, bounds, metersPerPixel, options, expected: JSON.parse(JSON.stringify(grid)) };
  });
  // The fixture is worth holding a port to only if it takes in every kind of line and label, a dropped level, an edge moved, and an empty answer.
  const all = cases.map((c) => c.expected);
  const kinds = new Set(all.flatMap((g) => g.lines.map((l) => l.kind)));
  const labelKinds = new Set(all.flatMap((g) => g.labels.map((l) => l.kind)));
  const edges = new Set(all.flatMap((g) => g.labels.map((l) => l.edge).filter(Boolean)));
  expect([...kinds].sort()).toEqual(["major", "minor", "zone"]);
  expect([...labelKinds].sort()).toEqual(["easting", "gzd", "northing", "square"]);
  expect([...edges].sort()).toEqual(["bottom", "left", "right", "top"]);
  expect(all.some((g) => g.dropped.length > 0)).toBe(true);
  expect(all.filter((g) => g.lines.length === 0).length).toBeGreaterThanOrEqual(3);
  expect(new Set(all.map((g) => g.spacing))).toEqual(new Set([null, 100, 1000, 10000, 100000]));
  settle({
    description: "The map's MGRS grid for one view (frontend/src/feature/mgrsGrid/mgrsGrid.js): mgrsGrid(bounds, metersPerPixel, options) for each case.",
    generatedBy: GENERATED_BY,
    cases,
  });
});
