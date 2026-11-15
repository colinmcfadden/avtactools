import { useCallback } from "react";
import { useSetLines } from "../setLines/useSetLines";
import { createFlot, flipFlot, flotsFromData } from "./flots";

/**
 * The FLOTs open in this session, filed as sketched routes are: `setId` none for this session's own, or the id of the set they are in
 * (one opened from the Library, or a mission pack's). A line being drawn is a draft held here (null when none is), so abandoning it
 * leaves nothing behind; it needs two points to become a FLOT. useSetLines keeps them, as it keeps a set's phase lines.
 */
export const useFlots = () => {
  const lines = useSetLines({ idPrefix: "flot", fromData: flotsFromData });
  const { changeLine, finishDrawing } = lines;
  const flipFlotById = useCallback((id) => changeLine(id, flipFlot), [changeLine]);

  return {
    flots: lines.lines,
    updateFlot: lines.updateLine,
    flipFlot: flipFlotById,
    deleteFlot: lines.deleteLine,
    setFlotsOf: lines.setLinesOf,
    openSavedFlots: lines.openSavedLines,
    hasSessionFlots: lines.hasSessionLines,
    isDrawing: lines.isDrawing,
    draftPoints: lines.draftPoints,
    startDrawing: lines.startDrawing,
    addDraftPoint: lines.addDraftPoint,
    undoDraftPoint: lines.undoDraftPoint,
    cancelDrawing: lines.cancelDrawing,
    /** Makes the draft a FLOT in the set `setId` (none: this session's). */
    finishDrawing: (setId = null) => finishDrawing(createFlot, setId),
  };
};
