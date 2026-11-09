import { vi } from "vitest";
import {
  documentOf, maskStillFits, maskSubject, readDocument, sameApartFromMasks, setNameFromFile,
} from "../feature/threats/threatSets";

// A set of threats as the Library keeps it: what the web makes of a saved document and what it writes back, so the
// apps read and write the same one. A threat given a new id (none, or one already taken) reads "<new>" here, because
// the web's are made from the clock and a fixture cannot depend on it.
vi.mock("../feature/threats/threatModel", async (importOriginal) => ({ ...(await importOriginal()), newThreatId: () => "<new>" }));

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

const NAME = "threats/sets.json";
const GENERATED_BY = "frontend/src/contracts/threatSetFixtures.test.js (UPDATE_CONTRACTS=1)";

const band = (altFt, color, alpha, colorIndex, viewable = true) => ({ altFt, color, alpha, colorIndex, viewable });
const radar = (type, over = {}) => ({
  type, rangeNmi: type === 0 ? 25 : 15.5, antennaHeightFt: 20, aglNotMsl: true, showMask: true, showRangeRings: true,
  bands: [band(50, "#fbbf24", 0.32, 1), band(250, "#f97316", 0.28, 3), band(500, "#facc15", 0.24, 5, false)],
  ...over,
});
const threat = (over = {}) => ({
  id: "t-1", name: "SA-6", milstdId: "SHAPMFS-------", lat: 34.5, lon: -84.25, information: "2 launchers", source: "SOF",
  showThreat: true, color: "#ef4444", visible: true, radars: [radar(0), radar(1)], ...over,
});
const maskOf = (t, over = {}) => ({
  bounds: [[34, -85], [35, -84]], radars: [{ type: 0, png: "data:image/png;base64,AAAA" }], for: maskSubject(t), ...over,
});

// Keys turned round: the API answers with its keys sorted, so a mask read back need not match the threat's order.
const reversed = (value) => {
  if (Array.isArray(value)) return value.map(reversed);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).reverse().map((key) => [key, reversed(value[key])]));
  return value;
};

const DOCUMENTS = [
  ["one threat as the web saves it", { version: 1, threats: [threat()] }],
  ["no threats", { version: 1, threats: [] }],
  ["several in order", { version: 1, threats: [threat({ id: "a" }), threat({ id: "b", name: "SA-2" }), threat({ id: "c", name: "ZSU" })] }],
  ["a mask that fits is kept", { version: 1, threats: [threat({ mask: maskOf(threat()) })] }],
  ["a mask with its keys in another order still fits", { version: 1, threats: [threat({ mask: maskOf(threat(), { for: reversed(maskSubject(threat())) }) })] }],
  ["a mask for another place is dropped", { version: 1, threats: [threat({ mask: maskOf(threat({ lat: 34.6 })) })] }],
  ["a mask for other radars is dropped", { version: 1, threats: [threat({ mask: maskOf(threat({ radars: [radar(0)] })) })] }],
  ["a mask with nothing to say what it is for is dropped", { version: 1, threats: [threat({ mask: { bounds: [[34, -85], [35, -84]], radars: [] } })] }],
  ["a null mask", { version: 1, threats: [threat({ mask: null })] }],
  ["a hidden threat stays hidden", { version: 1, threats: [threat({ visible: false })] }],
  ["no visible field is shown", { version: 1, threats: [(({ visible, ...rest }) => rest)(threat())] }],
  ["a null visible is shown", { version: 1, threats: [threat({ visible: null })] }],
  ["what was being worked out is not saved", { version: 1, threats: [threat({ maskLoading: true, maskError: "It failed" })] }],
  ["a field a newer release wrote on a threat survives", { version: 1, threats: [threat({ priority: { rank: 2 } })] }],
  ["a field a newer release wrote on a radar survives", { version: 1, threats: [threat({ radars: [radar(0, { sector: [10, 90] })] })] }],
  ["a field a newer release wrote on the document survives", { version: 1, owner: "ops", threats: [threat()] }],
  ["a later version is kept", { version: 2, threats: [threat()] }],
  ["no version is the first", { threats: [threat()] }],
  ["no list of threats is no threats", { version: 1 }],
  ["a list that is not one is no threats", { version: 1, threats: { a: 1 } }],
  ["no position cannot be read", { version: 1, threats: [(({ lat, ...rest }) => rest)(threat()), threat({ id: "b" })] }],
  ["a position in words cannot be read", { version: 1, threats: [threat({ lat: "34.5" }), threat({ id: "b" })] }],
  ["no list of radars cannot be read", { version: 1, threats: [threat({ radars: null }), threat({ id: "b" })] }],
  ["a radar with no bands cannot be read", { version: 1, threats: [threat({ radars: [{ type: 0 }] }), threat({ id: "b" })] }],
  ["what is not a threat cannot be read, and goes after the rest", { version: 1, threats: ["text", null, 4, threat({ id: "b" })] }],
  ["no id is given one", { version: 1, threats: [(({ id, ...rest }) => rest)(threat())] }],
  ["an empty id is given one", { version: 1, threats: [threat({ id: "" })] }],
  ["an id used twice in the document is given a new one the second time", { version: 1, threats: [threat({ id: "a" }), threat({ id: "a", name: "SA-2" })] }],
  ["an id another open set holds is given a new one", { version: 1, threats: [threat({ id: "a" }), threat({ id: "b" })] }, ["a"]],
];

