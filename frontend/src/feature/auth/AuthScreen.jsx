import React, { useEffect, useState } from "react";
import { useAuth } from "./AuthContext";
import GoogleLoginButton from "./GoogleLoginButton";

const AUTH_MODES = new Set([
  "login",
  "register",
  "check-email",
  "verify",
  "resend",
  "forgot",
  "reset",
]);

const readAuthLocation = () => {
  const params = new URLSearchParams(window.location.search);
  const requested = params.get("auth") || "login";
  return {
    mode: AUTH_MODES.has(requested) ? requested : "login",
    token: params.get("token") || "",
  };
};

const writeAuthLocation = (mode, { token = "", replace = false } = {}) => {
  const safeMode = AUTH_MODES.has(mode) ? mode : "login";
  const url = new URL(window.location.href);

  if (safeMode === "login") url.searchParams.delete("auth");
  else url.searchParams.set("auth", safeMode);

  if (token) url.searchParams.set("token", token);
  else url.searchParams.delete("token");

  const nextUrl = `${url.pathname}${url.search}${url.hash}`;
  window.history[replace ? "replaceState" : "pushState"]({}, "", nextUrl);
  return { mode: safeMode, token };
};

const removeTokenFromAddressBar = () => {
  const url = new URL(window.location.href);
  if (!url.searchParams.has("token")) return;
  url.searchParams.delete("token");
  window.history.replaceState({}, "", `${url.pathname}${url.search}${url.hash}`);
};

const errorMessage = (error, fallback) =>
  error?.response?.data?.message ||
  error?.response?.data?.error ||
  error?.message ||
  fallback;

const isUnverifiedError = (error) =>
  error?.response?.data?.code === "email_not_verified" ||
  error?.response?.data?.error === "email_not_verified";

const maskEmail = (email) => {
  if (!email || !email.includes("@")) return "your email address";
  const [local, domain] = email.split("@");
  const visible = local.slice(0, Math.min(2, local.length));
  return `${visible}${"•".repeat(Math.max(3, local.length - visible.length))}@${domain}`;
};

const Feedback = ({ type = "error", children, onDismiss }) => {
  if (!children) return null;
  return (
    <div className={`auth-feedback auth-feedback--${type}`} role="status" aria-live="polite">
      <span>{children}</span>
      {onDismiss && (
        <button type="button" onClick={onDismiss} aria-label="Dismiss message">
          ×
        </button>
      )}
    </div>
  );
};

const Field = ({ label, hint, action, ...inputProps }) => (
  <label className="auth-field">
    <span className="auth-field__label-row">
      <span>{label}</span>
      {action}
    </span>
    <input {...inputProps} />
    {hint && <small>{hint}</small>}
  </label>
);

const PasswordField = ({ label, hint, ...inputProps }) => {
  const [visible, setVisible] = useState(false);
  const generatedId = React.useId();
  const inputId = inputProps.id || generatedId;
  return (
    <div className="auth-field">
      <label className="auth-field__label-row" htmlFor={inputId}>
        <span>{label}</span>
      </label>
      <span className="auth-password-wrap">
        <input
          {...inputProps}
          id={inputId}
          type={visible ? "text" : "password"}
        />
        <button
          type="button"
          onClick={() => setVisible((value) => !value)}
          aria-label={visible ? "Hide password" : "Show password"}
        >
          {visible ? "Hide" : "Show"}
        </button>
      </span>
      {hint && <small>{hint}</small>}
    </div>
  );
};

const SubmitButton = ({ busy, busyLabel, children }) => (
  <button className="auth-submit" type="submit" disabled={busy}>
    {busy && <span className="auth-button-spinner" aria-hidden="true" />}
    {busy ? busyLabel : children}
  </button>
);

const Divider = () => (
  <div className="auth-divider" aria-hidden="true">
    <span>or continue with email</span>
  </div>
);

