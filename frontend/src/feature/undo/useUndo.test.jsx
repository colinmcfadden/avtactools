import { act, renderHook } from "@testing-library/react";
import { vi } from "vitest";
import { EMPTY_HISTORY, recordStep, redoStep, undoStep } from "./history";
import { pickRedo, pickUndo, useUndo } from "./useUndo";

const withStep = (history, seq, label = "Step") =>
  recordStep(history, { label, before: { n: seq }, after: { n: seq + 0.5 }, seq });
const source = (history) => ({ history, undo: vi.fn(), redo: vi.fn() });

describe("pickUndo", () => {
  it("takes the newest step of all", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1));
    const routes = source(withStep(EMPTY_HISTORY, 2));
    expect(pickUndo([lz, routes]).source).toBe(routes);
  });

  it("answers null with nothing to undo", () => {
    expect(pickUndo([source(EMPTY_HISTORY), null])).toBeNull();
  });
});

describe("pickRedo", () => {
  const undone = (seq, undoneSeq) => undoStep(withStep(EMPTY_HISTORY, seq), undoneSeq).history;

  it("takes the step undone last", () => {
    const lz = source(undone(1, 11));
    const routes = source(undone(2, 10));
    expect(pickRedo([lz, routes]).source).toBe(lz);
  });

  it("offers nothing once something has been done since", () => {
    const routes = source(undone(2, 10));
    const lz = source(withStep(EMPTY_HISTORY, 12));
    expect(pickRedo([lz, routes])).toBeNull();
  });

  it("walks back and forth across two documents in order", () => {
    let lz = withStep(EMPTY_HISTORY, 1, "A");
    let routes = withStep(EMPTY_HISTORY, 2, "B");
    routes = undoStep(routes, 3).history;
    lz = undoStep(lz, 4).history;
    expect(pickRedo([source(lz), source(routes)]).step.label).toBe("A");
    lz = redoStep(lz).history;
    expect(pickRedo([source(lz), source(routes)]).step.label).toBe("B");
    routes = redoStep(routes).history;
    expect(pickUndo([source(lz), source(routes)]).step.label).toBe("B");
  });
});

describe("useUndo", () => {
  const press = (key, more = {}, target = window) =>
    act(() => {
      target.dispatchEvent(new KeyboardEvent("keydown", { key, ctrlKey: true, bubbles: true, cancelable: true, ...more }));
    });

  it("undoes on the key and says what it undid", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1, "Move helicopter"));
    const toast = vi.fn();
    renderHook(() => useUndo({ sources: [lz], toast }));
    press("z");
    expect(lz.undo).toHaveBeenCalledTimes(1);
    expect(toast).toHaveBeenCalledWith({ tone: "info", message: "Undid: Move helicopter" });
  });

  it("redoes on Shift+Z and on Y", () => {
    const lz = source(undoStep(withStep(EMPTY_HISTORY, 1), 2).history);
    renderHook(() => useUndo({ sources: [lz] }));
    press("Z", { shiftKey: true });
    press("y");
    expect(lz.redo).toHaveBeenCalledTimes(2);
  });

  it("leaves the keys to a field being typed in", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1));
    renderHook(() => useUndo({ sources: [lz] }));
    const input = document.createElement("input");
    document.body.append(input);
    press("z", {}, input);
    input.remove();
    expect(lz.undo).not.toHaveBeenCalled();
  });

  it("leaves the keys alone with a dialog open or a window over the map", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1));
    const { rerender } = renderHook(({ keysOff }) => useUndo({ sources: [lz], keysOff }), { initialProps: { keysOff: true } });
    press("z");
    rerender({ keysOff: false });
    const dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    document.body.append(dialog);
    press("z");
    dialog.remove();
    expect(lz.undo).not.toHaveBeenCalled();
  });

  it("takes back a drawing's last point rather than a step", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1));
    const removeLast = vi.fn();
    const { result } = renderHook(() => useUndo({ sources: [lz], draft: { points: 2, removeLast } }));
    press("z");
    expect(removeLast).toHaveBeenCalledTimes(1);
    expect(lz.undo).not.toHaveBeenCalled();
    expect(result.current.canRedo).toBe(false);
    expect(result.current.undoTitle).toBe("Undo: Remove last point");
  });

  it("says why nothing can be undone in a read-only pack", () => {
    const lz = source(withStep(EMPTY_HISTORY, 1));
    const toast = vi.fn();
    const { result } = renderHook(() => useUndo({ sources: [lz], lockedReason: "Read only.", toast }));
    expect(result.current.canUndo).toBe(false);
    expect(result.current.undoTitle).toBe("Read only.");
    press("z");
    expect(lz.undo).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith({ tone: "warn", message: "Read only." });
  });
});
