import { useCallback, useMemo, useReducer, useRef, useState } from "react";
import { nextRouteColor, ROUTE_COLORS } from "./colorPalette";
import { buildSketchMsnx } from "./createMsnx";
import { defaultRoutePlan, ensureRoutePlan, fetchPointElevationsFt } from "./routeCalc";
import { fetchForecastWinds, mergeWindsIntoPlan } from "./routeWinds";
import {
  appendAmpsPoint,
  designatePoint,
  insertShapingPoint,
  movePoint,
  removePoint,
  renamePoint,
  sketchRouteVersions,
  withPlanPatch,
  withPointClock,
  withPointOverride,
} from "./sketchOps";
import { INITIAL_SKETCH_STATE, routeSketchReducer, setKeyOf, SKETCHES } from "./routeSketchReducer";
import { currentFieldVisit } from "../undo/fieldVisit";
import { nextStepSeq } from "../undo/history";

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
  const [{ routes: sketchedRoutes, history: sketchHistory }, dispatch] = useReducer(routeSketchReducer, INITIAL_SKETCH_STATE);
  // One thing a person did to one route: `op` is route -> route, and a typed field's keystrokes share `key`.
  const editRoute = useCallback((routeId, label, op, key = null) => {
    dispatch({
      type: "edit",
      routeId,
      update: (routes) => routes.map((route) => (route.id === routeId ? op(route) : route)),
      step: { seq: nextStepSeq(), label, key },
    });
  }, []);
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

  const removeLastDraftPoint = () => {
    setDraftPoints((prev) => prev.slice(0, -1));
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
    const versions = sketchRouteVersions(draftPoints, {
      name,
      id: generateId("sketch"),
      color: nextRouteColor(),
      plan: defaultRoutePlan(aircraftProfile),
      newPointId: () => crypto.randomUUID(),
    }).map((route) => (setId ? { ...route, setId } : route));

    // A step for each point, so undo takes the route back as it was drawn. Two points are the least
    // that is a route: the first step is both of them.
    versions.forEach((route, index) => {
      dispatch({
        type: "edit",
        setKey: setKeyOf(route),
        update: (routes) => (index === 0 ? [...routes, route] : routes.map((r) => (r.id === route.id ? route : r))),
        step: { seq: nextStepSeq(), label: index === 0 ? "Draw route" : "Add point" },
      });
    });
    setDraftPoints([]);
    return true;
  };

  /**
   * Changes a point's designation. Demoting one of a route's two remaining
   * AMPS points to shaping is refused — legs need at least two endpoints.
   */
  const designateSketchPoint = (routeId, pointId, designation) => {
    editRoute(routeId, "Change point type", (route) => designatePoint(route, pointId, designation));
  };

  // `chartElevationFt` is set when a drag snaps onto a local point (its charted
  // elevation) and cleared (undefined) on any normal drag, so the point reverts
  // to the DEM elevation when moved off a known point.
  const updateSketchPointPosition = useCallback((routeId, pointId, lat, lon, chartElevationFt) => {
    editRoute(routeId, "Move point", (route) => movePoint(route, pointId, lat, lon, chartElevationFt));
  }, [editRoute]);

  /** A point on the line. With a `designation` it is a named point; without, one that only bends the line. */
  const insertSketchPoint = (routeId, lat, lon, designation = null) => {
    const pointId = crypto.randomUUID();
    editRoute(routeId, "Add point", (route) => {
      const added = insertShapingPoint(route, lat, lon, () => pointId);
      return designation ? designatePoint(added, pointId, { kind: "amps", ...designation }) : added;
    });
  };

  const removeSketchPoint = (routeId, pointId) => {
    editRoute(routeId, "Delete point", (route) => removePoint(route, pointId));
  };

  /** Appends a designated AMPS point to the end of a route (used to snap the
   *  route line onto a named local point, carrying its charted elevation). */
  const appendSketchPoint = (routeId, lat, lon, options = {}) => {
    const pointId = crypto.randomUUID();
    editRoute(routeId, "Add point", (route) => appendAmpsPoint(route, lat, lon, options, () => pointId));
  };

  /**
   * Restores saved sketch routes. Route ids are regenerated so loading the
   * same save twice can't collide (point ids are UUIDs and stay as saved);
   * the "sketch-" prefix matters — App routes context-menu actions on it.
   * A set's routes (`setId`) keep their ids: others name them in their edits.
   */
  const loadSketchRoutes = (routes, { setId = null } = {}) => {
    const restored = routes.map((route) => restoreSketchRoute(route, setId));
    dispatch({ type: "append", routes: restored });
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
    // Two opens can resolve before the next render; the second must see the first's routes, or both
    // become the sketches and the second's record takes the first's routes with it.
    routesRef.current = [...routesRef.current, ...restored];
    dispatch({ type: "append", routes: restored });
    return setId;
  };

  /**
   * Puts a set's routes in place of the ones it has (`routes`: the set's routes as it now has them),
   * keeping where the set sits in the list and which of its routes this person has hidden.
   */
  const replaceRouteSet = useCallback((setId, routes, { rebase, dropHistory } = {}) => {
    dispatch({ type: "place", setId, routes: routes.map((route) => restoreSketchRoute(route, setId)), rebase, dropHistory });
  }, []);

  /** Closes a set (`setId` null: this session's own sketches). Not something to undo. */
  const removeRouteSet = useCallback((setId) => {
    dispatch({ type: "removeSet", setKey: setId ?? SKETCHES });
  }, []);

  const undoSketch = useCallback((setKey) => {
    dispatch({ type: "undo", setKey, seq: nextStepSeq() });
  }, []);

  const redoSketch = useCallback((setKey) => {
    dispatch({ type: "redo", setKey, seq: nextStepSeq() });
  }, []);

  /** Merges plan settings (airspeed, altitude, wind, TOT, ...) into a route. */
  const updateRoutePlan = (routeId, patch) => {
    editRoute(routeId, "Edit route plan", (route) => withPlanPatch(route, patch), currentFieldVisit());
  };

  /** Merges a per-point "to" override (altitude/airspeed/wind). Pass null to clear the point. */
  const updatePointPlanOverride = (routeId, pointId, patch) => {
    editRoute(routeId, "Edit point plan", (route) => withPointOverride(route, pointId, patch), currentFieldVisit());
  };

  /**
   * Sets (or clears, with empty string) a point's clock/TOT time. Only one
   * point anchors the clock at a time, so setting one clears the others.
   */
  const setSketchPointClock = (routeId, pointId, clock) => {
    editRoute(routeId, "Set clock", (route) => withPointClock(route, pointId, clock));
  };

  /** Renames a sketch point, optionally snapping it to a known local point's
   *  coords + charted elevation. */
  const updateSketchPointName = (routeId, pointId, name, coords, chartElevationFt) => {
    editRoute(routeId, "Rename point", (route) => renamePoint(route, pointId, name, coords, chartElevationFt), currentFieldVisit());
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

    dispatch({ type: "quiet", routeId, change: (r) => ({ ...r, plan: mergeWindsIntoPlan(r.plan, amps, winds) }) });
    return { winds };
  };

  /** Fetches ground elevations for a route's points (AGL altitudes, TAS). */
  const refreshRouteElevations = async (routeId) => {
    const route = sketchedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const elevations = await fetchPointElevationsFt(route);
    dispatch({ type: "quiet", routeId, change: (r) => ({ ...r, elevations: { ...r.elevations, ...elevations } }) });
    return elevations;
  };

  const removeSketchRoute = (routeId) => {
    dispatch({
      type: "edit",
      routeId,
      update: (routes) => routes.filter((route) => route.id !== routeId),
      step: { seq: nextStepSeq(), label: "Delete route" },
    });
  };

  const toggleSketchVisibility = (routeId) => {
    dispatch({ type: "visible", routeId });
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
    sketchHistory,
    undoSketch,
    redoSketch,
    replaceRouteSet,
    removeRouteSet,
    startSketch,
    cancelSketch,
    addDraftPoint,
    removeLastDraftPoint,
    finishSketch,
    designateSketchPoint,
    updateSketchPointPosition,
    insertSketchPoint,
    removeSketchPoint,
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
