import { buildSketchRoute, designatePoint, movePoint, insertShapingPoint, appendAmpsPoint, withPlanPatch, withPointOverride, withPointClock, renamePoint, autoDesignation } from "../feature/msnxImport/sketchOps";
import { defaultRoutePlan } from "../feature/msnxImport/routeCalc";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What can be done to a sketched route (the web's `sketchOps`, which are the bodies of `useRouteSketch`'s updaters), run over the cases that are
// fragile: the automatic attack profile for every length of route, a designation that is only half there, demoting the last two AMPS points
// (refused), a click that is nearest to the first and to the last leg, a clock moved from one point to another. Every case is the operation, its
// inputs and what the web makes of them; the native apps are held to all of them. Ids and colours are the caller's, so they are fixed here.
// A key the web leaves undefined is absent, as it is in the saved JSON.

const GENERATED_BY = "frontend/src/contracts/sketchFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));

// Point ids are a counter: p1, p2, ... for a route being built, and n1, n2, ... for a point added to one (so it never reuses an id the route has).
const ids = (prefix = "p") => {
  let n = 0;
  return () => `${prefix}${++n}`;
};

const settle = (name, document) => {
  const file = fixturePath(name);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

const CH47 = {
  slug: "ch47f", name: "CH-47F Chinook", icon_key: "ch47", rotor_diameter_m: 18.29, rotor_tip_clearance_m: 75,
  default_airspeed_kts: 130, default_airspeed_type: "indicated", max_indicated_kts: 170, default_altitude_ft: 300, default_altitude_ref: "msl",
  default_fuel_flow_lb_hr: 3000, default_gross_weight_lb: 40000,
};

const pt = (i, designation) => ({ lat: 34.7 + i * 0.01, lon: -84.1 + i * 0.01, designation: designation ?? null });
const draft = (n, designations = {}) => Array.from({ length: n }, (_, i) => pt(i, designations[i]));

const build = (draftPoints, { name = "ROUTE 1", plan = defaultRoutePlan(null) } = {}) =>
  buildSketchRoute(draftPoints, { name, id: "sketch-1", color: "#FF453A", plan, newPointId: ids() });

// The routes the operation cases start from.
const FIVE = build(draft(5));                                                    // target, ip, shaping, ip, target
const SIX = build(draft(6));                                                     // target, ip, two shaping, ip, target
const THREE_AMPS = build(draft(4, { 1: { ptType: "turn", name: ".CP1" } }));     // target, turn, shaping... (the auto profile makes #1 an ip; the designation wins)
const TWO_AMPS = build(draft(2));
const PLANNED = (() => {
  let route = withPointOverride(FIVE, "p2", { altitude: { value: 200, ref: "agl" }, airspeed: { value: 90, type: "indicated" } });
  route = withPointOverride(route, "p3", { wind: { dirTrue: 270, speedKts: 15 } });
  return withPointClock(route, "p4", "12:30:00");
})();

// A clock anchor that also has another override of its own, to see which of them survive when the anchor moves.
const PLANNED_BOTH = withPointClock(withPointOverride(FIVE, "p2", { altitude: { value: 150, ref: "msl" } }), "p2", "12:30:00");
// A route whose first leg has no length (its two points are in the same place).
const DUPED = { ...FIVE, points: [FIVE.points[0], { ...FIVE.points[1], lat: FIVE.points[0].lat, lon: FIVE.points[0].lon }, ...FIVE.points.slice(2)] };

const op = (name, route, args, result) => ({ name, route: saved(route), args: saved(args), expected: saved(result) });

const sketchFixture = () => ({
  generatedBy: GENERATED_BY,
  auto: [[0, 1], [1, 1], [0, 2], [1, 2], [2, 2], [0, 3], [1, 3], [2, 3], [3, 3], [2, 5], [3, 5], [4, 5], [5, 5]].map(([index, lastIndex]) => ({
    index, lastIndex, expected: autoDesignation(index, lastIndex),
  })),
  build: [
    ["two points", draft(2)],
    ["three points", draft(3)],
    ["four points", draft(4)],
    ["five points", draft(5)],
    ["a designation set while drawing wins over the automatic one", draft(5, { 0: { ptType: "ip", name: ".LZ" }, 2: { ptType: "target", name: ".OBJ", chartElevationFt: 1320 } })],
    ["a designation with no type is a turn point, and with no name is named for its place", draft(5, { 0: {}, 2: {}, 3: { ptType: "ip" } })],
    ["a designation with an empty name falls back too", draft(4, { 2: { ptType: "turn", name: "" } })],
    ["and an empty type is a turn point", draft(4, { 1: { ptType: "", name: ".X" } })],
  ].map(([label, points]) => ({ label, draft: points, plan: null, expected: saved(build(points)) }))
    .concat([{ label: "an aircraft's own plan defaults", draft: draft(3), plan: CH47, expected: saved(build(draft(3), { plan: defaultRoutePlan(CH47) })) }])
    .concat([{ label: "fewer than two points is no route", draft: draft(1), plan: null, expected: null }, { label: "no points is no route", draft: [], plan: null, expected: null }]),
  designate: [
    op("a shaping point becomes a point named for the route's default", FIVE, { pointId: "p3", spec: { kind: "amps" } }, designatePoint(FIVE, "p3", { kind: "amps" })),
    op("with a type and a name", FIVE, { pointId: "p3", spec: { kind: "amps", ptType: "target", name: ".OBJ" } }, designatePoint(FIVE, "p3", { kind: "amps", ptType: "target", name: ".OBJ" })),
    op("an empty name is kept as an empty name", FIVE, { pointId: "p3", spec: { kind: "amps", ptType: "turn", name: "" } }, designatePoint(FIVE, "p3", { kind: "amps", ptType: "turn", name: "" })),
    op("an AMPS point changes type and keeps its name", FIVE, { pointId: "p4", spec: { kind: "amps", ptType: "turn" } }, designatePoint(FIVE, "p4", { kind: "amps", ptType: "turn" })),
    op("the start stays the start", FIVE, { pointId: "p1", spec: { kind: "amps", ptType: "ip", name: ".SP" } }, designatePoint(FIVE, "p1", { kind: "amps", ptType: "ip", name: ".SP" })),
    op("with three AMPS points one can go back to shaping", FIVE, { pointId: "p4", spec: { kind: "shaping" } }, designatePoint(FIVE, "p4", { kind: "shaping" })),
    op("the last two AMPS points are refused: the route comes back as it was", TWO_AMPS, { pointId: "p2", spec: { kind: "shaping" } }, designatePoint(TWO_AMPS, "p2", { kind: "shaping" })),
    op("a shaping point being made shaping again is not refused", SIX, { pointId: "p3", spec: { kind: "shaping" } }, designatePoint(SIX, "p3", { kind: "shaping" })),
    op("a new name keeps the point's type", FIVE, { pointId: "p2", spec: { kind: "amps", name: ".NEW" } }, designatePoint(FIVE, "p2", { kind: "amps", name: ".NEW" })),
    op("the start can be made shaping, and is then a waypoint", FIVE, { pointId: "p1", spec: { kind: "shaping" } }, designatePoint(FIVE, "p1", { kind: "shaping" })),
    op("a point nobody knows changes nothing", FIVE, { pointId: "nope", spec: { kind: "amps" } }, designatePoint(FIVE, "nope", { kind: "amps" })),
  ],
  move: [
    op("a drag", FIVE, { pointId: "p3", lat: 34.75, lon: -84.2, chartElevationFt: null }, movePoint(FIVE, "p3", 34.75, -84.2, undefined)),
    op("a drag onto a local point carries its charted elevation", FIVE, { pointId: "p3", lat: 34.75, lon: -84.2, chartElevationFt: 1180 }, movePoint(FIVE, "p3", 34.75, -84.2, 1180)),
    op("a drag off it drops the charted elevation", movePoint(FIVE, "p3", 34.75, -84.2, 1180), { pointId: "p3", lat: 34.76, lon: -84.21, chartElevationFt: null }, movePoint(movePoint(FIVE, "p3", 34.75, -84.2, 1180), "p3", 34.76, -84.21, undefined)),
  ],
  insert: [
    ["between the first two", 34.705, -84.095],
    ["between the middle two", 34.725, -84.075],
    ["between the last two", 34.745, -84.055],
    ["far from everything, it still goes in the nearest leg", 35.5, -83.0],
    ["near an existing point, the nearer leg wins", 34.71, -84.09],
  ].map(([label, lat, lon]) => op(label, FIVE, { lat, lon }, insertShapingPoint(FIVE, lat, lon, ids("n"))))
    .concat([op("exactly on an existing point, the first of the legs that meet there wins", FIVE, { lat: FIVE.points[1].lat, lon: FIVE.points[1].lon }, insertShapingPoint(FIVE, FIVE.points[1].lat, FIVE.points[1].lon, ids("n")))])
    .concat([op("a leg with no length still counts", DUPED, { lat: DUPED.points[0].lat, lon: DUPED.points[0].lon }, insertShapingPoint(DUPED, DUPED.points[0].lat, DUPED.points[0].lon, ids("n")))])
    .concat([op("a route of one point has no leg to split", { ...FIVE, points: FIVE.points.slice(0, 1) }, { lat: 34.7, lon: -84.1 }, insertShapingPoint({ ...FIVE, points: FIVE.points.slice(0, 1) }, 34.7, -84.1, ids("n")))]),
  append: [
    op("a plain point", FIVE, { lat: 34.8, lon: -84.0, options: {} }, appendAmpsPoint(FIVE, 34.8, -84.0, {}, ids("n"))),
    op("a named local point carries its elevation", FIVE, { lat: 34.8, lon: -84.0, options: { name: ".CAMP", ptType: "ip", chartElevationFt: 990 } }, appendAmpsPoint(FIVE, 34.8, -84.0, { name: ".CAMP", ptType: "ip", chartElevationFt: 990 }, ids("n"))),
  ],
  plan: [
    op("a patch merges over the plan", FIVE, { patch: { airspeed: { value: 120, type: "true" }, date: "2026-10-03" } }, withPlanPatch(FIVE, { airspeed: { value: 120, type: "true" }, date: "2026-10-03" })),
    op("a route with no plan takes the defaults", { ...FIVE, plan: undefined }, { patch: { tempC: 5 } }, withPlanPatch({ ...FIVE, plan: undefined }, { tempC: 5 })),
    op("an override is set", FIVE, { pointId: "p2", patch: { altitude: { value: 100, ref: "msl" } } }, withPointOverride(FIVE, "p2", { altitude: { value: 100, ref: "msl" } })),
    op("an override merges with what the point already had", PLANNED, { pointId: "p2", patch: { wind: { dirTrue: 90, speedKts: 5 } } }, withPointOverride(PLANNED, "p2", { wind: { dirTrue: 90, speedKts: 5 } })),
    op("null clears the point's overrides, its clock too", PLANNED, { pointId: "p4", patch: null }, withPointOverride(PLANNED, "p4", null)),
    op("a clock is set on a point", FIVE, { pointId: "p2", clock: "09:15" }, withPointClock(FIVE, "p2", "09:15")),
    op("setting a clock moves it: the old anchor loses its clock and keeps its other overrides", PLANNED, { pointId: "p2", clock: "13:00:00" }, withPointClock(PLANNED, "p2", "13:00:00")),
    op("an empty clock clears the anchor, and a point left with nothing is dropped", PLANNED, { pointId: "p2", clock: "" }, withPointClock(PLANNED, "p2", "")),
    op("moving the anchor keeps the old anchor's other overrides", PLANNED_BOTH, { pointId: "p3", clock: "14:00" }, withPointClock(PLANNED_BOTH, "p3", "14:00")),
    op("a clock on a point that has overrides keeps them", PLANNED, { pointId: "p3", clock: "10:00" }, withPointClock(PLANNED, "p3", "10:00")),
  ],
  rename: [
    op("a name", FIVE, { pointId: "p2", name: ".IP1", coords: null, chartElevationFt: null }, renamePoint(FIVE, "p2", ".IP1", null, undefined)),
    op("snapped to a local point's place and elevation", FIVE, { pointId: "p2", name: ".CAMP", coords: { lat: 34.9, lon: -84.3 }, chartElevationFt: 1500 }, renamePoint(FIVE, "p2", ".CAMP", { lat: 34.9, lon: -84.3 }, 1500)),
    op("snapped with no elevation", FIVE, { pointId: "p2", name: ".CAMP", coords: { lat: 34.9, lon: -84.3 }, chartElevationFt: null }, renamePoint(FIVE, "p2", ".CAMP", { lat: 34.9, lon: -84.3 }, undefined)),
  ],
  routes: { five: saved(FIVE), six: saved(SIX), threeAmps: saved(THREE_AMPS), twoAmps: saved(TWO_AMPS), planned: saved(PLANNED), plannedBoth: saved(PLANNED_BOTH), duped: saved(DUPED) },
});

describe("sketched route operations", () => {
  it("are what the fixture says (and the committed fixture is current)", () => {
    settle("routes/sketch.json", sketchFixture());
  });

  it("start from the routes the fixture lists", () => {
    expect(FIVE.points.map((p) => [p.kind, p.ptType, p.name])).toEqual([
      ["amps", "target", ".TGT"], ["amps", "ip", ".SP"], ["shaping", null, ""], ["amps", "ip", ".RP"], ["amps", "target", ".TGT"],
    ]);
    expect(PLANNED.plan.perPoint).toEqual({
      p2: { altitude: { value: 200, ref: "agl" }, airspeed: { value: 90, type: "indicated" } },
      p3: { wind: { dirTrue: 270, speedKts: 15 } },
      p4: { clock: "12:30:00" },
    });
  });
});
