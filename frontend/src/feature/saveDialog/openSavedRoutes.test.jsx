import { act, renderHook } from "@testing-library/react";
import { useMemo } from "react";
import { isOpenedSetId, useRouteSketch } from "../msnxImport/useRouteSketch";
import { ToastProvider } from "../ui/Toast";
import useRouteSaves, { SKETCHES } from "./useRouteSaves";

// The sketch names new points with crypto.randomUUID, which browsers have and this test's DOM does not.
const hadCrypto = "crypto" in global;
beforeAll(() => {
  if (!hadCrypto || !global.crypto?.randomUUID) global.crypto = { ...(global.crypto ?? {}), randomUUID: require("crypto").randomUUID };
});
afterAll(() => {
  if (!hadCrypto) delete global.crypto;
});

const savedRoute = (id, name) => ({
  id,
  name,
  points: [
    { id: `${id}-1`, lat: 34.5, lon: -84.1, kind: "amps", ptType: "start", name: ".SP", role: "start" },
    { id: `${id}-2`, lat: 34.6, lon: -84.2, kind: "amps", ptType: "ip", name: ".RP", role: "waypoint" },
  ],
});
const ALPHA = { id: 42, name: "ALPHA", updated_at: "2026-10-01T10:00:00" };
const BRAVO = { id: 7, name: "BRAVO", updated_at: "2026-09-20T08:00:00" };

// The sketch and its saves composed as App composes them: the route sets, and opening a saved one.
const useHarness = (library) => {
  const sketch = useRouteSketch();
  const sets = useMemo(
    () => sketch.sketchSets.map(({ setId, routes }) => ({ key: setId ?? SKETCHES, kind: "sketch", routes })),
    [sketch.sketchSets],
  );
  const saves = useRouteSaves({ sets, library, signedIn: true });
  const open = (entry, routes) => saves.adopt(sketch.openSavedRoutes(routes) ?? SKETCHES, entry);
  return { sketch, saves, sets, open };
};

const setup = () => {
  const library = {
    savedRoutes: [],
    updateSketch: jest.fn().mockResolvedValue({ updated_at: "2026-10-07T12:00:00" }),
    saveSketch: jest.fn(),
  };
  const view = renderHook(() => useHarness(library), { wrapper: ToastProvider });
  return { ...view, library };
};

const sketchRoute = (result, name) => {
  act(() => result.current.sketch.startSketch());
  act(() => {
    result.current.sketch.addDraftPoint(34, -84);
    result.current.sketch.addDraftPoint(34.1, -84.1);
  });
  act(() => {
    result.current.sketch.finishSketch(name);
  });
};

const names = (set) => set.routes.map((route) => route.name);
const openedSet = (result) => result.current.sets.find((set) => isOpenedSetId(set.key));

describe("opening a saved set of routes", () => {
  it("into a session with no sketches makes it the sketches, and new sketches join it", () => {
    const { result } = setup();
    act(() => result.current.open(ALPHA, [savedRoute("a", "ALPHA 1")]));
    expect(result.current.sets.map((set) => set.key)).toEqual([SKETCHES]);
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: { id: 42, name: "ALPHA" }, dirty: false, savedAt: ALPHA.updated_at });

    sketchRoute(result, "ROUTE 2");
    expect(names(result.current.sets[0])).toEqual(["ALPHA 1", "ROUTE 2"]);
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: { id: 42 }, dirty: true });
  });

  it("beside unsaved sketches opens it as a set of its own, and leaves the sketches unsaved", async () => {
    const { result, library } = setup();
    sketchRoute(result, "ROUTE 1");
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: null, dirty: true });

    act(() => result.current.open(ALPHA, [savedRoute("a", "ALPHA 1")]));
    const sketches = result.current.sets.find((set) => set.key === SKETCHES);
    const alpha = openedSet(result);
    expect(names(sketches)).toEqual(["ROUTE 1"]);
    expect(names(alpha)).toEqual(["ALPHA 1"]);
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: null, dirty: true });
    expect(result.current.saves.stateOf(alpha.key)).toMatchObject({ link: { id: 42, name: "ALPHA" }, name: "ALPHA", dirty: false });

    // Saving the opened set writes only its own routes to its own record.
    act(() => result.current.sketch.updateRoutePlan(alpha.routes[0].id, { tempC: 30 }));
    expect(result.current.saves.stateOf(alpha.key).dirty).toBe(true);
    await act(async () => {
      await result.current.saves.save(alpha.key);
    });
    expect(library.updateSketch).toHaveBeenCalledTimes(1);
    const [recordId, sent, name] = library.updateSketch.mock.calls[0];
    expect([recordId, sent.map((route) => route.name), name]).toEqual([42, ["ALPHA 1"], "ALPHA"]);
    // Where the set is filed here is not part of the record.
    sent.forEach((route) => expect(route).not.toHaveProperty("setId"));
    expect(result.current.saves.stateOf(alpha.key).dirty).toBe(false);
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: null, dirty: true });
  });

  it("beside sketches saved as another record keeps both records", () => {
    const { result } = setup();
    act(() => result.current.open(BRAVO, [savedRoute("b", "BRAVO 1")]));
    act(() => result.current.open(ALPHA, [savedRoute("a", "ALPHA 1")]));
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: { id: 7, name: "BRAVO" }, dirty: false });
    expect(result.current.saves.stateOf(openedSet(result).key)).toMatchObject({ link: { id: 42, name: "ALPHA" }, dirty: false });
    expect(result.current.sets.map(names)).toEqual([["BRAVO 1"], ["ALPHA 1"]]);
  });

  it("gives the routes new ids, so a record and its copy can be open together", () => {
    const { result } = setup();
    act(() => result.current.open(ALPHA, [savedRoute("a", "ALPHA 1")]));
    act(() => result.current.open({ ...ALPHA, id: 43, name: "ALPHA (2)" }, [savedRoute("a", "ALPHA 1")]));
    const ids = result.current.sketch.sketchedRoutes.map((route) => route.id);
    expect(new Set(ids).size).toBe(2);
    // App sends a right-click on a route line to the sketch by this prefix.
    ids.forEach((id) => expect(id).toMatch(/^sketch-/));
  });

  it("files the routes by where they are opened, not by anything the record carries", () => {
    const { result } = setup();
    act(() => result.current.open(ALPHA, [{ ...savedRoute("a", "ALPHA 1"), setId: "opened-old" }]));
    expect(result.current.sets.map((set) => set.key)).toEqual([SKETCHES]);
    expect(result.current.sketch.sketchedRoutes[0]).not.toHaveProperty("setId");
  });

  it("closing the opened set drops it and its link, and nothing else", () => {
    const { result } = setup();
    sketchRoute(result, "ROUTE 1");
    act(() => result.current.open(ALPHA, [savedRoute("a", "ALPHA 1")]));
    const alpha = openedSet(result);
    act(() => alpha.routes.forEach((route) => result.current.sketch.removeSketchRoute(route.id)));
    expect(result.current.sets.map((set) => set.key)).toEqual([SKETCHES]);
    expect(result.current.saves.stateOf(alpha.key).link).toBeNull();
    expect(result.current.saves.stateOf(SKETCHES)).toMatchObject({ link: null, dirty: true });
  });
});
