import { act, renderHook } from "@testing-library/react";
import { useState } from "react";
import { useBackstops } from "./useBackstops";

const setup = (initial) =>
  renderHook(() => {
    const [backstops, setBackstops] = useState(initial);
    return useBackstops({ backstops, setBackstops });
  });

describe("useBackstops", () => {
  it("treats a diagram that has none as an empty list", () => {
    expect(setup(undefined).result.current.backstops).toEqual([]);
  });

  it("adds a backstop only once the line has two points", () => {
    const { result } = setup(undefined);
    act(() => result.current.startDrawing());
    expect(result.current.isDrawing).toBe(true);
    act(() => result.current.addDraftPoint(34, -84));
    act(() => result.current.finishDrawing());
    expect(result.current.isDrawing).toBe(true);
    expect(result.current.backstops).toEqual([]);

    act(() => result.current.addDraftPoint(34.1, -84));
    act(() => result.current.finishDrawing());
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.backstops[0].points).toEqual([{ lat: 34, lon: -84 }, { lat: 34.1, lon: -84 }]);
    expect(result.current.backstops[0].id).toMatch(/^bs-/);
  });

  it("leaves nothing behind when a drawing is cancelled", () => {
    const { result } = setup([]);
    act(() => result.current.startDrawing());
    act(() => result.current.addDraftPoint(34, -84));
    act(() => result.current.cancelDrawing());
    expect(result.current.isDrawing).toBe(false);
    expect(result.current.draftPoints).toEqual([]);
    expect(result.current.backstops).toEqual([]);
  });

  it("takes back the last point, and takes no point when not drawing", () => {
    const { result } = setup([]);
    act(() => result.current.addDraftPoint(1, 2));
    expect(result.current.isDrawing).toBe(false);
    act(() => result.current.startDrawing());
    act(() => result.current.addDraftPoint(34, -84));
    act(() => result.current.addDraftPoint(35, -84));
    act(() => result.current.undoDraftPoint());
    expect(result.current.draftPoints).toEqual([{ lat: 34, lon: -84 }]);
  });

  it("changes and deletes only the one named", () => {
    const { result } = setup([{ id: "a", points: [] }, { id: "b", points: [] }]);
    act(() => result.current.updateBackstop("a", { points: [{ lat: 1, lon: 2 }] }));
    expect(result.current.backstops[0].points).toHaveLength(1);
    expect(result.current.backstops[1].points).toHaveLength(0);
    act(() => result.current.deleteBackstop("a"));
    expect(result.current.backstops.map((b) => b.id)).toEqual(["b"]);
  });
});
