# Sending grids to people — plan

Status: **decided** (the owner, 2026-11-04): **build options 1 to 4** on the web and Android, and leave out 5 (ATAK). Options 1 to 3
need no server and go first; option 4's server work is laid out in §7 for the owner to see before it is built. On Android, the
notification of a grid sent in the app shows the grid (§3, option 4). Nothing here is built yet.

The owner asked to be able to "send grids to people quickly within the application (on both android and web)", and on Android "via other modes
(like airdrop or bluetooth or something)". This file lays out the ways to do it, what each costs, and a recommendation. §6 lists the questions
for the owner. Where this plan and the code disagree once it is built, the code wins (AGENTS.md §16).

---

## 1. What a shared grid says

Every way of sending below carries the same text, so the person receiving it can read it whatever they open it with:

```
LZ HAWK
16S GC 28864 55349
34.54568° N, 84.12346° W
https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK
```

- **The label** is optional: an LZ/PZ's name, a route point's, a threat's. A bare point under the crosshair has none.
- **The grid** is the app's own (`utils/mgrs.js` on the web, `MgrsConverter` on Android): five digits, truncated, the zone zero-padded, as
  PyGeodesy writes it (AGENTS.md §13). It comes first because crews work in MGRS.
- **The lat/long** is decimal degrees with hemisphere letters, five places (about a metre), which every EFB, ATAK and Google Maps reads.
  The app's own parser reads it back exactly (the target field's dropdown).
- **The link** opens EZ/PZ at the grid (§3, option 3). The grid goes after a `#`, not in a `?` query: a fragment is never sent to a server,
  so the place stays out of Vercel's and Cloudflare's logs.

Unclassified only (AGENTS.md §2): this is a place and a name, which the app already saves and syncs. Nothing about the sender goes with it.

## 2. Where it is offered

The same *Share* action wherever a single place is shown, so it is always a tap or two away:

| Where | Web | Android |
|---|---|---|
| The target field's dropdown (the as-you-type reading) | a share button on each row | the same, in the map's search |
| The map's long-press / right-click menu | beside *Copy grid* | beside *Copy grid* |
| The open LZ/PZ (its target, with its name) | the LZ/PZ panel | the LZ/PZ panel's card |
| A held route point, local point or threat | its inspector | its held bar |
| The grid readout under the crosshair | — | a long press on the pill (a tap already copies) |

## 3. The options

### Option 1 — The system share sheet (and *Copy*)

