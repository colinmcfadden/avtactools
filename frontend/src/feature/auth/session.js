import axios from "axios";

/**
 * Where the web keeps its sign-in, and how it keeps it going (AUTHENTICATION.md, "The web's lasting sign-in").
 *
 * A cookie session: the refresh token is an HttpOnly cookie the browser keeps and no script can read, and the
 * access token lives here, in memory, for 15 minutes at a time. A reload starts with no token and asks for one
 * with the cookie, so one sign-in lasts while the account is active and the browser is used every 30 days.
 * `ezpz.session` says such a session exists (it is no secret), and its going tells other tabs of a sign-out.
 *
 * A browser that will not keep the cookie (Safari, while the API is on another site) is found out by asking
 * once, just after signing in. It keeps the sign-in's 24-hour token in `localStorage`, as the web always has.
 *
 * Refreshes are serialised across tabs (Web Locks): the cookie is one per browser and every refresh spends it,
 * so two tabs refreshing at once could leave the browser holding the one that lost, which reads as a copy the
 * next time it is used and ends the session.
 */

const API_BASE_URL = process.env.REACT_APP_API_URL;

export const LEGACY_TOKEN_KEY = "auth_token";
export const SESSION_KEY = "ezpz.session";
export const SESSION_HEADERS = { "X-EZPZ-Session": "cookie" };

const COOKIE = "cookie";
const LOCK = "ezpz-refresh";
// Refreshed this long before it lapses, and treated as lapsed this long before, so a request never goes with
// a token about to be refused.
const REFRESH_AHEAD_MS = 2 * 60 * 1000;
const STALE_MS = 30 * 1000;
// A refresh that met no answer may have reached the server and spent the cookie the browser still holds; the
// server forgives that token for 30 seconds, so it is asked again well inside them (and, once, at once).
const RETRY_MS = 10 * 1000;
const AGAIN_AFTER_MS = 1500;
const REFRESH_TIMEOUT_MS = 12 * 1000;

let memoryToken = null;
let expiresAt = 0;
let inFlight = null;
let timer = null;
const listeners = new Set();

const read = (key) => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};
const write = (key, value) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Storage refused (a private window): the session still works in this tab.
  }
};
const remove = (key) => {
  try {
    localStorage.removeItem(key);
  } catch {
    // As above.
  }
};

/** A token's claims, read without checking it (the server checks it), or null. */
export const claimsOf = (token) => {
  try {
    const part = String(token).split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(part.padEnd(part.length + ((4 - (part.length % 4)) % 4), "=")));
  } catch {
    return null;
  }
};

const expiryOf = (token) => {
  const exp = claimsOf(token)?.exp;
  return typeof exp === "number" ? exp * 1000 : 0;
};

export const hasCookieSession = () => read(SESSION_KEY) === COOKIE;

/** The access token as it stands: this tab's, or a browser that keeps no cookie's stored one. */
export const getAccessToken = () => memoryToken ?? read(LEGACY_TOKEN_KEY);

/** Who is signed in, and in which session: the same across refreshes, different after a sign-out or another sign-in. */
export const sessionKey = () => {
  const token = getAccessToken();
  if (!token) return null;
  const claims = claimsOf(token);
  return claims?.sid ? `${claims.sub}:${claims.sid}` : token;
};

/** Hears every new access token (a refresh, a sign-in) and null when the session ends. Returns the way to stop. */
export const onAccessToken = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

const announce = (token) => listeners.forEach((listener) => {
  try {
    listener(token);
  } catch {
    // One listener's fault is not the session's.
  }
});

const schedule = (ms) => {
  clearTimeout(timer);
  timer = setTimeout(() => {
    timer = null;
    refreshAccessToken()
      .then((token) => {
        if (!token) expire();
      })
      .catch(() => schedule(RETRY_MS));
  }, Math.max(ms, 5000));
};

const holdToken = (token) => {
  memoryToken = token;
  expiresAt = expiryOf(token);
  if (expiresAt) schedule(expiresAt - Date.now() - REFRESH_AHEAD_MS);
  announce(token);
};

/** Forgets this tab's token and its timer, and leaves the stored state alone (another tab may already have changed it). */
export const forgetToken = () => {
  clearTimeout(timer);
  timer = null;
  const had = memoryToken !== null;
  memoryToken = null;
  expiresAt = 0;
  if (had) announce(null);
};

/** Ends the session in this browser: every tab hears of it through the storage event. */
export const endSession = () => {
  forgetToken();
  remove(SESSION_KEY);
  remove(LEGACY_TOKEN_KEY);
};

