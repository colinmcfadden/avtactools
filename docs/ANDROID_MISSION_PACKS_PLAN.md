# Android mission-pack engine: the plan it is built from

Status (2026-10-09): **A1–A10 are done** on `feat/android-mission-packs`; **A11–A15 remain**, then the screens. This is the
design the engine is being built to: the synthesis of three drafts (fidelity to the web first, offline robustness first, the
smallest sound change), made on 2026-10-08, with the owner's decisions and the amendments made while building on top. **Where it
disagrees with the code, the code wins** (AGENTS.md §16), and AGENTS.md §17 says what each module holds now. Read
`docs/MISSION_PACKS.md` first (what packs are, the operations, the API, the rules), then `docs/HANDOFF.md` (where things stand).

What changed under the plan since it was written, all done:

- The web's session was fixed first and `session.json` regenerated (`67554b1`): an edit the server took is never offered back,
  an acked edit carries the `seq` its result gave and is confirmed once the session reaches it, a batch that is out holds what
  is queued behind it in a pack seen read-only. The Android session (`PackSessions`, A7) ports that, not the older web code the
  plan describes; the plan's Android-only rule "no reload while an edit is sent or acked" is not needed for acked edits.
- A refused ops batch now says which of its operations the pack already took (`taken`, `718ed9c`), and every pack write route
  reads the caller's role and the pack's deletion again under the pack's lock (`f204a8f`).
- A live stream closed with 4403 pauses on Android (it is only ever about the account), where the web treats it as gone.

| Step | Commit | Step | Commit |
|---|---|---|---|
| W0 | `38e9d7b` | A1 | `5510381` |
| A2 | `f456858` | A3 | `5a16b39` |
| A4 | `bc33909` | W1 | `02100c1` |
| A5 | `60e3d74` | A6 | `fe01c28` |
| A7 | `573883c` | A8 | `4c02e68` |
| A9 | `c228381` | A10 | `99bc493` |

Not in the plan but done on the way: `72ab5dc` (a flaky network test fixed at its cause).

## The owner's decisions (2026-10-08): these override anything below

