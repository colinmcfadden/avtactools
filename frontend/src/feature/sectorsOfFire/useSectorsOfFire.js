import { useState } from "react";

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

    const centerLat = Number(targetLocation[0]);
    const centerLon = Number(targetLocation[1]);
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return;

    const offset = 0.001;
    const newSector = {
      id: `sec-${Date.now()}`,
      points: [
        { lat: centerLat + offset, lng: centerLon },
        { lat: centerLat - offset, lng: centerLon + offset },
        { lat: centerLat - offset, lng: centerLon - offset },
      ],
    };

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
