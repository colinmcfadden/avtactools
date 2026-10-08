import { useCallback, useState } from "react";

/**
 * Whether the map shows the MGRS grid. Off until someone turns it on, then remembered in this
 * browser: it is how this person likes to look at the map, not part of any plan, so it is not
 * saved with an LZ or synced. Storage that cannot be read or written (a private window, blocked
 * site data) leaves the grid off at load and simply not remembered; the switch still works.
 */
export const MGRS_GRID_STORAGE_KEY = "ezpz.mgrsGrid";

const readStored = () => {
  try {
    return window.localStorage.getItem(MGRS_GRID_STORAGE_KEY) === "on";
  } catch {
    return false;
  }
};

const store = (on) => {
  try {
    if (on) window.localStorage.setItem(MGRS_GRID_STORAGE_KEY, "on");
    else window.localStorage.removeItem(MGRS_GRID_STORAGE_KEY);
  } catch {
    // Not remembered across reloads; nothing else depends on it.
  }
};

const useMgrsGrid = () => {
  const [showMgrsGrid, setShown] = useState(readStored);
  const setShowMgrsGrid = useCallback((on) => {
    setShown(Boolean(on));
    store(Boolean(on));
  }, []);
  return { showMgrsGrid, setShowMgrsGrid };
};

export default useMgrsGrid;
