import { useState } from "react";

export const useUnit = (targetLocation) => {
  const [units, setUnits] = useState([]);
    const addUnit = (unitConfig) => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }
    const offset = Math.random() * 0.001;
    const newUnit = {
      id: `unit-${Date.now()}`,
      type: unitConfig.id,
      path: unitConfig.path,
      lat: targetLocation[0] + offset,
      lon: targetLocation[1] + offset,
    };
    setUnits((prev) => [...prev, newUnit]);
  };

  const updateUnitPosition = (id, newLat, newLon) => {
    setUnits((prev) =>
      prev.map((u) => (u.id === id ? { ...u, lat: newLat, lon: newLon } : u)),
    );
  };

  const deleteUnit = (id) => {
    setUnits((prev) => prev.filter((u) => u.id !== id));
  };

  return { units, addUnit, updateUnitPosition, deleteUnit };
}