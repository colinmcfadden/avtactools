import { useCallback, useRef, useState } from "react";
import { createFlot, flipFlot, flotsFromData } from "./flots";

const newFlotId = () => `flot-${Date.now()}-${Math.random().toString(36).slice(2)}`;

const filed = ({ setId: _old, ...flot }, setId) => (setId ? { ...flot, setId } : flot);

/**
 * The FLOTs open in this session, filed as sketched routes are: `setId` none for this session's own, or the id of the set they are in
 * (one opened from the Library, or a mission pack's). A line being drawn is a draft held here (null when none is), so abandoning it
 * leaves nothing behind; it needs two points to become a FLOT.
 */
export const useFlots = () => {
  const [flots, setFlots] = useState([]);
  const [draft, setDraft] = useState(null);
  // What is here now, not when the caller's render began: opening a saved set waits on the network.
  const flotsRef = useRef(flots);
  flotsRef.current = flots;

  const updateFlot = useCallback((id, patch) => setFlots((list) => list.map((flot) => (flot.id === id ? { ...flot, ...patch } : flot))), []);
  const flipFlotById = useCallback((id) => setFlots((list) => list.map((flot) => (flot.id === id ? flipFlot(flot) : flot))), []);
  const deleteFlot = useCallback((id) => setFlots((list) => list.filter((flot) => flot.id !== id)), []);

  /** Puts `list` (a set's saved data's FLOTs) in place of the FLOTs of the set `setId` (none: this session's). */
  const setFlotsOf = useCallback((setId, list = []) => {
    const owner = setId ?? null;
    setFlots((previous) => [...previous.filter((flot) => (flot.setId ?? null) !== owner), ...list.map((flot) => filed(flot, setId))]);
  }, []);

  /** A set's FLOTs opened from the Library: new ids, as its routes get, since a record and its copy can be open side by side. */
  const openSavedFlots = useCallback(
    (setId, data) =>
      setFlotsOf(
        setId,
        flotsFromData(data).map((flot) => ({ ...flot, id: newFlotId() })),
      ),
    [setFlotsOf],
  );

  const hasSessionFlots = useCallback(() => flotsRef.current.some((flot) => !flot.setId), []);

  /** Makes the draft a FLOT in the set `setId` (none: this session's). */
  const finishDrawing = (setId = null) => {
    if (!draft || draft.length < 2) return;
    setFlots((previous) => [...previous, filed(createFlot(draft, newFlotId()), setId)]);
    setDraft(null);
  };

  return {
    flots,
    updateFlot,
    flipFlot: flipFlotById,
    deleteFlot,
    setFlotsOf,
    openSavedFlots,
    hasSessionFlots,
    isDrawing: draft !== null,
    draftPoints: draft ?? [],
    startDrawing: useCallback(() => setDraft([]), []),
    addDraftPoint: useCallback((lat, lon) => setDraft((points) => (points ? [...points, { lat, lon }] : points)), []),
    undoDraftPoint: useCallback(() => setDraft((points) => (points ? points.slice(0, -1) : points)), []),
    cancelDrawing: useCallback(() => setDraft(null), []),
    finishDrawing,
  };
};
