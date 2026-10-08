import { useCallback, useEffect, useState } from "react";

const has = (areas, id) => Object.prototype.hasOwnProperty.call(areas, id);

/*
 * The capture area of the active LZ/PZ's card. It belongs to the LZ/PZ (saved with it, and shared in a
 * Mission Pack), except where this person cannot change the pack (it is finished, or they only view
 * it): the pack would put a moved area straight back, yet exporting a card changes nothing in the
 * pack. There the area is this person's own, one per LZ/PZ, until they can edit again.
 *
 * `own`: keep it here rather than in the LZ/PZ. `shared`/`setShared`: the LZ/PZ's own area and its
 * setter. Returns { exportBox, setExportBox } for useExport.
 */
export const useCaptureArea = ({ own, diagramId, shared, setShared }) => {
  const [areas, setAreas] = useState({});

  // Able to edit again: the LZ/PZ's own area is the one that counts.
  useEffect(() => {
    if (!own) setAreas({});
  }, [own]);

  const exportBox = own && diagramId != null && has(areas, diagramId) ? areas[diagramId] : shared;

  const setExportBox = useCallback(
    (next) => {
      if (!own) {
        setShared(next);
        return;
      }
      if (diagramId == null) return;
      setAreas((prev) => {
        const current = has(prev, diagramId) ? prev[diagramId] : shared;
        return { ...prev, [diagramId]: typeof next === "function" ? next(current) : next };
      });
    },
    [own, diagramId, shared, setShared],
  );

  return { exportBox, setExportBox };
};

export default useCaptureArea;
