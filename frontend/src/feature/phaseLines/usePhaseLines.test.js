import { act, renderHook } from "@testing-library/react";
import { usePhaseLines } from "./usePhaseLines";

const drawn = (result, name = "RED", setId = undefined) => {
  act(() => result.current.startDrawing());
  act(() => {
    result.current.addDraftPoint(34, -84);
    result.current.addDraftPoint(34.1, -84.1);
  });
  act(() => result.current.finishDrawing(name, setId));
};

describe("usePhaseLines", () => {
  it("makes a phase line from a draft of two points or more, under the name given, in this session's own by default", () => {
    const { result } = renderHook(() => usePhaseLines());
    drawn(result, "pl dog");
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.phaseLines).toHaveLength(1);
    const [line] = result.current.phaseLines;
    expect(line).not.toHaveProperty("setId");
    expect(line.id).toMatch(/^pl-/);
    expect(line.name).toBe("DOG");
    expect(line.points).toEqual([{ lat: 34, lon: -84 }, { lat: 34.1, lon: -84.1 }]);
  });

  it("files a phase line under the set it is drawn into", () => {
    const { result } = renderHook(() => usePhaseLines());
    drawn(result, "RED", "pack:p:i");
    expect(result.current.phaseLines[0].setId).toBe("pack:p:i");
  });

  it("does not finish with one point, and keeps drawing", () => {
    const { result } = renderHook(() => usePhaseLines());
    act(() => result.current.startDrawing());
    act(() => result.current.addDraftPoint(34, -84));
    act(() => result.current.finishDrawing("RED"));
    expect(result.current.isDrawing).toBe(true);
    expect(result.current.phaseLines).toEqual([]);
  });

  it("makes nothing of a draft dropped while its name was asked for", () => {
    const { result } = renderHook(() => usePhaseLines());
    act(() => result.current.startDrawing());
    act(() => {
      result.current.addDraftPoint(34, -84);
      result.current.addDraftPoint(35, -85);
    });
    // The name dialog holds the finish of the render it was opened in.
    const finishAsAsked = result.current.finishDrawing;
    act(() => result.current.cancelDrawing());
    act(() => finishAsAsked("RED"));
    expect(result.current.phaseLines).toEqual([]);
    expect(result.current.isDrawing).toBe(false);
  });

  it("leaves nothing behind when cancelled, and takes a point back", () => {
    const { result } = renderHook(() => usePhaseLines());
    act(() => result.current.startDrawing());
    act(() => {
      result.current.addDraftPoint(34, -84);
      result.current.addDraftPoint(35, -85);
    });
    act(() => result.current.undoDraftPoint());
    expect(result.current.draftPoints).toEqual([{ lat: 34, lon: -84 }]);
    act(() => result.current.cancelDrawing());
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.phaseLines).toEqual([]);
  });

  it("renames, moves and deletes one", () => {
    const { result } = renderHook(() => usePhaseLines());
    drawn(result);
    const { id } = result.current.phaseLines[0];
    act(() => result.current.renamePhaseLine(id, "blue"));
    expect(result.current.phaseLines[0].name).toBe("BLUE");
    act(() => result.current.renamePhaseLine(id, "  "));
    expect(result.current.phaseLines[0].name).toBe("BLUE");
    act(() => result.current.updatePhaseLine(id, { points: [{ lat: 1, lon: 2 }, { lat: 3, lon: 4 }] }));
    expect(result.current.phaseLines[0].points[0]).toEqual({ lat: 1, lon: 2 });
    act(() => result.current.deletePhaseLine(id));
    expect(result.current.phaseLines).toEqual([]);
  });

  it("replaces only the phase lines of the set named", () => {
    const { result } = renderHook(() => usePhaseLines());
    drawn(result);
    drawn(result, "BLUE", "opened-a");
    act(() => result.current.setPhaseLinesOf("opened-a", [{ id: "new", name: "DOG", points: [] }]));
    expect(result.current.phaseLines.map((l) => [l.id === "new", l.setId])).toEqual([[false, undefined], [true, "opened-a"]]);
    act(() => result.current.setPhaseLinesOf(null, []));
    expect(result.current.phaseLines.map((l) => l.id)).toEqual(["new"]);
  });

  it("gives phase lines opened from the Library new ids and keeps their names", () => {
    const { result } = renderHook(() => usePhaseLines());
    const data = { phaseLines: [{ id: "saved", name: "RED", points: [] }], flots: [{ id: "flot", points: [] }] };
    act(() => result.current.openSavedPhaseLines("opened-a", data));
    act(() => result.current.openSavedPhaseLines("opened-b", data));
    const [one, two] = result.current.phaseLines;
    expect(result.current.phaseLines).toHaveLength(2);
    expect(one.id).not.toBe("saved");
    expect(one.id).not.toBe(two.id);
    expect(one.name).toBe("RED");
  });

  it("says whether this session has phase lines of its own", () => {
    const { result } = renderHook(() => usePhaseLines());
    expect(result.current.hasSessionPhaseLines()).toBe(false);
    drawn(result, "RED", "opened-a");
    expect(result.current.hasSessionPhaseLines()).toBe(false);
    drawn(result);
    expect(result.current.hasSessionPhaseLines()).toBe(true);
  });
});
