import { exportBody, exportFileName, exportGroups, threatCount } from "./threatExport";

const threat = (name, extra = {}) => ({
  id: name,
  name,
  milstdId: "SHGPEWMAI------",
  lat: 34.6,
  lon: -84.2,
  information: "",
  source: "",
  showThreat: true,
  visible: true,
  maskLoading: false,
  radars: [{ type: 0, rangeNmi: 7, antennaHeightFt: 20, aglNotMsl: true, showMask: true, showRangeRings: true, bands: [] }],
  ...extra,
});

const SETS = [
  { id: "a", name: "NORTH", threats: [threat("SA-8"), threat("ZSU-23", { visible: false })] },
  { id: "b", name: "EMPTY", threats: [] },
  { id: "c", name: "SOUTH", threats: [threat("SA-15")] },
];

describe("exporting threats", () => {
  it("takes every open set with threats, or the one asked for", () => {
    expect(exportGroups(SETS).map((set) => set.name)).toEqual(["NORTH", "SOUTH"]);
    expect(exportGroups(SETS, "c").map((set) => set.name)).toEqual(["SOUTH"]);
    expect(exportGroups(SETS, "b")).toEqual([]);
    expect(threatCount(exportGroups(SETS))).toBe(3);
  });

  it("sends each set as a folder of its threats, hidden ones too, without what is the screen's", () => {
    const body = exportBody(exportGroups(SETS), "MISSION 1.kmz", "kmz");
    expect(body.fileName).toBe("MISSION 1.kmz");
    expect(body.format).toBe("kmz");
    expect(body.sets.map((set) => [set.name, set.threats.map((t) => t.name)])).toEqual([
      ["NORTH", ["SA-8", "ZSU-23"]],
      ["SOUTH", ["SA-15"]],
    ]);
    expect(body.sets[0].threats[0]).not.toHaveProperty("visible");
    expect(body.sets[0].threats[0]).not.toHaveProperty("maskLoading");
    expect(body.sets[0].threats[0].radars[0].rangeNmi).toBe(7);
  });

  it("names the file for its set, or threats", () => {
    expect(exportFileName("NORTH", "kml")).toBe("NORTH.kml");
    expect(exportFileName("MISSION 1.msnx", "ths")).toBe("MISSION 1.ths");
    expect(exportFileName("A/B: C?", "kmz")).toBe("A_B_ C_.kmz");
    expect(exportFileName("", "kmz")).toBe("threats.kmz");
    expect(exportFileName(null, "ths")).toBe("threats.ths");
  });
});
