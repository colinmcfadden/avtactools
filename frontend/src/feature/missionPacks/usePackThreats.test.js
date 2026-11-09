import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { useFakePack } from "./fakePack";
import { maskSubject } from "../threats/threatSets";
import { usePackThreats } from "./usePackThreats";

const RADAR = { type: 1, rangeNmi: 25, antennaHeightFt: 30, aglNotMsl: true, showMask: true, showRangeRings: true, bands: [{ altFt: 300, color: 1, alpha: 128 }] };
const THREAT = (id, extra = {}) => ({ id, name: `T-${id}`, lat: 34.5, lon: -84.1, milstdId: "SHAPMFG--------", radars: [RADAR], ...extra });
const SET = { uuid: "ts-1", kind: "threatset", name: "AIR DEFENCE", data: { version: 1, threats: [THREAT("a"), THREAT("b")] } };
const LOCAL_ID = "pack:p-1:ts-1";

const useHarness = (initial = [SET], packUuid = "p-1") => {
  const [packSets, setPackSets] = useState(null);
  const pack = useFakePack(initial);
  const [gone, setGone] = useState([]);
  const [refusals, setRefusals] = useState([]);
  const [fetched, setFetched] = useState([]);
  const threats = usePackThreats({
    packUuid, items: pack.items, edit: pack.edit, actor: "Colin M.", packSets, setPackSets,
    fetchMasks: (list) => setFetched((f) => [...f, list.map((t) => t.id)]),
    onGone: (_uuid, name) => setGone((g) => [...g, name]), onRefused: (why) => setRefusals((r) => [...r, why]),
    newId: () => "new", delayMs: 0,
  });
  return { threats, pack, packSets, setPackSets, gone, refusals, fetched };
};

const flush = (result) => act(async () => { result.current.threats.flush(); });
const threatsIn = (result, uuid = "ts-1") => result.current.pack.items.find((i) => i.uuid === uuid)?.data.threats;
const setOf = (result) => result.current.packSets.find((s) => s.id === LOCAL_ID);
const change = (result, update) =>
  act(async () => result.current.setPackSets((sets) => sets.map((s) => (s.id === LOCAL_ID ? update(s) : s))));

describe("a pack's sets of threats", () => {
  it("has none open until one is asked for, and null with no pack open", () => {
    const { result, rerender } = renderHook(({ uuid }) => useHarness([SET], uuid), { initialProps: { uuid: null } });
    expect(result.current.packSets).toBeNull();
    rerender({ uuid: "p-1" });
    expect(result.current.packSets).toEqual([]);
  });

  it("opens one, with the threats' ids as the pack has them, and asks for the masks it lacks", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    expect(setOf(result).threats.map((t) => t.id)).toEqual(["a", "b"]);
    expect(result.current.fetched).toEqual([["a", "b"]]);
  });

  it("sends a threat moved with its place whole, and neither its hidden flag nor its mask", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    await change(result, (s) => ({ ...s, threats: s.threats.map((t) => (t.id === "a" ? { ...t, lat: 35, visible: false, mask: { for: maskSubject(t), images: ["x"] } } : t)) }));
    await flush(result);
    const sent = threatsIn(result)[0];
    expect(sent.lat).toBe(35);
    expect("visible" in sent).toBe(false);
    expect("mask" in sent).toBe(false);
    expect(result.current.pack.sent.current.at(-1)[0].summary).toBe("Colin M. moved the threat T-a.");
  });

  it("sends nothing for what is each person's own alone", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    const before = result.current.pack.sent.current.length;
    await change(result, (s) => ({ ...s, threats: s.threats.map((t) => ({ ...t, visible: false, mask: { images: ["x"] }, maskError: "no" })) }));
    await flush(result);
    expect(result.current.pack.sent.current.length).toBe(before);
  });

  it("takes someone else's change and keeps what this person hid", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    await change(result, (s) => ({ ...s, threats: s.threats.map((t) => (t.id === "a" ? { ...t, visible: false } : t)) }));
    await flush(result);
    await act(async () => { result.current.pack.remote([{ type: "patch", item: "ts-1", path: ["threats", { id: "a" }], value: { name: "RENAMED" } }]); });
    const a = setOf(result).threats.find((t) => t.id === "a");
    expect(a.name).toBe("RENAMED");
    expect(a.visible).toBe(false);
  });

  it("takes a set someone removed off the map and says so", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    await act(async () => { result.current.pack.remote([{ type: "item.delete", item: "ts-1" }]); });
    expect(setOf(result)).toBeUndefined();
    expect(result.current.gone).toEqual(["AIR DEFENCE"]);
  });

  it("makes a new set with the next name, its threats on the map and only the shared part sent", async () => {
    const { result } = renderHook(() => useHarness());
    let made;
    await act(async () => { made = result.current.threats.createItem("", [THREAT("z", { visible: false, mask: { images: ["x"] } })]); });
    expect(made).toBe("ts-new");
    const item = result.current.pack.items.find((i) => i.uuid === "ts-new");
    expect(item.name).toBe("THREATS 2");
    expect("mask" in item.data.threats[0]).toBe(false);
    const open = result.current.packSets.find((s) => s.id === "pack:p-1:ts-new");
    expect(open.threats[0].visible).toBe(false);
  });

  it("renames a set in capitals, within the limit, and leaves a blank one alone", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    await act(async () => { result.current.threats.renameSet("ts-1", "   "); });
    expect(setOf(result).name).toBe("AIR DEFENCE");
    await act(async () => { result.current.threats.renameSet("ts-1", "north ga"); });
    await flush(result);
    expect(result.current.pack.items.find((i) => i.uuid === "ts-1").name).toBe("NORTH GA");
  });

  it("closes a set here and leaves it in the pack", async () => {
    const { result } = renderHook(() => useHarness());
    await act(async () => { result.current.threats.openItem("ts-1"); });
    await act(async () => { result.current.threats.closeItem("ts-1"); });
    expect(setOf(result)).toBeUndefined();
    expect(result.current.pack.items.some((i) => i.uuid === "ts-1")).toBe(true);
  });
});
