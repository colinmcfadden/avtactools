import React, { useEffect } from "react";
import { useAuth } from "./AuthContext";
import AuthScreen from "./AuthScreen";
import "./AuthScreen.css";

const clearAuthQuery = () => {
  const url = new URL(window.location.href);
  const hadAuthQuery = url.searchParams.has("auth") || url.searchParams.has("token");
  if (!hadAuthQuery) return;
  url.searchParams.delete("auth");
  url.searchParams.delete("token");
  window.history.replaceState({}, "", `${url.pathname}${url.search}${url.hash}`);
};

const hasTokenAuthAction = () => {
  const params = new URLSearchParams(window.location.search);
  return (
    Boolean(params.get("token")) &&
    (params.get("auth") === "verify" || params.get("auth") === "reset")
  );
};

const AuthLoadingScreen = () => (
  <div className="auth-shell auth-shell--loading" role="status" aria-live="polite">
    <div className="auth-loader-mark" aria-hidden="true">
      <span />
      <span />
      <span />
    </div>
    <div className="auth-loader-title">EZ-PZ</div>
    <div className="auth-loader-copy">Establishing secure session</div>
  </div>
);

const AuthGate = ({ children }) => {
  const { user, isLoading, authNotice } = useAuth();
  const tokenAuthAction = hasTokenAuthAction();

  useEffect(() => {
    if (user && !tokenAuthAction) clearAuthQuery();
  }, [user, tokenAuthAction]);

  if (isLoading) return <AuthLoadingScreen />;
  // Verification and password-reset links must remain reachable even when the
  // browser already has a session (for example, while recovering a different
  // account). Never silently discard their one-time token.
  if (tokenAuthAction) return <AuthScreen notice={authNotice} />;
  if (!user) return <AuthScreen notice={authNotice} />;
  return children;
};

export default AuthGate;
