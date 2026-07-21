import axios from "axios";

const API_BASE_URL = process.env.REACT_APP_API_URL;

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

api.interceptors.request.use((config) => {
  const token = localStorage.getItem("auth_token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// When a stored token stops being accepted (expired or signed with a rotated
// secret), clear it and tell AuthContext so the full-screen auth gate can
// explain what happened. Authentication failures from public auth endpoints
// are left to their forms to render inline.
let handlingExpiry = false;
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const protectedUnauthorized =
      error.response?.status === 401 && !isPublicAuthRequest(error.config?.url);
    if (protectedUnauthorized) {
      // Dispatch even when another tab already removed the shared token. This
      // tab may still have authenticated React state and must close it when a
      // protected request proves the session is no longer accepted.
      if (localStorage.getItem("auth_token")) {
        localStorage.removeItem("auth_token");
      }
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

export default api;
