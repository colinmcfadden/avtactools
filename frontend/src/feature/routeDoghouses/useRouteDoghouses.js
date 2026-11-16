import { useCallback, useState } from "react";

/**
 * Whether the map draws a doghouse on each leg of the routes shown (routeDoghouses.js). On until someone turns them off,
 * then remembered in this browser: how this person likes the map, not part of a plan, so neither saved nor synced.
 * Storage that cannot be read or written leaves them on and the choice simply not remembered.
 */
export const ROUTE_DOGHOUSES_STORAGE_KEY = "ezpz.routeDoghouses";

const readStored = () => {
  try {
    return window.localStorage.getItem(ROUTE_DOGHOUSES_STORAGE_KEY) !== "off";
  } catch {
    return true;
  }
};

const store = (on) => {
  try {
    if (on) window.localStorage.removeItem(ROUTE_DOGHOUSES_STORAGE_KEY);
    else window.localStorage.setItem(ROUTE_DOGHOUSES_STORAGE_KEY, "off");
  } catch {
    // Not remembered across reloads; nothing else depends on it.
  }
};

const useRouteDoghouses = () => {
  const [showRouteDoghouses, setShown] = useState(readStored);
  const setShowRouteDoghouses = useCallback((on) => {
    setShown(Boolean(on));
    store(Boolean(on));
  }, []);
  return { showRouteDoghouses, setShowRouteDoghouses };
};

export default useRouteDoghouses;
