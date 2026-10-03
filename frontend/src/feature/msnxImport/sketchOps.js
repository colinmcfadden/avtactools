import { findNearestAdjacentIndex } from "./mutateMsnx";
import { defaultRoutePlan } from "./routeCalc";

/*
 * What can be done to a sketched route, as pure functions: each takes a route and
 * returns the route it becomes (the same object when nothing changes). They were
 * the bodies of `useRouteSketch`'s state updaters; they are separate so the native
 * apps can be held to the same results (contracts/fixtures/routes/sketch.json).
 * Anything random or clock-dependent (point ids, colours) is passed in.
 */

/**
 * The standard attack profile applied to a finished sketch: the first two points
 * are Target then IP and the last two RP (IP) then Target; everything in the middle
 * is shaping (null).
 */
export const autoDesignation = (index, lastIndex) => {
  if (index === 0) return { ptType: "target", name: ".TGT" };
  if (index === lastIndex) return { ptType: "target", name: ".TGT" };
  if (index === 1) return { ptType: "ip", name: ".SP" };
  if (index === lastIndex - 1) return { ptType: "ip", name: ".RP" };
  return null;
};

/**
 * A route from the points drawn so far ({ lat, lon, designation }), or null with
 * fewer than two. A designation set while drawing (right-click menu, or "+" from a
 * local point) always wins over the automatic one.
 */
export const buildSketchRoute = (draftPoints, { name, id, color, plan, newPointId }) => {
  if (draftPoints.length < 2) return null;
  const lastIndex = draftPoints.length - 1;
  return {
    id,
    name,
    color,
    visible: true,
    plan,
    elevations: {},
    points: draftPoints.map((p, i) => {
      const base = {
        id: newPointId(),
        lat: p.lat,
        lon: p.lon,
        ele: null,
        role: i === 0 ? "start" : "waypoint",
      };
      if (p.designation) {
        return {
          ...base,
          kind: "amps",
          ptType: p.designation.ptType || "turn",
          name: p.designation.name || (i === 0 ? ".SP" : `.CP${i}`),
          chartElevationFt: p.designation.chartElevationFt,
        };
      }
      const auto = autoDesignation(i, lastIndex);
      if (auto) {
        return { ...base, kind: "amps", ptType: auto.ptType, name: auto.name };
      }
      return { ...base, kind: "shaping", ptType: null, name: "" };
    }),
  };
};

/**
 * Changes a point's designation. Demoting one of a route's two remaining AMPS
 * points to shaping is refused (legs need at least two endpoints): the same route
 * comes back.
 */
export const designatePoint = (route, pointId, { kind, ptType, name }) => {
  if (kind === "shaping") {
    const ampsCount = route.points.filter((p) => p.kind === "amps").length;
    const target = route.points.find((p) => p.id === pointId);
    if (target?.kind === "amps" && ampsCount <= 2) return route;
  }
  return {
    ...route,
    points: route.points.map((p) => {
      if (p.id !== pointId) return p;
      if (kind === "shaping") {
        return { ...p, kind: "shaping", ptType: null, name: "", role: "waypoint" };
      }
      return {
        ...p,
        kind: "amps",
        ptType: ptType ?? p.ptType ?? "turn",
        name: name !== undefined ? name : p.name || ".CP",
        role: p.role === "start" ? "start" : "waypoint",
      };
    }),
  };
};

/**
 * Moves a point. `chartElevationFt` is set when a drag snaps onto a local point
 * (its charted elevation) and left undefined on any normal drag, so the point
 * reverts to the DEM elevation when moved off a known point.
 */
export const movePoint = (route, pointId, lat, lon, chartElevationFt) => ({
  ...route,
  points: route.points.map((p) => (p.id === pointId ? { ...p, lat, lon, chartElevationFt } : p)),
});

/** A shaping point on the line, between the nearest pair of consecutive points. */
export const insertShapingPoint = (route, lat, lon, newPointId) => {
  const index = findNearestAdjacentIndex(route, lat, lon);
  if (index === -1) return route;
  const points = [...route.points];
  points.splice(index + 1, 0, {
    id: newPointId(),
    lat,
    lon,
    ele: null,
    kind: "shaping",
    ptType: null,
    name: "",
    role: "waypoint",
  });
  return { ...route, points };
};

/** A designated AMPS point at the end of a route (snaps the line onto a named local point). */
export const appendAmpsPoint = (route, lat, lon, { name = "", ptType = "turn", chartElevationFt } = {}, newPointId) => ({
  ...route,
  points: [
    ...route.points,
    {
      id: newPointId(),
      lat,
      lon,
      ele: null,
      kind: "amps",
      ptType,
      name,
      role: "waypoint",
      chartElevationFt,
    },
  ],
});

/** Merges plan settings (airspeed, altitude, wind, date, ...) into a route. */
export const withPlanPatch = (route, patch) => ({
  ...route,
  plan: { ...defaultRoutePlan(), ...route.plan, ...patch },
});

/** Merges a per-point "to" override (altitude/airspeed/wind). Null clears the point's overrides. */
export const withPointOverride = (route, pointId, patch) => {
  const plan = { ...defaultRoutePlan(), ...route.plan };
  const perPoint = { ...plan.perPoint };
  if (patch === null) {
    delete perPoint[pointId];
  } else {
    perPoint[pointId] = { ...perPoint[pointId], ...patch };
  }
  return { ...route, plan: { ...plan, perPoint } };
};

/**
 * Sets (or clears, with an empty clock) a point's clock/TOT time. Only one point
 * anchors the clock at a time, so setting one clears the others; their other
 * overrides stay, and a point left with nothing is dropped.
 */
export const withPointClock = (route, pointId, clock) => {
  const plan = { ...defaultRoutePlan(), ...route.plan };
  const perPoint = {};
  for (const [id, over] of Object.entries(plan.perPoint || {})) {
    const { clock: _clock, ...rest } = over;
    if (Object.keys(rest).length) perPoint[id] = rest;
  }
  if (clock) {
    perPoint[pointId] = { ...perPoint[pointId], clock };
  }
  return { ...route, plan: { ...plan, perPoint } };
};

/** Renames a point, optionally snapping it to a known local point's coordinates and charted elevation. */
export const renamePoint = (route, pointId, name, coords, chartElevationFt) => ({
  ...route,
  points: route.points.map((p) =>
    p.id === pointId
      ? { ...p, name, ...(coords ? { lat: coords.lat, lon: coords.lon, chartElevationFt } : {}) }
      : p,
  ),
});
