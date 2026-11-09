import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { apiRootOf, isCoverage, levelsFiner, liveBuildOf } from "./vfrCharts";

const API_BASE_URL = import.meta.env.VITE_API_URL || "";

export const LOOK_EVERY_MS = 5 * 60 * 1000;
export const AFTER_A_FAILED_TILE_MS = 60 * 1000;
// A look at the very moment a build runs out would find the server's answer of the minute before (it keeps it for 60 s).
export const PAST_EXPIRY_MS = 61 * 1000;
const WAIT_MS = 8000;

const fetchJson = async (url, init) => {
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), WAIT_MS);
  try {
    const response = await fetch(url, { ...init, signal: stop.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
};

/** Nothing known of any charts and nobody asking: ArcGIS draws the VFR map. For a screen given no charts. */
export const NO_CHARTS = { known: true, live: null, coverage: null, tileFailed: () => {} };

const DENSE_SCREEN = `(min-resolution: ${Math.SQRT2}dppx)`;

// Read from the ratio itself, so a browser that cannot answer the query (Safari before 16) still gets the right tiles when the map opens;
// the query only says when to read it again: a window moved to another screen, or the browser's zoom changed.
const onScreenChange = (changed) => {
  const query = window.matchMedia?.(DENSE_SCREEN);
  query?.addEventListener?.("change", changed);
  return () => query?.removeEventListener?.("change", changed);
};
const screenLevelsFiner = () => levelsFiner(window.devicePixelRatio || 1);

/**
 * Which build of the FAA's charts draws the VFR map (docs/VFR_CHARTS.md §4.5, §8): `live`, or null for ArcGIS; `known` is false until
 * the first answer, so a page opening on the VFR map asks ArcGIS for nothing it will not draw.
 *
 * `/api/config` is asked once when the app opens and, while the VFR map is `shown`, every 5 minutes, just after the live build runs
 * out, and after a chart tile fails (`tileFailed`, at most once a minute): a tile that fails is either a build gone or no network, and
 * Leaflet cannot say which. Past the browser's cache, or the config's own max-age would hand back the old build. A new build changes
 * the layer; the same build changes nothing; a look that gets no answer leaves things as they are. `finer` is how many levels finer
 * this screen draws them from (`levelsFiner`).
 */
export default function useVfrCharts(shown) {
  const [known, setKnown] = useState(false);
  const [live, setLive] = useState(null);
  const [coverage, setCoverage] = useState(null);
  const finer = useSyncExternalStore(onScreenChange, screenLevelsFiner);

  const alive = useRef(true);
  const asking = useRef(false);
  const lookedAt = useRef(0);
  const shownNow = useRef(shown);
  const held = useRef(null);
  shownNow.current = shown;

  const look = useCallback(async () => {
    if (asking.current) return;
    asking.current = true;
    lookedAt.current = Date.now();
    try {
      const config = await fetchJson(`${API_BASE_URL}/config`, { cache: "no-store" });
      if (!alive.current) return;
      const next = liveBuildOf(config, apiRootOf(API_BASE_URL));
      setLive((before) => (before && next && before.build === next.build ? before : next));
      setKnown(true);
      if (next && shownNow.current && held.current !== next.build) {
        const data = await fetchJson(next.coverage);
        if (alive.current && isCoverage(data)) {
          held.current = next.build;
          setCoverage({ build: next.build, data });
        }
      }
    } catch {
      // No answer: what was known stands, and the map is not left waiting for one.
      if (alive.current) setKnown(true);
    } finally {
      asking.current = false;
    }
  }, []);

  useEffect(() => {
    alive.current = true;
    look();
    return () => {
      alive.current = false;
    };
  }, [look]);

  const expires = live ? Date.parse(live.expires) + PAST_EXPIRY_MS : null;

  useEffect(() => {
    if (!shown) return undefined;
    // Back on the VFR map after a while on another, or without the coverage a look before it had no reason to fetch.
    if (Date.now() - lookedAt.current >= LOOK_EVERY_MS || (live && held.current !== live.build)) look();

    let timer;
    const plan = () => {
      const toExpiry = expires == null ? Infinity : expires - Date.now();
      timer = setTimeout(() => {
        if (document.visibilityState !== "hidden") look();
        plan();
      }, Math.min(LOOK_EVERY_MS, toExpiry > 0 ? toExpiry : Infinity));
    };
    plan();

    const onVisible = () => {
      if (document.visibilityState === "visible" && Date.now() - lookedAt.current >= LOOK_EVERY_MS) look();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [shown, live, expires, look]);

  const tileFailed = useCallback(() => {
    if (Date.now() - lookedAt.current >= AFTER_A_FAILED_TILE_MS) look();
  }, [look]);

  return useMemo(
    () => ({ known, live, coverage: live && coverage?.build === live.build ? coverage.data : null, tileFailed, finer }),
    [known, live, coverage, tileFailed, finer],
  );
}
