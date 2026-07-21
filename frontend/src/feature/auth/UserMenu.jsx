import React from "react";
import { useAuth } from "./AuthContext";
import { useIsMobile } from "./useIsMobile";

const UserMenu = ({ variant = "desktop" }) => {
  const { user, isLoading, logout } = useAuth();
  const isMobile = useIsMobile();

  if (isLoading) return null;

  // A desktop and a mobile UserMenu are both always in the tree. Mount only the
  // one matching the active layout so there is a single account control.
  if (variant === "mobile" ? !isMobile : isMobile) return null;
  if (!user) return null;

  const displayName = user.name || user.email || "Account";
  const initial = displayName.charAt(0).toUpperCase();

  return (
    <div className="floating-auth">
      {user.picture ? (
        <img
          src={user.picture}
          alt=""
          referrerPolicy="no-referrer"
          className="user-avatar"
        />
      ) : (
        <div className="user-avatar user-avatar-fallback">{initial}</div>
      )}
      <span className="user-name" title={user.email}>
        {displayName}
      </span>
      <button className="logout-btn" onClick={logout}>
        Log out
      </button>
    </div>
  );
};

export default UserMenu;
