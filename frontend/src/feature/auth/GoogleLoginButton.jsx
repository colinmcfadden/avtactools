import React from "react";
import { GoogleLogin } from "@react-oauth/google";
import { useAuth } from "./AuthContext";

const GoogleLoginButton = () => {
  const { login } = useAuth();

  return (
    <GoogleLogin
      onSuccess={(credentialResponse) => login(credentialResponse.credential)}
      onError={() => console.error("Google login failed")}
      theme="filled_black"
      size="medium"
    />
  );
};

export default GoogleLoginButton;
