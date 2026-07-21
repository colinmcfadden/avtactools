import React from "react";
import { GoogleLogin } from "@react-oauth/google";
import { useAuth } from "./AuthContext";

const GoogleLoginButton = ({ onSuccess, onError }) => {
  const { loginWithGoogle } = useAuth();

  const handleSuccess = async (credentialResponse) => {
    try {
      if (!credentialResponse?.credential) {
        throw new Error("Google did not return a sign-in credential.");
      }
      await loginWithGoogle(credentialResponse.credential);
      onSuccess?.();
    } catch (error) {
      onError?.(error);
    }
  };

  return (
    <GoogleLogin
      onSuccess={handleSuccess}
      onError={() => onError?.(new Error("Google sign-in was not completed."))}
      theme="filled_black"
      size="large"
      shape="rectangular"
      text="signin_with"
    />
  );
};

export default GoogleLoginButton;
