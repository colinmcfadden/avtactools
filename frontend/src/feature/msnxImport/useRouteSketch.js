import { useCallback, useState } from "react";
import { nextRouteColor } from "./colorPalette";
import { findNearestAdjacentIndex } from "./mutateMsnx";
import { buildSketchMsnx } from "./createMsnx";
import { defaultRoutePlan, ensureRoutePlan, fetchPointElevationsFt } from "./routeCalc";
import { fetchForecastWinds, mergeWindsIntoPlan } from "./routeWinds";

const generateId = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const useRouteSketch = ({ aircraftProfile = null } = {}) => {
  const [isSketching, setIsSketching] = useState(false);
  const [draftPoints, setDraftPoints] = useState([]);
  const [sketchedRoutes, setSketchedRoutes] = useState([]);

  const startSketch = () => {
    setDraftPoints([]);
    setIsSketching(true);
  };

  const cancelSketch = () => {
    setDraftPoints([]);
    setIsSketching(false);
  };

  /**
   * Adds a point to the in-progress sketch. `designation` (from the draw-mode
   * right-click menu) pre-marks it as a named AMPS point: { ptType, name }.
   */
  const addDraftPoint = (lat, lon, designation = null) => {
    setDraftPoints((prev) => [...prev, { lat, lon, designation }]);
  };

  /** Returns true if a route was created (needs >= 2 points). */
  const finishSketch = (name) => {
    setIsSketching(false);
    if (draftPoints.length < 2) {
      setDraftPoints([]);
      return false;
    }

    // AMPS model: only designated points ("amps") become real route points;
    // everything else exports as serpentine shaping geometry on the leg
    // between them. Points designated during drawing keep their type/name;
    // otherwise the standard attack profile is applied automatically — the
    // first two points become Target then IP, and the last two become RP
    // (IP) then Target. Everything in the middle is shaping.
    const lastIndex = draftPoints.length - 1;
    const autoDesignation = (i) => {
      if (i === 0) return { ptType: "target", name: ".TGT" };
      if (i === lastIndex) return { ptType: "target", name: ".TGT" };
      if (i === 1) return { ptType: "ip", name: ".SP" };
      if (i === lastIndex - 1) return { ptType: "ip", name: ".RP" };
      return null;
    };

    const route = {
      id: generateId("sketch"),
      name,
      color: nextRouteColor(),
      visible: true,
      plan: defaultRoutePlan(aircraftProfile),
      elevations: {},
      points: draftPoints.map((p, i) => {
        const base = {
          id: crypto.randomUUID(),
          lat: p.lat,
          lon: p.lon,
          ele: null,
          role: i === 0 ? "start" : "waypoint",
        };
        // A designation set during drawing (right-click menu, or "+" from a
        // local point) always wins.
        if (p.designation) {
          return {
            ...base,
            kind: "amps",
            ptType: p.designation.ptType || "turn",
            name: p.designation.name || (i === 0 ? ".SP" : `.CP${i}`),
            chartElevationFt: p.designation.chartElevationFt,
          };
        }
        const auto = autoDesignation(i);
        if (auto) {
          return { ...base, kind: "amps", ptType: auto.ptType, name: auto.name };
        }
        return { ...base, kind: "shaping", ptType: null, name: "" };
      }),
    };

    setSketchedRoutes((prev) => [...prev, route]);
    setDraftPoints([]);
    return true;
  };

  /**
   * Changes a point's designation. Demoting one of a route's two remaining
   * AMPS points to shaping is refused — legs need at least two endpoints.
   */
  const designateSketchPoint = (routeId, pointId, { kind, ptType, name }) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;

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
      }),
    );
  };

  // `chartElevationFt` is set when a drag snaps onto a local point (its charted
  // elevation) and cleared (undefined) on any normal drag, so the point reverts
  // to the DEM elevation when moved off a known point.
  const updateSketchPointPosition = useCallback((routeId, pointId, lat, lon, chartElevationFt) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        return {
          ...route,
          points: route.points.map((p) =>
            p.id === pointId ? { ...p, lat, lon, chartElevationFt } : p,
          ),
        };
      }),
    );
  }, []);

  const insertSketchPoint = (routeId, lat, lon) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        const index = findNearestAdjacentIndex(route, lat, lon);
        if (index === -1) return route;

        const points = [...route.points];
        points.splice(index + 1, 0, {
          id: crypto.randomUUID(),
          lat,
          lon,
          ele: null,
          kind: "shaping",
          ptType: null,
          name: "",
          role: "waypoint",
        });
        return { ...route, points };
      }),
    );
  };

  /** Appends a designated AMPS point to the end of a route (used to snap the
   *  route line onto a named local point, carrying its charted elevation). */
  const appendSketchPoint = (
    routeId,
    lat,
    lon,
    { name = "", ptType = "turn", chartElevationFt } = {},
  ) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        return {
          ...route,
          points: [
            ...route.points,
            {
              id: crypto.randomUUID(),
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
        };
      }),
    );
  };

  /**
   * Restores saved sketch routes. Route ids are regenerated so loading the
   * same save twice can't collide (point ids are UUIDs and stay as saved);
   * the "sketch-" prefix matters — App routes context-menu actions on it.
   */
  const loadSketchRoutes = (routes) => {
    const restored = routes.map((route) =>
      ensureRoutePlan({
        ...route,
        id: generateId("sketch"),
        color: route.color || nextRouteColor(),
        visible: true,
        elevations: route.elevations || {},
      }),
    );
    setSketchedRoutes((prev) => [...prev, ...restored]);
  };

  /** Merges plan settings (airspeed, altitude, wind, TOT, ...) into a route. */
  const updateRoutePlan = (routeId, patch) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId
          ? { ...route, plan: { ...defaultRoutePlan(), ...route.plan, ...patch } }
          : route,
      ),
    );
  };

  /** Merges a per-point "to" override (altitude/airspeed/wind). Pass null to clear the point. */
  const updatePointPlanOverride = (routeId, pointId, patch) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        const plan = { ...defaultRoutePlan(), ...route.plan };
        const perPoint = { ...plan.perPoint };
        if (patch === null) {
          delete perPoint[pointId];
        } else {
          perPoint[pointId] = { ...perPoint[pointId], ...patch };
        }
        return { ...route, plan: { ...plan, perPoint } };
      }),
    );
  };

  /**
   * Sets (or clears, with empty string) a point's clock/TOT time. Only one
   * point anchors the clock at a time, so setting one clears the others.
   */
  const setSketchPointClock = (routeId, pointId, clock) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        const plan = { ...defaultRoutePlan(), ...route.plan };
        const perPoint = {};
        // Drop every other point's clock; keep their other overrides.
        for (const [id, over] of Object.entries(plan.perPoint || {})) {
          const { clock: _clock, ...rest } = over;
          if (Object.keys(rest).length) perPoint[id] = rest;
        }
        if (clock) {
          perPoint[pointId] = { ...perPoint[pointId], clock };
        }
        return { ...route, plan: { ...plan, perPoint } };
      }),
    );
  };

  /** Renames a sketch point, optionally snapping it to a known local point's
   *  coords + charted elevation. */
  const updateSketchPointName = (routeId, pointId, name, coords, chartElevationFt) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        return {
          ...route,
          points: route.points.map((p) =>
            p.id === pointId
              ? {
                  ...p,
                  name,
                  ...(coords
                    ? { lat: coords.lat, lon: coords.lon, chartElevationFt }
                    : {}),
                }
              : p,
          ),
        };
      }),
    );
  };

  /**
   * Fetches winds for each of a route's points from the nearest station and
   * writes them as per-point "to" wind overrides. Each point uses its planned
   * clock time (or the plan date at midday) so future points draw from the TAF
   * and current ones from the METAR — the backend decides per point. Returns
   * { winds, error? } for the caller to surface a status.
   */
  const applyForecastWinds = async (routeId) => {
    const route = sketchedRoutes.find((r) => r.id === routeId);
    if (!route) return { error: "Route not found." };

    const { winds, amps, error } = await fetchForecastWinds(route);
    if (error) return { error };

    setSketchedRoutes((prev) =>
      prev.map((r) =>
        r.id === routeId ? { ...r, plan: mergeWindsIntoPlan(r.plan, amps, winds) } : r,
      ),
    );
    return { winds };
  };

  /** Fetches ground elevations for a route's points (AGL altitudes, TAS). */
  const refreshRouteElevations = async (routeId) => {
    const route = sketchedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const elevations = await fetchPointElevationsFt(route);
    setSketchedRoutes((prev) =>
      prev.map((r) =>
        r.id === routeId
          ? { ...r, elevations: { ...r.elevations, ...elevations } }
          : r,
      ),
    );
    return elevations;
  };

  const removeSketchRoute = (routeId) => {
    setSketchedRoutes((prev) => prev.filter((route) => route.id !== routeId));
  };

  const toggleSketchVisibility = (routeId) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? { ...route, visible: !route.visible } : route,
      ),
    );
  };

  const exportSketches = async () => {
    if (sketchedRoutes.length === 0) return;
    try {
      const result = await buildSketchMsnx(sketchedRoutes, undefined, aircraftProfile);
      // The file already downloaded; tell the user only when AMPS will open it
      // as a different airframe than the one they planned with.
      if (result?.warning) alert(result.warning);
    } catch (err) {
      alert("Error exporting routes: " + err.message);
    }
  };

  return {
    isSketching,
    draftPoints,
    sketchedRoutes,
    startSketch,
    cancelSketch,
    addDraftPoint,
    finishSketch,
    designateSketchPoint,
    updateSketchPointPosition,
    insertSketchPoint,
    appendSketchPoint,
    loadSketchRoutes,
    removeSketchRoute,
    toggleSketchVisibility,
    exportSketches,
    updateRoutePlan,
    updatePointPlanOverride,
    setSketchPointClock,
    updateSketchPointName,
    refreshRouteElevations,
    applyForecastWinds,
  };
};
