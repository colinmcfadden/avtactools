import { useState } from "react";

/**
 * Optional controlled-state shape:
 *   { goAround: GoAround[], setGoAround: React.Dispatch<React.SetStateAction<GoAround[]>> }
 */
export const useGoAround = (targetLocation, options = {}) => {
  const [internalGoAround, setInternalGoAround] = useState([]);
  const controlledGoAround = options?.goAround;
  const controlledSetGoAround = options?.setGoAround;
  const isControlled =
    controlledGoAround !== undefined &&
    typeof controlledSetGoAround === "function";
  const goAround = isControlled ? controlledGoAround : internalGoAround;
  const setGoAround = isControlled ? controlledSetGoAround : setInternalGoAround;
  const goAroundList = Array.isArray(goAround) ? goAround : [];

  const addGoAround = (direction) => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }

    const centerLat = Number(targetLocation[0]);
    const centerLon = Number(targetLocation[1]);
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return;

    const offset = 0.001;
    const newGoAround = {
      id: `ga-${Date.now()}`,
      lat: direction === "N" ? centerLat + offset : centerLat - offset,
      lon: centerLon,
      direction,
      rotation: 0,
    };

    setGoAround((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newGoAround,
    ]);
  };

  const updateGoAround = (id, newProps) => {
    setGoAround((previous) =>
      (Array.isArray(previous) ? previous : []).map((goAroundItem) =>
        goAroundItem.id === id ? { ...goAroundItem, ...newProps } : goAroundItem,
      ),
    );
  };

  const deleteGoAround = (id) => {
    setGoAround((previous) =>
      (Array.isArray(previous) ? previous : []).filter(
        (goAroundItem) => goAroundItem.id !== id,
      ),
    );
  };

  return {
    goAround: goAroundList,
    setGoAround,
    addGoAround,
    updateGoAround,
    deleteGoAround,
  };
};
