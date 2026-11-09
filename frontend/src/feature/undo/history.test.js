import {
  EMPTY_HISTORY,
  MAX_UNDO,
  patchHistory,
  recordStep,
  redoStep,
  topRedo,
  topUndo,
  undoStep,
} from "./history";

const step = (before, after, more = {}) => ({ label: "Change", before, after, seq: 1, ...more });

describe("recordStep", () => {
  it("adds a step and clears what could be redone", () => {
    const one = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }));
    const undone = undoStep(one, 2).history;
    const next = recordStep(undone, step({ a: 1 }, { a: 3 }));
    expect(next.undo).toHaveLength(1);
    expect(next.redo).toEqual([]);
  });

  it("makes no step for no difference, and leaves redo alone", () => {
    const one = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }));
    const undone = undoStep(one, 2).history;
    expect(recordStep(undone, step({ a: 1 }, { a: 1 }))).toBe(undone);
  });

  it("merges steps made under one key, and keeps the first label", () => {
    let history = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }, { key: "visit", label: "Edit altitude" }));
    history = recordStep(history, step({ a: 2 }, { a: 3 }, { key: "visit", label: "Other", seq: 5 }));
    expect(history.undo).toHaveLength(1);
    expect(history.undo[0]).toMatchObject({ before: { a: 1 }, after: { a: 3 }, label: "Edit altitude", seq: 5 });
  });

  it("drops a merged step that ends where it began", () => {
    let history = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }, { key: "visit" }));
    history = recordStep(history, step({ a: 2 }, { a: 1 }, { key: "visit" }));
    expect(history.undo).toEqual([]);
  });

  it("does not merge across another key or none", () => {
    let history = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }, { key: "one" }));
    history = recordStep(history, step({ a: 2 }, { a: 3 }, { key: "two" }));
    history = recordStep(history, step({ a: 3 }, { a: 4 }));
    history = recordStep(history, step({ a: 4 }, { a: 5 }));
    expect(history.undo).toHaveLength(4);
  });

  it("keeps the newest steps past the limit", () => {
    let history = EMPTY_HISTORY;
    for (let n = 0; n < MAX_UNDO + 5; n += 1) history = recordStep(history, step({ n }, { n: n + 1 }));
    expect(history.undo).toHaveLength(MAX_UNDO);
    expect(topUndo(history).after).toEqual({ n: MAX_UNDO + 5 });
    expect(history.undo[0].before).toEqual({ n: 5 });
  });
});

describe("undoStep and redoStep", () => {
  it("walk back and forth", () => {
    const one = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }));
    const undone = undoStep(one, 7);
    expect(undone.step.before).toEqual({ a: 1 });
    expect(topRedo(undone.history).undoneSeq).toBe(7);
    const redone = redoStep(undone.history);
    expect(redone.step.after).toEqual({ a: 2 });
    expect(topUndo(redone.history)).toMatchObject({ seq: 1 });
    expect(topUndo(redone.history).undoneSeq).toBeUndefined();
    expect(redone.history.redo).toEqual([]);
  });

  it("answer null with nothing to do", () => {
    expect(undoStep(EMPTY_HISTORY, 1)).toBeNull();
    expect(redoStep(EMPTY_HISTORY)).toBeNull();
  });

  it("does not merge a step made after a redo into the one redone", () => {
    const one = recordStep(EMPTY_HISTORY, step({ a: 1 }, { a: 2 }, { key: "visit" }));
    const redone = redoStep(undoStep(one, 2).history).history;
    const next = recordStep(redone, step({ a: 2 }, { a: 3 }, { key: "visit" }));
    expect(next.undo).toHaveLength(2);
  });
});

describe("patchHistory", () => {
  const twoSteps = () => {
    const shared = { a: 2 };
    let history = recordStep(EMPTY_HISTORY, step({ a: 1 }, shared));
    history = recordStep(history, step(shared, { a: 3 }));
    return history;
  };

  it("lays a change over every version and leaves redo standing", () => {
    const undone = undoStep(twoSteps(), 9).history;
    const patched = patchHistory(undone, (v) => ({ ...v, theirs: true }));
    expect(patched.undo[0].before).toEqual({ a: 1, theirs: true });
    expect(patched.redo[0].after).toEqual({ a: 3, theirs: true });
    expect(patched.redo).toHaveLength(1);
  });

  it("changes each shared version once and keeps it shared", () => {
    let calls = 0;
    const patched = patchHistory(twoSteps(), (v) => {
      calls += 1;
      return { ...v };
    });
    expect(calls).toBe(3);
    expect(patched.undo[0].after).toBe(patched.undo[1].before);
  });

  it("drops a step the change leaves with no difference", () => {
    const patched = patchHistory(twoSteps(), (v) => ({ a: Math.min(v.a, 2) }));
    expect(patched.undo).toHaveLength(1);
    expect(patched.undo[0]).toMatchObject({ before: { a: 1 }, after: { a: 2 } });
  });

  it("returns an empty history as it is", () => {
    expect(patchHistory(EMPTY_HISTORY, (v) => v)).toBe(EMPTY_HISTORY);
  });
});
