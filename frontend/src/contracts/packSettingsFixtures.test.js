import { act, renderHook } from "@testing-library/react";
import { doghouseFieldUpdates, doghouseHeadingDegrees, doghouseHeadingText } from "../feature/doghouses/doghouseFields";
import { createDefaultDoghouses, useDoghouses } from "../feature/doghouses/useDoghouses";
import { useGoAround } from "../feature/goAround/useGoAround";
import { useHelicopters } from "../feature/helicopters/useHelicopters";
import {
  LZ_WORKSPACE_ACTIONS,
  createInitialLzWorkspace,
  createLzDiagramFromTarget,
  lzWorkspaceReducer,
} from "../feature/lzWorkspace/useLzWorkspace";
import { diffData, sameData } from "../feature/missionPacks/packDiff";
import { applyPackOp, validatePackOp } from "../feature/missionPacks/packOps";
import { lzDiagramFromItem, lzItemData } from "../feature/missionPacks/packLz";
import { packLocalId } from "../feature/missionPacks/packRef";
import { routeSetData, routesFromItem } from "../feature/missionPacks/packRoutes";
import { ROUTE_COLORS } from "../feature/msnxImport/colorPalette";
import { defaultRoutePlan } from "../feature/msnxImport/routeCalc";
import { mergeWindsIntoPlan } from "../feature/msnxImport/routeWinds";
import {
  appendAmpsPoint,
  buildSketchRoute,
  designatePoint,
  insertShapingPoint,
  movePoint,
  renamePoint,
  withPlanPatch,
  withPointClock,
  withPointOverride,
} from "../feature/msnxImport/sketchOps";
import { usePzMarker } from "../feature/pzMarker/usePzMarker";
import { useSectorsOfFire } from "../feature/sectorsOfFire/useSectorsOfFire";
import { UNIT_TYPES } from "../feature/unit/UnitIcons";
import { useUnit } from "../feature/unit/useUnit";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// Every setting of a planning graphic or a route the web makes, as the pack item it leaves behind: the item before and after the change,
// made by the web's own code (the graphics' hooks wired as App.js wires them, through the workspace's reducer; the sketch's operations as
// useRouteSketch applies them), and what the web's editor sends for it (packDiff.js). The native apps are held to reading each item as the
// web's editor does and to sending the same for the same change.

const GENERATED_BY = "frontend/src/contracts/packSettingsFixtures.test.js (UPDATE_CONTRACTS=1)";

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

const PACK = "6f1c2a9e-3b7d-4c55-9a10-2d8e5f7b4c31";
const LZ_ITEM = "b7e3c1a4-2d5f-4e6a-9b8c-0d1e2f3a4b5c";
const ROUTE_ITEM = "c8f4d2b5-3e6a-4f7b-8c9d-1e2f3a4b5c6d";

// The web names a new aircraft, PZ marker, sector, go-around and unit by the clock's milliseconds, and drops a unit a random few metres off the
// target. While the fixture is built the clock is stopped at `clock.now`, which each placement moves on, and chance is refused except where a
// case names its draw: the file comes out the same every time it is made.
const T0 = 1760364000000; // 2026-10-13T14:00:00Z
const clock = { now: T0 };
const refuseChance = () => {
  throw new Error("a settings case reached for chance: give it its draw");
};

const withStoppedClock = (build) => {
  const RealDate = global.Date;
  const random = Math.random;
  global.Date = new Proxy(RealDate, {
    construct: (target, args) => (args.length === 0 ? new target(clock.now) : new target(...args)),
    get: (target, key) => (key === "now" ? () => clock.now : Reflect.get(target, key)),
  });
  Math.random = refuseChance;
  clock.now = T0;
  try {
    return build();
  } finally {
    global.Date = RealDate;
    Math.random = random;
  }
};

/** What `run` returns with Math.random answering `draw`. */
const drawing = (draw, run) => {
  Math.random = () => draw;
  try {
    return run();
  } finally {
    Math.random = refuseChance;
  }
};

