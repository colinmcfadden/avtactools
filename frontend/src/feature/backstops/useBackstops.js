import { useState } from "react";
import { createBackstop } from "./backstops";

const listOf = (value) => (Array.isArray(value) ? value : []);

/**
 * The open LZ/PZ's backstops, and the one being drawn. A line being drawn is a draft held here (null when none is), so abandoning it
 * leaves nothing on the diagram; it needs two points to become a backstop.
 */
export const useBackstops = ({ backstops, setBackstops }) => {
  const [draft, setDraft] = useState(null);

  const updateBackstop = (id, patch) =>
    setBackstops((previous) => listOf(previous).map((item) => (item.id === id ? { ...item, ...patch } : item)));
  const deleteBackstop = (id) => setBackstops((previous) => listOf(previous).filter((item) => item.id !== id));

  const finishDrawing = () => {
    if (!draft || draft.length < 2) return;
    const made = createBackstop(draft, `bs-${Date.now()}`);
    setBackstops((previous) => [...listOf(previous), made]);
    setDraft(null);
  };

  return {
    backstops: listOf(backstops),
    updateBackstop,
    deleteBackstop,
    isDrawing: draft !== null,
    draftPoints: draft ?? [],
    startDrawing: () => setDraft([]),
    addDraftPoint: (lat, lon) => setDraft((points) => (points ? [...points, { lat, lon }] : points)),
    undoDraftPoint: () => setDraft((points) => (points ? points.slice(0, -1) : points)),
    cancelDrawing: () => setDraft(null),
    finishDrawing,
  };
};
