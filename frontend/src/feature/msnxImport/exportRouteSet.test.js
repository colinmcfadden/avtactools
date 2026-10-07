import { exportRouteSetFiles } from "./exportRouteSet";

const SKETCH_SET = { key: "sketches", kind: "sketch", routes: [{ id: "r-1", name: "RED 1" }, { id: "r-2", name: "RED 2" }] };
const MISSION_SET = { key: "file-1", kind: "mission", fileName: "GOAT SUCKER.msnx", routes: [{ id: "m-1", name: "INGRESS" }] };

const deps = (overrides = {}) => ({
  withThreats: true,
  threatCount: 2,
  exportMission: jest.fn().mockResolvedValue(undefined),
  exportSketches: jest.fn().mockResolvedValue({ airframe: "UH-60L", exact: true, warning: null }),
  exportThreats: jest.fn().mockResolvedValue(undefined),
  toast: jest.fn(),
  ...overrides,
});

describe("exporting a route set", () => {
  let alertSpy;
  beforeEach(() => {
    alertSpy = jest.spyOn(window, "alert").mockImplementation(() => {});
  });
  afterEach(() => alertSpy.mockRestore());

  it("makes no .ths when the sketches' .msnx could not be made, and says so in a toast", async () => {
    const d = deps({ exportSketches: jest.fn().mockRejectedValue(new Error("template missing")) });
    await expect(exportRouteSetFiles(SKETCH_SET, d)).resolves.toBe(false);
    expect(d.exportThreats).not.toHaveBeenCalled();
    expect(d.toast).toHaveBeenCalledWith({ tone: "error", message: "The mission file could not be made: template missing" });
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it("makes no .ths when a mission's .msnx could not be made", async () => {
    const d = deps({ exportMission: jest.fn().mockRejectedValue(new Error("zip failed")) });
    await expect(exportRouteSetFiles(MISSION_SET, d)).resolves.toBe(false);
    expect(d.exportMission).toHaveBeenCalledWith("file-1");
    expect(d.exportThreats).not.toHaveBeenCalled();
    expect(d.toast).toHaveBeenCalledWith(expect.objectContaining({ tone: "error" }));
  });

  it("names the .ths after the routes it travels with", async () => {
    const sketches = deps();
    await expect(exportRouteSetFiles(SKETCH_SET, sketches)).resolves.toBe(true);
    expect(sketches.exportSketches).toHaveBeenCalledWith(SKETCH_SET.routes);
    expect(sketches.exportThreats).toHaveBeenCalledWith("RED 1_RED 2_threats");
    expect(sketches.toast).not.toHaveBeenCalled();

    const mission = deps();
    await exportRouteSetFiles(MISSION_SET, mission);
    expect(mission.exportThreats).toHaveBeenCalledWith("GOAT SUCKER_threats");
  });

  it("passes on the airframe warning as a toast, and still adds the threats", async () => {
    const warning = "This mission will open in AMPS as a UH-60L, not a CH-47F.";
    const d = deps({ exportSketches: jest.fn().mockResolvedValue({ airframe: "UH-60L", exact: false, warning }) });
    await exportRouteSetFiles(SKETCH_SET, d);
    expect(d.toast).toHaveBeenCalledWith(expect.objectContaining({ tone: "warn", message: warning }));
    expect(d.exportThreats).toHaveBeenCalled();
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it("says when the mission exported but the threats did not", async () => {
    const d = deps({ exportThreats: jest.fn().mockRejectedValue(new Error("server down")) });
    await expect(exportRouteSetFiles(SKETCH_SET, d)).resolves.toBe(true);
    expect(d.toast).toHaveBeenCalledWith({ tone: "warn", message: "The mission exported, but the threats (.ths) could not be: server down" });
  });

  it("adds no .ths unless asked and there are threats", async () => {
    const unasked = deps({ withThreats: false });
    await exportRouteSetFiles(SKETCH_SET, unasked);
    expect(unasked.exportThreats).not.toHaveBeenCalled();

    const none = deps({ threatCount: 0 });
    await exportRouteSetFiles(SKETCH_SET, none);
    expect(none.exportThreats).not.toHaveBeenCalled();
  });
});