// -- An LZ/PZ -------------------------------------------------------------------------------------------------------------------------

/** One action of the workspace's reducer on [diagram], as App.jsx dispatches it for the diagram on the map. */
const reduce = (diagram, action) => {
  const state = createInitialLzWorkspace([diagram]);
  return lzWorkspaceReducer(state, { ...action, diagramId: diagram.id }).diagramsById[diagram.id];
};

const targetOf = (diagram) => [diagram.target.lat, diagram.target.lon];

// Each graphic's hook, given the diagram's collection and App.jsx's setters for it.
const useHelicoptersOf = (d, set) => useHelicopters(targetOf(d), { helicopters: d.graphics.helicopters, setHelicopters: set });
const usePzMarkersOf = (d, set) => usePzMarker(targetOf(d), { pzMarker: d.graphics.pzMarkers, setPzMarkers: set });
const useSectorsOf = (d, set) => useSectorsOfFire(targetOf(d), { sectorsOfFire: d.graphics.sectorsOfFire, setSectors: set });
const useGoAroundsOf = (d, set) => useGoAround(targetOf(d), { goAround: d.graphics.goArounds, setGoAround: set });
const useUnitsOf = (d, set) => useUnit(targetOf(d), { units: d.graphics.units, setUnits: set });
const useDoghousesOf = (d, set, setFlightData) =>
  useDoghouses(targetOf(d), setFlightData, { doghouses: d.graphics.doghouses, setDoghouses: set });

/**
 * [diagram] once [call] has been made on the hook that edits [collection], wired as App.jsx wires it: what the hook sets goes through the
 * reducer (setGraphicCollection, and setFlightData for the doghouses), and the hook is drawn again with the result, so an effect that follows
 * it (the flight data following the doghouses) runs as it does in the app.
 */
const viaHook = (diagram, collection, useHook, call) => {
  let current = diagram;
  const setCollection = (next) => {
    const items = typeof next === "function" ? next(current.graphics?.[collection] ?? []) : next;
    current = reduce(current, { type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION, collection, items });
  };
  const setFlightData = (next) => {
    const flightData = typeof next === "function" ? next(current.flightData ?? {}) : next;
    current = reduce(current, { type: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA, flightData });
  };
  const { result, rerender, unmount } = renderHook(() => useHook(current, setCollection, setFlightData));
  act(() => call(result.current, current));
  rerender();
  unmount();
  return current;
};

/** [diagram] with what the web gives a placement made now, the clock then moved on: each graphic its own millisecond. */
const placing = (diagram, collection, useHook, call) => {
  const placed = viaHook(diagram, collection, useHook, call);
  clock.now += 1;
  return placed;
};

const graphic = (diagram, collection, index = 0) => diagram.graphics[collection][index];

const RING = [[34.7841, -84.0826], [34.7843, -84.0817], [34.7835, -84.0815], [34.7833, -84.0824]];
const DRAWN = [[34.7845, -84.0829], [34.7846, -84.0813], [34.7831, -84.0811], [34.7829, -84.0827]];

/** What useTerrain hands handleAnalysisComplete: the server's answer (as /api/analyze-field gives it) and what is made of it. */
const analysisOf = (boundary, elevation) => ({
  latLong: "34°47'01.7\"N 84°04'55.9\"W",
  gridElevation: elevation,
  customLZ: null,
  detectedLZ: boundary,
  results: { elevation, message: "Field detected", status: "success", suggested_lz: RING },
});

/**
 * App.jsx's handleAnalysisComplete: the analysis completes, the standard doghouses are made once, for a diagram that has none, and the flight
 * data then follows them (useDoghouses's effect).
 */
const analysed = (diagram, analysis) => {
  let next = reduce(diagram, { type: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS, analysis });
  if (!diagram.graphics?.doghouses?.length) {
    next = reduce(next, {
      type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
      collection: "doghouses",
      items: createDefaultDoghouses(targetOf(diagram), diagram.id),
    });
  }
  return viaHook(next, "doghouses", useDoghousesOf, () => {});
};

