# Notifications — plan

Status: **decided; N0, the server, built** (2026-10-28). Android (N1) is next. The owner asked for notifications that are few and useful: a Mission Pack invitation
and a pack finished, and "a couple more" that earn their place. This file proposes which ones, how they reach Android first and then the
web, and what the server keeps. **§10 holds the owner's answers** (2026-10-28): Android polls every 15 minutes, the web is in-app only, the
list stands as §2 has it, and the names always show on a lock screen. Only a new-device sign-in email (question 6) is still open. Where this plan and
the code disagree once it is built, the code wins (AGENTS.md §16).

---

## 1. What it is

A notification tells someone that **something happened to them** that they would otherwise not know until they next opened the app and
looked in the right place, and that **they will want to act on or know before they plan**. Everything else stays in the app, where it
already is: the switcher lists waiting invitations, a finished pack has its banner, a pack's history says who changed what.

The test each candidate below is held to:

1. **It is about the person**, not about a pack's activity (nobody wants "Sam moved a doghouse").
2. **It is rare**: a few a week for a busy planner, never a stream.
3. **Something waits on it**: an answer (an invitation), a change of plan (the pack is final), or access (the app opens up).
4. **The app would not otherwise tell them in time.**

Email stays as it is: it is how an invitation reaches someone with no account, and nothing here replaces it or adds a new email.

---

## 2. Which notifications

### 2.1 Proposed for the first release

| # | Notification | Who gets it | Text (example) | Why it earns its place |
|---|---|---|---|---|
| 1 | **Invited to a Mission Pack** | The invitee, if the address is an account's (its sign-in or verified `.mil` address, `pack_support.addresses`) | *Sam B. invited you to OP DK as an editor.* | The owner's first. The pack's owner is waiting on an answer. |
| 1a | **Added to a Mission Pack** (a teammate added directly, `add_member`) | The person added | *Sam B. added you to OP DK as an editor.* | The same moment as an invitation, with no answer needed; today it is an email (`send_pack_added_email`). |
| 2 | **Invited to a team** | The invitee, as 1 | *Sam B. invited you to the team 1-171 AVN.* | A team is the way into many packs, and the inviter waits on it. Rarer than 1. |
| 3 | **A pack you're in is finished** | Every member but the person who finished it | *OP DK was finished by Sam B. It is now read-only.* | The owner's second. The plan is final: brief from it, stop changing it. Debounced (§2.3). |
| 4 | **A pack handed to you** (`change_member` to `owner`) | The new owner | *Sam B. made you the owner of OP DK.* | It gives them a duty (finish, reopen, invite) and happens once in a pack's life. |
| 5 | **Access approved** (an admin clears the affiliation gate, `admin_routes.set_access_approval`) | The person approved | *Your EZ/PZ access was approved.* | A person waiting for this has nothing else to tell them; they signed up and were stopped at the gate. |
| 6 | **Changes kept in your Library** (Android only, made on the device) | This device's person, **only when the app is in the background** | *Some changes to OP DK couldn't be sent and were kept in your Library.* | The one case where a person's own work went somewhere they did not put it, with nobody looking (§2.4). |

Grouped for settings (§5) into **Invitations** (1, 1a, 2), **Finished packs** (3), **Packs handed to you** (4) and **Changes kept** (6).
Access approved (5) has no setting: it happens once in an account's life, to someone who cannot yet reach the settings.

### 2.2 Weighed and left out

| Candidate | Why not |
|---|---|
| **A pack reopened** | It undoes nothing for the members: the pack takes edits again, and the first thing they would do with it (open it) shows that. The common case is an owner who finishes, sees a mistake and reopens within minutes: two alerts for nothing. Instead a reopen **cancels a finish not yet sent** (§2.3). |
| **Role changed** (viewer ↔ editor) | Rare, and the pack says it the moment they open it: a viewer made an editor finds the tools there. Not worth an alert. |
| **Removed from a pack** | A person removed has nothing to do about it, and the app already says the pack is no longer available if it was open. An alert reads as a reprimand. |
| **A 3D build ready** | Built for a place before streaming; now **the place streams** wherever USGS has a survey (`lidar/stream.py`, AGENTS.md §9 option H), first points in about 2 s, and the web builds nothing on save where it streams. A build is also the place's, not a person's: its watcher is an opaque id with no account, so the server cannot say whose it is. And nobody asked to be told: a build on save runs with no one waiting on it. |
| **Sign-in from a new device** | Worth having, but it is **email's job**: an alert must reach the person even when the device is the thief's, and a push goes to the account's devices, the new one included. Proposed as a separate small email (§10, question 6), not here. |
| **Signed out from elsewhere** | The device signed out cannot be told: its session is gone, so it cannot poll, and a push token is let go with it. It already finds out on its next call and says why (`SessionStore.end`, AGENTS.md §17). |
| **Someone joined a pack you invited them to** | Tempting, but a pack's owner who invites ten people gets ten alerts. The Members page and the pack's history say it. A later candidate if owners ask for it, collapsed per pack. |
| **Every edit, presence, sync progress, a refused edit while the app is open** | Never. The app says these where they happen (toasts, the Pack panel, the history). |

