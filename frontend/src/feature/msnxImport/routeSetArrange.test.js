import { act, renderHook } from "@testing-library/react";
import { isOpenedSetId, useRouteSketch } from "./useRouteSketch";
import { SKETCHES, withoutOrphans } from "./routeSketchReducer";
import { pickRedo, pickUndo } from "../undo/useUndo";

// Arranging routes among sets: a new set, a route moved from one set to another, two routes of a
// set made one, a route's colour. Each is one thing done, and one Undo takes it back.

const draw = (result, name, setId = null, points = [[34, -84], [34.1, -84.1], [34.2, -84.2], [34.3, -84.3]]) => {
  act(() => result.current.startSketch());
  points.forEach(([lat, lon]) => act(() => result.current.addDraftPoint(lat, lon)));
  act(() => result.current.finishSketch(name, setId));
  return result.current.sketchedRoutes.find((route) => route.name === name);
};

const setsNow = (result) => result.current.sketchSets.map((set) => [set.setId, set.routes.map((route) => route.name)]);
const routeNamed = (result, name) => result.current.sketchedRoutes.find((route) => route.name === name);

// One Undo and Redo as the app has them: the newest step among the sets' histories (feature/undo/useUndo.js).
const sources = (result) =>
  Object.entries(result.current.sketchHistory).map(([key, history]) => ({ key, history }));
const undo = (result) => {
  const target = pickUndo(sources(result));
  if (target) act(() => result.current.undoSketch(target.source.key));
  return target?.step.label ?? null;
};
const redo = (result) => {
  const target = pickRedo(sources(result));
  if (target) act(() => result.current.redoSketch(target.source.key));
  return target?.step.label ?? null;
};
// Every step that draws a route, taken back: a route of four points is three steps.
const settle = (result) => JSON.parse(JSON.stringify(result.current.sketchedRoutes));

describe("a new route set", () => {
  it("is there from the moment it is made, with no routes, beside this session's sketches", () => {
    const { result } = renderHook(() => useRouteSketch());
    draw(result, "ROUTE 1");
    let made;
    act(() => { made = result.current.newRouteSet(); });
    expect(isOpenedSetId(made)).toBe(true);
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1"]], [made, []]]);
  });

  it("takes the routes sketched into it, and is still there when they have gone", () => {
    const { result } = renderHook(() => useRouteSketch());
    let made;
    act(() => { made = result.current.newRouteSet(); });
    const route = draw(result, "ROUTE 1", made);
    expect(route.setId).toBe(made);
    expect(setsNow(result)).toEqual([[made, ["ROUTE 1"]]]);
    act(() => result.current.removeSketchRoute(route.id));
    expect(setsNow(result)).toEqual([[made, []]]);
  });

  it("goes when it is closed, and two made are two sets", () => {
    const { result } = renderHook(() => useRouteSketch());
    let first;
    let second;
    act(() => { first = result.current.newRouteSet(); });
    act(() => { second = result.current.newRouteSet(); });
    expect(first).not.toBe(second);
    expect(setsNow(result)).toEqual([[first, []], [second, []]]);
    act(() => result.current.removeRouteSet(first));
    expect(setsNow(result)).toEqual([[second, []]]);
  });
});