/** The patch PZMarker.jsx's anchor dragend sends: the anchor where it was dropped and the tip moved with it. */
const pzAnchorDragged = (marker, lat, lon) => ({
  lat,
  lon,
  tipLat: (marker.tipLat || marker.lat) + (lat - marker.lat),
  tipLon: (marker.tipLon || marker.lon - 0.001) + (lon - marker.lon),
});

/** The steps of one LZ/PZ in a pack, each [name, before, after] as diagrams. */
const lzSteps = () => {
  const steps = [];
  const step = (name, before, after) => {
    steps.push([name, before, after]);
    return after;
  };

  const targeted = createLzDiagramFromTarget({
    target: [34.783817, -84.08219],
    mgrs: "16S GD 66993 52949",
    id: packLocalId(PACK, LZ_ITEM),
    name: "LZ HAWK",
  });
  let d = step("the first analysis: what it found, the standard doghouses, and the flight data they give", targeted, analysed(targeted, analysisOf(RING, "4050")));

  d = step("an aircraft is placed", d, placing(d, "helicopters", useHelicoptersOf, (h) => h.addHelo()));
  d = step("a second aircraft is placed, clear of the first", d, placing(d, "helicopters", useHelicoptersOf, (h) => h.addHelo()));
  d = step("a PZ marker is placed", d, placing(d, "pzMarkers", usePzMarkersOf, (h) => h.addPZMarker()));
  d = step("a sector of fire is placed", d, placing(d, "sectorsOfFire", useSectorsOf, (h) => h.addSectorOfFire()));
  d = step("a go-around is placed", d, placing(d, "goArounds", useGoAroundsOf, (h) => h.addGoAround("left")));
  d = step("a unit is placed from a preset", d, drawing(0.4, () => placing(d, "units", useUnitsOf, (h) => h.addUnit(UNIT_TYPES[0]))));
  const full = d;

  const helo = graphic(full, "helicopters", 1);
  step("an aircraft is dragged", full, viaHook(full, "helicopters", useHelicoptersOf, (h) => h.updateHelicopter(helo.id, { lat: 34.78402, lon: -84.08195 })));
  step("an aircraft is turned", full, viaHook(full, "helicopters", useHelicoptersOf, (h) => h.updateHelicopter(helo.id, { rotation: 287.40123 })));
  step("an aircraft is removed", full, viaHook(full, "helicopters", useHelicoptersOf, (h) => h.deleteHelicopter(helo.id)));

  const pz = graphic(full, "pzMarkers");
  step("a PZ marker is dragged by its anchor, its tip with it", full, viaHook(full, "pzMarkers", usePzMarkersOf, (h) => h.updatePZMarker(pz.id, pzAnchorDragged(pz, 34.78352, -84.08305))));
  step("a PZ marker's tip is dragged", full, viaHook(full, "pzMarkers", usePzMarkersOf, (h) => h.updatePZMarker(pz.id, { tipLat: 34.78421, tipLon: -84.08398 })));
  step("a PZ marker is removed", full, viaHook(full, "pzMarkers", usePzMarkersOf, (h) => h.deletePZMarker(pz.id)));

  const sector = graphic(full, "sectorsOfFire");
  step("a sector's corner is dragged", full, viaHook(full, "sectorsOfFire", useSectorsOf, (h) => h.updateSectorOfFirePoint(sector.id, 1, 34.78301, -84.08088)));
  step("a sector is dragged", full, viaHook(full, "sectorsOfFire", useSectorsOf, (h) => h.moveSectorOfFire(sector.id, 0.00042, -0.00031)));
  step("a sector is removed", full, viaHook(full, "sectorsOfFire", useSectorsOf, (h) => h.deleteSectorOfFire(sector.id)));

  const ga = graphic(full, "goArounds");
  step("a go-around is dragged", full, viaHook(full, "goArounds", useGoAroundsOf, (h) => h.updateGoAround(ga.id, { lat: 34.78251, lon: -84.08236 })));
  step("a go-around is turned", full, viaHook(full, "goArounds", useGoAroundsOf, (h) => h.updateGoAround(ga.id, { rotation: 93.5 })));
  step("a go-around is removed", full, viaHook(full, "goArounds", useGoAroundsOf, (h) => h.deleteGoAround(ga.id)));

  // Doghouse.jsx: a typed field becomes doghouseFieldUpdates; a typed heading and a turn become the heading's text. The flight data follows.
  const [sp, rp] = full.graphics.doghouses;
  const doghouse = (name, id, changes) => step(name, full, viaHook(full, "doghouses", useDoghousesOf, (h) => h.updateDoghouse(id, changes)));
  doghouse("a doghouse's label is typed", sp.id, doghouseFieldUpdates("id", "[SP2]"));
  doghouse("a doghouse's minutes are typed", sp.id, doghouseFieldUpdates("time-m", "03", { minutes: "03", seconds: "57" }));
  doghouse("a doghouse's distance is typed", rp.id, doghouseFieldUpdates("dist", "6.4"));
  doghouse("a doghouse's airspeed is typed", rp.id, doghouseFieldUpdates("airspeed", "80"));
  doghouse("a doghouse's heading is typed, and the takeoff heading follows it", sp.id, { heading: doghouseHeadingText(doghouseHeadingDegrees("95")) });
  doghouse("a doghouse is turned, and the landing heading follows it", rp.id, { heading: `${Math.round(271.6).toString().padStart(3, "0")}°` });
  doghouse("a doghouse is dragged", rp.id, { lat: 34.78203, lon: -84.08561 });

  const unit = graphic(full, "units");
  step("a unit is dragged", full, viaHook(full, "units", useUnitsOf, (h) => h.updateUnitPosition(unit.id, 34.78461, -84.08133)));
  step(
    "the unit builder is applied to a unit",
    full,
    viaHook(full, "units", useUnitsOf, (h) => h.updateUnit(unit.id, { sidc: "SHGPUCI----D---", uniqueDesignation: "B/2-75", higherFormation: "75 RGR" })),
  );
  step("a unit is removed", full, viaHook(full, "units", useUnitsOf, (h) => h.deleteUnit(unit.id)));

  step("the flight data is typed", full, reduce(full, { type: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA, flightData: { callSign: "HAWK 7", frequency: "251.0" } }));
  step("the view is each person's own: nothing is sent", full, reduce(full, { type: LZ_WORKSPACE_ACTIONS.SET_VIEW, view: { mapStyle: "vfr-sectional", showLZOutline: false, showHeatmap: true } }));

  // App.jsx's toggleDrawingMode: starting resets an analysed diagram and clears the drawn boundary; finishing keeps three corners or more.
  const drawing0 = reduce(reduce(full, { type: LZ_WORKSPACE_ACTIONS.RESET_ANALYSIS }), { type: LZ_WORKSPACE_ACTIONS.SET_ANALYSIS_DRAFT, analysis: { customLZ: null } });
  d = step("drawing a boundary starts: the analysis is reset and its graphics kept", full, drawing0);
  d = step("the boundary drawn", d, reduce(d, { type: LZ_WORKSPACE_ACTIONS.SET_ANALYSIS_DRAFT, analysis: { customLZ: DRAWN } }));
  // useTerrain analyses the drawn boundary in place of the one the model would find; the doghouses are there already, so none are made.
  step("analysed again, of the boundary drawn", d, analysed(d, { ...analysisOf(DRAWN, "4060") }));
  return steps;
};

