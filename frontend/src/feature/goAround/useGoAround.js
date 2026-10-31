import { useState } from "react";

/**
 * A new go-around arrow 0.001 degrees (about 110 m) north ("N") or south of the target, pointing the way it was named. Nothing for a
 * target that is not a position. Pure, and exported so the native apps can be held to it (contracts/fixtures/planning/graphics.json).
 */
export const createGoAround = (target, direction, id) => {
  const centerLat = Number(target[0]);
  const centerLon = Number(target[1]);
  if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return null;

  const offset = 0.001;
  return {
    id,
    lat: direction === "N" ? centerLat + offset : centerLat - offset,
    lon: centerLon,
    direction,
    rotation: 0,
  };
};

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

    const newGoAround = createGoAround(targetLocation, direction, `ga-${Date.now()}`);
    if (!newGoAround) return;

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
