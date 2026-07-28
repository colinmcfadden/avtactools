import React, { useState } from "react";
import { useAuth } from "./AuthContext";

const errorText = (err, fallback) =>
  err?.response?.data?.message ||
  err?.response?.data?.error ||
  err?.message ||
  fallback;

/**
 * Shown when a user is signed in but hasn't cleared the military-affiliation
 * gate. They verify control of a .mil address via an emailed code; on success
 * the user object's `access_ok` flips true and AuthGate renders the app.
 */
const AffiliationScreen = () => {
  const { user, requestMilCode, verifyMil, logout } = useAuth();
  const [step, setStep] = useState(user?.mil_email ? "code" : "email");
  const [email, setEmail] = useState(user?.mil_email || "");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const sendCode = async (address) => {
    await requestMilCode(address);
    setNotice(`A code was sent to ${address}. Enter it below — it expires in 30 minutes.`);
    setStep("code");
  };

  const submitEmail = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await sendCode(email.trim());
    } catch (err) {
      setError(errorText(err, "Couldn't send a code. Check the address and try again."));
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await verifyMil(code.trim().toUpperCase());
      // On success the user's access_ok flips true and AuthGate shows the app.
    } catch (err) {
      setError(errorText(err, "That code is invalid or has expired."));
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await sendCode(email.trim());
    } catch (err) {
      setError(errorText(err, "Couldn't resend the code. Try again shortly."));
    } finally {
      setBusy(false);
    }
  };

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
          <div className="auth-kicker"><span /> Verify your affiliation</div>
          <h1>One more step</h1>
          <p>
            Access is limited to Army/DoD personnel. Verify control of a{" "}
            <strong>.mil</strong> email address to continue. If you can't receive
            mail at your .mil account, an administrator can approve you instead.
          </p>
        </div>
      </section>

      <section className="auth-access-panel">
        <div className="auth-card">
          <header className="auth-card__header">
            <span>MILITARY AFFILIATION</span>
            <h2>Verify .mil email</h2>
            <p>Signed in as {user?.email}.</p>
          </header>
          <div className="auth-card__body">
            {error && (
              <div className="auth-feedback" role="alert"><span>{error}</span></div>
            )}
            {notice && (
              <div className="auth-feedback auth-feedback--info" role="status"><span>{notice}</span></div>
            )}

            {step === "email" ? (
              <form className="auth-form" onSubmit={submitEmail}>
                <label className="auth-field">
                  <span className="auth-field__label-row"><span>.mil email address</span></span>
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="name@army.mil"
                    autoComplete="email"
                    required
                    autoFocus
                  />
                  <small>Any .mil address works (army.mil, mail.mil, us.army.mil, …).</small>
                </label>
                <button className="auth-submit" type="submit" disabled={busy}>
                  {busy ? "Sending…" : "Send verification code"}
                </button>
              </form>
            ) : (
              <form className="auth-form" onSubmit={submitCode}>
                <label className="auth-field">
                  <span className="auth-field__label-row"><span>Verification code</span></span>
                  <input
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    placeholder="8-character code"
                    autoComplete="one-time-code"
                    required
                    autoFocus
                    style={{ textTransform: "uppercase", letterSpacing: "0.18em" }}
                  />
                  <small>Sent to {email}. Expires in 30 minutes.</small>
                </label>
                <button className="auth-submit" type="submit" disabled={busy}>
                  {busy ? "Verifying…" : "Verify affiliation"}
                </button>
                <div className="auth-form-links">
                  <button className="auth-text-action" type="button" onClick={resend} disabled={busy}>
                    Resend code
                  </button>
                  <button
                    className="auth-text-action"
                    type="button"
                    onClick={() => { setStep("email"); setError(""); setNotice(""); }}
                  >
                    Use a different address
                  </button>
                </div>
              </form>
            )}
          </div>
          <footer className="auth-card__footer">
            <button className="auth-text-action" type="button" onClick={logout}>
              Sign out
            </button>
          </footer>
        </div>
      </section>
    </main>
  );
};

export default AffiliationScreen;