/** The item the web's editor makes of [data] once it holds it: what anyone opening the item after the change has. */
const lzShape = (data) => lzItemData(lzDiagramFromItem(PACK, { uuid: LZ_ITEM, name: "LZ HAWK", data }));

// -- A set of routes ---------------------------------------------------------------------------------------------------------------------

const SET_ID = packLocalId(PACK, ROUTE_ITEM);

/** The routes as the sketch keeps a pack item's: restored (useRouteSketch's restoreSketchRoute) in the item's set. */
const editorRoutes = (data) => routesFromItem({ uuid: ROUTE_ITEM, data }, SET_ID);

/** The item the web's editor makes of [data] once it holds it. */
const routeShape = (data) => routeSetData(editorRoutes(data));

/** useRouteSketch's updaters: `prev.map((route) => (route.id === id ? change(route) : route))`. */
const onRoute = (routes, id, change) => routes.map((route) => (route.id === id ? change(route) : route));

/** The steps of one set of routes in a pack, each [name, before, after] as the sketch's routes. */
const routeSteps = () => {
  let ids = 0;
  // The web names a new point with crypto.randomUUID(): here, in order.
  const newPointId = () => `pt-${String((ids += 1)).padStart(2, "0")}`;
  const steps = [];
  const step = (name, before, after) => {
    steps.push([name, before, after]);
    // What the editor has once the pack holds the change.
    return editorRoutes(routeSetData(after));
  };

  const blue = buildSketchRoute(
    [{ lat: 34.783817, lon: -84.08219 }, { lat: 34.6512, lon: -84.2931 }],
    { name: "BLUE 1", id: "sketch-1760363000000-a8d2kq", color: ROUTE_COLORS[1], plan: defaultRoutePlan(), newPointId },
  );
  const start = editorRoutes(routeSetData([{ ...blue, setId: SET_ID }]));

  // finishSketch: the standard attack profile on five points drawn, in the set on the map.
  const red = buildSketchRoute(
    [
      { lat: 34.6512, lon: -84.2931 },
      { lat: 34.6907, lon: -84.2405 },
      { lat: 34.7214, lon: -84.1822 },
      { lat: 34.7601, lon: -84.1276 },
      { lat: 34.783817, lon: -84.08219 },
    ],
    { name: "RED 1", id: "sketch-1760364000000-k3j9qa", color: ROUTE_COLORS[0], plan: defaultRoutePlan(), newPointId },
  );
  let routes = step("a route is drawn", start, [...start, { ...red, setId: SET_ID }]);
  const R = red.id;
  const [p1, , p3, p4, p5] = red.points.map((point) => point.id);

  routes = step(
    "a point named for a local point is put at the end of a route, with its charted elevation",
    routes,
    onRoute(routes, R, (route) => appendAmpsPoint(route, 34.7023, -84.2011, { name: "ALPHA", ptType: "turn", chartElevationFt: 1730 }, newPointId)),
  );
  routes = step("a shaping point is put on the line", routes, onRoute(routes, R, (route) => insertShapingPoint(route, 34.7101, -84.2049, newPointId)));
  // The five points drawn are Target, IP (.SP), shaping, IP (.RP) and Target.
  routes = step("a shaping point is made a route point", routes, onRoute(routes, R, (route) => designatePoint(route, p3, { kind: "amps", ptType: "turn", name: "ACP2" })));
  routes = step("a route point is made a shaping point", routes, onRoute(routes, R, (route) => designatePoint(route, p4, { kind: "shaping" })));
  routes = step("a point is renamed", routes, onRoute(routes, R, (route) => renamePoint(route, p3, "ACP3")));
  routes = step(
    "a point is renamed to a local point's name, and put on it with its charted elevation",
    routes,
    onRoute(routes, R, (route) => renamePoint(route, p3, "BRAVO", { lat: 34.7219, lon: -84.1815 }, 1415)),
  );
  routes = step("a point is dragged off the local point", routes, onRoute(routes, R, (route) => movePoint(route, p3, 34.7226, -84.1809)));

  // RoutePlanSection.jsx's inputs: a route value merged into its plan, a point's value written whole from what the field showed.
  routes = step("the route's date is set", routes, onRoute(routes, R, (route) => withPlanPatch(route, { date: "2026-10-14" })));
  routes = step("the route's temperature is typed", routes, onRoute(routes, R, (route) => withPlanPatch(route, { tempC: 22 })));
  routes = step("the route's fuel flow is typed", routes, onRoute(routes, R, (route) => withPlanPatch(route, { fuelFlowLbHr: 1010 })));

  const own = (route, id, field) => route.plan.perPoint?.[id]?.[field] || route.plan[field];
  const point = (name, id, values) => {
    routes = step(name, routes, onRoute(routes, R, (route) => withPointOverride(route, id, values(route))));
  };
  point("a point's altitude is typed", p3, (route) => ({ altitude: { ref: own(route, p3, "altitude").ref, value: 300 } }));
  point("a point's altitude reference is chosen", p3, (route) => ({ altitude: { value: own(route, p3, "altitude").value, ref: "msl" } }));
  point("a point's speed is typed", p3, (route) => ({ airspeed: { type: own(route, p3, "airspeed").type, value: 110 } }));
  point("a point's speed type is chosen", p3, (route) => ({ airspeed: { value: own(route, p3, "airspeed").value, type: "indicated" } }));
  point("a point's wind direction is typed", p3, (route) => ({ wind: { speedKts: own(route, p3, "wind").speedKts, dirTrue: 250 } }));
  point("a point's wind speed is typed", p3, (route) => ({ wind: { dirTrue: own(route, p3, "wind").dirTrue, speedKts: 15 } }));
  point("another point is given its first altitude", p5, (route) => ({ altitude: { ref: own(route, p5, "altitude").ref, value: 150 } }));

  routes = step("the clock is set at a point", routes, onRoute(routes, R, (route) => withPointClock(route, p1, "14:30:00")));
  routes = step("the clock is moved to another point", routes, onRoute(routes, R, (route) => withPointClock(route, p3, "14:45:00")));
  routes = step("the clock is cleared", routes, onRoute(routes, R, (route) => withPointClock(route, p3, "")));
  routes = step("a point's own values are cleared", routes, onRoute(routes, R, (route) => withPointOverride(route, p5, null)));

  // useRouteSketch's applyForecastWinds and refreshRouteElevations, with what the services answered.
  const winds = { [p1]: { dirTrue: 270, speedKts: 12, tempC: 18 }, [p3]: { dirTrue: 280, speedKts: 14, tempC: 17 } };
  routes = step(
    "winds are fetched for the route",
    routes,
    onRoute(routes, R, (route) => ({ ...route, plan: mergeWindsIntoPlan(route.plan, route.points.filter((p) => p.kind === "amps"), winds) })),
  );
  routes = step(
    "ground elevations are fetched for the route",
    routes,
    onRoute(routes, R, (route) => ({ ...route, elevations: { ...route.elevations, [p1]: 1180, [p3]: 1415, [p5]: 4050 } })),
  );
  step("a route hidden is each person's own: nothing is sent", routes, onRoute(routes, R, (route) => ({ ...route, visible: !route.visible })));
  step("a route is removed", routes, routes.filter((route) => route.id !== R));
  return steps;
};

