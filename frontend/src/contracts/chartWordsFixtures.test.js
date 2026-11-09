import { chartWords } from "../feature/vfrCharts/vfrCharts";

const { readFixture } = require("./readFixture");
const { UPDATE, dumps, writeFixture } = require("./fixtureIO");

// What the VFR map says of the charts under it (docs/VFR_CHARTS.md §4.5), over the recorded coverage.json: the web is the reference, and
// Android's overlay is held to the same answers. Regenerate with UPDATE_CONTRACTS=1 after an intended change.

const NAME = "charts/words.json";
const COVERAGE = readFixture("charts/coverage.json");
const LIVE = {
  build: COVERAGE.build,
  effective: COVERAGE.effective,
  expires: COVERAGE.expires,
  bounds: COVERAGE.bounds,
  fallback: [[-158.906, 18.646, -154.688, 22.594], [165.938, 12.897, 180, 21.943]],
};
const IN_CYCLE = "2026-11-05T12:00:00Z";

const box = (lat, lon, half = 0.01) => ({ west: lon - half, south: lat - half, east: lon + half, north: lat + half });
const at = (lat, lon, zoom, half) => ({ view: box(lat, lon, half), center: { lat, lon }, zoom, now: IN_CYCLE });

// Places chosen against the fixture's outlines: Alabama has the Atlanta sectional alone, Atlanta its TAC, Washington every tier.
const CASES = [
  ["a sectional alone", at(33.5, -87, 10)],
  ["a sectional alone, at the level it is stored to", at(33.5, -87, 12)],
  ["a sectional enlarged twice", at(33.5, -87, 13)],
  ["a sectional enlarged as far as the plan's example", at(33.5, -87, 16)],
  ["a TAC's ground before the TAC is shown", at(33.75, -84.4, 11)],
  ["a TAC over its sectional, which is wholly under it", at(33.75, -84.4, 13)],
  ["a TAC enlarged", at(33.75, -84.4, 15)],
  ["a TAC's edge in view: both, the finer first", { ...at(33.8, -85.3, 13, 0.05), center: { lat: 33.8, lon: -85.2 } }],
  ["the crosshair on the sectional beside a TAC", { ...at(33.8, -85.3, 15, 0.05), center: { lat: 33.8, lon: -85.4 } }],
  ["the helicopter chart", at(39.2, -76.6, 14)],
  ["every tier stacked at Washington", at(38.87, -77.03, 14)],
  ["the inset enlarged", at(38.87, -77.03, 18)],
  ["below the first level, the first level's charts", at(33.5, -87, 4)],
  ["more charts than are named", { view: { west: -88, south: 32, east: -72, north: 40 }, center: { lat: 36, lon: -80.5 }, zoom: 12, now: IN_CYCLE }],
  ["no chart under the crosshair or in view", at(34, -80.5, 16)],
  ["a turn of the world east", { view: box(33.5, 273), center: { lat: 33.5, lon: 273 }, zoom: 10, now: IN_CYCLE }],
  ["ArcGIS's rectangle in view", at(21, -157, 9)],
  ["ArcGIS's rectangle across the date line", { view: { west: 170, south: 15, east: 190, north: 20 }, center: { lat: 17, lon: 180 }, zoom: 9, now: IN_CYCLE }],
  ["a moment before the expiry instant", { ...at(33.5, -87, 10), now: "2026-12-24T09:00:59Z" }],
  ["at the expiry instant", { ...at(33.5, -87, 10), now: "2026-12-24T09:01:00Z" }],
  ["past it, the charts in view together", { ...at(33.8, -85.3, 13, 0.05), now: "2027-01-02T00:00:00Z" }],
  ["coverage not come yet", { ...at(33.5, -87, 16), coverage: false }],
  ["coverage not come yet, past the expiry", { ...at(33.5, -87, 16), coverage: false, now: "2026-12-25T00:00:00Z" }],
  ["no live build", { ...at(33.5, -87, 16), live: false }],
];

const answers = () =>
  CASES.map(([name, { coverage = true, live = true, now, ...place }]) => ({
    name,
    live,
    coverage,
    now,
    ...place,
    expected: chartWords({
      live: live ? LIVE : null,
      coverage: coverage ? COVERAGE : null,
      now: Date.parse(now),
      ...place,
    }),
  }));

test("what the map says of the charts under it", () => {
  const document = { generatedBy: "frontend/src/contracts/chartWordsFixtures.test.js (UPDATE_CONTRACTS=1)", live: LIVE, cases: answers() };
  if (UPDATE) writeFixture(NAME, dumps(document));
  expect(readFixture(NAME)).toEqual(document);
});