1. Edits a pack refused are saved as "NAME (my edits)" (the web's myEditsName and the fixture). docs/MISSION_PACKS.md's "(my offline edits)" is corrected in the docs commit.
2. Sign-out keeps unsent pack edits for the SAME account and sends them on that account's next sign-in; never under another account (userId on every outbox row, Call.asUser, AccountScope wipes on a different account). Recorded as a deliberate difference from the web.
3. Edits a pack refused are saved to the Library automatically whenever their pack is not open (closed, draining, in the worker, at enable); while it is open they wait, durable, for the screens' keep/discard offer.
4. feature_disabled, affiliation_required and an ended session PAUSE the queue (nothing dropped); sending resumes at the next enable.
5. The web session bugs (a) acked entries dropped on 423/403/404/settle, (c) reload into a finished pack says read_only, (d) 423 without finished_by ignores finished_at, (e) stale source fields after update-from-original, (h) acked entries stuck after a reload — are being FIXED ON THE WEB first, and session.json regenerated. Port the fixed web behaviour; drop Android-only workarounds for (h) once the web fix lands (re-read packSession.js at HEAD before porting A7).
6. DONE on the web (commit 67554b1): an acked entry carries the seq its result gave and is confirmed once session.seq reaches it (withoutReached, in batchAnswered and reloadSession); batchAnswered marks results acked BEFORE receiving the page; only untaken entries (queued, or sent and refused) are dropped; in a read-only (not gone) session a batch that is out holds what is queued behind it until it is answered, then everything untaken is dropped in edit order; gone drops at once; reloadSession goes through settle (finished -> pack_finished, viewer -> read_only); a 423 takes finished_by/finished_at whenever present; item.replace by me gives the source the server's shape (original 'same', counts null). session.json now has 126 scenarios. PORT THESE, from HEAD. The design's Android-only rule 'no reload while an op is sent or acked' (resolved #9, risk 4) is NOT needed for acked entries any more; keep only what the new web code itself does. Known gap pinned as scenario #94 (a lost answer whose resend is refused): an owner decision on an additive server field is pending.

## Amendments from the W1 review (2026-10-08): these override the sections they name

1. **What W1 pinned.** shared.json's `lzShape` (32 cases) and `routeShape` (26) go beyond commitPlan W1's "routes with ids only, no unknown keys": they pin the web's shape whole, including the old names, flat snapshots, JavaScript's Number() and trim on a target (hex text, lists, "", true, a BOM-only and a U+001C-only grid, a blank target.mgrs hiding mapData's), the status's truthiness (`detectedLZ: []` analysed, `results: 0` not), and route sets with id-less and non-object routes and set-level fields. Four cases are `webBug: true` and must NOT be copied: lzShape "fields this version does not know are dropped: ..." (the web nulls weather, target.elevationFt, analysis.futureAnalysis, graphics.futureGraphics) and routeShape "routes with no id are left out of the shape (the web's editor keeps them)", "routes that are not objects are left out of the shape: ..." and "a set's other fields are dropped, and its version is 1 whatever it was". Today's DiagramNormalizer differs from the unmarked cases on hex text and one-element lists (JsValue.number gives NaN) and on trim (Kotlin's trim keeps U+FEFF and takes U+001C): A8 fixes the port, not the fixture.
2. **PackSharedFixtureTest** replays lzShape and routeShape through the REAL `LzPackKind.currentShape(localId, item)` and `RoutePackKind.currentShape(localId, item)` (production docOf, carry source item.data), never with carryFrom = null: that path would hide a carryUnknown that puts back an old name or a raster. It skips the webBug cases by the mark and adds Kotlin tests of the right behaviour, saying so: the LZ case's four unknown fields come back as they were and flushPlan's reshape sends no null for them; a route set's version 2 and own field are kept; and for id-less and non-object routes, the editor's document (docOf(fromItem(...))) and currentShape agree, so a set holding one sends nothing on open and, after someone else's change, takes theirs once and sends nothing more (the web loops there: confirmed by a probe of usePackRoutes, a batch of two whole-routes patches "edited MISSION 1" every pass).
3. **carryUnknown** puts back only names the web does not read. Diagram's descriptor names are not enough: it must also exclude every name normalizeLzDiagram reads, which shared.json's description lists per level (the test's LZ_READ_NAMES): at the top schemaVersion, status, target, targetLocation, gridInput, mapData, flightData, analysis, graphics, the own fields (id, savedId, dirty, createdAt, updatedAt, view, name), created_at, updated_at, and a flat snapshot's customLZ, detectedLZ, terrainData, results, analysisResults, gridElevation, latLong, doghouses, helicopters, pzMarkers, pzMarker, sectorsOfFire, goArounds, goAround, units, measurements, exportBox, mapStyle, showLZOutline, showHeatmap; in a target object lat, lon, mgrs, latitude, lng, longitude; in an analysis object customLZ, detectedLZ, terrainData, results, analysisResults, gridElevation, latLong; in a graphics object the seven lists, pzMarker, goAround, exportBox. Never terrainData at any level. (If the owner drops carryUnknown instead, the LZ webBug case becomes one to copy, and the mark goes on the web side first.)
4. **RoutePackKind** keeps currentShape = docOf(fromItem(localId, item, null), item.data), so its editor and its shape cannot disagree (the web's bug). But fromItem + routeItemData must give the web's shape on every unmarked routeShape case, and RouteSets.parse/serialize as they stand need not: they move an unreadable route to the end, and whether writeRoute fills a missing or falsy colour by the id hash, elevations {} and ensureRoutePlan's JavaScript spread (a list or text plan by position, a number nothing, null values winning) and the TOT move must be checked against the fixture. Where they cannot, a pack set reads and writes through a JSON-level port of routesFromItem + restoreSketchRoute in both directions, keeping set-level fields and the version as found (webBug above). For the webBug route cases choose one rule for both directions (keeping each such route where it stands is the least lossy) and test it.
5. **For the owner (A15, AGENTS.md §15):** besides the unknown LZ keys: a route set's own fields and version are nulled and made 1 by the reshape; an id-less (or number, text, true, list) route makes usePackRoutes re-send the set without end once anything changes it; a null route, or `routes` that is an object, makes the set fail to open (restoreSketchRoute reads `route.id` of null; `routes.map` of an object).

## Decisions settled while designing

1. Module: core-sync package (minimal) vs new module (fidelity, offline). Decision: a new pure module android/core-missionpacks, package app.ezpztac.missionpacks. It keeps about 4k lines of ports and their fixture and live tests out of core-sync's test task, which already runs live-server scenarios, and keeps Gradle's per-module cache useful. Never call it core-packs: the plan's core-mappacks, /api/map-packs and AppConfig.Services.packs (Dto.kt:99) are all about map packs.
2. Where the engine lives: MissionPacks in core-data (fidelity) vs a pure PackEngine (offline, minimal). Decision: pure PackEngine and PackClient in core-missionpacks, so the whole state machine runs under JUnit 5 with virtual time, the fake server and the real Flask server. core-data adds only Room, the Library keeper and the editor glue.
3. Session representation: JSON trees (fidelity, minimal) vs typed SessionEvent and OpsAnswer (offline). Decision: kotlinx JsonObject trees end to end in the session and engine, ported function by function from packSession.js. The fixtures are JSON, events edit arbitrary paths, and ApiJson's explicitNulls=false would silently drop value:null and after:null from a typed op.
4. Engine calls: typed DTOs then re-encoded (minimal) vs raw JSON (fidelity, offline). Decision: three raw JsonObject calls for the engine (pack document, events, ops), so nulls and fields a newer server adds ride into session.pack. The typed DTOs serve the screens' calls and are held strict by DtoFixtureTest.
5. Threading: confining each client to one thread (fidelity) vs mutex transitions (offline, minimal). Decision: both. Each PackClient runs on dispatcher.limitedParallelism(1), so its flags and timers read like packClient.js. Every session change goes through transition { } under a Mutex: compute, store.write, then publish. Network calls run outside the lock.
6. Coalesced writer (fidelity) vs persisting before publishing (offline, minimal). Decision: persist every transition in one Room transaction before publishing it, writing only the rows that changed by identity. edit() returns only once the ops are on disk, and the state is written as sent before the HTTP call (SyncEngine's rule).
7. Room tables: five (§8, fidelity, offline) vs three (minimal). Decision: four tables. pack holds members and item order as JSON columns. pack_item holds confirmed items. pack_op_outbox holds the outbox and the dropped list, with dropOrder. pack_own holds each person's own fields. No pack_member, because the session replaces the whole list. No pack_event, because History is an online screen and a cache can come later with a v4 AutoMigration. §8 is updated in the same commit.
8. Drop order: missing in offline. Decision: persist dropOrder. droppedVersions applies ops per item in the order they were dropped, not the order they were made: 'what two refusals dropped is put together' in session.json, and the [4,5,1,2,3] case.
9. Restore and reload safety: catch up before reloading (fidelity) vs an Android-only retire step (offline) vs reloading only on diverged (minimal). Decision: no Android-only change to the pure session. A restored session in which any op was sent or acked catches up from its stored seq before any reload or send. While running, a diverged reload waits until no pending op is sent or acked. The latent web bug (a reload jumps seq past our own event, so the op stays acked for ever) goes to the owner, to fix on the web first if wanted.
10. Change detection: reference identity (fidelity, minimal) vs a version counter (offline). Decision: === on JsonElement is a 'maybe changed' hint, exactly as usePackItemSync uses it. Every decision is then made with sameData, so a spurious new instance can only cause an earlier send, never a wrong one.
11. Editor join: an optional pack store (fidelity) vs DiagramSession taking a DocumentStore (minimal) vs RoutingDocumentStore plus bind (offline). Decision: DiagramSession and RouteSession gain `packs: DocumentStore<D>? = null`, and their store routes PackRef ids to it. Existing tests compile unchanged, there is no Hilt cycle (PackItemStore never needs the session), and RouteRepository.release() is never called for a pack id.
12. What triggers a reconcile: every session.active change (fidelity) vs pack changes only (minimal). Decision: a change in the open pack's state, a change in which document is open, and the end of each save. Never per drag frame. DocumentSession's own 600 ms debounce does the web's 'schedule'.
13. Taking someone else's change: two functions through setQuietly (fidelity) vs a new takeIn (offline) vs setQuietly with one carry function (minimal). Decision: setQuietly with one delta function. The delta is diffData(base.doc, theirShape), rebased onto docOf of each version. The baseline is set first, on Main. Applied to the active document it gives exactly theirs; applied to undo history it carries their change into each step. The save that follows sends nothing. No new takeIn.
14. A change tidy makes on open (offline's owe): Decision: add DocumentSession.owe(). Reconcile's SEND_LATER calls it, so headings derived by Doghouses.settle on open are sent, as the web's useDoghouses effect sends them.
15. A save cut off mid-way (minimal): Decision: PackItemStore.save runs engine.edit and the baseline update under NonCancellable, because DocumentSession.flush() cancels the pending save job.
16. How AnalysisService finds a diagram: through the session (fidelity) vs a routing store (offline). Decision: add DocumentSession.document(id), which is the active document or store.open(id). AnalysisService.load uses it, and AnalysisService loses its DiagramRepository parameter.
17. Fields a newer web wrote: carry them (fidelity) vs accept the loss (offline, minimal). Decision: PackLz.carryUnknown puts back top-level, analysis, target and graphics keys this version's Diagram does not know, from the baseline's raw data. Without it, the first reshape from an installed app would send null for a newer web's field, for everyone in the pack. (Amended by W1's review, item 3: never a name the web reads.)
18. The shape fixture (offline): Decision: yes, in web commit W1. lzShape and routeShape (item data to lzItemData(lzDiagramFromItem(...)) and to routeSetData(routesFromItem(...))) go into shared.json, together with the three creation sentences in describe.json, so the reshape cannot churn between clients.
19. Kept edits: offered (fidelity, minimal) vs saved automatically (offline, §8). Decision: dropped ops are always durable in Room. While their pack is open, they wait for the screens to offer keep or discard. Whenever the pack is not open (closed, draining, in the worker, at enable), they are saved to the Library at once through PackKeeper, under a deterministic uuid. Nothing is ever lost and §8 holds. The name follows the fixture, myEditsName = 'NAME (my edits)'.
20. feature_disabled, affiliation_required, or a session that ended: gone (fidelity) vs paused (offline, minimal). Decision: PAUSED. The batch goes back in the queue, nothing is dropped, and sending resumes at the next enable. An admin unticking a tester must not cost the tester's queued edits.
21. A 403 or 404 on load or catch-up: the web shuts down and leaves pending ops unkept (packClient.js:166). Decision: run batchFailed with that status, so the ops land in dropped as gone and are kept to the Library.
22. Sign-out: abandon as the web does vs keep. Decision: keep the queue for the same account, as all three propose. Guards: userId on every outbox row, Call.asUser, and AccountScope counting and wiping pack rows. Flagged for the owner, against §5 step 9's wording.
23. Polling: adaptive (offline) vs a constant 3 s. Decision: 3 s, only while in the foreground and not live, matching the web. Adaptive polling and reconnect jitter are deferred until measured on a device.
24. Item-size pre-check (offline): Decision: no. The server's 413 is handled as on the web: that batch is dropped and kept to the Library.
25. asUser guard (offline): Decision: yes. An internal Call.asUser is checked in executeSigned before anything is sent, and a mismatch throws OtherAccountException. The engine maps it to PAUSED.
26. Background sync in a cold process (offline): Decision: fix it in its own early commit. EngineSyncRunner calls auth.restore() when the state is Unknown, and returns Done unless the data is Ownership.Yours.
27. Drain before or after the Library sync: Decision: packs first, so Library records made from dropped edits go up in the same run.
28. Live socket API: listener (fidelity) vs Flow. Decision: core-network parses frames into LiveMessage behind a PackLiveConnection interface, so tests can fake it. The buffer holds 1000 messages; overflow closes the socket and reports Closed(1013). The hello is the first frame and a token frame follows every new access token. Ping every 25 s.
29. Invitation links: Decision: InviteLinks.parse lives in feature-auth beside AuthLinks and shares SITE_HOSTS. InviteAcceptance, a pure port of useInviteLink, lives in core-missionpacks. AppViewModel keeps the token in SavedStateHandle, the analog of the web's sessionStorage.
30. JavaScript number semantics: public additions to core-model (fidelity, minimal) vs internal (offline). Decision: an internal `Js` object in core-missionpacks, built on the public JsNumber.toText. core-model gains only DiagramJson.encode, which DiagramRepository and PackLz share.
31. Scope: Decision: the pure op builders and sentences are included, plus PackWorkspace.createLz and createRouteSet. PackSeen, History, LastPack, the copy-in orchestration, pack point sets on the map and REFUSED/LOST words are deferred to the screens work.
32. The working tree: fidelity's 'item.replace must be put back' is a mutation harness at work. Port HEAD and the committed fixtures, never the working file, and never touch someone else's uncommitted work.
33. Seq type: Long, so it is the same width in the session, Room and the DTOs.
34. Status OFFLINE (fidelity): Decision: yes. A pack restored from the device whose server has not answered yet can be read and edited.
35. ON_STOP: Decision: AppRoot, which is always composed, calls AppViewModel.appStopped(). It flushes DiagramSession and RouteSession, which closes today's gap where RouteSession is never flushed, then puts the engine in the background and asks for a sync if anything is unsent.
36. Recording feature_disabled: Decision: extend cross_cutting() in test_network_fixtures.py, since the spec already calls it cross-cutting (openapi.yaml:1307), and record it once on GET /api/packs. LiveServer strips REALTIME_PUBLIC_URL from the child's environment.

## Modules

**NEW: android/core-missionpacks**

A pure Kotlin module.

Plugins:
- `ezpz.kotlin-library` (explicitApi, warnings as errors, JUnit 5, `-Xjdk-release=17`)
- `alias(libs.plugins.kotlin.serialization)`
- `java-test-fixtures`

Add `include(":core-missionpacks")` to android/settings.gradle.kts right after `:core-sync`, with a one-line comment: "Mission packs (docs/MISSION_PACKS.md): pure, held to contracts/fixtures/packs; not map packs."

build.gradle.kts, copied from core-sync's:
- `api`:
  - `project(":core-model")`
  - `project(":core-network")`
  - `libs.kotlinx.coroutines.core`
  - `libs.kotlinx.serialization.json`, because public signatures expose JsonObject
- `testFixturesApi`:
  - `project(":core-network")`
  - coroutines-core
  - `platform(libs.junit.bom)`
  - `libs.junit.jupiter.api`
- `testImplementation`:
  - `project(":core-testing")`
  - `testFixtures(project(":core-network"))` (LiveServer, Rig)
  - coroutines-test
  - turbine
- `tasks.test` takes the same backend/**/*.py and EZPZ_LIVE_PYTHON inputs as core-sync/build.gradle.kts:31-39.

It holds:
- **The ports, mirroring frontend/src/feature/missionPacks file by file:**
  - Js.kt
  - PackOps.kt (packOps.js)
  - PackDiff.kt (packDiff.js)
  - PackSession.kt (packSession.js)
  - PackActions.kt (packActions.js plus the creation sentences)
  - PackLz.kt (packLz.js)
  - PackRoutes.kt (packRoutes.js)
  - PackPoints.kt (the pure exports of usePackPoints.js)
  - PackRef.kt (packRef.js)
  - PackFailure.kt and PackMessages.kt (failureOf, MESSAGES, packErrorMessage, joinedMessage)
  - PackItemSync.kt (the decision half of usePackItemSync.js)
  - InviteAcceptance.kt (useInviteLink.js)
- **Android-only files:**
  - PackKind.kt, with LzPackKind and RoutePackKind
  - PackApi.kt, with ApiPackApi
  - PackStore.kt, with InMemoryPackStore
  - PackKeeper.kt
  - PackClient.kt (packClient.js, internal)
  - PackEngine.kt (registry, drain, keeping)
- **testFixtures:** FakePackServer, FakeLive, PackScenarioBook, PackDevice, RecordingKeeper.

It does not depend on core-sync or core-data. Library writes go out through the PackKeeper interface.

**core-network (existing; additive only)**

New files:
- PackDto.kt
- PackEndpoints.kt (37 typed calls and 3 raw engine calls, with id checks)
- PackLive.kt (the WebSocket over OkHttp 4.12's own newWebSocket; no new dependency)

Edits:
- ApiException.kt: a 5th parameter, `body: JsonObject? = null`, and a new class OtherAccountException.
- ApiClient.kt:
  - map() sets `body`
  - an internal accessTokens flow
  - newWebSocket
  - `Call.asUser`, checked in executeSigned
- testFixtures LiveServer:
  - `makeAccount(..., features, name): Int`
  - startOrNull removes REALTIME_PUBLIC_URL from the child's environment

**core-model (existing)**

New file DiagramJson.kt: `DiagramJson.encode(Diagram): JsonObject`, moved out of DiagramRepository.kt:51 and :128.

**core-data (existing, Android)**

Dependencies:
- `api(project(":core-missionpacks"))`
- `testImplementation(testFixtures(project(":core-missionpacks")))`

New files:
- PackEntities.kt, PackDao.kt, RoomPackStore.kt
- LibraryPackKeeper.kt
- PackItemStore.kt, PackEditorSync.kt, PackWorkspace.kt

Changed files:
- EzpzDatabase v3, plus schemas/app.ezpztac.data.EzpzDatabase/3.json, committed
- AccountScope.kt (wipe, and ownership counts)
- DataModule.kt (providers)
- DocumentSession.kt (`document(id)` and `owe()`)
- DiagramSession.kt and RouteSession.kt (an optional packs store)
- AnalysisService.kt (load goes through the session; the repository parameter is removed)

**feature-auth (existing)**

New file InviteLinks.kt, beside AuthLinks in AuthRoute.kt.

**app (existing)**

New files:
- PackRuntime.kt (the seam AppViewModel and the runner use), with EnginePackRuntime
- NetworkWatcher.kt
- PackInvites.kt, with ApiPackInvites

Changed files:
- AppViewModel.kt
- AppRoot.kt (ON_STOP)
- EzpzApplication.kt (starts NetworkWatcher)
- sync/SyncRunner.kt
- di/AppModule.kt (`@Provides packApi(client): PackApi = ApiPackApi(client)`, and `PackInvites`)

**backend/tests/test_network_fixtures.py**

A `packs()` section. No production backend change.

**frontend (web first)**

Web commit W1 only: the creation sentences are extracted into packActions.js, and the shape and creation cases are added to the pack fixtures.

**Dependency edges**

- app → {feature-*, core-data, core-missionpacks (through core-data), core-network}
- core-data → core-missionpacks → core-network → core-model
- core-data → core-sync → core-network
- feature-auth → core-network, unchanged

Features never depend on each other and nothing depends on app. Pure modules stay JUnit 5; core-data and app stay JUnit 4 with Robolectric. No new library.

## Types

Every declaration in a pure module is explicitly `public` or `internal`. Ops, events, pack bodies and answers are always JsonObject trees.

=== core-network ===

**ApiException.kt**
- `public open class ApiException(public val status: Int, public val code: String?, message: String, cause: Throwable? = null, public val body: JsonObject? = null)`. map() (ApiClient.kt:379) passes `error.json` in its else branch, so `reason`, `index`, `item`, `finished_by` and `finished_at` survive.
- `public class OtherAccountException(message: String) : ApiException(0, "other_account", message)`. Thrown before sending when `Call.asUser` is not the stored session's user.

**ApiClient internals**
- `internal class Call(..., val asUser: Int? = null)`. executeSigned checks `sessions.read()?.user?.id == asUser` before any send.
- `private val tokens = MutableStateFlow<String?>(null)` and `internal val accessTokens: StateFlow<String?>`.
  - Set in restore() (:95) and in store() right after sessions.write (:234).
  - Cleared in end(), endedWithoutStoring(), logout() and endSessionIfOfflineTooLong().
- `internal fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket`, over a client built lazily once with `http.newBuilder().pingInterval(25, SECONDS)` and the X-EZPZ-Client header.

**PackDto.kt**
All strict, @Serializable, snake_case through @SerialName, nullable fields `= null`, opaque JSON as JsonElement or JsonObject.
- `PackPerson(id: Int? = null, name: String)`
- `PackTeam(id, name, memberCount, role: String? = null)`
- `PackItemCounts(lz, route, pointset)`
- `PackSummary(uuid, name, description, status, role, owner: PackPerson, team: PackTeam? = null, headSeq: Long, seenSeq: Long, memberCount, audienceCount, itemCount, itemCounts, finishedAt: String? = null, finishedBy: PackPerson? = null, createdAt, updatedAt)`
- `Pack`: the same fields, plus `members: List<PackMember>`, `items: List<PackItemDto>` and `liveUrl: String? = null`
- `PackMember(userId, name, email, role, addedAt, seenAt: String? = null)`
- `PackItemDto(uuid, kind, name, revision, seq: Long, createdBy: PackPerson? = null, updatedBy: PackPerson? = null, createdAt, updatedAt, source: PackItemSource? = null, data: JsonElement? = null)`
- `PackItemSource(kind, uuid: String? = null, revision, original: String? = null, originalUpdatedAt: String? = null, packChanges: Int? = null, lastPackChange: PackChange? = null)`
- `PackChange(actor: PackPerson, summary, createdAt)`
- `PackEvent(seq: Long, type, item: String? = null, actor: PackPerson, summary, status, reason: String? = null, clientOpId: String? = null, op: JsonObject, createdAt)`
- `PackOpResult(clientOpId, seq: Long, status, reason: String? = null)`
- `PackOpsResult(headSeq: Long, results, events, hasMore)`
- `PackEventPage(events, cursor: Long, hasMore, headSeq: Long)`
- `PackSeenDto(seenSeq: Long, seenAt)`
- `PackItemReply(item: PackItemDto, event: PackEvent? = null, headSeq: Long)`
- `LibraryCopy(kind, id, clientUuid, name, revision)`
- `Invite(id, email: String? = null, role, status, invitedBy: PackPerson? = null, expiresAt, createdAt, pack: InvitePack? = null, team: InviteTeam? = null)`
- `InvitePack`, `InviteTeam`
- `InviteReply(invite, emailSent: Boolean? = null, token: String? = null)`
- `InviteAccepted(invite, pack: PackSummary? = null, team: TeamSummary? = null)`
- `TeamSummary(id, name, role, memberCount, createdAt)`
- `Team(... + members: List<TeamMember>)`
- `TeamMember(userId, name, email, role, joinedAt)`
- `UserBrief(id, name, email)`
- `StatusReply(status)`
- Internal wrappers: PacksBody, TeamsBody, InvitesBody, UsersBody, MemberBody, InviteBody, ItemBody.
- `public sealed interface TeamShare { data object Unchanged; data object None; data class With(val teamId: Int, val role: String) }`
- `public sealed interface PackSource { data class Id(val kind: String, val id: Int); data class ClientUuid(val kind: String, val uuid: String) }`

**PackEndpoints.kt**
Extensions on ApiClient. Every path id is checked first by `internal object PackPaths { fun pack(uuid: String): String; fun item(id: String): String }`:
- A pack uuid must match `^[A-Za-z0-9-]{1,36}$`.
- An item id must match `^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$` (pack_ops.py:35).
- Anything else throws IllegalArgumentException. send() splits on '/' (ApiClient.kt:315), and OkHttp resolves '..'.

Packs:
- `listPacks(): List<PackSummary>`
- `createPack(name: String, description: String? = null, share: TeamShare = TeamShare.Unchanged, uuid: String? = null): Saved<Pack>`
- `pack(uuid): Pack`
- `updatePack(uuid, name: String? = null, description: String? = null, share: TeamShare = TeamShare.Unchanged): Pack`. The body is a buildJsonObject, so None sends `team_id: null`.
- `deletePack(uuid): StatusReply`
- `finishPack(uuid): Pack`
- `reopenPack(uuid): Pack`
- `duplicatePack(uuid, name: String? = null): Pack`
- `packEvents(uuid, since: Long, limit: Int = 500): PackEventPage`
- `markPackSeen(uuid, seq: Long): PackSeenDto`

Items:
- `copyIntoPack(uuid, source: PackSource, item: String, name: String? = null, summary: String? = null): Saved<PackItemReply>`
- `packItem(uuid, item): PackItemDto`
- `updateFromOriginal(uuid, item, summary: String? = null): PackItemReply`
- `savePackItemToLibrary(uuid, item, name: String? = null): LibraryCopy`

Members:
- `addPackMember(uuid, userId: Int, role: String = "editor"): PackMember`
- `changePackMember(uuid, userId, role): PackMember`
- `removePackMember(uuid, userId): StatusReply`

Pack invitations:
- `packInvites(uuid): List<Invite>`
- `inviteToPack(uuid, email, role = "editor"): Saved<InviteReply>`
- `resendPackInvite(uuid, inviteId: Int): InviteReply`
- `revokePackInvite(uuid, inviteId): Invite`

The caller's invitations:
- `myInvites(): List<Invite>`
- `acceptInvite(id: Int): InviteAccepted`
- `declineInvite(id): Invite`
- `acceptInviteLink(token: String): InviteAccepted`

Teams:
- `listTeams(): List<TeamSummary>`
- `createTeam(name): Team`
- `team(id): Team`
- `renameTeam(id, name): Team`
- `deleteTeam(id): StatusReply`
- `changeTeamMember(id, userId, role): Team`
- `removeTeamMember(id, userId): StatusReply`
- `teamInvites(id): List<Invite>`
- `inviteToTeam(id, email: String? = null, role = "member"): InviteReply`
- `revokeTeamInvite(id, inviteId): Invite`

People: `searchPeople(q: String): List<UserBrief>`.

Raw calls for the engine:
- `packDocument(uuid, asUser: Int? = null): JsonObject` (NORMAL priority)
- `packEventsDocument(uuid, since: Long, limit: Int = 500, background: Boolean, asUser: Int? = null): JsonObject` (BACKGROUND priority when `background`)
- `sendPackOps(uuid, batch: JsonObject, asUser: Int? = null): JsonObject` (NORMAL; not heavy)

**PackLive.kt**
- `public sealed interface LiveMessage`:
  - `Welcome(headSeq: Long, role: String?, status: String?, you: JsonObject?)`
  - `Event(seq: Long, event: JsonObject)`
  - `Head(seq: Long)`
  - `data object Resync`
  - `Presence(people: List<JsonObject>)`
  - `Closed(code: Int, reason: String?)`, with `packGone` (4404, 4410 only) and `reconnect` (every close but a gone pack, 4400, 4403 and the local 4401 `Closed.SIGNED_OUT`), as built in A4
- `public interface PackLiveConnection { val messages: Flow<LiveMessage>; fun presence(focus: JsonObject?): Boolean; fun close(code: Int = 1000) }`
  - presence returns false when the frame is over 1 KB or before the welcome.
- `public suspend fun ApiClient.openPackLive(liveUrl: String, packUuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection?` (asUser required, A4 review)
  - Null when signed out or someone other than asUser is signed in, when the URL is not ws:// or wss://, or when the uuid fails PackPaths.
  - Ends with Closed(4401, "signed_out"), reconnect false, once asUser is no longer the account signed in.
  - The hello `{type:hello, pack, token}` is sent right after newWebSocket.
  - `{type:token, token}` is sent on each new accessTokens value.
  - Frames for another pack, and `closed`, are ignored.
  - A failure is reported as Closed(1006) exactly once.
  - The channel holds 1000 messages; overflow closes the socket and reports Closed(1013).

=== core-model ===
`public object DiagramJson { public fun encode(diagram: Diagram): JsonObject }`, using `Json { encodeDefaults = true; explicitNulls = true }`. DiagramRepository's private serialize calls it.

=== core-missionpacks: pure, fixture-held ===

**Js.kt** (`internal object Js`)
- `orderedKeys(keys: Collection<String>): List<String>`: canonical array indices (< 2^32-1) first, ascending, then insertion order.
- `strictEquals(a: JsonElement?, b: JsonElement?): Boolean`: numbers by double, so 1 === 1.0; "1" is never 1; an object or array never equals another.
- `idKey(id: JsonPrimitive): String` is `"string:"+s` or `"number:"+JsNumber.toText(d)`.
- `isId(e)`
- `read(o: JsonObject?, key: String): JsRead`, where `internal sealed interface JsRead { data class Own(val value: JsonElement); data object Inherited; data object Missing }`. Inherited covers the 11 Object.prototype names diff.json pins.
- `truthy(e)`
- `toNumber(e): Double`: ECMAScript ToNumber, with JS whitespace trimmed, 0x/0o/0b, Infinity, '' is 0, `[x]` is ToNumber(x), an object is NaN.
- `round(d): Double` (Math.round)
- `templateText(e): String` (String(v) in a template literal; -0 prints as 0)
- `slice16(s, n)` (UTF-16 units)

**PackOps.kt**
- `public data class PackItemState(val kind: String, val name: String, val data: JsonElement, val deleted: Boolean)`
- `public enum class OpStatus { APPLIED, SKIPPED, INVALID }`
- `public data class OpResult(val items: Map<String, PackItemState>, val status: OpStatus, val reason: String?)`
- `public object PackOps`:
  - `ITEM_KINDS`, `CLIENT_OP_TYPES`, `SERVER_OP_TYPES`, `MAX_NAME_LENGTH = 100`, `ITEM_ID: Regex`
  - `fun validate(op: JsonElement?): String?`
  - `fun apply(items: Map<String, PackItemState>, op: JsonElement): OpResult`
- It is immutable and copies along the path only. Untouched entries keep their instance. A new key is appended; an existing key keeps its place.

**PackDiff.kt**
- `public class PackDiffException(message: String) : IllegalArgumentException(message)`
- `public object PackDiff`:
  - `fun sameData(a: JsonElement?, b: JsonElement?): Boolean` (Kotlin null is undefined, JsonNull is null)
  - `fun diffData(before: JsonElement?, after: JsonElement?): List<JsonObject>` (throws PackDiffException)
  - `fun diffItem(item: String, before: JsonElement?, after: JsonElement?): List<JsonObject>`
- longestRising is ported exactly, and keys are walked in Js.orderedKeys order. The __proto__ and constructor quirks are kept as diff.json pins them.

**PackSession.kt**
- `public enum class PendingState { QUEUED, SENT, ACKED }`
- `public data class PendingOp(val op: JsonObject, val state: PendingState)`
- `public data class DroppedOp(val op: JsonObject, val reason: String)`
- `public data class PackSession(val me: Int, val pack: JsonObject, val members: List<JsonObject>, val confirmed: Map<String, PackItemState>, val info: Map<String, JsonObject>, val order: List<String>, val seq: Long, val pending: List<PendingOp>, val dropped: List<DroppedOp>, val readOnly: Boolean, val gone: String?, val diverged: Boolean, val view: Map<String, PackItemState>)`, with the getters `uuid`, `status`, `role`, `liveUrl`, `headSeq`.
- Result types: `public data class Edited(val session: PackSession, val refused: String?)`, `NextBatch(session, batch: JsonObject /*{ops, base_seq}*/)`, `Received(session, gap: Boolean)`, `Answered(session, catchUp: Boolean)`.
- `public object PackSessions` with `MAX_BATCH = 200`:
  - `open(pack: JsonObject, me: Int)`
  - `reload(s, pack)`
  - `edit(s, ops: List<JsonElement>, newId: () -> String): Edited`
  - `nextBatch(s): NextBatch?`
  - `receive(s, events: List<JsonObject>): Received`
  - `batchAnswered(s, answer: JsonObject): Answered`
  - `batchFailed(s, failure: PackFailure): PackSession`
  - `abandon(s, reason)`
  - `visibleItems(s): List<JsonObject>`
  - `toJson(s): JsonObject` (fixtures)
  - Android only:
    - `restored(s) = batchFailed(s, PackFailure(0))`
    - `hasSentOrAcked(s): Boolean`
    - `withoutDropped(s, clientOpIds: Set<String>)`
  - ITEM_EVENTS includes item.replace.

**PackFailure.kt**
- `public data class PackFailure(val status: Int, val code: String? = null, val reason: String? = null, val finishedBy: JsonElement? = null, val finishedAt: JsonElement? = null, val item: String? = null, val retryAfterSeconds: Long? = null)`
  - `val retryable` = status 0, 429 or 5xx
  - `val pauses` = OtherAccountException, SessionEndedException, AffiliationRequiredException, or code feature_disabled
- `companion fromJson(JsonObject)`
- `companion of(t: Throwable)`:
  - NetworkException → 0
  - ApiException(unreadable_response) on a 2xx → 0
  - RateLimitedException → 429, with Retry-After
  - any other ApiException → status, code and body fields
  - any other Throwable → 0

**PackActions.kt**
- `public data class ItemVersion(val uuid: String, val kind: String, val name: String, val data: JsonElement)`
- `public object PackActions`:
  - `PREFIX`
  - `packItemId(kind, id)`
  - `renameSummary(actor: String?, from, to)`
  - `deleteSummary(actor, name)`
  - `copySummary(actor, kind, recordName: String?)`
  - `addedLzSummary(actor, label)`
  - `addedRouteSetSummary(actor, label)`
  - `addedPointSetSummary(actor, label, count: Int)` (en-US grouping by hand)
  - `deleteItemOp(uuid, name, actor): JsonObject`
  - `renameItemOp(uuid, name, actor, from): JsonObject`
  - `createItemOp(uuid, kind, name, data, summary): JsonObject`
  - `droppedVersions(s: PackSession): List<ItemVersion>`
  - `myEditsName(name): String` ("$name (my edits)", cut at 100 UTF-16 units)

**PackLz.kt**
- `public object PackLz`:
  - `OWN_FIELDS`
  - `sharedLzData(data: JsonElement?): JsonObject`
  - `describeLzChange(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String`
  - `lzItemData(d: Diagram): JsonObject` = sharedLzData(DiagramJson.encode(DiagramNormalizer.serialize(d)))
  - `lzDiagramFromItem(localId: String, item: PackItemView, view: JsonElement?, env: DiagramNormalizer.Environment): Diagram`
  - `carryUnknown(raw: JsonElement?, doc: JsonObject): JsonObject`: puts back keys not among the descriptor element names of Diagram, DiagramAnalysis, DiagramTarget and DiagramGraphics, at those four levels only. Never view. (Amended by W1's review, item 3: nor any name the web reads there, legacy and flat-snapshot names included, and never terrainData.)

**PackRoutes.kt**
- `public object PackRoutes`:
  - `routeSetData(routes: JsonArray?)`
  - `sharedRouteData(data)`
  - `describeRouteChange(before, after, name, actor)`
  - `routeItemData(set: RouteSet)` = sharedRouteData(RouteSets.serialize(set.copy(mission = null)))
  - `routeSetFromItem(localId, item: PackItemView, hidden: Set<String>): RouteSet` (W1's review, item 4: with routeItemData, must give routeShape's unmarked cases)

**PackPoints.kt**
- `public object PackPoints { pointsForPack(points: JsonElement?): JsonArray; describePointsChange(before, after, name, actor): String }`

**PackRef.kt**
- `public data class PackItemRef(val pack: String, val item: String)`
- `public object PackRef { fun localId(pack, item) = "pack:$pack:$item"; fun parse(id: String?): PackItemRef? }`, matching `^pack:([^:]+):(.+)$`.

**PackMessages.kt**
- `public object PackMessages { fun words(f: PackFailure): String; fun joinedMessage(a: InviteAccepted): String }`. The app's own words only, never a server's.

**PackItemSync.kt**
- `public class PackBaseline(public val data: JsonElement?, public val name: String, public val doc: JsonElement, public val carryFrom: JsonElement?)`. `data == null` means JUST_SENT; `data` is compared with ===.
- `public enum class Reconcile { NOTHING, SETTLE, SEND_LATER, FLUSH_FIRST, TAKE_THEIRS, REMOVED }`
- `public data class FlushPlan(val ops: List<JsonObject>, val summary: String, val sentName: String, val sentDoc: JsonElement)`
- `public object PackItemSync`:
  - `fun reconcile(base: PackBaseline?, item: PackItemView?, mineDoc: JsonElement, mineName: String, readOnly: Boolean, theirShape: () -> JsonElement): Reconcile` (usePackItemSync.js:111-151, branch for branch)
  - `fun flushPlan(uuid: String, base: PackBaseline, item: PackItemView, mineDoc: JsonElement, mineName: String, shared: JsonElement, theirShape: JsonElement, describe: (JsonElement, JsonElement, String?) -> String, actor: String?): FlushPlan?` (lines 70-97: rename, then the reshape only with content changes, then the changes, all under one summary)
  - `fun takeTheirsDelta(base: PackBaseline, theirShape: JsonElement): List<JsonObject>?` (null when diffData throws)
  - `fun rebase(doc: JsonElement, delta: List<JsonObject>, kind: String): JsonElement` (folds PackOps.apply; a skipped op passes)

**PackKind.kt**
- `public interface PackKind<D : Any>`:
  - `val kind: String`
  - `fun shared(data: JsonElement?): JsonElement`
  - `fun fromItem(localId: String, item: PackItemView, own: JsonObject?): D`
  - `fun docOf(document: D, carryFrom: JsonElement?): JsonElement`
  - `fun currentShape(localId: String, item: PackItemView): JsonElement` (default: docOf(fromItem(localId, item, null), item.data))
  - `fun nameOf(document: D): String`
  - `fun ownOf(document: D): JsonObject`
  - `fun withShared(document: D, shared: JsonElement, name: String): D` (keeps the document's own fields)
  - `fun describe(before: JsonElement, after: JsonElement, name: String?, actor: String?): String`
- `public class LzPackKind(env: DiagramNormalizer.Environment = DiagramNormalizer.Environment()) : PackKind<Diagram>`. Its own fields are `{view}`; createdAt and updatedAt are kept from the document.
- `public object RoutePackKind : PackKind<RouteSet>`. Its own fields are `{hidden: [routeId]}`.

=== core-missionpacks: engine ===

**PackApi.kt**
- `public interface PackApi`:
  - `suspend fun getPack(uuid: String, asUser: Int): JsonObject`
  - `suspend fun events(uuid: String, since: Long, limit: Int, background: Boolean, asUser: Int): JsonObject`
  - `suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject`
  - `suspend fun openLive(liveUrl: String, uuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection?`
- `public class ApiPackApi(private val client: ApiClient) : PackApi`

**PackStore.kt**
- `public interface PackStore`:
  - `suspend fun load(pack: String, me: Int): PackSession?`: as stored, with sent and acked states. Null when absent or another account's.
  - `suspend fun write(before: PackSession?, after: PackSession, me: Int)`: atomic, identity-diffed.
  - `suspend fun forget(pack: String)`
  - `suspend fun owed(me: Int): List<String>`: packs with queued or sent ops.
  - `suspend fun withDropped(me: Int): List<String>`
  - `suspend fun unsentCount(me: Int): Int`: queued, sent and dropped.
  - `suspend fun touch(pack: String, at: Long)`
  - `suspend fun prune(me: Int, keep: Int = 10)`
  - `suspend fun own(pack: String, item: String): JsonObject?`
  - `suspend fun putOwn(pack: String, item: String, own: JsonObject)`
- `public class InMemoryPackStore : PackStore`: a Mutex, not re-entrant.

**PackKeeper.kt**
- `public interface PackKeeper { suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> }`
- `public data class KeptVersion(val key: String /* pack:item:firstDroppedClientOpId */, val kind: String, val name: String /* already myEditsName */, val data: JsonElement)`
- `public sealed interface KeptOutcome { data class Saved(val key: String, val kind: String, val libraryUuid: String, val name: String); data class NothingToKeep(val key: String, val reason: String) }`

**PackClient.kt and PackEngine.kt** (public types)
- `public enum class PackStatus { LOADING, OFFLINE, POLLING, LIVE, ERROR, PAUSED, GONE }`
- `public data class PackItemView(val uuid: String, val kind: String, val name: String, val data: JsonElement /* the same instance as session.view */, val info: JsonObject?, val pendingCreate: Boolean)`
- `public data class PackState(val uuid: String, val status: PackStatus, val session: PackSession?, val items: List<PackItemView>, val people: List<JsonObject>, val error: PackFailure?) { fun item(uuid: String): PackItemView?; val readOnly: Boolean }`
- `public data class PackTiming(val pollMs: Long = 3_000, val presenceMs: Long = 100, val retryMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000), val eventsPage: Int = 500)`
- `public data class PackUser(val id: Int, val name: String)`
- `public enum class DrainOutcome { DONE, RETRY, PAUSED }`
- `public sealed interface PackNotice`:
  - `Refused(pack, localId: String?, reason)`
  - `ItemRemoved(localId, name)`
  - `Dropped(pack, count, reasons: Set<String>)`: while the pack is open
  - `Kept(pack, packName, saved: List<KeptOutcome.Saved>, reasons: Set<String>)`
  - `NothingToKeep(pack, key, reason)`
  - `OwnSkipped(pack, count)`
  - `Gone(pack, why)`
  - `Paused(pack, code)`
- `internal class PackClient(...)`: see the engine field.
- `public class PackEngine(api: PackApi, store: PackStore, keeper: PackKeeper, scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.Default, requestBackgroundDrain: () -> Unit = {}, timing: PackTiming = PackTiming(), newId: () -> String = { UUID.randomUUID().toString() }, now: () -> Long = System::currentTimeMillis)`:
  - `val me: StateFlow<PackUser?>`
  - `val open: StateFlow<PackState?>`
  - `val notices: SharedFlow<PackNotice>`
  - `suspend fun enable(user: PackUser)`
  - `suspend fun disable()`
  - `fun foreground(visible: Boolean)`
  - `fun wake()`
  - `suspend fun openPack(uuid: String)`
  - `suspend fun closePack()`
  - `suspend fun edit(pack: String, ops: List<JsonObject>): String?`: the refusal reason, or null once the ops are durable
  - `fun setFocus(focus: JsonObject?)`
  - `fun refresh()`
  - `suspend fun keepDropped(pack: String)`
  - `suspend fun discardDropped(pack: String)`
  - `suspend fun drainAll(user: PackUser, timeoutMs: Long = 60_000): DrainOutcome`
  - `suspend fun unsentCount(): Int`

**InviteAcceptance.kt**
- `public sealed interface InviteState { data object None; data class Waiting(val message: String); data object Accepting; data class Joined(val answer: InviteAccepted, val message: String); data class Failed(val message: String, val retryable: Boolean) }`
- `public class InviteAcceptance(accept: suspend (String) -> InviteAccepted) { val state: StateFlow<InviteState>; suspend fun run(token: String?, enabled: Boolean): Boolean /* true = clear the token */; fun dismiss() }`. Single-flight per token. Retryable on status 0, 429 and 5xx.

=== core-data ===
- Internal entities and DAO: see persistence. `internal class RoomPackStore(db: EzpzDatabase) : PackStore`.
- `class LibraryPackKeeper @Inject constructor(store: SyncStore, sync: SyncRepository, scheduler: SyncScheduler, env: DiagramNormalizer.Environment) : PackKeeper`. The uuid is `UUID.nameUUIDFromBytes("ezpz-pack-kept:$key")`. It checks `store.transaction { record(kind, uuid) }`, which sees tombstones, before SyncRepository.create.
  - LZ: DiagramJson.encode(DiagramNormalizer.serialize(normalize(data + {id: uuid, name})))
  - ROUTE: `{version: 1, routes}`
  - POINT_SET: `{points}`; an empty list is NothingToKeep(empty_point_set)
- `class PackItemStore<D : Any>(engine: PackEngine, kind: PackKind<D>, store: PackStore, idOf: (D) -> String, main: CoroutineDispatcher) : DocumentStore<D>`:
  - `override suspend fun open(uuid: String): D?`
  - `override suspend fun save(document: D): D`
  - `override fun release(uuid: String)`
  - `internal val saved: SharedFlow<String>`
  - `internal val putBack: SharedFlow<Pair<String, String>>` (localId, reason)
  - `internal fun baseline(localId): PackBaseline?`, `setBaseline(localId, PackBaseline?)`, `isSaving(localId): Boolean`: all on Main.
- `internal class PackEditorSync<D : Any>(session: DocumentSession<D>, items: PackItemStore<D>, kind: PackKind<D>, engine: PackEngine, keeper: PackKeeper, idOf: (D) -> String, presence: Boolean) { fun start(scope: CoroutineScope) }`
- `@Singleton class PackWorkspace @Inject constructor(engine: PackEngine, diagrams: DiagramSession, routes: RouteSession, lz: PackItemStore<Diagram>, rt: PackItemStore<RouteSet>, keeper: PackKeeper, lzKind: LzPackKind)`:
  - `fun start()` (idempotent)
  - `suspend fun openPack(uuid: String)`
  - `suspend fun closePack()`
  - `suspend fun openItem(itemUuid: String): Boolean`
  - `suspend fun createLz(target: DiagramTarget, name: String?): String?`: the local id, or null with a Refused notice
  - `suspend fun createRouteSet(name: String?): String?`
- `DocumentSession`: `suspend fun document(id: String): D?` and `fun owe()` (marks the open document unsaved and starts the usual debounce if no save is pending).
- `DiagramSession(repository: DiagramRepository, scope: CoroutineScope, packs: DocumentStore<Diagram>? = null)` and `RouteSession(repository: RouteRepository, scope: CoroutineScope, packs: DocumentStore<RouteSet>? = null)`. The anonymous store routes PackRef ids to `packs`, both open and save; release goes to packs.release for a pack id and repository.release() otherwise.
- `AnalysisService(api: TerrainApi, session: DiagramSession, scope: CoroutineScope, applyContext: CoroutineContext)`. load(id) = session.document(id).

=== feature-auth ===
`object InviteLinks { const val PARAM = "invite"; val TOKEN = Regex("^[A-Za-z0-9_-]{16,200}$"); fun parse(link: String?, hosts: Set<String> = AuthLinks.SITE_HOSTS): String? }`. https only, and only on the site's hosts.

=== app ===
- `interface PackRuntime { suspend fun enable(user: PackUser); suspend fun disable(); fun foreground(visible: Boolean); fun wake(); suspend fun unsentCount(): Int; suspend fun drainAll(user: PackUser): DrainOutcome; fun start() }`, with `@Singleton class EnginePackRuntime @Inject constructor(engine: PackEngine, workspace: PackWorkspace) : PackRuntime`.
- `interface PackInvites { suspend fun accept(token: String): InviteAccepted }`, with `ApiPackInvites(client)`.
- `@Singleton class NetworkWatcher @Inject constructor(@ApplicationContext ctx: Context, packs: PackRuntime) { fun start() }`. registerDefaultNetworkCallback's onAvailable calls packs.wake().
- AppViewModel gains the constructor parameters `packs: PackRuntime`, `invites: PackInvites` and `savedState: SavedStateHandle`, plus `val inviteState: StateFlow<InviteState>`, `fun retryInvite()`, `fun dismissInvite()` and `fun appStopped()`.
- `EngineSyncRunner(engine, auth, accounts: AccountScope, packs: PackRuntime, diagrams, routes)`.

## Persistence

**EzpzDatabase goes to version 3** (EzpzDatabase.kt:17-23).

- Entities: add PackEntity, PackItemEntity, PackOpEntity and PackOwnEntity.
- `version = 3`, with `autoMigrations = [AutoMigration(1, 2), AutoMigration(2, 3)]` and a comment: "3: mission packs: four new tables, nothing existing altered".
- Only tables are added, so the AutoMigration needs no spec. `open()` keeps no destructive fallback, so an installed app updates in place.
- Add `abstract fun packDao(): PackDao`.
- Commit `core-data/schemas/app.ezpztac.data.EzpzDatabase/3.json`, written by Room KSP through `room.schemaLocation`, in the same commit.
- No foreign keys, as in v2. Children are deleted explicitly inside one transaction.
- JSON columns are TEXT, written with a plain `Json`. A JsonPrimitive keeps its literal text, so `1.0` round-trips as `1.0`.

**Tables**

1. `pack`
   - Primary key `uuid TEXT`.
   - `userId INTEGER`: whose copy this is.
   - `meta TEXT`: session.pack, which is the GET body less items and members, with head_seq kept current by withHead.
   - `members TEXT`: a JSON array.
   - `itemOrder TEXT`: a JSON array, session.order.
   - `seq INTEGER`
   - `readOnly INTEGER`
   - `gone TEXT NULL`
   - `diverged INTEGER`
   - `openedAt INTEGER`
2. `pack_item`
   - Primary key (`packUuid`, `uuid`).
   - `kind TEXT`, `name TEXT`
   - `data TEXT`: the confirmed JSON, `null` once deleted.
   - `deleted INTEGER`. Deleted rows stay, so a reused uuid is skipped as item_exists.
   - `info TEXT NULL`: the item without data, from session.info; null for a deleted item.
3. `pack_op_outbox`
   - `localSeq INTEGER PRIMARY KEY AUTOINCREMENT`: the order the edits were made in.
   - `packUuid TEXT`, `userId INTEGER`, `clientOpId TEXT`
   - `op TEXT`: as it is sent, with client_op_id and summary and only the documented keys.
   - `state TEXT`: queued, sent, acked or dropped.
   - `reason TEXT NULL`
   - `dropOrder INTEGER NULL`: max+1 when dropped, so the dropped list keeps the web's order, which is not edit order.
   - `seq INTEGER NULL`: an acked op's event number, as its result named it (null: it named none, or the op is not acked). Owner decision 6: an acked op is confirmed once session.seq reaches it, so it must survive a restart.
   - A unique index on (`packUuid`, `clientOpId`), and an index on (`packUuid`, `state`).
4. `pack_own`
   - Primary key (`packUuid`, `itemUuid`).
   - `own TEXT`: this person's own fields, never sent. For an LZ, `{view}`; for a route set, `{hidden: [routeId]}`.
   - Point-set colour and visibility stay in PointSetViews, keyed by the local id.

**PackDao** (internal)
- `pack(uuid)`, `items(pack)`, `ops(pack)`, `own(pack, item)`
- `upsertPack`, `upsertItems`, `deleteItems(pack, uuids)`
- `insertOps`, `updateOps`, `deleteOps(pack, clientOpIds)`
- `upsertOwn`
- `owed(me): List<String>`: `SELECT DISTINCT packUuid FROM pack_op_outbox WHERE userId = :me AND state IN ('queued','sent')`
- `withDropped(me)`
- `unsentOrDropped(): Int`: queued, sent and dropped; acked ops are already on the server.
- `forget(pack)`: four deletes.
- `wipe()`: four deletes.
- `prunable(me, keep)`

Use @Upsert, never `@Insert(REPLACE)`, which deletes and re-inserts (SyncDao's note). Order always comes from itemOrder, localSeq and dropOrder, never from rowid.

**RoomPackStore.write(before, after, me)**
One `database.withTransaction`:
- Upsert the pack row when `before?.pack !== after.pack`, or members, order, seq, readOnly, gone or diverged changed.
- Upsert each item whose `confirmed[u] !== before.confirmed[u]` or `info[u] !== before.info[u]`.
- Delete items missing from `after.confirmed`; that happens only on a reload.
- Diff ops by client_op_id across pending and dropped: insert new ones in list order, update a changed state, reason or dropOrder, and delete those that are gone (confirmed by their event, or discarded or kept).

Untouched entries keep their instance in the Kotlin session (PackSessionIdentityTest pins this), so an event someone else makes rewrites one item row, not the pack. Items can be 5 MB.

**load(pack, me)**
Returns null if the row's userId is not `me`. That is a backstop; AccountScope.wipe should already have run. Otherwise it rebuilds the session:
- confirmed and info from pack_item
- order, members and meta from pack
- pending from rows queued, sent or acked, by localSeq
- dropped from rows dropped, by dropOrder
- view folded by `PackSessions.withView` (pending applied over confirmed, nothing else changed), never by `restored` or anything that settles: sent stays sent, acked stays acked with its seq, a read-only session keeps the queued ops it holds for an answer (corrected in A9's review)

The client first asks `hasSentOrAcked` on the stored session, to decide whether to catch up before anything else, and applies `restored` itself only just before it sends again, after that catch-up. `PackStoreContract` (core-missionpacks testFixtures) holds a store to this: RoomPackStoreTest runs its cases under Robolectric, as InMemoryPackStoreTest does.

**Durability order**
- `PackEngine.edit()` returns only after the transaction holding the new queued rows commits. So `PackItemStore.save` returns, and `DocumentSession.write` clears `unsaved`, only once the edit is on disk.
- `nextBatch` is persisted as sent before `sendOps`.
- An answer, a catch-up page, a received event or a reload is one transaction: seq, confirmed items and the removal of confirmed ops always agree. A crash between pages resumes from the last whole page.
- The keeper writes Library records first, each in its own SyncStore transaction, then the dropped rows are deleted. The deterministic uuid makes a repeat after a crash a no-op.

**What survives process death**
- Every pack this account opened: meta, members, order, confirmed items with info, seq, readOnly and gone.
- Every unconfirmed edit, in the state it was written in. Queued stays queued. Sent stays sent on the device; the client puts it back in the queue and resends it under the same client_op_id only after a catch-up from the stored seq has removed whatever the server already took. Acked is resolved by that same catch-up.
- Every dropped edit and its order, until it is kept or discarded.
- Each person's own fields.

What does not survive:
- Status, timers, the socket, presence and baselines. Baselines are settled again when an item is opened, and the pending ops are the change, folded into the view.
- Edits younger than the 600 ms debounce. ON_STOP now flushes both sessions.

**AccountScope** (AccountScope.kt:34-56)
- `wipe()` adds `database.packDao().wipe()` inside the existing withTransaction.
- `ownership()` returns `SomeoneElses(unsyncedChanges = dao.outboxSize() + packDao.unsentOrDropped())`.
- `claim` is unchanged.
- AppViewModel already wipes and then claims (clearOtherAccountsPlans), so another account never sees or sends these rows.

**Sign-out** does not wipe, as with the Library. `PackEngine.disable()` halts sending. The same account finds its queue and sends it after enable.

**Housekeeping**
- At enable, `prune(me, keep = 10)` forgets packs outside the 10 most recently opened that have nothing queued, sent, acked or dropped.
- A gone pack is forgotten once its dropped edits are kept.

**Threats** never reach these tables: there is no item kind for them, and PackOps refuses `kind: threat` as bad_kind.

## The engine

### PackEngine (the registry)

PackEngine is one @Singleton, provided by DataModule. It is constructed with `PackEngine(api, RoomPackStore, LibraryPackKeeper, CoroutineScope(SupervisorJob() + Dispatchers.Default), requestBackgroundDrain = syncScheduler::requestSync)`.

- It holds at most one PackClient per pack uuid, ever. So there is only ever one batch in flight per pack, whether the pack is open, draining, or being drained by the worker.
- At most one client is OPEN at a time, as on the web.

**enable(user)**
1. Set `me`.
2. `prune`.
3. Keep the dropped edits of every pack in `withDropped(me)` that is not open, through the keeper.
4. For each pack in `owed(me)`, start a headless drainer.

**disable()** (sign-out, feature off, or another account)
- Halt every client at once: cancel its jobs and close its socket with 1000.
- Abandon nothing; the rows stay.
- Clear `me` and `open`.

**openPack(uuid)**
- The open client, if another pack's, is demoted to draining.
- A client already draining this pack is promoted back to open.
- Otherwise a client is created and `start()`ed.
- `store.touch(uuid, now)`.

PackWorkspace calls this, and before it flushes and closes any session holding the old pack's items (see editorJoin), so their last change reaches the old pack while that pack still accepts edits.

**closePack()** demotes the open client to draining.

**Keeping dropped edits**
When a client leaves the open state (closed, halted after draining, or gone), or a draining or headless client drops anything, the engine runs `keepDropped(pack)`:
1. `PackActions.droppedVersions(session)` gives each item's version.
2. Each becomes `KeptVersion(key = "$pack:$item:$firstDroppedClientOpId", kind, myEditsName(name), data)`.
3. `keeper.keep`, then `withoutDropped(all)`, persisted.
4. Notices `Kept` and `NothingToKeep`, then `requestBackgroundDrain()` so the new Library records go up.

While the pack is open, a drop only emits `Dropped`. The screens may then call keepDropped or discardDropped; whatever is left is kept when the pack closes.


### PackClient: the state machine

PackClient ports packClient.js.

**Modes**
- OPEN: polls, opens the socket, sends presence.
- DRAINING: sends only.
- HALTED.

**Status**
- LOADING: no session yet.
- OFFLINE: restored from Room; the server has not answered yet. Edits are accepted and queued.
- POLLING
- LIVE
- ERROR: a load failed with no session.
- PAUSED
- GONE

**Confinement**
- Every handler runs on `dispatcher.limitedParallelism(1)`: load, catch-up, poll tick, socket message, send answer, timers, and the edit entry point. The web's flags stay plain vars: stopped, closing, welcomed, socketRetry, sendRetry, loadRetry, loading, sending, catchingUp, catchUpAgain, reloadWanted, focus, focusSent.
- Every session change is `transition { s -> f(s) }`: under a Mutex, compute; `store.write(before, next, me)`; publish `PackState` (items = visibleItems mapped to PackItemView, with `data` the same instance as view[uuid].data).
- Network calls run outside the Mutex.
- Applying an answer or a catch-up page runs in `withContext(NonCancellable)`, so a stop in mid-answer does not waste the round trip. It is correct without that too.

**Timers**
`later(name, ms) { }` and `clear(name)` are named jobs on the client dispatcher, so `StandardTestDispatcher(testScheduler)` drives them in tests.

| Timer | Delay | Runs |
|---|---|---|
| poll | 3000 ms | only status POLLING, only in the foreground; runs catchUp |
| reconnect | RETRY_MS[socketRetry] | |
| send | RETRY_MS[sendRetry], or max(Retry-After, that) after a 429 | |
| presence | 100 ms | |
| load | RETRY_MS[loadRetry] | only in the foreground |

RETRY_MS is 1, 2, 5, 10 and 30 s, the last repeated.

**Load (open mode)**
1. `stored = store.load(uuid, me)`. If present:
   - `needsCatchUp = hasSentOrAcked(stored)`; remember whether a batch was out (`requeueStored`).
   - `session = stored`, as read; publish OFFLINE. The batch out stays sent until the first send after the catch-up applies `restored` (corrected in A9: a reload into a pack finished meanwhile then holds it for the server's answer instead of dropping it). The flag is cleared only once that send's transition is on disk.
   - Flushing is held off (`loading = true`).
2. If needsCatchUp, catch up from `stored.seq` until has_more is false. Receive removes our own ops that the server already took, by client_op_id.
3. `getPack` (NORMAL), then `session = stored ? reload(session, pack) : open(pack, me)`, persisted. Then POLLING, `loadRetry = 0`, `loading = false`, flush, startPolling, and connect if `live_url` is set and the app is in the foreground.

Failures, by PackFailure:
- pauses: PAUSED, plus a Paused notice. Nothing is retried until the next enable.
- 403 or 404: `transition { batchFailed(it, failure) }` drops pending as gone, so it is kept. Status GONE, notice, no retry. On the web the pending ops would be lost.
- Anything else: ERROR, or the status stays OFFLINE if a session exists. Load again after RETRY_MS[loadRetry], not while in the background.

A drainer (headless or demoted) skips `getPack`. It runs steps 1 and 2, then flushes.

**catchUp**
Single-flight, with an `again` flag, as packClient.js:141.
- Loop `events(uuid, since = session.seq, 500)`. It goes BACKGROUND while polling, so an analysis goes first, and NORMAL after a gap, a head, a resync or a welcome.
- Each page: `transition { receive(it, page.events) }`; then `notice OwnSkipped` for our own skipped results; continue while has_more.
- `diverged`: set `reloadWanted`. Reload (getPack, then reload, which keeps pending) only once no pending op is sent or acked. Otherwise reload after the batch's answer or the next catch-up.
- Failures:
  - pauses: PAUSED.
  - 403 or 404: GONE, with pending dropped as above.
  - Anything else: wait for the next poll, event or reconnect.
- Then flush.

**Polling**
startPolling runs from the end of load until the welcome, and again after a socket close. A tick runs only while in the foreground. That is a constant 3 s, as on the web.

**Live stream**
Only for the OPEN client, only in the foreground, and only one socket. OkHttp holds a dispatcher slot per socket, 5 per host. Drainers and the worker never open one.

`connect()` calls `api.openLive(liveUrl, uuid, scope, me)`. The hello and the token frames are sent by core-network. Messages are collected on the client dispatcher, in order:

- **Welcome:** welcomed; socketRetry = 0; clear poll; status LIVE; catchUp if `headSeq > seq`; resend presence.
- **Event:**
  - `seq == seq+1` and not catching up: `transition { receive }`; then reload if diverged (subject to the reload rule), catchUp on a gap, then flush.
  - `seq > seq+1`: catchUp.
- **Head** with seq > ours, and **Resync:** catchUp.
- **Presence:** publish the people.
- **Closed:** (corrected in A4's review: follow `Closed.packGone` and `Closed.reconnect`, not a list of codes here)
  - `packGone` (4404, 4410): GONE, with pending dropped and kept.
  - 4403: not gone. The service sends it only when the API's `/access` check answers 403, and that route gives 403 only for the account (`feature_disabled` from require_feature, `affiliation_required` from the gate), never the pack. So no reconnect: catch up (or load) now, and the API's own 403 code decides PAUSED, nothing dropped (owner decision 4). Deliberately unlike the web, which shuts down as gone.
  - 4401 `Closed.SIGNED_OUT` (local: the account is no longer the one signed in): no reconnect; the poll's OtherAccountException or SessionEndedException pauses the client, and only that account's next enable reopens it.
  - 4400: poll only, no reconnect.
  - Anything else (the service's 4401, 4408, 1006, 1009, 1011, 1013): poll, and reconnect after RETRY_MS[socketRetry], as the web does after every close but 4400.
  - 4401 never triggers a refresh directly. The next poll goes through executeSigned, which refreshes only if the token really lapsed, and the reconnect uses whatever is stored then.

**Presence**
`setFocus(focus)` is throttled to 100 ms and sent only after the welcome. A focus over 1 KB is not sent, because the service drops it silently. The focus is `{item}` for the open LZ/PZ; `at` waits for the map screens.

**Foreground**
- `foreground(false)` (ON_STOP): close the socket with 1000; clear poll, presence and load. Sending continues while the process lives.
- `foreground(true)` (ON_START): connect if live; startPolling if not live; catchUp; wake.
- `wake()` (ON_START, a network becomes the default, a sign-in): load now if the status is ERROR or OFFLINE and nothing is loading, and flush now if a send retry is waiting.


### Sending

`flush()` is one batch in flight, as packClient.js:267-309. It returns early if halted, sending, loading or PAUSED.

1. `transition { nextBatch }` persists the batch as sent. `nextBatch` takes at most 200 ops, with `base_seq = session.seq`.
2. `sendOps(uuid, batch, asUser = me.id)`.
3. On success: `transition { batchAnswered }`. Then OwnSkipped; then catchUp if asked; flush again; `drained()`.
4. On failure, `PackFailure.of(e)`:
   - retryable (0, 429, 5xx): `batchFailed` requeues; retry after RETRY_MS[sendRetry], or Retry-After. While draining, also call `requestBackgroundDrain()` once, so WorkManager (CONNECTED, KEEP) takes over if the process dies.
   - pauses (OtherAccountException, SessionEndedException, AffiliationRequiredException, 403 feature_disabled): `batchFailed(PackFailure(0))` requeues; status PAUSED; stop all timers; nothing is dropped.
   - 423, 403 pack_read_only, other 403, 404, 400, 413: `batchFailed` exactly as the fixture pins it. 423 and 403/404 drop everything pending, acked included. 400 and 413 drop that batch only, and the rest goes at once. Then the drop handling under PackEngine.

**edit(ops)**, refused in this order:
- halted or draining: "closed"
- no session: "loading"
- PAUSED: "paused"
- otherwise PackSessions.edit's own refusals: gone, read_only, an op validation reason, unknown_type

Otherwise: `transition { edit(it, ops, newId) }` (durable), then flush. client_op_ids are UUIDs: 36 printable ASCII characters, within the server's 64. Ops carry only the documented keys plus client_op_id and summary.


### Stop and drain
- `stop()` (demote) mirrors packClient.js:380: clear poll, reconnect, presence and load; close the socket with 1000; keep sending until `drained()` (nothing queued or sent, no send timer); then halt, keep the dropped edits, and forget the client.
- `halt()` at disable stops at once without abandoning anything. That is safe because rows carry userId, Call.asUser refuses another session, and AccountScope stops another account.


### drainAll(user, timeoutMs = 60 s) for WorkManager
Called from `EngineSyncRunner.runOnce`.
1. Keep dropped edits for every pack in `withDropped(user.id)` that is not open.
2. For each pack in `owed(user.id)`:
   - It has a client: await it to drain.
   - Otherwise: create a headless client and run load steps 1 and 2 plus flush passes, with no timers. A retryable failure ends that pack with RETRY.
3. Loop while `owed` is not empty and progress is made. WorkManager's KEEP ignores a request made while the work is RUNNING.

It never opens a socket. It returns RETRY if any pack still owes after a retryable failure, PAUSED if any paused, and DONE otherwise.

**EngineSyncRunner.runOnce:**
1. If `auth.state.value is AuthState.Unknown`, call `auth.restore()`. Today a WorkManager process the app did not start returns Done at SyncRunner.kt:43.
2. Return Done unless the state is SignedIn and `accounts.ownership(id) == Yours`.
3. If `user.accessOk && user.hasFeature("mission_packs")`: `packs.drainAll(PackUser(id, name))` first.
4. Then `engine.sync()`, as today, with `follow`.
5. The outcome is Retry if either asked for it.

ApiUser.hasFeature reads the server's resolve_features map, which carries `mission_packs: false` for an account it is off for, so the DEFAULT_OFF default holds.


### The app's gate
AppViewModel gets a second collector, separate from onAuth's distinctUntilChangedBy(id, accessOk) at :72, which never sees a feature flip:
- `combine(backend.state, ownership)` maps to `PackUser?` when SignedIn, accessOk, `hasFeature("mission_packs")` and Ownership.Yours.
- `distinctUntilChanged`, then `packs.enable(user)` and `workspace.start()`, else `packs.disable()`.

Lifecycle:
- SignedOut branch (:85): `closeOpenDocuments()` first, then `packs.disable()`.
- `appStarted()` (:78): `packs.foreground(true); packs.wake()`.
- New `appStopped()`, from AppRoot's ON_STOP: flush DiagramSession and RouteSession, each in its own try; then `packs.foreground(false)`; then `sync.requestSync()` if `packs.unsentCount() > 0`.
- NetworkWatcher: `ConnectivityManager.registerDefaultNetworkCallback`, onAvailable calls `wake()`. ACCESS_NETWORK_STATE is already in the manifest.


### Never
- The engine never reads or writes ThreatStore.
- It never shows a server's text: words come from PackMessages.
- It never sends item.replace.
- It never sends under another account.

## Joining the editors to a pack

**Principle (MISSION_PACKS.md §5a).** The editors do not change.

A pack item opens in the same singleton DiagramSession or RouteSession that GraphicsViewModel, MapDrag, BoundaryDrawing, RouteSketching, AnalysisService and RoutePlanning already use, under the local id `pack:<pack>:<item>` (PackRef).

**The routing store.** DataModule provides:
- `PackItemStore<Diagram>(engine, LzPackKind(env), packStore, { it.id }, Dispatchers.Main.immediate)` and the RouteSet equivalent.
- `DiagramSession(repository, scope, packs = lzItems)` and `RouteSession(repository, scope, packs = routeItems)`.

There is no Hilt cycle, because PackItemStore never needs a session. The session's anonymous store sends PackRef ids to the pack store, for both open and save. For release, a pack id goes to `packs.release`; anything else goes to `repository.release()`.

Library-only behaviour never fires for a pack id:
- PackItemStore.save always returns the same document, so DocumentSession never calls adopt() or reidentify.
- EngineSyncRunner.follow only ever names Library uuids.
- Pack item uuids are never reused, so nothing is ever re-created under a new id.

**open(localId).** It returns `kind.fromItem(localId, item, packStore.own(pack, item))`, but only when the engine's open pack is that pack, the pack has a session, and the item is visible and of this kind. Otherwise it returns null, and the caller starts from the list. It never opens a pack as a side effect and settles no baseline. Baselines are settled by the reconcile when it first sees the item, exactly as the web's `if (!base) settle`.

**save(document): sending, usePackItemSync's flush.** DocumentSession's 600 ms debounce takes the place of the web's 400 ms timer, and its coalesce key makes a drag one change. The save runs as `withContext(NonCancellable)`, because DocumentSession.flush cancels the pending save job:

1. On Main:
   - Mark the item as saving.
   - Read the state and the item. If the item is missing, return the document (the reconcile handles removal).
   - Write `ownOf(doc)` to pack_own if it changed. A base map change therefore sends nothing but is kept.
   - If the pack is read-only, emit putBack(localId, read_only) and return.
   - `base = baseline(localId)`. If there is none (a document saved through update(id) while not open, or saved before its first reconcile), use a transient `PackBaseline(item.data, item.name, kind.currentShape(localId, item), item.data)`.
2. Off Main: compute `mineDoc = kind.docOf(doc, base.carryFrom)` and `PackItemSync.flushPlan(uuid, base, item, mineDoc, nameOf(doc), kind.shared(item.data), kind.currentShape(localId, item), kind::describe, engine.me.name)`. This is the rename, then the reshape only with content changes, then the diff from the baseline; every op carries one summary. If the plan is null, return.
3. `engine.edit(ref.pack, plan.ops)`, which suspends until Room has the ops. If refused, emit putBack(localId, reason) and return.
4. On Main: only if a baseline existed, set it to `PackBaseline(null /* JUST_SENT */, plan.sentName, plan.sentDoc, base.carryFrom)`. A transient baseline leaves nothing behind.
5. Finally: clear the saving mark, emit `saved(localId)`, and return the same document.

**The update(id) path.** When AnalysisService or RoutePlanning applies fetched data to a pack document that is no longer open, the transient baseline makes the diff exactly that change. `DocumentSession.document(id)`, used by AnalysisService.load, finds pack diagrams through the same store. If the pack itself was switched away meanwhile, open returns null and the result is dropped, as for a deleted diagram.

**PackEditorSync: receiving, usePackItemSync's effect.** It runs on Dispatchers.Main.immediate. Triggers, conflated:
- the engine's open-state flow
- `session.active.map(idOf).distinctUntilChanged()` (the open id, not its content, so a drag frame does no work)
- `items.saved`

For the active document, if it is a PackRef of the open pack and not saving:
- Compute `mineDoc = docOf(active, base?.carryFrom ?: item?.data)`, with an identity memo per pass.
- Call `PackItemSync.reconcile(base, item, mineDoc, nameOf(active), readOnly, { currentShape })`.

| Decision | Action |
|---|---|
| SETTLE | `setBaseline(item.data, item.name, currentShape, item.data)`. On first sight, evaluate once more, so a change tidy (Doghouses.settle) made on open is found. |
| SEND_LATER | `session.owe()`: marks it unsaved and starts the debounce if nothing is pending. |
| FLUSH_FIRST | Ours go first: launch `session.flush()`; the `saved` event triggers the next pass, which takes theirs. |
| TAKE_THEIRS | See below. If the cause was a local change in a read-only pack, also emit `Refused(read_only)`. |
| REMOVED | If changed here, `keeper.keep` the unsent version (`myEditsName(base.name)`, `docOf(active)`); the web loses it. Then clear the baseline, `session.close()`, and emit `ItemRemoved(localId, base.name)`. |

putBack requests are handled the same way as TAKE_THEIRS, plus a Refused notice. setQuietly is never called from inside save(), because it would cancel the save job that is calling it.

For an LZ/PZ, the item focus is also sent: `engine.setFocus({item})` while one is open.

**Taking theirs without breaking undo.** On Main, in one turn:
1. `theirs = currentShape(item)` and `delta = PackItemSync.takeTheirsDelta(base, theirs)`, which is `diffData(base.doc, theirs)`.
2. Set the baseline to `(item.data, item.name, theirs, item.data)` first.
3. `session.setQuietly { d -> kind.withShared(d, PackItemSync.rebase(kind.docOf(d, base.carryFrom), delta, kind), if (nameOf(d) == base.name) item.name else nameOf(d)) }`. If delta is null (diff threw, never for a normalised document), use `{ d -> kind.fromItem(localId, item, kind.ownOf(d)) }`.

Why this works:
- On the active document, which has not changed here, docOf equals base.doc, so the result is exactly theirs, with the view and hidden routes kept.
- setQuietly applies the same function to every undo and redo step (patchHistory, DocumentSession.kt:166). Their change is therefore carried into each step, and an op whose target an old version lacks is skipped. Undo afterwards takes back only this person's own step and keeps theirs, except on a field both touched. The undo is then an ordinary local change, sent as its inverse from the new baseline.
- setQuietly marks the document unsaved. The save 600 ms later diffs the new baseline against the document, finds nothing, and sends nothing back. Order matters: the baseline is set before setQuietly. If tidy derives something new from theirs (doghouse headings), that alone is sent, as the web's effect would.

**A drag across someone else's change.** A remote event while the finger is down finds a change here, so FLUSH_FIRST sends the position so far. The next pass takes theirs into the coalesced step, the following frames stay in that step (MapDrag states absolute positions from the drag start), and the rest goes as one more change. This matches the usePackLz test "still sends the rest of a drag as one change after someone else's arrived in the middle of it".

**Each person's own fields are never in a pack** (pinned by shared.json):
- LZ: sharedLzData drops id, savedId, dirty, createdAt, updatedAt, view, name and analysis.terrainData. fromItem and withShared keep the view (from pack_own, or from the document) and the times. The slope raster stays runtime-only in AnalysisService, keyed by id and boundaryKey, so a boundary someone else changed is measured again, as the web's sameBoundary rule does.
- Unknown LZ keys: `docOf` carries keys this version does not model from the baseline's raw data, and currentShape carries them from the item's own data. The reshape and the diff therefore never send null for a newer web's field.
- Routes: sharedRouteData drops `visible` (Android has no setId). fromItem and withShared keep the routes this person hid, by id, which pack_own records. RouteSet already keeps extras and unreadable routes.
- Point sets have no session; points are never edited, on the web too. Their colour and visibility are PointSetViews entries under the local id.

**Creating and opening** (PackWorkspace):
- `createLz(target, name)`:
  1. uuid = `packItemId("lz", UUID)`.
  2. The diagram is `DiagramNormalizer.fromTarget(id = localId)`.
  3. `engine.edit(createItemOp(uuid, "lz", label, lzItemData(diagram), addedLzSummary(actor, label)))`, where label is `name.trim()` or `LZ/PZ n`.
  4. Then `diagrams.open(localId)`.
- `createRouteSet` is the same with `{version: 1, routes: []}`, `ROUTES n` and addedRouteSetSummary.
- `openItem(itemUuid)` opens the right session.
- `openPack` and `closePack` first close any session holding an item of the pack being left, so the flush goes to it while it is still open.
- An engine status of GONE closes those sessions, with notices.

Sign-out keeps today's order: AppViewModel.closeOpenDocuments flushes and closes both sessions before `packs.disable()`.

**Conflicts with remote changes** are field-level, last writer in server order:
- Different fields or graphics both stand, because changes are always sent as the diff from the baseline the editor was last in step with, never from the pack's latest.
- On one field, the later send wins, here too once its event arrives.
- An edit to something deleted elsewhere is skipped by the server and logged. An OwnSkipped notice says so, and the next pass takes theirs.

**Read-only and refused.** A local change in a read-only pack is put back. Edits the pack dropped while in flight (finished, or a viewer now) stay in session.dropped and Room, and are kept to the Library as `NAME (my edits)`; nothing is silently dropped.

## Tests

Fixtures are loaded with `Fixtures.load("packs/...")` and compared with `JsonCompare.differences` (objects unordered, lists ordered, null equal to absent). Case counts are read from the files, never hard-coded. Today they are: ops 134; diff 78, plus 27 sameData; session 93; shared lz 14, routes 13, routeSet 6, points 15, myEditsName 8; describe lz 120, routes 43, points 23, summaries 16; plus W1's lzShape, routeShape and creation cases.

Pure modules use JUnit 5, with bodies `= runBlocking<Unit>` or `runTest`. Android modules use JUnit 4 with Robolectric `@Config(sdk = [36])`.


### core-missionpacks

**Fixture replays (strict)**
- **PackOpsFixtureTest:** a @TestFactory over every ops.json case: status, reason and expected items. Also: untouched items keep their instance, and 1 equals 1.0 while "1" does not.
- **PackDiffFixtureTest:**
  - ops are equal in order;
  - `throws` cases expect PackDiffException;
  - every case not marked `throws` or `rebuilds:false` rebuilds `after`: its ops folded through PackOps give sameData;
  - every sameData case.
- **PackSessionFixtureTest:**
  - Each scenario is a dynamic test.
  - newId gives op-1, op-2, … per scenario.
  - A single op given on its own is wrapped in a list.
  - After every step: `result` (refused, batch, gap, catchUp), the whole `PackSessions.toJson(session)`, and `visible` in order.
  - At the end: `dropped_versions` through PackActions.droppedVersions.
  - `max_batch == MAX_BATCH`.
- **PackSessionIdentityTest:** receive, edit, batchAnswered and reload keep untouched confirmed and info entries by instance; `restored` turns sent into queued; `hasSentOrAcked`.
- **PackSharedFixtureTest:** lz, routes, routeSet, points, myEditsName, and lzShape and routeShape through LzPackKind and RoutePackKind.currentShape as production runs it (carry source item.data, not carryFrom = null), with a fixed DiagramNormalizer.Environment, skipping the webBug cases and testing the right behaviour for them (W1's review, item 2).
- **PackDescribeFixtureTest:** lz, routes and points sentences exactly (a lone surrogate included); summaries (renameSummary, deleteSummary, copySummary, packItemId, and the added* sentences after W1).

**Logic**
- **JsTest:** key order, ToNumber (0x5A, 0o17, 0b11, ' ', 'Infinity', true, [7]), round, -0 in template text, slice16.
- **PackLzCarryTest:**
  - a newer web's unknown top-level, analysis, target and graphics keys survive docOf, currentShape and a reshape plus an edit (no null is ever sent for them);
  - known keys are never put back;
  - view is never carried.
- **PackItemSyncTest:** the reconcile table and flushPlan, case for case from usePackLz.test.js (14), usePackRoutes.test.js (9) and usePackPoints.test.js (6). It uses a test-only `applyOps` (port of fakePack.js) that asserts every op validates and applies. Also rebase cases, and takeTheirsDelta returning null.
- **PackFailureTest:** the exception mapping, including OtherAccountException and feature_disabled pausing, and unreadable_response on a 2xx being status 0.
- **InviteAcceptanceTest:** single flight; Waiting while off; kept on 0, 429 and 5xx; cleared otherwise; joinedMessage.

**PackClientTest**
Virtual time, with the client scope `CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))`, cancelled at the end, as AnalysisServiceTest.kt:73 does. It runs over FakePackServer, FakeLive and InMemoryPackStore.

It ports packClient.test.js's 24 cases one by one. The sign-out cases become "halt keeps the queue; the same user sends it after enable; another user never does".

Android-only cases:
- a restored batch that was out is not resent before the catch-up from the stored seq removes it, and nothing stays acked;
- a restore with only queued ops skips the catch-up;
- edit() returns only after the store has the ops;
- no reload while sent or acked;
- PAUSED keeps the rows;
- 403 or 404 on load or catch-up drops pending into dropped;
- unreadable_response is retried;
- 4401 polls and never loops on refresh;
- presence over 1 KB is not sent;
- background mode closes the socket but keeps sending.

**PackEngineTest**
- one client per pack;
- opening another pack demotes the first, and it drains;
- reopening promotes the drainer;
- disable keeps the rows;
- drops while open give Dropped, and the remainder is kept at close;
- drops while draining or at enable are kept through RecordingKeeper, idempotently after a crash between keep and clear;
- drainAll skips the open pack, loops while it makes progress, and returns RETRY, PAUSED or DONE;
- prune.

**testFixtures**
- **FakePackServer : PackApi** ports fakePack.js and pack_routes.apply_ops (pack_routes.py:385-487):
  - validation in the server's order: invalid_batch (1 to 200 ops, base_seq a whole number ≥ 0); invalid_op with index and reason (bad_op; bad_client_op_id for one that is not printable ASCII of 1 to 64, or a duplicate; unknown_type; the validate reason; bad_summary); then 404 pack_not_found, 403 pack_read_only, 423 pack_finished with finished_at and finished_by;
  - dedupe per pack by client_op_id, answered from the log;
  - events after base_seq paged at `pageSize` (500), with has_more;
  - 413 item_too_large with a configurable limit, rolling back the whole batch;
  - a 403 feature_disabled switch.
- **Hooks:** `loseAnswers(n)`, `offline`, `failNext(status, body)`, `elsewhere(actor, ops)`, `finish(by)`, `reopen()`, `setRole(user, role)`, `remove(user)`, `deletePack()`, `live(): FakeLive`.
- **FakeLive : PackLiveConnection** pushes welcome, event, head, resync and presence, and closes with any code.
- **PackScenarioBook:** plain suspend scenarios over `Env { device(label); server }`, as core-sync's ScenarioBook:
  - two people, different fields, both stand;
  - one field, the later wins;
  - offline edits survive a new client over the same store and are sent exactly once;
  - a lost answer is resent and applied once;
  - finished while edits were out: dropped, and kept as "(my edits)";
  - a viewer is refused as read_only;
  - removed means gone, kept;
  - an edit to an item deleted elsewhere is skipped;
  - a 413 rolls back only that batch (fake only);
  - a catch-up across more than one page (fake, pageSize 3).
- Runners:
  - **FakePackScenarios:** the book against FakePackServer.
  - **LivePackScenarios:** @TestInstance(PER_CLASS), `assumeTrue(LiveServer.available)`, `LiveServer.startOrNull(3600)`.
    - Two accounts are made with `makeAccount(email, features = mapOf("mission_packs" to true), name = "Sam B.")`.
    - The second joins through `inviteToPack`, then `acceptInviteLink(LiveServer.emailed("pack_invite", to))`.
    - `clearRateLimits()` runs between scenarios (30 invitations an hour).
    - `PackTiming(pollMs = 200)` and `refresh()` are used rather than waiting.
    - Polling only: SQLite has no NOTIFY, and REALTIME_PUBLIC_URL is stripped.


### core-network
- **DtoFixtureTest:** every new recording in `decoders` (strict, re-encoding keeps everything); `{status}` bodies decode as StatusReply, so no exclusions are added.
- **PackCallsTest** (Rig over MockWebServer, replaying Recorded.mock):
  - every method, path, query and body;
  - `value: null` and `after: null` arrive as JSON null;
  - TeamShare.None sends `team_id: null`;
  - a bad uuid or item id ('..', '/', too long) throws before any request;
  - asUser of another account throws OtherAccountException with no request;
  - ApiException.body carries index, reason, item, finished_by and finished_at;
  - events with background = true wait behind an analysis ticket.
- **PackLiveTest** (`MockResponse().withWebSocketUpgrade(listener)` on /live):
  - the hello is the first frame and carries the stored token;
  - a REST call refused with 401, then `Rig.refreshed(...)`, makes the socket send `{type: token}`;
  - frames for another pack, and `closed`, are ignored;
  - 4403 arrives as Closed(4403) (not gone, no reconnect), a dropped connection as Closed(1006) once, close() sends 1000;
  - overflow arrives as Closed(1013);
  - a non-ws URL gives null.
- **LivePackCallsTest:** every typed call against live_server.py: create, ops, a replay answered from the log, events paged, seen, finish then 423, reopen; members; pack invitations and accepting the link; team routes and the link token; search; and a 403 feature_disabled for an account without the feature.


### core-data (Robolectric, Room in memory as RoomEnv.kt)
- **RoomPackStoreTest:** a round trip keeps order, info, the pending order, the dropped order (dropOrder) and own fields; a write after one item changed rewrites only that row; load for another userId gives null; forget; prune; the unique (packUuid, clientOpId).
- **RoomPackScenarioTest:** PackScenarioBook with each device on its own in-memory database, run with ParameterizedRobolectricTestRunner as RoomScenarioTest.kt.
- **RoomMigrationTest:**
  - build v2 from the committed 2.json, with a record, an outbox row, a blob and the cursor; open it with EzpzDatabase.open; the rows survive and the four tables exist and are empty;
  - the same from v1;
  - 2.json and 3.json differ only by the new tables.
- **AccountScopeTest:** wipe empties the pack tables; ownership counts queued, sent and dropped pack ops.
- **PackEditorTest:** a real DiagramSession over PackItemStore, a PackEngine over FakePackServer and InMemoryPackStore, and StandardTestDispatcher scopes with Main set to the test dispatcher. The debounce is fired with `advanceTimeBy(SAVE_AFTER_STILL_MS + 1); runCurrent()`. It holds:
  - opening sends nothing;
  - a drag is one change with its sentence;
  - a base map change sends nothing but persists own;
  - theirs is taken with the view kept, and undo reverts only my step and sends only its inverse;
  - a change here and there at once: ours goes first;
  - a drag interrupted by theirs goes as one more change;
  - one field, the later wins;
  - closed just after a change, it still goes;
  - read-only puts it back with Refused;
  - refused puts it back;
  - removed with unsent changes is kept and the session closes;
  - rename both ways;
  - createLz;
  - an older shape gets the reshape only on its first change;
  - unknown keys survive;
  - owe sends tidy-derived headings;
  - AnalysisService.update reaches a pack item that is not open;
  - a flush cancelled mid-save never sends twice;
  - Library uuids still go to DiagramRepository;
  - DiagramSessionTest and RouteSessionTest are unchanged and green.
- **RoutePackEditorTest:** a hidden route stays hidden and private; adding a point and moving one become ops; RouteRepository.release is never called for a pack id.
- **LibraryPackKeeperTest:** each kind saved as `NAME (my edits)` in the Library's shape and queued for sync; idempotent by key, tombstones included; an empty point set gives NothingToKeep.
- **PackWorkspaceTest:** switching packs flushes into the old pack before it closes.


### feature-auth
**InviteLinksTest:** https and the site hosts only; the token regex; `auth=` links are untouched.


### app
- **AppViewModelTest** (built positionally, AppViewModelTest.kt:172; a RecordingPackRuntime and a fake PackInvites):
  - enabled only for SignedIn, accessOk, mission_packs and Yours;
  - a feature flip enables and disables;
  - sign-out disables after closeOpenDocuments;
  - appStarted brings the engine forward and wakes it;
  - appStopped flushes both sessions, then backgrounds the engine and requests a sync when anything is unsent;
  - an invite link is kept in SavedStateHandle across sign-in and never routed to AuthHost, waits while the feature is off, is accepted once, is kept on 0, 429 or 5xx, and is cleared otherwise.
- **EngineSyncRunnerTest:** a cold process restores auth; another account's data returns Done; packs are drained before the Library sync; outcomes combine.


### backend
**test_network_fixtures.py** gets `packs()` as the last call at :511:
- pack_bp and team_bp added to extra_blueprints (:120);
- the three mail senders patched, as live_server.py:79-83 does;
- `User.features = {'mission_packs': True}`;
- REALTIME_PUBLIC_URL pinned with patch.dict (one pack recorded with a wss:// live_url, the rest with null);
- fixed pack uuids added to KEPT_UUIDS, item ids such as lz-1;
- a `token` placeholder in normalise();
- cross_cutting() accepts 403 feature_disabled.
Then `UPDATE_CONTRACTS=1`, keeping only the intended diff (the analyze-field floats already differ on Windows).

## Commit plan

1. **Done, `38e9d7b`.** W0 (prerequisite, not ours; never revert it): test(contracts): pin the pack diff, client session, own fields and history sentences. This is the uncommitted work in the tree: contracts/fixtures/packs/{diff,session,shared,describe}.json, frontend/src/contracts/pack{Diff,Session,Shared}Fixtures.test.js, the packActions.js and usePackItemSync.js extraction, and contracts/README. Its author commits it, after their mutation harness puts packSession.js back to HEAD: the working file currently differs from HEAD by one changing line, the latest being email:null at :131. Check: CI=true npm test.
2. **Done, `5510381`.** A1 fix(android): let a background sync restore the session in a process the app did not start. EngineSyncRunner calls auth.restore() when the state is Unknown and returns Done unless accounts.ownership(id) is Yours. Tests: EngineSyncRunnerTest. Check: ./gradlew :app:testDebugUnitTest --tests '*SyncRunner*'. Independent of everything else.
3. **Done, `f456858`.** A2 test(contracts,android): record the pack, team, invitation and search responses, with strict types for them. backend/tests/test_network_fixtures.py gets packs(), extra_blueprints, the mail patches, the features, the REALTIME_PUBLIC_URL pin, KEPT_UUIDS, a token placeholder and cross_cutting for feature_disabled; responses.json is regenerated with UPDATE_CONTRACTS=1. android/core-network gets PackDto.kt and the DtoFixtureTest decoders. The recordings and the types must land together, because DtoFixtureTest requires every 200 to 202 response to have a type. Check: python -m pytest tests/test_network_fixtures.py, then ./gradlew :core-network:test.
4. **Done, `5a16b39`.** A3 feat(android): call every pack, team, invitation and people-search route. PackEndpoints.kt (37 typed calls and 3 raw), PackPaths id checks, ApiException.body set in map(), OtherAccountException and Call.asUser in executeSigned. LiveServer.makeAccount(..., features, name): Int, and startOrNull strips REALTIME_PUBLIC_URL. Tests: PackCallsTest, LivePackCallsTest. Update the AGENTS §17 core-network row. Check: ./gradlew :core-network:test with EZPZ_LIVE_PYTHON set.
5. **Done, `bc33909`.** A4 feat(android): open a mission pack's live stream over OkHttp's own WebSocket. ApiClient gets accessTokens and newWebSocket (a ping client built once); PackLive.kt adds LiveMessage, PackLiveConnection and openPackLive. Test: PackLiveTest. No new dependency. Check: ./gradlew :core-network:test.
6. **Done, `02100c1`.** W1 (web, after W0) test(contracts): pin an editor's shape of a pack item and the sentences for a new one. Extract addedLzSummary, addedRouteSetSummary and addedPointSetSummary from usePackLz.js:121, usePackRoutes.js:116 and usePackPoints.js:148 into packActions.js, with no change in behaviour. Add lzShape and routeShape cases (item data in, lzItemData(lzDiagramFromItem(...)) and routeSetData(routesFromItem(...)) out; routes with ids only, no unknown keys) to packSharedFixtures.test.js, and the creation cases to describe.json summaries. Run UPDATE_CONTRACTS=1; then CI=true npm test and npm run build.
7. **Done, `60e3d74`.** A5 feat(android): apply mission-pack operations as the server does. The new module android/core-missionpacks: its settings.gradle.kts include and build.gradle.kts (copied from core-sync, test inputs included), Js.kt, PackOps.kt and PackRef.kt. Tests: PackOpsFixtureTest, JsTest (key order, strictEquals, idKey). In the same commit, add a row to the AGENTS §17 module table, and to NATIVE_APPS_PLAN.md's table saying mission packs are not map packs. Check: ./gradlew :core-missionpacks:test.
8. **Done, `fe01c28`.** A6 feat(android): work out the operations an edit to a pack item becomes. PackDiff.kt with longestRising, JS key order and the pinned prototype quirks. Test: PackDiffFixtureTest, including the rebuild property and sameData. Check: ./gradlew :core-missionpacks:test.
9. **Done, `573883c`.** A7 feat(android): hold an open pack as the web's client session does. PackSession.kt and PackSessions (with restored, hasSentOrAcked and withoutDropped), PackFailure.kt, and PackActions.droppedVersions. Tests: PackSessionFixtureTest (every scenario, with dropped_versions), PackSessionIdentityTest, PackFailureTest. Check: ./gradlew :core-missionpacks:test.
10. **Done, `4c02e68`.** A8 feat(android): keep each person's own fields out of a pack item, and write its history. core-model: DiagramJson.encode, which DiagramRepository now uses (DiagramRepositoryTest unchanged). core-missionpacks: PackLz (with carryUnknown), PackRoutes, PackPoints, the rest of PackActions (sentences, op builders, myEditsName), PackMessages, PackKind, LzPackKind and RoutePackKind. Tests: PackSharedFixtureTest (including lzShape and routeShape), PackDescribeFixtureTest, PackLzCarryTest, and JsTest's ToNumber, round and template cases. Check: ./gradlew :core-model:test :core-missionpacks:test.
11. **Done, `c228381`.** A9 feat(android): send, catch up and follow one mission pack, offline included. PackApi and ApiPackApi, PackStore and InMemoryPackStore, PackKeeper, PackClient (internal), and the public state types. testFixtures: FakePackServer, FakeLive, RecordingKeeper. Test: PackClientTest. Check: ./gradlew :core-missionpacks:test.
12. **Done, `99bc493`.** A10 feat(android): run every open and closing mission pack, and keep what a pack refuses. PackEngine (registry, enable and disable, drainAll, keeping), PackItemSync (the pure decisions), InviteAcceptance. testFixtures: PackScenarioBook. Tests: PackEngineTest, PackItemSyncTest, InviteAcceptanceTest, FakePackScenarios, LivePackScenarios. Check: ./gradlew :core-missionpacks:test with EZPZ_LIVE_PYTHON set.
13. **Done, on `feat/android-mission-packs-a11`.** Amended while building (the code wins): a write is compared with `before` only when the stored copy is this account's, and is otherwise written whole (another account's own fields dropped with its copy); `dropOrder` is the place in the session's `dropped`, renumbered as edits are let go; item data and operations longer than 256K characters are read in parts with `substr`, because a row over the 2 MB cursor window cannot be read on a device (a test holds a 3 MB item); JSON is written with `toString()`, since `encodeToString` writes numbers again (`2.50` as `2.5`); `prune` is done in Kotlin over `byLastOpened`. A11 feat(android): keep mission packs and their unsent edits on the device (database version 3). PackEntities, PackDao and RoomPackStore. EzpzDatabase v3 with AutoMigration(2, 3) and packDao(), and the generated schemas/app.ezpztac.data.EzpzDatabase/3.json committed. AccountScope wipe and ownership. LibraryPackKeeper. In DataModule: PackStore, PackKeeper and PackEngine (requestBackgroundDrain = SyncScheduler::requestSync). In app/di/AppModule: packApi, in the same commit, so the Hilt graph is whole. core-data gets api(core-missionpacks) and testFixtures. Tests: RoomPackStoreTest, RoomPackScenarioTest, RoomMigrationTest (2 to 3 and 1 to 3), AccountScopeTest, LibraryPackKeeperTest. Update the AGENTS §17 core-data row (database version 3, pack tables). Check: the full ./gradlew test testDebugUnitTest lintDebug assembleDebug (about 7 minutes; run it in the background).
14. **To do.** A12 feat(android): edit a mission pack's LZ/PZs and route sets in the existing editors. DocumentSession gets document(id) and owe(). DiagramSession and RouteSession take an optional packs store, with release routed. AnalysisService.load goes through the session, its repository parameter is removed, and DataModule and AnalysisServiceTest are updated. New: PackItemStore, PackEditorSync, PackWorkspace, and the DataModule providers. Tests: PackEditorTest, RoutePackEditorTest, PackWorkspaceTest; DiagramSessionTest and RouteSessionTest stay unchanged and green. Check: the full build.
15. **To do.** A13 feat(android): run mission packs with the app. PackRuntime and EnginePackRuntime. AppViewModel gets the gate collector, appStopped, and disable on sign-out after closeOpenDocuments. AppRoot gets an ON_STOP hook, which fixes RouteSession never being flushed. NetworkWatcher is started in EzpzApplication. EngineSyncRunner drains packs before the Library sync. Tests: AppViewModelTest, EngineSyncRunnerTest. Check: the full build.
16. **To do.** A14 feat(android): accept a mission-pack invitation link once signed in. InviteLinks.kt in feature-auth. PackInvites and ApiPackInvites. AppViewModel tries AuthLinks then InviteLinks in onLink, keeps the token in SavedStateHandle, accepts once the Gate is Ready and the feature is on, and exposes inviteState, retryInvite and dismissInvite. Tests: InviteLinksTest, AppViewModelTest. Check: the full build.
17. **To do.** A15 docs: record the Android mission-pack engine. AGENTS.md: §17 gets a 'Mission packs' block (modules, the rules that bit, the deliberate differences from the web, what cannot be verified here); §15 gets the web candidates (no reload while sent or acked, the catch-up 403/404 loss, unknown LZ keys, id-less routes); §4's Mission packs row. docs/MISSION_PACKS.md §8 step 4: four tables, the kept-edit name and the status; §6 says 4403 means "the pack is not this person's to see", which the backend does not do (the `/access` route answers 403 only for feature_disabled or affiliation_required; backend/realtime/service.py's comment at CLOSE_CODES says the same wrong thing): correct both, and say the Android client pauses on it. docs/HANDOFF.md §1 and §2. contracts/README.md if W0 and W1 did not list the new fixtures. The owner settles '(my edits)' against '(my offline edits)' first.

## Risks

1. Concurrent work in the tree. A mutation harness is rewriting frontend/src/feature/missionPacks/packSession.js. During this review it removed item.replace from ITEM_EVENTS, removed status:finished on a 423, set readOnly:false at :247 and set email:null at :131. The fixtures and packActions.js and usePackItemSync.js are uncommitted. Port only HEAD and the committed fixtures, never revert someone else's work, and start A5 and later only after W0 lands.
2. Naming conflict: shared.json and myEditsName give 'NAME (my edits)', while MISSION_PACKS.md §8 and HANDOFF.md say 'NAME (my offline edits)'. The design follows the fixture. The owner decides, and any change is made on the web first.
3. Deliberate differences from the web, each for the owner to see:
- Sign-out keeps queued edits for the same account instead of abandoning them (against the wording of §5 step 9).
- feature_disabled, affiliation_required and an ended session pause instead of dropping as gone.
- A 403 or 404 on load or catch-up keeps the pending edits.
- Dropped edits are saved to the Library automatically whenever their pack is not open.
- A removed item's unsent edits are kept.
- The send debounce is 600 ms, not 400 ms.
- No reload while an op is sent or acked.
- Unknown LZ keys are carried.
- A token frame is sent after each refresh.
Each belongs on the web too, if it should be shared.
4. Latent web bug, reported and not fixed here. reloadSession can move seq past our own op's event, and batchAnswered then leaves that op acked for ever, folded over newer values. Android avoids it by rule: catch up first on restore, and never reload while an op is sent or acked. A diverged reload during a catch-up on restore can still hit it ('should never happen'). The proper fix is web first, then a regenerated session.json.
5. Reference identity is load-bearing, as on the web. Every change must make new JsonElement instances exactly where JavaScript makes new objects, and untouched entries must keep theirs. PackSessionIdentityTest and RoomPackStoreTest's one-row rewrite pin this. A careless copy would rewrite whole packs (5 MB items) on every event.
6. Taking theirs patches up to 100 undo steps on the main thread, each a docOf and a withShared (encode and normalize). An identity memo and conflated reconciles limit it, but measure on a device with a large LZ. The fallback is to patch only the newest N steps and drop older undo.
7. carryUnknown is Android-only; the names it must never put back are in shared.json's description, and its unknown-key case is webBug (W1's review). If it is wrong, the first edit's reshape could null, or put back, a newer web's field for everyone in the pack. Routes differ in two known ways: RouteSet keeps id-less routes in unreadable and moves them to the end, where the web drops them, and a route point with no id makes the diff send its list whole.
8. The live stream cannot be checked end to end here. live_server.py uses SQLite (no NOTIFY) and live_url is null, the live service is not deployed, a release build refuses ws://, and an emulator cannot reach ws://localhost. PackLiveTest covers the client protocol only. The token re-send and 4401 handling need tests/test_realtime_live.py (Postgres) or the deployed service. OkHttp holds a dispatcher slot per socket (5 per host), so keep exactly one, for the open pack.
9. A 413 rolls back a whole batch of up to 200 ops, as on the web. They are dropped and kept as Library copies, so one oversized item can create several '(my edits)' records. There is no client-side size pre-check.
10. Room v3 has no destructive fallback. A missing or uncommitted 3.json, or an entity change without a version bump, fails the build or crashes installed apps. RoomMigrationTest 2 to 3 and 1 to 3 are required.
11. Persisting every transition rewrites each touched item row, up to 5 MB, on every event someone else makes. Measure while someone else drags on the web, which sends a change every 400 ms. A possible later optimisation is to batch confirmed-item writes for the open pack, but only while the outbox is unchanged: seq, confirmed items and outbox removal must stay in one transaction.
12. Cross-account safety rests on three guards: userId on every outbox row, Call.asUser checked before a send, and AccountScope counting and wiping pack rows. A missed path would send one person's edits as another. AppViewModelTest and PackCallsTest must cover them.
13. WorkManager's KEEP ignores a request made while the work is RUNNING, so drainAll must loop until nothing is owed. Not verifiable here: Doze, the timing of CONNECTED work, the NetworkWatcher callback, SavedStateHandle across process death, and the assetlinks-verified link (still an owner step).
14. Recording: rec() fails on a status a route does not document, so feature_disabled is recorded through cross_cutting. Team link tokens and generated uuids need placeholders or the file changes every run. test_network_fixtures.py already differs on the owner's Windows machine (analyze-field floats). Android CI installs a fixed backend package list.
15. Threading in the join: baselines are read and written only on Main; computing a send is off Main; setQuietly is never called from inside save(), because it cancels the very save job that called it; reconcile releases nothing it holds before session.flush(). A mistake here gives either an echo back to the pack or a deadlock.
16. A history sentence that ends in a lone surrogate is sent by Kotlin through OkHttp's UTF-8 encoder as '?', where the web sends a \ud83d escape. Only the log text differs.
17. Each Android commit is a full build of about 7 minutes, with lint and warnings as errors. A JUnit 5 test must be '= runBlocking<Unit>'. advanceUntilIdle does not run backgroundScope. Neither store's transaction is re-entrant, so the keeper must never write the Library inside a pack transaction.

## Deferred to the screens

1. Every pack screen, built in the redesigned shell (HANDOFF §2 items 2 and 3): the workspace switcher, New pack, the Pack panel (Items and History), Members, Teams, Add to pack, the finished banner, presence flags, the Opening and Cannot-reach labels, and the refused and lost toasts, including porting REFUSED_WORDS and LOST_WORDS from usePackWorkspace.jsx.
2. The keep or discard offer for edits a pack dropped while it was open. The engine already holds them durably, emits Dropped, and keeps them by default when the pack closes.
3. The Library-or-pack workspace: parking the Library's open documents, a LastPack preference, and what HomeViewModel does with LastDiagram or LastRouteSet when they name a pack: id.
4. Pack point sets on the map: PointSetViews keyed by local id, with settle including pack sets so their colours are not pruned; importing an .LPS straight into a pack; and the screen wrappers for rename and delete (the PackActions op builders already exist).
5. Copy-in from the Library: syncing a pending record first, then copyIntoPack with PackSource.ClientUuid and refresh. Also the Update from original dialog, Save to Library from a pack, and an offline copy-in fallback (an owner decision).
6. A port of usePackSeen (the seen marker and changedSince), History paging, and an offline History cache (a pack_event table in a v4 AutoMigration if wanted).
7. The presence pointer (`at`, the map position) and the named flags on the map.
8. Read-only gating of map interactions (drag, turn, delete) and of the sheet's tools. The engine already puts back any change it refuses.
9. Opening the pack after an invitation is accepted. inviteState carries the answer for the shell.
10. Adaptive polling, reconnect jitter and an item-size pre-check, once measured on a device or the owner decides.
11. Web follow-ups for the owner, to go through the fixtures first: never reload while an op is sent or acked; keep pending edits on a 403 or 404 during catch-up; keep unknown LZ keys in normalizeLzDiagram; how routes without ids are handled.
