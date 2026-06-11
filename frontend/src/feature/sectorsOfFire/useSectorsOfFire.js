import { useState, useEffect } from "react";

export const useSectorsOfFire = (targetLocation) => {
    const [sectorsOfFire, setSectors] = useState([]); 

    const addSectorOfFire = () => {
    if (!targetLocation) return;
    const centerLat = targetLocation[0];
    const centerLon = targetLocation[1];
    const offset = 0.001;
    const newSector = {
      id: `sec-${Date.now()}`,
      points: [
        { lat: centerLat + offset, lng: centerLon },
        { lat: centerLat - offset, lng: centerLon + offset },
        { lat: centerLat - offset, lng: centerLon - offset },
      ],
    };
    setSectors((prev) => [...prev, newSector]);
  };

  const updateSectorOfFirePoint = (id, pointIndex, newLat, newLng) => {
    setSectors((prev) =>
      prev.map((sec) => {
        if (sec.id !== id) return sec;
        const newPoints = [...sec.points];
        newPoints[pointIndex] = { lat: newLat, lng: newLng };
        return { ...sec, points: newPoints };
      }),
    );
  };

  const moveSectorOfFire = (id, dLat, dLon) => {
    setSectors((prev) =>
      prev.map((sec) => {
        if (sec.id !== id) return sec;
        const newPoints = sec.points.map((p) => ({
          lat: p.lat + dLat,
          lng: p.lng + dLon,
        }));
        return { ...sec, points: newPoints };
      }),
    );
  };

  const deleteSectorOfFire = (id) => {
    setSectors((prev) => prev.filter((s) => s.id !== id));
  };

  return { sectorsOfFire, addSectorOfFire, updateSectorOfFirePoint, moveSectorOfFire, deleteSectorOfFire };
}