const GoogleAccess = ({ onAuthenticated, onError }) => (
  <div className="auth-google">
    <GoogleLoginButton onSuccess={onAuthenticated} onError={onError} />
  </div>
);

const SignInPanel = ({
  notice,
  onNavigate,
  onAuthenticated,
  onNeedsVerification,
  onResendVerification,
}) => {
  const { loginWithPassword, dismissAuthNotice } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await loginWithPassword(email.trim(), password);
      onAuthenticated();
    } catch (requestError) {
      if (isUnverifiedError(requestError)) {
        onNeedsVerification(email.trim());
      } else {
        setError(errorMessage(requestError, "Unable to sign in. Check your credentials."));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Feedback type="info" onDismiss={notice ? dismissAuthNotice : undefined}>
        {notice}
      </Feedback>
      <Feedback>{error}</Feedback>
      <GoogleAccess
        onAuthenticated={onAuthenticated}
        onError={(googleError) =>
          setError(errorMessage(googleError, "Google sign-in could not be completed."))
        }
      />
      <Divider />
      <form className="auth-form" onSubmit={handleSubmit}>
        <Field
          label="Email address"
          type="email"
          autoComplete="email"
          inputMode="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="name@example.com"
          required
          autoFocus
        />
        <PasswordField
          label="Password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
        <div className="auth-form-links">
          <button className="auth-text-action" type="button" onClick={() => onResendVerification(email.trim())}>
            Resend verification email
          </button>
          <button className="auth-text-action" type="button" onClick={() => onNavigate("forgot")}>
            Forgot password?
          </button>
        </div>
        <SubmitButton busy={busy} busyLabel="Signing in…">
          Sign in
        </SubmitButton>
      </form>
      <p className="auth-switch-copy">
        Need an account?{" "}
        <button type="button" onClick={() => onNavigate("register")}>
          Register with email
        </button>
      </p>
    </>
  );
};

