/*
 * An invitation link: <site>/?invite=<token>, from an email or a team's shared link
 * (backend/email_service.py). The token is taken out of the address at once, before
 * anything renders, so it is not left in the history, a bookmark or a screenshot,
 * and kept for this tab (sessionStorage) until the person has signed in, cleared the
 * .mil gate and can open packs; useInviteLink then accepts it.
 */

export const INVITE_PARAM = "invite";
export const INVITE_STORAGE_KEY = "ezpz.packInvite";

// What the server issues (secrets.token_urlsafe(32): 43 characters) and accepts (at most 200).
const TOKEN = /^[A-Za-z0-9_-]{16,200}$/;

// The token, for a tab whose sessionStorage is blocked.
let memory = null;

const storage = (win) => {
  try {
    return win.sessionStorage;
  } catch {
    return null; // blocked storage: the link is then good for this load only
  }
};

/**
 * Takes `?invite=` out of the address and keeps it. Returns the token, or null. A value that
 * is not a token is removed and dropped. Leaves every other parameter as it was.
 */
export const captureInviteLink = (win = window) => {
  const url = new URL(win.location.href);
  if (!url.searchParams.has(INVITE_PARAM)) return null;
  const token = url.searchParams.get(INVITE_PARAM) || "";
  url.searchParams.delete(INVITE_PARAM);
  win.history.replaceState(win.history.state, "", `${url.pathname}${url.search}${url.hash}`);
  if (!TOKEN.test(token)) return null;
  try {
    storage(win)?.setItem(INVITE_STORAGE_KEY, token);
  } catch {
    // Kept in memory only; a reload loses it.
  }
  memory = token;
  return token;
};

/** The link waiting to be accepted in this tab, or null. */
export const pendingInviteLink = (win = window) => {
  try {
    const stored = storage(win)?.getItem(INVITE_STORAGE_KEY);
    if (stored && TOKEN.test(stored)) return stored;
  } catch {
    // fall through to memory
  }
  return memory;
};

export const clearInviteLink = (win = window) => {
  memory = null;
  try {
    storage(win)?.removeItem(INVITE_STORAGE_KEY);
  } catch {
    // nothing to clear
  }
};
