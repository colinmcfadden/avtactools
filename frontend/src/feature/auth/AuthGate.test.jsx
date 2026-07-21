import React from "react";
import { render, screen } from "@testing-library/react";
import AuthGate from "./AuthGate";
import { useAuth } from "./AuthContext";

jest.mock("./AuthContext", () => ({ useAuth: jest.fn() }));
jest.mock("./AuthScreen", () => ({
  __esModule: true,
  default: ({ notice }) => <div data-testid="auth-screen">{notice}</div>,
}));

describe("AuthGate", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/");
  });

  test("shows a session loader without mounting protected content", () => {
    useAuth.mockReturnValue({ user: null, isLoading: true, authNotice: "" });

    render(
      <AuthGate>
        <div>Protected planner</div>
      </AuthGate>,
    );

    expect(screen.getByRole("status")).toHaveTextContent("Establishing secure session");
    expect(screen.queryByText("Protected planner")).not.toBeInTheDocument();
  });

  test("shows the auth screen and forwards a session notice", () => {
    useAuth.mockReturnValue({
      user: null,
      isLoading: false,
      authNotice: "Your session expired.",
    });

    render(
      <AuthGate>
        <div>Protected planner</div>
      </AuthGate>,
    );

    expect(screen.getByTestId("auth-screen")).toHaveTextContent("Your session expired.");
    expect(screen.queryByText("Protected planner")).not.toBeInTheDocument();
  });

  test("mounts protected content only for an authenticated user", () => {
    useAuth.mockReturnValue({
      user: { id: 7, email: "pilot@example.com" },
      isLoading: false,
      authNotice: "",
    });

    render(
      <AuthGate>
        <div>Protected planner</div>
      </AuthGate>,
    );

    expect(screen.getByText("Protected planner")).toBeInTheDocument();
    expect(screen.queryByTestId("auth-screen")).not.toBeInTheDocument();
  });

  test("a token action overrides an existing signed-in session", () => {
    useAuth.mockReturnValue({
      user: { id: 7, email: "pilot@example.com" },
      isLoading: false,
      authNotice: "",
    });
    window.history.replaceState({}, "", "/?auth=reset&token=one-time-token");

    render(
      <AuthGate>
        <div>Protected planner</div>
      </AuthGate>,
    );

    expect(screen.getByTestId("auth-screen")).toBeInTheDocument();
    expect(screen.queryByText("Protected planner")).not.toBeInTheDocument();
    expect(window.location.search).toContain("token=one-time-token");
  });
});
