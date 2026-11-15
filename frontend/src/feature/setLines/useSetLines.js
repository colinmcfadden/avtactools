import { useCallback, useRef, useState } from "react";
import { filedLine } from "./setLines";

/**
 * The lines of one kind (FLOTs, phase lines) open in this session, filed as sketched routes are: `setId` none for this session's
 * own, or the id of the set they are in (one opened from the Library, or a mission pack's). A line being drawn is a draft held here
 * (null when none is), so abandoning it leaves nothing behind; it needs two points to become a line.
 *
 * `idPrefix` starts every id made here, and `fromData(data)` reads the kind's lines out of a set's saved data.
 */
export const useSetLines = ({ idPrefix, fromData }) => {
  const [lines, setLines] = useState([]);
  const [draft, setDraft] = useState(null);
  // What is here now, not when the caller's render began: opening a saved set waits on the network.
  const linesRef = useRef(lines);
  linesRef.current = lines;
  // The draft as it is now too: a line is finished from a dialog that was opened renders ago (a phase line's name).
  const draftRef = useRef(draft);
  draftRef.current = draft;

  const newId = useCallback(() => `${idPrefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`, [idPrefix]);

  const changeLine = useCallback((id, change) => setLines((list) => list.map((line) => (line.id === id ? change(line) : line))), []);
  const updateLine = useCallback((id, patch) => changeLine(id, (line) => ({ ...line, ...patch })), [changeLine]);
  const deleteLine = useCallback((id) => setLines((list) => list.filter((line) => line.id !== id)), []);

  /** Puts `list` (a set's saved data's lines) in place of the lines of the set `setId` (none: this session's). */
  const setLinesOf = useCallback((setId, list = []) => {
    const owner = setId ?? null;
    setLines((previous) => [...previous.filter((line) => (line.setId ?? null) !== owner), ...list.map((line) => filedLine(line, setId))]);
  }, []);

  /** A set's lines opened from the Library: new ids, as its routes get, since a record and its copy can be open side by side. */
  const openSavedLines = useCallback(
    (setId, data) =>
      setLinesOf(
        setId,
        fromData(data).map((line) => ({ ...line, id: newId() })),
      ),
    [setLinesOf, fromData, newId],
  );

  const hasSessionLines = useCallback(() => linesRef.current.some((line) => !line.setId), []);

  /** Makes the draft a line, `make(points, id)`, in the set `setId` (none: this session's). */
  const finishDrawing = (make, setId = null) => {
    const points = draftRef.current;
    if (!points || points.length < 2) return;
    setLines((previous) => [...previous, filedLine(make(points, newId()), setId)]);
    setDraft(null);
  };

  return {
    lines,
    changeLine,
    updateLine,
    deleteLine,
    setLinesOf,
    openSavedLines,
    hasSessionLines,
    isDrawing: draft !== null,
    draftPoints: draft ?? [],
    startDrawing: useCallback(() => setDraft([]), []),
    addDraftPoint: useCallback((lat, lon) => setDraft((points) => (points ? [...points, { lat, lon }] : points)), []),
    undoDraftPoint: useCallback(() => setDraft((points) => (points ? points.slice(0, -1) : points)), []),
    cancelDrawing: useCallback(() => setDraft(null), []),
    finishDrawing,
  };
};
