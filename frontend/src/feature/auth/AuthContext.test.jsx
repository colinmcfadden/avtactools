import React from "react";
import { act, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthContext";
import api from "./api";

jest.mock("./api", () => ({
  __esModule: true,
  default: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

const AuthProbe = () => {
  const { user, isLoading, authNotice } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(isLoading)}</span>
      <span data-testid="email">{user?.email || "signed-out"}</span>
      <span data-testid="notice">{authNotice}</span>
    </div>
  );
};

const dispatchTokenStorage = (newValue) => {
  window.dispatchEvent(
    new StorageEvent("storage", {
      key: "auth_token",
      newValue,
      storageArea: window.localStorage,
    }),
  );
};

describe("AuthProvider tab synchronization", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.clearAllMocks();
  });

  test("restores a login from another tab and follows its logout", async () => {
    api.get.mockResolvedValue({
      data: { id: 11, email: "pilot@example.com", name: "Pilot" },
    });
    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));

    act(() => {
      localStorage.setItem("auth_token", "token-from-other-tab");
      dispatchTokenStorage("token-from-other-tab");
    });

    await waitFor(() =>
      expect(screen.getByTestId("email")).toHaveTextContent("pilot@example.com"),
    );
    expect(api.get).toHaveBeenCalledWith("/auth/me");

    act(() => {
      localStorage.removeItem("auth_token");
      dispatchTokenStorage(null);
    });

    expect(screen.getByTestId("email")).toHaveTextContent("signed-out");
    expect(screen.getByTestId("notice")).toHaveTextContent(
      "signed out in another browser tab",
    );
  });
});
