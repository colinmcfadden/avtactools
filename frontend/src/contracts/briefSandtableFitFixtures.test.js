import L from "leaflet";
import { fitInsets, overviewBounds } from "../feature/brief/sandtableSteps";
import { CASES as COMPILE_CASES, compileCase } from "./briefCases";
import { UPDATE, compact, writeFixture } from "./fixtureIO";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/sandtable-fit.json: where the live sandtable fits a step's figure, for Android's sandtable (docs/MISSION_BRIEF.md, B9):
//  - `insets`: what feature/brief/sandtableSteps.js's `fitInsets` keeps clear of the boxes laid over the map (the card, the constants box, steps lying over
//    it), the reference for Android's port of it;
//  - `views`: where the web's map, Leaflet, flies for a step's area with those insets as its padding (SandtableLayer.jsx: `map.flyToBounds(bounds,
//    { paddingTopLeft, paddingBottomRight, maxZoom: 17 })`, on a map with MapView.jsx's `zoomSnap` 0.1), the reference for Android's SandtableCamera, which
//    works the same out itself for MapLibre (whose zoom is Leaflet's less one). Leaflet's own code answers, not a copy of its arithmetic.
// Regenerate with
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/briefSandtableFit
// and review the diff.

const FIXTURE = "brief/sandtable-fit.json";

const box = (left, top, right, bottom) => ({ left, top, right, bottom });

// The map's box and what lies over it, as the sandtable measures them (sandtableSteps.test.js's, and more that squeeze and tie).
const MAP = box(300, 56, 1440, 900);
const CARD = box(480, 500, 1260, 884);
const CONSTANTS = box(1158, 68, 1428, 260);
const INSET_CASES = [
  { name: "nothing over the map", map: MAP, covers: [] },
  { name: "the card along the foot", map: MAP, covers: [CARD] },
  { name: "the constants box, then the card", map: MAP, covers: [CONSTANTS, CARD] },
  { name: "a corner box taken from the top on a wide map", map: box(300, 56, 2400, 900), covers: [box(2100, 68, 2388, 140)] },
  { name: "a box beside the map", map: MAP, covers: [box(0, 56, 290, 900)] },
  { name: "a box inside the band the card keeps clear", map: MAP, covers: [CARD, box(600, 700, 900, 880)] },
  { name: "steps lying over a narrow map", map: box(0, 56, 800, 860), covers: [box(0, 56, 300, 860)] },
  { name: "steps over a narrow map, the card and the constants", map: box(0, 56, 845, 800), covers: [box(0, 56, 300, 800), box(8, 420, 837, 792), box(593, 68, 833, 112)] },
  { name: "a cover nearly the whole map: squeezed to the least", map: MAP, covers: [box(300, 120, 1440, 900)] },
  { name: "covers either side and top and bottom: squeezed both ways", map: MAP, covers: [box(300, 56, 900, 900), box(840, 56, 1440, 900), box(300, 56, 1440, 500), box(300, 450, 1440, 900)] },
  { name: "a tiny map", map: box(0, 0, 100, 100), covers: [box(0, 10, 100, 100)] },
  { name: "two boxes of one size: the first first", map: MAP, covers: [box(310, 66, 410, 166), box(1330, 790, 1430, 890)] },
  { name: "a map with no size", map: box(0, 0, 0, 0), covers: [CARD] },
  { name: "no map", map: null, covers: [CARD] },
  { name: "another margin and least", map: MAP, covers: [CARD, CONSTANTS], options: { margin: 24, min: 240 } },
];

const insetCase = (c) => ({ ...c, insets: fitInsets(c.map, c.covers, c.options) });

// Leaflet's own answer: the map as MapView.jsx makes it (zoomSnap 0.1, zoomDelta 1), sized as given. Leaflet snaps a fit to `zoomSnap` only where the browser
// does 3D transforms (`Browser.any3d`), which every browser the app runs in does and jsdom does not report.
L.Browser.any3d = true;
const leafletView = (bounds, [x, y], insets) => {
  const element = document.createElement("div");
  document.body.appendChild(element);
  const map = L.map(element, { zoomSnap: 0.1, zoomDelta: 1, center: [0, 0], zoom: 3 });
  map.getSize = () => L.point(x, y);
  const { center, zoom } = map._getBoundsCenterZoom(L.latLngBounds(bounds), {
    paddingTopLeft: [insets.left, insets.top],
    paddingBottomRight: [insets.right, insets.bottom],
    maxZoom: 17,
  });
  map.remove();
  element.remove();
  return { center: [center.lat, center.lng], zoom };
};

