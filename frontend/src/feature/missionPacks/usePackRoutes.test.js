import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { restoreSketchRoute, useRouteSketch } from "../msnxImport/useRouteSketch";
import { useFakePack } from "./fakePack";
import { routeSetData } from "./packRoutes";
import { usePackRoutes } from "./usePackRoutes";

const RAW_ROUTE = {
  id: "r-1", name: "RED 1", color: "#0A84FF",
  points: [
    { id: "p-1", lat: 34.5, lon: -84.1, kind: "amps", ptType: "start", name: ".SP", role: "start", ele: null },
    { id: "p-2", lat: 34.6, lon: -84.2, kind: "amps", ptType: "ip", name: ".RP", role: "waypoint", ele: null },
  ],
};
// The set as the pack has it: in today's shape, as anything this app sends would be.
const SET = { uuid: "rt-1", kind: "route", name: "OP DK INGRESS", data: routeSetData([restoreSketchRoute(RAW_ROUTE, "x")]) };
const SET_ID = "pack:p-1:rt-1";

const useHarness = (initial = [SET], options = {}) => {
  const sketch = useRouteSketch();
  const pack = useFakePack(initial, options);
  const [gone, setGone] = useState([]);
  const routes = usePackRoutes({
    packUuid: "p-1", items: pack.items, edit: pack.edit, actor: "Colin M.",
    sketchedRoutes: sketch.sketchedRoutes, loadSketchRoutes: sketch.loadSketchRoutes,
    replaceRouteSet: sketch.replaceRouteSet, removeRouteSet: sketch.removeRouteSet,
    onGone: (_uuid, name) => setGone((g) => [...g, name]), newId: () => "new",
  });
  return { sketch, pack, routes, gone };
};

const settleTimers = () => act(() => { jest.advanceTimersByTime(1000); });
const setRoutes = (result) => result.current.sketch.sketchedRoutes.filter((r) => r.setId === SET_ID);

// The sketch names new points with crypto.randomUUID, which browsers have and this test's DOM does not.
const hadCrypto = "crypto" in global;
beforeAll(() => {
  if (!hadCrypto || !global.crypto?.randomUUID) global.crypto = { ...(global.crypto ?? {}), randomUUID: require("crypto").randomUUID };
});
afterAll(() => {
  if (!hadCrypto) delete global.crypto;
});

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

const opened = (...args) => {
  const view = renderHook(() => useHarness(...args));
  act(() => { view.result.current.routes.openItem("rt-1"); });
  settleTimers();
  return view;
};

