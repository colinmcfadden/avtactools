import { useEffect, useRef } from "react";
import { topRedo, topUndo } from "./history";

/*
 * One Undo for what is on the map. Each document keeps its own history (history.js); a `source` is one
 * of them, { history, undo(), redo() }: the LZ/PZ showing and each route set on the map. Undo takes the
 * newest step among them, so it is "the last thing I did" wherever that was, and never changes a document
 * that is not showing.
 *
 * Redo takes the step undone last. A step keeps its place in the order when it is redone. A new edit
 * elsewhere than the step undone ends its redo as an edit in its own document would: one timeline.
 */

/** The source whose newest step is the newest of all, or null. */
export const pickUndo = (sources) => {
  let best = null;
  sources.forEach((source) => {
    const step = topUndo(source?.history);
    if (step && (!best || step.seq > best.step.seq)) best = { source, step };
  });
  return best;
};

/** The source holding the step undone last, while nothing has been done since. */
export const pickRedo = (sources) => {
  let best = null;
  let newest = 0;
  sources.forEach((source) => {
    const step = topRedo(source?.history);
    if (step && (!best || step.undoneSeq > best.step.undoneSeq)) best = { source, step };
    newest = Math.max(newest, topUndo(source?.history)?.seq ?? 0);
  });
  return best && newest < best.step.undoneSeq ? best : null;
};

const isTyping = (target) =>
  Boolean(target) &&
  (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || target.isContentEditable);

/**
 * `sources`: as above. `draft`: a drawing under way, { points, removeLast } (while it is, undo takes back
 * its last point and nothing else). `lockedReason`: why nothing can be undone now. `keysOff`: the keyboard
 * shortcuts are off (a window over the planning map).
 */
export const useUndo = ({ sources, draft = null, lockedReason = null, keysOff = false, toast }) => {
  const undoTarget = pickUndo(sources);
  const redoTarget = pickRedo(sources);
  const drawing = Boolean(draft);

  const canUndo = !lockedReason && (drawing ? draft.points > 0 : Boolean(undoTarget));
  const canRedo = !lockedReason && !drawing && Boolean(redoTarget);

  const undo = () => {
    if (lockedReason) {
      toast?.({ tone: "warn", message: lockedReason });
      return;
    }
    if (drawing) {
      if (draft.points > 0) draft.removeLast();
      return;
    }
    if (!undoTarget) return;
    undoTarget.source.undo();
    toast?.({ tone: "info", message: `Undid: ${undoTarget.step.label}` });
  };

  const redo = () => {
    if (lockedReason) {
      toast?.({ tone: "warn", message: lockedReason });
      return;
    }
    if (drawing || !redoTarget) return;
    redoTarget.source.redo();
    toast?.({ tone: "info", message: `Redid: ${redoTarget.step.label}` });
  };

  const latest = useRef({});
  latest.current = { undo, redo, keysOff };

  useEffect(() => {
    const onKey = (event) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
      const key = event.key.toLowerCase();
      const isUndo = key === "z" && !event.shiftKey;
      const isRedo = (key === "z" && event.shiftKey) || (key === "y" && !event.shiftKey);
      if (!isUndo && !isRedo) return;
      // A field, a dialog and anything over the map keep the keys for themselves.
      if (latest.current.keysOff || isTyping(event.target) || document.querySelector('[role="dialog"]')) return;
      event.preventDefault();
      if (isUndo) latest.current.undo();
      else latest.current.redo();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const undoTitle =
    lockedReason ?? (drawing ? (draft.points > 0 ? "Undo: Remove last point" : "No point to remove") : undoTarget ? `Undo: ${undoTarget.step.label}` : "Nothing to undo");
  const redoTitle =
    lockedReason ?? (drawing ? "Finish drawing to redo" : redoTarget ? `Redo: ${redoTarget.step.label}` : "Nothing to redo");

  return { canUndo, canRedo, undo, redo, undoTitle, redoTitle };
};
