import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { useLzWorkspace } from "../lzWorkspace/useLzWorkspace";
import { useFakePack } from "./fakePack";
import { lzItemData, packDiagramId } from "./packLz";
import { usePackLz } from "./usePackLz";

const LZ_DATA = {
  schemaVersion: 2,
  status: "analyzed",
  target: { lat: 34.5, lon: -84.1, mgrs: "16S GC 28864 55349" },
  mapData: { mgrs: "16S GC 28864 55349" },
  flightData: { landing_hdg: "090°" },
  analysis: { customLZ: null, detectedLZ: [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], results: { area: 1 }, gridElevation: "1200", latLong: "" },
  graphics: {
    helicopters: [{ id: 101, lat: 34.5, lon: -84.1, heading: 90 }, { id: 102, lat: 34.501, lon: -84.1, heading: 90 }],
    doghouses: [], pzMarkers: [], sectorsOfFire: [], goArounds: [], units: [], measurements: [], exportBox: null,
  },
  view: { mapStyle: "satellite", showLZOutline: true, showHeatmap: false },
};

const ITEM = { uuid: "lz-1", kind: "lz", name: "LZ HAWK", data: LZ_DATA };
const DIAGRAM = packDiagramId("p-1", "lz-1");

const useHarness = ({ items: initial = [ITEM], readOnly = false, refuse = null } = {}) => {
  const ws = useLzWorkspace();
  const { items, edit, remote, sent } = useFakePack(initial, { refuse });
  const [gone, setGone] = useState([]);
  const [refusals, setRefusals] = useState([]);
  const bridge = usePackLz({
    packUuid: "p-1", items, edit, readOnly, workspace: ws.workspace, importDiagram: ws.importDiagram,
    applyRemoteDiagram: ws.applyRemoteDiagram, removeDiagram: ws.removeDiagram, setActiveDiagram: ws.setActiveDiagram,
    actor: "Colin M.", onGone: (_id, name) => setGone((g) => [...g, name]),
    onRefused: (why) => setRefusals((r) => [...r, why]), newId: () => "new",
  });
  return { ws, items, sent, remote, bridge, gone, refusals };
};

const diagramOf = (result, id = DIAGRAM) => result.current.ws.workspace.diagramsById[id];
const settleTimers = () => act(() => { jest.advanceTimersByTime(1000); });

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

const opened = (options) => {
  const view = renderHook(() => useHarness(options));
  act(() => { view.result.current.bridge.openItem("lz-1"); });
  settleTimers();
  return view;
};