### 2.3 Collapse, debounce and limits

- **A finish waits two minutes** before it is sent (`deliver_after`), and **a reopen inside that time cancels it**. After it has gone,
  a reopen sends nothing (§2.2), and takes the finish down on every device, since it is no longer so. Finishing again later sends a new one.
- **One pack, one finish**: a finish replaces an earlier one for the same pack (`collapse_key = pack_finished:<pack>`).
- **One invitation, one alert**: a resend (`resend_pack_invite`, or inviting the same address again) updates the notification it already
  made, seen or not, and does not alert again unless the last alert is more than a day old. A withdrawn or answered invitation **withdraws its
  notification** on every device (§3.3). Anything else under the same key is news again (added back after leaving, a pack handed back): the
  earlier one is withdrawn and a new one alerts.
- **An invitation to what the person is already in** (added by name, or joined the team by its link, before answering) is no longer listed.
- **Several at once are one group**: on Android, notifications that arrive in one poll are posted as a group with a summary (*3 invitations*),
  alerting once; on the web, one toast says them all.
- **At most 10 alerts an hour for one person**; past that they are delivered quietly (listed, no sound or banner). What counts is what
  alerted: delivered, not quiet, and not withdrawn before it was (a finish reopened in time never alerted). The senders are already limited
  (`pack_invite:user`, 30 an hour).
- **Nothing for the person who did it**: the owner who finishes is not told the pack is finished.
- **Pack notifications need `mission_packs`**: until it leaves `entitlements.DEFAULT_OFF`, only admins and ticked testers get 1, 1a, 2, 3, 4
  and 6, as only they can open a pack.

### 2.4 Changes kept in your Library (Android only)

When the app is in the front, the pack's toast already says it (`PackNotice.Refused`, `Kept`). When it is not (the background drain,
`EngineSyncRunner`, keeps what a pack refused as *NAME (my edits)*), nobody is looking, and the work sits in the Library unannounced. So the
drain posts one local notification **per pack per run**, naming the pack only. It never names the item: an item's name is whatever its
author typed and may be a place (*LZ 16S GC 3120*), which must not reach a lock screen (§6). Tapping it opens the Library. No server and no
web part: the web has no background.

---

## 3. The server

### 3.1 An outbox, written with the event

One table, `notification`, written **in the same transaction as the change that causes it**, so a refused or rolled-back change notifies
nobody:

| Column | Holds |
|---|---|
| `id` | |
| `user_id` | Who it is for. Deleted with the account (both deletion paths). |
| `type` | `pack_invite`, `pack_added`, `team_invite`, `pack_finished`, `pack_owner` |
| `fields` | JSON: `{actor: {name}, role}`, who did it as they were named then, and a role. The pack's and the team's names are read when the feed is asked for, so none outlives a pack or team deleted. Never an item, a place, a grid or a token (§6). |
| `collapse_key` | §2.3 |
| `pack_uuid`, `invite_id` | Kept apart from `fields`, so a pack deleted or an invitation answered can withdraw what it made |
| `quiet` | Past ten in the person's hour (§2.3) |
| `created_at`, `updated_at`, `deliver_after` | A row is delivered once `deliver_after` has passed; `updated_at` moves when an invitation is sent again the same day |
| `withdrawn_at` | A reopen, an answered or revoked invitation, a pack deleted, a team's invitations deleted with it |
| `seen_at` | Set by any device once the person has seen or acted on it; every other device then takes it down |

