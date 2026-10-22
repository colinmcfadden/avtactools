import { useCallback, useState } from "react";

/**
 * How opaque the slope map's raster is drawn, as a whole percentage of the raster's own: the PNG's bands carry their own alpha (145 to 185 of 255, rising
 * with steepness so steep ground stands out), and this multiplies it. Set with the slider under the Slope Map switch, in steps of 5 from 10 (still to be
 * seen: the switch is what hides it) to 100 (the raster as the server drew it), from 55, which lands the middle band near 36% and the range at about 31-40%,
 * light enough to read the imagery through.
 *
 * Remembered in this browser: how this person likes the slope map over the imagery, not part of any plan, so never saved with an LZ/PZ or synced (a field
 * of the diagram's view would be dropped by normalizeView). Android keeps the same on the device (`SlopeOpacity`, the same steps). Storage that cannot be
 * read or written (a private window, blocked site data) leaves it at 55 at load and simply not remembered; the slider still works.
 */
export const SLOPE_OPACITY_STORAGE_KEY = "ezpz.slopeOpacity";

export const SLOPE_OPACITY = Object.freeze({ DEFAULT: 55, MIN: 10, MAX: 100, STEP: 5 });

/** [percent] as the slider keeps it: a number (or a number's text) brought within MIN to MAX, then onto the nearest step; anything else is the default. */
export const slopeOpacityOf = (percent) => {
  const n = typeof percent === "number" ? percent : typeof percent === "string" && percent.trim() !== "" ? Number(percent) : NaN;
  if (!Number.isFinite(n)) return SLOPE_OPACITY.DEFAULT;
  const within = Math.min(SLOPE_OPACITY.MAX, Math.max(SLOPE_OPACITY.MIN, n));
  return Math.round(within / SLOPE_OPACITY.STEP) * SLOPE_OPACITY.STEP;
};

const readStored = () => {
  try {
    const kept = window.localStorage.getItem(SLOPE_OPACITY_STORAGE_KEY);
    return kept === null ? SLOPE_OPACITY.DEFAULT : slopeOpacityOf(kept);
  } catch {
    return SLOPE_OPACITY.DEFAULT;
  }
};

const store = (percent) => {
  try {
    window.localStorage.setItem(SLOPE_OPACITY_STORAGE_KEY, String(percent));
  } catch {
    // Not remembered across reloads; nothing else depends on it.
  }
};

/** The slope map's opacity, a percentage ([SLOPE_OPACITY]), and its setter, which keeps it on the nearest step and remembers it in this browser. */
const useSlopeOpacity = () => {
  const [slopeOpacity, setOpacity] = useState(readStored);
  const setSlopeOpacity = useCallback((percent) => {
    const kept = slopeOpacityOf(percent);
    setOpacity(kept);
    store(kept);
  }, []);
  return { slopeOpacity, setSlopeOpacity };
};

export default useSlopeOpacity;
