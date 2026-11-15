import { act, renderHook } from "@testing-library/react";
import { useFlots } from "../flot/useFlots";
import { restoreSketchRoute, useRouteSketch } from "../msnxImport/useRouteSketch";
import { useFakePack } from "./fakePack";
import { routeSetData } from "./packRoutes";
import { usePackRoutes } from "./usePackRoutes";

// Routes arranged in a Mission Pack: moved between two of its sets, joined, recoloured. Each reaches the
// pack as operations on the items it changed, in words, and an undo sends the change back.

const point = (id, lat, lon, name) => ({ id, lat, lon, kind: "amps", ptType: "turn", name, role: "waypoint", ele: null });
const RED = { id: "r-1", name: "RED 1", color: "#FF453A", points: [point("p-1", 34.5, -84.1, ".SP"), point("p-2", 34.6, -84.2, ".RP")] };
const BLUE = { id: "r-2", name: "BLUE 1", color: "#0A84FF", points: [point("q-1", 34.7, -84.3, ".CP1"), point("q-2", 34.8, -84.4, ".LZ")] };
const GREEN = { id: "r-3", name: "GREEN 1", color: "#32D74B", points: [point("g-1", 35, -85, ".A"), point("g-2", 35.1, -85.1, ".B")] };

const item = (uuid, name, routes) => ({ uuid, kind: "route", name, data: routeSetData(routes.map((route) => restoreSketchRoute(route, "x"))) });
const SERIAL_1 = item("rt-1", "SERIAL 1", [RED, BLUE]);
const SERIAL_2 = item("rt-2", "SERIAL 2", [GREEN]);
const ID_1 = "pack:p-1:rt-1";
const ID_2 = "pack:p-1:rt-2";

const useHarness = (initial) => {
  const sketch = useRouteSketch();
  const flotTools = useFlots();
  const pack = useFakePack(initial);
  const routes = usePackRoutes({
    packUuid: "p-1", items: pack.items, edit: pack.edit, actor: "Colin M.",
    sketchedRoutes: sketch.sketchedRoutes, flots: flotTools.flots, setFlotsOf: flotTools.setFlotsOf, loadSketchRoutes: sketch.loadSketchRoutes,
    replaceRouteSet: sketch.replaceRouteSet, removeRouteSet: sketch.removeRouteSet, newId: () => "new",
  });
  return { sketch, pack, routes };
};

const settle = () => act(() => { vi.advanceTimersByTime(1000); });

const hadCrypto = "crypto" in global;
beforeAll(() => {
  if (!hadCrypto || !global.crypto?.randomUUID) global.crypto = { ...(global.crypto ?? {}), randomUUID: require("crypto").randomUUID };
});
afterAll(() => {
  if (!hadCrypto) delete global.crypto;
});
beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const opened = (initial = [SERIAL_1, SERIAL_2]) => {
  const view = renderHook(() => useHarness(initial));
  act(() => { view.result.current.routes.openItem("rt-1"); });
  act(() => { view.result.current.routes.openItem("rt-2"); });
  settle();
  expect(view.result.current.pack.sent.current).toEqual([]);
  return view;
};

const itemOf = (result, uuid) => result.current.pack.items.find((i) => i.uuid === uuid);
const routeNames = (result, uuid) => itemOf(result, uuid).data.routes.map((route) => route.name);
const sentences = (result) => [...new Set(result.current.pack.sent.current.flat().map((op) => op.summary))];
const sentTo = (result) => [...new Set(result.current.pack.sent.current.flat().map((op) => op.item))];

