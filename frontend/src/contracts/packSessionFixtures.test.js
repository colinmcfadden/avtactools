import {
  MAX_BATCH,
  abandon,
  batchAnswered,
  batchFailed,
  droppedVersions,
  edit,
  nextBatch,
  openSession,
  receive,
  reloadSession,
  visibleItems,
} from "../feature/missionPacks/packSession";

const fs = require("fs");
const { UPDATE, compact, fixturePath, writeFixture } = require("./fixtureIO");

// How an open mission pack is held on a device: the web's packSession.js is the reference (docs/MISSION_PACKS.md §5),
// and the native apps follow it. A scenario opens a pack and plays steps on it, recording the whole session after each,
// so a port replays the same steps from the JSON alone and compares what it holds.

const GENERATED_BY = "frontend/src/contracts/packSessionFixtures.test.js (UPDATE_CONTRACTS=1)";

// Written with every value of up to 800 characters on one line and every longer one broken up (fixtureIO.compact), so a
// regenerated scenario diffs step by step rather than as one line of hundreds of kilobytes, and the file stays about
// the size of the same JSON on one line (a narrower cut indents it to half as large again).
const settle = (name, document) => {
  const file = fixturePath(name);
  const text = `${compact(document, "", 800)}\n`;
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

// None of packSession's functions should need the clock or a random id. While the fixture is built, both throw, so a
// scenario that reached for either could not be written.
const withoutClockOrRandomness = (build) => {
  const RealDate = global.Date;
  const random = Math.random;
  const hadCrypto = Object.prototype.hasOwnProperty.call(window, "crypto");
  const crypto = window.crypto;
  const refuse = (what) => () => {
    throw new Error(`a scenario reached for ${what}: give it what it needs`);
  };
  global.Date = new Proxy(RealDate, {
    construct: (target, args) => (args.length === 0 ? refuse("the clock")() : new target(...args)),
    get: (target, key) => (key === "now" ? refuse("the clock") : Reflect.get(target, key)),
  });
  Math.random = refuse("randomness");
  Object.defineProperty(window, "crypto", { configurable: true, value: { randomUUID: refuse("a random id") } });
  try {
    return build();
  } finally {
    global.Date = RealDate;
    Math.random = random;
    if (hadCrypto) Object.defineProperty(window, "crypto", { configurable: true, value: crypto });
    else delete window.crypto;
  }
};

// -- People, times and the pack as GET /api/packs/<uuid> returns it ---------------------------------------------

const COLIN = { id: 1, name: "Colin" };
const SAM = { id: 2, name: "Sam" };
const ALEX = { id: 3, name: "Alex" };
// The server's own changes (an account or a team deleted) are logged with no user.
const SERVER = { id: null, name: "EZ-PZ" };

const PACK_UUID = "6f1c2a9e-3b7d-4c55-9a10-2d8e5f7b4c31";
const LIBRARY_UUID = "0d9c7e52-61a4-4f0b-8e3a-5b2c1d7f9a60";

// As the server writes a time (UTC, no zone): event n happened at 13:00:n, so each event's own time shows where it is used.
const at = (seq) => `2026-10-05T13:${String(Math.floor(seq / 60)).padStart(2, "0")}:${String(seq % 60).padStart(2, "0")}`;

const member = (person, role) => ({
  user_id: person.id, name: person.name, email: `${person.name.toLowerCase()}@unit.example`, role, added_at: at(1), seen_at: null,
});

const LZ_DATA = {
  schemaVersion: 2,
  id: "d-1",
  name: "LZ HAWK",
  status: "analyzed",
  flightData: { callSign: "HAWK 6", landingHeading: 270 },
  graphics: { helicopters: [{ id: "h-1", lat: 34.5, lon: -84.1, heading: 270 }] },
};
const POINTS = [{ id: "lps-0", name: "ALPHA", lat: 34.5, lon: -84.1, elevationFt: 1730 }];

const packItem = (uuid, kind, name, data, { seq, createdSeq = seq, revision = 1, createdBy = COLIN, updatedBy = createdBy, source = null }) => ({
  uuid, kind, name, revision, seq, created_by: createdBy, updated_by: updatedBy, created_at: at(createdSeq), updated_at: at(seq), source, data,
});

const LZ_ITEM = packItem("lz-1", "lz", "LZ HAWK", LZ_DATA, { seq: 2 });
const PS_ITEM = packItem("ps-1", "pointset", "LOCAL", POINTS, { seq: 3 });

const TEAMS = { 5: "1ST PLT", 7: "2ND PLT" };
const TEAM = { id: 5, name: "1ST PLT", member_count: 6, role: "editor" };

const packBody = ({
  role = "editor",
  status = "active",
  headSeq = 3,
  team = null,
  members = [member(COLIN, "owner"), member(SAM, "editor")],
  items = [LZ_ITEM, PS_ITEM],
  finishedAt = null,
  finishedBy = null,
  liveUrl = null,
  audienceCount = members.length,
} = {}) => ({
  uuid: PACK_UUID,
  name: "OP DK",
  description: "Practice air assault, phase 2.",
  status,
  role,
  owner: COLIN,
  team,
  head_seq: headSeq,
  seen_seq: 0,
  member_count: members.length,
  audience_count: audienceCount,
  item_count: items.length,
  item_counts: Object.fromEntries(["lz", "route", "pointset"].map((kind) => [kind, items.filter((i) => i.kind === kind).length])),
  finished_at: finishedAt,
  finished_by: finishedBy,
  created_at: at(1),
  updated_at: at(headSeq),
  members,
  items,
  live_url: liveUrl,
});

const PACK = packBody();
const VIEWER_PACK = packBody({ role: "viewer", members: [member(COLIN, "owner"), member(SAM, "viewer")] });
const FINISHED = { headSeq: 4, status: "finished", finishedAt: at(4), finishedBy: COLIN };
const WITH_ALEX = packBody({ members: [member(COLIN, "owner"), member(SAM, "editor"), member(ALEX, "viewer")] });
const SHARED = packBody({ team: TEAM, audienceCount: 7 });

// -- Operations and the events the server logs for them --------------------------------------------------------

const HELO = (id) => ["graphics", "helicopters", { id }];
const setHeading = (value, item = "lz-1") => ({ type: "set", item, path: ["flightData", "landingHeading"], value });
const setCallSign = (value) => ({ type: "set", item: "lz-1", path: ["flightData", "callSign"], value });
const rename = (item, name) => ({ type: "item.rename", item, name });
const create = (item, kind, name, data) => ({ type: "item.create", item, kind, name, data });
const deleteItem = (item) => ({ type: "item.delete", item });
const NEW_LZ = (id, name) => ({ schemaVersion: 2, id, name, status: "targeted" });

const ITEM_TYPES = new Set(["item.create", "item.delete", "item.rename", "item.replace", "set", "patch", "upsert", "insert", "remove"]);
const KIND_WORDS = { lz: "LZ", route: "routes", pointset: "points" };
const NAMES = { "lz-1": "LZ HAWK", "ps-1": "LOCAL" };
const roleWords = (role) => (role === "editor" ? "an editor" : "a viewer");

// The History panel's sentence, as backend/routes/pack_routes.py writes it. The session never reads it.
const sentence = (actor, op) => {
  const who = actor.name;
  const name = NAMES[op.item] || "an item";
  switch (op.type) {
    case "item.create":
      return op.source ? `${who} added "${op.name}" (copied from their library).` : `${who} added the ${KIND_WORDS[op.kind]} "${op.name}".`;
    case "item.delete": return `${who} removed "${name}".`;
    case "item.rename": return `${who} renamed "${name}" to "${op.name}".`;
    case "item.replace": return `${who} updated "${name}" from the original in their library.`;
    case "pack.update":
      return op.name !== undefined ? `${who} renamed the pack from "OP DK" to "${op.name}".` : `${who} changed the pack's description.`;
    case "pack.share":
      return op.team_id == null
        ? `${who} stopped sharing the pack with a team.`
        : `${who} shared the pack with the team "${TEAMS[op.team_id]}" as ${op.team_role}s.`;
    case "pack.finish": return `${who} finished the pack. It is read-only for everyone.`;
    case "pack.reopen": return `${who} reopened the pack.`;
    case "pack.transfer": return `${who} made ${op.name} the owner of the pack.`;
    case "member.add": return `${who} added ${op.name} as ${roleWords(op.role)}.`;
    case "member.join": return `${who} joined the pack as ${roleWords(op.role)}.`;
    case "member.role": return `${who} made ${op.name} ${roleWords(op.role)}.`;
    case "member.remove": return op.user_id === actor.id ? `${op.name} left the pack.` : `${who} removed ${op.name} from the pack.`;
    case "invite.create": return `${who} invited ${op.email} as ${roleWords(op.role)}.`;
    default: return `${who} edited "${name}".`;
  }
};

// The operation as the server logs it: client_op_id and summary are the event's own fields, not the operation's.
const payload = (op) => Object.fromEntries(Object.entries(op).filter(([key]) => key !== "client_op_id" && key !== "summary"));

/** A PackEvent (contracts/openapi.yaml): `item` is the item's uuid for an item operation, null for a pack event. */
const logged = (seq, op, actor, { clientOpId = null, status = "applied", reason = null, summary = sentence(actor, op) } = {}) => ({
  seq,
  type: op.type,
  item: ITEM_TYPES.has(op.type) ? op.item : null,
  actor,
  summary,
  status,
  reason,
  client_op_id: clientOpId,
  op: payload(op),
  created_at: at(seq),
});

/** An edit made here, logged: `id` is the client_op_id edit() gave it. */
const ours = (seq, op, id, { actor = SAM, ...rest } = {}) => logged(seq, op, actor, { clientOpId: id, ...rest });
/** Someone else's edit through POST .../ops, logged with the id their own client gave it. */
const theirs = (seq, op, actor = COLIN, rest = {}) => logged(seq, op, actor, { clientOpId: `${actor.name.toLowerCase()}-${seq}`, ...rest });
/** What the server writes itself: pack and member events, a copy from a library, an update from the original. */
const server = (seq, op, actor = COLIN, rest = {}) => logged(seq, op, actor, rest);

const resultOf = (event) => ({ client_op_id: event.client_op_id, seq: event.seq, status: event.status, reason: event.reason });

/** POST .../ops's 200 body (PackOpsResult): one result per operation sent, in order, and every event after base_seq. */
const answer = (events, results, { hasMore = false, headSeq = Math.max(...events.map((e) => e.seq), ...results.map((r) => r.seq)) } = {}) => ({
  head_seq: headSeq, results, events, has_more: hasMore,
});
/** The answer to a batch whose events are all on the page: the results are those of our own events in it. */
const answered = (...events) => answer(events, events.filter((e) => e.actor.id === SAM.id && e.client_op_id).map(resultOf));

const S = {
  edit: (ops) => ({ do: "edit", ops }),
  nextBatch: () => ({ do: "nextBatch" }),
  receive: (...events) => ({ do: "receive", events }),
  batchAnswered: (body) => ({ do: "batchAnswered", answer: body }),
  batchFailed: (failure) => ({ do: "batchFailed", failure }),
  abandon: (reason) => ({ do: "abandon", reason }),
  reload: (pack) => ({ do: "reload", pack }),
};

const FINISH = { type: "pack.finish" };
const REOPEN = { type: "pack.reopen" };
// The refusals of POST .../ops that say what of the batch the pack already has (`taken`, docs/MISSION_PACKS.md §4), as the
// server sends them for a batch sent once: none of it.
const FINISHED_423 = { status: 423, code: "pack_finished", finished_at: at(8), finished_by: COLIN, taken: [] };
const READ_ONLY_403 = { status: 403, code: "pack_read_only", taken: [] };
const TOO_LARGE_413 = { status: 413, code: "item_too_large", taken: [] };
// The 423 to a batch sent again after its answer was lost, the pack having been finished at 5, as a server from before `taken`
// sends it: it says nothing of what of the batch it already has. Today's server adds `taken`.
const LOST_423 = { status: 423, code: "pack_finished", finished_at: at(5), finished_by: COLIN };
const OLDER_SERVER = "a batch whose answer was lost, refused with 423 when sent again by a server from before `taken`: nothing says what "
  + "the pack took, so the whole batch is dropped, and its event, seen after, leaves it dropped and offered back though the pack has it";
// Taken by the server, but the answer's page ended before its event (has_more): left "acked".
const ACKED_ANSWER = answer(
  [theirs(4, setCallSign("HAWK 7")), theirs(5, rename("lz-1", "LZ EAGLE"))],
  [{ client_op_id: "op-1", seq: 7, status: "applied", reason: null }],
  { hasMore: true, headSeq: 7 },
);
// lz-1 as a reload finds it after ACKED_ANSWER's events (4 and 5), before op-1 (7)...
const EAGLE_AT_5 = packItem("lz-1", "lz", "LZ EAGLE", { ...LZ_DATA, flightData: { callSign: "HAWK 7", landingHeading: 270 } },
  { seq: 5, createdSeq: 2, revision: 3 });
// ...and once the server has applied our heading of 90 to it, at `seq`.
const HEADING_90_AT = (seq) => packItem("lz-1", "lz", "LZ EAGLE", { ...LZ_DATA, flightData: { callSign: "HAWK 7", landingHeading: 90 } },
  { seq, createdSeq: 2, revision: 4, updatedBy: SAM });
// lz-1 once the server has applied our heading of 90, and nothing else, at 4.
const HEADING_90_BY_ME = packItem("lz-1", "lz", "LZ HAWK", { ...LZ_DATA, flightData: { callSign: "HAWK 6", landingHeading: 90 } },
  { seq: 4, createdSeq: 2, revision: 2, updatedBy: SAM });

// The smallest edit there is, so the scenario that fills a batch stays small: it records every session it passes through.
const MANY = Array.from({ length: MAX_BATCH + 1 }, (_, i) => ({ type: "set", item: "lz-1", path: ["n"], value: i }));

// A copy in the pack of a library record of Sam's, as Sam (who copied it in) and as anyone else is told of it.
const COPIED = { kind: "lz", uuid: LIBRARY_UUID, revision: 2 };
const sourceFor = (copier) => (copier
  ? { ...COPIED, original: "changed", original_updated_at: "2026-10-05T12:40:00", pack_changes: 1,
    last_pack_change: { actor: COLIN, summary: 'Colin edited "LZ HAWK".', created_at: at(3) } }
  : { ...COPIED, original: null, original_updated_at: null, pack_changes: null, last_pack_change: null });
const copiedPack = (source) => packBody({
  items: [packItem("lz-1", "lz", "LZ HAWK", LZ_DATA, { seq: 3, createdSeq: 2, revision: 2, createdBy: SAM, updatedBy: COLIN, source })],
});
const UPDATE_FROM_ORIGINAL = server(4, {
  type: "item.replace", item: "lz-1", data: { ...LZ_DATA, flightData: { callSign: "HAWK 6", landingHeading: 180 } }, source_revision: 3,
}, SAM);
// A copy made while the pack is open (its source as item.create's event gives it), then updated from the original by its copier.
const COPIED_IN = (copier) => server(4, { ...create("lz-5", "lz", "LZ CROW", NEW_LZ("d-5", "LZ CROW")), source: COPIED }, copier);
const UPDATED_IN = (copier) => server(5, {
  type: "item.replace", item: "lz-5", data: { ...NEW_LZ("d-5", "LZ CROW"), status: "analyzed" }, source_revision: 3,
}, copier, { summary: `${copier.name} updated "LZ CROW" from the original in their library.` });

// -- The scenarios ------------------------------------------------------------------------------------------------

const SCENARIOS = [
  // -- Opening ----------------------------------------------------------------------------------------------------
  { name: "a pack opens with its items in the server's order, each with what the pack knows of it", me: SAM.id, pack: PACK, steps: [] },
  { name: "nothing waits to be sent in a pack just opened", me: SAM.id, pack: PACK, steps: [S.nextBatch()] },
  { name: "a viewer's pack opens read-only, and an edit there is refused as read_only", me: SAM.id, pack: VIEWER_PACK,
    steps: [S.edit(setHeading(90))] },
  { name: "a finished pack opens read-only, for its owner too", me: COLIN.id, pack: packBody({ role: "owner", ...FINISHED }),
    steps: [S.edit(setHeading(90))] },
  { name: "a pack with no items opens empty", me: SAM.id, pack: packBody({ items: [], headSeq: 1, liveUrl: "wss://live.example/packs" }), steps: [] },

  // -- Edits made here --------------------------------------------------------------------------------------------
  { name: "an edit shows at once and waits to be sent", me: SAM.id, pack: PACK, steps: [S.edit(setHeading(90))] },
  { name: "one operation may be given on its own, not in a list", me: SAM.id, pack: PACK, steps: [S.edit(rename("lz-1", "LZ EAGLE"))] },
  { name: "an operation of every kind a client sends is taken, its summary kept, and the view is all of them in order", me: SAM.id, pack: PACK,
    steps: [S.edit([
      setHeading(90),
      { type: "patch", item: "lz-1", path: ["flightData"], value: { callSign: "HAWK 7" } },
      { type: "upsert", item: "lz-1", path: ["graphics", "helicopters"], value: { id: "h-2", lat: 34.51, lon: -84.11, heading: 90 } },
      { type: "insert", item: "lz-1", path: ["graphics", "helicopters"], after: "h-1", value: { id: "h-3", lat: 34.52, lon: -84.12, heading: 90 } },
      { type: "remove", item: "lz-1", path: HELO("h-1") },
      rename("lz-1", "LZ EAGLE"),
      create("rt-1", "route", "MISSION 1", { version: 1, routes: [] }),
      { ...deleteItem("ps-1"), summary: 'Sam removed "LOCAL".' },
    ]), S.nextBatch()] },
  { name: "a malformed operation is refused with its reason, and nothing is taken", me: SAM.id, pack: PACK,
    steps: [S.edit({ type: "set", item: "lz-1", path: [], value: 1 })] },
  { name: "something that is not an operation is refused as bad_op", me: SAM.id, pack: PACK, steps: [S.edit(["set"])] },
  { name: "item.replace is the server's own, so an edit of it is refused as unknown_type", me: SAM.id, pack: PACK,
    steps: [S.edit({ type: "item.replace", item: "lz-1", data: {} })] },
  { name: "a type nobody knows is refused as unknown_type", me: SAM.id, pack: PACK, steps: [S.edit({ type: "move", item: "lz-1" })] },
  { name: "one bad operation refuses the whole edit, and uses up no id", me: SAM.id, pack: PACK,
    steps: [S.edit([setHeading(90), { type: "set", item: "lz-1", path: [0], value: 1 }]), S.edit(setHeading(91))] },
  { name: "edits go in batches, one at a time, in order", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.edit(rename("lz-1", "LZ EAGLE")), S.nextBatch(), S.edit(setHeading(180)), S.nextBatch()] },
  { name: `at most ${MAX_BATCH} operations go in one batch, and the rest go in the next`, me: SAM.id, pack: PACK,
    steps: [
      S.edit(MANY),
      S.nextBatch(),
      S.batchAnswered(answered(...MANY.slice(0, MAX_BATCH).map((op, i) => ours(4 + i, op, `op-${i + 1}`)))),
      S.nextBatch(),
      S.batchAnswered(answered(ours(4 + MAX_BATCH, MANY[MAX_BATCH], `op-${MAX_BATCH + 1}`))),
    ] },

  // -- The server's events ----------------------------------------------------------------------------------------
  { name: "an event confirms the edit made here that it names, which stops being pending", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.receive(ours(4, setHeading(90), "op-1"))] },
  { name: "someone else's edit to another field merges with what is pending here", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.receive(theirs(4, setCallSign("HAWK 7")))] },
  { name: "someone else's insert after no element puts the new one first", me: SAM.id, pack: PACK,
    steps: [S.receive(theirs(4, {
      type: "insert", item: "lz-1", path: ["graphics", "helicopters"], after: null, value: { id: "h-0", lat: 34.49, lon: -84.09, heading: 270 },
    }))] },
  { name: "when two people set one field, the later in the server's order stands", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.receive(theirs(4, setHeading(180))), S.receive(ours(5, setHeading(90), "op-1"))] },
  { name: "someone else's later edit shows once ours is confirmed", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.receive(ours(4, setHeading(90), "op-1"), theirs(5, setHeading(180)))] },
  { name: "an event already seen is passed over, whatever it says", me: SAM.id, pack: PACK,
    steps: [S.receive(theirs(4, setHeading(90))), S.receive(theirs(3, setHeading(1)), theirs(4, setHeading(2)))] },
  { name: "an event after a missing number is not applied, and the caller fetches from seq", me: SAM.id, pack: PACK,
    steps: [S.receive(theirs(5, setHeading(90)))] },
  { name: "events before a missing number are applied, and what they made read-only is settled", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.receive(server(4, FINISH), server(6, REOPEN))] },
  { name: "a skipped event changes nothing but answers the edit it names", me: SAM.id, pack: PACK,
    steps: [
      S.edit({ type: "remove", item: "lz-1", path: HELO("h-9") }),
      S.nextBatch(),
      S.receive(ours(4, { type: "remove", item: "lz-1", path: HELO("h-9") }, "op-1", { status: "skipped", reason: "target_missing" })),
    ] },
  { name: "someone else's skipped event changes nothing but the seq", me: SAM.id, pack: PACK,
    steps: [S.receive(theirs(4, setHeading(10, "lz-7"), COLIN, { status: "skipped", reason: "item_missing" }))] },
  { name: "an applied event that does not apply here marks the copy as drifted, and a reload keeps what is pending", me: SAM.id, pack: PACK,
    steps: [
      S.receive(theirs(4, { type: "patch", item: "lz-1", path: HELO("h-9"), value: { lat: 34.52 } })),
      S.edit(setHeading(90)),
      S.reload(packBody({
        headSeq: 9,
        items: [
          packItem("lz-1", "lz", "LZ HAWK", {
            ...LZ_DATA,
            graphics: { helicopters: [...LZ_DATA.graphics.helicopters, { id: "h-9", lat: 34.52, lon: -84.12, heading: 90 }] },
          }, { seq: 9, createdSeq: 2, revision: 4 }),
          PS_ITEM,
        ],
      })),
    ] },
  { name: "an item someone else makes is added after the others, and one deleted leaves", me: SAM.id, pack: PACK,
    steps: [
      S.receive(theirs(4, create("ps-2", "pointset", "MORE POINTS", [{ id: "lps-0", name: "CHARLIE", lat: 34.7, lon: -84.3 }]))),
      S.receive(theirs(5, deleteItem("lz-1"))),
    ] },
  { name: "an item I copied in from my library arrives with its source, its original not known until the pack is loaded again", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { ...create("lz-5", "lz", "LZ CROW", NEW_LZ("d-5", "LZ CROW")), source: COPIED }, SAM))] },
  { name: "a rename by someone else changes the item's name, revision, seq and who changed it", me: SAM.id, pack: PACK,
    steps: [S.receive(theirs(4, rename("lz-1", "LZ EAGLE")))] },
  { name: "an item made here is shown at once and confirmed by its item.create", me: SAM.id, pack: PACK,
    steps: [
      S.edit(create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW"))),
      S.nextBatch(),
      S.batchAnswered(answered(ours(4, create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW")), "op-1"))),
    ] },
  { name: "an item updated from the original reads the same again to whoever copied it in", me: SAM.id, pack: copiedPack(sourceFor(true)),
    steps: [S.receive(UPDATE_FROM_ORIGINAL)] },
  { name: "the same update tells nobody else anything about the original", me: COLIN.id, pack: copiedPack(sourceFor(false)),
    steps: [S.receive(UPDATE_FROM_ORIGINAL)] },
  { name: "an item.replace by me of a copy that names no original keeps original null", me: SAM.id,
    pack: copiedPack({ ...sourceFor(false), uuid: null, revision: 1 }), steps: [S.receive(UPDATE_FROM_ORIGINAL)] },
  { name: "an item.replace of an item with no source leaves its source null", me: SAM.id, pack: copiedPack(null),
    steps: [S.receive(UPDATE_FROM_ORIGINAL)] },
  { name: "an item I copied in while the pack was open, then updated from the original, reads the same again, its source in the server's shape",
    me: SAM.id, pack: PACK,
    steps: [S.receive(COPIED_IN(SAM)), S.receive(UPDATED_IN(SAM))] },
  { name: "someone else's copy, made and updated while the pack is open, tells me nothing about its original", me: SAM.id, pack: PACK,
    steps: [S.receive(COPIED_IN(COLIN)), S.receive(UPDATED_IN(COLIN))] },
  { name: "the pack's head follows the newest event, ours included", me: SAM.id, pack: PACK,
    steps: [
      S.receive(theirs(4, setHeading(90)), theirs(5, setHeading(95))),
      S.edit(setHeading(100)),
      S.nextBatch(),
      S.batchAnswered(answered(ours(6, setHeading(100), "op-1"))),
    ] },

  // -- Events about the pack --------------------------------------------------------------------------------------
  { name: "the pack is renamed, then finished (what is pending is dropped as pack_finished), then reopened", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.receive(server(4, { type: "pack.update", name: "OP EAGLE" })),
      S.receive(server(5, FINISH)),
      S.receive(server(6, REOPEN)),
    ] },
  { name: "a new description leaves the name as it was", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { type: "pack.update", description: "Phase 2 moved to Thursday." }, SAM))] },
  { name: "sharing with a team names the team by id and role only", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { type: "pack.share", team_id: 5, team_role: "editor" }))] },
  { name: "sharing with another team keeps the first team's name and size until the pack is loaded again", me: SAM.id, pack: SHARED,
    steps: [S.receive(server(4, { type: "pack.share", team_id: 7, team_role: "viewer" }))] },
  { name: "the owner stops sharing with a team", me: SAM.id, pack: SHARED,
    steps: [S.receive(server(4, { type: "pack.share", team_id: null, team_role: "editor" }))] },
  { name: "a team deleted stops the sharing with an event that names no role", me: SAM.id, pack: SHARED,
    steps: [S.receive(server(4, { type: "pack.share", team_id: null }, COLIN,
      { summary: 'The team "1ST PLT" was deleted, so its members no longer see this pack through it.' }))] },
  { name: "a viewer stays read-only when the pack is reopened", me: SAM.id,
    pack: packBody({ role: "viewer", members: [member(COLIN, "owner"), member(SAM, "viewer")], ...FINISHED }),
    steps: [S.receive(server(5, REOPEN))] },
  { name: "the owner hands the pack to me", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { type: "pack.transfer", user_id: SAM.id, name: "Sam" }))] },
  { name: "the owner hands the pack to someone else and becomes an editor", me: COLIN.id, pack: packBody({ role: "owner" }),
    steps: [S.receive(server(4, { type: "pack.transfer", user_id: SAM.id, name: "Sam" }))] },
  { name: "a hand-over between two others leaves my role as it was", me: ALEX.id, pack: { ...WITH_ALEX, role: "viewer" },
    steps: [S.receive(server(4, { type: "pack.transfer", user_id: SAM.id, name: "Sam" }))] },
  { name: "a viewer the pack is handed to becomes its owner and may edit", me: SAM.id, pack: VIEWER_PACK,
    steps: [S.receive(server(4, { type: "pack.transfer", user_id: SAM.id, name: "Sam" })), S.edit(setHeading(90))] },
  { name: "the server hands the pack to me when its owner's account is deleted", me: SAM.id, pack: PACK,
    steps: [S.receive(
      server(4, { type: "pack.transfer", user_id: SAM.id, name: "Sam" }, SERVER, { summary: "Sam owns the pack now: its owner's account was deleted." }),
      server(5, { type: "member.remove", user_id: COLIN.id, name: "Colin" }, SERVER, { summary: "Colin left: their account was deleted." }),
    )] },
  { name: "someone added is listed with no address, and the count goes up", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { type: "member.add", user_id: ALEX.id, name: "Alex", role: "viewer" }))] },
  { name: "someone already listed is not added twice, and the count stays", me: SAM.id, pack: WITH_ALEX,
    steps: [S.receive(server(4, { type: "member.add", user_id: ALEX.id, name: "Alex", role: "editor" }))] },
  { name: "someone who accepted an invitation joins", me: SAM.id, pack: PACK,
    steps: [S.receive(server(4, { type: "member.join", user_id: ALEX.id, name: "Alex", role: "viewer" }, ALEX))] },
  { name: "my role lowered to viewer makes the pack read-only, and what is queued is dropped as read_only", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.receive(server(4, { type: "member.role", user_id: SAM.id, name: "Sam", role: "viewer" }))] },
  { name: "my role lowered to viewer while a batch is out drops nothing until its answer: a 403 then drops it with what was queued "
    + "behind it, as read_only, in the order they were made", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.receive(server(4, { type: "member.role", user_id: SAM.id, name: "Sam", role: "viewer" })),
      S.batchFailed(READ_ONLY_403),
    ] },
  { name: "my role raised to editor lets me edit", me: SAM.id, pack: VIEWER_PACK,
    steps: [S.receive(server(4, { type: "member.role", user_id: SAM.id, name: "Sam", role: "editor" })), S.edit(setHeading(90))] },
  { name: "someone else's role change is only theirs", me: SAM.id, pack: WITH_ALEX,
    steps: [S.receive(server(4, { type: "member.role", user_id: ALEX.id, name: "Alex", role: "editor" }))] },
  { name: "someone else removed leaves the list, and the count goes down", me: SAM.id, pack: WITH_ALEX,
    steps: [S.receive(server(4, { type: "member.remove", user_id: ALEX.id, name: "Alex" }))] },
  { name: "removing someone not listed lowers the count all the same, never below zero", me: SAM.id, pack: PACK,
    steps: [S.receive(
      server(4, { type: "member.remove", user_id: 9, name: "Kim" }),
      server(5, { type: "member.remove", user_id: 10, name: "Lee" }),
      server(6, { type: "member.remove", user_id: 11, name: "Max" }),
      server(7, { type: "member.add", user_id: ALEX.id, name: "Alex", role: "viewer" }),
    )] },
  { name: "me removed from a pack not shared with a team: gone, and what is pending is dropped as gone", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.receive(server(4, { type: "member.remove", user_id: SAM.id, name: "Sam" }))] },
  { name: "me removed while an edit the server took is unseen: that edit stays, and only what it did not take is dropped as gone", me: SAM.id,
    pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.edit(setHeading(91)),
      S.receive(server(6, { type: "member.remove", user_id: SAM.id, name: "Sam" })),
    ] },
  { name: "me removed from a pack shared with a team: still in it through the team, and still editing", me: SAM.id, pack: SHARED,
    steps: [S.edit(setHeading(90)), S.receive(server(4, { type: "member.remove", user_id: SAM.id, name: "Sam" })), S.edit(setHeading(91))] },
  { name: "made a viewer and the pack finished in one delivery: what is pending is dropped as pack_finished", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.receive(server(4, { type: "member.role", user_id: SAM.id, name: "Sam", role: "viewer" }), server(5, FINISH)),
    ] },
  { name: "made a viewer and removed in one delivery: what is pending is dropped as gone, and gone is asked before read_only", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.receive(
        server(4, { type: "member.role", user_id: SAM.id, name: "Sam", role: "viewer" }),
        server(5, { type: "member.remove", user_id: SAM.id, name: "Sam" }),
      ),
      S.edit(setHeading(91)),
    ] },
  { name: "removed and the pack finished in one delivery: what is pending is dropped as gone, and gone is asked before pack_finished", me: SAM.id,
    pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.receive(server(4, { type: "member.remove", user_id: SAM.id, name: "Sam" }), server(5, FINISH)),
    ] },
  { name: "a finish seen while an edit the server took is unseen keeps that edit, which its event confirms after a reopen", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(answer([theirs(4, setCallSign("HAWK 7"))], [{ client_op_id: "op-1", seq: 7, status: "applied", reason: null }],
        { hasMore: true, headSeq: 7 })),
      S.receive(server(5, FINISH)),
      S.receive(server(6, REOPEN), ours(7, setHeading(90), "op-1")),
    ] },
  { name: "a finish seen while a batch is out drops nothing until its answer: a 423 then drops it with what was queued behind it, in the "
    + "order they were made, so my version ends on my last edit", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.receive(server(4, FINISH)),
      S.batchFailed({ status: 423, code: "pack_finished", finished_at: at(4), finished_by: COLIN, taken: [] }),
    ] },
  { name: "a finish seen while a batch is out that is then refused as too large: it is dropped as item_too_large, and what was queued "
    + "behind it as pack_finished, in order", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.receive(server(4, FINISH)),
      S.batchFailed(TOO_LARGE_413),
    ] },
  { name: "a finish seen while a batch is out whose answer was lost: it goes again with what was queued behind it, and the pack decides "
    + "(reopened, it takes both)", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.receive(server(4, FINISH)),
      S.batchFailed({ status: 0 }),
      S.nextBatch(),
      S.batchAnswered(answered(server(5, REOPEN), ours(6, setHeading(90), "op-1"), ours(7, setHeading(91), "op-2"))),
    ] },
  { name: "me removed while a batch is out: gone drops it at once with what is queued, as gone, since its answer is not waited for",
    me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.receive(server(4, { type: "member.remove", user_id: SAM.id, name: "Sam" })),
    ] },
  { name: "events this version does not act on are passed over, their numbers counted", me: SAM.id, pack: PACK,
    steps: [S.receive(
      server(4, { type: "invite.create", email: "kim@unit.example", role: "viewer" }),
      server(5, { type: "pack.archive" }, COLIN, { summary: "Colin archived the pack." }),
      theirs(6, setHeading(90)),
    )] },

  // -- Answers to a batch -----------------------------------------------------------------------------------------
  { name: "a batch's answer applies everything after base_seq, ours and others'", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(answered(theirs(4, rename("lz-1", "LZ EAGLE")), ours(5, setHeading(90), "op-1"))),
    ] },
  { name: "an answer whose events did not all fit leaves ours taken but unseen, never sent again", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.nextBatch(),
      S.edit(setHeading(95)),
      S.nextBatch(),
      S.receive(
        theirs(6, { type: "upsert", item: "lz-1", path: ["graphics", "helicopters"], value: { id: "h-2", lat: 34.51, lon: -84.11, heading: 90 } }),
        ours(7, setHeading(90), "op-1"),
      ),
      S.batchAnswered(answered(
        theirs(6, { type: "upsert", item: "lz-1", path: ["graphics", "helicopters"], value: { id: "h-2", lat: 34.51, lon: -84.11, heading: 90 } }),
        ours(7, setHeading(90), "op-1"),
        ours(8, setHeading(95), "op-2"),
      )),
    ] },
  { name: "an answer naming an operation whose event it did not carry still asks for a catch-up", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(answer([], [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }]))] },
  { name: "events the live stream brought while the batch was out are passed over in its answer", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.receive(theirs(4, setCallSign("HAWK 7")), ours(5, setHeading(90), "op-1")),
      S.batchAnswered(answered(theirs(4, setCallSign("HAWK 7")), ours(5, setHeading(90), "op-1"))),
    ] },
  { name: "an answer whose events skip a number stops there and asks for a catch-up", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(answered(theirs(5, setCallSign("HAWK 7")), ours(6, setHeading(90), "op-1")))] },
  { name: "an answer whose events skip a number after ours still asks for a catch-up", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(answered(ours(4, setHeading(90), "op-1"), theirs(6, setCallSign("HAWK 7"))))] },
  { name: "an answer with more events to come asks for a catch-up, though ours were all on the page", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(answer([ours(4, setHeading(90), "op-1")], [resultOf(ours(4, setHeading(90), "op-1"))], { hasMore: true, headSeq: 5 })),
    ] },
  { name: "an operation the server skipped, its event not on the page, is taken all the same and never sent again", me: SAM.id, pack: PACK,
    steps: [
      S.edit({ type: "remove", item: "lz-1", path: HELO("h-9") }),
      S.nextBatch(),
      S.batchAnswered(answer([theirs(4, setCallSign("HAWK 7"))], [{ client_op_id: "op-1", seq: 5, status: "skipped", reason: "target_missing" }],
        { hasMore: true, headSeq: 5 })),
      S.nextBatch(),
    ] },
  { name: "an operation the server skipped is confirmed all the same", me: SAM.id, pack: PACK,
    steps: [
      S.edit({ type: "remove", item: "lz-1", path: HELO("h-9") }),
      S.nextBatch(),
      S.batchAnswered(answered(ours(4, { type: "remove", item: "lz-1", path: HELO("h-9") }, "op-1", { status: "skipped", reason: "target_missing" }))),
    ] },
  // The results are read before the page: a page that ends where the pack is read-only, or I am out of it, drops none of what they name.
  { name: "an answer whose page ends at a finish (has_more) keeps what its results say was taken, and drops what was queued behind it; "
    + "the events after the page, a reopen and ours, confirm the taken one", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.batchAnswered(answer([theirs(4, setCallSign("HAWK 7")), server(5, FINISH)], [{ client_op_id: "op-1", seq: 7, status: "applied", reason: null }],
        { hasMore: true, headSeq: 7 })),
      S.receive(server(6, REOPEN), ours(7, setHeading(90), "op-1")),
    ] },
  { name: "an answer whose page ends at my removal (has_more; I was added back after) keeps what its results say was taken, and drops "
    + "only what was queued, as gone", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.batchAnswered(answer([server(4, { type: "member.remove", user_id: SAM.id, name: "Sam" })],
        [{ client_op_id: "op-1", seq: 6, status: "applied", reason: null }], { hasMore: true, headSeq: 6 })),
    ] },

  // -- A batch the server did not take ----------------------------------------------------------------------------
  { name: "a batch that got no answer is sent again as it was", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 0 }), S.nextBatch()] },
  { name: "a batch refused with 401 waits, unchanged, to be sent again once signed in", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 401 })] },
  { name: "a batch refused with 429 is sent again later", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 429, code: "rate_limited" })] },
  { name: "a batch that met a server error is sent again later", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 500 })] },
  { name: "a batch that met a bad gateway is sent again later, with what was queued behind it", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed({ status: 502 }), S.nextBatch()] },
  { name: "a batch refused because the pack was finished drops everything pending and says who finished it", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed(FINISHED_423)] },
  { name: "a 423 that names nobody (whoever finished the pack has since deleted their account) marks it finished, says when, and finished_by "
    + "is null", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(),
      S.batchFailed({ status: 423, code: "pack_finished", finished_at: at(8), finished_by: null, taken: [] })] },
  { name: "a 423 keeps an edit the server took whose event was not seen yet, and drops only what it did not take", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(ACKED_ANSWER), S.edit(setHeading(91)), S.nextBatch(), S.batchFailed(FINISHED_423)] },
  { name: "a batch refused because I may only view drops everything pending as read_only", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed(READ_ONLY_403)] },
  { name: "a batch refused because I may only view keeps an edit the server took, and drops only what it did not take", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(ACKED_ANSWER), S.edit(setHeading(91)), S.nextBatch(),
      S.batchFailed(READ_ONLY_403)] },
  { name: "a batch refused with another 403 means the pack is no longer this person's to see", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed({ status: 403, code: "feature_disabled" })] },
  { name: "a batch refused with 404 means the pack is gone, and a later edit is refused as gone", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 404, code: "pack_not_found" }), S.edit(setHeading(91))] },
  { name: "a batch refused with 404 keeps an edit the server took: the pack had it, so it is not mine to save", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchAnswered(ACKED_ANSWER), S.edit(setHeading(91)), S.nextBatch(),
      S.batchFailed({ status: 404, code: "pack_not_found" })] },
  // A batch whose answer was lost may have been taken, and sent again (with what was made meanwhile) it can be refused. The
  // refusal's `taken` names what of it the pack already has (docs/MISSION_PACKS.md §4): that is acked, and only the rest dropped.
  { name: "a batch whose answer was lost, refused with 423 when sent again with an edit made meanwhile: what the 423 says the pack took "
    + "the first time (taken) is kept, so it is not offered back, its event confirms it, and only the edit made meanwhile is dropped",
    me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.edit(setHeading(91)),
      S.nextBatch(),
      S.batchFailed({ ...LOST_423, taken: [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }] }),
      S.receive(ours(4, setHeading(90), "op-1"), server(5, FINISH)),
    ] },
  { name: OLDER_SERVER, me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.edit(setHeading(91)),
      S.nextBatch(),
      S.batchFailed(LOST_423),
      S.receive(ours(4, setHeading(90), "op-1"), server(5, FINISH)),
    ] },
  { name: "a batch whose answer was lost, refused with 403 pack_read_only when sent again (I was made a viewer meanwhile): what the 403 "
    + "says the pack took, one it skipped too, is kept, and only the edit made meanwhile is dropped, as read_only", me: SAM.id, pack: PACK,
    steps: [
      S.edit([setHeading(90), { type: "remove", item: "lz-1", path: HELO("h-9") }]),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.edit(setHeading(91)),
      S.nextBatch(),
      S.batchFailed({ ...READ_ONLY_403, taken: [
        { client_op_id: "op-1", seq: 4, status: "applied", reason: null },
        { client_op_id: "op-2", seq: 5, status: "skipped", reason: "target_missing" },
      ] }),
      S.receive(
        ours(4, setHeading(90), "op-1"),
        ours(5, { type: "remove", item: "lz-1", path: HELO("h-9") }, "op-2", { status: "skipped", reason: "target_missing" }),
        server(6, { type: "member.role", user_id: SAM.id, name: "Sam", role: "viewer" }),
      ),
    ] },
  { name: "a batch whose answer was lost that grew by an edit too large: what the 413 to it says the pack took the first time is kept, "
    + "only the edit too large is dropped, and what was queued behind the batch goes next", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.edit(setCallSign("HAWK 9")),
      S.nextBatch(),
      S.edit(setHeading(92)),
      S.batchFailed({ ...TOO_LARGE_413, taken: [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }] }),
      S.nextBatch(),
      S.batchAnswered(answer([ours(4, setHeading(90), "op-1"), ours(5, setHeading(92), "op-3")], [resultOf(ours(5, setHeading(92), "op-3"))])),
    ] },
  { name: "a batch whose answer was lost, sent again after a reload that passed its event, refused with 423: what taken names is in the "
    + "reloaded copy already and its event will not come again, so it leaves pending at once and nothing is offered back", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.reload(packBody({ headSeq: 4, items: [HEADING_90_BY_ME, PS_ITEM] })),
      S.nextBatch(),
      S.batchFailed({ ...LOST_423, taken: [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }] }),
    ] },
  // `taken` is about the batch out. The server names only operations of the batch it was sent (it looks up the batch's own
  // ids); a name that is not in it is not acked, or an edit the server has never seen would be neither sent nor offered back.
  { name: "a 423 whose taken names an edit still queued, not in the batch out: the pack has never seen it, so it is dropped with the "
    + "batch and offered back", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.batchFailed({ ...FINISHED_423, taken: [{ client_op_id: "op-2", seq: 9, status: "applied", reason: null }] }),
    ] },
  { name: "a 413 whose taken names edits not in the batch out (one still queued, one an earlier answer acked) leaves both as they were: "
    + "the queued one goes in the next batch, and the acked one keeps the seq its answer gave it", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.edit(setHeading(91)),
      S.nextBatch(),
      S.edit(setCallSign("HAWK 9")),
      S.batchFailed({ ...TOO_LARGE_413, taken: [
        { client_op_id: "op-1", seq: 6, status: "applied", reason: null },
        { client_op_id: "op-3", seq: 8, status: "applied", reason: null },
      ] }),
      S.nextBatch(),
    ] },
  // Nothing is sent to ask, so nothing says what the pack has: docs/MISSION_PACKS.md §5, rule 7.
  { name: "a batch whose answer was lost, then a reload into a finished pack before it goes again: it is dropped though the pack took it "
    + "(the reload has it), as nothing is sent to ask (a known gap, pinned as the web does it)", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.reload(packBody({ headSeq: 5, status: "finished", finishedAt: at(5), finishedBy: COLIN, items: [HEADING_90_BY_ME, PS_ITEM] })),
      S.nextBatch(),
    ] },
  { name: "a malformed batch drops that batch only, with the reason the server gave, and the rest still goes", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.batchFailed({ status: 400, code: "invalid_op", reason: "bad_path" }),
      S.nextBatch(),
    ] },
  { name: "a batch too large drops that batch only, named by its code", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed(TOO_LARGE_413)] },
  { name: "a refusal that names no code or reason is named by its status", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed({ status: 400 })] },
  { name: "a 409 drops that batch only, named by its status", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed({ status: 409 }), S.nextBatch()] },
  { name: "a 422 (the sign-in library refusing a token it cannot read) drops that batch as http_422, where a 401 waits: "
    + "pinned as the web does it, an open question", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setHeading(91)), S.batchFailed({ status: 422 }), S.nextBatch()] },

  // -- Signing out ------------------------------------------------------------------------------------------------
  { name: "signing out gives up what is queued or sent and keeps what the server took", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.edit(setHeading(91)),
      S.nextBatch(),
      S.edit(setHeading(92)),
      S.abandon("signed_out"),
    ] },
  { name: "signing out with nothing waiting changes nothing", me: SAM.id, pack: PACK, steps: [S.abandon("signed_out")] },

  // -- Loading the pack again -------------------------------------------------------------------------------------
  { name: "a reload while a batch is out keeps it out, on top of the server's copy", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.reload(packBody({
        headSeq: 5,
        items: [
          packItem("lz-1", "lz", "LZ EAGLE", { ...LZ_DATA, flightData: { callSign: "HAWK 7", landingHeading: 270 } }, { seq: 5, createdSeq: 2, revision: 3 }),
          PS_ITEM,
        ],
      })),
      S.batchAnswered(answered(
        theirs(4, rename("lz-1", "LZ EAGLE")),
        theirs(5, setCallSign("HAWK 7"), COLIN, { summary: 'Colin edited "LZ EAGLE".' }),
        ours(6, setHeading(90), "op-1", { summary: 'Sam edited "LZ EAGLE".' }),
      )),
    ] },
  { name: "a reload into a pack where I am now a viewer, while a batch is out, drops nothing until its answer: a 403 then drops it with "
    + "what was queued behind it, as read_only", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.edit(setHeading(91)),
      S.reload(packBody({ role: "viewer", headSeq: 5, members: [member(COLIN, "owner"), member(SAM, "viewer")] })),
      S.batchFailed(READ_ONLY_403),
    ] },
  { name: "a reload into a finished pack while a batch is out keeps it out: its answer, naming an event the reload has passed, confirms "
    + "it, and nothing is offered back", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.reload(packBody({
        headSeq: 5,
        status: "finished",
        finishedAt: at(5),
        finishedBy: COLIN,
        items: [packItem("lz-1", "lz", "LZ HAWK", { ...LZ_DATA, flightData: { callSign: "HAWK 6", landingHeading: 90 } },
          { seq: 4, createdSeq: 2, revision: 2, updatedBy: SAM }), PS_ITEM],
      })),
      S.batchAnswered(answered(ours(4, setHeading(90), "op-1"))),
    ] },
  { name: "a reload into a finished pack drops what is pending as pack_finished", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.reload(packBody(FINISHED))] },
  { name: "a reload keeps what was dropped before it", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.batchFailed(TOO_LARGE_413), S.reload(PACK)] },
  { name: "a reload keeps an edit the server took but has not shown pending on top, until its event comes", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.reload(packBody({
        headSeq: 5,
        items: [
          packItem("lz-1", "lz", "LZ EAGLE", { ...LZ_DATA, flightData: { callSign: "HAWK 7", landingHeading: 270 } }, { seq: 5, createdSeq: 2, revision: 3 }),
          PS_ITEM,
        ],
      })),
      S.receive(theirs(6, setCallSign("HAWK 8")), ours(7, setHeading(90), "op-1")),
    ] },
  { name: "a reload into a pack where I am now a viewer, past an edit the server took, ends that edit's wait and drops the rest as read_only",
    me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.edit(setHeading(91)),
      S.reload(packBody({
        role: "viewer",
        headSeq: 7,
        members: [member(COLIN, "owner"), member(SAM, "viewer")],
        items: [HEADING_90_AT(7), PS_ITEM],
      })),
    ] },
  { name: "a reload into a pack where I am now a viewer, short of an edit the server took, keeps that edit until its event comes, "
    + "and drops the rest as read_only", me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.edit(setHeading(91)),
      S.reload(packBody({ role: "viewer", headSeq: 5, members: [member(COLIN, "owner"), member(SAM, "viewer")], items: [EAGLE_AT_5, PS_ITEM] })),
      S.receive(theirs(6, setCallSign("HAWK 8")), ours(7, setHeading(90), "op-1")),
    ] },
  { name: "a reload past an edit the server took ends its wait, as its event will not come again, and a later change to that field shows",
    me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchAnswered(ACKED_ANSWER),
      S.reload(packBody({ headSeq: 7, items: [HEADING_90_AT(7), PS_ITEM] })),
      S.receive(theirs(8, setHeading(180), COLIN, { summary: 'Colin edited "LZ EAGLE".' })),
    ] },
  { name: "an operation the server skipped, passed by a reload before its event came, ends its wait the same way", me: SAM.id, pack: PACK,
    steps: [
      S.edit({ type: "remove", item: "lz-1", path: HELO("h-9") }),
      S.nextBatch(),
      S.batchAnswered(answer([theirs(4, setCallSign("HAWK 7"))], [{ client_op_id: "op-1", seq: 5, status: "skipped", reason: "target_missing" }],
        { hasMore: true, headSeq: 5 })),
      S.reload(packBody({
        headSeq: 5,
        items: [
          packItem("lz-1", "lz", "LZ HAWK", { ...LZ_DATA, flightData: { callSign: "HAWK 7", landingHeading: 270 } }, { seq: 4, createdSeq: 2, revision: 2 }),
          PS_ITEM,
        ],
      })),
    ] },
  { name: "a batch answered after a reload that already holds its event is confirmed by its result, and asks for no catch-up", me: SAM.id,
    pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.reload(packBody({ headSeq: 6, items: [HEADING_90_AT(6), PS_ITEM] })),
      S.batchAnswered(answered(
        theirs(4, rename("lz-1", "LZ EAGLE")),
        theirs(5, setCallSign("HAWK 7"), COLIN, { summary: 'Colin edited "LZ EAGLE".' }),
        ours(6, setHeading(90), "op-1", { summary: 'Sam edited "LZ EAGLE".' }),
      )),
    ] },
  { name: "a batch sent again after its answer was lost, answered from the log with a seq a reload has passed, is confirmed by that result",
    me: SAM.id, pack: PACK,
    steps: [
      S.edit(setHeading(90)),
      S.nextBatch(),
      S.batchFailed({ status: 0 }),
      S.reload(packBody({
        headSeq: 4,
        items: [packItem("lz-1", "lz", "LZ HAWK", { ...LZ_DATA, flightData: { callSign: "HAWK 6", landingHeading: 90 } },
          { seq: 4, createdSeq: 2, revision: 2, updatedBy: SAM }), PS_ITEM],
      })),
      S.nextBatch(),
      S.batchAnswered(answer([], [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }])),
    ] },

  // -- What the person sees ---------------------------------------------------------------------------------------
  { name: "an item made here is shown at the end before the server has it", me: SAM.id, pack: PACK,
    steps: [S.edit(create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW")))] },
  { name: "the pack's order is kept as the server gave it, and items made here follow in the order they were made", me: SAM.id,
    pack: packBody({
      items: [packItem("ps-1", "pointset", "LOCAL", POINTS, { seq: 2 }), packItem("lz-1", "lz", "LZ HAWK", LZ_DATA, { seq: 3 })],
    }),
    steps: [
      S.edit(create("rt-9", "route", "MISSION 9", { version: 1, routes: [] })),
      S.edit(create("lz-0", "lz", "LZ ZERO", NEW_LZ("d-0", "LZ ZERO"))),
    ] },
  { name: "a pending rename shows over what the pack knows, and a pending delete hides the item", me: SAM.id, pack: PACK,
    steps: [S.edit(rename("lz-1", "LZ EAGLE")), S.edit(deleteItem("ps-1"))] },
  { name: "items made here follow in the order JavaScript lists an object's keys: canonical array indices (7, 42, 4294967294) "
    + "first, ascending; then the rest as made, 042 and 4294967295 among them", me: SAM.id, pack: PACK,
    steps: [S.edit(["lz-9", "42", "042", "4294967295", "4294967294", "7"].map((id) => create(id, "lz", `LZ ${id}`, NEW_LZ(`d-${id}`, `LZ ${id}`))))] },

  // -- What the pack would not take, kept as the person's own version -----------------------------------------------
  { name: "edits refused when the pack was finished come back as my version of each item", me: SAM.id, pack: PACK,
    steps: [
      S.edit([
        { type: "patch", item: "lz-1", path: ["flightData"], value: { callSign: "HAWK 9" } },
        { type: "patch", item: "lz-1", path: HELO("h-1"), value: { lat: 34.51 } },
        create("ps-9", "pointset", "NEW POINTS", [{ id: "a", lat: 34.6, lon: -84.2 }]),
        { type: "remove", item: "lz-1", path: HELO("h-99") },
      ]),
      S.nextBatch(),
      S.batchFailed(FINISHED_423),
    ] },
  { name: "an item made here and refused comes back whole, with what was done to it after", me: SAM.id, pack: PACK,
    steps: [
      S.edit([
        create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW")),
        { type: "set", item: "lz-2", path: ["flightData", "landingHeading"], value: 45 },
      ]),
      S.nextBatch(),
      S.batchFailed(TOO_LARGE_413),
    ] },
  { name: "an edit to an item someone else deleted meanwhile is left out", me: SAM.id, pack: PACK,
    steps: [
      S.edit([setHeading(90), { type: "patch", item: "ps-1", path: [{ id: "lps-0" }], value: { name: "ALFA" } }]),
      S.receive(theirs(4, deleteItem("lz-1"))),
      S.receive(server(5, FINISH)),
    ] },
  { name: "an item I deleted is not offered back", me: SAM.id, pack: PACK,
    steps: [S.edit([deleteItem("ps-1"), setHeading(90)]), S.receive(server(4, FINISH))] },
  { name: "what two refusals dropped is put together, in the order the items were first touched", me: SAM.id, pack: PACK,
    steps: [
      S.edit({ type: "patch", item: "ps-1", path: [{ id: "lps-0" }], value: { name: "ALFA" } }),
      S.nextBatch(),
      S.batchFailed(TOO_LARGE_413),
      S.edit([setHeading(90), { type: "upsert", item: "ps-1", path: [], value: { id: "lps-1", name: "BRAVO", lat: 34.6, lon: -84.2 } }]),
      S.receive(server(4, FINISH)),
    ] },
  { name: "my version starts from the item as the pack confirmed it, not from my edits still waiting to be sent", me: SAM.id, pack: PACK,
    steps: [S.edit(setHeading(90)), S.nextBatch(), S.edit(setCallSign("HAWK 9")), S.batchFailed(TOO_LARGE_413)] },
  { name: "a rename the pack would not take names my version", me: SAM.id, pack: PACK,
    steps: [S.edit([rename("lz-1", "LZ EAGLE"), setHeading(90)]), S.nextBatch(), S.batchFailed(FINISHED_423)] },
  // The batch was out when the finish was seen, so it waited for its answer: the pack, reopened, took it after all.
  { name: "an item made here, in flight when a finish was seen, waits for its answer: the pack, reopened, took it (and has since deleted "
    + "it), so nothing is offered back", me: SAM.id, pack: PACK,
    steps: [
      S.edit(create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW"))),
      S.nextBatch(),
      S.receive(server(4, FINISH)),
      S.batchAnswered(answered(
        server(4, FINISH),
        server(5, REOPEN),
        ours(6, create("lz-2", "lz", "LZ CROW", NEW_LZ("d-2", "LZ CROW")), "op-1"),
        theirs(7, deleteItem("lz-2"), COLIN, { summary: 'Colin removed "LZ CROW".' }),
      )),
    ] },
];

// -- Playing a scenario -------------------------------------------------------------------------------------------

const plain = (value) => JSON.parse(JSON.stringify(value));

// Everything handed to the session, and every session it returns, is frozen: a function that changed what it was
// given would throw here (modules run in strict mode), so the recorded sessions are what pure functions return.
const freeze = (value) => {
  if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value).forEach(freeze);
  }
  return value;
};

const play = ({ me, pack, steps }) => {
  let taken = 0;
  const newId = () => `op-${(taken += 1)}`;
  const recorded = [];
  const record = (step, session, result) => {
    recorded.push({
      ...step,
      ...(result === undefined ? {} : { result: plain(result) }),
      session: plain(session),
      visible: plain(visibleItems(session)),
    });
    return freeze(session);
  };

  let session = record({ do: "open" }, openSession(freeze(plain(pack)), me));
  steps.forEach((step) => {
    const input = freeze(plain(step));
    switch (step.do) {
      case "edit": {
        const out = edit(session, input.ops, newId);
        session = record(step, out.session, { refused: out.refused });
        break;
      }
      case "nextBatch": {
        const out = nextBatch(session);
        session = record(step, out ? out.session : session, out ? { batch: out.batch } : null);
        break;
      }
      case "receive": {
        const out = receive(session, input.events);
        session = record(step, out.session, { gap: out.gap });
        break;
      }
      case "batchAnswered": {
        const out = batchAnswered(session, input.answer);
        session = record(step, out.session, { catchUp: out.catchUp });
        break;
      }
      case "batchFailed":
        session = record(step, batchFailed(session, input.failure));
        break;
      case "abandon":
        session = record(step, abandon(session, input.reason));
        break;
      case "reload":
        session = record(step, reloadSession(session, input.pack));
        break;
      default:
        throw new Error(`unknown step ${step.do}`);
    }
  });
  return { steps: recorded, dropped_versions: plain(droppedVersions(session)) };
};

const sessionFixture = () => withoutClockOrRandomness(() => ({
  description: "How an open mission pack is held on a device, as frontend/src/feature/missionPacks/packSession.js holds it "
    + "(the reference; docs/MISSION_PACKS.md §5), with its droppedVersions. Each scenario opens `pack` "
    + "(a GET /api/packs/<uuid> body: contracts/openapi.yaml `Pack`) as the user whose id is `me`, then plays `steps` in "
    + "order, each on the session the one before left. `do` names the function and the step gives its input: "
    + "open (always the first step): openSession(pack, me); "
    + "edit {ops}: edit(session, ops, newId), where `ops` is one operation or a list of them (one alone is taken as a list "
    + "of one) and newId hands out \"op-1\", \"op-2\", ... counted per scenario, one per operation taken, none for a refused "
    + "edit; nextBatch: nextBatch(session); receive {events}: receive(session, events), the events oldest first as "
    + "GET /api/packs/<uuid>/events and the live stream send them (`PackEvent`); batchAnswered {answer}: "
    + "batchAnswered(session, answer), the 200 body of POST /api/packs/<uuid>/ops (`PackOpsResult`): each entry of the batch "
    + "out (state sent) a result names is acked with that result's seq first (a name not in the batch out leaves its entry as "
    + "it was), then its events are received (so a page that ends where the pack is "
    + "read-only or I am out of it drops none of what the results say was taken); batchFailed {failure}: "
    + "batchFailed(session, failure), where failure is {status, code, reason, finished_by, finished_at, taken} read from a "
    + "refusal's status and body (packApi.failureOf), status 0 when no answer came, a key the body did not have absent; "
    + "taken is the list the server sends on POST .../ops's 403 pack_read_only, 413 and 423 (`PackError.taken`): the "
    + "operations of the refused batch its log already has, the caller's own, in log order, each as a `PackOpResult` "
    + "{client_op_id, seq, status, reason}, [] when none; every such refusal here carries it except one scenario named for a "
    + "server from before it; "
    + "abandon {reason}: abandon(session, reason); reload {pack}: reloadSession(session, pack), a fresh GET body, with what is "
    + "pending kept on top less the acked entries its head_seq has reached, and a read-only copy settled as a pack event that "
    + "makes the pack read-only settles it (below). "
    + "`result` is what the function returned besides the session, and only these have one: edit {refused: null, or why "
    + "nothing was taken}; nextBatch null (nothing queued, or a batch is already out) or {batch: {ops, base_seq}}, the body "
    + "to POST; receive {gap} (true: an event is missing before one given; fetch from session.seq); batchAnswered "
    + "{catchUp} (true: fetch events from session.seq; true when an event was missing, the page has_more, or an acked entry "
    + "still waits). "
    + "`session` is the whole session afterwards: me; pack (the body without members and items, kept current by pack "
    + "events; its head_seq follows the newest event applied); members; confirmed (uuid to {kind, name, data, deleted}: "
    + "the items as the server has confirmed them as of event `seq`; a deleted one stays, name \"\" and data null); info "
    + "(uuid to the item's body without data, for each live confirmed item); order (the confirmed uuids in the order they "
    + "were added); seq; pending ([{op, state}], an acked one {op, state, seq}: edits made here, each op carrying its "
    + "client_op_id, state queued, sent (in the batch out) or acked (taken by the server, its event not yet seen here; seq "
    + "is the event number its result named, null if it named none). An acked entry leaves pending when its event arrives, "
    + "or as soon as session.seq reaches its seq, its effect then being in confirmed whether or not that event was applied "
    + "here (a reload, or events seen before the batch's answer, can move seq past it); one with seq null waits for its "
    + "event); dropped ([{op, reason}]: edits given up as not taken, in the order they were made: only queued and sent ones "
    + "are ever dropped, never an acked one, which the pack has; two known exceptions are below); readOnly; gone (null, removed, "
    + "not_found or forbidden); diverged (an event the server applied "
    + "did not apply here: load the pack again); view (confirmed with every pending op applied over it in order, by the "
    + "rules of packs/ops.json). "
    + "`visible` is visibleItems(session): the live items of `view`, those in `order` first, then the ones made here in the "
    + "order JavaScript's Object.keys lists the keys of `view`: first every key that is a canonical array index (\"0\", or a "
    + "digit 1-9 followed by digits, with a value at most 4294967294), in ascending numeric order, then every other key in "
    + "the order it was added (so \"042\", \"00\" and \"4294967295\", which an item id may be, keep the order they were made "
    + "in). Each is the item's info (or {uuid, pendingCreate: true} for one the server has not confirmed) with view's kind, "
    + "name, data and deleted over it. "
    + "`dropped_versions` is droppedVersions(session) after the last step: [{uuid, kind, name, data}], one for each item a "
    + "dropped op touched, in the order first touched, rebuilt from the item as now confirmed (from nothing, for one made "
    + "here or now deleted) with those ops applied in order, an op that does not apply passed over; an item that ends "
    + "deleted or never made is left out. "
    + "Settling (after every receive, reload, batchAnswered, and a 400/413-like refusal): a session that is gone (an event "
    + "removed me from a pack not shared with a team) drops everything not taken, queued and sent, as gone at once, since "
    + "nothing waits for the answer of a batch out; a read-only one (finished, or I am a viewer) drops what is queued, as "
    + "pack_finished when the pack is finished, else read_only, but not while a batch is out: the server decides only when "
    + "the batch reaches the pack, which may take it yet (it took it before the finish, or takes it after a reopen), so "
    + "nothing is dropped until that batch is answered, and then everything in the order it was made. "
    + "Refusals: first, whatever the status, every entry of the batch out (state sent) that the failure's taken names (matched "
    + "by client_op_id) is acked with that result's seq, as batchAnswered acks what its results name, and one whose seq "
    + "session.seq has already reached then leaves pending at once (a reload passed its event, which will not come again); a "
    + "name not in the batch out (an edit still queued, which the server has never seen, or one an earlier answer acked) "
    + "leaves that entry as it was, for what follows to settle (the server names only operations of the batch it was sent); "
    + "a taken that is absent or not a list names nothing. "
    + "Then: a batch that got no answer, a 401, a 429 or a 5xx is sent again unchanged, and is not "
    + "settled, in a pack seen read-only either (the server may have taken it; nextBatch sends it again, with what is queued "
    + "behind it); 423 drops everything not taken (queued or sent) as pack_finished and takes finished_by and finished_at "
    + "from the failure, each one it has, null included (one absent leaves the pack's as it was); 403 pack_read_only drops "
    + "everything not taken as read_only; any other 403, or a 404, everything not taken as gone; an acked entry stays "
    + "pending through all three; any other status (400, 409, 413, 422, ...) drops what is left of that batch (its sent "
    + "entries) only, named by the body's reason, else its code, else http_<status>, and the rest is then settled. "
    + "So a 422, which the sign-in library answers for a token it cannot read, is dropped as http_422 where a 401 waits for "
    + "the person to sign in again; that may be a web bug (AGENTS.md §15) and is pinned as the web does it today. "
    + "And a batch whose answer was lost, which the pack may have taken, and that is refused when it goes again (the pack "
    + "finished, I was made a viewer, or the batch grew by an edit too large) keeps what taken names and drops only the rest. "
    + "The two known exceptions, pinned as the web does them: a refusal from a server from before taken (absent, so nothing "
    + "is acked and the whole batch is dropped and offered back, as before), and a reload that finds the pack read-only after "
    + "a batch's answer was lost and before it goes again, which drops it as it drops anything queued, since nothing is sent "
    + "to ask (events cannot do this: the batch's own events come before the finish or the role change in the log and "
    + "confirm it first). "
    + "Every input is shaped as the server sends it: packSession.js also has defaults for what the server always sends "
    + "(an event's op, an answer's events and results, a failure's status, a pack's head_seq), and no scenario relies on them. "
    + "In this file, unlike the other pack fixtures, an absent key and a null one are DIFFERENT: compare as JSON with "
    + "undefined and null kept apart. JSON has no undefined, so a key the JavaScript left undefined is absent (a failure's "
    + "code or reason), and a port leaves it out too. Several shapes in the session are narrower than the server's types in "
    + "contracts/openapi.yaml, because the session builds them from an event: a member added by member.add or member.join "
    + "is {user_id, name, email: \"\", role, added_at}, with no seen_at; pack.team after a pack.share from no team is "
    + "{id, role}, with no name or member_count (from a team, it keeps the old team's name and member_count until the pack "
    + "is loaded again); an item's info after its item.create event has source null or the event's source ({kind, uuid, "
    + "revision} for a copy from a library) with original: null added, and no original_updated_at, pack_changes or "
    + "last_pack_change. After an item.replace, a source (where there is one) is in the server's shape (pack_support.source_body): "
    + "revision is the event's source_revision; original is \"same\" when the actor is me and the source names an original "
    + "(uuid not null), else null (the server lets only whoever copied an item in update it, only from an original the copy "
    + "names, and tells nobody else anything about that original); original_updated_at, pack_changes and last_pack_change are "
    + "null (the server counts nothing an update would replace while the original is the same, gives none of the three to "
    + "anyone but the copier, and the event does not say when the original last changed, so original_updated_at is not "
    + "known until the pack is loaded again). (A pack.share that named a team and no "
    + "team_role would leave pack.team's role absent; the server always sends one, and no scenario has it.) So a port holds "
    + "the session's pack, members and info as JSON, or in types whose fields are left out when absent, never decoding them "
    + "into the server's types and writing them out again, which would add nulls or fail. Objects compare without regard "
    + "to key order, lists in order. Event pages here are cut short; the server's hold up to 500, and has_more says there "
    + "is more. The file is written with short values on one line and long ones broken up, so a regenerated scenario "
    + "diffs step by step.",
  generatedBy: GENERATED_BY,
  max_batch: MAX_BATCH,
  scenarios: SCENARIOS.map((scenario) => ({ name: scenario.name, me: scenario.me, pack: scenario.pack, ...play(scenario) })),
}));

// The keys contracts/openapi.yaml requires of each body, so a scenario's inputs are shaped as the server sends them.
// backend/tests/test_openapi_contract.py (PackSessionFixtureTests) holds every input to the spec itself as well.
const PACK_KEYS = ["uuid", "name", "description", "status", "role", "owner", "team", "head_seq", "seen_seq", "member_count",
  "audience_count", "item_count", "item_counts", "finished_at", "finished_by", "created_at", "updated_at", "members", "items", "live_url"];
const ITEM_KEYS = ["uuid", "kind", "name", "revision", "seq", "created_by", "updated_by", "created_at", "updated_at", "source", "data"];
const MEMBER_KEYS = ["user_id", "name", "email", "role", "added_at", "seen_at"];
const EVENT_KEYS = ["seq", "type", "item", "actor", "summary", "status", "reason", "client_op_id", "op", "created_at"];
const RESULT_KEYS = ["client_op_id", "seq", "status", "reason"];
const SOURCE_KEYS = ["kind", "uuid", "revision", "original", "original_updated_at", "pack_changes", "last_pack_change"];
const PERSON_KEYS = ["id", "name"];
const TEAM_KEYS = ["id", "name", "member_count", "role"];
const COUNT_KEYS = ["lz", "route", "pointset"];
const LAST_CHANGE_KEYS = ["actor", "summary", "created_at"];
const sorted = (keys) => [...keys].sort();

let built = null;
const fixture = () => {
  if (!built) built = sessionFixture();
  return built;
};

describe("mission pack session fixture", () => {
  it("packs/session.json", () => settle("packs/session.json", fixture()));

  it("covers every step, outcome and reason", () => {
    const { scenarios } = fixture();
    const steps = scenarios.flatMap((s) => s.steps);
    const of = (kind) => steps.filter((s) => s.do === kind);
    const has = (values, expected) => expected.forEach((value) => expect(values).toContainEqual(value));

    scenarios.forEach((s) => {
      expect(s.steps[0].do).toBe("open");
      expect(s.steps.slice(1).map((step) => step.do)).not.toContain("open");
    });
    expect(sorted(new Set(steps.map((s) => s.do))))
      .toEqual(sorted(["open", "edit", "nextBatch", "receive", "batchAnswered", "batchFailed", "abandon", "reload"]));
    has(of("edit").map((s) => s.result.refused), [null, "gone", "read_only", "bad_path", "bad_op", "unknown_type"]);
    expect(of("nextBatch").some((s) => s.result === null)).toBe(true);
    expect(of("nextBatch").some((s) => s.result?.batch.ops.length === MAX_BATCH)).toBe(true);
    has(of("receive").map((s) => s.result.gap), [true, false]);
    has(of("batchAnswered").map((s) => s.result.catchUp), [true, false]);
    has(of("batchFailed").map((s) => s.failure.status), [0, 400, 401, 403, 404, 409, 413, 422, 423, 429, 500, 502]);
    // A refusal that says the pack has some of the batch, of each kind that can; what it names of the batch out is then acked
    // or, its event passed by a reload, gone from pending, and never dropped; and one from a server that does not say.
    const saying = scenarios.flatMap(({ steps: played }) => played.flatMap((s, i) =>
      (s.do === "batchFailed" && s.failure.taken?.length ? [{ ...s, before: played[i - 1].session }] : [])));
    has(saying.map((s) => s.failure.status), [403, 413, 423]);
    const entry = (session, t) => session.pending.find((p) => p.op.client_op_id === t.client_op_id);
    const out = (s, t) => entry(s.before, t)?.state === "sent";
    const dropIds = (s) => s.session.dropped.map((d) => d.op.client_op_id);
    has(saying.flatMap((s) => s.failure.taken.filter((t) => out(s, t)).map((t) => entry(s.session, t)?.state ?? "left")), ["acked", "left"]);
    saying.forEach((s) => s.failure.taken.filter((t) => out(s, t)).forEach((t) => expect(dropIds(s)).not.toContain(t.client_op_id)));
    // A name outside the batch out (an edit still queued, one an earlier answer acked) is not acked: its entry is as it was,
    // unless the refusal then drops it as one the pack did not take.
    const outside = saying.flatMap((s) => s.failure.taken.filter((t) => entry(s.before, t) && !out(s, t)).map((t) => {
      if (entry(s.session, t)) expect(entry(s.session, t)).toEqual(entry(s.before, t));
      else expect(dropIds(s)).toContain(t.client_op_id);
      return [entry(s.before, t).state, entry(s.session, t)?.state ?? "dropped"];
    }));
    has(outside, [["queued", "queued"], ["queued", "dropped"], ["acked", "acked"]]);
    expect(scenarios.filter((s) => s.name === OLDER_SERVER)).toHaveLength(1);

    const sessions = steps.map((s) => s.session);
    has(sessions.flatMap((s) => s.dropped.map((d) => d.reason)),
      ["pack_finished", "read_only", "gone", "bad_path", "item_too_large", "http_400", "http_409", "http_422", "signed_out"]);
    has(sessions.map((s) => s.gone), [null, "removed", "not_found", "forbidden"]);
    has(sessions.flatMap((s) => s.pending.map((p) => p.state)), ["queued", "sent", "acked"]);
    // Every result names its event (the server's always do), and only an acked entry carries one.
    sessions.flatMap((s) => s.pending).forEach((p) => expect(p.state === "acked" ? Number.isInteger(p.seq) : !("seq" in p)).toBe(true));
    // An acked entry that left pending through a reload, before its event was applied here.
    expect(steps.some((s, i) => s.do === "reload" && steps[i - 1].session.pending.some((p) => p.state === "acked")
      && !s.session.pending.some((p) => p.state === "acked"))).toBe(true);
    // A pack seen read-only while a batch is out holds it (and what is queued behind it) for that batch's answer...
    expect(sessions.some((s) => s.readOnly && !s.gone && s.pending.some((p) => p.state === "sent")
      && s.pending.some((p) => p.state === "queued"))).toBe(true);
    // ...so what is dropped is always in the order it was made (newId counts up in that order, per scenario).
    sessions.forEach((s) => {
      const made = s.dropped.map((d) => Number(d.op.client_op_id.slice("op-".length)));
      expect(made).toEqual([...made].sort((a, b) => a - b));
    });
    expect(sessions.some((s) => s.diverged)).toBe(true);
    expect(steps.some((s) => s.visible.some((item) => item.pendingCreate))).toBe(true);
    expect(scenarios.filter((s) => s.dropped_versions.length > 0).length).toBeGreaterThanOrEqual(5);

    const events = steps.flatMap((s) => [...(s.events || []), ...(s.answer?.events || [])]);
    has(events.map((e) => e.type), [
      "item.create", "item.delete", "item.rename", "item.replace", "set", "patch", "upsert", "insert", "remove",
      "pack.update", "pack.share", "pack.finish", "pack.reopen", "pack.transfer",
      "member.add", "member.join", "member.role", "member.remove", "invite.create", "pack.archive",
    ]);
    has(events.map((e) => e.status), ["applied", "skipped"]);
  });

  it("gives every input the shape the server sends", () => {
    const { scenarios } = fixture();
    const packs = scenarios.flatMap((s) => [s.pack, ...s.steps.filter((step) => step.do === "reload").map((step) => step.pack)]);
    const person = (value) => expect(sorted(Object.keys(value))).toEqual(sorted(PERSON_KEYS));
    // A person who may be null (an item made by a deleted account, a pack nobody finished): the keys, or none.
    const personOrNull = (value) => expect(value === null ? null : sorted(Object.keys(value))).toEqual(value === null ? null : sorted(PERSON_KEYS));
    packs.forEach((pack) => {
      expect(sorted(Object.keys(pack))).toEqual(sorted(PACK_KEYS));
      person(pack.owner);
      personOrNull(pack.finished_by);
      expect(pack.team === null ? null : sorted(Object.keys(pack.team))).toEqual(pack.team === null ? null : sorted(TEAM_KEYS));
      expect(sorted(Object.keys(pack.item_counts))).toEqual(sorted(COUNT_KEYS));
      pack.members.forEach((m) => expect(sorted(Object.keys(m))).toEqual(sorted(MEMBER_KEYS)));
      pack.items.forEach((item) => {
        expect(sorted(Object.keys(item))).toEqual(sorted(ITEM_KEYS));
        personOrNull(item.created_by);
        personOrNull(item.updated_by);
      });
    });
    const sources = packs.flatMap((pack) => pack.items.filter((item) => item.source).map((item) => item.source));
    sources.forEach((source) => expect(sorted(Object.keys(source))).toEqual(sorted(SOURCE_KEYS)));
    sources.filter((source) => source.last_pack_change).forEach(({ last_pack_change: change }) => {
      expect(sorted(Object.keys(change))).toEqual(sorted(LAST_CHANGE_KEYS));
      person(change.actor);
    });
    expect(sources.some((source) => source.last_pack_change)).toBe(true);
    expect(packs.some((pack) => pack.team !== null)).toBe(true);
    expect(packs.some((pack) => pack.finished_by !== null)).toBe(true);
    const steps = scenarios.flatMap((s) => s.steps);
    steps.flatMap((s) => [...(s.events || []), ...(s.answer?.events || [])]).forEach((event) => {
      expect(sorted(Object.keys(event))).toEqual(sorted(EVENT_KEYS));
      person(event.actor);
      expect(event.op).not.toHaveProperty("client_op_id");
      expect(event.op).not.toHaveProperty("summary");
    });
    steps.filter((s) => s.do === "batchFailed" && s.failure.finished_by).forEach((s) => person(s.failure.finished_by));
    // `taken` comes on exactly the three refusals that carry it, always, but in the scenario of a server from before it.
    const carries = (failure) => failure.status === 423 || failure.status === 413 || (failure.status === 403 && failure.code === "pack_read_only");
    scenarios.forEach(({ name, steps: played }) => played.filter((s) => s.do === "batchFailed").forEach(({ failure }) => {
      expect([name, "taken" in failure]).toEqual([name, carries(failure) && name !== OLDER_SERVER]);
      (failure.taken || []).forEach((r) => expect(sorted(Object.keys(r))).toEqual(sorted(RESULT_KEYS)));
    }));
    steps.filter((s) => s.do === "batchAnswered").forEach(({ answer: body }) => {
      expect(sorted(Object.keys(body))).toEqual(sorted(["head_seq", "results", "events", "has_more"]));
      body.results.forEach((r) => expect(sorted(Object.keys(r))).toEqual(sorted(RESULT_KEYS)));
    });
  });

  it("changes nothing it was given", () => {
    const before = JSON.stringify(SCENARIOS);
    sessionFixture();
    expect(JSON.stringify(SCENARIOS)).toBe(before);
  });

  it("refuses the clock and randomness while the fixture is built", () => {
    expect(() => withoutClockOrRandomness(() => Date.now())).toThrow(/the clock/);
    expect(() => withoutClockOrRandomness(() => new Date())).toThrow(/the clock/);
    expect(() => withoutClockOrRandomness(() => Math.random())).toThrow(/randomness/);
    expect(() => withoutClockOrRandomness(() => window.crypto.randomUUID())).toThrow(/a random id/);
    expect(new Date(0).toISOString()).toBe("1970-01-01T00:00:00.000Z");
  });
});
