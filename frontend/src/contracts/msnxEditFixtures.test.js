import JSZip from "jszip";
import {
  applyPlanToMsnxDocs,
  buildMsnxBlob,
  findNearestAdjacentIndex,
  insertPointOnRoute,
  updatePointCoordinate,
  updatePointName,
} from "../feature/msnxImport/mutateMsnx";
import { parseMsnxFile } from "../feature/msnxImport/parseMsnx";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What the web does to an imported AMPS mission when a person edits it: drag a point, rename it, insert a point on
// a leg, change the plan, and write the result back into the file it came from (mutateMsnx.js). Each case starts from
// the committed `template.msnx` and records two things the native apps are held to:
//
//   current     the routes as the web's state holds them *after* the edits (the points moved, renamed or added, the plan changed). A native
//               app given exactly this, and the file it started from, must write the same file.
//   afterParse  what the web's reader finds in the file it wrote.
//
// The files are compared as parsed documents, not bytes: two serializers need not agree on how to write the same document.
// Point and leg ids the web makes up are fixed (the counter below), so the files can be reproduced.

jest.setTimeout(180000);

const TEMPLATE = fs.readFileSync(fixturePath("msnx/template.msnx"));

const withDeterministicIds = async (build) => {
  let counter = 0;
  const hadCrypto = Object.prototype.hasOwnProperty.call(window, "crypto");
  const original = window.crypto;
  Object.defineProperty(window, "crypto", {
    configurable: true,
    value: { randomUUID: () => `00000000-0000-4000-8000-${String((counter += 1)).padStart(12, "0")}` },
  });
  try {
    return await build();
  } finally {
    if (hadCrypto) Object.defineProperty(window, "crypto", { configurable: true, value: original });
    else delete window.crypto;
  }
};

const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (v === undefined ? null : v)));
const shape = ({ routes }) => clean({
  routes: routes.map(({ name, segmentId, points, plan, elevations }) => ({ name, segmentId, points, plan, elevations })),
});

// Edits are written as the web's own handlers apply them (useMsnxImport.js): the XML is changed first, then the route state.
const MOVE = [
  { op: "move", index: 0, lat: 34.665, lon: -84.13 },        // the first named point
  { op: "move", index: 5, lat: 34.689, lon: -84.1305 },      // a serpentine point: its leg's trackpoint is moved with it
];
const RENAME = [{ op: "rename", index: 26, name: ".LZ DOG" }];
const INSERT = [{ op: "insert", near: { lat: 34.6779, lon: -84.1332 } }];
const PLAN = [{
  op: "plan",
  index: 26,
  override: { airspeed: { value: 111, type: "ground" }, altitude: { value: 900, ref: "msl" }, wind: { dirTrue: 200, speedKts: 15 } },
}];

// After an insert the last named point is one place further on.
const PLAN_AFTER_INSERT = [{ ...PLAN[0], index: 27 }];

const CASES = [
  { name: "edit-move", description: "A named point and a serpentine point dragged (updatePointCoordinate).", ops: MOVE },
  { name: "edit-rename", description: "A named point renamed (updatePointName).", ops: RENAME },
  { name: "edit-insert", description: "A point inserted on the leg nearest a click (insertPointOnRoute), which splits the leg.", ops: INSERT },
  { name: "edit-plan", description: "One arrival point's plan edited and written back (applyPlanToMsnxDocs).", ops: PLAN },
  { name: "edit-all", description: "A move, a rename, an insert and a plan edit together, in that order.", ops: [...MOVE, ...RENAME, ...INSERT, ...PLAN_AFTER_INSERT] },
];