// -- The fixture ---------------------------------------------------------------------------------------------------------------------

/** Applies [ops] to [data] as the server would, insisting each one is well formed and applies. */
const applyAll = (kind, data, ops) => {
  let items = { it: { kind, name: "IT", data: JSON.parse(JSON.stringify(data)), deleted: false } };
  ops.forEach((op, i) => {
    const full = { ...op, item: "it" };
    expect([i, validatePackOp(full)]).toEqual([i, null]);
    const result = applyPackOp(items, full);
    expect([i, op.type, result.status, result.reason]).toEqual([i, op.type, "applied", null]);
    items = result.items;
  });
  return items.it.data;
};

const caseOf = (kind) => ([name, before, after]) => {
  const data = (version) => (kind === "lz" ? lzItemData(version) : routeSetData(version));
  const was = data(before);
  const now = data(after);
  return { name, kind, before: was, after: now, ops: JSON.parse(JSON.stringify(diffData(was, now))) };
};

const shapeOf = (c) => (c.kind === "lz" ? lzShape : routeShape);

const settingsFixture = () => withStoppedClock(() => ({
  description: "Every setting of a planning graphic or a route the web makes in a mission pack, as the item it leaves: `before` is the item's "
    + "data as the web's editor holds it (its baseline), `after` the editor's version once the setting is made, made by the web's own code (an "
    + "LZ/PZ's graphics through their hooks wired as App.js wires them, through the workspace's reducer, the flight data following the doghouses; "
    + "a route set's routes through sketchOps.js as useRouteSketch applies them, a point's values written whole from what RoutePlanSection.jsx's "
    + "field showed), and `ops` what the web sends for it (packDiff.js diffData(before, after), applied with the rules of packs/ops.json). "
    + "`before` and `after` are each also what the web's editor makes of an item holding them once it opens it (lzItemData(lzDiagramFromItem(...)), "
    + "or routeSetData(routesFromItem(...)); checked when this file is written). `kind` is \"lz\" or \"route\"; the pack is " + PACK + ", the "
    + "LZ/PZ item " + LZ_ITEM + " and the route set " + ROUTE_ITEM + ". A port reads `before` and `after` into its editor and writes each back "
    + "unchanged, finds `ops` for the change, and whatever it sends instead (the same, kept whole where the web sends a part) rebuilds `after` "
    + "from `before`. "
    + "The cases build on each other in order: an LZ/PZ is analysed, then given one graphic of each kind, then each setting of each is made "
    + "from there; a route is drawn beside another, then each setting is made in turn. A view or a route hidden is each person's own, and "
    + "sends nothing. Ids made from the clock are its milliseconds from 2026-10-13T14:00:00Z, one each; a new point's id (the web's "
    + "crypto.randomUUID()) is numbered in order, and a unit dropped near the target drew 0.4.",
  generatedBy: GENERATED_BY,
  cases: [...lzSteps().map(caseOf("lz")), ...routeSteps().map(caseOf("route"))],
}));

