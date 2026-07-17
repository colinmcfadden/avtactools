import { useCallback, useRef, useState } from "react";
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

const generateId = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const useMsnxImport = () => {
  const [importedRoutes, setImportedRoutes] = useState([]);
  const filesRef = useRef(new Map()); // fileId -> { zip, docs, originalName }

  const importMsnxFile = async (file) => {
    const { zip, docs, routes } = await parseMsnxFile(file);
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

    setImportedRoutes((prev) => [...prev, ...newRoutes]);
    return { fileId };
  };

  const toggleRouteVisibility = (routeId) => {
    setImportedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId ? { ...route, visible: !route.visible } : route,
      ),
    );
  };

  // NOTE for both handlers below: the XML-doc mutation must happen OUTSIDE the
  // setImportedRoutes updater. React re-runs updater functions (StrictMode
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

    setImportedRoutes((prev) =>
      prev.map((route) => {
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
  }, [importedRoutes]);

  const insertPoint = useCallback((routeId, lat, lon) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const fileEntry = filesRef.current.get(route.fileId);
    if (!fileEntry) return;

    // May throw (e.g. no connecting leg near the click) — deliberately not
    // caught here so the caller's try/catch can surface it to the user.
    const { afterPointId, point: newPoint } = insertPointOnRoute(fileEntry.docs, route, lat, lon);

    setImportedRoutes((prev) =>
      prev.map((r) => {
        if (r.id !== routeId) return r;
        const afterIndex = r.points.findIndex((p) => p.id === afterPointId);
        if (afterIndex === -1) return r;
        const points = [...r.points];
        points.splice(afterIndex + 1, 0, newPoint);
        return { ...r, points };
      }),
    );
  }, [importedRoutes]);

  // --- inline plan editing (mirrors useRouteSketch, for imported routes) ---
  // Plan edits live in React state and are written back into the mission docs
  // at export/serialize time via applyPlanToMsnxDocs; coordinate/name edits
  // mutate the docs immediately (like the drag handler above).

  const updateRoutePlan = (routeId, patch) => {
    setImportedRoutes((prev) =>
      prev.map((route) =>
        route.id === routeId
          ? { ...route, plan: { ...defaultRoutePlan(), ...route.plan, ...patch } }
          : route,
      ),
    );
  };

  const updatePointPlanOverride = (routeId, pointId, patch) => {
    setImportedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        const plan = { ...defaultRoutePlan(), ...route.plan };
        const perPoint = { ...plan.perPoint };
        if (patch === null) delete perPoint[pointId];
        else perPoint[pointId] = { ...perPoint[pointId], ...patch };
        return { ...route, plan: { ...plan, perPoint } };
      }),
    );
  };

  /** Sets/clears a point's clock (TOT); only one point anchors at a time. */
  const setPointClock = (routeId, pointId, clock) => {
    setImportedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
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
    setImportedRoutes((prev) =>
      prev.map((r) => {
        if (r.id !== routeId) return r;
        return {
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
        };
      }),
    );
  };

  const refreshRouteElevations = async (routeId) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return;
    const elevations = await fetchPointElevationsFt(route);
    setImportedRoutes((prev) =>
      prev.map((r) =>
        r.id === routeId ? { ...r, elevations: { ...r.elevations, ...elevations } } : r,
      ),
    );
    return elevations;
  };

  const applyForecastWinds = async (routeId) => {
    const route = importedRoutes.find((r) => r.id === routeId);
    if (!route) return { error: "Route not found." };
    const { winds, amps, error } = await fetchForecastWinds(route);
    if (error) return { error };
    setImportedRoutes((prev) =>
      prev.map((r) =>
        r.id === routeId ? { ...r, plan: mergeWindsIntoPlan(r.plan, amps, winds) } : r,
      ),
    );
    return { winds };
  };

  const removeRoute = (id) => {
    setImportedRoutes((prev) => prev.filter((route) => route.id !== id));
  };

  const clearRoutes = () => {
    setImportedRoutes([]);
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