**Android:** an `ACTION_SEND` intent with `text/plain` (the text in §1), through `Intent.createChooser`. That one intent reaches everything
the phone has: **Quick Share** (Android's nearby sharing, phone to phone over Bluetooth and Wi-Fi Direct, **no signal or SIM needed**),
Bluetooth, SMS, Signal, WhatsApp, mail, ATAK's chat if installed, and *Copy*. Android has no AirDrop; Quick Share is its equivalent, and since
2024 it also reaches Windows PCs. (Android Beam, over NFC, was removed in Android 10.)

**Web:** `navigator.share({ title, text, url })` where the browser has it: Safari on iOS and macOS (which reaches **AirDrop**, Messages and
mail), Chrome and Edge on Android and ChromeOS, Edge and Chrome on Windows (Nearby Share and mail). Firefox on the desktop has none: there the
button is *Copy* (the Clipboard API), with a toast that says what was copied.

- **Cost:** about a day on each platform. **No dependency, no server work.** The app shares the same way already (route exports, `.ths`).
- **Offline:** yes on Android (Quick Share, Bluetooth). On the web, AirDrop works offline between Apple devices; the rest wants a signal.
- **Risk:** none new. The person picks the recipient in the system's own sheet; the app sends nothing itself.

### Option 2 — A QR code on the screen

The sender shows a QR code; another phone's camera reads it, offline, whatever its platform, with no pairing. Good for a cockpit or a
briefing room where nobody shares a network.

- **It carries the text in §1**, not only the link, so a phone with no signal still shows the grid and lat/long when its camera reads it
  (both iOS's and Android's cameras show a text code's words and make its link tappable).
- **Web:** free. The app already ships `qrcode` (the threat and ForeFlight exports draw their codes with it).
- **Android:** the app has **no QR library**. Two ways, either needs the owner's yes:
  - **ZXing core** (`com.google.zxing:core`, Apache 2.0, pure Java, about 550 KB before R8 shrinks it, the de facto standard). A new
    dependency (AGENTS.md §2).
  - **Vendor a small generator** (Nayuki's QR Code generator, MIT, one file, encode only), ported to Kotlin and held to the web's codes by
    decoding both in a test. No dependency; more code that is ours to keep correct.
- **Reading a code inside EZ/PZ** (so a scan opens the grid in the app rather than the camera app) needs a camera and a decoder (CameraX and
  ZXing or ML Kit; ML Kit needs Play services). **Not proposed now**: the phone's own camera app reads the code and its link opens EZ/PZ.
- **Cost:** half a day on the web; one to two days on Android, depending on the library choice.

### Option 3 — A link that opens EZ/PZ at the grid

`https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK`. The web reads the fragment as it loads (as it reads `?invite=` today,
`feature/missionPacks/inviteLink.js`), takes it out of the address bar, flies the map there and drops a marker with *Set as target*. A grid
that does not read says so in words. Nothing is created or saved until the person chooses.

- **Android:** the app's verified link filter already names `https://ezpztac.app/` (the emailed sign-in links). It opens the app at the grid
  **once the site serves `/.well-known/assetlinks.json`** for the app's package and signing key (an owner step that waits on the
  `applicationId`, AGENTS.md §17). Until then the link opens the browser, which opens the web app at the grid.
- **Signed out:** the grid waits through sign-in, as an invitation link does.
- **Cost:** about a day on the web, a day on Android. No server work. It is also what options 1 and 2 put in the text, so it is worth
  building with either.

### Option 4 — Sending to a person in the app

Two shapes, with very different costs:

- **4a. Into the open Mission Pack** as a one-point point set. **No server work**: packs already hold point sets, every member gets them
  through the pack's own sync, and the history says who added it. But it reaches only a pack's members, and **Android does not draw a pack's
  point sets yet** (`docs/ANDROID_MISSION_PACKS_PLAN.md`, *Deferred* 4), so it waits on that.
- **4b. To a teammate directly**, outside any pack: a new server table (sender, recipient, grid, label, sent, seen), routes to send, list and
  dismiss, the people search limited to teammates as it is today, an inbox in each app, and a new notification kind in the outbox
  (`docs/NOTIFICATIONS.md`). **On Android the notification shows the grid** (the owner, 2026-11-04): *Sam B. sent you a grid.* and, on a
  new line, the grid (`16S GC 28864 55349`), and a tap opens the app at the point. That is a deliberate exception to the outbox's rule that
  a row names people, packs and teams only (AGENTS.md §4, §7), because the grid is the whole message and it is unclassified: the row keeps
  the grid in its `fields`, it shows on the lock screen like every notification's words, and nothing else about the place (the label, the
  lat/long) goes in it. A new route the apps call is a minor API version (AGENTS.md §6).
- **Cost:** 4a about two days once Android draws a pack's point sets. 4b about a week across the server, the web and Android.
- **Offline:** neither. Both go through the server.

### Option 5 — ATAK

A point as a **KML placemark** (`.kml`, a few lines: name, description with the grid, the point) through the share sheet. ATAK registers for
KML and imports it as a marker; Google Earth and most map apps open it too. A **CoT** event (`.cot` XML) is ATAK's native form and keeps the
point's type, but nothing but TAK reads it, and ATAK's handling of a lone `.cot` from the share sheet varies by version: KML is the safer first
step. An ATAK data package (a zip with a manifest) is the plan's later work for routes and threats (`docs/NATIVE_APPS_PLAN.md`).

- **Cost:** about a day on Android (and a *Download .kml* on the web). No dependency.
- **Not verifiable here:** what ATAK makes of it. It wants one try on a device with ATAK installed.

## 4. Recommendation (as proposed; the owner chose 1 to 4, §6)

1. **Build options 1 and 3 together first**: the share sheet with *Copy* on both platforms, carrying the text in §1 with its link, and the
   web reading the link. That gives Quick Share and Bluetooth on Android and AirDrop from Apple devices, offline, in about three days, with no
   dependency and no server work. It answers the owner's request as asked.
2. **Then option 2, the QR code**: free on the web; on Android, the owner's choice of ZXing core or a vendored generator.
3. **Then option 5, KML to ATAK**, once someone can try it on a device with ATAK.
4. **Option 4 last**, and 4a before 4b: it needs a signal anyway, and 4a costs little once Android draws a pack's point sets.

## 5. What stays true whichever is built

- One function makes the text in §1 on each platform, held to a fixture (`contracts/fixtures/coords/share.json`, reference the web), so a
  grid shared from Android and one shared from the web read the same.
- The app never sends anything itself in options 1, 2, 3 and 5: the person chooses the recipient in the system's sheet or by showing their screen.
- A label is the person's own text: it is cut to a sensible length (60 characters, by characters, never splitting an emoji) and escaped in
  the KML and the link.
- Nothing is shared from a threat unless the person asks, as today.

## 6. Questions for the owner

1. ~~Which options to build, and in what order?~~ **Answered: 1 to 4; not 5** (2026-11-04).
2. For the QR code on Android: **ZXing core** as a dependency, or a **vendored generator**?
3. Should the shared text include the link at all while Android's verified links are not set up? (It still opens the web app.)
4. For option 4b, who may receive: teammates only (as the people search is today), or anyone in a pack with you too?
5. KML or CoT for ATAK, if anyone has seen what their ATAK build does with each?

## 7. Option 4: the server work

What the server needs so that a person can send a grid to someone else in the app, in plain terms. It is **option 4b**: a grid sent to
named people, apart from any pack. Option 4a (a grid put into the open pack as a one-point point set) needs no server work at all, and
can come later once Android draws a pack's point sets.

### 7.1 What is kept: one new table

**`shared_grid`**: one row for each person a grid is sent to. A grid sent to three people is three rows, so each recipient's copy
is read, seen and deleted on its own.

| Column | What it holds |
|---|---|
| `id` | the row's number |
| `send_uuid` | an id the sending device chooses for the send, so a send repeated after a lost answer makes no second copy (unique with `recipient_id`) |
| `sender_id` | who sent it (a user; see §7.8 for when that account is deleted) |
| `sender_name` | the sender's name as it was when sent, so the recipient still sees who it came from after the sender's account is gone |
| `recipient_id` | who it was sent to (a user; deleted with them) |
| `mgrs` | the grid, as the server writes it: five digits, truncated, zone zero-padded (PyGeodesy, as `/api/convert-grid` does) |
| `lat`, `lon` | the point, worked out by the server from the grid, never trusted from the client |
| `label` | optional, the sender's words (an LZ's name), at most 60 characters |
| `sent_at` | when it was sent |
| `seen_at` | when the recipient first opened it (null until then) |