describe("mission pack settings fixture", () => {
  it("packs/settings.json", () => settle("packs/settings.json", settingsFixture()));

  it("rebuilds every case's `after` from its operations, and sends nothing exactly when nothing that is shared changed", () => {
    settingsFixture().cases.forEach((c) => {
      expect([c.name, sameData(applyAll(c.kind, c.before, c.ops), c.after)]).toEqual([c.name, true]);
      expect([c.name, c.ops.length === 0]).toEqual([c.name, sameData(c.before, c.after)]);
    });
  });

  it("holds `before` and `after` in the shape the web's editor gives an item it opens, so the operations are the setting's alone", () => {
    settingsFixture().cases.forEach((c) => {
      expect([c.name, shapeOf(c)(c.before)]).toEqual([c.name, c.before]);
      expect([c.name, shapeOf(c)(c.after)]).toEqual([c.name, c.after]);
    });
  });

  it("covers every setting, each kind of graphic, and both kinds of item", () => {
    const { cases } = settingsFixture();
    const lz = cases.filter((c) => c.kind === "lz");
    const routes = cases.filter((c) => c.kind === "route");
    expect(lz.length).toBeGreaterThanOrEqual(30);
    expect(routes.length).toBeGreaterThanOrEqual(25);
    const placed = lz.find((c) => c.name === "a unit is placed from a preset").after.graphics;
    ["helicopters", "pzMarkers", "sectorsOfFire", "goArounds", "units"].forEach((collection) => expect(placed[collection]).toHaveLength(collection === "helicopters" ? 2 : 1));
    expect(cases.filter((c) => c.ops.length === 0).map((c) => c.name)).toEqual([
      "the view is each person's own: nothing is sent",
      "a route hidden is each person's own: nothing is sent",
    ]);
    const names = cases.map((c) => c.name);
    expect(new Set(names).size).toBe(names.length);
  });
});