describe("a route moved to another set", () => {
  const twoSets = () => {
    const hook = renderHook(() => useRouteSketch());
    const { result } = hook;
    const one = draw(result, "ROUTE 1");
    const two = draw(result, "ROUTE 2");
    let made;
    act(() => { made = result.current.newRouteSet(); });
    const three = draw(result, "ROUTE 3", made);
    return { result, one, two, three, made };
  };

  it("leaves the one and is in the other, after the routes that set has, whole", () => {
    const { result, one, made } = twoSets();
    act(() => result.current.moveSketchRoute(one.id, made));
    expect(setsNow(result)).toEqual([[null, ["ROUTE 2"]], [made, ["ROUTE 3", "ROUTE 1"]]]);
    const { setId, ...moved } = routeNamed(result, "ROUTE 1");
    expect(setId).toBe(made);
    expect(moved).toEqual(one);
  });

  it("back to this session's own sketches is filed under no set", () => {
    const { result, three, made } = twoSets();
    act(() => result.current.moveSketchRoute(three.id, null));
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1", "ROUTE 2", "ROUTE 3"]], [made, []]]);
    expect("setId" in routeNamed(result, "ROUTE 3")).toBe(false);
  });

  it("to the set it is in, or of a route that is not there, is nothing", () => {
    const { result, one, made } = twoSets();
    const before = result.current.sketchedRoutes;
    const history = result.current.sketchHistory;
    act(() => result.current.moveSketchRoute(one.id, null));
    act(() => result.current.moveSketchRoute("nobody", made));
    // Named as being in a set it is not in.
    act(() => result.current.moveSketchRoute(one.id, null, made));
    expect(result.current.sketchedRoutes).toBe(before);
    expect(result.current.sketchHistory).toBe(history);
  });

  it("is one thing done: one Undo puts it back in both sets, and one Redo moves it again", () => {
    const { result, one, made } = twoSets();
    const before = settle(result);
    act(() => result.current.moveSketchRoute(one.id, made));
    const after = settle(result);
    expect(after).not.toEqual(before);

    expect(undo(result)).toBe("Move route");
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1", "ROUTE 2"]], [made, ["ROUTE 3"]]]);
    expect(settle(result)).toEqual(before);
    // What undo takes next is the drawing of a route, not half a move.
    expect(pickUndo(sources(result)).step.label).not.toBe("Move route");

    expect(redo(result)).toBe("Move route");
    expect(settle(result)).toEqual(after);
    expect(pickRedo(sources(result))).toBeNull();
    expect(undo(result)).toBe("Move route");
    expect(settle(result)).toEqual(before);
  });

  it("the route is never in both sets or in neither, whichever set the undo is asked of", () => {
    [SKETCHES, "made"].forEach((which) => {
      const { result, one, made } = twoSets();
      const key = which === "made" ? made : SKETCHES;
      act(() => result.current.moveSketchRoute(one.id, made));
      act(() => result.current.undoSketch(key));
      expect(result.current.sketchedRoutes.filter((route) => route.id === one.id)).toHaveLength(1);
      expect(setsNow(result)).toEqual([[null, ["ROUTE 1", "ROUTE 2"]], [made, ["ROUTE 3"]]]);
      act(() => result.current.redoSketch(key));
      expect(result.current.sketchedRoutes.filter((route) => route.id === one.id)).toHaveLength(1);
      expect(routeNamed(result, "ROUTE 1").setId).toBe(made);
    });
  });

  it("an edit made after the move is taken back first, then the move", () => {
    const { result, one, three, made } = twoSets();
    act(() => result.current.moveSketchRoute(one.id, made));
    act(() => result.current.setSketchRouteColor(three.id, "#FFFFFF"));
    act(() => result.current.removeSketchRoute(routeNamed(result, "ROUTE 2").id));
    expect(undo(result)).toBe("Delete route");
    expect(undo(result)).toBe("Change route colour");
    expect(undo(result)).toBe("Move route");
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1", "ROUTE 2"]], [made, ["ROUTE 3"]]]);
    expect(routeNamed(result, "ROUTE 3").color).toBe(three.color);
  });

  it("moved out and back again is two steps, each taken back whole", () => {
    const { result, one, made } = twoSets();
    act(() => result.current.moveSketchRoute(one.id, made));
    act(() => result.current.moveSketchRoute(one.id, null));
    expect(setsNow(result)).toEqual([[null, ["ROUTE 2", "ROUTE 1"]], [made, ["ROUTE 3"]]]);
    expect(undo(result)).toBe("Move route");
    expect(setsNow(result)).toEqual([[null, ["ROUTE 2"]], [made, ["ROUTE 3", "ROUTE 1"]]]);
    expect(undo(result)).toBe("Move route");
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1", "ROUTE 2"]], [made, ["ROUTE 3"]]]);
  });

  it("whether it is hidden goes with it, and is each person's own: undo keeps it as it is now", () => {
    const { result, one, made } = twoSets();
    act(() => result.current.toggleSketchVisibility(one.id));
    act(() => result.current.moveSketchRoute(one.id, made));
    expect(routeNamed(result, "ROUTE 1").visible).toBe(false);
    undo(result);
    expect(routeNamed(result, "ROUTE 1").visible).toBe(false);
  });

  it("the last route moved out of this session's sketches leaves no such set, and undo brings it back", () => {
    const hook = renderHook(() => useRouteSketch());
    const { result } = hook;
    const one = draw(result, "ROUTE 1");
    let made;
    act(() => { made = result.current.newRouteSet(); });
    act(() => result.current.moveSketchRoute(one.id, made));
    expect(setsNow(result)).toEqual([[made, ["ROUTE 1"]]]);
    undo(result);
    expect(setsNow(result)).toEqual([[null, ["ROUTE 1"]], [made, []]]);
  });

  it("a set that a route was moved out of stays until it is closed, though it was not made here", () => {
    const { result } = renderHook(() => useRouteSketch());
    draw(result, "MINE");
    let opened;
    act(() => { opened = result.current.openSavedRoutes([{ id: "saved-1", name: "SAVED", points: [{ id: "p1", lat: 1, lon: 1 }, { id: "p2", lat: 2, lon: 2 }] }]); });
    expect(isOpenedSetId(opened)).toBe(true);
    act(() => result.current.moveSketchRoute(routeNamed(result, "SAVED").id, null, opened));
    expect(setsNow(result)).toEqual([[null, ["MINE", "SAVED"]], [opened, []]]);
    act(() => result.current.removeRouteSet(opened));
    expect(setsNow(result)).toEqual([[null, ["MINE", "SAVED"]]]);
  });

  it("where two sets hold a route of one id, the set named is the one it leaves", () => {
    const { result } = renderHook(() => useRouteSketch());
    const twin = { id: "r1", name: "TWIN", points: [{ id: "p1", lat: 1, lon: 1 }, { id: "p2", lat: 2, lon: 2 }] };
    act(() => result.current.loadSketchRoutes([twin], { setId: "pack:a:one" }));
    act(() => result.current.loadSketchRoutes([twin], { setId: "pack:a:two" }));
    act(() => result.current.loadSketchRoutes([], { setId: "pack:a:three" }));
    act(() => result.current.moveSketchRoute("r1", "pack:a:three", "pack:a:two"));
    expect(result.current.sketchedRoutes.map((route) => route.setId)).toEqual(["pack:a:one", "pack:a:three"]);
    // Taking the move back leaves the other set's route of that id alone.
    act(() => result.current.undoSketch("pack:a:two"));
    expect(result.current.sketchedRoutes.map((route) => route.setId).sort()).toEqual(["pack:a:one", "pack:a:two"]);
  });
});

