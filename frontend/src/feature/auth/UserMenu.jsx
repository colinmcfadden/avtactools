import React from "react";
import { useAuth } from "./AuthContext";
import { useIsMobile } from "./useIsMobile";
import GoogleLoginButton from "./GoogleLoginButton";

const UserMenu = ({ variant = "desktop" }) => {
  const { user, isLoading, logout } = useAuth();
  const isMobile = useIsMobile();

  if (isLoading) return null;

  // A desktop and a mobile UserMenu are both always in the tree (CSS shows one
  // per breakpoint). Mount only the one matching the active layout, so a
  // logged-out visitor renders a single <GoogleLogin>; two would make Google's
  // library warn that initialize() was called multiple times.
  if (variant === "mobile" ? !isMobile : isMobile) return null;

  return (
    <div className="floating-auth">
      {!user ? (
        <GoogleLoginButton />
      ) : (
        <>
          {user.picture ? (
            <img
              src={user.picture}
              alt=""
              referrerPolicy="no-referrer"
              className="user-avatar"
            />
          ) : (
            <div className="user-avatar user-avatar-fallback">
              {user.name?.charAt(0)?.toUpperCase() || "?"}
            </div>
          )}
          <span className="user-name" title={user.email}>
            {user.name}
          </span>
          <button className="logout-btn" onClick={logout}>
            Log out
          </button>
        </>
      )}
    </div>
  );
};

export default UserMenu;
