/**
 * The map opens where the person is, when their browser lets it.
 *
 * Asked once, when the map first loads in a page, and only if nothing has claimed the view yet (an LZ/PZ opened at launch, a
 * `#grid=` link, a brief presented). The answer moves the map only if the view is still the one it was made with: anything that
 * moved it meanwhile (the person panning or zooming, a search, a pack's LZ/PZ, a grid link) is a view someone chose, and the
 * position is then dropped. A refusal, no geolocation, a timeout or an error leave the default view exactly as it was.
 *
 * The position goes to Leaflet and nowhere else: it is not kept, sent to the server, put in a URL or saved with a plan. What is
 * remembered, per browser, is only that the person refused (`ezpz.startAtLocation`), so they are not asked again on every load;
 * a browser that later reports the permission as granted (changed in its site settings) is used whatever was remembered.
 */
export const START_LOCATION_STORAGE_KEY = "ezpz.startAtLocation";
const REFUSED = "refused";

// A town and its surroundings: closer than the default view, wide enough for a coarse (Wi-Fi or IP) position.
export const LOCATED_ZOOM = 13;

export const GEOLOCATION_OPTIONS = Object.freeze({
  enableHighAccuracy: false, // the map is centred, not a point placed: a quick, coarse fix is enough
  timeout: 10000, // after the person allows it; how long the prompt stays open is theirs
  maximumAge: 10 * 60 * 1000,
});

const PERMISSION_DENIED = 1;

// One ask per page load, however often the map is made again (React's development double mount included): `request` is the
// answer on its way, shared by whichever map is there when it comes; `settled` once a map has used it or let it go.
let request = null;
let settled = false;
// Whether the map there now still wants an answer, read before the browser is asked.
let wanted = () => false;

/** For tests: lets a new "page" ask again. */
export const resetStartAtLocation = () => {
  request = null;
  settled = false;
  wanted = () => false;
};

const readRefused = (storage) => {
  try {
    return storage?.getItem(START_LOCATION_STORAGE_KEY) === REFUSED;
  } catch {
    return false;
  }
};

const writeRefused = (storage, refused) => {
  try {
    if (refused) storage?.setItem(START_LOCATION_STORAGE_KEY, REFUSED);
    else storage?.removeItem(START_LOCATION_STORAGE_KEY);
  } catch {
    // Not remembered; the browser keeps its own answer anyway.
  }
};

// "granted", "denied", "prompt", or null where the browser cannot say.
const permissionState = async (permissions) => {
  try {
    if (!permissions?.query) return null;
    const status = await permissions.query({ name: "geolocation" });
    return status?.state ?? null;
  } catch {
    return null;
  }
};

const defaultEnv = () => {
  const nav = typeof navigator === "undefined" ? undefined : navigator;
  let storage;
  try {
    storage = typeof window === "undefined" ? undefined : window.localStorage;
  } catch {
    storage = undefined;
  }
  return { geolocation: nav?.geolocation, permissions: nav?.permissions, storage };
};

const usablePosition = (position) => {
  const lat = position?.coords?.latitude;
  const lon = position?.coords?.longitude;
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return [lat, lon];
};

// The browser asked, once: a [lat, lon], or null for no answer to use (refused, denied, timed out, unavailable, not wanted).
const ask = async ({ geolocation, permissions, storage }) => {
  const state = await permissionState(permissions);
  if (!wanted() || state === "denied") return null;
  if (state !== "granted" && readRefused(storage)) return null;
  return new Promise((resolve) => {
    const onPosition = (position) => {
      writeRefused(storage, false);
      resolve(usablePosition(position));
    };
    const onError = (error) => {
      if (error?.code === PERMISSION_DENIED) writeRefused(storage, true);
      resolve(null);
    };
    try {
      geolocation.getCurrentPosition(onPosition, onError, GEOLOCATION_OPTIONS);
    } catch {
      resolve(null);
    }
  });
};

/**
 * Starts (or joins) the one ask for this page on a Leaflet map. `claimed()` says whether the app has set a view of its own
 * (read as it starts, before the browser is asked, and when the answer comes). Returns a function that lets go: an answer
 * after it does nothing to this map, and is kept for the map made next, if it comes in time.
 */
export const attachStartAtLocation = (map, { claimed = () => false, env = defaultEnv(), zoom = LOCATED_ZOOM } = {}) => {
  if (settled || !map) return () => {};
  if (claimed() || !env?.geolocation?.getCurrentPosition) {
    settled = true;
    return () => {};
  }

  let live = true;
  let viewChosen = false;
  // Leaflet fires movestart for every change of view (a drag, a wheel, setView, flyTo, fitBounds) and zoomstart for every
  // zoom; a resize of the map's box fires neither, so a sidebar folding is not taken for a view chosen.
  const chosen = () => {
    viewChosen = true;
  };
  map.on("movestart zoomstart", chosen);
  const detach = () => {
    if (!live) return;
    live = false;
    map.off("movestart zoomstart", chosen);
  };
  const stillOurs = () => live && !viewChosen && !claimed();

  wanted = stillOurs;
  if (!request) request = ask(env);
  request.then((at) => {
    if (!live) return; // a map made after this one takes the answer
    settled = true;
    const go = at && stillOurs();
    detach(); // our own move below is not a view someone chose
    if (go) map.setView(at, zoom, { animate: false });
  });

  return detach;
};