describe("usePackLz", () => {
  it("opens an item as a diagram named for the pack and the item, and sends nothing for it", () => {
    const { result } = opened();
    expect(diagramOf(result).name).toBe("LZ HAWK");
    expect(result.current.ws.workspace.activeDiagramId).toBe(DIAGRAM);
    expect(result.current.sent.current).toEqual([]);
  });

  it("sends a drag as one change, at the helicopter, with a sentence for the history", () => {
    const { result } = opened();
    act(() => { result.current.ws.patchGraphic("helicopters", 102, { lat: 34.502 }, DIAGRAM); });
    act(() => { result.current.ws.patchGraphic("helicopters", 102, { lat: 34.503, lon: -84.101 }, DIAGRAM); });
    expect(result.current.sent.current).toEqual([]);
    settleTimers();
    expect(result.current.sent.current).toEqual([[{
      type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: 102 }], value: { lat: 34.503, lon: -84.101 },
      summary: "Colin M. moved Chalk 2 on LZ HAWK.",
    }]]);
    settleTimers();
    expect(result.current.sent.current).toHaveLength(1); // the echo changes nothing
  });

  it("keeps this person's view to themselves", () => {
    const { result } = opened();
    act(() => { result.current.ws.setView({ mapStyle: "vfr-sectional", showHeatmap: true }, DIAGRAM); });
    settleTimers();
    expect(result.current.sent.current).toEqual([]);
  });

  it("applies someone else's change and keeps this person's view of the diagram", () => {
    const { result } = opened();
    act(() => { result.current.ws.setView({ mapStyle: "vfr-sectional" }, DIAGRAM); });
    act(() => { result.current.remote([{ type: "patch", item: "lz-1", path: ["flightData"], value: { landing_hdg: "270°" } }]); });
    settleTimers();
    expect(diagramOf(result).flightData.landing_hdg).toBe("270°");
    expect(diagramOf(result).view.mapStyle).toBe("vfr-sectional");
    expect(result.current.sent.current).toEqual([]);
  });

  it("sends a change waiting here before taking someone else's, so both stand", () => {
    const { result } = opened();
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat: 34.499 }, DIAGRAM); });
    act(() => { result.current.remote([{ type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: 102 }], value: { heading: 270 } }]); });
    settleTimers();
    expect(result.current.sent.current).toHaveLength(1);
    expect(result.current.sent.current[0][0].path).toEqual(["graphics", "helicopters", { id: 101 }]);
    const [first, second] = diagramOf(result).graphics.helicopters;
    expect([first.lat, second.heading]).toEqual([34.499, 270]);
  });

  it("keeps both when a change here and someone else's arrive at the same moment", () => {
    const { result } = opened();
    act(() => {
      result.current.ws.patchGraphic("helicopters", 101, { lat: 34.499 }, DIAGRAM);
      result.current.remote([{ type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: 102 }], value: { heading: 270 } }]);
    });
    settleTimers();
    const [first, second] = diagramOf(result).graphics.helicopters;
    expect([first.lat, second.heading]).toEqual([34.499, 270]);
    expect(result.current.sent.current).toHaveLength(1);
  });

  it("still sends the rest of a drag as one change after someone else's arrived in the middle of it", () => {
    const { result } = opened();
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat: 34.4991 }, DIAGRAM); });
    act(() => { result.current.remote([{ type: "patch", item: "lz-1", path: ["flightData"], value: { landing_hdg: "180°" } }]); });
    [34.4992, 34.4993, 34.4994].forEach((lat) => {
      act(() => { jest.advanceTimersByTime(50); });
      act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat }, DIAGRAM); });
    });
    settleTimers();
    expect(result.current.sent.current.map((batch) => batch.map((op) => op.value?.lat))).toEqual([[34.4991], [34.4994]]);
    expect(diagramOf(result).flightData.landing_hdg).toBe("180°");
    expect(diagramOf(result).graphics.helicopters[0].lat).toBe(34.4994);
  });

  it("on one field, the change sent later stands", () => {
    const { result } = opened();
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { heading: 45 }, DIAGRAM); });
    act(() => { result.current.remote([{ type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: 101 }], value: { heading: 300 } }]); });
    settleTimers();
    // Theirs reached the pack first; ours, sent after it, is the one that stands, here and on the server.
    expect(diagramOf(result).graphics.helicopters[0].heading).toBe(45);
    expect(result.current.items[0].data.graphics.helicopters[0].heading).toBe(45);
  });

  it("still sends a change made just before the LZ/PZ was closed", () => {
    const { result } = opened();
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat: 34.4 }, DIAGRAM); });
    act(() => { result.current.ws.removeDiagram(DIAGRAM); });
    settleTimers();
    expect(result.current.sent.current.map((batch) => batch[0].value)).toEqual([{ lat: 34.4 }]);
    expect(result.current.items[0].data.graphics.helicopters[0].lat).toBe(34.4);
  });

  it("puts back a change the pack will not take", () => {
    const { result } = opened({ readOnly: true });
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat: 1 }, DIAGRAM); });
    settleTimers();
    expect(diagramOf(result).graphics.helicopters[0].lat).toBe(34.5);
    expect(result.current.sent.current).toEqual([]);
  });

  it("puts back and reports a change the pack refused", () => {
    const { result } = opened({ refuse: "pack_finished" });
    act(() => { result.current.ws.patchGraphic("helicopters", 101, { lat: 1 }, DIAGRAM); });
    settleTimers();
    expect(diagramOf(result).graphics.helicopters[0].lat).toBe(34.5);
    expect(result.current.refusals).toEqual(["pack_finished"]);
  });

  it("closes an item someone removed", () => {
    const { result } = opened();
    act(() => { result.current.remote([{ type: "item.delete", item: "lz-1" }]); });
    settleTimers();
    expect(diagramOf(result)).toBeUndefined();
    expect(result.current.gone).toEqual(["LZ HAWK"]);
  });

  it("renames the item, and takes someone else's name for it", () => {
    const { result } = opened();
    act(() => { result.current.ws.setDiagramName("LZ EAGLE", DIAGRAM); });
    settleTimers();
    expect(result.current.sent.current).toEqual([[{ type: "item.rename", item: "lz-1", name: "LZ EAGLE", summary: 'Colin M. renamed "LZ HAWK" to "LZ EAGLE".' }]]);
    act(() => { result.current.remote([{ type: "item.rename", item: "lz-1", name: "LZ OSPREY" }]); });
    settleTimers();
    expect(diagramOf(result).name).toBe("LZ OSPREY");
    expect(result.current.sent.current).toHaveLength(1);
  });

  it("makes a new LZ/PZ in the pack", () => {
    const { result } = renderHook(() => useHarness());
    let id;
    act(() => { id = result.current.bridge.createItem([34.6, -84.2], { mgrs: "16S GC 1 2" }); });
    settleTimers();
    expect(id).toBe(packDiagramId("p-1", "lz-new"));
    const [[create]] = result.current.sent.current;
    expect(create).toMatchObject({ type: "item.create", item: "lz-new", kind: "lz", name: "LZ/PZ 2" });
    expect(create.data).toEqual(lzItemData(diagramOf(result, id)));
    expect(create.data.view).toBeUndefined();
    expect(result.current.ws.workspace.activeDiagramId).toBe(id);
    expect(result.current.sent.current).toHaveLength(1);
  });

  it("edits an item whose data is in an older shape without sending anything until it is changed", () => {
    const legacy = { uuid: "lz-1", kind: "lz", name: "LZ OLD", data: {
      targetLocation: [34.5, -84.1], gridInput: "16S GC 28864 55349", status: "analyzed",
      analysis: { detectedLZ: [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]] },
      helicopters: [{ id: 7, lat: 34.5, lon: -84.1 }], savedId: 12, dirty: false, view: { mapStyle: "topo" },
    } };
    const { result } = opened({ items: [legacy] });
    expect(result.current.sent.current).toEqual([]);
    expect(diagramOf(result).view.mapStyle).toBe("topo");
    act(() => { result.current.ws.patchGraphic("helicopters", 7, { lat: 34.6 }, DIAGRAM); });
    settleTimers();
    // Every operation applied to the old shape (applyOps insists); the item is in the current shape now.
    expect(result.current.items[0].data.graphics.helicopters[0].lat).toBe(34.6);
    expect(result.current.sent.current).toHaveLength(1);
  });
});