const RegisterPanel = ({ onNavigate, onAuthenticated, onRegistered }) => {
  const { register } = useAuth();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await register({ name: name.trim(), email: email.trim() });
      onRegistered(email.trim());
    } catch (requestError) {
      setError(errorMessage(requestError, "We could not create the account."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Feedback>{error}</Feedback>
      <GoogleAccess
        onAuthenticated={onAuthenticated}
        onError={(googleError) =>
          setError(errorMessage(googleError, "Google sign-in could not be completed."))
        }
      />
      <Divider />
      <form className="auth-form" onSubmit={handleSubmit}>
        <Field
          label="Full name"
          type="text"
          autoComplete="name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          required
          autoFocus
        />
        <Field
          label="Email address"
          type="email"
          autoComplete="email"
          inputMode="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
        />
        <div className="auth-policy-notice">
          <strong>Data-handling notice</strong>
          <span>Use this system only for authorized purposes. Do not upload or process classified information.</span>
        </div>
        <SubmitButton busy={busy} busyLabel="Creating account…">
          Create account
        </SubmitButton>
      </form>
      <p className="auth-switch-copy">
        Already registered?{" "}
        <button type="button" onClick={() => onNavigate("login")}>
          Sign in
        </button>
      </p>
    </>
  );
};

const ResendVerification = ({ initialEmail = "" }) => {
  const { resendVerification } = useAuth();
  const [email, setEmail] = useState(initialEmail);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    if (!cooldown) return undefined;
    const timer = window.setInterval(
      () => setCooldown((seconds) => Math.max(0, seconds - 1)),
      1000,
    );
    return () => window.clearInterval(timer);
  }, [cooldown]);

  const handleResend = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await resendVerification(email.trim());
      setMessage("If that account is eligible, a new verification link is on its way.");
      setCooldown(30);
    } catch (requestError) {
      setError(errorMessage(requestError, "A new verification link could not be requested."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="auth-form auth-form--compact" onSubmit={handleResend}>
      <Feedback type="success">{message}</Feedback>
      <Feedback>{error}</Feedback>
      <Field
        label="Email address"
        type="email"
        autoComplete="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        required
      />
      <button className="auth-secondary" type="submit" disabled={busy || cooldown > 0}>
        {busy ? "Requesting…" : cooldown ? `Resend available in ${cooldown}s` : "Resend verification email"}
      </button>
    </form>
  );
};

const CheckEmailPanel = ({ email, onNavigate }) => (
  <div className="auth-state">
    <div className="auth-state__icon auth-state__icon--mail" aria-hidden="true">✉</div>
    <h2>Check your inbox</h2>
    <p>
      We sent an account verification link to <strong>{maskEmail(email)}</strong>.
      Follow that link before signing in.
    </p>
    <div className="auth-instruction">
      Verification links expire for your security. Check spam or junk mail if it does not arrive shortly.
    </div>
    <ResendVerification initialEmail={email} />
    <button className="auth-text-action" type="button" onClick={() => onNavigate("login")}>
      Return to sign in
    </button>
  </div>
);

const ResendVerificationPanel = ({ email, onNavigate }) => (
  <>
    <p className="auth-lead">
      Enter the email used to register. For privacy, the response is the same whether or not an eligible account exists.
    </p>
    <ResendVerification initialEmail={email} />
    <button className="auth-text-action" type="button" onClick={() => onNavigate("login")}>
      Return to sign in
    </button>
  </>
);

const ForgotPasswordPanel = ({ onNavigate, onRequested }) => {
  const { requestPasswordReset } = useAuth();
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await requestPasswordReset(email.trim());
      setSent(true);
      onRequested(email.trim());
    } catch (requestError) {
      setError(errorMessage(requestError, "The reset request could not be completed."));
    } finally {
      setBusy(false);
    }
  };

  if (sent) {
    return (
      <div className="auth-state">
        <div className="auth-state__icon auth-state__icon--mail" aria-hidden="true">✉</div>
        <h2>Check your inbox</h2>
        <p>If an account matches <strong>{maskEmail(email)}</strong>, a password-reset link has been sent.</p>
        <button className="auth-submit" type="button" onClick={() => onNavigate("login")}>
          Return to sign in
        </button>
      </div>
    );
  }

  return (
    <>
      <p className="auth-lead">Enter your account email and we will send a time-limited reset link.</p>
      <Feedback>{error}</Feedback>
      <form className="auth-form" onSubmit={handleSubmit}>
        <Field
          label="Email address"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          required
          autoFocus
        />
        <SubmitButton busy={busy} busyLabel="Sending reset link…">
          Send reset link
        </SubmitButton>
      </form>
      <button className="auth-text-action" type="button" onClick={() => onNavigate("login")}>
        Return to sign in
      </button>
    </>
  );
};

const ResetPasswordPanel = ({ token, onNavigate }) => {
  const { resetPassword, clearSession } = useAuth();
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);
  const [complete, setComplete] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (password.length < 15) {
      setError("Use a password of at least 15 characters.");
      return;
    }
    if (password !== confirmation) {
      setError("The passwords do not match.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await resetPassword(token, password);
      removeTokenFromAddressBar();
      clearSession();
      setComplete(true);
    } catch (requestError) {
      setError(errorMessage(requestError, "This reset link is invalid or has expired."));
    } finally {
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <div className="auth-state">
        <Feedback>This password-reset link is incomplete.</Feedback>
        <button className="auth-submit" type="button" onClick={() => onNavigate("forgot")}>
          Request a new link
        </button>
      </div>
    );
  }

  if (complete) {
    return (
      <div className="auth-state">
        <div className="auth-state__icon auth-state__icon--success" aria-hidden="true">✓</div>
        <h2>Password updated</h2>
        <p>Your new password is active. Sign in to continue to the planner.</p>
        <button className="auth-submit" type="button" onClick={() => onNavigate("login", { replace: true })}>
          Continue to sign in
        </button>
      </div>
    );
  }

  return (
    <>
      <p className="auth-lead">Choose a new password for your account.</p>
      <Feedback>{error}</Feedback>
      <form className="auth-form" onSubmit={handleSubmit}>
        <PasswordField
          label="New password"
          hint="Use 15 or more characters. A passphrase works well."
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          minLength={15}
          required
          autoFocus
        />
        <PasswordField
          label="Confirm new password"
          autoComplete="new-password"
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
          minLength={15}
          required
        />
        <SubmitButton busy={busy} busyLabel="Updating password…">
          Update password
        </SubmitButton>
      </form>
    </>
  );
};