**What is listed is only what is still so** (`notification_routes._still_so`), whatever was withdrawn: an invitation still waiting, unexpired and
addressed to them; a pack still there with them in it, still finished, still theirs; under the names the pack and the team have now. So a person
removed from a pack, a pack unshared from their team or handed on again, an invitation expired, all leave the list with nothing written, and a
code path that forgets to withdraw cannot leave a false notification. Rows older than 30 days are deleted by a sweep run with the feed (at most
once an hour, whoever polls). There is no migration framework: the table is new, so `db.create_all()` makes it (AGENTS.md §7).

Access approved (5) needs **no row**: the gated person can reach only `/api/auth/*` (`affiliation_gate.py`), and `GET /api/auth/me` already
says `access_ok`. The device that saw the gate notices it lift (§4.4, §7.2). Changes kept (6) is the device's own.

### 3.2 What makes each one

| Notification | Where | Notes |
|---|---|---|
| Pack invitation | `pack_routes.invite_to_pack`, `resend_pack_invite` | Only for an address that is an account's. One with no account gets the email alone and finds the invitation in the switcher once in. |
| Withdrawn | `revoke_pack_invite`, `team_routes.revoke_team_invite`, `_accept`, `decline_invite`, `pack_support.delete_team` | `withdrawn_at` on the invitation's notification |
| Added | `pack_routes.add_member` | |
| Team invitation | `team_routes.invite_to_team` (with an email) | A link invitation (no email) names nobody and makes none |
| Finished | `pack_routes.finish_pack` | Everyone in the pack (`notify.people_in`: its members and its team's) but the actor; `deliver_after` two minutes |
| Reopened | `pack_routes.reopen_pack` | Withdraws the pack's finishes: one not yet sent is never sent, one sent is taken down; makes nothing |
| Handed over | `pack_routes.change_member` to `owner`; `pack_support._hand_on` (an account deleted: its heir is told, with no actor) | |
| Pack deleted | `pack_routes.delete_pack`, `pack_support._purge_pack` (an account deleted with nobody else in its pack) | Withdraws everything about it |
| Account deleted | `pack_support.release_account` (both deletion paths) | Its own notifications go (`notify.forget_account`) |

Each is a call to one helper (`notify.add(user_id, type, pack=…, team=…, actor=…, invite=…, role=…, delay=…)`) in `backend/notify.py`,
beside `email_service.py`, so the routes stay as they read. It names models only, so `pack_support` calls it too.

### 3.3 Routes (described in `contracts/openapi.yaml`)

All need a bearer JWT and the affiliation gate; none needs `mission_packs` (a person without it simply has no pack rows).

| Route | Does |
|---|---|
| `GET /api/notifications` | The caller's deliverable, unwithdrawn notifications of the last 30 days, newest first, each with `seen`, and the caller's settings (§5). Answers an `ETag` of the body; `If-None-Match` with nothing new is a 304, so a poll that finds nothing costs a few hundred bytes. Compared weakly, since Cloudflare hands a compressed body's tag on as `W/"…"`. `Cache-Control: private` (AGENTS.md §14). |
| `POST /api/notifications/seen` | `{ids}`: seen, on every device |
| `PUT /api/notifications/settings` | `{type: bool, …}`: §5 |

Each notification is flat: `id`, `type`, `group` (its setting), `key` (its collapse key, which an Android notification can be tagged with),
`pack` and `team` (`{uuid, name}`, `{id, name}`, as they are now), `actor` (`{name}`, as it was then), `invite_id`, `role` (the invitation's,
or the one a person was added with), `created_at`, `updated_at`, `seen` and `quiet`. Neither `type` nor `group` is an enum in the spec: a
client passes over one it does not know, so a new kind is additive. A setting this server does not know is passed over by the `PUT` (the
answer says what holds), so a newer client can send one to an older server.

**No cursor.** The list is short (a few a week), so a device asks for all of it and keeps which ids it has shown. A cursor by `id` would
miss a finish whose two minutes end after a later row was read (the reason `sync_support` uses a counter under a lock, not an id); with no
cursor there is nothing to miss.

The routes are new, so the API's minor moves (1.1 → 1.2) and no installed app is affected (AGENTS.md §6). Responses are recorded from the
real server (`test_network_fixtures.py`) and held to the spec, as every route the apps call.

### 3.4 Delivery is the client's question, not the outbox's

The outbox is the same whatever carries it: a device that polls reads it, and a push (§4.2, §8), if the owner chooses one, only says
"look now". Nothing about which notifications exist or what they say changes with the transport.

---

## 4. Android delivery

### 4.1 The options

| | **A. Poll with WorkManager, notify locally** | **B. Firebase Cloud Messaging (FCM)** | **C. UnifiedPush** |
|---|---|---|---|
| **How** | A periodic worker asks `GET /api/notifications` and posts what is new | Google's push service wakes the app; it then asks the outbox (§4.2) | A distributor app the person installs (ntfy, …) holds a connection to a push server and wakes ours |
| **New dependency** | **None** (WorkManager, `NotificationManagerCompat` are already in) | `com.google.firebase:firebase-messaging` (pulls Firebase common, installations and Play services' cloud messaging); server needs **none** (FCM's HTTP v1 API over `requests`, a token from `google-auth`, both already pinned) | `org.unifiedpush.android:connector`; server: Web Push encryption (`pywebpush` or equal), and a push server to run or trust |
| **Needs on the phone** | Nothing | **Google Play services**: devices without them (de-Googled phones, some managed devices) get nothing | **A second app installed by every crew member** |
| **Needs from the owner** | Nothing | A Firebase project, its `google-services.json`, a service account key as a server secret (`FCM_SERVICE_ACCOUNT_JSON`), and **the `applicationId` chosen**, because Firebase registers the app by package name and the id is still the placeholder (`app.ezpztac.unreleased`, AGENTS.md §17) | A push server (ntfy beside the API on the VM) or a public one |
| **Timeliness** | WorkManager's least period is **15 minutes**, and the system stretches it: usually within 15–30 min on a phone in use; **hours on a phone left still** (Doze); **about once a day for an app rarely opened** (App Standby's *rare* and *restricted* buckets) | Seconds (high-priority messages are let through Doze) | Seconds, while the distributor's connection lives |
| **Battery and data** | One small request every 15 min or more, 304 when nothing is new; WorkManager batches it with other apps' work | Next to none | The distributor's connection |
| **Privacy** | Nothing leaves our server | Google sees that a message went to this install, and Firebase Installations registers an id for it. With a **content-free "look now" message** (§4.2) Google never sees a name | As A, plus whoever runs the push server |
| **Who already does this** | | Almost every app | Privacy-focused apps |

