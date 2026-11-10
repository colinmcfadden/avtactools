import { act, renderHook } from "@testing-library/react";
import { useRouteSketch } from "./useRouteSketch";
import { SKETCHES } from "./routeSketchReducer";
import { removePoint, whyPointStays } from "./sketchOps";
import { routesFingerprint } from "../saveDialog/useRouteSaves";
import { beginFieldVisit } from "../undo/fieldVisit";

const drawRoute = (result) => {
  act(() => result.current.startSketch());
  act(() => result.current.addDraftPoint(34, -84));
  act(() => result.current.addDraftPoint(34.1, -84.1));
  act(() => result.current.finishSketch("ROUTE 1"));
  return result.current.sketchedRoutes[0];
};

const stack = (result, key = SKETCHES) => result.current.sketchHistory[key];

const drawPoints = (result, count, designations = {}) => {
  act(() => result.current.startSketch());
  for (let i = 0; i < count; i += 1) {
    act(() => result.current.addDraftPoint(34 + i / 10, -84 - i / 10, designations[i] ?? null));
  }
  act(() => result.current.finishSketch("ROUTE 1"));
  return result.current.sketchedRoutes[0];
};

describe("undo of a route drawn, a point at a time", () => {
  const pointsNow = (result) => result.current.sketchedRoutes[0]?.points ?? null;
  const namesNow = (result) => pointsNow(result).map((point) => point.name);

  it("takes off the last point each time, until two are left and the route goes with them", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawPoints(result, 5);
    const ids = route.points.map((point) => point.id);
    expect(route.points).toHaveLength(5);
    for (const left of [4, 3, 2]) {
      act(() => result.current.undoSketch(SKETCHES));
      expect(result.current.sketchedRoutes).toHaveLength(1);
      expect(result.current.sketchedRoutes[0].id).toBe(route.id);
      expect(pointsNow(result).map((point) => point.id)).toEqual(ids.slice(0, left));
    }
    act(() => result.current.undoSketch(SKETCHES));
    expect(result.current.sketchedRoutes).toEqual([]);
    expect(stack(result).undo).toEqual([]);
  });

  it("puts them back a point at a time, ending on the route as it was finished", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawPoints(result, 4);
    act(() => result.current.undoSketch(SKETCHES));
    act(() => result.current.undoSketch(SKETCHES));
    act(() => result.current.undoSketch(SKETCHES));
    expect(result.current.sketchedRoutes).toEqual([]);
    act(() => result.current.redoSketch(SKETCHES));
    expect(pointsNow(result)).toHaveLength(2);
    act(() => result.current.redoSketch(SKETCHES));
    expect(pointsNow(result)).toHaveLength(3);
    act(() => result.current.redoSketch(SKETCHES));
    expect(result.current.sketchedRoutes).toEqual([route]);
  });

  it("leaves what is left of the route as finishing there would have made it", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawPoints(result, 5);
    expect(namesNow(result)).toEqual([".TGT", ".SP", "", ".RP", ".TGT"]);
    act(() => result.current.undoSketch(SKETCHES));
    expect(namesNow(result)).toEqual([".TGT", ".SP", ".RP", ".TGT"]);
    act(() => result.current.undoSketch(SKETCHES));
    expect(namesNow(result)).toEqual([".TGT", ".SP", ".TGT"]);
  });

  it("keeps a point designated while drawing", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawPoints(result, 4, { 1: { ptType: "turn", name: "HAWK" } });
    act(() => result.current.undoSketch(SKETCHES));
    expect(namesNow(result)[1]).toBe("HAWK");
  });

  it("takes back what was done to the route since before any of its points", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawPoints(result, 3);
    act(() => result.current.updateSketchPointPosition(route.id, route.points[0].id, 35, -85));
    act(() => result.current.undoSketch(SKETCHES));
    expect(pointsNow(result)).toHaveLength(3);
    expect(pointsNow(result)[0].lat).toBe(route.points[0].lat);
    act(() => result.current.undoSketch(SKETCHES));
    expect(pointsNow(result)).toHaveLength(2);
  });

  it("names each step for what it did", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawPoints(result, 3);
    expect(stack(result).undo.map((step) => step.label)).toEqual(["Draw route", "Add point"]);
  });
});

