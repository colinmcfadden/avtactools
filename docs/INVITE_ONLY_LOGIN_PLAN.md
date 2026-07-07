# Plan: Invite-Only Login (Approval Flag)

Status: not started — written 2026-07-06 as a reference for future work.

## Goal

Only approved users can use the app. Anyone else who signs in with Google sees
"Your account is pending approval — contact the administrator." The list is
managed inside the app, not in Google Cloud Console.

## Why in-app (decision record)

Google OAuth authenticates identity; authorization is the app's job. The two
console-side options were considered and rejected:

- **"Internal" app type** — only works if all users are in one Google
  Workspace org; personal Gmail accounts can't sign in.
- **"Testing" publishing status** — caps at 100 hand-managed test users,
  keeps the app "unverified", and rejected users get Google's generic
  `access_denied` page with no way to show a custom message.

The backend `google_auth` route is the single chokepoint: Google verifies the
identity, then our code decides whether to mint the app JWT. Nothing works
without that JWT, so one check covers the whole app.

An approval flag beats a pre-typed invite list: no collecting emails in
advance, no typos. Anyone who tries to log in shows up as "pending" and
approval is one click. It also gives a natural home for future roles.

## Changes

### 1. Backend — models.py

Add to `User`:

```python
is_approved = db.Column(db.Boolean, nullable=False, default=False)
is_admin = db.Column(db.Boolean, nullable=False, default=False)
```

Migration: `create_all` won't alter existing tables. Follow the existing
pattern in `app.py` (see the `picture` column backfill around line 66):

```python
ALTER TABLE user ADD COLUMN is_approved BOOLEAN NOT NULL DEFAULT 0
ALTER TABLE user ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT 0
UPDATE user SET is_approved = 1   -- grandfather all existing users!
```

**Do not skip the backfill** — without it every current user (including the
admin) is locked out. Set your own row's `is_admin = 1` manually (Neon/
Supabase console in prod, `ezpz.db` locally) — there's no in-app bootstrap.

### 2. Backend — routes/auth.py `google_auth`

After the user row is found/created, before `create_access_token`:

```python
if not user.is_approved:
    return jsonify({
        "status": "pending",
        "message": "Your account is pending approval — contact the administrator.",
    }), 403
```

First-time visitors still get a `User` row created (that's how they appear in
the pending list); they just don't get a token. Include `is_admin` in the
`/api/auth/google` and `/api/auth/me` user payloads so the frontend can show
the admin UI.

### 3. Backend — admin endpoints (new routes/admin_routes.py)

All `@jwt_required()` plus an admin check (helper or decorator):

- `GET /api/admin/users` — all users with `is_approved`, `is_admin`,
  email/name/picture, ordered pending-first.
- `PUT /api/admin/users/<id>` — set `is_approved` (approve/revoke). Refuse to
  revoke your own row so the last admin can't lock themselves out.

Register the blueprint in `app.py`.

### 4. Frontend — login flow

`feature/auth/AuthContext.jsx` calls `api.post("/auth/google", ...)`. On a
403 with `status: "pending"`, surface the server's `message` (alert or
inline text in the sign-in UI) instead of the generic failure path.

Note: the axios response interceptor in `feature/auth/api.js` treats 401 as
session-expiry — using 403 here keeps it out of that path. Keep it 403.

### 5. Frontend — admin UI (minimal)

Only rendered when `user.is_admin`:

- A "Users" entry point — options: item in the `UserMenu` dropdown, or a
  third tab in the existing Saved modal tab pattern
  (`feature/savedMaps/HistoryModal.jsx` — `.modal-tabs` styles already exist
  in `ExportModal.css`).
- List users (pending grouped on top) with Approve / Revoke buttons hitting
  the admin endpoints. Follow the row style used by the saved maps/routes
  lists.

The backend enforces the admin check; hiding the UI is cosmetic only.

## Testing checklist

- [ ] New Google account signs in → gets 403 + pending message, row appears in DB
- [ ] Admin approves → same account signs in successfully
- [ ] Revoke → account is back to the pending message on next login
- [ ] Existing (grandfathered) users unaffected
- [ ] Non-admin calling `/api/admin/*` gets 403
- [ ] Admin cannot revoke their own account

## Zero-effort fallback (rejected but noted)

A comma-separated `ALLOWED_EMAILS` env var checked in `google_auth`. No DB
change, but every list edit means updating a Fly secret and restarting.
Acceptable for ~5 users; painful beyond that.