An index on `(recipient_id, sent_at)` for the recipient's list. No migration framework is needed: `db.create_all()` makes a new table.
Nothing else changes in the existing tables.

### 7.2 The routes, and `contracts/openapi.yaml`

| Route | What it does |
|---|---|
| `GET /api/grids/recipients?q=` | People the caller may send to (§7.3), by name or sign-in email, as the people search answers: id, name, email |
| `POST /api/grids/send` | `{send_uuid, recipients: [user ids, 1 to 10], grid, label?}`. The server reads the grid itself (refused 400 `invalid_grid` if it does not read), checks every recipient (403 `not_allowed`, sending to nobody, if any one is not allowed), writes a row and a notification for each, and answers the rows. The same `send_uuid` again answers the same rows |
| `GET /api/grids/received` | The caller's grids from the last 30 days, newest first: sender's name, grid, lat/long, label, when sent, whether seen. With an `ETag` and a 304 when nothing changed, as the notifications feed has |
| `POST /api/grids/received/<id>/seen` | Marks one seen (the badge goes; the notification is withdrawn) |
| `DELETE /api/grids/received/<id>` | The recipient deletes their copy |

All need a sign-in and the affiliation gate, as every route does, and a feature key, **`grid_sharing`**, in `entitlements.DEFAULT_OFF`
until it has been tried (off for everyone but admins and ticked testers, as `mission_packs` was; launching is taking it out of the list).