### 4.2 If FCM: a message that says nothing

Were FCM chosen, its message would carry **no text**: a data message with only `{"look": "1"}`, sent high-priority when a row becomes
deliverable. The app then asks the outbox, as the poll does, and posts from what our server said. So Google carries no pack names, people's
names or anything else; the notification's words come from us. It costs a request on arrival, which a high-priority message has time for.
The device registers its token with `POST /api/push/devices` (`{platform, token}`), tied to its session (`sid`), so signing a session out,
here or from elsewhere, forgets its token. The polling worker stays for phones without Play services.

### 4.3 Decided: A now, B later only if A proves too slow

**The owner's choice, 2026-10-28: start with polling.** FCM stays a later step (N4), weighed again once crews have used the poll.


**Poll (A).** It needs no dependency, no Google, no Firebase project and no `applicationId`, and it holds to the app's choice to keep Play
services out of everything but Google sign-in. What it gives up is speed, and the two notifications the owner named do not need speed: an
invitation **already arrives by email in seconds** (the push is the reminder, and the inviter is not waiting by the minute), and a finished
pack changes the plan for the next brief, not the next minute. Access approved is the one a person waits on, and that person has just signed
up, so the app is in the active bucket and the poll is quick.

The outbox does not change if FCM is added later (§3.4): B is a step of its own (§9, N4), to be taken once the `applicationId` is chosen and
if crews find the poll too slow.

### 4.4 On the device

- **The worker** (`NotificationWorker`, unique periodic, every 15 min, network required) asks the outbox with its last `ETag`, posts what it
  has not shown, takes down what is seen or withdrawn, and keeps the ids shown (a preference, per account, at most 200). It also runs once
  when the app comes to the front, so opening the app brings what waits. Every 15 minutes is the owner's choice (§10, question 2).