describe("half a move is never taken alone", () => {
  const moved = () => {
    const hook = renderHook(() => useRouteSketch());
    const { result } = hook;
    const one = draw(result, "ROUTE 1");
    draw(result, "ROUTE 2");
    let made;
    act(() => { made = result.current.newRouteSet(); });
    draw(result, "ROUTE 3", made);
    act(() => result.current.moveSketchRoute(one.id, made));
    return { result, one, made };
  };
  const labels = (result, key, stack = "undo") => (result.current.sketchHistory[key]?.[stack] ?? []).map((step) => step.label);
  const once = (result, id) => result.current.sketchedRoutes.filter((route) => route.id === id).length;

  it("the set it went to closed: the set it left can no longer take the move back, nor anything before it", () => {
    const { result, one, made } = moved();
    expect(labels(result, SKETCHES).at(-1)).toBe("Move route");
    act(() => result.current.removeRouteSet(made));
    // Its versions from before the move have the route in them: put back, it would be a route nobody moved back.
    expect(labels(result, SKETCHES)).toEqual([]);
    expect(undo(result)).toBeNull();
    expect(once(result, one.id)).toBe(0);
    expect(setsNow(result)).toEqual([[null, ["ROUTE 2"]]]);
  });

  it("the set it left closed: the set it went to keeps the route, and what was done there since", () => {
    const { result, one, made } = moved();
    act(() => result.current.setSketchRouteColor(one.id, "#FFFFFF"));
    act(() => result.current.removeRouteSet(null));
    expect(labels(result, made)).toEqual(["Change route colour"]);
    expect(undo(result)).toBe("Change route colour");
    expect(undo(result)).toBeNull();
    expect(setsNow(result)).toEqual([[made, ["ROUTE 3", "ROUTE 1"]]]);
  });

  it("an edit in one set after the move was undone ends its redo in both", () => {
    const { result, one, made } = moved();
    undo(result);
    expect(labels(result, SKETCHES, "redo")).toEqual(["Move route"]);
    expect(labels(result, made, "redo")).toEqual(["Move route"]);
    act(() => result.current.setSketchRouteColor(one.id, "#FFFFFF"));
    expect(labels(result, SKETCHES, "redo")).toEqual([]);
    expect(labels(result, made, "redo")).toEqual([]);
    expect(redo(result)).toBeNull();
    expect(once(result, one.id)).toBe(1);
    expect(routeNamed(result, "ROUTE 1").setId).toBeUndefined();
  });

  it("routes opened into one of the sets end what could be undone there, and the move with it", () => {
    const { result, one, made } = moved();
    act(() => result.current.loadSketchRoutes([{ id: "x", name: "LATER", points: [{ id: "p1", lat: 1, lon: 1 }, { id: "p2", lat: 2, lon: 2 }] }], { setId: made }));
    expect(labels(result, made)).toEqual([]);
    expect(labels(result, SKETCHES)).toEqual([]);
    expect(undo(result)).toBeNull();
    expect(once(result, one.id)).toBe(1);
  });

  it("asked of a set whose half is not next, changes nothing", () => {
    const { result, one, made } = moved();
    act(() => result.current.removeSketchRoute(routeNamed(result, "ROUTE 2").id));
    const before = result.current.sketchedRoutes;
    // The newest step where the route went is its half of the move; where it left, a deletion since.
    act(() => result.current.undoSketch(made));
    expect(result.current.sketchedRoutes).toBe(before);
    expect(once(result, one.id)).toBe(1);
  });
});

