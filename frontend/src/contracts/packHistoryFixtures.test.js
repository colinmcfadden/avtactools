import { flightDataFromDoghouses } from "../feature/doghouses/doghouseFields";
import { createDefaultDoghouses } from "../feature/doghouses/useDoghouses";
import {
  LZ_WORKSPACE_ACTIONS,
  createInitialLzWorkspace,
  createLzDiagramFromTarget,
  lzWorkspaceReducer,
} from "../feature/lzWorkspace/useLzWorkspace";
import { composeEdit } from "../feature/missionPacks/packEdit";
import { historyLines } from "../feature/missionPacks/packHistory";
import { describeLzChange, lzDiagramFromItem, lzItemData, sharedLzData } from "../feature/missionPacks/packLz";
import { packLocalId } from "../feature/missionPacks/packRef";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// How a pack's history reads its log: one line for each change, however many operations it went to the pack as (packHistory.js). The
// changes here are made by the web's own code (the workspace's reducer, the flight data following the doghouses as useDoghouses has it,
// composeEdit as usePackItemSync sends them) and logged as the server logs them (pack_routes.apply_ops: one event per operation, each with
// the sentence it carried); the rest are the edges of the rule, by hand.

const GENERATED_BY = "frontend/src/contracts/packHistoryFixtures.test.js (UPDATE_CONTRACTS=1)";