describe("a route moved between two sets of a pack", () => {
  it("leaves the one item and is in the other, whole and with its id, each change in words", () => {
    const { result } = opened();
    act(() => result.current.sketch.moveSketchRoute("r-2", ID_2, ID_1));
    settle();
    expect(routeNames(result, "rt-1")).toEqual(["RED 1"]);
    expect(routeNames(result, "rt-2")).toEqual(["GREEN 1", "BLUE 1"]);
    expect(itemOf(result, "rt-2").data.routes[1]).toEqual(SERIAL_1.data.routes[1]);
    expect(sentTo(result).sort()).toEqual(["rt-1", "rt-2"]);
    expect(sentences(result).sort()).toEqual(["Colin M. added the route BLUE 1 to SERIAL 2.", "Colin M. removed the route BLUE 1 from SERIAL 1."]);
    // Where a route is filed here is never sent.
    expect(JSON.stringify(result.current.pack.sent.current)).not.toContain("setId");
  });

  it("one Undo sends it back to where it was, in both items", () => {
    const { result } = opened();
    act(() => result.current.sketch.moveSketchRoute("r-2", ID_2, ID_1));
    settle();
    act(() => result.current.sketch.undoSketch(ID_1));
    settle();
    expect(routeNames(result, "rt-1")).toEqual(["RED 1", "BLUE 1"]);
    expect(routeNames(result, "rt-2")).toEqual(["GREEN 1"]);
    expect(itemOf(result, "rt-1").data).toEqual(SERIAL_1.data);
    expect(itemOf(result, "rt-2").data).toEqual(SERIAL_2.data);
  });

  it("into a set made for it: the new item, then the route in it", () => {
    const { result } = opened([SERIAL_1]);
    let uuid;
    act(() => { uuid = result.current.routes.createItem("SERIAL 9"); });
    act(() => result.current.sketch.moveSketchRoute("r-1", result.current.routes.setIdOf(uuid), ID_1));
    settle();
    expect(itemOf(result, uuid).name).toBe("SERIAL 9");
    expect(routeNames(result, uuid)).toEqual(["RED 1"]);
    expect(routeNames(result, "rt-1")).toEqual(["BLUE 1"]);
  });

  it("the last route out leaves the set in the pack, empty", () => {
    const { result } = opened();
    act(() => result.current.sketch.moveSketchRoute("r-3", ID_1, ID_2));
    settle();
    expect(routeNames(result, "rt-2")).toEqual([]);
    expect(routeNames(result, "rt-1")).toEqual(["RED 1", "BLUE 1", "GREEN 1"]);
    expect(result.current.routes.open).toEqual(["rt-1", "rt-2"]);
  });

  it("someone else's change to the route since stands when the move is undone", () => {
    const { result } = opened();
    act(() => result.current.sketch.moveSketchRoute("r-2", ID_2, ID_1));
    settle();
    // Sam renames it where it now is.
    act(() => result.current.pack.remote([{ type: "patch", item: "rt-2", path: ["routes", { id: "r-2" }], value: { name: "BLUE 2" } }]));
    settle();
    expect(routeNames(result, "rt-2")).toEqual(["GREEN 1", "BLUE 2"]);
    act(() => result.current.sketch.undoSketch(ID_2));
    settle();
    expect(routeNames(result, "rt-2")).toEqual(["GREEN 1"]);
    // Back where it was, under the name Sam gave it: only the move is taken back.
    expect(routeNames(result, "rt-1")).toEqual(["RED 1", "BLUE 2"]);
    act(() => result.current.sketch.redoSketch(ID_2));
    settle();
    expect(routeNames(result, "rt-1")).toEqual(["RED 1"]);
    expect(routeNames(result, "rt-2")).toEqual(["GREEN 1", "BLUE 2"]);
  });
});

describe("two routes of a pack's set made one", () => {
  it("is one change to the one item, said as a combine", () => {
    const { result } = opened();
    act(() => { result.current.sketch.combineSketchRoutes("r-1", "r-2"); });
    settle();
    const routes = itemOf(result, "rt-1").data.routes;
    expect(routes.map((route) => route.name)).toEqual(["RED 1"]);
    expect(routes[0].points.map((p) => p.id)).toEqual(["p-1", "p-2", "q-1", "q-2"]);
    expect(routes[0].color).toBe("#FF453A");
    expect(sentTo(result)).toEqual(["rt-1"]);
    expect(sentences(result)).toEqual(["Colin M. combined the route BLUE 1 into RED 1."]);
  });

  it("Undo sends both routes back as they were", () => {
    const { result } = opened();
    act(() => { result.current.sketch.combineSketchRoutes("r-1", "r-2"); });
    settle();
    act(() => result.current.sketch.undoSketch(ID_1));
    settle();
    expect(itemOf(result, "rt-1").data).toEqual(SERIAL_1.data);
  });
});

describe("a route's colour in a pack", () => {
  it("is everyone's: one change, said as a colour", () => {
    const { result } = opened();
    act(() => result.current.sketch.setSketchRouteColor("r-2", "#FFFFFF"));
    settle();
    expect(itemOf(result, "rt-1").data.routes[1].color).toBe("#FFFFFF");
    expect(result.current.pack.sent.current.flat()).toHaveLength(1);
    expect(sentences(result)).toEqual(["Colin M. changed the colour of BLUE 1."]);
  });
});
