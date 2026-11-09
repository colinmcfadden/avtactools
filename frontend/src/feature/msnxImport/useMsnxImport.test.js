import { act, renderHook } from "@testing-library/react";
import { updatePointCoordinate, updatePointName, insertPointOnRoute } from "./mutateMsnx";
import { useMsnxImport } from "./useMsnxImport";

vi.mock("./parseMsnx", () => ({
  parseMsnxFile: vi.fn(async () => ({
    zip: {},
    docs: { marker: true },
    aircraft: null,
    routes: [
      { name: "A", segmentId: "s1", plan: {}, elevations: {}, points: [{ id: "a1", name: ".CP1", lat: 1, lon: 2 }, { id: "lz-a", name: ".LZ", lat: 3, lon: 4 }] },
      { name: "B", segmentId: "s2", plan: {}, elevations: {}, points: [{ id: "lz-b", name: ".LZ", lat: 3, lon: 4 }, { id: "b2", name: ".CP2", lat: 5, lon: 6 }] },
    ],
  })),
}));

vi.mock("./mutateMsnx", async (importOriginal) => ({
  ...(await importOriginal()),
  updatePointCoordinate: vi.fn(),
  updatePointName: vi.fn(),
  insertPointOnRoute: vi.fn(() => ({ afterPointId: "a1", point: { id: "new", name: ".NEWPT", lat: 2, lon: 3 } })),
}));

vi.mock("./routeCalc", async (importOriginal) => ({
  ...(await importOriginal()),
  fetchPointElevationsFt: vi.fn(async () => ({ a1: 1234 })),
}));

const opened = async () => {
  const view = renderHook(() => useMsnxImport());
  let fileId;
  await act(async () => {
    ({ fileId } = await view.result.current.importMsnxFile({ name: "m.msnx" }));
  });
  vi.clearAllMocks();
  return { ...view, fileId };
};

const routeOf = (result, index = 0) => result.current.importedRoutes[index];
const pointOf = (result, index, id) => routeOf(result, index).points.find((p) => p.id === id);

describe("useMsnxImport undo", () => {
  it("writes a move's undo and redo into the mission's XML, each once", async () => {
    const { result, fileId } = await opened();
    const id = routeOf(result).id;
    act(() => { result.current.updatePointPosition(id, "a1", 10, 20); });
    expect(updatePointCoordinate.mock.calls).toEqual([[{ marker: true }, "a1", 10, 20]]);
    vi.clearAllMocks();
    act(() => { result.current.undoMission(fileId); });
    expect(pointOf(result, 0, "a1")).toMatchObject({ lat: 1, lon: 2 });
    expect(updatePointCoordinate.mock.calls).toEqual([[{ marker: true }, "a1", 1, 2]]);
    vi.clearAllMocks();
    act(() => { result.current.redoMission(fileId); });
    expect(pointOf(result, 0, "a1")).toMatchObject({ lat: 10, lon: 20 });
    expect(updatePointCoordinate.mock.calls).toEqual([[{ marker: true }, "a1", 10, 20]]);
  });

  it("takes a hand-off point back in both routes in one step", async () => {
    const { result, fileId } = await opened();
    act(() => { result.current.updatePointPosition(routeOf(result).id, "lz-a", 7, 8); });
    expect(pointOf(result, 1, "lz-b")).toMatchObject({ lat: 7, lon: 8 });
    expect(result.current.missionHistory[fileId].undo).toHaveLength(1);
    vi.clearAllMocks();
    act(() => { result.current.undoMission(fileId); });
    expect(pointOf(result, 0, "lz-a")).toMatchObject({ lat: 3, lon: 4 });
    expect(pointOf(result, 1, "lz-b")).toMatchObject({ lat: 3, lon: 4 });
    expect(updatePointCoordinate.mock.calls.map((c) => c[1]).sort()).toEqual(["lz-a", "lz-b"]);
  });

  it("writes a rename's undo into the XML, and a snap's place too", async () => {
    const { result, fileId } = await opened();
    act(() => { result.current.updatePointName(routeOf(result).id, "a1", ".HAWK", { lat: 9, lon: 9 }, 100); });
    vi.clearAllMocks();
    act(() => { result.current.undoMission(fileId); });
    expect(updatePointName.mock.calls).toEqual([[{ marker: true }, "a1", ".CP1"]]);
    expect(updatePointCoordinate.mock.calls).toEqual([[{ marker: true }, "a1", 1, 2]]);
  });

  it("treats a plan value as a step that touches no XML", async () => {
    const { result, fileId } = await opened();
    act(() => { result.current.updateRoutePlan(routeOf(result).id, { fuelFlow: 900 }); });
    vi.clearAllMocks();
    act(() => { result.current.undoMission(fileId); });
    expect(routeOf(result).plan.fuelFlow).not.toBe(900);
    expect(updatePointCoordinate).not.toHaveBeenCalled();
    expect(updatePointName).not.toHaveBeenCalled();
  });

  it("clears what could be undone when a point is spliced into the file", async () => {
    const { result, fileId } = await opened();
    act(() => { result.current.updatePointPosition(routeOf(result).id, "a1", 10, 20); });
    act(() => { result.current.insertPoint(routeOf(result).id, 2, 3); });
    expect(insertPointOnRoute).toHaveBeenCalledTimes(1);
    expect(routeOf(result).points.map((p) => p.id)).toEqual(["a1", "new", "lz-a"]);
    expect(result.current.missionHistory[fileId]).toBeUndefined();
  });

  it("names a point spliced in from the plan, in the file too, and answers its id", async () => {
    const { result } = await opened();
    let id;
    act(() => { id = result.current.insertPoint(routeOf(result).id, 2, 3, { name: "HAWK", chartElevationFt: 900 }); });
    expect(id).toBe("new");
    expect(updatePointName).toHaveBeenCalledWith(expect.anything(), "new", "HAWK");
    expect(routeOf(result).points[1]).toMatchObject({ id: "new", name: "HAWK", chartElevationFt: 900 });
  });

  it("brings a deleted route back, and forgets everything when the mission is closed", async () => {
    const { result, fileId } = await opened();
    act(() => { result.current.removeRoute(routeOf(result, 1).id); });
    expect(result.current.importedRoutes).toHaveLength(1);
    act(() => { result.current.undoMission(fileId); });
    expect(result.current.importedRoutes.map((r) => r.name)).toEqual(["A", "B"]);
    act(() => { result.current.closeMission(fileId); });
    expect(result.current.importedRoutes).toEqual([]);
    expect(result.current.missionHistory[fileId]).toBeUndefined();
  });

  it("keeps fetched ground through an undo and adds no step for it", async () => {
    const { result, fileId } = await opened();
    const id = routeOf(result).id;
    act(() => { result.current.updateRoutePlan(id, { fuelFlow: 900 }); });
    await act(async () => { await result.current.refreshRouteElevations(id); });
    expect(result.current.missionHistory[fileId].undo).toHaveLength(1);
    act(() => { result.current.undoMission(fileId); });
    expect(routeOf(result).elevations).toEqual({ a1: 1234 });
  });

  it("keeps a route hidden through an undo", async () => {
    const { result, fileId } = await opened();
    const id = routeOf(result).id;
    act(() => { result.current.updatePointPosition(id, "a1", 10, 20); });
    act(() => { result.current.toggleRouteVisibility(id); });
    act(() => { result.current.undoMission(fileId); });
    expect(routeOf(result).visible).toBe(false);
  });
});