// Each area a step of the briefs flies to (OP DK's figures, the antimeridian's overview), and a point, on three maps: a tablet's beside its steps, a narrow
// window's, a small one; with the margins alone and with the card and the constants box over the map.
const AREAS = (() => {
  const seen = new Map();
  COMPILE_CASES.forEach((c) => {
    const model = compileCase(c);
    [...(model.steps ?? []).map((s) => s.figure?.bounds), overviewBounds(model)].forEach((b) => {
      if (Array.isArray(b) && !seen.has(JSON.stringify(b))) seen.set(JSON.stringify(b), b);
    });
  });
  return [...seen.values(), [[34.5501, -84.1102], [34.5501, -84.1102]]];
})();
const MAPS = [[980, 736], [845, 680], [600, 400]];
const VIEW_CASES = AREAS.flatMap((bounds) => MAPS.flatMap(([x, y]) => {
  const map = box(0, 0, x, y);
  const card = box(Math.max(8, (x - 780) / 2), y - Math.min(0.48 * y, 400) - 16, Math.min(x - 8, (x + 780) / 2), y - 16);
  const constants = box(x - 282, 12, x - 12, 204);
  return [fitInsets(map, []), fitInsets(map, [card, constants])].map((insets) => ({ bounds, size: [x, y], insets }));
}));

const viewCase = (c) => ({ ...c, leaflet: leafletView(c.bounds, c.size, c.insets) });

// Leaflet's projection runs through Math.sin, Math.log and Math.atan, whose last digit differs between machines (V8 on Apple silicon fuses multiply-adds that
// x86-64 rounds twice, AGENTS.md §11), so a centre is held to 1e-9 of a degree (a tenth of a millimetre), not to its last digit; everything else exactly.
const NEAR = 1e-9;
const withinNear = (made, recorded) => {
  if (typeof made === "number" && typeof recorded === "number") return Math.abs(made - recorded) <= NEAR ? recorded : made;
  if (Array.isArray(made) && Array.isArray(recorded) && made.length === recorded.length) return made.map((v, i) => withinNear(v, recorded[i]));
  if (made && recorded && typeof made === "object" && typeof recorded === "object" && !Array.isArray(made) && !Array.isArray(recorded)) {
    return Object.fromEntries(Object.entries(made).map(([k, v]) => [k, k in recorded ? withinNear(v, recorded[k]) : v]));
  }
  return made;
};

const document_ = () => ({
  generatedBy: "frontend/src/contracts/briefSandtableFitFixtures.test.js (UPDATE_CONTRACTS=1)",
  leaflet: L.version,
  insets: INSET_CASES.map(insetCase),
  views: VIEW_CASES.map(viewCase),
});

describe("brief/sandtable-fit.json", () => {
  if (UPDATE) {
    it("writes the fixture", () => {
      writeFixture(FIXTURE, `${compact(document_())}\n`);
    });
    return;
  }

  const fixture = readFixture(FIXTURE);
  const made = JSON.parse(JSON.stringify(document_()));

  it("is Leaflet's own answer, from the Leaflet the app runs", () => {
    expect(fixture.leaflet).toBe(L.version);
  });

  it("holds fitInsets for every case", () => {
    expect(fixture.insets).toEqual(made.insets);
  });

  it("holds Leaflet's view for every area, map and inset", () => {
    expect(fixture.views).toEqual(withinNear(made.views, fixture.views));
  });

  it("still sees a view that moved", () => {
    const moved = JSON.parse(JSON.stringify(fixture.views));
    moved[0].leaflet.center[0] += 1e-7;
    expect(withinNear(moved, fixture.views)).not.toEqual(fixture.views);
    moved[0].leaflet.center[0] -= 1e-7 - 1e-12;
    expect(withinNear(moved, fixture.views)).toEqual(fixture.views);
  });
});

describe("where the sandtable fits a figure", () => {
  it("snaps a fit to a tenth of a level, as the app's map does, and is never closer than 17", () => {
    VIEW_CASES.map(viewCase).forEach(({ leaflet }) => {
      expect(Math.abs(leaflet.zoom * 10 - Math.round(leaflet.zoom * 10))).toBeLessThan(1e-9);
      expect(leaflet.zoom).toBeLessThanOrEqual(17);
    });
  });

  it("frames a point at the closest it goes", () => {
    const point = viewCase({ bounds: [[34.5501, -84.1102], [34.5501, -84.1102]], size: [980, 736], insets: fitInsets(box(0, 0, 980, 736), []) });
    expect(point.leaflet.zoom).toBe(17);
  });
});
