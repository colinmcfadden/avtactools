import { ARCGIS_WORDS, apiRootOf, chartLayerOptions, chartPreviewUrl, chartWords, isCoverage, leafletBounds, levelsFiner, liveBuildOf, nextExpiry } from "./vfrCharts";

const { readFixture } = require("../../contracts/readFixture");

const COVERAGE = readFixture("charts/coverage.json");
const EDITION = "29 Oct – 23 Dec 2026";
const IN_CYCLE = Date.parse("2026-11-05T12:00:00Z");
const EXPIRES = Date.parse("2026-12-24T09:01:00Z");
const VFR = {
  build: "2026-10-29.1",
  cycle: "2026-10-29",
  tiles: "/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp",
  coverage: "/charts/vfr/2026-10-29.1/coverage.json",
  tileSize: 512,
  minZoom: 5,
  maxZoom: 14,
  effective: "2026-10-29T09:01Z",
  expires: "2026-12-24T09:01Z",
  bounds: [-88, 32, -72, 40],
  fallback: [[-158.9, 18.6, -154.7, 22.6]],
};
const LIVE = liveBuildOf({ charts: { vfr: VFR } }, "https://api.test");

const words = (lat, lon, zoom, more = {}) =>
  chartWords({
    live: LIVE,
    coverage: COVERAGE,
    view: { west: lon - 0.01, south: lat - 0.01, east: lon + 0.01, north: lat + 0.01 },
    center: { lat, lon },
    zoom,
    now: IN_CYCLE,
    ...more,
  });
const texts = (answer) => answer.lines.map((line) => line.text);

describe("what the config says of the live build", () => {
  it("is taken from under the API's root, not its /api", () => {
    expect(apiRootOf("https://api.test/api")).toBe("https://api.test");
    expect(apiRootOf("https://api.test/api/")).toBe("https://api.test");
    expect(apiRootOf(undefined)).toBe("");
    expect(LIVE.tiles).toBe("https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp");
    expect(LIVE.coverage).toBe("https://api.test/charts/vfr/2026-10-29.1/coverage.json");
  });

  it("is nothing where there is no live build or nothing that can be drawn from", () => {
    expect(liveBuildOf({})).toBeNull();
    expect(liveBuildOf(null)).toBeNull();
    expect(liveBuildOf({ charts: {} })).toBeNull();
    ["build", "tiles", "coverage", "minZoom", "maxZoom", "tileSize", "bounds"].forEach((field) => {
      expect(liveBuildOf({ charts: { vfr: { ...VFR, [field]: undefined } } })).toBeNull();
    });
    expect(liveBuildOf({ charts: { vfr: { ...VFR, bounds: [1, 2, 3] } } })).toBeNull();
    expect(liveBuildOf({ charts: { vfr: { ...VFR, bounds: [1, 2, 3, "4"] } } })).toBeNull();
  });

  it("keeps only the fallback rectangles it can draw", () => {
    expect(liveBuildOf({ charts: { vfr: { ...VFR, fallback: undefined } } }).fallback).toEqual([]);
    expect(liveBuildOf({ charts: { vfr: { ...VFR, fallback: [[1, 2, 3], [1, 2, 3, 4]] } } }).fallback).toEqual([[1, 2, 3, 4]]);
  });

  it("knows a coverage it can read", () => {
    expect(isCoverage(COVERAGE)).toBe(true);
    expect(isCoverage({ charts: "x", minLevel: 5 })).toBe(false);
    expect(isCoverage({ charts: [] })).toBe(false);
    expect(isCoverage(null)).toBe(false);
  });
});

describe("the chart layer", () => {
  it("counts in Leaflet's zooms, the build's levels plus one, and goes no further than its bounds", () => {
    expect(chartLayerOptions(LIVE)).toEqual({
      tileSize: 512,
      zoomOffset: -1,
      minNativeZoom: 6,
      maxNativeZoom: 15,
      maxZoom: 20,
      bounds: [[32, -88], [40, -72]],
    });
    expect(leafletBounds([-158.9, 18.6, -154.7, 22.6])).toEqual([[18.6, -158.9], [22.6, -154.7]]);
  });

  it("on a dense screen lays each tile over half the width, one level finer, within the same levels", () => {
    expect(chartLayerOptions(LIVE, 1)).toEqual({
      tileSize: 256,
      zoomOffset: 0,
      minNativeZoom: 5,
      maxNativeZoom: 14,
      maxZoom: 20,
      bounds: [[32, -88], [40, -72]],
    });
  });

  it("draws one level finer only from the ratio where that level is the nearer of the two, and never two", () => {
    expect([1, 1.25, 1.4].map(levelsFiner)).toEqual([0, 0, 0]);
    expect([1.5, 2, 2.625, 3, 4].map(levelsFiner)).toEqual([1, 1, 1, 1, 1]);
  });

  it("gives the switcher a tile of the build, over Denver where it reaches", () => {
    expect(chartPreviewUrl({ ...LIVE, bounds: [-125, 24, -66, 50] })).toBe("https://api.test/charts/vfr/2026-10-29.1/8/53/97.webp");
    // The Atlanta and Washington build of the fixture does not reach Denver: its own middle, at the level asked for.
    expect(chartPreviewUrl(LIVE)).toBe("https://api.test/charts/vfr/2026-10-29.1/8/71/100.webp");
    expect(chartPreviewUrl({ ...LIVE, minZoom: 9, maxZoom: 11 })).toContain("/9/");
    expect(chartPreviewUrl({ ...LIVE, minZoom: 5, maxZoom: 6 })).toContain("/6/");
  });
});

