import { act, renderHook } from "@testing-library/react";
import { useFlots } from "./useFlots";

const drawn = (result, setId) => {
  act(() => result.current.startDrawing());
  act(() => {
    result.current.addDraftPoint(34, -84);
    result.current.addDraftPoint(34.1, -84.1);
  });
  act(() => result.current.finishDrawing(setId));
};

describe("useFlots", () => {
  it("makes a FLOT from a draft of two points or more, in this session's own by default", () => {
    const { result } = renderHook(() => useFlots());
    drawn(result);
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.flots).toHaveLength(1);
    expect(result.current.flots[0]).not.toHaveProperty("setId");
    expect(result.current.flots[0].points).toEqual([{ lat: 34, lon: -84 }, { lat: 34.1, lon: -84.1 }]);
  });

  it("files a FLOT under the set it is drawn into", () => {
    const { result } = renderHook(() => useFlots());
    drawn(result, "pack:p:i");
    expect(result.current.flots[0].setId).toBe("pack:p:i");
  });

  it("does not finish with one point, and keeps drawing", () => {
    const { result } = renderHook(() => useFlots());
    act(() => result.current.startDrawing());
    act(() => result.current.addDraftPoint(34, -84));
    act(() => result.current.finishDrawing());
    expect(result.current.isDrawing).toBe(true);
    expect(result.current.flots).toEqual([]);
  });

  it("leaves nothing behind when cancelled, and takes a point back", () => {
    const { result } = renderHook(() => useFlots());
    act(() => result.current.startDrawing());
    act(() => {
      result.current.addDraftPoint(34, -84);
      result.current.addDraftPoint(35, -85);
    });
    act(() => result.current.undoDraftPoint());
    expect(result.current.draftPoints).toEqual([{ lat: 34, lon: -84 }]);
    act(() => result.current.cancelDrawing());
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.flots).toEqual([]);
  });

  it("takes no point when nothing is being drawn", () => {
    const { result } = renderHook(() => useFlots());
    act(() => result.current.addDraftPoint(34, -84));
    expect(result.current.draftPoints).toEqual([]);
  });

  it("flips, moves and deletes one", () => {
    const { result } = renderHook(() => useFlots());
    drawn(result);
    const { id } = result.current.flots[0];
    act(() => result.current.flipFlot(id));
    expect(result.current.flots[0].flipped).toBe(true);
    act(() => result.current.updateFlot(id, { points: [{ lat: 1, lon: 2 }, { lat: 3, lon: 4 }] }));
    expect(result.current.flots[0].points[0]).toEqual({ lat: 1, lon: 2 });
    act(() => result.current.deleteFlot(id));
    expect(result.current.flots).toEqual([]);
  });

  it("replaces only the FLOTs of the set named", () => {
    const { result } = renderHook(() => useFlots());
    drawn(result);
    drawn(result, "opened-a");
    act(() => result.current.setFlotsOf("opened-a", [{ id: "new", points: [] }]));
    expect(result.current.flots.map((f) => [f.id === "new", f.setId])).toEqual([[false, undefined], [true, "opened-a"]]);
    act(() => result.current.setFlotsOf(null, []));
    expect(result.current.flots.map((f) => f.id)).toEqual(["new"]);
  });

  it("gives FLOTs opened from the Library new ids, so a record and its copy can be open at once", () => {
    const { result } = renderHook(() => useFlots());
    const data = { flots: [{ id: "saved", flipped: true, points: [] }] };
    act(() => result.current.openSavedFlots("opened-a", data));
    act(() => result.current.openSavedFlots("opened-b", data));
    const [one, two] = result.current.flots;
    expect(one.id).not.toBe("saved");
    expect(one.id).not.toBe(two.id);
    expect(one.flipped).toBe(true);
  });

  it("says whether this session has FLOTs of its own", () => {
    const { result } = renderHook(() => useFlots());
    expect(result.current.hasSessionFlots()).toBe(false);
    drawn(result, "opened-a");
    expect(result.current.hasSessionFlots()).toBe(false);
    drawn(result);
    expect(result.current.hasSessionFlots()).toBe(true);
  });
});
