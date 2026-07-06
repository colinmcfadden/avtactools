import axios from "axios";

const API_BASE_URL = process.env.REACT_APP_API_URL;

const api = axios.create({ baseURL: API_BASE_URL });

api.interceptors.request.use((config) => {
  const token = localStorage.getItem("auth_token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// When a stored token stops being accepted (expired or signed with a rotated
// secret), clear it and tell AuthContext so the UI flips back to signed-out
// instead of silently failing every request.
let handlingExpiry = false;
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const isAuthCheck = error.config?.url?.includes("/auth/me");
    if (error.response?.status === 401 && localStorage.getItem("auth_token")) {
      localStorage.removeItem("auth_token");
      window.dispatchEvent(new Event("auth-expired"));
      // The silent session-restore check on page load shouldn't alert.
      if (!isAuthCheck && !handlingExpiry) {
        handlingExpiry = true;
        alert("Your session has expired — please sign in again.");
        setTimeout(() => {
          handlingExpiry = false;
        }, 2000);
      }
    }
    return Promise.reject(error);
  },
);

export default api;
