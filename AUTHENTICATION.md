# EZ-PZ account access

EZ-PZ supports Google sign-in and verified email/password accounts. The React
authentication gate prevents the planner from mounting before a session is
established, and the Flask API requires the same signed session for operational
terrain, weather, coordinate, export, threat, and saved-data endpoints.

## Local setup

1. Copy `backend/.env.example` to `backend/.env` and
   `frontend/.env.example` to `frontend/.env`.
2. Generate a unique `JWT_SECRET_KEY`. Never reuse the example value or commit
   the resulting `.env` file.
3. Start Flask on port 5000 and React on port 3000.
4. Keep `EMAIL_DELIVERY_MODE=console` locally. Verification and reset links are
   written to the Flask development log so the flow can be tested without an
   email account.

Manual accounts cannot sign in until their email is verified. Verification
links expire after 24 hours. Password-reset links expire after 60 minutes. Both
token types are stored as SHA-256 hashes, are single-use, and are consumed only
after an explicit user action.

## Resend email delivery

1. Create a Resend account and verify a dedicated sending domain or subdomain.
2. Configure SPF and DKIM using the DNS records Resend supplies; add a DMARC
   policy for the parent domain.
3. Create a send-only API key.
4. Set `RESEND_API_KEY`, `EMAIL_FROM`, `FRONTEND_URL`, and optionally
   `NEW_ACCOUNT_NOTIFY_EMAIL` as Fly secrets.
5. Do not set `EMAIL_DELIVERY_MODE=console` in production.

Fly startup intentionally fails if `RESEND_API_KEY` is missing or `EMAIL_FROM`
still uses Resend's test sender. This prevents registrations from appearing to
succeed when the activation email cannot be delivered.

The backend sends verification, welcome, password-reset, password-changed, and
optional new-account administrator notifications. API keys stay in Flask and
are never exposed to the React build.

Example Fly configuration (replace every placeholder before running it):

```powershell
fly secrets set JWT_SECRET_KEY="<random-secret>" GOOGLE_CLIENT_ID="<google-client-id>" RESEND_API_KEY="<resend-key>" EMAIL_FROM="EZ-PZ Account Services <security@notify.example.com>" FRONTEND_URL="https://app.example.com" CORS_ORIGINS="https://app.example.com" NEW_ACCOUNT_NOTIFY_EMAIL="<administrator-email>"
```

Vercel needs:

```text
REACT_APP_API_URL=https://api.example.com/api
REACT_APP_GOOGLE_CLIENT_ID=<google-client-id>
```

In Google Cloud, add `http://localhost:3000` and the production frontend URL as
authorized JavaScript origins for that web client. Configure the same client ID
as `GOOGLE_CLIENT_ID` on Fly.

Add the production frontend URL and any intentionally supported preview URL to
`CORS_ORIGINS` as a comma-separated list. Avoid a wildcard origin.

## Current security posture and next steps

The current bearer-token design is compatible with the existing Vercel/Fly
split and invalidates account sessions after a password reset. It is still a
prototype posture. Before handling CUI or other sensitive operational data:

- Add server-side logout/session revocation. Today logout removes the browser's
  token, but a copied token remains valid until its 24-hour expiry.
- Put the frontend and API on sibling custom domains and migrate sessions from
  browser local storage to short-lived Secure, HttpOnly cookies with CSRF
  protection and refresh-token rotation.
- Replace the single-process authentication limiter with a Redis-backed or
  edge-enforced distributed limiter before adding workers or Fly machines.
- Move the QR KMZ handoff store to Redis before adding workers or Fly machines.
  QR links now contain only an opaque token, expire after ten minutes, allow
  three downloads, and keep at most 64 MiB in the current backend process.
- Add roles, administrator approval or invitation policy, audit events,
  account suspension controls, and compromised-password screening.
- Move transactional email to a durable job queue before scaling the single
  Gunicorn worker or treating email delivery as mission-critical.
- Prefer passkeys/security keys and federated CAC/PIV access over SMS MFA.
  CAC integration should be performed through an approved OIDC/SAML identity
  provider or organizational ICAM service, not by reading CAC certificates in
  application JavaScript.

Authentication alone does not make Vercel/Fly hosting suitable for classified
information, CUI, or a DoD authorization boundary.

## SMS planning

There is no durable free production SMS tier in the United States. Trials are
appropriate only for development. For future notification-only SMS, Telnyx is
typically inexpensive; Twilio has the largest ecosystem. For authentication,
use a managed verification product rather than constructing OTP logic in this
repository, and retain SMS only as a fallback behind phishing-resistant MFA.
