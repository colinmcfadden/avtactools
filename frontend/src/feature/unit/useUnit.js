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
      path: unitConfig.path, // legacy image presets
      // MIL-STD-2525 symbol (from the unit builder), with optional labels.
      sidc: unitConfig.sidc,
      uniqueDesignation: unitConfig.uniqueDesignation,
      higherFormation: unitConfig.higherFormation,
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

  /** Patches a unit's symbol/labels in place (from the unit builder in edit mode). */
  const updateUnit = (id, patch) => {
    setUnits((prev) => prev.map((u) => (u.id === id ? { ...u, ...patch } : u)));
  };

  const deleteUnit = (id) => {
    setUnits((prev) => prev.filter((u) => u.id !== id));
  };

  return { units, setUnits, addUnit, updateUnit, updateUnitPosition, deleteUnit };
}