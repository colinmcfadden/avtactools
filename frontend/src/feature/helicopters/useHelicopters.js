import { useEffect, useMemo, useState } from "react";
import { getDistanceFeet } from "../../utils/Helpers";
import {
  UH60_MIN_CENTER_SPACING_FEET,
  UH60_ROTOR_TIP_CLEARANCE_FEET,
} from "../../utils/helicopterCapacity";

/**
 * Optional controlled-state shape:
 *   { helicopters: Helicopter[], setHelicopters: React.Dispatch<React.SetStateAction<Helicopter[]>> }
 */
export const useHelicopters = (targetLocation, options = {}) => {
  const [internalHelicopters, setInternalHelicopters] = useState([]);
  const [proximityAlerts, setProximityAlerts] = useState([]);
  const controlledHelicopters = options?.helicopters;
  const controlledSetHelicopters = options?.setHelicopters;
  const isControlled =
    controlledHelicopters !== undefined &&
    typeof controlledSetHelicopters === "function";
  const helicopters = isControlled ? controlledHelicopters : internalHelicopters;
  const setHelicopters = isControlled
    ? controlledSetHelicopters
    : setInternalHelicopters;
  const helicopterList = useMemo(
    () => (Array.isArray(helicopters) ? helicopters : []),
    [helicopters],
  );

  const addHelo = () => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }

    let finalLat = targetLocation[0];
    let finalLon = targetLocation[1];
    let isClear = false;
    let attempts = 0;
    const offsetStep = 0.0001;

    while (!isClear && attempts < 50) {
      isClear = true;
      for (const helicopter of helicopterList) {
        const distance = getDistanceFeet(
          finalLat,
          finalLon,
          helicopter.lat,
          helicopter.lon,
        );
        if (distance < UH60_MIN_CENTER_SPACING_FEET) {
          isClear = false;
          finalLon += offsetStep;
          break;
        }
      }
      attempts += 1;
    }

    const newHelo = {
      id: Date.now(),
      lat: finalLat,
      lon: finalLon,
      rotation: 0,
      type: "helo",
    };

    setHelicopters((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newHelo,
    ]);
  };

  const updateHelicopter = (id, newProps) => {
    setHelicopters((previous) =>
      (Array.isArray(previous) ? previous : []).map((helicopter) =>
        helicopter.id === id ? { ...helicopter, ...newProps } : helicopter,
      ),
    );
  };

  const deleteHelicopter = (id) => {
    setHelicopters((previous) =>
      (Array.isArray(previous) ? previous : []).filter(
        (helicopter) => helicopter.id !== id,
      ),
    );
  };

  useEffect(() => {
    const alerts = [];

    for (let index = 0; index < helicopterList.length; index += 1) {
      for (
        let comparisonIndex = index + 1;
        comparisonIndex < helicopterList.length;
        comparisonIndex += 1
      ) {
        const firstHelicopter = helicopterList[index];
        const secondHelicopter = helicopterList[comparisonIndex];
        const centerDistance = getDistanceFeet(
          firstHelicopter.lat,
          firstHelicopter.lon,
          secondHelicopter.lat,
          secondHelicopter.lon,
        );
        const edgeToEdgeDistance =
          centerDistance -
          (UH60_MIN_CENTER_SPACING_FEET - UH60_ROTOR_TIP_CLEARANCE_FEET);

        if (edgeToEdgeDistance < UH60_ROTOR_TIP_CLEARANCE_FEET) {
          const displayDistance = Math.max(0, Math.round(edgeToEdgeDistance));
          alerts.push({
            id: `${firstHelicopter.id}-${secondHelicopter.id}`,
            message: `Separation Alert: Rotor edges are only ${displayDistance} ft apart (Min: ${Math.round(UH60_ROTOR_TIP_CLEARANCE_FEET)} ft / 60 m).`,
          });
        }
      }
    }

    setProximityAlerts((previous) => {
      const unchanged =
        previous.length === alerts.length &&
        previous.every(
          (alert, index) =>
            alert.id === alerts[index].id &&
            alert.message === alerts[index].message,
        );

      return unchanged ? previous : alerts;
    });
  }, [helicopterList]);

  return {
    helicopters: helicopterList,
    setHelicopters,
    addHelo,
    updateHelicopter,
    deleteHelicopter,
    proximityAlerts,
  };
};