describe("deleting and adding a route's points", () => {
  const route = (result) => result.current.sketchedRoutes[0];
  const shapingOf = (r) => r.points.find((point) => point.kind === "shaping");

  it("adds a named point from the plan in one step, called the next free checkpoint, and undo takes it off", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawRoute(result);
    const route = result.current.sketchedRoutes[0];
    const after = route.points[0].id;
    let added;
    act(() => { added = result.current.addRoutePoint(route.id, after, { lat: 34.5, lon: -84.5 }); });
    const points = result.current.sketchedRoutes[0].points;
    expect(points[1]).toMatchObject({ id: added, kind: "amps", name: ".CP1" });
    expect(stack(result).undo.at(-1).label).toBe("Add point");
    act(() => { result.current.undoSketch(SKETCHES); });
    expect(result.current.sketchedRoutes[0].points.map((p) => p.id)).toEqual(route.points.map((p) => p.id));
  });

  it("deletes one point, and undo brings it back in its place", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 5);
    const gone = shapingOf(drawn);
    act(() => result.current.removeSketchPoint(drawn.id, gone.id));
    expect(route(result).points.map((p) => p.id)).toEqual(drawn.points.filter((p) => p.id !== gone.id).map((p) => p.id));
    expect(stack(result).undo.at(-1).label).toBe("Delete point");
    act(() => result.current.undoSketch(SKETCHES));
    expect(route(result).points.map((p) => p.id)).toEqual(drawn.points.map((p) => p.id));
  });

  it("lets go of what was kept for the point deleted, and of nothing else", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 5);
    const gone = shapingOf(drawn);
    const kept = drawn.points[1];
    act(() => result.current.updatePointPlanOverride(drawn.id, gone.id, { altFt: 500 }));
    act(() => result.current.updatePointPlanOverride(drawn.id, kept.id, { altFt: 700 }));
    act(() => result.current.removeSketchPoint(drawn.id, gone.id));
    expect(route(result).plan.perPoint[gone.id]).toBeUndefined();
    expect(route(result).plan.perPoint[kept.id]).toEqual({ altFt: 700 });
  });

  it("makes the next point the start when the first is deleted", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 5);
    act(() => result.current.removeSketchPoint(drawn.id, drawn.points[0].id));
    expect(route(result).points[0]).toMatchObject({ id: drawn.points[1].id, role: "start" });
  });

  it("refuses to leave a route without two named points", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 2);
    expect(whyPointStays(drawn, drawn.points[0].id)).toMatch(/two points/);
    act(() => result.current.removeSketchPoint(drawn.id, drawn.points[0].id));
    expect(route(result).points).toHaveLength(2);
    expect(stack(result).undo).toHaveLength(1);
  });

  it("names no reason for a point that can go, or for one that is not there", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 5);
    expect(whyPointStays(drawn, shapingOf(drawn).id)).toBeNull();
    expect(whyPointStays(drawn, "nowhere")).toBeNull();
    expect(whyPointStays(undefined, "nowhere")).toBeNull();
  });

  it("keeps two named points once only two are left, though others bend the line", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 4, { 0: { ptType: "turn", name: "A" }, 3: { ptType: "turn", name: "B" } });
    const route4 = {
      ...drawn,
      points: drawn.points.map((p, i) => (i === 1 || i === 2 ? { ...p, kind: "shaping", ptType: null, name: "" } : p)),
    };
    expect(whyPointStays(route4, route4.points[0].id)).toMatch(/two named points/);
    expect(whyPointStays(route4, route4.points[1].id)).toBeNull();
    expect(removePoint(route4, route4.points[0].id)).toBe(route4);
  });

  it("adds a named point on the line in one step", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 3);
    act(() => result.current.insertSketchPoint(drawn.id, 34.05, -84.05, { ptType: "turn", name: ".CP" }));
    expect(route(result).points).toHaveLength(4);
    expect(route(result).points[1]).toMatchObject({ kind: "amps", ptType: "turn", name: ".CP", lat: 34.05 });
    act(() => result.current.undoSketch(SKETCHES));
    expect(route(result).points).toHaveLength(3);
  });

  it("adds a point that only bends the line when it is given no name", () => {
    const { result } = renderHook(() => useRouteSketch());
    const drawn = drawPoints(result, 3);
    act(() => result.current.insertSketchPoint(drawn.id, 34.05, -84.05));
    expect(route(result).points[1]).toMatchObject({ kind: "shaping", name: "" });
  });
});