describe("the charts named", () => {
  it("names a chart with its edition, in the plan's words", () => {
    expect(words(33.5, -87, 10)).toEqual({ lines: [{ text: `FAA Atlanta Sectional, ${EDITION}`, expired: false }], enlarged: null });
  });

  it("says a finer chart once it is shown, and not the one wholly under it", () => {
    expect(texts(words(33.75, -84.4, 11))).toEqual([`FAA Atlanta Sectional, ${EDITION}`]);      // level 10: the TAC starts at 11
    expect(texts(words(33.75, -84.4, 13))).toEqual([`FAA Atlanta TAC, ${EDITION}`]);
    expect(texts(words(38.87, -77.03, 14))).toEqual([`FAA Washington Inset, ${EDITION}`]);
  });

  it("goes by the level Leaflet draws, which is the zoom rounded", () => {
    expect(texts(words(33.75, -84.4, 11.4))).toEqual([`FAA Atlanta Sectional, ${EDITION}`]);
    expect(texts(words(33.75, -84.4, 11.5))).toEqual([`FAA Atlanta TAC, ${EDITION}`]);
  });

  it("names both while a finer chart's edge is in view, the finer first", () => {
    const view = { west: -85.35, south: 33.75, east: -85.25, north: 33.85 };
    expect(texts(words(33.8, -85.3, 13, { view }))).toEqual([`FAA Atlanta TAC, Atlanta Sectional, ${EDITION}`]);
  });

  it("counts what it does not name", () => {
    const view = { west: -88, south: 32, east: -72, north: 40 };
    expect(texts(words(36, -80, 12, { view }))).toEqual([`FAA Atlanta TAC, Baltimore-Washington TAC and 2 more, ${EDITION}`]);
  });

  it("does not take a chart's corner for the chart", () => {
    // Inside the bounding box of this outline's northwest corner, and outside the outline itself.
    const triangle = { ...COVERAGE.charts[0], id: "t", title: "Triangle", outline: { type: "Polygon", coordinates: [[[0, 0], [10, 0], [10, 10], [0, 0]]] } };
    const coverage = { ...COVERAGE, charts: [triangle] };
    expect(texts(words(8, 2, 10, { coverage }))).toEqual([]);
    expect(texts(words(2, 8, 10, { coverage }))).toEqual([`FAA Triangle, ${EDITION}`]);
  });

  it("finds a chart's edge when only a vertex of it is in the view", () => {
    const view = { west: -82.5, south: 31.5, east: -81.5, north: 32.5 };           // round the sectional's southeast corner
    expect(texts(words(32, -82, 10, { view }))).toEqual([`FAA Atlanta Sectional, ${EDITION}`]);
  });

  it("reads a map panned round the world", () => {
    expect(texts(words(33.5, -87 + 360, 10))).toEqual([`FAA Atlanta Sectional, ${EDITION}`]);
    expect(texts(words(33.5, -87 - 720, 10))).toEqual([`FAA Atlanta Sectional, ${EDITION}`]);
  });

  it("gives a chart held back from an older cycle its own line", () => {
    const older = { start: "2026-09-03", end: "2026-10-28", effective: "2026-09-03T09:01Z", expires: "2026-10-29T09:01Z" };
    const charts = COVERAGE.charts.map((chart) => (chart.id === "atlanta-sec" ? { ...chart, edition: older } : chart));
    const view = { west: -85.35, south: 33.75, east: -85.25, north: 33.85 };
    expect(words(33.8, -85.3, 13, { view, coverage: { ...COVERAGE, charts } }).lines).toEqual([
      { text: "FAA Atlanta Sectional · Expired 28 Oct 2026 — not current", expired: true },
      { text: `FAA Atlanta TAC, ${EDITION}`, expired: false },
    ]);
  });

  it("writes the year twice only when an edition crosses one", () => {
    const edition = { start: "2026-12-24", end: "2027-02-18", effective: "2026-12-24T09:01Z", expires: "2027-02-19T09:01Z" };
    const charts = COVERAGE.charts.map((chart) => ({ ...chart, edition }));
    expect(texts(words(33.5, -87, 10, { coverage: { ...COVERAGE, charts } }))).toEqual(["FAA Atlanta Sectional, 24 Dec 2026 – 18 Feb 2027"]);
  });
});

