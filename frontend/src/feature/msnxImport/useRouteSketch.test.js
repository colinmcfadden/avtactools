import { act, renderHook } from "@testing-library/react";
import { buildSketchMsnx } from "./createMsnx";
import { useRouteSketch } from "./useRouteSketch";

// Building the file needs the AMPS template over the network; what is under test is what the hook
// does with the outcome.
jest.mock("./createMsnx", () => ({ ...jest.requireActual("./createMsnx"), buildSketchMsnx: jest.fn() }));

const ROUTES = [{ id: "sketch-1", name: "RED 1", points: [] }];

describe("exporting sketched routes", () => {
  let alertSpy;
  beforeEach(() => {
    alertSpy = jest.spyOn(window, "alert").mockImplementation(() => {});
    buildSketchMsnx.mockReset();
  });
  afterEach(() => alertSpy.mockRestore());

  it("hands a failure to the caller rather than keeping it", async () => {
    buildSketchMsnx.mockRejectedValue(new Error("template missing"));
    const { result } = renderHook(() => useRouteSketch());
    let outcome;
    await act(async () => {
      outcome = result.current.exportSketches(ROUTES);
      await expect(outcome).rejects.toThrow("template missing");
    });
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it("returns the airframe warning for the caller to show", async () => {
    const made = { airframe: "UH-60L", exact: false, warning: "This mission will open in AMPS as a UH-60L." };
    buildSketchMsnx.mockResolvedValue(made);
    const { result } = renderHook(() => useRouteSketch());
    await expect(result.current.exportSketches(ROUTES)).resolves.toEqual(made);
    expect(buildSketchMsnx).toHaveBeenCalledWith(ROUTES, undefined, null);
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it("makes nothing from no routes", async () => {
    const { result } = renderHook(() => useRouteSketch());
    await expect(result.current.exportSketches([])).resolves.toBeNull();
    expect(buildSketchMsnx).not.toHaveBeenCalled();
  });
});
