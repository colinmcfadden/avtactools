import { useEffect, useMemo, useState } from "react";

const toCoordinates = (target) => {
  const lat = Array.isArray(target) ? target[0] : target?.lat;
  const lon = Array.isArray(target)
    ? target[1]
    : target?.lng ?? target?.lon;
  const numericLat = Number(lat);
  const numericLon = Number(lon);

  if (!Number.isFinite(numericLat) || !Number.isFinite(numericLon)) {
    return null;
  }

  return { lat: numericLat, lon: numericLon };
};

/**
 * Creates the standard SP/RP doghouses for a diagram without changing any
 * hook state. Pass the diagram id as `namespace` to keep ids unique across
 * diagrams; the coordinate-based fallback remains deterministic for callers
 * that do not have a diagram id yet.
 */
export const createDefaultDoghouses = (target, namespace) => {
  const center = toCoordinates(target);
  if (!center) return [];

  const fallbackNamespace = `doghouse-${center.lat.toFixed(6)}-${center.lon.toFixed(6)}`;
  const idNamespace = String(namespace || fallbackNamespace).replace(/[^a-zA-Z0-9_-]/g, "_");
  const offset = 0.003;

  return [
    {
      id: `${idNamespace}-sp1`,
      role: "takeoff",
      lat: center.lat,
      lon: center.lon - offset,
      id_val: "[SP1]",
      heading: "000°",
      time: "01+57",
      dist: "3.13km",
      airspeed: "60 KIAS",
    },
    {
      id: `${idNamespace}-rp1`,
      role: "landing",
      lat: center.lat - offset / 2,
      lon: center.lon - offset,
      id_val: "[RP1]",
      heading: "000°",
      time: "02+10",
      dist: "5.20km",
      airspeed: "40 KIAS",
    },
  ];
};

/**
 * Optional controlled-state shape:
 *   { doghouses: Doghouse[], setDoghouses: React.Dispatch<React.SetStateAction<Doghouse[]>> }
 */
export const useDoghouses = (targetLocation, setFlightData, options = {}) => {
  const [internalDoghouses, setInternalDoghouses] = useState([]);
  const controlledDoghouses = options?.doghouses;
  const controlledSetDoghouses = options?.setDoghouses;
  const isControlled =
    controlledDoghouses !== undefined &&
    typeof controlledSetDoghouses === "function";
  const doghouses = isControlled ? controlledDoghouses : internalDoghouses;
  const setDoghouses = isControlled ? controlledSetDoghouses : setInternalDoghouses;
  const doghouseList = useMemo(
    () => (Array.isArray(doghouses) ? doghouses : []),
    [doghouses],
  );

  const updateDoghouse = (id, changes) => {
    setDoghouses((previous) => {
      const currentDoghouses = Array.isArray(previous) ? previous : [];
      return currentDoghouses.map((doghouse) =>
        doghouse.id === id ? { ...doghouse, ...changes } : doghouse,
      );
    });
  };

  useEffect(() => {
    if (!setFlightData) return;

    // `role` is used by new default doghouses. The older id/id_val fallbacks
    // keep existing saved diagrams compatible.
    const landingDoghouse = doghouseList.find(
      (doghouse) =>
        doghouse.role === "landing" ||
        doghouse.id === "dh2" ||
        doghouse.id_val === "[RP1]",
    );
    const takeoffDoghouse = doghouseList.find(
      (doghouse) =>
        doghouse.role === "takeoff" ||
        doghouse.id === "dh1" ||
        doghouse.id_val === "[SP1]",
    );

    if (landingDoghouse || takeoffDoghouse) {
      setFlightData((previous) => ({
        ...previous,
        landing_hdg: landingDoghouse
          ? landingDoghouse.heading
          : previous.landing_hdg,
        takeoff_hdg: takeoffDoghouse
          ? takeoffDoghouse.heading
          : previous.takeoff_hdg,
      }));
    }
  }, [doghouseList, setFlightData]);

  useEffect(() => {
    const handleEdit = (event) => {
      const { id, field } = event.detail;
      const newValue = window.prompt(`Enter new value for ${field}:`);
      if (newValue !== null) {
        setDoghouses((previous) => {
          const currentDoghouses = Array.isArray(previous) ? previous : [];
          return currentDoghouses.map((doghouse) =>
            doghouse.id === id ? { ...doghouse, [field]: newValue } : doghouse,
          );
        });
      }
    };

    window.addEventListener("edit-dh", handleEdit);
    return () => window.removeEventListener("edit-dh", handleEdit);
  }, [setDoghouses]);

  // Doghouses are deliberately not generated from targetLocation. The active
  // diagram must explicitly call createDefaultDoghouses after analysis.
  void targetLocation;

  return {
    doghouses: doghouseList,
    setDoghouses,
    updateDoghouse,
  };
};
