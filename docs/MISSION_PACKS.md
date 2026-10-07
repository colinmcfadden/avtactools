# Mission Packs

A **mission pack** is a shared container of LZs, sets of sketched routes and point
sets that a team plans one operation in. Every member edits the same pack, edits
reach everyone, and the pack keeps a log of who changed what. One-off library
records (`SavedLZ`, `SavedRoute`, `SavedPointSet`) are untouched by any of it.

Not the offline download the native plan used to call a "mission pack": that is
now a **map pack** (`docs/NATIVE_APPS_PLAN.md`, Offline strategy).

**Status:** steps 1–3 are built: the backend (packs, items, the operation stream,
the log, finish, teams, invites, search, seen markers), the live service (§6), and
the web's side of everything below the screens (`frontend/src/feature/missionPacks/`):
the sync client (`useMissionPack`), a call for every route (`packApi`), invitation
links, and the editors kept in step with an open pack (§5a). The pack screens are
built too, inside the menu redesign's dock and workspace switcher
(`docs/MENU_REDESIGN.md` §8; how they join the editors is the end of §5a).

**Off until it launches.** Packs ship together with the menu redesign (the owner,
2026-10-07). Until then `mission_packs` is in `entitlements.DEFAULT_OFF`: every
route answers 403 `feature_disabled` except to admins and to testers an admin
ticks it for on the user's page in the dashboard. Launching is taking it out of
`DEFAULT_OFF`, in the same release as the screens.

---

## 1. Decisions

From the architecture proposal (Oct 5, 2026). The owner accepted every
recommendation on 2026-10-05.

| # | Question | Decision |
|---|---|---|
| 1 | Name clash with the native plan's offline download | The offline download is a **map pack**; this feature keeps "Mission Pack" |
| 2 | Live sync transport | A separate realtime service using the Python `websockets` library (approved as a new dependency), in its own container. Step 3 |
| 3 | Pulling in library items | **Copy**, remembering the source, with *Update from original* |
| 4 | Finding people | **Teams** plus **email invites**; name search only finds people who share a team with you |
| 5 | Lock vs finish | One **Finish**, which the owner can reopen |
| 6 | Roles | **Owner, editor, viewer** |
| 7 | Owner deletes their account | The pack passes to someone else in it; it is deleted only if nobody else is in it |
| 8 | Imported AMPS missions (`kind: mission`) | **Not in v1**: sketched routes only |
| 9 | Fly backend has no realtime service | Clients **poll** `events?since=` there |

**Filled in while building** (not settled by the proposal; say if any should change):

- A pack shared with a team gives that team's members one role, `team_role` (editor or viewer). Someone's own membership wins over it.
- On account deletion the heir is the longest-standing **editor**, else the longest-standing **viewer**, else the longest-standing member of the team the pack is shared with. A team passes to its longest-standing admin, else member.
- *Update from original* is only for the member who copied the item in, and only they are told the original changed. The original is their private record. A duplicated pack's items have no source link.
- A teammate is **added directly** by name (they get an email notice); anyone else gets an **invitation** they accept. An emailed link works for whichever account opens it, since people often sign in with a different address from the one they were invited at.
- Editors rename and describe a pack. Only the owner finishes, reopens, deletes, manages members and invites, and shares with a team. A finished pack still lets the owner manage members and sharing, and lets everyone read it, copy items out, and duplicate it.
- Limits: 200 operations per batch, 5 MB per item, 300-character log lines, 30 invitations an hour per person, and invitations last 14 days.

## 2. Data model

Eight new tables, made by `db.create_all()` at boot, all in `backend/models.py`.
No existing table is altered.