const VerifyEmailPanel = ({ token, email, onNavigate }) => {
  const { verifyEmail, clearSession } = useAuth();
  const [status, setStatus] = useState(token ? "ready" : "error");
  const [message, setMessage] = useState(token ? "" : "This verification link is incomplete.");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [formError, setFormError] = useState("");

  const handleVerify = async (event) => {
    event.preventDefault();
    if (!token) return;
    if (password.length < 15) {
      setFormError("Use a password of at least 15 characters.");
      return;
    }
    if (password !== confirmation) {
      setFormError("The passwords do not match.");
      return;
    }
    setStatus("working");
    setMessage("");
    setFormError("");
    try {
      const result = await verifyEmail(token, password);
      removeTokenFromAddressBar();
      clearSession();
      setStatus("success");
      setMessage(result?.message || "Your email address has been verified.");
    } catch (requestError) {
      setStatus("error");
      setMessage(errorMessage(requestError, "This verification link is invalid or has expired."));
    }
  };

  if (status === "ready") {
    return (
      <div className="auth-state">
        <div className="auth-state__icon auth-state__icon--mail" aria-hidden="true">✉</div>
        <h2>Create your password</h2>
        <p>Finish account setup by choosing the password you will use to access the planner.</p>
        <form className="auth-form auth-form--verify" onSubmit={handleVerify}>
          <Feedback>{formError}</Feedback>
          <PasswordField
            label="Password"
            hint="Use 15 or more characters. A passphrase works well."
            autoComplete="new-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            minLength={15}
            required
            autoFocus
          />
          <PasswordField
            label="Confirm password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            minLength={15}
            required
          />
          <SubmitButton busy={false} busyLabel="Activating account…">
            Verify email and activate account
          </SubmitButton>
        </form>
      </div>
    );
  }

  if (status === "working") {
    return (
      <div className="auth-state" role="status" aria-live="polite">
        <div className="auth-state-spinner" aria-hidden="true" />
        <h2>Verifying account</h2>
        <p>Validating your secure registration link…</p>
      </div>
    );
  }

  if (status === "success") {
    return (
      <div className="auth-state">
        <div className="auth-state__icon auth-state__icon--success" aria-hidden="true">✓</div>
        <h2>Email verified</h2>
        <p>{message}</p>
        <button className="auth-submit" type="button" onClick={() => onNavigate("login", { replace: true })}>
          Continue to sign in
        </button>
      </div>
    );
  }

  return (
    <div className="auth-state">
      <div className="auth-state__icon auth-state__icon--error" aria-hidden="true">!</div>
      <h2>Verification unsuccessful</h2>
      <Feedback>{message}</Feedback>
      <ResendVerification initialEmail={email} />
      <button className="auth-text-action" type="button" onClick={() => onNavigate("login", { replace: true })}>
        Return to sign in
      </button>
    </div>
  );
};

