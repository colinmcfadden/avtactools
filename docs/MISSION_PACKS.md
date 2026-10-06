# Mission Packs

A **mission pack** is a shared container of LZs, sets of sketched routes and point
sets that a team plans one operation in. Every member edits the same pack, edits
reach everyone, and the pack keeps a log of who changed what. One-off library
records (`SavedLZ`, `SavedRoute`, `SavedPointSet`) are untouched by any of it.

Not the offline download the native plan used to call a "mission pack": that is
now a **map pack** (`docs/NATIVE_APPS_PLAN.md`, Offline strategy).

**Status:** backend steps 1 and 2 are built (packs, items, the operation stream,
the log, finish, teams, invites, search). No client uses them yet. The web UI is
built inside the menu redesign's dock and workspace switcher
(`docs/MENU_REDESIGN.md` on `docs/menu-redesign`, §8), after its phases 1–5.

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

Seven new tables, made by `db.create_all()` at boot, all in `backend/models.py`.
No existing table is altered.

| Table | What it holds |
|---|---|
| `team`, `team_member` | A unit or section, and who is in it (owner, admin, member) |
| `mission_pack` | The pack: owner, optional team share (`team_id`, `team_role`), status (`active` / `finished`), and `head_seq`, its own change counter. Deleting it leaves a tombstone with the content gone |
| `mission_pack_member` | Who is in it and as what (owner, editor, viewer) |
| `mission_pack_invite` | Invitations to a pack or a team: an email address (or, for a team, none: a single-use link), a SHA-256 token, role, status, expiry |
| `mission_pack_item` | An item: `kind` (`lz`, `route`, `pointset`), `name`, and `data`, **exactly the JSON the library keeps** (`lz_data`, `route_data`, `points_data`), so every editor, exporter and native normalizer works on it unchanged. `source_*` records where a copy came from |
| `mission_pack_event` | The log: one row per change, numbered by `seq` within the pack, with who, a sentence, the operation, and whether it was applied or skipped (and why) |

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
| Edits | `POST /api/packs/<uuid>/ops` takes a batch, each operation with a `client_op_id`, plus an optional `base_seq`. `GET /api/packs/<uuid>/events?since=&limit=` returns the log |
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

**Invitation links** are `<FRONTEND_URL>/?invite=<token>`. No client reads that
parameter yet; the web will when its pack UI lands, and the native apps when they
accept the site's links (`AuthLinks`).

## 5. How a client keeps up

1. `GET /api/packs/<uuid>` returns the items **as of `head_seq`**. A writer holds the pack's row while it writes, and this read waits for it (`FOR SHARE` on Postgres).
2. Apply your own edits at once, and send them in batches to `POST …/ops` with `base_seq` set to the last `seq` you have. Send a drag as one operation when the finger lifts, not one per frame.
3. The answer carries every event after `base_seq`, yours included, in order. Rebase whatever you have not seen confirmed on top of them, using the applier.
4. If an answer is lost, send the same batch again. A `client_op_id` the pack has seen is answered from the log and not applied twice.
5. Until the realtime service exists, poll `GET …/events?since=<seq>` every few seconds while a pack is open. A type a client does not know it skips.
6. When the pack is finished (423), switch to read-only and say who finished it and when.

## 6. Rules worth keeping

- **One order per pack.** Every write takes the pack's row first (`pack_support.lock`, an UPDATE as `sync_support.next_seq` does, which also queues SQLite writers) and numbers its events under it. Don't number events from a timestamp or outside the lock.
- **The log is the record.** `mission_pack_event` is append-only. Pack-level changes are events too: `pack.create`, `pack.update`, `pack.share`, `pack.finish`, `pack.reopen`, `pack.transfer`, `member.add`, `member.join`, `member.role`, `member.remove`, `invite.create`, `invite.revoke`. `actor_name` is kept as written, so the log still reads after an account is deleted.
- **Nothing reaches back into the library.** A copy is the pack's. Pack edits never touch the original, and only the person who copied an item in can see whether their original changed.
- **Threats are never part of a pack.** There is no item kind for them, and `item.create` with `kind: threat` is malformed.
- **Finished means read-only for everyone**, the owner included, until the owner reopens it. The server enforces it from step 1, so a client that forgets cannot get round it.
- **Deleting an account** calls `pack_support.release_account` before the user row goes, from both `DELETE /api/auth/me` and the admin dashboard. `mission_pack.owner_id` has no `ON DELETE` on purpose: Postgres refuses to delete an owner that was not released. `tests/test_packs.py` turns on SQLite's foreign keys so the tests catch this too.
- **The admin dashboard is read only** here (`/admin/packs`, `/admin/teams`). It shows who owns what and who is in it, never what a pack holds.
- **Aggregation.** A pack gathers one operation's LZs, PZs and routes in one place. It is unclassified like everything else here, but `docs/USER_GUIDE.md` should say so when the pack UI ships.

## 7. Phases

1. **Packs for one person** (backend: done). Tables, pack routes, copy-in from the library, the operation stream and the log, finish. The web's pack mode waits for the menu redesign.
2. **Sharing** (backend: done). Teams, name search, email invites, roles, members.
3. **Live sync.** A `realtime/` service using `websockets` in its own container (with its own Coolify app, as `backend/lidar` has). It checks the same JWT and pack membership and listens on Postgres `LISTEN/NOTIFY`; the Session pooler supports it, but check before relying on it. It forwards each new event to the pack's open sockets, with presence, and sends a ping every 30 s so Cloudflare's 100 s idle timeout does not close them. The API sends `pg_notify` after each commit. Clients fall back to polling where the service is not running (Fly).
4. **Android.** Room migration (pack, pack_item, pack_member, pack_op_outbox, pack_event), a `PackSyncEngine` beside the existing engine, OkHttp's WebSocket, an offline outbox, pack screens. Operations refused because the pack was finished meanwhile are saved to the library as "NAME (my offline edits)": nothing is silently dropped. Record the pack routes' responses in `contracts/fixtures/network/responses.json` then.
5. **History and finish UI** on both apps.

**Not in v1:** rewinding a pack to an earlier point (the log makes it possible
later), imported AMPS missions in packs, and an admin who edits packs.