| Table | What it holds |
|---|---|
| `team`, `team_member` | A unit or section, and who is in it (owner, admin, member) |
| `mission_pack` | The pack: owner, optional team share (`team_id`, `team_role`), status (`active` / `finished`), and `head_seq`, its own change counter. Deleting it leaves a tombstone with the content gone |
| `mission_pack_member` | Who is in it and as what (owner, editor, viewer) |
| `mission_pack_invite` | Invitations to a pack or a team: an email address (or, for a team, none: a single-use link), a SHA-256 token, role, status, expiry |
| `mission_pack_item` | An item: `kind` (`lz`, `route`, `pointset`), `name`, and `data`, **exactly the JSON the library keeps** (`lz_data`, `route_data`, `points_data`), so every editor, exporter and native normalizer works on it unchanged. `source_*` records where a copy came from |
| `mission_pack_event` | The log: one row per change, numbered by `seq` within the pack, with who, a sentence, the operation, and whether it was applied or skipped (and why) |
| `mission_pack_seen` | How far each person has looked in a pack (`seen_seq`, `seen_at`): what "changed since you looked" is measured from on every device, and a member's "Seen" time. Not part of the log |

Why a separate item table rather than a `pack_id` on `SavedLZ`: those tables
belong to one user, every query filters on it, and they sync through the per-user
cursor. A shared item would leak into the owner's library and their devices' sync,
or need a special case in every one of those queries.

## 3. Operations

Edits travel as small operations addressed by **stable ids, never array
positions**. The server applies them one at a time per pack, gives each the next
`seq`, and logs it. The server does not know what an LZ or a route is: it only
follows paths through JSON. Domain logic (the auto attack profile, doghouse
headings, analysis results) runs on the client, as today, and is sent as the
operations it produces.

**The rules are a contract.** `frontend/src/feature/missionPacks/packOps.js` is the
reference. It writes `contracts/fixtures/packs/ops.json` (134 cases), and
`backend/pack_ops.py` is held to every case (`tests/test_pack_ops.py`). The Kotlin
applier will be too. Change the rules on the web first and regenerate:

```powershell
cd frontend; $env:UPDATE_CONTRACTS="1"; $env:CI="true"; npx react-scripts test --watchAll=false src/contracts/packOpsFixtures
```

| Operation | Fields | Does |
|---|---|---|
| `item.create` | `kind`, `name`, `data` | Adds an item. `data` is an object for `lz` and `route`, a list for `pointset` |
| `item.rename` | `name` | |
| `item.delete` | | Deletes it; name and content go too. Its uuid is never used again |
| `set` | `path` (not empty), `value` | Sets a field. Objects missing or null on the way are made |
| `patch` | `path` (may be empty), `value` (object) | Merges fields into the object at `path` |
| `upsert` | `path` (to a list), `value` (object with `id`) | Merges into the element with that id, or appends it. A missing or null list is made |
| `insert` | `path` (to a list), `after` (id or null), `value` | After that element; first if `after` is null; last if that element is gone |
| `remove` | `path` ending in `{"id": …}` | Removes that element |
| `item.replace` | `data` | **Server only** (*Update from original*). Clients apply it from the log |

A **path** is a list of segments. Each segment is either a key (a string) or
`{"id": x}`: the first element of a list whose `id` is exactly `x`. The number
`1` is not the text `"1"`, and an element that is not an object or has no `id` is
passed over. Keys named `__proto__`, `constructor` and `prototype` are refused,
because in JavaScript they would reach an object's prototype.

```json
{"type": "set", "item": "lz-1", "path": ["graphics", "helicopters", {"id": "h-3"}, "lat"], "value": 34.51}
{"type": "insert", "item": "rt-1", "path": ["routes", {"id": "r-1"}, "points"], "after": "p-7", "value": {"id": "p-9", "lat": 34.5, "lon": -84.1}}
```

**Outcomes.** An operation is `applied`; or `skipped`, meaning it is well formed but
what it edits is gone or in the way (reasons `item_missing`, `item_exists`,
`target_missing`, `not_an_object`, `not_an_array`, `element_exists`), so nothing
changes and the log says why; or `invalid`, meaning it is malformed (`bad_op`,
`unknown_type`, `bad_item`, `bad_kind`, `bad_name`, `bad_data`, `bad_path`,
`bad_value`, `bad_after`). An invalid operation refuses its whole batch.

**Conflicts** follow from the order. Two people editing different fields or
different graphics both stand, which covers nearly every case. Two people editing
the same field leave the later one in server order (field-level last writer
wins). An edit to something already deleted is skipped and logged.