describe("histories with half a move in them", () => {
  const step = (label, link) => ({ label, before: [], after: [], key: null, seq: 1, ...(link === undefined ? {} : { link }) });
  const state = (history) => ({ routes: [], history });

  it("are left alone while both halves are there", () => {
    const whole = state({ a: { undo: [step("Draw"), step("Move", 7)], redo: [] }, b: { undo: [step("Move", 7), step("Edit")], redo: [] } });
    expect(withoutOrphans(whole)).toBe(whole);
    const plain = state({ a: { undo: [step("Draw")], redo: [step("Edit")] } });
    expect(withoutOrphans(plain)).toBe(plain);
  });

  it("lose a half with no other, and what is under it, but not what came after", () => {
    const cut = withoutOrphans(state({ a: { undo: [step("Draw"), step("Move", 7), step("Edit")], redo: [] }, b: { undo: [step("Draw")], redo: [] } }));
    expect(cut.history.a.undo.map((s) => s.label)).toEqual(["Edit"]);
    expect(cut.history.b.undo.map((s) => s.label)).toEqual(["Draw"]);
  });

  it("follow a chain: a half lost under another move takes that move's other half too", () => {
    const cut = withoutOrphans(state({
      a: { undo: [step("Move", 2), step("Move", 1)], redo: [] },   // 1's other half is gone, and 2 is under it
      b: { undo: [step("Draw"), step("Move", 2), step("Edit")], redo: [] },
    }));
    expect(cut.history.a.undo).toEqual([]);
    expect(cut.history.b.undo.map((s) => s.label)).toEqual(["Edit"]);
  });

  it("in redo, lose what would be made again after the lost half", () => {
    // Redo makes the last of the list first.
    const cut = withoutOrphans(state({ a: { undo: [], redo: [step("Later"), step("Move", 7), step("Sooner")] }, b: { undo: [], redo: [] } }));
    expect(cut.history.a.redo.map((s) => s.label)).toEqual(["Sooner"]);
  });

  it("an undone half and a done half of one move are not a pair", () => {
    const cut = withoutOrphans(state({ a: { undo: [step("Move", 7)], redo: [] }, b: { undo: [], redo: [step("Move", 7)] } }));
    expect(cut.history.a.undo).toEqual([]);
    expect(cut.history.b.redo).toEqual([]);
  });
});

