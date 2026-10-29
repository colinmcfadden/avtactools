import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import api from "./api";
import {
  LEGACY_TOKEN_KEY,
  SESSION_KEY,
  endSession,
  forgetToken,
  hasCookieSession,
  refreshSession,
  signOut,
  startSession,
} from "./session";

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [authNotice, setAuthNotice] = useState("");

  const establishSession = useCallback(async (payload) => {
    if (!payload?.access_token || !payload?.user) {
      throw new Error("The server returned an incomplete sign-in response.");
    }
    await startSession(payload);
    setUser(payload.user);
    setAuthNotice("");
    return payload.user;
  }, []);

  useEffect(() => {
    let active = true;
    let requestSequence = 0;

    const restoreStoredSession = async () => {
      const requestId = ++requestSequence;
      const current = () => active && requestId === requestSequence;
      try {
        if (hasCookieSession()) {
          // A cookie session: the page starts with no token and asks for one with the cookie.
          const data = await refreshSession();
          if (!current()) return;
          if (data?.user) {
            setUser(data.user);
            setAuthNotice("");
          } else {
            endSession();
            setUser(null);
            setAuthNotice("Your session ended. Sign in again to continue.");
          }
          return;
        }
        if (!localStorage.getItem(LEGACY_TOKEN_KEY)) return;
        const res = await api.get("/auth/me");
        if (!current()) return;
        setUser(res.data);
        setAuthNotice("");
      } catch {
        if (!current()) return;
        setUser(null);
        if (hasCookieSession()) {
          // The server could not be asked; nothing ended, and a reload once it is back finds the session.
          setAuthNotice("Could not reach the server. Reload to try again.");
        } else {
          localStorage.removeItem(LEGACY_TOKEN_KEY);
        }
      } finally {
        if (current()) setIsLoading(false);
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
      if (event.key !== LEGACY_TOKEN_KEY && event.key !== SESSION_KEY && event.key !== null) return;
      if (!event.newValue) {
        // Invalidate any in-flight /auth/me response from the older token.
        requestSequence += 1;
        forgetToken();
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

  const requestMilCode = useCallback(async (email) => {
    const res = await api.post("/auth/mil/request", { email });
    return res.data;
  }, []);

  const verifyMil = useCallback(async (code) => {
    const res = await api.post("/auth/mil/verify", { code });
    if (res.data?.user) setUser(res.data.user);
    return res.data;
  }, []);

  const dismissAuthNotice = useCallback(() => setAuthNotice(""), []);

  const clearSession = useCallback((notice = "") => {
    endSession();
    setUser(null);
    setAuthNotice(notice);
  }, []);

  // Ends the session on the server too (and clears its cookie), so the browser's sign-in cannot be used again.
  const logout = useCallback(() => {
    signOut();
    setUser(null);
    setAuthNotice("You have been signed out.");
  }, []);

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
      requestMilCode,
      verifyMil,
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
      requestMilCode,
      verifyMil,
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
