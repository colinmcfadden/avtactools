import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import AuthScreen from "./AuthScreen";
import { useAuth } from "./AuthContext";

jest.mock("./AuthContext", () => ({ useAuth: jest.fn() }));
jest.mock("./GoogleLoginButton", () => ({
  __esModule: true,
  default: () => <div data-testid="google-login" />,
}));

const authMethods = (overrides = {}) => ({
  loginWithPassword: jest.fn(),
  register: jest.fn(),
  verifyEmail: jest.fn(),
  resendVerification: jest.fn(),
  requestPasswordReset: jest.fn(),
  resetPassword: jest.fn(),
  dismissAuthNotice: jest.fn(),
  clearSession: jest.fn(),
  ...overrides,
});

describe("AuthScreen", () => {
  afterEach(() => {
    window.history.replaceState({}, "", "/");
    jest.clearAllMocks();
  });

  test("requires an explicit click before consuming an email-verification token", async () => {
    const verifyEmail = jest.fn().mockResolvedValue({ message: "Account verified." });
    const clearSession = jest.fn();
    useAuth.mockReturnValue(authMethods({ verifyEmail, clearSession }));
    window.history.replaceState({}, "", "/?auth=verify&token=verification-token");

    render(<AuthScreen />);

    expect(verifyEmail).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "a-secure-passphrase-15" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "a-secure-passphrase-15" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Verify email and activate account" }),
    );

    await waitFor(() =>
      expect(verifyEmail).toHaveBeenCalledWith(
        "verification-token",
        "a-secure-passphrase-15",
      ),
    );
    expect(await screen.findByText("Account verified.")).toBeInTheDocument();
    expect(clearSession).toHaveBeenCalled();
  });

  test("defaults to the sign-in view for an unknown auth mode", () => {
    useAuth.mockReturnValue(authMethods());
    window.history.replaceState({}, "", "/?auth=unexpected");

    render(<AuthScreen />);

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toHaveAttribute("autocomplete", "email");
  });

  test("starts manual registration with identity details before collecting a password", async () => {
    const register = jest.fn().mockResolvedValue({ status: "success" });
    useAuth.mockReturnValue(authMethods({ register }));
    window.history.replaceState({}, "", "/?auth=register");

    render(<AuthScreen />);

    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Full name"), {
      target: { value: "Avery Pilot" },
    });
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "avery@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(register).toHaveBeenCalledWith({
        name: "Avery Pilot",
        email: "avery@example.com",
      }),
    );
    expect(await screen.findByText("Check your inbox")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  test("offers a direct, privacy-preserving verification resend path from sign in", async () => {
    const resendVerification = jest.fn().mockResolvedValue({ status: "success" });
    useAuth.mockReturnValue(authMethods({ resendVerification }));

    render(<AuthScreen />);
    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "pilot@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Resend verification email" }));

    expect(screen.getByRole("heading", { name: "Resend activation link" })).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toHaveValue("pilot@example.com");

    fireEvent.click(screen.getByRole("button", { name: "Resend verification email" }));
    await waitFor(() => expect(resendVerification).toHaveBeenCalledWith("pilot@example.com"));
    expect(await screen.findByText(/If that account is eligible/)).toBeInTheDocument();
  });

  test("successful password reset clears any existing local session", async () => {
    const resetPassword = jest.fn().mockResolvedValue({ status: "success" });
    const clearSession = jest.fn();
    useAuth.mockReturnValue(authMethods({ resetPassword, clearSession }));
    window.history.replaceState({}, "", "/?auth=reset&token=reset-token");

    render(<AuthScreen />);

    fireEvent.change(screen.getByLabelText("New password"), {
      target: { value: "another-secure-passphrase" },
    });
    fireEvent.change(screen.getByLabelText("Confirm new password"), {
      target: { value: "another-secure-passphrase" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Update password" }));

    await waitFor(() =>
      expect(resetPassword).toHaveBeenCalledWith(
        "reset-token",
        "another-secure-passphrase",
      ),
    );
    expect(clearSession).toHaveBeenCalled();
    expect(await screen.findByText("Password updated")).toBeInTheDocument();
  });
});
