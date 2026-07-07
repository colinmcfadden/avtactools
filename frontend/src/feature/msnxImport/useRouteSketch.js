import { useState } from "react";
import { nextRouteColor } from "./colorPalette";
import { findNearestAdjacentIndex } from "./mutateMsnx";
import { buildSketchMsnx } from "./createMsnx";

const generateId = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const useRouteSketch = () => {
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

  const addDraftPoint = (lat, lon) => {
    setDraftPoints((prev) => [...prev, { lat, lon }]);
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
    // between them. First and last are auto-designated — legs need endpoints.
    const lastIndex = draftPoints.length - 1;
    const route = {
      id: generateId("sketch"),
      name,
      color: nextRouteColor(),
      visible: true,
      points: draftPoints.map((p, i) => {
        const isEndpoint = i === 0 || i === lastIndex;
        return {
          id: crypto.randomUUID(),
          lat: p.lat,
          lon: p.lon,
          ele: null,
          kind: isEndpoint ? "amps" : "shaping",
          ptType: isEndpoint ? "turn" : null,
          name: isEndpoint ? (i === 0 ? ".SP" : `.CP${lastIndex}`) : "",
          role: i === 0 ? "start" : "waypoint",
        };
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

  const updateSketchPointPosition = (routeId, pointId, lat, lon) => {
    setSketchedRoutes((prev) =>
      prev.map((route) => {
        if (route.id !== routeId) return route;
        return {
          ...route,
          points: route.points.map((p) => (p.id === pointId ? { ...p, lat, lon } : p)),
        };
      }),
    );
  };

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

  /**
   * Restores saved sketch routes. Route ids are regenerated so loading the
   * same save twice can't collide (point ids are UUIDs and stay as saved);
   * the "sketch-" prefix matters — App routes context-menu actions on it.
   */
  const loadSketchRoutes = (routes) => {
    const restored = routes.map((route) => ({
      ...route,
      id: generateId("sketch"),
      color: route.color || nextRouteColor(),
      visible: true,
    }));
    setSketchedRoutes((prev) => [...prev, ...restored]);
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
      await buildSketchMsnx(sketchedRoutes);
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
    loadSketchRoutes,
    removeSketchRoute,
    toggleSketchVisibility,
    exportSketches,
  };
};
