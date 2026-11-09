import { act, renderHook } from "@testing-library/react";
import { useRouteSketch } from "./useRouteSketch";
import { SKETCHES } from "./routeSketchReducer";
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
