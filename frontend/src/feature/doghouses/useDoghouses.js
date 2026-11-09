import { useEffect, useMemo, useRef, useState } from "react";
import { flightDataFromDoghouses, flightDoghouses } from "./doghouseFields";

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
 *   { doghouses: Doghouse[], setDoghouses: React.Dispatch<React.SetStateAction<Doghouse[]>>, diagramId }
 * `diagramId` names whose doghouses they are (App.jsx: the diagram on the map).
 */
export const useDoghouses = (targetLocation, setFlightData, options = {}) => {
  const [internalDoghouses, setInternalDoghouses] = useState([]);
  const controlledDoghouses = options?.doghouses;
  const controlledSetDoghouses = options?.setDoghouses;
  const diagramId = options?.diagramId;
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

  // The flight data follows the doghouses when they change, or when another diagram's are on the map; not each time the same doghouses
  // come again in a new list. A mission pack's version put into the diagram (someone else's change, or this person's put back by a pack
  // that takes nothing) makes every list anew, and following the doghouses then put their headings back over the flight data it brought
  // and sent that as this person's own change: a landing heading typed elsewhere was undone, a turn heard on the live stream (its landing
  // heading a moment before its doghouse) came back twice, and a read-only pack's LZ/PZ without the headings went round for ever.
  const followed = useRef(null);
  useEffect(() => {
    if (!setFlightData) return;
    const seen = JSON.stringify(doghouseList);
    if (followed.current && followed.current.diagramId === diagramId && followed.current.seen === seen) return;
    followed.current = { diagramId, seen };

    const { landing, takeoff } = flightDoghouses(doghouseList);
    if (landing || takeoff) {
      setFlightData((previous) => flightDataFromDoghouses(doghouseList, previous));
    }
  }, [doghouseList, setFlightData, diagramId]);

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