const fits = (name, subject, mask) => ({ name, subject, mask, fits: maskStillFits(subject, mask) });

const NAMES = [
  "north ga.ths", "NORTH GA.THS", "Mixed.Ths", "two.ths.ths", "no extension", ".ths", "   .ths", "  padded  .ths", "tab\there.ths", "straße.ths",
  "a".repeat(120), `${"a".repeat(99)}\u{1F600}b.ths`, "", " space .ths",
];

const moved = threat({ lat: 34.6 });

test("a set of threats as the Library keeps it", () => {
  const document = {
    description: "A set of threats as the Library keeps it: what is made of a saved document, and what is written back. An id of \"<new>\" is one the reader chose.",
    generatedBy: GENERATED_BY,
    documents: DOCUMENTS.map(([name, data, usedIds = []]) => {
      const read = readDocument(data, new Set(usedIds));
      return {
        name, data, usedIds, shown: read.threats.map((t) => ({ id: t.id, visible: t.visible, masked: t.mask != null })),
        unreadable: read.unreadable.length, written: JSON.parse(JSON.stringify(documentOf(read))),
      };
    }),
    subjects: [threat(), threat({ radars: [] }), threat({ lat: -0.5, lon: 179.999, radars: [radar(1, { bands: [] })] })].map((t) => ({ threat: t, subject: maskSubject(t) })),
    masks: [
      fits("the mask of this threat", threat(), maskOf(threat())),
      fits("keys in another order", threat(), maskOf(threat(), { for: reversed(maskSubject(threat())) })),
      fits("moved since", moved, maskOf(threat())),
      fits("a radar changed since", threat({ radars: [radar(0), radar(1, { rangeNmi: 16 })] }), maskOf(threat())),
      fits("a band's colour changed since", threat({ radars: [radar(0, { bands: [band(50, "#000000", 0.32, 1)] }), radar(1)] }), maskOf(threat())),
      fits("the name changed since", threat({ name: "OTHER" }), maskOf(threat())),
      fits("shown or not changed since", threat({ visible: false }), maskOf(threat())),
      fits("nothing to say what it is for", threat(), { bounds: [], radars: [] }),
      fits("no mask", threat(), null),
    ],
    sameApartFromMasks: [
      ["the same", { version: 1, threats: [threat()] }, { version: 1, threats: [threat()] }],
      ["one has a mask", { version: 1, threats: [threat({ mask: maskOf(threat()) })] }, { version: 1, threats: [threat()] }],
      ["their masks differ", { version: 1, threats: [threat({ mask: { a: 1 } })] }, { version: 1, threats: [threat({ mask: { a: 2 } })] }],
      ["keys in another order", { version: 1, threats: [threat()] }, { threats: [reversed(threat())], version: 1 }],
      ["a threat moved", { version: 1, threats: [threat()] }, { version: 1, threats: [moved] }],
      ["a threat more", { version: 1, threats: [threat()] }, { version: 1, threats: [threat(), threat({ id: "b" })] }],
      ["what is not a threat", { version: 1, threats: ["text"] }, { version: 1, threats: ["text"] }],
    ].map(([name, a, b]) => ({ name, a, b, same: sameApartFromMasks(a, b) })),
    names: NAMES.map((file) => ({ file, name: setNameFromFile(file) })),
  };
  const file = fixturePath(NAME);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(NAME, text);
    return;
  }
  if (!fs.existsSync(file)) throw new Error(`${NAME} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  expect(fs.readFileSync(file, "utf8")).toBe(text);
});
