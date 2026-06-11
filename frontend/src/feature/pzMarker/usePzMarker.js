import { useState } from "react";

export const usePzMarker = (targetLocation) => {
    const [pzMarker, setPzMarkers] = useState([]);

    const addPZMarker = () => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }
    const startLat = parseFloat(targetLocation[0]);
    const startLon = parseFloat(targetLocation[1]);
    const newPZ = {
      id: `pz-${Date.now()}`,
      lat: startLat,
      lon: startLon,
      tipLat: startLat,
      tipLon: startLon - 0.002,
    };
    setPzMarkers((prev) => [...prev, newPZ]);
  };

  const updatePZMarker = (id, newProps) => {
    setPzMarkers((prev) =>
      prev.map((pz) => (pz.id === id ? { ...pz, ...newProps } : pz)),
    );
  };

  const deletePZMarker = (id) => {
    setPzMarkers((prev) => prev.filter((pz) => pz.id !== id));
  };

    return { pzMarker, addPZMarker, updatePZMarker, deletePZMarker };
}