describe("usePackRoutes", () => {
  it("opens a set's routes in the sketch, filed under the set, with their ids, and sends nothing", () => {
    const { result } = opened();
    expect(setRoutes(result).map((r) => [r.id, r.name, r.visible])).toEqual([["r-1", "RED 1", true]]);
    expect(result.current.sketch.sessionRoutes).toEqual([]);
    expect(result.current.pack.sent.current).toEqual([]);
  });

  it("sends a moved point as one change at that point", () => {
    const { result } = opened();
    act(() => { result.current.sketch.updateSketchPointPosition("r-1", "p-2", 34.61, -84.21); });
    settleTimers();
    expect(result.current.pack.sent.current).toHaveLength(1);
    const [op] = result.current.pack.sent.current[0];
    expect(op).toMatchObject({
      type: "patch", item: "rt-1", path: ["routes", { id: "r-1" }, "points", { id: "p-2" }],
      summary: "Colin M. moved .RP on RED 1.",
    });
    expect(op.value).toMatchObject({ lat: 34.61, lon: -84.21 });
  });

  it("keeps a hidden route hidden and to this person", () => {
    const { result } = opened();
    act(() => { result.current.sketch.toggleSketchVisibility("r-1"); });
    settleTimers();
    expect(result.current.pack.sent.current).toEqual([]);
    act(() => { result.current.pack.remote([{ type: "patch", item: "rt-1", path: ["routes", { id: "r-1" }], value: { name: "RED 9" } }]); });
    settleTimers();
    expect(setRoutes(result).map((r) => [r.name, r.visible])).toEqual([["RED 9", false]]);
  });

  it("takes someone else's new point", () => {
    const { result } = opened();
    act(() => {
      result.current.pack.remote([{ type: "insert", item: "rt-1", path: ["routes", { id: "r-1" }, "points"], after: "p-1",
        value: { id: "p-9", lat: 34.55, lon: -84.15, kind: "shaping" } }]);
    });
    settleTimers();
    expect(setRoutes(result)[0].points.map((p) => p.id)).toEqual(["p-1", "p-9", "p-2"]);
    expect(result.current.pack.sent.current).toEqual([]);
  });

  it("puts a route drawn while the set is chosen into the set", () => {
    const { result } = opened();
    act(() => { result.current.sketch.startSketch(); });
    act(() => { result.current.sketch.addDraftPoint(34.7, -84.3); });
    act(() => { result.current.sketch.addDraftPoint(34.8, -84.4); });
    act(() => { result.current.sketch.finishSketch("RED 2", result.current.routes.setIdOf("rt-1")); });
    settleTimers();
    const [[op]] = result.current.pack.sent.current;
    expect(op).toMatchObject({ type: "insert", item: "rt-1", path: ["routes"], after: "r-1", summary: "Colin M. added the route RED 2 to OP DK INGRESS." });
    expect(op.value).not.toHaveProperty("visible");
    expect(op.value).not.toHaveProperty("setId");
    expect(result.current.sketch.sessionRoutes).toEqual([]);
  });

  it("leaves this session's own routes out of the pack", () => {
    const { result } = opened();
    act(() => { result.current.sketch.loadSketchRoutes([{ ...RAW_ROUTE, id: "mine" }]); });
    const mine = result.current.sketch.sessionRoutes[0];
    act(() => { result.current.sketch.updateSketchPointPosition(mine.id, "p-1", 1, 1); });
    settleTimers();
    expect(result.current.pack.sent.current).toEqual([]);
    expect(setRoutes(result)).toHaveLength(1);
  });

  it("makes a set and renames one", () => {
    const { result } = renderHook(() => useHarness());
    let uuid;
    act(() => { uuid = result.current.routes.createItem("OP DK EGRESS"); });
    settleTimers();
    expect(uuid).toBe("rt-new");
    act(() => { result.current.routes.renameSet("rt-new", "OP DK EXFIL"); });
    settleTimers();
    expect(result.current.pack.sent.current.map((batch) => batch.map((op) => [op.type, op.name]))).toEqual([
      [["item.create", "OP DK EGRESS"]],
      [["item.rename", "OP DK EXFIL"]],
    ]);
  });

  it("takes a set someone removed off the map", () => {
    const { result } = opened();
    act(() => { result.current.pack.remote([{ type: "item.delete", item: "rt-1" }]); });
    settleTimers();
    expect(setRoutes(result)).toEqual([]);
    expect(result.current.gone).toEqual(["OP DK INGRESS"]);
  });

  it("brings a set saved in an older shape up to date with its first change, and sends nothing for opening it", () => {
    const old = { uuid: "rt-1", kind: "route", name: "OLD", data: { version: 1, routes: [{ ...RAW_ROUTE, visible: false, plan: { tot: { time: "12:00:00", pointId: "p-2" } } }] } };
    const { result } = opened([old]);
    expect(result.current.pack.sent.current).toEqual([]);
    act(() => { result.current.sketch.updateSketchPointPosition("r-1", "p-1", 34.4, -84.0); });
    settleTimers();
    // Every operation applied to the old shape (useFakePack insists), and the set has today's.
    const [route] = result.current.pack.items[0].data.routes;
    expect(route.plan.perPoint["p-2"].clock).toBe("12:00:00");
    expect(route.points[0].lat).toBe(34.4);
  });
});