const run = (c) => withDeterministicIds(async () => {
  const { zip, docs, routes } = await parseMsnxFile(TEMPLATE);
  const route = routes[0];
  const recorded = [];
  for (const op of c.ops) {
    if (op.op === "move") {
      const point = route.points[op.index];
      updatePointCoordinate(docs, point.id, op.lat, op.lon);
      route.points[op.index] = { ...point, lat: op.lat, lon: op.lon };
    } else if (op.op === "rename") {
      const point = route.points[op.index];
      updatePointName(docs, point.id, op.name);
      route.points[op.index] = { ...point, name: op.name };
    } else if (op.op === "insert") {
      const afterIndex = findNearestAdjacentIndex(route, op.near.lat, op.near.lon);
      const { afterPointId, point } = insertPointOnRoute(docs, route, op.near.lat, op.near.lon);
      const at = route.points.findIndex((p) => p.id === afterPointId);
      route.points.splice(at + 1, 0, point);
      recorded.push({ afterIndex, afterPointId, newPointId: point.id });
    } else if (op.op === "plan") {
      const point = route.points[op.index];
      route.plan.perPoint[point.id] = { ...route.plan.perPoint[point.id], ...op.override };
    }
  }
  // The web writes every route's plan on every export. The native apps write it only where it changed (rounding it back into a file that was only
  // opened would round every altitude in it), so a case that edits neither the plan nor the points leaves the plan alone.
  if (c.ops.some((op) => op.op === "plan" || op.op === "insert")) applyPlanToMsnxDocs(docs, route);
  await buildMsnxBlob(zip, docs);
  const bytes = await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE", compressionOptions: { level: 9 } });
  // Cut to the four parts the editor changes, as the other fixtures are cut down.
  const source = await JSZip.loadAsync(bytes);
  const reduced = new JSZip();
  const date = new Date(Date.UTC(2026, 9, 2));
  for (const name of ["mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml", "mission/vehicles.xml"]) {
    reduced.file(name, await source.file(name).async("uint8array"), { date });
  }
  const out = await reduced.generateAsync({ type: "nodebuffer", compression: "DEFLATE", compressionOptions: { level: 9 } });
  return { bytes: out, current: shape({ routes: [route] }), inserts: recorded };
});

const document = async (c) => {
  const { bytes, current, inserts } = await run(c);
  return {
    bytes,
    entry: {
      name: c.name,
      file: `edits/${c.name}.msnx`,
      description: c.description,
      ops: c.ops,
      // The ids the web made up while editing, in the order it asked for them.
      inserts,
      current: current.routes,
      afterParse: shape(await parseMsnxFile(bytes)).routes,
    },
  };
};

describe("msnx edit fixtures (the web's mutateMsnx)", () => {
  if (UPDATE) {
    it("writes the files and the answers", async () => {
      const cases = [];
      for (const c of CASES) {
        const { bytes, entry } = await document(c);
        writeFixture(`msnx/edits/${c.name}.msnx`, bytes);
        cases.push(entry);
      }
      writeFixture("msnx/edits.json", dumps({
        description: "Edits the web makes to an imported mission and writes back into its file (mutateMsnx.js), from template.msnx. "
          + "`current` is the route state after the edits; `afterParse` is what the web's reader finds in the file it wrote.",
        generatedBy: "frontend/src/contracts/msnxEditFixtures.test.js (UPDATE_CONTRACTS=1)",
        tolerance: 1e-9,
        cases,
      }));
    });
    return;
  }

  const recorded = JSON.parse(fs.readFileSync(fixturePath("msnx/edits.json"), "utf8"));

  it("has every case, no more and no fewer", () => {
    expect(recorded.cases.map((c) => c.name)).toEqual(CASES.map((c) => c.name));
  });

  describe.each(CASES.map((c) => [c.name, c]))("%s", (_name, c) => {
    const expected = recorded.cases.find((r) => r.name === c.name);

    it("the committed file still reads as the recorded answer", async () => {
      const bytes = fs.readFileSync(fixturePath(`msnx/${expected.file}`));
      expect(shape(await parseMsnxFile(bytes)).routes).toEqual(expected.afterParse);
    });

    it("making the edits again with today's web code gives the same state and the same answer", async () => {
      const { entry } = await document(c);
      expect(entry.current).toEqual(expected.current);
      expect(entry.afterParse).toEqual(expected.afterParse);
      expect(entry.inserts).toEqual(expected.inserts);
    });
  });

  it("covers what the editor does", () => {
    const byName = Object.fromEntries(recorded.cases.map((c) => [c.name, c]));
    expect(byName["edit-insert"].inserts).toHaveLength(1);
    const after = byName["edit-insert"].afterParse[0].points;
    expect(after.length).toBe(byName["edit-insert"].current[0].points.length);
    expect(after.some((p) => p.name === ".NEWPT")).toBe(true);
    expect(byName["edit-rename"].afterParse[0].points[26].name).toBe(".LZ DOG");
    expect(byName["edit-move"].afterParse[0].points[0].lat).toBe(34.665);
  });
});