const expire = () => {
  endSession();
  window.dispatchEvent(new CustomEvent("auth-expired", {
    detail: { message: "Your session expired. Sign in again to continue." },
  }));
};

/**
 * Whether the server offers cookie sessions (API 1.2 and later), asked once a page from the public
 * `/api/config`. Until it is known to, nothing asks for one: an older server answers no request made with
 * credentials, so the browser would refuse every sign-in. Not knowing (offline) is no, and is asked again.
 */
let offered = null;
export const cookieSessionsOffered = () => {
  if (!offered) {
    offered = axios.get(`${API_BASE_URL}/config`, { timeout: 5000 })
      .then(({ data }) => {
        const [major, minor] = String(data?.apiVersion ?? "").split(".").map(Number);
        return major > 1 || (major === 1 && minor >= 2);
      })
      .catch(() => {
        offered = null;
        return false;
      });
  }
  return offered;
};

const underLock = (work) =>
  (typeof navigator !== "undefined" && navigator.locks?.request ? navigator.locks.request(LOCK, work) : work());

/**
 * One refresh by cookie: `{ ok: true, data }`, or `{ ok: false, code }` when the server ended the session or the
 * browser sent no cookie. Throws when the server could not be asked (no connection, busy, down): nothing ended.
 */
const askWithCookie = ({ again = true } = {}) => underLock(async () => {
  for (let tries = again ? 2 : 1; ; tries -= 1) {
    try {
      const response = await axios.post(`${API_BASE_URL}/auth/refresh`, null, {
        headers: SESSION_HEADERS,
        withCredentials: true,
        timeout: REFRESH_TIMEOUT_MS,
      });
      return { ok: true, data: response.data };
    } catch (error) {
      const status = error.response?.status;
      if (status === 401 || status === 403) return { ok: false, code: error.response.data?.code ?? null };
      if (error.response || tries <= 1) throw error;
      await new Promise((resolve) => setTimeout(resolve, AGAIN_AFTER_MS));
    }
  }
});

/**
 * A new access token for this tab's cookie session, shared by everyone who asks while one is on its way.
 * Resolves to the server's answer (`access_token`, `user`), or null when the session is over; rejects when the
 * server could not be asked.
 */
export const refreshSession = () => {
  if (!inFlight) {
    inFlight = askWithCookie()
      .then((outcome) => {
        if (!outcome.ok) return null;
        holdToken(outcome.data.access_token);
        return outcome.data;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
};

export const refreshAccessToken = () => refreshSession().then((data) => data?.access_token ?? null);

/** The token to send now: refreshed first when this tab's has lapsed or is about to. */
export const freshAccessToken = async () => {
  if (memoryToken && Date.now() < expiresAt - STALE_MS) return memoryToken;
  if (memoryToken || hasCookieSession()) {
    try {
      return await refreshAccessToken();
    } catch {
      return memoryToken;
    }
  }
  return read(LEGACY_TOKEN_KEY);
};

/**
 * After a sign-in: keeps what the server gave. A cookie session is tried once at once, to learn whether this
 * browser keeps and sends the cookie; if it does not (or cannot be asked now), the sign-in's token is kept as
 * the web always kept it. Resolves to "cookie" or "stored".
 */
export const startSession = async (payload) => {
  forgetToken();
  remove(LEGACY_TOKEN_KEY);
  if (payload.session === COOKIE) {
    let outcome = null;
    try {
      outcome = await askWithCookie({ again: false });
    } catch {
      outcome = null;
    }
    if (outcome?.ok) {
      holdToken(outcome.data.access_token);
      write(SESSION_KEY, COOKIE);
      return COOKIE;
    }
  }
  remove(SESSION_KEY);
  write(LEGACY_TOKEN_KEY, payload.access_token);
  return "stored";
};

/** Signs out here, and asks the server to end the session (and clear its cookie) without waiting on it. */
export const signOut = () => {
  const token = memoryToken && Date.now() < expiresAt - STALE_MS ? memoryToken : read(LEGACY_TOKEN_KEY);
  const cookie = hasCookieSession();
  endSession();
  if (!token && !cookie) return Promise.resolve();
  return (async () => {
    let usable = token;
    if (!usable && cookie) {
      const outcome = await askWithCookie({ again: false }).catch(() => null);
      usable = outcome?.ok ? outcome.data.access_token : null;
    }
    if (!usable) return;
    await axios.post(`${API_BASE_URL}/auth/logout`, null, {
      headers: { ...SESSION_HEADERS, Authorization: `Bearer ${usable}` },
      withCredentials: true,
    }).catch(() => {});
  })();
};