const settle = (name, document) => {
  const file = fixturePath(name);
  const text = dumps(document);
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

const PACK = "6f1c2a9e-3b7d-4c55-9a10-2d8e5f7b4c31";
const LZ = "b7e3c1a4-2d5f-4e6a-9b8c-0d1e2f3a4b5c";
const OTHER_LZ = "d9a5e3c6-4f7b-4a8c-9d0e-2f3a4b5c6d7e";
const COLIN = { id: 1, name: "Colin McFadden" };
const SAM = { id: 2, name: "Sam Bell" };
const RING = [[34.7841, -84.0826], [34.7843, -84.0817], [34.7835, -84.0815], [34.7833, -84.0824]];

// -- Changes, made as the web makes them ----------------------------------------------------------------------------------------------

const reduce = (diagram, action) => {
  const state = createInitialLzWorkspace([diagram]);
  return lzWorkspaceReducer(state, { ...action, diagramId: diagram.id }).diagramsById[diagram.id];
};

// The flight data following the doghouses (useDoghouses's effect), as App.js sets it.
const following = (diagram) =>
  reduce(diagram, { type: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA, flightData: flightDataFromDoghouses(diagram.graphics.doghouses, diagram.flightData) });

const targeted = () =>
  createLzDiagramFromTarget({ target: [34.783817, -84.08219], mgrs: "16S GD 66993 52949", id: packLocalId(PACK, LZ), name: "LZ HAWK" });

// App.js's handleAnalysisComplete: what was found, the standard doghouses made once, and the flight data they give.
const analysed = (diagram) => {
  const done = reduce(diagram, {
    type: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS,
    analysis: { detectedLZ: RING, gridElevation: "4050", latLong: "34°47'01.7\"N 84°04'55.9\"W", results: { elevation: "4050", status: "success" } },
  });
  const made = reduce(done, {
    type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
    collection: "doghouses",
    items: createDefaultDoghouses([diagram.target.lat, diagram.target.lon], diagram.id),
  });
  return following(made);
};

// A doghouse turned to [heading] (Doghouse.jsx's rotate, through updateDoghouse), the flight data following it.
const turned = (diagram, role, heading) => {
  const items = diagram.graphics.doghouses.map((dh) => (dh.role === role ? { ...dh, heading } : dh));
  return following(reduce(diagram, { type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION, collection: "doghouses", items }));
};

const renamed = (diagram, name) => reduce(diagram, { type: LZ_WORKSPACE_ACTIONS.SET_DIAGRAM_NAME, name });

/** What usePackItemSync sends for the change from [before] to [after] (diagrams), made by [who] (the sentence's short name). */
const sent = (before, after, who) => {
  const doc = lzItemData(before);
  const item = { uuid: LZ, kind: "lz", name: before.name, data: doc };
  const edit = composeEdit({
    uuid: LZ,
    base: { doc, name: before.name },
    mine: { doc: lzItemData(after), name: after.name },
    item,
    shared: sharedLzData,
    currentShape: (it) => lzItemData(lzDiagramFromItem(PACK, it)),
    describe: describeLzChange,
    actor: who,
  });
  return edit.ops;
};

// -- The log, as the server writes it ------------------------------------------------------------------------------------------------

/** A log: [batches] numbered from 1 as the server numbers them, one event per operation. */
const log = (...batches) => {
  let seq = 0;
  return batches.flatMap(({ ops, actor, skipped = {}, gapBefore = 0 }) => {
    seq += gapBefore;
    return ops.map((op, i) => {
      seq += 1;
      const { client_op_id: _sentId, summary, ...payload } = op; // eslint-disable-line no-unused-vars
      const reason = skipped[i] ?? null;
      return {
        seq,
        type: payload.type,
        item: payload.item ?? null,
        actor,
        summary,
        status: reason ? "skipped" : "applied",
        reason,
        client_op_id: `c-${seq}`,
        op: payload,
        created_at: `2026-10-13T14:00:${String(seq).padStart(2, "0")}`,
      };
    });
  });
};

const batch = (ops, actor, extra = {}) => ({ ops, actor, ...extra });

/** Hand-made operations with one sentence, for the edges of the rule. */
const patches = (count, item, summary, path = ["flightData"]) =>
  Array.from({ length: count }, () => ({ type: "patch", item, path, value: {}, summary }));

const cases = () => {
  const hawk = analysed(targeted());
  const turned272 = turned(hawk, "landing", "272°");
  const turned273 = turned(turned272, "landing", "273°");
  const turned274 = turned(turned273, "landing", "274°");
  const takeoffTurned = turned(turned272, "takeoff", "095°");
  const turnedAgain = turned(takeoffTurned, "landing", "300°");
  const threeTurns = log(
    batch(sent(hawk, turned272, "Colin M."), COLIN),
    batch(sent(turned272, turned273, "Colin M."), COLIN),
    batch(sent(turned273, turned274, "Colin M."), COLIN),
  );
  return [
    {
      name: "a doghouse turned: the landing heading it gives and its own heading, one change sent as two operations, is one line",
      events: log(batch(sent(hawk, turned272, "Colin M."), COLIN)),
    },
    {
      name: "the first analysis: what it found, the standard doghouses and the flight data they give, five operations, is one line",
      events: log(batch(sent(targeted(), hawk, "Colin M."), COLIN)),
    },
    {
      name: "a change sent with a rename: the rename carries the change's sentence, and they are one line",
      events: log(batch(sent(hawk, renamed(turned272, "LZ EAGLE"), "Colin M."), COLIN)),
    },
    {
      name: "a doghouse turned three times a moment apart: three changes with one sentence, one after another, are one line",
      events: threeTurns,
    },
    {
      name: "where new starts between them: a line each side of it, never what is new folded into what was seen",
      since: 2,
      events: threeTurns,
    },
    {
      name: "someone else's change between two of one person's: a line each",
      events: log(
        batch(sent(hawk, turned272, "Colin M."), COLIN),
        batch(sent(turned272, takeoffTurned, "Sam B."), SAM),
        batch(sent(takeoffTurned, turnedAgain, "Colin M."), COLIN),
      ),
    },
    {
      name: "part of a change taken and part skipped (what it turned was removed meanwhile): a line for each outcome",
      events: log(batch(sent(hawk, turned272, "Colin M."), COLIN, { skipped: { 1: "target_missing" } })),
    },
    {
      name: "skipped the same way is one line, and another reason is a line of its own",
      events: log(batch(patches(3, LZ, "Sam B. moved Chalk 1 on LZ HAWK."), SAM, { skipped: { 0: "target_missing", 1: "target_missing", 2: "not_an_object" } })),
    },
    {
      name: "a number missing from the log as held: a line each side of it",
      events: log(batch(patches(2, LZ, "Sam B. moved Chalk 1 on LZ HAWK."), SAM), batch(patches(1, LZ, "Sam B. moved Chalk 1 on LZ HAWK."), SAM, { gapBefore: 1 })),
    },
    {
      name: "one sentence on two items (two LZ/PZs of one name): a line each",
      events: log(batch(patches(1, LZ, "Sam B. moved Chalk 1 on LZ/PZ 1."), SAM), batch(patches(1, OTHER_LZ, "Sam B. moved Chalk 1 on LZ/PZ 1."), SAM)),
    },
    {
      name: "one person under another name (their account renamed meanwhile): a line each, as the server counts them",
      events: log(batch(patches(1, LZ, "Sam B. moved Chalk 1 on LZ HAWK."), SAM), batch(patches(1, LZ, "Sam B. moved Chalk 1 on LZ HAWK."), { id: 2, name: "Samuel Bell" })),
    },
    {
      name: "the pack's own events, with no item: one sentence one after another is one line, two sentences two",
      events: log(
        batch([{ type: "pack.update", description: "Night LZs.", summary: "Colin McFadden changed the pack's description." }], COLIN),
        batch([{ type: "pack.update", description: "Night LZs only.", summary: "Colin McFadden changed the pack's description." }], COLIN),
        batch([{ type: "member.add", user_id: 2, name: "Sam Bell", role: "editor", summary: "Colin McFadden added Sam Bell." }], COLIN),
        batch([{ type: "member.add", user_id: 3, name: "Jess Reyes", role: "viewer", summary: "Colin McFadden added Jess Reyes." }], COLIN),
      ),
    },
    { name: "nothing logged: no lines", events: [] },
  ].map(({ name, since = null, events }) => ({ name, since, events, lines: historyLines(events, since).map((line) => line.map((e) => e.seq)) }));
};

test("packs/history.json: a pack's log read as the history's lines", () => {
  const all = cases();
  // The rule's own checks, beside the file: every event on exactly one line, in order, and the web's doghouse turn two events.
  all.forEach(({ events, lines }) => expect(lines.flat()).toEqual(events.map((e) => e.seq)));
  expect(all[0].events.map((e) => e.op.path)).toEqual([["flightData"], ["graphics", "doghouses", { id: expect.stringMatching(/-rp1$/) }]]);
  expect(all[0].lines).toEqual([[1, 2]]);
  settle("packs/history.json", {
    description:
      "A mission pack's log read as its history's lines (frontend/src/feature/missionPacks/packHistory.js, historyLines). One change goes to " +
      "the pack as one operation or several, each with the change's sentence, and the server logs every operation as an event " +
      "(backend/routes/pack_routes.py, apply_ops): a doghouse turned is the landing or takeoff heading it gives and its own heading, an " +
      "analysis five operations. Events that follow one another in the log (seq one more than the last), by the same person (actor id and " +
      "name), on the same item, with the same sentence, status and reason, are one line, as the server counts a copy's changes " +
      "(pack_support.pack_changes); a line never holds events on both sides of `since` (where \"new since you looked\" starts; null: " +
      "nowhere). `events` are the log as GET /api/packs/<uuid>/events gives it, oldest first; `lines` the lines, oldest first, each the " +
      "seqs of its events. The first cases are made by the web's own code (the workspace's reducer, the flight data following the " +
      "doghouses, composeEdit) and logged as the server logs them; the rest are the rule's edges, by hand.",
    generatedBy: GENERATED_BY,
    cases: all,
  });
});
