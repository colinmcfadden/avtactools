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
seed the local SQLite database explicitly. The script prompts for a password,
uses the normal password-login/session path afterward, and refuses to run when
production is declared or when the database is not SQLite:

```powershell
cd backend
python dev_user.py pilot@local.ezpz.test
```

For the Android emulator, build
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
- **Refresh tokens.** A crew at a FARP cannot sign in every 24 hours, so an
  android or ios client is also given a refresh token (`POST /api/auth/refresh`),
  kept in the device's encrypted store. The web has one too, but only as a cookie
  no script can read (*The web's lasting sign-in*, below).
  - Stored as SHA-256 hashes, like every account token (`AccountToken`, purpose
    `refresh`). Each use spends the token and returns the next; the chain is one
    signed-in device (a *family*).
  - **A sign-in lasts while the account is active**, as ForeFlight's does: a family
    lives 30 days from each refresh and has no other limit. (It used to end 180
    days after sign-in, `refresh_expired`; the owner dropped that on 2026-10-28.)
    A device unused for 30 days signs in again.
  - A spent token presented again within 30 seconds is read as a lost response (the
    phone was in a dead zone) and answered again. After that it is read as a copy:
    the whole family is revoked (`refresh_reuse_detected`).
  - What ends one: a password reset (the `sv` claim), an admin suspension, deleting
    the account, signing it out (`POST /api/auth/logout`, or another device's
    `DELETE /api/auth/sessions/<id>`), 30 days unused, and a reused token after the
    grace period. Each takes effect at once (below).
- **No offline limit on Android**, as ForeFlight has none (the owner, 2026-10-28).
  A device stays signed in however long it is without signal; its plans are on the
  device. It used to sign out after 14 days without the server (`OfflineGrace`),
  which locked a crew out of planning with no way to sign in where it was. When the
  device next reaches the server, a session unused for 30 days, suspended, reset or
  signed out elsewhere is refused and the person signs in again. The cost: an
  account suspended while a device is offline keeps working on that device, with
  what is already on it, until the device reaches the server.
- **Revocation is immediate.** A session's access token carries its family as `sid`,
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

## The web's lasting sign-in

The web used to keep one 24-hour access token in `localStorage` and sign in again
every day. Now:

- **The refresh token is an HttpOnly cookie.** A web sign-in that sends
  `X-EZPZ-Session: cookie` (`POST /api/auth/login` or `/google`) starts a family
  (client `web`) and sets `ezpz_refresh`: `HttpOnly; Secure; SameSite=None;
  Partitioned; Path=/api/auth; Max-Age=2592000` (`backend/refresh_cookie.py`). The
  body never carries it. A sign-in without the header (an older bundle, a script)
  is answered as it always was.
- **The access token lives in the page's memory**, 15 minutes at a time
  (`frontend/src/feature/auth/session.js`). A reload, or a new tab, starts with
  none and asks `POST /api/auth/refresh` with the header and the cookie; the answer
  is a new access token and the next cookie. The page refreshes two minutes before
  a token lapses, and a 401 is answered by one refresh and the request sent again.
  `ezpz.session` in `localStorage` says a cookie session exists (no secret) and
  carries a sign-out to the other tabs.
- **One refresh at a time across tabs** (Web Locks, `navigator.locks`). Every
  refresh spends the one cookie the browser holds; two tabs refreshing at once
  could leave the browser holding the token that lost, which, used after the grace
  period, would read as a copy and end the session.
- **The custom header is the CSRF guard.** The cookie is `SameSite=None`, because
  the web and the API are on different sites, so the browser would send it with a
  request from any page. A form cannot add a header, and a page's script cannot
  send one cross-origin without a CORS preflight, which the API answers only for
  `CORS_ORIGINS`. Without the header the cookie is not read at all. A web token is
  refreshed only by its cookie and a native one only in a body.
- **Credentials only under `/api/auth`** (`backend/cors_config.py`): those routes
  answer `Access-Control-Allow-Credentials: true` to the listed origins and never
  to `*`; every other route is unchanged and the cookie is never sent to one.
  Because credentials are answered, the list must be exact: a preview pattern must
  match this project's preview names only (anchored, as staging's is), never every
  `*.vercel.app` page.