describe("undo of sketched routes", () => {
  it("takes back a route drawn, and puts it back", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawRoute(result);
    expect(stack(result).undo[0].label).toBe("Draw route");
    act(() => result.current.undoSketch(SKETCHES));
    expect(result.current.sketchedRoutes).toEqual([]);
    act(() => result.current.redoSketch(SKETCHES));
    expect(result.current.sketchedRoutes).toHaveLength(1);
  });

  it("takes back a move, and reads as it was saved", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    const saved = routesFingerprint([route]);
    act(() => result.current.updateSketchPointPosition(route.id, route.points[0].id, 35, -85));
    expect(routesFingerprint(result.current.sketchedRoutes)).not.toBe(saved);
    act(() => result.current.undoSketch(SKETCHES));
    expect(routesFingerprint(result.current.sketchedRoutes)).toBe(saved);
  });

  it("makes one step of a visit to a field, and a new one of the next visit", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    act(() => beginFieldVisit());
    act(() => result.current.updateRoutePlan(route.id, { tempC: 1 }));
    act(() => result.current.updateRoutePlan(route.id, { tempC: 12 }));
    expect(stack(result).undo).toHaveLength(2);
    act(() => beginFieldVisit());
    act(() => result.current.updateRoutePlan(route.id, { tempC: 20 }));
    expect(stack(result).undo).toHaveLength(3);
    const planTemp = () => result.current.sketchedRoutes[0].plan.tempC;
    act(() => result.current.undoSketch(SKETCHES));
    expect(planTemp()).toBe(12);
    act(() => result.current.undoSketch(SKETCHES));
    expect(planTemp()).toBe(route.plan.tempC);
  });

  it("removes the step when a field goes back to where it began", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    act(() => beginFieldVisit());
    act(() => result.current.updateRoutePlan(route.id, { tempC: 1 }));
    act(() => result.current.updateRoutePlan(route.id, { tempC: route.plan.tempC }));
    expect(stack(result).undo).toHaveLength(1);
  });

  it("keeps which routes are hidden out of it", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    act(() => result.current.updateSketchPointPosition(route.id, route.points[0].id, 35, -85));
    act(() => result.current.toggleSketchVisibility(route.id));
    act(() => result.current.undoSketch(SKETCHES));
    expect(result.current.sketchedRoutes[0].visible).toBe(false);
    expect(result.current.sketchedRoutes[0].points[0].lat).toBe(route.points[0].lat);
  });

  it("brings a deleted route back in its place", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawRoute(result);
    act(() => result.current.startSketch());
    act(() => result.current.addDraftPoint(30, -80));
    act(() => result.current.addDraftPoint(30.1, -80.1));
    act(() => result.current.finishSketch("ROUTE 2"));
    const [first, second] = result.current.sketchedRoutes;
    act(() => result.current.removeSketchRoute(first.id));
    act(() => result.current.undoSketch(SKETCHES));
    expect(result.current.sketchedRoutes.map((r) => r.id)).toEqual([first.id, second.id]);
  });

  it("does not forget a route set's own history when another is edited", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    let setId;
    act(() => {
      setId = result.current.openSavedRoutes([{ ...route, id: "saved" }]);
    });
    const opened = result.current.sketchedRoutes.find((r) => r.setId === setId);
    act(() => result.current.updateSketchPointPosition(opened.id, opened.points[0].id, 40, -90));
    expect(stack(result, setId).undo).toHaveLength(1);
    expect(stack(result).undo).toHaveLength(1);
    act(() => result.current.undoSketch(setId));
    expect(result.current.sketchedRoutes.find((r) => r.setId === setId).points[0].lat).toBe(opened.points[0].lat);
    expect(result.current.sketchedRoutes.find((r) => !r.setId).points[0].lat).toBe(route.points[0].lat);
  });

  it("forgets a set's history when it is closed", () => {
    const { result } = renderHook(() => useRouteSketch());
    drawRoute(result);
    act(() => result.current.removeRouteSet(null));
    expect(result.current.sketchedRoutes).toEqual([]);
    expect(stack(result)).toBeUndefined();
  });

  it("clears redo for a new edit, and not for a set put in from elsewhere", () => {
    const { result } = renderHook(() => useRouteSketch());
    const route = drawRoute(result);
    act(() => result.current.updateSketchPointPosition(route.id, route.points[0].id, 35, -85));
    act(() => result.current.undoSketch(SKETCHES));
    expect(stack(result).redo).toHaveLength(1);
    act(() => result.current.updateSketchPointPosition(route.id, route.points[0].id, 36, -86));
    expect(stack(result).redo).toEqual([]);
  });
});
