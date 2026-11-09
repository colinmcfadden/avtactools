import React from "react";
import { act, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthContext";
import api from "./api";
import { forgetToken, refreshSession } from "./session";

vi.mock("./api", () => ({
  __esModule: true,
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock("./session", async (importOriginal) => ({
  ...(await importOriginal()),
  refreshSession: vi.fn(),
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
    vi.clearAllMocks();
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

describe("AuthProvider with a cookie session", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  afterEach(() => forgetToken());

  const renderProbe = () => render(
    <AuthProvider>
      <AuthProbe />
    </AuthProvider>,
  );

  test("a reload is signed in by the cookie alone, with nothing kept in localStorage", async () => {
    localStorage.setItem("ezpz.session", "cookie");
    refreshSession.mockResolvedValue({ access_token: "a.b.c", user: { id: 3, email: "crew@example.com" } });
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("email")).toHaveTextContent("crew@example.com"));
    expect(refreshSession).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalled();
    expect(localStorage.getItem("auth_token")).toBeNull();
  });

  test("a session the server ended is said so, and forgotten", async () => {
    localStorage.setItem("ezpz.session", "cookie");
    refreshSession.mockResolvedValue(null);
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("email")).toHaveTextContent("signed-out");
    expect(screen.getByTestId("notice")).toHaveTextContent("Your session ended");
    expect(localStorage.getItem("ezpz.session")).toBeNull();
  });

  test("a server that cannot be reached ends nothing: a reload later finds the session", async () => {
    localStorage.setItem("ezpz.session", "cookie");
    refreshSession.mockRejectedValue(new Error("Network Error"));
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("notice")).toHaveTextContent("Could not reach the server");
    expect(localStorage.getItem("ezpz.session")).toBe("cookie");
  });

  test("a sign-out in another tab signs this one out", async () => {
    localStorage.setItem("ezpz.session", "cookie");
    refreshSession.mockResolvedValue({ access_token: "a.b.c", user: { id: 3, email: "crew@example.com" } });
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("email")).toHaveTextContent("crew@example.com"));
    act(() => {
      localStorage.removeItem("ezpz.session");
      window.dispatchEvent(new StorageEvent("storage", { key: "ezpz.session", newValue: null, storageArea: window.localStorage }));
    });
    expect(screen.getByTestId("email")).toHaveTextContent("signed-out");
    expect(screen.getByTestId("notice")).toHaveTextContent("signed out in another browser tab");
  });
});
