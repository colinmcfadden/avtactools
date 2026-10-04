# EZ-PZ account access

EZ-PZ supports Google sign-in and verified email/password accounts. The React
authentication gate prevents the planner from mounting before a session is
established, and the Flask API requires the same signed session for operational
terrain, weather, coordinate, export, threat, and saved-data endpoints.

## Local setup

1. Put any local settings in the gitignored `backend/.env` and `frontend/.env`
   files. There are no checked-in example files; `AGENTS.md` lists the settings.
2. Generate a unique `JWT_SECRET_KEY`. Never reuse a deployed value or commit
   the resulting `.env` file.
3. Start Flask on port 5000 and React on port 3000.
4. Keep `EMAIL_DELIVERY_MODE=console` locally. Verification and reset links are
   written to the Flask development log so the flow can be tested without an
   email account.

For a repeatable local account without completing email and affiliation flows,
seed the local SQLite database explicitly. The command prompts for a password,
uses the normal password-login/session path afterward, and refuses to run when
production is declared or when the database is not SQLite:

```powershell
cd backend
python -m flask --app app create-dev-user
```

The default address is `pilot@local.ezpz.test`. For the Android emulator, build
the debug app against the local backend (the production API remains the default
when this property is omitted):

```powershell
cd android
.\gradlew.bat installDebug "-Pezpz.apiUrl=http://10.0.2.2:5000/"
```

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

Production startup (Fly, or any host with `TRUSTED_PROXY` or
`APP_ENV=production` set) intentionally fails if `RESEND_API_KEY` is missing or
`EMAIL_FROM` still uses Resend's test sender. This prevents registrations from
appearing to succeed when the activation email cannot be delivered.

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

## Native apps (Android, iOS)

The native apps use the same endpoints, plus a few that exist for them. They are
identified by `X-EZPZ-Client: android/1.4.0 (212)` (`backend/client_header.py`).

- **Google client IDs.** An ID token's audience is the client ID of the app that
  asked Google for it, so the web, Android and iOS clients each have their own.
  `GOOGLE_CLIENT_ID` (the web's) and the comma-separated `GOOGLE_CLIENT_IDS` are
  all accepted. The audience passed to google-auth is always a non-empty list:
  `None` would skip the check, and an empty list rejects everything.
- **Refresh tokens, native only.** A crew at a FARP cannot sign in every 24 hours,
  so an android or ios client is also given a refresh token (`POST
  /api/auth/refresh`). The web is not: its token sits in `localStorage` where any
  script can read it, and a 30-day token there would be a worse trade than the
  24-hour one.
  - Stored as SHA-256 hashes, like every account token (`AccountToken`, purpose
    `refresh`). Each use spends the token and returns the next; the chain is one
    signed-in device (a *family*).
  - Alive 30 days from each refresh, ending 180 days after sign-in.
  - A spent token presented again within 30 seconds is read as a lost response (the
    phone was in a dead zone) and answered again. After that it is read as a copy:
    the whole family is revoked (`refresh_reuse_detected`).
  - A password reset, an admin suspension, and a revoke all end it.
- **Revocation is immediate.** A native access token carries its family as `sid`,
  and every request checks the family still has a live token
  (`token_revocation.is_revoked`). So `POST /api/auth/logout` and `DELETE
  /api/auth/sessions/<id>` (a lost phone, revoked from a tablet) take effect at
  once instead of when the 24-hour access token lapses. `GET /api/auth/sessions`
  lists the devices.
- **Revoke must be final.** Revoking a family expires every token in it. Marking
  them merely *spent* would leave the newest one good for the 30-second lost-response
  window, which is how this was first written; `tests/test_native_sessions.py` has the
  case.
- **Account deletion** (`DELETE /api/auth/me`), which the app stores require. It
  wants the word `DELETE` and proof of ownership *now*: the password, or a fresh
  Google ID token. An access token alone is not proof, because a copied refresh
  token can mint one. The super-admin cannot be deleted. It removes saved LZs,
  routes, point sets, custom aircraft profiles, sign-in history and every token.

The contract for these routes is `contracts/openapi.yaml`, and
`backend/tests/test_openapi_contract.py` and `test_native_sessions.py` hold the
responses to it. Changes are additive: an installed app cannot be updated.

## Current security posture and next steps

The current bearer-token design is compatible with the existing Vercel/Fly
split and invalidates account sessions after a password reset. It is still a
prototype posture. Before handling CUI or other sensitive operational data:

- Add server-side logout/session revocation **for the web**. The native apps have
  it (above); on the web, logout still only removes the browser's token, and a
  copied token remains valid until its 24-hour expiry.
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