const PANEL_COPY = {
  login: { eyebrow: "ACCOUNT ACCESS", title: "Sign in", subtitle: "Continue to the mission planning workspace." },
  register: { eyebrow: "NEW USER", title: "Create account", subtitle: "Start with your email, then set a password after verification." },
  "check-email": { eyebrow: "EMAIL VERIFICATION", title: "Activate account", subtitle: "One final step protects access to your workspace." },
  verify: { eyebrow: "EMAIL VERIFICATION", title: "Activate account", subtitle: "Complete registration with a secure passphrase." },
  resend: { eyebrow: "EMAIL VERIFICATION", title: "Resend activation link", subtitle: "Request a new time-limited verification email." },
  forgot: { eyebrow: "ACCOUNT RECOVERY", title: "Reset access", subtitle: "Recover access using your verified email address." },
  reset: { eyebrow: "ACCOUNT RECOVERY", title: "Set new password", subtitle: "Secure your account with a new passphrase." },
};

const AuthScreen = ({ notice = "" }) => {
  const [locationState, setLocationState] = useState(readAuthLocation);
  const [pendingEmail, setPendingEmail] = useState("");
  const { mode, token } = locationState;

  useEffect(() => {
    const handlePopState = () => setLocationState(readAuthLocation());
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const navigate = (nextMode, options) =>
    setLocationState(writeAuthLocation(nextMode, options));

  const onAuthenticated = () => {
    writeAuthLocation("login", { replace: true });
  };

  const copy = PANEL_COPY[mode] || PANEL_COPY.login;

  let panel;
  if (mode === "register") {
    panel = (
      <RegisterPanel
        onNavigate={navigate}
        onAuthenticated={onAuthenticated}
        onRegistered={(email) => {
          setPendingEmail(email);
          navigate("check-email");
        }}
      />
    );
  } else if (mode === "check-email") {
    panel = <CheckEmailPanel email={pendingEmail} onNavigate={navigate} />;
  } else if (mode === "verify") {
    panel = <VerifyEmailPanel token={token} email={pendingEmail} onNavigate={navigate} />;
  } else if (mode === "resend") {
    panel = <ResendVerificationPanel email={pendingEmail} onNavigate={navigate} />;
  } else if (mode === "forgot") {
    panel = <ForgotPasswordPanel onNavigate={navigate} onRequested={setPendingEmail} />;
  } else if (mode === "reset") {
    panel = <ResetPasswordPanel token={token} onNavigate={navigate} />;
  } else {
    panel = (
      <SignInPanel
        notice={notice}
        onNavigate={navigate}
        onAuthenticated={onAuthenticated}
        onNeedsVerification={(email) => {
          setPendingEmail(email);
          navigate("check-email");
        }}
        onResendVerification={(email) => {
          setPendingEmail(email);
          navigate("resend");
        }}
      />
    );
  }

  return (
    <main className="auth-shell">
      <div className="auth-grid" aria-hidden="true" />
      <section className="auth-brand-panel" aria-label="Application information">
        <div className="auth-brand">
          <img src="/img/ezpz_logo-1.png" alt="EZ-PZ Tactical LZ/PZ Planner" />
          <div>
            <span>EZ-PZ</span>
            <strong>Tactical Planner</strong>
          </div>
        </div>
        <div className="auth-brand-message">
          <div className="auth-kicker"><span /> For Army Aviators, by Army Aviators</div>
          <h1>Frictionless mission planning</h1>
          <p>Terrain analysis, LZ/PZ diagrams, threat planning, and AMPS-compatible routes in one easy to use operating picture.</p>
        </div>
      {/* <div className="auth-security-note">
          <strong>OPERATIONAL SECURITY</strong>
          <p>Use only for authorized purposes. Do not enter, upload, or process classified information.</p>
        </div>  */}
      </section>

      <section className="auth-access-panel">
        <div className="auth-card">
          <header className="auth-card__header">
            <span>{copy.eyebrow}</span>
            <h2>{copy.title}</h2>
          </header>
          <div className="auth-card__body">{panel}</div>
          <footer className="auth-card__footer">
            <span>Protected account access</span>
            <span aria-hidden="true">•</span>
            <span>EZ-PZ</span>
          </footer>
        </div>
        <p className="auth-legal">Operational data-handling guidance applies throughout this system.</p>
      </section>
    </main>
  );
};

export default AuthScreen;