Names are kept as sent. They must not be blank and are at most 100 characters,
counted as code points (as Python and Kotlin count them). Clients trim before
sending.

## 4. The API

Everything is described in `contracts/openapi.yaml`, and the backend's responses
are held to it (`tests/test_packs.py::ContractTests`). Every route needs the
`mission_packs` entitlement and is `Cache-Control: private, no-store`. A pack the
caller is not in is a 404, so its existence is not revealed.

| Area | Routes |
|---|---|
| Packs | `GET/POST /api/packs`; `GET/PUT/DELETE /api/packs/<uuid>`; `POST …/finish`, `…/reopen`, `…/duplicate` |
| Edits | `POST /api/packs/<uuid>/ops` takes a batch, each operation with a `client_op_id`, plus an optional `base_seq`. `GET /api/packs/<uuid>/events?since=&limit=` returns the log. `PUT /api/packs/<uuid>/seen {seq}` records how far the caller has looked (never goes back); a pack carries the caller's `seen_seq`, its members their `seen_at`, and a list of packs `item_counts` by kind |
| Items | `POST /api/packs/<uuid>/items` copies an item in from the library. `GET …/items/<item>`; `POST …/items/<item>/update-from-original`; `POST …/items/<item>/library` saves a copy to your library |
| Members | `POST /api/packs/<uuid>/members` adds a teammate. `PUT/DELETE …/members/<user_id>`. Making someone the owner hands the pack over |
| Invites | `GET/POST /api/packs/<uuid>/invites`, `DELETE …/invites/<id>`, `POST …/invites/<id>/resend`. `GET /api/invites` lists the caller's; `POST /api/invites/<id>/accept`, `…/decline`, and `POST /api/invites/accept {token}` (from the link) answer them |
| Teams | `GET/POST /api/teams`; `GET/PUT/DELETE /api/teams/<id>`; `PUT/DELETE …/members/<user_id>`; `GET/POST …/invites` (with no email, a single-use link whose token is shown once); `DELETE …/invites/<id>` |
| Search | `GET /api/users/search?q=` finds people who share a team with you, by name or sign-in email. Never a `.mil` address they did not sign in with |

**Errors** are `{error, code}`. The codes worth knowing: `pack_finished` (423, with who
finished the pack and when), `pack_read_only` (a viewer), `owner_only`,
`invalid_op` (with `index` and `reason`), `item_too_large` (413),
`mission_not_supported`, `not_your_original`, `not_a_teammate`,
`owner_must_transfer`, and `invite_gone` / `invite_expired` (410).

**Invitation links** are `<FRONTEND_URL>/?invite=<token>`. The web takes the token
out of the address before anything renders and accepts it once the person is signed
in (`useInviteLink`), then opens the pack. Accepting needs the `mission_packs`
entitlement like every other pack route, so while packs are off a tester must be
ticked before they open the link. The native apps will read it when they accept the
site's links (`AuthLinks`).

## 5. How a client keeps up

The web does all of this in `feature/missionPacks/`: `packSession.js` is the state
(pure, and the part a native port follows), `packClient.js` the network, and
`useMissionPack(packUuid, me)` the React side.