- **Only for the account whose plans are on the device**, signed in, not below the server's minimum version, as the sync runner checks
  (`EngineSyncRunner`); **a sign-out cancels every posted notification and forgets the ids**, as it wipes the threat picture.
- **Access approved**: while the account is gated the worker asks `GET /api/auth/me` instead (the only route a gated account reaches) and
  posts once when `access_ok` turns true on a device that saw it false.
- **In the front, a toast, not a system notification**: the frame's toasts (`ToastHost`) say it, with its action, and it is marked seen.
- **Channels**, one per setting group (§2.1), so the system's own per-channel switch works too: *Invitations* (high importance),
  *Finished packs*, *Packs handed to you*, *Account* (access approved), *Changes kept* (default importance).
- **Lock screen**: `VISIBILITY_PUBLIC`, **the names always shown** (the owner, 2026-10-28), even on a phone set to hide sensitive
  notification content. That is safe only because the text never holds a place, an item or anything from a pack (§6), so §6 is the rule
  that must hold; a test checks every notification's words against it.
- **Small icon**: the app's mark in one colour, as Android requires; no new artwork beyond that.

### 4.5 Asking for `POST_NOTIFICATIONS` (Android 13+)

Never at launch. Asked once, where the person can see what they get for it:

1. **On the gate's waiting screen** (`AffiliationHost`, waiting for an admin): *We'll tell you when your access is approved.* — *Notify me*.
2. **The first time the switcher opens** for an account with Mission Packs: a line under its heading, *Get told of invitations and finished
   packs* — *Turn on* · *Not now*. *Not now* hides it for that account; the setting stays in the account menu (§5).

The system shows its dialog at most twice; after a second refusal the switch in the account menu opens the app's system notification
settings instead. Below Android 13 nothing is asked: notifications are on unless turned off.

---

## 5. Settings

