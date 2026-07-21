import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import api from "./api";

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [authNotice, setAuthNotice] = useState("");

  const establishSession = useCallback((payload) => {
    if (!payload?.access_token || !payload?.user) {
      throw new Error("The server returned an incomplete sign-in response.");
    }
    localStorage.setItem("auth_token", payload.access_token);
    setUser(payload.user);
    setAuthNotice("");
    return payload.user;
  }, []);

  useEffect(() => {
    let active = true;
    let requestSequence = 0;

    const restoreStoredSession = async () => {
      const requestId = ++requestSequence;
      const token = localStorage.getItem("auth_token");
      if (!token) {
        if (active && requestId === requestSequence) setIsLoading(false);
        return;
      }
      try {
        const res = await api.get("/auth/me");
        if (!active || requestId !== requestSequence) return;
        setUser(res.data);
        setAuthNotice("");
      } catch {
        if (!active || requestId !== requestSequence) return;
        localStorage.removeItem("auth_token");
        setUser(null);
      } finally {
        if (active && requestId === requestSequence) setIsLoading(false);
      }
    };

    restoreStoredSession();

    // Fired by the api interceptor when a stored token stops being accepted.
    const onExpired = (event) => {
      setUser(null);
      setAuthNotice(
        event.detail?.message || "Your session ended. Sign in again to continue.",
      );
    };
    const onStorage = (event) => {
      if (event.key !== "auth_token" && event.key !== null) return;
      if (!event.newValue) {
        // Invalidate any in-flight /auth/me response from the older token.
        requestSequence += 1;
        setUser(null);
        setIsLoading(false);
        setAuthNotice("You were signed out in another browser tab.");
        return;
      }
      setIsLoading(true);
      restoreStoredSession();
    };
    window.addEventListener("auth-expired", onExpired);
    window.addEventListener("storage", onStorage);
    return () => {
      active = false;
      window.removeEventListener("auth-expired", onExpired);
      window.removeEventListener("storage", onStorage);
    };
  }, []);

  const loginWithGoogle = useCallback(async (credential) => {
    const res = await api.post("/auth/google", { token: credential });
    return establishSession(res.data);
  }, [establishSession]);

  const loginWithPassword = useCallback(async (email, password) => {
    const res = await api.post("/auth/login", { email, password });
    return establishSession(res.data);
  }, [establishSession]);

  const register = useCallback(async ({ name, email }) => {
    const res = await api.post("/auth/register", { name, email });
    return res.data;
  }, []);

  const verifyEmail = useCallback(async (token, password) => {
    const res = await api.post("/auth/verify-email", { token, password });
    return res.data;
  }, []);

  const resendVerification = useCallback(async (email) => {
    const res = await api.post("/auth/resend-verification", { email });
    return res.data;
  }, []);

  const requestPasswordReset = useCallback(async (email) => {
    const res = await api.post("/auth/forgot-password", { email });
    return res.data;
  }, []);

  const resetPassword = useCallback(async (token, password) => {
    const res = await api.post("/auth/reset-password", { token, password });
    return res.data;
  }, []);

  const dismissAuthNotice = useCallback(() => setAuthNotice(""), []);

  const clearSession = useCallback((notice = "") => {
    localStorage.removeItem("auth_token");
    setUser(null);
    setAuthNotice(notice);
  }, []);

  const logout = useCallback(() => {
    clearSession("You have been signed out.");
  }, [clearSession]);

  const value = useMemo(
    () => ({
      user,
      isLoading,
      authNotice,
      loginWithGoogle,
      loginWithPassword,
      register,
      verifyEmail,
      resendVerification,
      requestPasswordReset,
      resetPassword,
      dismissAuthNotice,
      clearSession,
      logout,
    }),
    [
      user,
      isLoading,
      authNotice,
      loginWithGoogle,
      loginWithPassword,
      register,
      verifyEmail,
      resendVerification,
      requestPasswordReset,
      resetPassword,
      dismissAuthNotice,
      clearSession,
      logout,
    ],
  );

  return (
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider.");
  }
  return context;
};
