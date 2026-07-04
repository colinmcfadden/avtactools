import React from "react";
import { useAuth } from "./AuthContext";
import GoogleLoginButton from "./GoogleLoginButton";

const UserMenu = () => {
  const { user, isLoading, logout } = useAuth();

  if (isLoading) return null;

  if (!user) {
    return (
      <div className="user-menu">
        <GoogleLoginButton />
      </div>
    );
  }

  return (
    <div className="user-menu">
      <span className="user-name" title={user.email}>
        {user.name}
      </span>
      <button className="logout-btn" onClick={logout}>
        Log out
      </button>
    </div>
  );
};

export default UserMenu;
