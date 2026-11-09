import { useCallback, useReducer, useRef } from "react";
import { parseMsnxFile } from "./parseMsnx";
import {
  updatePointCoordinate,
  updatePointName as updatePointNameInDocs,
  insertPointOnRoute,
  exportMsnxFile,
  buildMsnxBlob,
  applyPlanToMsnxDocs,
} from "./mutateMsnx";
import { nextRouteColor } from "./colorPalette";
import { defaultRoutePlan, fetchPointElevationsFt } from "./routeCalc";
import { fetchForecastWinds, mergeWindsIntoPlan } from "./routeWinds";
import { INITIAL_SKETCH_STATE, makeRouteReducer } from "./routeSketchReducer";
import { currentFieldVisit } from "../undo/fieldVisit";
import { nextStepSeq, topRedo, topUndo } from "../undo/history";

const generateId = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

// A mission's undo history is its file's: moving a hand-off point changes two of its routes at once.
const missionReducer = makeRouteReducer((route) => route.fileId);

const mapRoute = (routeId, change) => (routes) => routes.map((route) => (route.id === routeId ? change(route) : route));

export const useMsnxImport = () => {
  const [{ routes: importedRoutes, history: missionHistory }, dispatch] = useReducer(missionReducer, INITIAL_SKETCH_STATE);
  const filesRef = useRef(new Map()); // fileId -> { zip, docs, originalName }
  const routesRef = useRef(importedRoutes);
  routesRef.current = importedRoutes;

  const editRoutes = useCallback((routeId, label, update, key = null) => {
    dispatch({ type: "edit", routeId, update, step: { label, key, seq: nextStepSeq() } });
  }, []);

  /**
   * A point's place and name also live in the mission's XML, changed as they are edited. Undo and redo
   * write what differs between now and the version going back in, here and never in a reducer (React
   * runs those twice). A route taken off the list is not in `now`: its points were not touched while gone.
   */
  const writeVersionToDocs = useCallback((fileId, version) => {
    const docs = filesRef.current.get(fileId)?.docs;
    if (!docs) return;
    const now = new Map(routesRef.current.filter((r) => r.fileId === fileId).flatMap((r) => r.points).map((p) => [p.id, p]));
    version.forEach((route) =>
      route.points.forEach((target) => {
        const point = now.get(target.id);
        if (!point) return;
        if (point.lat !== target.lat || point.lon !== target.lon) updatePointCoordinate(docs, target.id, target.lat, target.lon);
        if (point.name !== target.name) updatePointNameInDocs(docs, target.id, target.name);
      }),
    );
  }, []);

  const undoMission = useCallback((fileId) => {
    const step = topUndo(missionHistory[fileId]);
    if (!step) return;
    writeVersionToDocs(fileId, step.before);
    dispatch({ type: "undo", setKey: fileId, seq: nextStepSeq() });
  }, [missionHistory, writeVersionToDocs]);

  const redoMission = useCallback((fileId) => {
    const step = topRedo(missionHistory[fileId]);
    if (!step) return;
    writeVersionToDocs(fileId, step.after);
    dispatch({ type: "redo", setKey: fileId });
  }, [missionHistory, writeVersionToDocs]);

  const importMsnxFile = async (file) => {
    const { zip, docs, routes, aircraft } = await parseMsnxFile(file);
    const fileId = generateId("msnxfile");
    filesRef.current.set(fileId, { zip, docs, originalName: file.name });

    const newRoutes = routes.map((route) => ({
      id: generateId("msnx"),
      fileId,
      fileName: file.name,
      name: route.name,
      segmentId: route.segmentId,
      color: nextRouteColor(),
      visible: true,
      points: route.points,
      // Planned performance data read from the mission file, so imported
      // routes drive the same inline plan editor as sketched ones.
      plan: route.plan,
      elevations: route.elevations || {},
    }));

    dispatch({ type: "append", routes: newRoutes });
    // The airframe AMPS recorded in the mission, so the caller can switch the
    // active aircraft to match what was actually planned.
    return { fileId, aircraft };
  };

  const toggleRouteVisibility = (routeId) => {
    dispatch({ type: "visible", routeId });
  };

  // NOTE for both handlers below: the XML-doc mutation must happen OUTSIDE the
  // reducer. React re-runs updater functions (StrictMode
  // double-invokes them in development), so a non-idempotent mutation inside
  // one runs twice — for insertPoint the second run threw "Couldn't find the
  // connecting leg" because the first run had already split that leg.

  const updatePointPosition = useCallback((routeId, pointId, lat, lon, chartElevationFt) => {
    const sourceRoute = importedRoutes.find((r) => r.id === routeId);
    const draggedPoint = sourceRoute?.points.find((p) => p.id === pointId);
    if (!sourceRoute || !draggedPoint) return;

    // AMPS models a hand-off point (e.g. an LZ that's one route's last
    // point and the next route's first point) as two *separate* point
    // objects with different ids that just happen to share a name and
    // coordinates — not one shared point. Match on (name, current position)
    // across the other routes from the same file so dragging one moves
    // every route's copy of it together, the way the user actually sees it
    // on screen.
    const strippedName = draggedPoint.name.replace(/^\./, "");
    const idsToUpdate = new Set([pointId]);
    for (const route of importedRoutes) {
      if (route.fileId !== sourceRoute.fileId) continue;
      for (const p of route.points) {
        if (idsToUpdate.has(p.id)) continue;
        if (
          p.name.replace(/^\./, "") === strippedName &&
          p.lat === draggedPoint.lat &&
          p.lon === draggedPoint.lon
        ) {
          idsToUpdate.add(p.id);
        }
      }
    }

    const fileEntry = filesRef.current.get(sourceRoute.fileId);
    if (fileEntry) {
      for (const id of idsToUpdate) updatePointCoordinate(fileEntry.docs, id, lat, lon);
    }

    editRoutes(routeId, "Move point", (routes) =>
      routes.map((route) => {
        if (route.fileId !== sourceRoute.fileId) return route;
        if (!route.points.some((p) => idsToUpdate.has(p.id))) return route;

        return {
          ...route,
          points: route.points.map((p) =>
            idsToUpdate.has(p.id) ? { ...p, lat, lon, chartElevationFt } : p,
          ),
        };
      }),
    );
  }, [importedRoutes, editRoutes]);

  const insertPoint = useCallback((routeId, lat, lon) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const fileEntry = filesRef.current.get(route.fileId);
    if (!fileEntry) return;

    // May throw (e.g. no connecting leg near the click) — deliberately not
    // caught here so the caller's try/catch can surface it to the user.
    const { afterPointId, point: newPoint } = insertPointOnRoute(fileEntry.docs, route, lat, lon);

    // Splitting a leg in the file cannot be taken back, so nothing before it can be either.
    dispatch({
      type: "barrier",
      setKey: route.fileId,
      update: mapRoute(routeId, (r) => {
        const afterIndex = r.points.findIndex((p) => p.id === afterPointId);
        if (afterIndex === -1) return r;
        const points = [...r.points];
        points.splice(afterIndex + 1, 0, newPoint);
        return { ...r, points };
      }),
    });
  }, [importedRoutes]);

  // --- inline plan editing (mirrors useRouteSketch, for imported routes) ---
  // Plan edits live in React state and are written back into the mission docs
  // at export/serialize time via applyPlanToMsnxDocs; coordinate/name edits
  // mutate the docs immediately (like the drag handler above).

  const updateRoutePlan = (routeId, patch) => {
    editRoutes(
      routeId,
      "Edit route plan",
      mapRoute(routeId, (route) => ({ ...route, plan: { ...defaultRoutePlan(), ...route.plan, ...patch } })),
      currentFieldVisit(),
    );
  };

  const updatePointPlanOverride = (routeId, pointId, patch) => {
    editRoutes(
      routeId,
      "Edit point plan",
      mapRoute(routeId, (route) => {
        const plan = { ...defaultRoutePlan(), ...route.plan };
        const perPoint = { ...plan.perPoint };
        if (patch === null) delete perPoint[pointId];
        else perPoint[pointId] = { ...perPoint[pointId], ...patch };
        return { ...route, plan: { ...plan, perPoint } };
      }),
      currentFieldVisit(),
    );
  };

  /** Sets/clears a point's clock (TOT); only one point anchors at a time. */
  const setPointClock = (routeId, pointId, clock) => {
    editRoutes(
      routeId,
      "Set clock",
      mapRoute(routeId, (route) => {
        const plan = { ...defaultRoutePlan(), ...route.plan };
        const perPoint = {};
        for (const [id, over] of Object.entries(plan.perPoint || {})) {
          const { clock: _clock, ...rest } = over;
          if (Object.keys(rest).length) perPoint[id] = rest;
        }
        if (clock) perPoint[pointId] = { ...perPoint[pointId], clock };
        return { ...route, plan: { ...plan, perPoint } };
      }),
    );
  };

  /** Renames a point (and snaps to a local point's coords + charted elevation when provided), writing to the docs. */
  const updatePointName = (routeId, pointId, name, coords, chartElevationFt) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    const fileEntry = route && filesRef.current.get(route.fileId);
    if (fileEntry) {
      updatePointNameInDocs(fileEntry.docs, pointId, name);
      if (coords) updatePointCoordinate(fileEntry.docs, pointId, coords.lat, coords.lon);
    }
    editRoutes(
      routeId,
      "Rename point",
      mapRoute(routeId, (r) => ({
        ...r,
        points: r.points.map((p) =>
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
      })),
      currentFieldVisit(),
    );
  };

  const refreshRouteElevations = async (routeId) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const elevations = await fetchPointElevationsFt(route);
    dispatch({ type: "quiet", routeId, change: (r) => ({ ...r, elevations: { ...r.elevations, ...elevations } }) });
    return elevations;
  };

  const applyForecastWinds = async (routeId) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return { error: "Route not found." };
    const { winds, amps, error } = await fetchForecastWinds(route);
    if (error) return { error };
    dispatch({ type: "quiet", routeId, change: (r) => ({ ...r, plan: mergeWindsIntoPlan(r.plan, amps, winds) }) });
    return { winds };
  };

  // The file's XML keeps the route: taking it off the list and putting it back changes nothing there.
  const removeRoute = (id) => {
    editRoutes(id, "Delete route", (routes) => routes.filter((route) => route.id !== id));
  };

  /** Closes a mission, its history with it. Not something to undo. */
  const closeMission = (fileId) => {
    dispatch({ type: "removeSet", setKey: fileId });
  };

  const clearRoutes = () => {
    dispatch({ type: "clear" });
    filesRef.current.clear();
  };

  /** Writes every route in a file's current plan back into its mission docs. */
  const applyPlansForFile = (fileId) => {
    const fileEntry = filesRef.current.get(fileId);
    if (!fileEntry) return;
    for (const route of importedRoutes) {
      if (route.fileId === fileId) applyPlanToMsnxDocs(fileEntry.docs, route);
    }
  };

  const exportFile = async (fileId) => {
    const fileEntry = filesRef.current.get(fileId);
    if (!fileEntry) return;

    applyPlansForFile(fileId);
    const filename = fileEntry.originalName.replace(/\.msnx$/i, "") + "_edited.msnx";
    await exportMsnxFile(fileEntry.zip, fileEntry.docs, filename);
  };

  /** Serializes a file group's current (edited) state to a .msnx Blob for saving. */
  const serializeFile = async (fileId) => {
    const fileEntry = filesRef.current.get(fileId);
    if (!fileEntry) return null;
    applyPlansForFile(fileId);
    const blob = await buildMsnxBlob(fileEntry.zip, fileEntry.docs);
    return { blob, fileName: fileEntry.originalName };
  };

  return {
    importedRoutes,
    missionHistory,
    undoMission,
    redoMission,
    closeMission,
    importMsnxFile,
    updatePointPosition,
    insertPoint,
    removeRoute,
    clearRoutes,
    exportFile,
    serializeFile,
    toggleRouteVisibility,
    updateRoutePlan,
    updatePointPlanOverride,
    setPointClock,
    updatePointName,
    refreshRouteElevations,
    applyForecastWinds,
  };
};
