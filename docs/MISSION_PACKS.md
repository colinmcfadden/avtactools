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
applier will be too. The rest of what a client does is pinned beside it, for the
native apps: how an editor's change becomes operations (`packs/diff.json`), one
change as sent (`packs/edit.json`), the history's sentences and new items' names
(`packs/describe.json`), what of an item is each person's own (`packs/shared.json`)
and how an open pack is held (`packs/session.json`); `contracts/README.md` lists
them. Change the rules on the web first and regenerate:

```powershell
cd frontend; $env:UPDATE_CONTRACTS="1"; $env:CI="true"; npx react-scripts test --watchAll=false src/contracts/pack
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
`owner_must_transfer`, `original_unknown` (409: an item copied before its record had a
`client_uuid`, so which record it came from is not known), and `invite_gone` /
`invite_expired` (410).

**What a refused batch already gave the pack.** On `POST …/ops`, a 403 `pack_read_only`,
a 413 `item_too_large` and a 423 `pack_finished` always carry `taken`: the operations of
that batch the log already has, the caller's own, in log order, each as its result in a
200 would be (`[]` when none). It answers a batch whose answer was lost and that is refused
when sent again (§5, rule 7): what it lists, the pack took the first time. It is worked out
under the pack's lock, so a first send still being applied is counted, and so is whether the
caller is in the pack and as what, and whether the pack is still there: a removal, a role
change or a deletion holds that lock while it works, and a batch that waited for it is
answered as the pack is now, as every write is (§7, where a role that comes through a team
is best effort). Someone not in the pack gets a bare
404, and a 400 carries none: it is decided from the operations alone, before the pack is
looked at, and a batch the pack took is well formed when sent again.
A client keeps what `taken` lists as the pack's, exactly as it keeps a 200's `results`,
reading both for the batch out only (a name not in it, such as an edit still queued, which
the server has never seen, is passed over), and reads a refusal with no `taken` (a server
from before it) as one that lists nothing (`packSession.batchFailed`; §5, rule 7).

**Counts and originals.** A pack's summary and body carry `audience_count`: everyone who can
open it, its own members and the team it is shared with, each person once. An item's `source`
carries `original_updated_at` (when the copier's Library record last changed), `pack_changes`
(edits to its content in the pack since it was copied or last updated; one edit made of
several operations counts once, and a rename does not count, because updating from the
original keeps the pack's name) and `last_pack_change` (who and the sentence). Copying in a
Library record that has no `client_uuid` yet (every record saved before sync) gives it one
first, so the copy names that record and no other.

**Invitation links** are `<FRONTEND_URL>/?invite=<token>`. The web takes the token
out of the address before anything renders and accepts it once the person is signed
in (`useInviteLink`), then opens the pack. Accepting needs the `mission_packs`
entitlement like every other pack route, so while packs are off a tester must be
ticked before they open the link. The native apps will read it when they accept the
site's links (`AuthLinks`).

## 5. How a client keeps up

The web does all of this in `feature/missionPacks/`: `packSession.js` is the state
(pure, and the part a native port follows: `contracts/fixtures/packs/session.json`),
`packClient.js` the network, and `useMissionPack(packUuid, me)` the React side.

1. `GET /api/packs/<uuid>` returns the items **as of `head_seq`**. A writer holds the pack's row while it writes, and this read waits for it (`FOR SHARE` on Postgres).
2. Apply your own edits at once, and send them in batches to `POST …/ops` with `base_seq` set to the last `seq` you have, one batch in flight at a time. Send a drag as one operation when the finger lifts, not one per frame.
3. The answer carries every event after `base_seq`, yours included, in order. Apply them to the confirmed copy and apply whatever is still pending on top again. An event confirms the pending edit with its `client_op_id`, applied or skipped. So does the `seq` the answer's result gives it, once your `seq` reaches that number: a reload, or events that came before the answer, may have moved past its event, which then never comes again.
4. If no answer comes (or a 401, 429 or 5xx), send the same batch again. A `client_op_id` the pack has seen is answered from the log and not applied twice; if the pack refuses the batch now, the refusal says which of it the pack has (rule 7).
5. Take everyone else's events from the live stream (§6) when the pack has a `live_url`; otherwise, and while the stream is down, poll `GET …/events?since=<seq>` every few seconds (not while the page is hidden). An event that skips a number means one was missed: fetch from your `seq`. A type a client does not know it skips.
6. If an event the server applied does not apply to your copy, your copy has drifted (it should never happen): load the pack again and keep what is pending on top.
7. When the pack is finished (423, a `pack.finish` event, or a reload that finds it finished), or this person may now only view (403 `pack_read_only`, a `member.role` event, or a reload), switch to read-only, say who finished it and when, and keep the edits the pack did not take (`session.dropped`, in the order they were made) so the person can save them to the library: what was queued, and a batch the server refused. A batch already out waits for its answer, and what is queued behind it waits with it, because the server decides only when the batch reaches the pack, which may take it yet (before the finish, or after a reopen): a refusal drops it with the rest, and a 200 keeps what it took (its results are read before its events) and drops the rest. An edit the server said it took is the pack's and is not offered back. When the pack is gone (403, 404, or this person removed), everything not taken is dropped at once, the batch out included, since nothing waits for its answer. A 400 or 413 drops that batch only. A batch whose answer was lost may have been taken, and sent again it can be refused (423, 403 `pack_read_only`, or a 413 when an edit sent with it the second time is too large). The refusal lists what of the batch the pack already has (`taken`, §4): those edits are kept as the pack's, acked as a 200's results are and confirmed by their events (which the web then fetches), and only the rest is dropped. A server from before `taken` sends none, which reads as nothing taken: the whole batch is dropped and offered back although the pack may have it, as before. Two narrower cases still offer back an edit the pack has: a reload that finds the pack read-only after a batch's answer was lost and before it goes again drops the batch with what is queued, as nothing is sent to ask (events cannot do this: the batch's own events come before the finish or the role change in the log, and confirm it first); and a 400 to such a resend carries no `taken`, which would matter only if an edit made meanwhile were malformed (the web checks every edit before it queues it, so that would be a bug on one side). Someone no longer in the pack gets a bare 404, so a batch of theirs whose answer was lost is dropped as gone, whatever the pack took of it. The older server and the reload are pinned in `session.json`.
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
  moved Chalk 2 on LZ IBIS.": `describeLzChange` and its route and point peers,
  `packs/describe.json`). `composeEdit` (`packEdit.js`, `packs/edit.json`) puts one
  change together: a rename first, then whatever brings an older item to today's
  shape, then the change, every operation with the one sentence.
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
  they had changed, to save to their library as "NAME (my edits)" (`myEditsName`,
  `packs/shared.json`).
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
  made by `me` sets `source.original` to `same`, and its `pack_changes` and `last_pack_change` to null,
  as the server then says), without waiting for the pack to be reloaded. The event does not say when the
  original last changed, so `original_updated_at` is null until then.
- **While a pack opens or cannot be reached**, the top bar names it with *Opening…* or *Cannot reach
  the pack*, never "Library", while the Library is parked.
- **Importing local points while a pack is open**: the pack destination is off for a viewer as for a
  finished pack, with the reason; points the pack refuses stay in the session; points sent to the
  Library or the session are not shown until the person switches back to the Library, and the review
  and a toast say so.
- **The pack's ⋯ menu**: Rename and Edit description (editors and the owner, not in a finished
  pack), Duplicate as a new pack, Leave pack (an owner is told to hand it over first: Members, Owner
  beside someone's name, after which they are an editor), Delete pack (owner, confirmed; never the
  prominent button). After leaving or deleting, the Library opens.
- **Member counts are `audience_count`**, so a pack shared with a team reads "18 members" in the
  switcher, on the LZ/PZ card ("18 members can see it") and in Add to pack.
- **Sharing with a team** offers *Can edit* or *Can view*, as New pack does, and the role can be
  changed later. Invite: pick the person or type the address, choose the role, then Invite.
- **Update from original asks the server as the dialog opens** (`GET …/items/<item>`), because the
  item as the pack was loaded does not follow edits made since: it says when the Library version
  changed and how many pack edits will be replaced, naming the last. A fact that did not come back is
  left out. "Original changed" itself is worked out when the pack loads, so a Library edit made while
  the pack is open shows after the pack is opened again.
- **Exporting an LZ card works in a read-only pack.** There the capture area is the person's own
  (`useCaptureArea`) and is never sent to the pack, since the pack would refuse it.

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

Close codes: **4401** the token was refused (sign in again); **4403** the account may
not open packs now: the API's `/access` answers 403 only for that (`feature_disabled`,
or `affiliation_required` from the `.mil` gate), never for the pack itself; **4404** the
pack is not this person's to see (not in it, or gone); **4410** it was deleted;
**4400** / **4408** a bad or missing hello (a client bug); **1013** the service could
not reach the API or the client fell 1,000 messages behind (try again later); **1009**
a frame over 8 KB. Anything else: reconnect with growing pauses, and poll meanwhile.
The web takes 4403 as the pack gone; the Android client asks the API and pauses with
every edit kept, as for any refusal about the account (`AGENTS.md` §15 lists the web's
side).

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
- **A write decides under the lock, by the pack as it is then.** What a route read before taking the lock may be out of date once it has it: a deletion, a removal, a role change, a hand-over, a re-share, and a withdrawn or re-sent invitation each hold the lock while they work, and a write that arrived meanwhile waited for it. So every write takes the lock before it changes anything, then reads again what it decides by. `lock` returns None for a pack deleted meanwhile (a tombstone, or the row gone with its owner's account), and the write then writes nothing to it. `pack_routes._lock` reads the caller's role again and answers with the refusal `_load` would give now (a bare 404 to someone no longer in the pack, 403 to someone made a viewer or no longer the owner). `role_of` reads the role as a column, because a membership object the session already holds keeps the role it was read with. Anything else read before the lock is read again after it: the team a share leaves unnamed (`update_pack`), the invitation being accepted (`_accept` refreshes it, and one found by its link must still have that link: sending it again or inviting the address again replaces the link under the lock, and the old one is then answered as any link that matches nothing), who owns each pack an account being deleted owns or is in (`release_account`: one handed away meanwhile is a pack they are in, one handed to them meanwhile is handed on as their own packs are), the team of a pack whose team is being deleted (`delete_team`). `tests/test_packs.py::WaitingForTheLockTests` (and `LostAnswerTests` for the operation stream) commit each competing change just before the request gets the lock, in the database only (never on the objects the request has read, which another session could not touch), and check that a refused request wrote nothing before the lock either. Reads are not held to this: `GET /api/packs/<uuid>` waits for a writer in progress but gives the caller's `role` as it was before it waited, and the log and `/access` take no lock, so a client may learn of a change a moment later, from its event or the next write's refusal. The lock's UPDATE moves the pack's `updated_at`, which `GET /api/packs` sorts by, so what changes nothing in the pack must not move it: the seen marker (`PUT …/seen`) takes no lock (it is not part of the log), and sending an invitation again takes it with `touch=False`, which keeps `updated_at` (it must see a withdrawal or a deletion it waited for, but logs nothing).
- **A role that comes through a team is best effort.** A change to a team's members (someone removed or leaving, an account deleted) does not take the lock of the packs shared with the team. A write reads the team role again under its own lock, so a removal that committed before then is seen; one that commits while the write holds the lock is not, and the write lands as if it had come just before the removal. That gap is Postgres's only: SQLite runs one writer at a time. Unsharing a pack and deleting a team do take the packs' locks, so those are exact, and a team's own roles (owner, admin, member) change nobody's role in a pack. Making team member changes take the lock of every pack on the team was weighed and left: the lock as writes take it would move each of those packs' `updated_at` (all of them jumping to the top of everyone's list for one person leaving a team; `touch=False` would not), one transaction locking many packs beside account and team deletion, which lock several in other orders, can deadlock (Postgres answers one of them with a 500), and none of it can be shown on SQLite, where the tests run. If it is ever needed, take the team's packs' locks in id order with `touch=False` (or a plain `SELECT … FOR UPDATE` in id order), so `updated_at` stays.
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
4. **Android** (the engine done, A1–A15; the screens not started. The plan, the owner's decisions and each step are in `docs/ANDROID_MISSION_PACKS_PLAN.md`, and what is built in `AGENTS.md` §17, *Mission packs on Android*). The pack routes' responses are recorded in `contracts/fixtures/network/responses.json` with strict types; a typed call for every route and the live stream on OkHttp's own WebSocket; the web's pack code ported and held to `contracts/fixtures/packs`; `PackEngine` beside the library's sync engine, with an offline outbox in Room (database version 3, four tables: `pack`, `pack_item`, `pack_op_outbox`, `pack_own`); the existing LZ and route editors joined to a pack's items; packs run with the app and drain in the background sync; invitation links. Edits a pack refused are saved to the library as "NAME (my edits)", the web's name for them (`myEditsName`, held to `contracts/fixtures/packs/shared.json`), whenever the pack is not open: nothing is silently dropped. Still to build: the screens (the switcher, New pack, the Pack panel with Items and History, Members, the finished banner, invitations) in the redesigned shell.
5. **History and finish UI**: done on the web; Android with the screens of step 4.

**Not in v1:** rewinding a pack to an earlier point (the log makes it possible
later), imported AMPS missions in packs, and an admin who edits packs.
