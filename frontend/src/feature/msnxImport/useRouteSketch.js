import { useCallback, useMemo, useRef, useState } from "react";
import { nextRouteColor, ROUTE_COLORS } from "./colorPalette";
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

// The same colour for the same route on every screen, for a set's route saved without one.
const colorFor = (id) => {
  let hash = 0;
  for (const ch of String(id)) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return ROUTE_COLORS[hash % ROUTE_COLORS.length];
};

/**
 * A route as the sketch keeps it, from saved data: this session's get a new id (loading one save
 * twice must not collide); a set's keep theirs, and anything filled in for one is the same every
 * time, since it is compared with what the set has.
 */
export const restoreSketchRoute = (route, setId = null) =>
  ensureRoutePlan({
    ...route,
    id: setId ? route.id : generateId("sketch"),
    color: route.color || (setId ? colorFor(route.id) : nextRouteColor()),
    visible: true,
    elevations: route.elevations || {},
    ...(setId ? { setId } : {}),
  });

const OPENED = "opened";

/** Whether `setId` is a set opened from the Library beside this session's sketches (openSavedRoutes). */
export const isOpenedSetId = (setId) => String(setId ?? "").startsWith(`${OPENED}-`);

/*
 * Sketched routes, in sets. Each route carries `setId`: none (null) for this session's own sketches,
 * which are saved and exported together as one bundle; an "opened-…" id for a saved set opened from
 * the Library beside them, saved and exported on its own; or the id of a set that lives elsewhere, a
 * mission pack's route set (feature/missionPacks/usePackRoutes.js), whose routes keep their ids so
 * everyone's edits can name them. Everything that changes one route finds it by its id, whichever
 * set it is in.
 */
export const useRouteSketch = ({ aircraftProfile = null } = {}) => {
  const [isSketching, setIsSketching] = useState(false);
  const [draftPoints, setDraftPoints] = useState([]);
  const [sketchedRoutes, setSketchedRoutes] = useState([]);
  const sessionRoutes = useMemo(() => sketchedRoutes.filter((route) => !route.setId), [sketchedRoutes]);
  // What is here now, not when the caller's render began: opening a saved set waits on the network.
  const routesRef = useRef(sketchedRoutes);
  routesRef.current = sketchedRoutes;

  /** This session's sketches (`setId` null) and each set opened beside them, in the order opened: [{ setId, routes }]. */
  const sketchSets = useMemo(() => {
    const sets = sessionRoutes.length > 0 ? [{ setId: null, routes: sessionRoutes }] : [];
    const opened = new Map();
    sketchedRoutes.forEach((route) => {
      if (!isOpenedSetId(route.setId)) return;
      if (!opened.has(route.setId)) opened.set(route.setId, []);
      opened.get(route.setId).push(route);
    });
    opened.forEach((routes, setId) => sets.push({ setId, routes }));
    return sets;
  }, [sessionRoutes, sketchedRoutes]);

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

  /** Returns true if a route was created (needs >= 2 points). `setId`: the set it goes in (none: this session's). */
  const finishSketch = (name, setId = null) => {
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

    setSketchedRoutes((prev) => [...prev, setId ? { ...route, setId } : route]);
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
   * A set's routes (`setId`) keep their ids: others name them in their edits.
   */
  const loadSketchRoutes = (routes, { setId = null } = {}) => {
    const restored = routes.map((route) => restoreSketchRoute(route, setId));
    setSketchedRoutes((prev) => [...prev, ...restored]);
  };

  /**
   * Opens a saved set of routes from the Library. With none of this session's own sketches here,
   * they become them, so new sketches join the set just opened. Otherwise they come in as a set of
   * their own: merged in, the sketches already here would be written into the record just opened, or
   * cut loose from the one they came from (docs/MENU_REDESIGN.md §5: opening never replaces unsaved
   * work). Returns the set's id, null for this session's own sketches.
   */
  const openSavedRoutes = (routes) => {
    const setId = routesRef.current.some((route) => !route.setId) ? generateId(OPENED) : null;
    // New route ids either way: a record and its copy (Save as…) can be open side by side. Where a
    // route is filed is this session's business, never the record's.
    const restored = routes.map(({ setId: _filed, ...route }) => ({ ...restoreSketchRoute(route), ...(setId ? { setId } : {}) }));
    setSketchedRoutes((prev) => [...prev, ...restored]);
    return setId;
  };

  /**
   * Puts a set's routes in place of the ones it has (`routes`: the set's routes as it now has them),
   * keeping where the set sits in the list and which of its routes this person has hidden.
   */
  const replaceRouteSet = useCallback((setId, routes) => {
    setSketchedRoutes((prev) => {
      const hidden = new Set(prev.filter((r) => r.setId === setId && r.visible === false).map((r) => r.id));
      const next = routes.map((route) => {
        const restored = restoreSketchRoute(route, setId);
        return hidden.has(restored.id) ? { ...restored, visible: false } : restored;
      });
      const at = prev.findIndex((r) => r.setId === setId);
      const others = prev.filter((r) => r.setId !== setId);
      if (at < 0) return [...others, ...next];
      const before = prev.slice(0, at).filter((r) => r.setId !== setId).length;
      return [...others.slice(0, before), ...next, ...others.slice(before)];
    });
  }, []);

  const removeRouteSet = useCallback((setId) => {
    setSketchedRoutes((prev) => prev.filter((r) => r.setId !== setId));
  }, []);

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

  /**
   * Exports this session's sketches, or `routes` (a set's), as a .msnx download. Resolves to what
   * buildSketchMsnx says about the file, whose `warning` is set when AMPS will open it as another
   * airframe than the one planned with; throws when the file could not be made. The caller tells
   * the person either way: a failure kept in here would let it carry on as if the file were made.
   */
  const exportSketches = async (routes = sessionRoutes) => {
    if (routes.length === 0) return null;
    return buildSketchMsnx(routes, undefined, aircraftProfile);
  };

  return {
    isSketching,
    draftPoints,
    sketchedRoutes,
    sessionRoutes,
    sketchSets,
    replaceRouteSet,
    removeRouteSet,
    startSketch,
    cancelSketch,
    addDraftPoint,
    finishSketch,
    designateSketchPoint,
    updateSketchPointPosition,
    insertSketchPoint,
    appendSketchPoint,
    loadSketchRoutes,
    openSavedRoutes,
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
