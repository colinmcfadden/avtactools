import { useState } from "react";

/**
 * A new unit from what the unit builder (or a preset) gives: it sits [offset] degrees north and east of [center], so it does not land on
 * the target itself. Pass the offset and the id in, so the result does not depend on the clock or on chance.
 */
export const createUnit = (unitConfig, center, offset, id) => ({
  id,
  type: unitConfig.id,
  path: unitConfig.path,
  sidc: unitConfig.sidc,
  uniqueDesignation: unitConfig.uniqueDesignation,
  higherFormation: unitConfig.higherFormation,
  lat: center[0] + offset,
  lon: center[1] + offset,
});

/**
 * Optional controlled-state shape:
 *   { units: Unit[], setUnits: React.Dispatch<React.SetStateAction<Unit[]>> }
 */
export const useUnit = (targetLocation, options = {}) => {
  const [internalUnits, setInternalUnits] = useState([]);
  const controlledUnits = options?.units;
  const controlledSetUnits = options?.setUnits;
  const isControlled =
    controlledUnits !== undefined && typeof controlledSetUnits === "function";
  const units = isControlled ? controlledUnits : internalUnits;
  const setUnits = isControlled ? controlledSetUnits : setInternalUnits;
  const unitList = Array.isArray(units) ? units : [];

  const addUnit = (unitConfig) => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }

    const centerLat = Number(targetLocation[0]);
    const centerLon = Number(targetLocation[1]);
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return;

    const newUnit = createUnit(unitConfig, [centerLat, centerLon], Math.random() * 0.001, `unit-${Date.now()}`);

    setUnits((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newUnit,
    ]);
  };

  const updateUnitPosition = (id, newLat, newLon) => {
    setUnits((previous) =>
      (Array.isArray(previous) ? previous : []).map((unit) =>
        unit.id === id ? { ...unit, lat: newLat, lon: newLon } : unit,
      ),
    );
  };

  const updateUnit = (id, patch) => {
    setUnits((previous) =>
      (Array.isArray(previous) ? previous : []).map((unit) =>
        unit.id === id ? { ...unit, ...patch } : unit,
      ),
    );
  };

  const deleteUnit = (id) => {
    setUnits((previous) =>
      (Array.isArray(previous) ? previous : []).filter((unit) => unit.id !== id),
    );
  };

  return {
    units: unitList,
    setUnits,
    addUnit,
    updateUnit,
    updateUnitPosition,
    deleteUnit,
  };
};
