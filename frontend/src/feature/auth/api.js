import axios from "axios";
import { beginPriority, isPriorityRequest } from "./requestPriority";
import {
  SESSION_HEADERS,
  cookieSessionsOffered,
  endSession,
  freshAccessToken,
  getAccessToken,
  hasCookieSession,
  refreshAccessToken,
} from "./session";

const API_BASE_URL = import.meta.env.VITE_API_URL;

const api = axios.create({ baseURL: API_BASE_URL });

const PUBLIC_AUTH_PATHS = new Set([
  "/auth/login",
  "/auth/google",
  "/auth/register",
  "/auth/verify-email",
  "/auth/resend-verification",
  "/auth/forgot-password",
  "/auth/reset-password",
]);

export const isPublicAuthRequest = (url = "") => {
  const path = String(url).split("?")[0].replace(/^https?:\/\/[^/]+/i, "");
  return PUBLIC_AUTH_PATHS.has(path) ||
    Array.from(PUBLIC_AUTH_PATHS).some((publicPath) => path.endsWith(publicPath));
};

const isAuthRequest = (url = "") => /(^|\/)auth\//.test(String(url).split("?")[0]);

api.interceptors.request.use(async (config) => {
  // The sign-in routes ask for a cookie session, and the cookie lives under /auth, so only those requests are
  // made with credentials (the server answers credentials there alone), and only to a server that offers them.
  if (isAuthRequest(config.url) && (hasCookieSession() || await cookieSessionsOffered())) {
    config.withCredentials = true;
    Object.assign(config.headers, SESSION_HEADERS);
  }
  if (isPublicAuthRequest(config.url)) return config;
  const token = await freshAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
    config.sentToken = token;
  }
  return config;
});

// When a token stops being accepted (lapsed, its session ended, or signed with
// a rotated secret), a cookie session asks for a new one once and sends the
// request again; otherwise the session is over: clear it and tell AuthContext
// so the full-screen auth gate can explain what happened. Authentication
// failures from public auth endpoints are left to their forms to render inline.
let handlingExpiry = false;
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const protectedUnauthorized =
      error.response?.status === 401 && !isPublicAuthRequest(error.config?.url);
    if (protectedUnauthorized && hasCookieSession() && !error.config.retried) {
      // Another request may have refreshed while this one was out: then its token is simply sent again.
      let token = getAccessToken();
      if (!token || token === error.config.sentToken) {
        token = await refreshAccessToken().catch(() => undefined);
      }
      if (token === undefined) return Promise.reject(error);  // the server could not be asked: nothing ended
      if (token) {
        // The attempt that failed holds heavy work's place (requestPriority); the one sent again takes its own.
        error.config.endPriority?.();
        error.config.retried = true;
        error.config.headers.Authorization = `Bearer ${token}`;
        error.config.sentToken = token;
        return api.request(error.config);
      }
    }
    if (protectedUnauthorized) {
      // Dispatch even when another tab already removed the shared token. This
      // tab may still have authenticated React state and must close it when a
      // protected request proves the session is no longer accepted.
      endSession();
      if (!handlingExpiry) {
        handlingExpiry = true;
        window.dispatchEvent(
          new CustomEvent("auth-expired", {
            detail: {
              message: "Your session expired. Sign in again to continue.",
            },
          }),
        );
        setTimeout(() => {
          handlingExpiry = false;
        }, 500);
      }
    }
    return Promise.reject(error);
  },
);

// Heavy server work (an LZ analysis, a viewshed, an export) holds 3D terrain
// loading back until it finishes, so the two do not compete for the server.
// See requestPriority.
api.interceptors.request.use((config) => {
  if (isPriorityRequest(config.url)) config.endPriority = beginPriority();
  return config;
});
api.interceptors.response.use(
  (response) => {
    response.config?.endPriority?.();
    return response;
  },
  (error) => {
    error.config?.endPriority?.();
    return Promise.reject(error);
  },
);

export default api;