describe("an edition that has run out", () => {
  it("turns red at its expiry instant, not at the end of its printed date", () => {
    expect(words(33.5, -87, 10, { now: EXPIRES - 1 }).lines).toEqual([{ text: `FAA Atlanta Sectional, ${EDITION}`, expired: false }]);
    expect(words(33.5, -87, 10, { now: EXPIRES }).lines).toEqual([
      { text: "FAA Atlanta Sectional · Expired 23 Dec 2026 — not current", expired: true },
    ]);
  });

  it("says when the next is due to the page, chart by chart", () => {
    expect(nextExpiry(LIVE, COVERAGE, IN_CYCLE)).toBe(EXPIRES);
    expect(nextExpiry(LIVE, null, IN_CYCLE)).toBe(EXPIRES);
    expect(nextExpiry(LIVE, COVERAGE, EXPIRES)).toBeNull();
    expect(nextExpiry(null, null, IN_CYCLE)).toBeNull();
    const early = { ...COVERAGE.charts[0], edition: { ...COVERAGE.charts[0].edition, expires: "2026-11-10T09:01Z" } };
    expect(nextExpiry(LIVE, { charts: [early, COVERAGE.charts[1]] }, IN_CYCLE)).toBe(Date.parse("2026-11-10T09:01Z"));
  });
});

describe("the line about a chart enlarged", () => {
  it("is the plan's example at web zoom 16 over a sectional", () => {
    expect(words(33.5, -87, 16).enlarged).toBe("Sectional enlarged 16× — no finer chart here");
  });

  it("starts once the chart is shown at twice its detail, and moves in whole powers of two", () => {
    expect(words(33.5, -87, 12).enlarged).toBeNull();     // 1.3×
    expect(words(33.5, -87, 13).enlarged).toBe("Sectional enlarged 2× — no finer chart here");
    expect(words(33.5, -87, 13.9).enlarged).toBe("Sectional enlarged 4× — no finer chart here");
  });

  it("names the finest chart at the crosshair", () => {
    expect(words(33.75, -84.4, 15).enlarged).toBe("TAC enlarged 4× — no finer chart here");
    expect(words(39.2, -76.6, 14).enlarged).toBe("Helicopter chart enlarged 2× — no finer chart here");
    expect(words(38.87, -77.03, 15).enlarged).toBe("Inset enlarged 2× — no finer chart here");
  });

  it("is said of the crosshair, not of the view", () => {
    const view = { west: -85.35, south: 33.75, east: -85.25, north: 33.85 };
    expect(words(33.8, -85.4, 15, { view }).enlarged).toBe("Sectional enlarged 8× — no finer chart here");
    expect(words(33.8, -85.2, 15, { view }).enlarged).toBe("TAC enlarged 4× — no finer chart here");
  });

  it("is not said where no chart is", () => {
    expect(words(34, -80.5, 18).enlarged).toBeNull();
  });
});

describe("ArcGIS's layers", () => {
  it("are all there is with no live build, and have no edition", () => {
    expect(words(33.5, -87, 16, { live: null })).toEqual({ lines: [{ text: ARCGIS_WORDS, expired: false }], enlarged: null });
    expect(ARCGIS_WORDS).toBe("FAA, via ArcGIS");
  });

  it("are named beside the charts only when one is in view", () => {
    expect(texts(words(20, -156, 9))).toEqual([ARCGIS_WORDS]);
    expect(texts(words(33.5, -87, 10))).not.toContain(ARCGIS_WORDS);
    const view = { west: -180, south: -80, east: 180, north: 80 };
    expect(texts(words(33.5, -87, 7, { view }))).toContain(ARCGIS_WORDS);
  });
});

describe("before the coverage has come", () => {
  it("names the build's own edition, and says nothing of enlargement", () => {
    expect(words(33.5, -87, 16, { coverage: null })).toEqual({ lines: [{ text: `FAA VFR charts, ${EDITION}`, expired: false }], enlarged: null });
  });

  it("still turns red", () => {
    expect(words(33.5, -87, 16, { coverage: null, now: EXPIRES }).lines).toEqual([
      { text: "FAA VFR charts · Expired 23 Dec 2026 — not current", expired: true },
    ]);
  });
});
