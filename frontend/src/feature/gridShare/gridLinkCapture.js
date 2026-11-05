/*
 * A grid link: <site>/#grid=16SGC2886455349&name=LZ%20HAWK (docs/GRID_SHARING.md, option 3). The fragment is
 * taken out of the address at once, before anything renders, so it is not left in the history or a bookmark,
 * and kept for this tab (sessionStorage) through a sign-in; the app then takes it (takeGridLink) once.
 */
import { readGridLink } from "../../utils/gridShare";

export const GRID_LINK_STORAGE_KEY = "ezpz.gridLink";

// The fragment, for a tab whose sessionStorage is blocked.
let memory = null;

const storage = (win) => {
  try {
    return win.sessionStorage;
  } catch {
    return null;
  }
};

/** Takes a `#grid=` fragment out of the address and keeps it. Returns the fragment, or null. Any other fragment is left. */
export const captureGridLink = (win = window) => {
  const hash = win.location.hash || "";
  if (!/^#(?:.*&)?grid=/.test(hash)) return null;
  const url = new URL(win.location.href);
  win.history.replaceState(win.history.state, "", `${url.pathname}${url.search}`);
  memory = hash;
  try {
    storage(win)?.setItem(GRID_LINK_STORAGE_KEY, hash);
  } catch {
    // Kept in memory only; a reload loses it.
  }
  return hash;
};

/**
 * The grid a link asked for (`{ choice, name }`, utils/gridShare's readGridLink), taken once: null when there is
 * none, `{ unread: true }` when there was one that does not read as a grid (cut short in a message), so it is said.
 */
export const takeGridLink = (win = window) => {
  let hash = memory;
  try {
    hash = storage(win)?.getItem(GRID_LINK_STORAGE_KEY) ?? hash;
    storage(win)?.removeItem(GRID_LINK_STORAGE_KEY);
  } catch {
    // fall back to memory
  }
  memory = null;
  if (!hash) return null;
  return readGridLink(hash) ?? { unread: true };
};