**In `contracts/openapi.yaml`**: the five routes, their bodies and their refusals go in under a new tag, *Shared grids*, recorded from the
real server by `backend/tests/test_network_fixtures.py` like every other route. The notification gains a type, `grid_shared`, and an
optional `grid` field (§7.4). All of it is **additive** (new routes, a new optional field; the notification's `type` is already a free
string that clients pass over when they do not know it), so the API's version goes from **1.3.0 to 1.4.0**, a minor. `api_version.py
check` in CI confirms it, and no app in the field has to update.

### 7.3 Who may send to whom

Only to someone the sender already works with: **someone in a team with them, or in a Mission Pack with them** (any role, the pack not
deleted). That is the people search's rule (`/api/users/search`, teammates only) widened to pack members, because a pack's members are
exactly who a crew sends grids to. Nobody can send to a stranger, look an account up by guessing an email, or learn whether an address has an
account: the recipients route lists only people already in a team or pack with the caller, and `send` refuses anyone else without saying why.
A suspended or deleted account is never listed and never receives. One cannot send to oneself.

### 7.4 How the recipient hears of it

Through the existing notification outbox (`notify.py`, `docs/NOTIFICATIONS.md`): `send` adds one `grid_shared` notification for each
recipient in the same transaction as the row, so a send that fails notifies nobody. It collapses by row (`grid_shared:<id>`), is grouped
under a new setting, **Grids sent to you** (on by default, the recipient may turn it off), and counts toward the existing ten-an-hour cap
on alerts.

**The words, on Android:** *Sam B. sent you a grid.* and, on a new line, the grid (`16S GC 28864 55349`). A tap opens the app at the point.
**This is the owner's decision of 2026-11-04 and an exception** to the outbox's rule that a notification names people, packs and teams
only (`notify.py`'s header, AGENTS.md §4): the grid is the message, it is unclassified, and it shows on the lock screen like every
notification's words. So the notification row carries the grid in its `fields` (the label and the lat/long do not go in it); `notify.py`'s
header and AGENTS.md say so. **The web** shows it in the app only, as its notifications do (N3), with the label too.

When it is built, AGENTS.md §4 and §7 (and `notify.py`'s header) record `grid_shared` as the one exception to the rule of
2026-10-28, by the owner's decision of 2026-11-04, in the same PR, so that nobody "fixes" it back.

### 7.5 How long a grid is kept

**30 days** from when it was sent, as notifications are (`notify.KEPT_FOR`), whether or not it was seen. The recipient may delete one sooner.
The clean-up is the outbox's own: a sweep, at most once an hour, from whichever request comes, deletes rows older than that (as
`notification_routes._sweep` does); nothing else needs scheduling. A grid a recipient saves (as a target, an LZ/PZ, a local point) is theirs
in their Library and is not touched by the clean-up.

### 7.6 Rate limits

On the existing limiter (`auth_rate_limit.check_rate_limits`, in-process, as the invitation limits are): **at most 10 recipients a send,
30 sends a minute and 200 recipients an hour** for one sender, refused 429 `rate_limited` with `Retry-After`. Enough to send a grid to a
whole flight several times over during planning; not enough to flood anyone. The ten-an-hour alert cap means a recipient's phone sounds at
most ten times an hour whatever is sent.

### 7.7 A recipient with no signal

Nothing is pushed (the owner's decision for notifications: Android polls every 15 minutes, the web asks in the app). A grid sent to someone
with no signal waits on the server, for 30 days, until their device next reaches it: Android's next poll, or opening the app, shows it then.
**The sender is told that a send is delivered to the server, not that it was seen**; the sent grid's `seen_at` is the recipient's, not
shown to the sender in this step. A sender with no signal cannot send through the app at all (the button says so), and is offered the share
sheet instead (option 1, which works over Quick Share and Bluetooth with no signal). A device that has fetched its received grids keeps them
on the device (Android in its database, wiped at sign-out), so they can still be opened and flown to with no signal.

### 7.8 Deleting an account

`pack_support.release_account`, which both deletion paths call first, gains one step:
- **Grids sent to the account being deleted** are deleted, with their notifications (`notify.forget_account` already removes those).
- **Grids the account sent** stay with their recipients, who may still need them: their `sender_id` is cleared and `sender_name` keeps
  who it was, as a pack's history keeps the name of someone who left. The 30-day clean-up removes them in time.

`sender_id` therefore has no `ON DELETE` of its own, as `mission_pack.owner_id` has none: `release_account` handles it, and a test
deletes an account that has sent and received grids.

### 7.9 Size of the work

The server: the table, the five routes, the notification type and setting, the rate limits, `release_account`'s step, the OpenAPI entries
and recordings, and tests (who may send, idempotency, clean-up, deletion, the 429s): about three days. Then the web (a *Send to…* choice
beside *Share*, the picker, a received-grids list) and Android (the same, with the notification's words and the received list in Room):
about two days each.
