import { describeLzChange, lzDiagramFromItem, lzItemData, packDiagramId, packDiagramRef, sharedLzData } from "./packLz";

const clone = (value) => JSON.parse(JSON.stringify(value));

const DOC = {
  status: "targeted",
  target: { lat: 34.5, lon: -84.1, mgrs: "16S GC 1 2" },
  flightData: { landing_hdg: "090°" },
  analysis: { customLZ: null, detectedLZ: null },
  graphics: {
    helicopters: [{ id: 1, lat: 34.5, lon: -84.1, heading: 90 }, { id: 2, lat: 34.6, lon: -84.1, heading: 90 }],
    sectorsOfFire: [{ id: "s-1", name: "S-1", points: [] }],
    units: [{ id: "u-1", uniqueDesignation: "A/2-10", lat: 1, lon: 1 }],
    exportBox: null,
  },
};

const say = (change) => {
  const after = clone(DOC);
  change(after);
  return describeLzChange(DOC, after, { name: "LZ IBIS", actor: "Sam B." });
};

describe("what an LZ change was, in words", () => {
  it("names the change the way a crew would", () => {
    expect(say((d) => { d.graphics.helicopters[1].lat = 34.61; })).toBe("Sam B. moved Chalk 2 on LZ IBIS.");
    expect(say((d) => { d.graphics.helicopters[0].heading = 270; })).toBe("Sam B. turned Chalk 1 to 270° on LZ IBIS.");
    expect(say((d) => { d.graphics.helicopters.push({ id: 3, lat: 1, lon: 1 }); })).toBe("Sam B. added Chalk 3 to LZ IBIS.");
    expect(say((d) => { d.graphics.sectorsOfFire = []; })).toBe("Sam B. removed sector S-1 from LZ IBIS.");
    expect(say((d) => { d.graphics.units[0].lat = 2; })).toBe("Sam B. moved unit A/2-10 on LZ IBIS.");
    expect(say((d) => { d.flightData.landing_hdg = "270°"; })).toBe("Sam B. changed the landing heading to 270° on LZ IBIS.");
    expect(say((d) => { d.flightData.callSign = "HAWK 6"; })).toBe("Sam B. changed the flight data on LZ IBIS.");
    expect(say((d) => { d.analysis.customLZ = [[1, 1], [1, 2], [2, 2]]; })).toBe("Sam B. drew the boundary of LZ IBIS.");
    expect(say((d) => { d.graphics.exportBox = { north: 1 }; })).toBe("Sam B. set the LZ card area on LZ IBIS.");
    expect(say((d) => { d.mapData = { mgrs: "x" }; })).toBe("Sam B. edited LZ IBIS.");
  });

  it("names the most telling change when one action made several", () => {
    expect(say((d) => {
      d.status = "analyzed";
      d.analysis.detectedLZ = [[1, 1], [1, 2], [2, 2]];
      d.graphics.doghouses = [{ id: "dh1", label: "SP1" }];
    })).toBe("Sam B. analyzed LZ IBIS.");
  });
});

describe("an LZ as a pack item", () => {
  it("shares everything but what is each person's own", () => {
    const data = sharedLzData({
      ...DOC, id: "d-1", savedId: 4, dirty: true, name: "LZ IBIS", createdAt: "t", updatedAt: "t",
      view: { mapStyle: "topo" }, analysis: { detectedLZ: null, terrainData: { pixels: "..." } },
    });
    ["id", "savedId", "dirty", "name", "createdAt", "updatedAt", "view"].forEach((key) => expect(data).not.toHaveProperty(key));
    expect(data.analysis).toEqual({ detectedLZ: null });
    expect(data.graphics.helicopters).toHaveLength(2);
  });

  it("opens as a diagram named for the pack and the item, and comes back out the same", () => {
    const item = { uuid: "lz-7", name: "LZ IBIS", data: { ...DOC, view: { mapStyle: "topo" } } };
    const diagram = lzDiagramFromItem("p-1", item);
    expect(diagram.id).toBe("pack:p-1:lz-7");
    expect(packDiagramRef(diagram.id)).toEqual({ pack: "p-1", item: "lz-7" });
    expect([diagram.name, diagram.savedId, diagram.view.mapStyle]).toEqual(["LZ IBIS", null, "topo"]);
    expect(lzItemData(lzDiagramFromItem("p-1", { ...item, data: lzItemData(diagram) }))).toEqual(lzItemData(diagram));
  });

  it("tells a pack diagram from any other", () => {
    expect(packDiagramId("p", "lz-1")).toBe("pack:p:lz-1");
    expect(packDiagramRef("2f6c0e1c-1111")).toBeNull();
    expect(packDiagramRef(undefined)).toBeNull();
  });
});