- **Per type, per account, on the server** (`notification_settings`, a JSON column on `user`, added by `schema_sync` as other columns are):
  `{"invitations": true, "pack_finished": true, "pack_owner": true}`. A missing key is **on**, as a missing entitlement is. `GET
  /api/notifications` returns them; `PUT /api/notifications/settings` changes them. An off type is still recorded (the person can turn it back
  on and find the switcher's invitations unaffected) but is never posted or toasted on any device.
- **Changes kept** is the device's own (Android only), in a preference.
- **Where**: the account menu's *Notifications* on Android (phone and tablet) and the web, one switch per group with a line on what it is.
  Android adds *System settings* (the app's notification page), because the system can also turn a channel or the app off.

---

## 6. What a notification may say

- **Names only**: a pack's name, a team's name, a person's display name, a role. **Never** an LZ's, route's or point's name, a grid, a
  coordinate, a height, a place in words, or anything from a pack's items: it shows on a lock screen and in the shade, and an item's name is
  whatever was typed (*LZ 16S GC 3120*). The outbox has no field for any of them (§3.1), and the words are made on the client from the type
  and those fields, so a new type cannot leak one by accident.
- **Unclassified only**, like everything else. Nothing here is new data: every field is something the person can already see in the switcher
  or a pack.
- Names are cut to 60 characters by characters (never through an emoji, AGENTS.md §17) and taken as text, never markup.

---

## 7. Tapping a notification

The tap opens `MainActivity` with an intent naming the notification (`type`, `id`, the account's id), read as incoming intents are
(`isNews`: a task restarted from the recent apps does not act on it again). A notification for another account than the one signed in opens
the app and does nothing else.

| Notification | Android | Web |
|---|---|---|
| Invitation (pack or team) | **The switcher, its invitations at the top**, with *Accept* and *Decline* (`PacksApi.accept(inviteId)`, `POST /api/invites/<id>/accept`) | The switcher, likewise |
| Added | Opens the pack (`PackDoor`), Pack panel | Opens the pack |
| Finished | Opens the pack, its finished banner; Pack panel | Opens the pack |
| Handed to you | Opens the pack, Pack panel | Opens the pack |
| Access approved | Opens the app (the gate lifts on `refreshUser`) | Reloads past the gate |
| Changes kept | The Library | — |

**Why not the invitation link (`InviteLinks`, `PackInvites`)**: an invitation's token is a bearer credential, stored only as its hash and
sent only in the email (`packs.new_token`). A notification never carries it (it would sit on a lock screen and in Google's servers under
FCM) and the server cannot recover it. The person is signed in, so accepting by id from the switcher, which already lists their waiting
invitations, is the same answer without the token. **No *Accept* button on the notification itself**: joining a pack is a choice made with
the pack's name and inviter in front of the person, not from a lock screen. A pack that is gone by the time the notification is tapped says
so in a toast (`PackWords`' words for a pack no longer available).

---

## 8. The web

### 8.1 The options

| | **In-app only** | **Web Push** |
|---|---|---|
| **How** | `feature/notifications/useNotifications` asks the outbox when a tab loads, comes back into view, and every 2 min while visible; new ones are toasts with their action | A service worker receives pushes from the browser's push service (VAPID-signed) and shows system notifications with the tab closed |
| **New dependency** | None | **Backend `pywebpush`** and what it pulls in (`py-vapid`, `http-ece`, `cryptography`), a **service worker** (CRA has none; one by hand in `public/`), VAPID keys as server secrets |
| **Reach** | Only while a tab is open | With the browser running; **on iPhone and iPad only as an installed home-screen web app** (iOS 16.4+) |
| **Privacy** | Nothing leaves our server | The browser's push service (Google's, Mozilla's, Apple's) carries an encrypted message it cannot read |

### 8.2 Recommendation: in-app only

The web is where a planner sits at a desk with the app open, and email already reaches them when it is not. In-app costs no dependency and no
service worker (which brings its own caching traps to a CRA build). What arrived while away is said **once, as one toast** on the next load
(*While you were away: 2 invitations, OP DK finished*), each part a link, then marked seen. Web Push is a later step if the owner wants it
(§9, N5), and with it the same "say nothing" choice as §4.2 is open.

---

## 9. Steps

Each as the other features were built: implemented, reviewed independently, fixed, committed with its tests and the build green, AGENTS.md
and `docs/HANDOFF.md` updated in the same commit.

| Step | What | Depends on |
|---|---|---|
| **N0** (built) | Backend: `notification` table, `notify.py`, the triggers of §3.2, the three routes, `notification_settings`, the sweep; OpenAPI (1.3.0), recorded responses, tests (one per trigger, the debounce and the reopen, withdrawal, the limits, another account's never seen) | — |
| **N1** | Android: core-network's calls and DTOs (held to the recordings), `NotificationWorker`, channels, posting and groups, the toast in front, tap routing, the permission ask, sign-out clearing; access approved from `/api/auth/me` | N0 |
| **N2** | Android: *Notifications* in the account menu; *Changes kept* from the background drain | N1 |
| **N3** | Web: `useNotifications`, the toasts and *While you were away*, tap routing, settings in the account menu | N0 |
| later **N4** | Android: FCM's content-free wake (§4.2), `POST /api/push/devices`, if the poll proves too slow (the owner chose polling first, 2026-10-28) | N1, the `applicationId` |
| later **N5** | Web Push, if the owner chooses it | N3 |

---

## 10. Questions for the owner

1. ~~**Android delivery**~~ **Answered, 2026-10-28: polling (A).** FCM is a later step (N4), if the poll proves too slow.
2. ~~**The poll's period**~~ **Answered: every 15 minutes**, the least WorkManager allows.
3. ~~**Web delivery**~~ **Answered: in-app only** (§8.2). Web Push stays a later step (N5).
4. ~~**The list**~~ **Answered: as proposed.** §2.1's six; a reopen is silent (it only cancels a finish not yet sent); role changes and
   removals are left out (§2.2).
5. ~~**The lock screen**~~ **Answered: always show the names** (`VISIBILITY_PUBLIC`, §4.4), resting on §6.
6. **New-device sign-in** as an email (§2.2), a separate small change: wanted?

---

## 11. What cannot be verified in an agent session

Notifications posted on a device, the permission dialog, the worker's real timing under Doze and App Standby, a tap from a locked screen,
and the toasts over the real map. Each wants a device run: the first after N1 is to send an invitation to a test account on a phone and
time how long it takes, with the app in front, in the back, and left still overnight.

---

## 12. Cost

Small to medium: N0 is a table, a module and three routes; N1 is a worker and a few screens' worth of wiring on patterns the app already has
(the sync worker, incoming intents, the frame's toasts). FCM (N4) would add a Firebase project and a service-account secret to keep, and a
dependency to keep current.