describe("two routes of a set made one", () => {
  it("is the first with the second's points after its own, and the second is gone", () => {
    const { result } = renderHook(() => useRouteSketch());
    const one = draw(result, "ROUTE 1");
    const two = draw(result, "ROUTE 2", null, [[35, -85], [35.1, -85.1], [35.2, -85.2]]);
    let done;
    act(() => { done = result.current.combineSketchRoutes(one.id, two.id); });
    expect(done).toBe(true);
    expect(result.current.sketchedRoutes).toHaveLength(1);
    const joined = result.current.sketchedRoutes[0];
    expect(joined.id).toBe(one.id);
    expect(joined.name).toBe("ROUTE 1");
    expect(joined.color).toBe(one.color);
    expect(joined.points.map((p) => p.id)).toEqual([...one.points, ...two.points].map((p) => p.id));
  });

  it("is one step: Undo brings both routes back as they were, in their places", () => {
    const { result } = renderHook(() => useRouteSketch());
    const one = draw(result, "ROUTE 1");
    const two = draw(result, "ROUTE 2", null, [[35, -85], [35.1, -85.1]]);
    draw(result, "ROUTE 3", null, [[36, -86], [36.1, -86.1]]);
    const before = settle(result);
    act(() => { result.current.combineSketchRoutes(two.id, one.id); });
    expect(result.current.sketchedRoutes.map((route) => route.name)).toEqual(["ROUTE 2", "ROUTE 3"]);
    expect(undo(result)).toBe("Combine routes");
    expect(settle(result)).toEqual(before);
    expect(redo(result)).toBe("Combine routes");
    expect(result.current.sketchedRoutes.map((route) => route.name)).toEqual(["ROUTE 2", "ROUTE 3"]);
  });

  it("keeps the first route hidden if it was, whatever the second was", () => {
    const { result } = renderHook(() => useRouteSketch());
    const one = draw(result, "ROUTE 1");
    const two = draw(result, "ROUTE 2", null, [[35, -85], [35.1, -85.1]]);
    act(() => result.current.toggleSketchVisibility(one.id));
    act(() => { result.current.combineSketchRoutes(one.id, two.id); });
    expect(result.current.sketchedRoutes[0].visible).toBe(false);
  });

  it("is refused for a route with itself, one that is gone, or routes of two sets", () => {
    const { result } = renderHook(() => useRouteSketch());
    const one = draw(result, "ROUTE 1");
    let made;
    act(() => { made = result.current.newRouteSet(); });
    const two = draw(result, "ROUTE 2", made);
    const before = result.current.sketchedRoutes;
    let answers;
    act(() => {
      answers = [
        result.current.combineSketchRoutes(one.id, one.id),
        result.current.combineSketchRoutes(one.id, "nobody"),
        result.current.combineSketchRoutes("nobody", one.id),
        result.current.combineSketchRoutes(one.id, two.id),
      ];
    });
    expect(answers).toEqual([false, false, false, false]);
    expect(result.current.sketchedRoutes).toBe(before);
  });
});

describe("a route's colour", () => {
  it("changes to one of the choices as a step, and to nothing else", () => {
    const { result } = renderHook(() => useRouteSketch());
    const one = draw(result, "ROUTE 1");
    act(() => result.current.setSketchRouteColor(one.id, "#00C7BE"));
    expect(result.current.sketchedRoutes[0].color).toBe("#00C7BE");
    act(() => result.current.setSketchRouteColor(one.id, "not a colour"));
    act(() => result.current.setSketchRouteColor(one.id, "#00c7be"));
    expect(result.current.sketchedRoutes[0].color).toBe("#00C7BE");
    expect(undo(result)).toBe("Change route colour");
    expect(result.current.sketchedRoutes[0].color).toBe(one.color);
    // The two that changed nothing were no steps.
    expect(pickUndo(sources(result)).step.label).not.toBe("Change route colour");
  });
});
