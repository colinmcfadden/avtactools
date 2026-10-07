import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { useFakePack } from "./fakePack";
import { describePointsChange, pointsForPack, usePackPoints } from "./usePackPoints";

const POINTS = [{ id: "lps-0-aa", name: "KGVL", lat: 34.27, lon: -83.83, elevationFt: 1276 }, { id: "lps-1-bb", name: "KJZP", lat: 34.45, lon: -84.46 }];
const SET = { uuid: "ps-1", kind: "pointset", name: "NORTH GA POINTS", data: POINTS };
const LOCAL_ID = "pack:p-1:ps-1";

const useHarness = (initial = [SET]) => {
  const [pointSets, setPointSets] = useState([{ id: "lps-own", name: "MINE", points: [], visible: true }]);
  const pack = useFakePack(initial);
  const [gone, setGone] = useState([]);
  const [refusals, setRefusals] = useState([]);
  const points = usePackPoints({
    packUuid: "p-1", items: pack.items, edit: pack.edit, actor: "Colin M.", pointSets, setPointSets,
    onGone: (_uuid, name) => setGone((g) => [...g, name]), onRefused: (why) => setRefusals((r) => [...r, why]),
    newId: () => "new",
  });
  return { pointSets, setPointSets, pack, points, gone, refusals };
};

const settleTimers = () => act(() => { jest.advanceTimersByTime(1000); });
const packSet = (result) => result.current.pointSets.find((s) => s.id === LOCAL_ID);

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

describe("usePackPoints", () => {
  it("shows a pack's set beside this person's own, and keeps its colour and visibility to them", () => {
    const { result } = renderHook(() => useHarness());
    act(() => { result.current.points.openItem("ps-1"); });
    settleTimers();
    expect(result.current.pointSets.map((s) => s.id)).toEqual(["lps-own", LOCAL_ID]);
    act(() => { result.current.setPointSets((sets) => sets.map((s) => (s.id === LOCAL_ID ? { ...s, visible: false, color: "#000000" } : s))); });
    act(() => { result.current.pack.remote([{ type: "patch", item: "ps-1", path: [{ id: "lps-1-bb" }], value: { name: "KJZP AIRPORT" } }]); });
    settleTimers();
    expect([packSet(result).visible, packSet(result).color, packSet(result).points[1].name]).toEqual([false, "#000000", "KJZP AIRPORT"]);
    expect(result.current.pack.sent.current).toEqual([]);
  });

  it("puts an import in the pack, and renames a set", () => {
    const { result } = renderHook(() => useHarness([]));
    let uuid;
    act(() => { uuid = result.current.points.createItem("KGVL TAXI POINTS", [{ name: "A1", lat: 1, lon: 1 }, { name: "A2", lat: 2, lon: 2 }]); });
    settleTimers();
    expect(uuid).toBe("ps-new");
    const [[create]] = result.current.pack.sent.current;
    expect(create).toMatchObject({ type: "item.create", kind: "pointset", name: "KGVL TAXI POINTS",
      summary: 'Colin M. added the point set "KGVL TAXI POINTS" (2 points).' });
    expect(create.data.map((p) => p.id)).toEqual(["pt-0", "pt-1"]);
    act(() => { result.current.points.renameSet("ps-new", "KGVL TAXI"); });
    settleTimers();
    expect(result.current.pack.sent.current[1]).toEqual([{ type: "item.rename", item: "ps-new", name: "KGVL TAXI",
      summary: 'Colin M. renamed "KGVL TAXI POINTS" to "KGVL TAXI".' }]);
  });

  it("refuses a set with no points", () => {
    const { result } = renderHook(() => useHarness([]));
    act(() => { expect(result.current.points.createItem("EMPTY", [])).toBeNull(); });
    expect(result.current.refusals).toEqual(["empty_point_set"]);
    expect(result.current.pack.sent.current).toEqual([]);
  });

  it("takes a set someone removed off the map", () => {
    const { result } = renderHook(() => useHarness());
    act(() => { result.current.points.openItem("ps-1"); });
    settleTimers();
    act(() => { result.current.pack.remote([{ type: "item.delete", item: "ps-1" }]); });
    settleTimers();
    expect(packSet(result)).toBeUndefined();
    expect(result.current.gone).toEqual(["NORTH GA POINTS"]);
  });
});

describe("points for a pack", () => {
  it("gives each point an id no other has", () => {
    expect(pointsForPack([{ id: "a" }, { id: "a" }, { name: "x" }, { id: 7 }]).map((p) => p.id)).toEqual(["a", "a-1", "pt-2", 7]);
  });

  it("says what changed", () => {
    const say = (after) => describePointsChange(POINTS, after, { name: "NGA", actor: "Sam B." });
    expect(say([...POINTS, { id: "n", name: "NEW" }])).toBe("Sam B. added 1 point to NGA.");
    expect(say(POINTS.slice(1))).toBe("Sam B. removed 1 point from NGA.");
    expect(say([{ ...POINTS[0], name: "KGVL2" }, POINTS[1]])).toBe("Sam B. changed KGVL2 in NGA.");
  });
});
