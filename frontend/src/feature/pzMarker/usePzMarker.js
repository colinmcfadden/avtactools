import { useState } from "react";

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

    const startLat = parseFloat(targetLocation[0]);
    const startLon = parseFloat(targetLocation[1]);
    const newPzMarker = {
      id: `pz-${Date.now()}`,
      lat: startLat,
      lon: startLon,
      tipLat: startLat,
      tipLon: startLon - 0.002,
    };

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