1. `GET /api/packs/<uuid>` returns the items **as of `head_seq`**. A writer holds the pack's row while it writes, and this read waits for it (`FOR SHARE` on Postgres).
2. Apply your own edits at once, and send them in batches to `POST …/ops` with `base_seq` set to the last `seq` you have, one batch in flight at a time. Send a drag as one operation when the finger lifts, not one per frame.
3. The answer carries every event after `base_seq`, yours included, in order. Apply them to the confirmed copy and apply whatever is still pending on top again. An event confirms the pending edit with its `client_op_id`, applied or skipped.
4. If no answer comes (or a 401, 429 or 5xx), send the same batch again. A `client_op_id` the pack has seen is answered from the log and not applied twice.
5. Take everyone else's events from the live stream (§6) when the pack has a `live_url`; otherwise, and while the stream is down, poll `GET …/events?since=<seq>` every few seconds (not while the page is hidden). An event that skips a number means one was missed: fetch from your `seq`. A type a client does not know it skips.
6. If an event the server applied does not apply to your copy, your copy has drifted (it should never happen): load the pack again and keep what is pending on top.
7. When the pack is finished (423, or a `pack.finish` event), switch to read-only, say who finished it and when, and keep the edits that were never taken (`session.dropped`) so the person can save them to the library. A 400 or 413 drops that batch only.
8. A pack that cannot be loaded (no answer, or a 5xx) is tried again by itself, after 1 s growing to 30 s, and at once when the connection or the tab comes back. A 403 or 404 means it is not this person's to see any more, and is not retried.
9. Closing a pack (switching workspace, opening another) stops listening but not sending: what is still queued, or waiting to be retried, is sent until nothing is left, and anything the pack would not take is reported (`onLost`, a toast). After a sign-out nothing more is sent, so a later sign-in can never carry the previous person's edits.

## 5a. The editors and an open pack (web)