- **Partitioned** (CHIPS) keeps the cookie for the site the app was opened on: a
  browser that blocks third-party cookies (Chrome's setting) may still keep it, and
  another site, another Vercel preview included (`vercel.app` is a public suffix),
  is never sent it.
- **Signing out** revokes the family and clears the cookie (`POST /api/auth/logout`
  with credentials). A new sign-in revokes the family of the cookie it replaces.
  Web sessions are listed in Sessions as `web` and can be signed out from a phone.
- **Asked for only of a server that offers it.** Before a sign-in the page reads
  the public `/api/config` once, and asks for a cookie session only from API 1.2
  on: an older server answers no request made with credentials, so a web deployed
  ahead of its server would otherwise have every sign-in refused by the browser.
  Either may go live first.
- **A refresh that met no answer is asked again at once**, and the page's own
  retries come every 10 seconds: a request that reached the server spent the
  cookie the browser still holds, and the server forgives that one for 30 seconds.
- **A browser that will not keep the cookie** is found out by one refresh right
  after signing in (`401 refresh_cookie_missing`). It keeps the sign-in's 24-hour
  access token in `localStorage` (`auth_token`), as the web always did, now with a
  `sid`, so signing out ends it on the server too. That is Safari today (below).

### Hosting the cookie (owner steps)

- **Safari needs the API on the web's site.** Safari keeps no third-party cookie,
  Partitioned or not. While the web is `ezpztac.app` and the API
  `prod-ezpz-api.mcfadd.in`, the cookie is third-party: Chrome, Edge and Firefox
  keep it, Safari (every iPad) falls back to the daily sign-in. Serving the API at
  **`api.ezpztac.app`** makes the cookie first-party everywhere: add the hostname to
  the Cloudflare Tunnel (as `admin.ezpztac.app` already reaches the backend), point
  Vercel's Production `REACT_APP_API_URL` at `https://api.ezpztac.app/api`, and keep
  `CORS_ORIGINS=https://ezpztac.app`. Nothing in the code changes. Staging can do the
  same (`dev-api.ezpztac.app`); Vercel's previews (`*.vercel.app`) stay cross-site.
- **Cloudflare must not cache `/api/auth/*`** and must pass `Set-Cookie` through;
  it does both by default for a `POST`. Nothing else is needed at the edge.
- **HTTPS end to end of the browser's view.** The cookie is `Secure`. Behind the
  tunnel the browser sees HTTPS, which is what counts. Locally, Chrome and Firefox
  keep a Secure cookie from `http://localhost`/`127.0.0.1`; a browser that does not
  falls back to the stored token, which is what local development did anyway.
- Existing web sign-ins are 24-hour tokens with no session; each signs in once more
  when it lapses, and from then on stays signed in.

## The .mil affiliation gate

A signed-in account reaches only `/api/auth/*` until it clears the gate
(`backend/affiliation_gate.py`): an admin approves it, or it proves a `.mil`
inbox. `POST /api/auth/mil/request` emails an 8-character code (30 minutes; a
new request spends the last one) and `POST /api/auth/mil/verify` takes it.

- **An address asked for only waits** (`user.mil_pending_email`). `mil_email` is
  only ever an address a code confirmed, because it is trusted: an emailed
  mission-pack or team invitation reaches the account at its sign-in address and
  at its confirmed `.mil` one (`pack_support.addresses`), so it can see and accept
  invitations sent there. The request once wrote `mil_email` and kept
  `mil_verified_at`, so an account that had verified one address could ask a
  code for anyone else's, never enter it, and be handed their invitations.
- **The code proves the address it was sent to**, the one pending; entering it
  moves that address to `mil_email` and clears the pending one.
- **Asking for a new code never closes the gate** on an account already through
  it: `mil_verified_at` stays, and so does the address it confirmed, until the new
  address's code is entered.
- **One address, one account.** A request is refused (409) when another account
  has verified the address. Entering the code is also refused when another account
  signs in with it and has proven it (a confirmed email link, or Google). That is
  asked only once the code has proven the caller holds the inbox: asked at the
  request, it would tell anyone signed in which `.mil` addresses have an account.
  A sign-up never confirmed counts for nothing, so it cannot keep an address from
  its owner.
- **Rows the old flow left are put right at startup**
  (`keep_unconfirmed_mil_addresses_pending`, from `app.py`): an address never
  verified, and one asked for after the account last verified (a `mil_verify`
  code not spent by a verification), move from `mil_email` to pending. Which
  address such an account had verified was never kept, so neither is trusted
  until a code is entered again; the account stays through the gate. It is safe on
  every start and logs how many it moved.

## Current security posture and next steps

The current bearer-token design is compatible with the existing Vercel/Fly
split and invalidates account sessions after a password reset. It is still a
prototype posture. Before handling CUI or other sensitive operational data:

- Put the frontend and API on sibling custom domains (`api.ezpztac.app`, above),
  so Safari keeps the web's refresh cookie too and no browser falls back to a
  token in `localStorage`. Then the cookie could be `SameSite=Lax`.
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
