import { useCallback } from "react";
import { useSetLines } from "../setLines/useSetLines";
import { createPhaseLine, phaseLinesFromData, renamePhaseLine } from "./phaseLines";

/**
 * The phase lines open in this session, kept as a set's FLOTs are (useSetLines): filed under the set they are in, with the line
 * being drawn held as a draft. A draft becomes a phase line under the name it is finished with.
 */
export const usePhaseLines = () => {
  const lines = useSetLines({ idPrefix: "pl", fromData: phaseLinesFromData });
  const { changeLine, finishDrawing } = lines;
  const rename = useCallback((id, name) => changeLine(id, (line) => renamePhaseLine(line, name)), [changeLine]);

  return {
    phaseLines: lines.lines,
    updatePhaseLine: lines.updateLine,
    renamePhaseLine: rename,
    deletePhaseLine: lines.deleteLine,
    setPhaseLinesOf: lines.setLinesOf,
    movePhaseLinesOf: lines.moveLinesOf,
    openSavedPhaseLines: lines.openSavedLines,
    hasSessionPhaseLines: lines.hasSessionLines,
    isDrawing: lines.isDrawing,
    draftPoints: lines.draftPoints,
    startDrawing: lines.startDrawing,
    addDraftPoint: lines.addDraftPoint,
    undoDraftPoint: lines.undoDraftPoint,
    cancelDrawing: lines.cancelDrawing,
    /** Makes the draft the phase line `name` in the set `setId` (none: this session's). */
    finishDrawing: (name, setId = null) => finishDrawing((points, id) => createPhaseLine(points, id, name), setId),
  };
};
