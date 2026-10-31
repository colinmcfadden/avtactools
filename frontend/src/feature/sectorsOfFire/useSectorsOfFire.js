import { useState } from "react";

/**
 * A new sector of fire: a triangle about 110 m (0.001 degrees) across, pointing north from the target. Nothing for a target that is
 * not a position. Pure, and exported so the native apps can be held to it (contracts/fixtures/planning/graphics.json).
 */
export const createSectorOfFire = (target, id) => {
  const centerLat = Number(target[0]);
  const centerLon = Number(target[1]);
  if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return null;

  const offset = 0.001;
  return {
    id,
    points: [
      { lat: centerLat + offset, lng: centerLon },
      { lat: centerLat - offset, lng: centerLon + offset },
      { lat: centerLat - offset, lng: centerLon - offset },
    ],
  };
};

/**
 * Optional controlled-state shape:
 *   { sectorsOfFire: Sector[], setSectors: React.Dispatch<React.SetStateAction<Sector[]>> }
 */
export const useSectorsOfFire = (targetLocation, options = {}) => {
  const [internalSectors, setInternalSectors] = useState([]);
  const controlledSectors = options?.sectorsOfFire;
  const controlledSetSectors = options?.setSectors;
  const isControlled =
    controlledSectors !== undefined && typeof controlledSetSectors === "function";
  const sectorsOfFire = isControlled ? controlledSectors : internalSectors;
  const setSectors = isControlled ? controlledSetSectors : setInternalSectors;
  const sectorList = Array.isArray(sectorsOfFire) ? sectorsOfFire : [];

  const addSectorOfFire = () => {
    if (!targetLocation) return;

    const newSector = createSectorOfFire(targetLocation, `sec-${Date.now()}`);
    if (!newSector) return;

    setSectors((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newSector,
    ]);
  };

  const updateSectorOfFirePoint = (id, pointIndex, newLat, newLng) => {
    setSectors((previous) =>
      (Array.isArray(previous) ? previous : []).map((sector) => {
        if (sector.id !== id) return sector;
        const points = [...sector.points];
        points[pointIndex] = { lat: newLat, lng: newLng };
        return { ...sector, points };
      }),
    );
  };

  const moveSectorOfFire = (id, dLat, dLon) => {
    setSectors((previous) =>
      (Array.isArray(previous) ? previous : []).map((sector) => {
        if (sector.id !== id) return sector;
        const points = sector.points.map((point) => ({
          lat: point.lat + dLat,
          lng: point.lng + dLon,
        }));
        return { ...sector, points };
      }),
    );
  };

  const deleteSectorOfFire = (id) => {
    setSectors((previous) =>
      (Array.isArray(previous) ? previous : []).filter(
        (sector) => sector.id !== id,
      ),
    );
  };

  return {
    sectorsOfFire: sectorList,
    setSectors,
    addSectorOfFire,
    updateSectorOfFirePoint,
    moveSectorOfFire,
    deleteSectorOfFire,
  };
};
