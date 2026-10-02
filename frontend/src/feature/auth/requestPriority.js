/**
 * Heavy server work goes first; 3D terrain loading waits for it.
 *
 * Terrain tiles for an area not yet cached are computed on request, and while
 * the 3D window streams them the server runs a couple of dozen at once. An LZ
 * analysis started then took 31.7 s instead of 11.6 s: the tile threads and
 * SAM share one Python interpreter, which runs Python on one thread at a time,
 * so more threads do not help. Instead, new terrain requests wait while
 * anything on this list is in flight, and the analysis gets the server.
 */

// Requests that make the server work hard enough to notice competition.
export const PRIORITY_PATHS = [
  "/analyze-field",      // SAM
  "/terrain-analysis",   // slope from the DEMs
  "/threat-mask",        // viewshed
  "/export-package",
  "/generate-excel",
];

let active = 0;
let waiters = [];

export const isPriorityRequest = (url = "") => {
  const path = String(url).split("?")[0];
  return PRIORITY_PATHS.some((priority) => path.endsWith(priority));
};

/** Mark heavy work as started. Returns the function that marks it finished. */
export const beginPriority = () => {
  active += 1;
  let ended = false;
  return () => {
    if (ended) return;
    ended = true;
    active -= 1;
    if (active === 0) {
      const ready = waiters;
      waiters = [];
      ready.forEach((resolve) => resolve());
    }
  };
};

export const priorityActive = () => active > 0;

/** Resolves at once when nothing heavy is running, otherwise when it ends. */
export const whenIdle = () =>
  active === 0 ? Promise.resolve() : new Promise((resolve) => waiters.push(resolve));