The editors are not changed to know about packs: every LZ/PZ stays in the workspace
(`useLzWorkspace`), every route in the sketch (`useRouteSketch`, now in sets: each
route's `setId`), every point set in `useLocalPoints`. A pack item open in one of them
has an id naming the pack and the item (`packRef.js`). `usePackLz`, `usePackRoutes`
and `usePackPoints` join them to the pack, on one engine (`usePackItemSync`):

- **A change made here is sent once the item has been still for 400 ms**, so a drag
  is one change, not one per frame, as the operations `packDiff.js` works out (an
  object's changed fields as one patch there; list elements by id: removed, inserted
  after their neighbour, patched, or moved), with a sentence for the history ("Sam B.
  moved Chalk 2 on LZ IBIS.": `describeLzChange` and its route and point peers).
- **What is sent is the change from the version the editor was last in step with**,
  never from the pack's latest, so it lands on top of whatever others did meanwhile:
  both stand, or on one field the later one. A change waiting here is sent before
  anyone else's is applied, so nothing made here is lost under it. An item still in an
  older shape (an old library LZ copied in) is brought to today's in the same send, so
  every change's path exists on the server; nothing is sent for merely opening one.
- **Someone else's change is put into the editor, keeping what is each person's own**:
  an LZ's view (base map, outline, slope map), its slope raster while the boundary is
  the same, its library link and unsaved flag; a route's being hidden; a point set's
  colour and visibility. None of that is ever in a pack.
- A change the pack will not take (finished, or this person may only look) is put back
  to the pack's version, and `droppedVersions` rebuilds the person's own version of what
  they had changed, to save to their library as "NAME (my edits)" (`packActions.js`).
- An item someone removed leaves the editor. `usePackSeen` moves the person's seen
  marker while they look, and `changedSince` says which items changed since. "Since"
  is the server's `seen_seq` for that pack as it loads: nothing is marked new until the
  pack has loaded, and switching straight from one pack to another never carries the
  first one's marker over.

These hooks are tested against the real editors and a fake pack that applies every
operation as the server would (`fakePack.js`), including a change here and one there in
the same moment, a drag that goes on after someone else's change arrived, and the same
field changed by two people.

**The screens** (`feature/missionPacks/ui/`, designs in `docs/MENU_REDESIGN.md` §8) are joined
to the app by `usePackWorkspace`. Things worth keeping true:

- **One workspace is open: the Library or one pack.** Opening a pack *parks* the Library's open
  LZ/PZs (the workspace's state, unsaved flags and all) and puts them back on the way out; the
  Library's routes, points and missions stay loaded but are not shown. Switching never discards
  anything. The open pack is remembered in `localStorage` (`ezpz.openPack`).
- **Just after a switch the hook still holds the previous pack's client for one render.** Its
  session names the other pack: `usePackWorkspace` ignores a session whose `pack.uuid` is not the
  open one. Without that the first LZ/PZ of the new pack was never opened.
- In a pack, all its route sets and point sets go on the map (and new ones as they arrive, unless
  closed here), its first LZ/PZ opens, a new target is a new LZ/PZ in it, a finished sketch goes
  into the chosen route set (made if there is none), local points can be imported straight into it,
  and the Library's Open becomes Add to pack.
- **"Who is here" is presence only**: the people the live service says have this pack open, never
  whether someone is signed in. Each person's focus is `{ item, at }`, the LZ/PZ they have open and
  where their pointer is on the map; the others are drawn as named flags (`PresenceLayer`). Without
  the live service (Fly, the local preview) nobody is shown, and that is correct.
- Read-only (finished, or a viewer; `packForPanels.readOnly`, the same test as the session's): the
  planning tools are off, the map offers no drag, turn, delete or inline edit of the pack's graphics
  and route points, the LZ/PZ panel has no Rename and the Routes panel no sketching, a banner says
  who finished it and when, and edits the pack would not take are offered back as "NAME (my edits)"
  in the Library. A pack that turns read-only in the middle of a drag ends the drag without a change.
- **History follows the pack while it is open.** `session.pack.head_seq` moves with every event
  applied, one's own included, and the tab fetches only what is newer than what it holds; with more
  than 500 events it shows the newest 500.
- After *Update from original*, the copier's item reads unchanged again at once (an `item.replace`
  made by `me` sets `source.original` to `same`), without waiting for the pack to be reloaded.
- **While a pack opens or cannot be reached**, the top bar names it with *Opening…* or *Cannot reach
  the pack*, never "Library", while the Library is parked.
- **Importing local points while a pack is open**: the pack destination is off for a viewer as for a
  finished pack, with the reason; points the pack refuses stay in the session; points sent to the
  Library or the session are not shown until the person switches back to the Library, and the review
  and a toast say so.

## 6. The live stream

`backend/realtime/service.py`, its own container (`backend/realtime/Dockerfile`,
Coolify base directory `/backend/realtime`), using `websockets`. The API sends a
Postgres `NOTIFY` on `mission_pack_events` inside the transaction that made each
change (`pack_support._announce`), so a change that rolls back is never announced
and announcements arrive in commit order. The service `LISTEN`s and passes each one
to the sockets that have that pack open. It holds no pack data, and access is
always the API's call: it asks `GET /api/packs/<uuid>/access` with the person's own
token when a socket opens, whenever a membership event arrives for that pack, and
every 5 minutes. Edits never go through it.

A payload over Postgres's 8,000-byte limit (an item made or replaced whole) is
announced by its number alone, and clients fetch it. If the `LISTEN` connection
drops, the service opens it again and tells every socket to catch up, because
whatever was announced meanwhile is lost.

**Protocol.** JSON text frames on `<live_url>` (the path `/live` or `/`; `/health`
answers plain HTTP for health checks).

| Direction | Message | Meaning |
|---|---|---|
| client → | `{"type": "hello", "pack": uuid, "token": jwt}` | First, within 10 s. One socket per open pack |
| client → | `{"type": "token", "token": jwt}` | A refreshed token, used from the next access check |
| client → | `{"type": "presence", "focus": {...}}` (or `null`) | What this person has open (1 KB at most); everyone's is sent at most every 100 ms |
| → client | `{"type": "welcome", "pack", "head_seq", "role", "status", "you": {"session", "user_id", "name"}}` | Access granted. Fetch from your `seq` if `head_seq` is ahead |
| → client | `{"type": "event", "pack", "seq", "event": {...}}` | One event, exactly as `GET …/events` gives it |
| → client | `{"type": "head", "pack", "seq"}` | Event `seq` exists but was too large to carry: fetch it |
| → client | `{"type": "resync", "pack"}` | Announcements may have been lost: fetch from your `seq` |
| → client | `{"type": "presence", "pack", "people": [{"session", "user_id", "name", "focus"}]}` | Who has the pack open, this socket included |
| → client | `{"type": "closed", "reason": code}` then a close frame | Why the socket is being closed |

Close codes: **4401** the token was refused (sign in again); **4403** / **4404** the
pack is not this person's to see; **4410** it was deleted; **4400** / **4408** a bad
or missing hello (a client bug); **1013** the service could not reach the API or the
client fell 1,000 messages behind (try again later); **1009** a frame over 8 KB.
Anything else: reconnect with growing pauses, and poll meanwhile.

**Settings.** On the API, `REALTIME_PUBLIC_URL` (`ws://` or `wss://`) is the
`live_url` every pack response carries; unset, it is null and clients poll. On the
service, `DATABASE_URL` (the API's Postgres: the Session pooler, since `LISTEN`
needs a session of its own), `REALTIME_API_URL` (the API's root, without `/api`),
`REALTIME_PORT` (default 8091) and optionally `REALTIME_ALLOWED_ORIGINS`. A socket
proves itself with a token, never a cookie, so the origin check is only defence in
depth. It pings every 30 s, under Cloudflare's 100 s idle timeout.

**Tests.** `tests/test_realtime.py` is the protocol over real sockets with the API
stood in for (needs `websockets`). `tests/test_realtime_live.py` is the whole chain:
the real pack routes on a real Postgres, their `NOTIFY`, the service and two
people's sockets. It runs when `EZPZ_LIVE_POSTGRES` names a local Postgres server.
Its docstring has the `docker run` line.

## 7. Rules worth keeping

- **One order per pack.** Every write takes the pack's row first (`pack_support.lock`, an UPDATE as `sync_support.next_seq` does, which also queues SQLite writers) and numbers its events under it. Don't number events from a timestamp or outside the lock.
- **The log is the record.** `mission_pack_event` is append-only. Pack-level changes are events too: `pack.create`, `pack.update`, `pack.share`, `pack.finish`, `pack.reopen`, `pack.transfer`, `member.add`, `member.join`, `member.role`, `member.remove`, `invite.create`, `invite.revoke`. `actor_name` is kept as written, so the log still reads after an account is deleted.
- **Nothing reaches back into the library.** A copy is the pack's. Pack edits never touch the original, and only the person who copied an item in can see whether their original changed.
- **Threats are never part of a pack.** There is no item kind for them, and `item.create` with `kind: threat` is malformed.
- **Finished means read-only for everyone**, the owner included, until the owner reopens it. The server enforces it from step 1, so a client that forgets cannot get round it.
- **Deleting an account** calls `pack_support.release_account` before the user row goes, from both `DELETE /api/auth/me` and the admin dashboard. `mission_pack.owner_id` has no `ON DELETE` on purpose: Postgres refuses to delete an owner that was not released. `tests/test_packs.py` turns on SQLite's foreign keys so the tests catch this too.
- **The admin dashboard is read only** here (`/admin/packs`, `/admin/teams`). It shows who owns what and who is in it, never what a pack holds.
- **Aggregation.** A pack gathers one operation's LZs, PZs and routes in one place. It is unclassified like everything else here, but `docs/USER_GUIDE.md` should say so when the pack UI ships.

## 8. Phases

1. **Packs for one person** (backend: done; web screens: done on `feat/menu-redesign`). Tables, pack routes, copy-in from the library, the operation stream and the log, finish.
2. **Sharing** (backend: done). Teams, name search, email invites, roles, members.
3. **Live sync** (done; not deployed). The live service (§6), the API's `NOTIFY`, and the web's sync client with polling where there is no service (Fly). Deploying it is an owner step: a Coolify app for `/backend/realtime`, a public hostname for it through the Cloudflare Tunnel, and `REALTIME_PUBLIC_URL` on the API. Supabase's Session pooler should carry `LISTEN`; it has not been tried against it yet.
4. **Android.** Room migration (pack, pack_item, pack_member, pack_op_outbox, pack_event), a `PackSyncEngine` beside the existing engine, OkHttp's WebSocket, an offline outbox, pack screens. Operations refused because the pack was finished meanwhile are saved to the library as "NAME (my offline edits)": nothing is silently dropped. Record the pack routes' responses in `contracts/fixtures/network/responses.json` then.
5. **History and finish UI**: done on the web; Android with step 4.

**Not in v1:** rewinding a pack to an earlier point (the log makes it possible
later), imported AMPS missions in packs, and an admin who edits packs.
