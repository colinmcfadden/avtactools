import React from "react";
import { useAuth } from "./AuthContext";
import GoogleLoginButton from "./GoogleLoginButton";

const UserMenu = () => {
  const { user, isLoading, logout } = useAuth();

  if (isLoading) return null;

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
