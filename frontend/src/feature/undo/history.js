/*
 * Undo and redo of one document, as plain data, so a reducer can hold it (StrictMode runs reducers
 * twice: nothing here has a side effect). Android's DocumentSession is the same idea.
 *
 * A step is { label, before, after, key, seq }: the document's content either side of one thing a
 * person did. Steps made one after another under one key are one step (a visit to a field), and one
 * that ends where it began is no step at all. Whatever is not the person's own doing (an analysis,
 * fetched winds, someone else's change in a pack) goes through patchHistory: it is laid over every
 * version, so undo never takes it back, and it leaves redo as it was.
 *
 * `seq` orders steps across documents, so one Undo can take the last thing done wherever it was
 * (feature/undo/useUndo.js). It is taken when the person acts, never in a reducer.
 */

export const MAX_UNDO = 100;

export const EMPTY_HISTORY = Object.freeze({ undo: Object.freeze([]), redo: Object.freeze([]) });

let lastSeq = 0;

/** The next place in the order of everything done. Called at the moment of acting, outside any reducer. */
export const nextStepSeq = () => {
  lastSeq += 1;
  return lastSeq;
};

export const sameJson = (a, b) => a === b || JSON.stringify(a) === JSON.stringify(b);

/**
 * `change` ({ label, before, after, key?, seq }) as the newest step. No difference, no step, and redo
 * is left alone: nothing was done. A key matching the newest step's merges into it.
 */
export const recordStep = (history, change, same = sameJson) => {
  const { label, before, after, key = null, seq } = change;
  if (same(before, after)) return history;
  const top = history.undo[history.undo.length - 1];
  if (key !== null && top && top.key === key) {
    const rest = history.undo.slice(0, -1);
    if (same(top.before, after)) return { undo: rest, redo: [] };
    return { undo: [...rest, { ...top, after, seq }], redo: [] };
  }
  const undo = [...history.undo, { label, before, after, key, seq }];
  return { undo: undo.length > MAX_UNDO ? undo.slice(undo.length - MAX_UNDO) : undo, redo: [] };
};

/** The newest step taken back: { history, step }, or null with none to take back. */
export const undoStep = (history, undoneSeq) => {
  const step = history.undo[history.undo.length - 1];
  if (!step) return null;
  return {
    step,
    history: { undo: history.undo.slice(0, -1), redo: [...history.redo, { ...step, undoneSeq, key: null }] },
  };
};

/**
 * The step last taken back, made again: { history, step }, or null with none. It keeps its `seq`, its
 * place in the order of everything done, so walking back and forth across documents stays in order.
 */
export const redoStep = (history) => {
  const step = history.redo[history.redo.length - 1];
  if (!step) return null;
  const { undoneSeq: _undoneSeq, ...made } = step;
  return { step, history: { undo: [...history.undo, made], redo: history.redo.slice(0, -1) } };
};

/**
 * `change` (version -> version) laid over every version in the history. Versions that are one object
 * (a step's `after` and the next step's `before`) stay one, so it costs one change per version
 * however long the history is. A step the change leaves with no difference goes.
 */
export const patchHistory = (history, change, same = sameJson) => {
  if (!history.undo.length && !history.redo.length) return history;
  const done = new Map();
  const patched = (version) => {
    if (!done.has(version)) done.set(version, change(version));
    return done.get(version);
  };
  const patchSteps = (steps) =>
    steps.flatMap((step) => {
      const before = patched(step.before);
      const after = patched(step.after);
      return same(before, after) ? [] : [{ ...step, before, after }];
    });
  return { undo: patchSteps(history.undo), redo: patchSteps(history.redo) };
};

export const canUndo = (history) => Boolean(history?.undo.length);
export const canRedo = (history) => Boolean(history?.redo.length);
export const topUndo = (history) => history?.undo[history.undo.length - 1] ?? null;
export const topRedo = (history) => history?.redo[history.redo.length - 1] ?? null;
