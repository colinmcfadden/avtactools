import { useCallback, useState } from "react";
import { nextRouteColor } from "./colorPalette";
import { buildSketchMsnx } from "./createMsnx";
import { defaultRoutePlan, ensureRoutePlan, fetchPointElevationsFt } from "./routeCalc";
import { fetchForecastWinds, mergeWindsIntoPlan } from "./routeWinds";
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
} from "./sketchOps";

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
    // otherwise the standard attack profile is applied automatically (see
    // sketchOps.autoDesignation).
    const route = buildSketchRoute(draftPoints, {
      name,
      id: generateId("sketch"),
      color: nextRouteColor(),
      plan: defaultRoutePlan(aircraftProfile),
      newPointId: () => crypto.randomUUID(),
    });

    setSketchedRoutes((prev) => [...prev, route]);
    setDraftPoints([]);
    return true;
  };

  /**
   * Changes a point's designation. Demoting one of a route's two remaining
   * AMPS points to shaping is refused — legs need at least two endpoints.
   */
  const designateSketchPoint = (routeId, pointId, designation) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => (route.id === routeId ? designatePoint(route, pointId, designation) : route)),
    );
  };

  // `chartElevationFt` is set when a drag snaps onto a local point (its charted
  // elevation) and cleared (undefined) on any normal drag, so the point reverts
  // to the DEM elevation when moved off a known point.
  const updateSketchPointPosition = useCallback((routeId, pointId, lat, lon, chartElevationFt) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? movePoint(route, pointId, lat, lon, chartElevationFt) : route,
      ),
    );
  }, []);

  const insertSketchPoint = (routeId, lat, lon) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? insertShapingPoint(route, lat, lon, () => crypto.randomUUID()) : route,
      ),
    );
  };

  /** Appends a designated AMPS point to the end of a route (used to snap the
   *  route line onto a named local point, carrying its charted elevation). */
  const appendSketchPoint = (routeId, lat, lon, options = {}) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? appendAmpsPoint(route, lat, lon, options, () => crypto.randomUUID()) : route,
      ),
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
      prev.map((route) => (route.id === routeId ? withPlanPatch(route, patch) : route)),
    );
  };

  /** Merges a per-point "to" override (altitude/airspeed/wind). Pass null to clear the point. */
  const updatePointPlanOverride = (routeId, pointId, patch) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => (route.id === routeId ? withPointOverride(route, pointId, patch) : route)),
    );
  };

  /**
   * Sets (or clears, with empty string) a point's clock/TOT time. Only one
   * point anchors the clock at a time, so setting one clears the others.
   */
  const setSketchPointClock = (routeId, pointId, clock) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => (route.id === routeId ? withPointClock(route, pointId, clock) : route)),
    );
  };

  /** Renames a sketch point, optionally snapping it to a known local point's
   *  coords + charted elevation. */
  const updateSketchPointName = (routeId, pointId, name, coords, chartElevationFt) => {
    setSketchedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? renamePoint(route, pointId, name, coords, chartElevationFt) : route,
      ),
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
