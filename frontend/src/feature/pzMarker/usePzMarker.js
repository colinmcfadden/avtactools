import { useState } from "react";

/**
 * A new PZ marker at the target, with its tip 0.002 degrees (about 160 m) west of it: the marker is a line from the centre to the tip,
 * and its length and direction are what the crew drags. Pure, and exported so the native apps can be held to it
 * (contracts/fixtures/planning/graphics.json).
 */
export const createPzMarker = (target, id) => {
  const startLat = parseFloat(target[0]);
  const startLon = parseFloat(target[1]);
  return {
    id,
    lat: startLat,
    lon: startLon,
    tipLat: startLat,
    tipLon: startLon - 0.002,
  };
};

/**
 * Optional controlled-state shape:
 *   { pzMarker: PzMarker[], setPzMarkers: React.Dispatch<React.SetStateAction<PzMarker[]>> }
 * `pzMarkers` and `setPzMarker` are also accepted as aliases.
 */
export const usePzMarker = (targetLocation, options = {}) => {
  const [internalPzMarkers, setInternalPzMarkers] = useState([]);
  const controlledPzMarkers = options?.pzMarker ?? options?.pzMarkers;
  const controlledSetPzMarkers = options?.setPzMarkers ?? options?.setPzMarker;
  const isControlled =
    controlledPzMarkers !== undefined &&
    typeof controlledSetPzMarkers === "function";
  const pzMarker = isControlled ? controlledPzMarkers : internalPzMarkers;
  const setPzMarkers = isControlled ? controlledSetPzMarkers : setInternalPzMarkers;
  const pzMarkerList = Array.isArray(pzMarker) ? pzMarker : [];

  const addPZMarker = () => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }

    const newPzMarker = createPzMarker(targetLocation, `pz-${Date.now()}`);

    setPzMarkers((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newPzMarker,
    ]);
  };

  const updatePZMarker = (id, newProps) => {
    setPzMarkers((previous) =>
      (Array.isArray(previous) ? previous : []).map((marker) =>
        marker.id === id ? { ...marker, ...newProps } : marker,
      ),
    );
  };

  const deletePZMarker = (id) => {
    setPzMarkers((previous) =>
      (Array.isArray(previous) ? previous : []).filter(
        (marker) => marker.id !== id,
      ),
    );
  };

  return {
    pzMarker: pzMarkerList,
    setPzMarkers,
    addPZMarker,
    updatePZMarker,
    deletePZMarker,
  };
};
