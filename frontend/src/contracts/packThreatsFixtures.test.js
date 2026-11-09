import fs from "fs";
import path from "path";
import { describe, expect, it } from "vitest";
import { createSummary, newItemName } from "../feature/missionPacks/packSentences";
import { maskSubject } from "../feature/threats/threatSets";
import { describeThreatChange, newThreatSetOp, sharedThreat, sharedThreatData, threatSetDoc, threatSetFromItem } from "../feature/missionPacks/packThreats";

/*
 * What a set of threats is in a mission pack: what of it is each person's own, how an item's data is read and written
 * back, and the history's sentences. Written here; the native apps are held to it.
 */

const file = path.resolve(__dirname, "../../../contracts/fixtures/packs/threats.json");

const RADAR = { type: 1, rangeNmi: 25, antennaHeightFt: 30, aglNotMsl: true, showMask: true, showRangeRings: true, bands: [{ altFt: 300, color: 1, alpha: 128 }] };
const THREAT = (id, extra = {}) => ({ id, name: `T-${id}`, lat: 34.5, lon: -84.1, milstdId: "SHAPMFG--------", source: "", information: "", showThreat: true, radars: [RADAR], ...extra });
const MASK = "data:image/png;base64,AAAA";
const maskFor = (threat, image = MASK) => ({ bounds: [[34, -85], [35, -84]], images: [image], for: maskSubject(threat) });

const reads = [
  { name: "a set as a Library copy brought it", data: { version: 1, threats: [THREAT("a"), THREAT("b")] } },
  { name: "what this person hid stays hidden", data: { version: 1, threats: [THREAT("a"), THREAT("b")] }, previous: { threats: [{ ...THREAT("a"), visible: false, mask: null, maskLoading: false, maskError: null }] } },
  { name: "an item's own visible is nobody's", data: { version: 1, threats: [THREAT("a", { visible: false })] } },
  { name: "a field a newer version wrote stays", data: { version: 2, note: "kept", threats: [THREAT("a", { extra: { nested: [1, null] } })] } },
  { name: "no version is the first", data: { threats: [THREAT("a")] } },
  { name: "no list of threats reads as none", data: { version: 1 } },
  { name: "data that is not an object reads as none", data: [1, 2] },
  { name: "a threat with no position is kept and not drawn", data: { version: 1, threats: [THREAT("a"), { id: "b", name: "ODD", radars: [] }] } },
  { name: "a threat with no id is kept and not drawn", data: { version: 1, threats: [{ ...THREAT("x"), id: undefined }] } },
  { name: "a threat whose id is true is kept and not drawn", data: { version: 1, threats: [THREAT(true)] } },
  { name: "an id the set has already is kept and not drawn", data: { version: 1, threats: [THREAT("a"), THREAT("a", { name: "SECOND" })] } },
  { name: "a number is an id", data: { version: 1, threats: [THREAT(7)] } },
  { name: "something that is not a threat is kept as it is", data: { version: 1, threats: [THREAT("a"), 7, null] } },
];

const masks = [
  { name: "a mask that came with a copy is drawn while it fits", threat: THREAT("a", { mask: maskFor(THREAT("a")) }), previous: null, drawn: "item" },
  { name: "one that does not fit is not", threat: THREAT("a", { lat: 35, mask: maskFor(THREAT("a")) }), previous: null, drawn: null },
  { name: "the person's own is kept while it fits", threat: THREAT("a"), previous: THREAT("a", { mask: maskFor(THREAT("a"), "data:mine") }), drawn: "previous" },
  { name: "the item's comes first", threat: THREAT("a", { mask: maskFor(THREAT("a")) }), previous: THREAT("a", { mask: maskFor(THREAT("a"), "data:mine") }), drawn: "item" },
  { name: "the person's own is dropped once the threat has moved", threat: THREAT("a", { lat: 35 }), previous: THREAT("a", { mask: maskFor(THREAT("a"), "data:mine") }), drawn: null },
  { name: "a mask being worked out goes on for the same place", threat: THREAT("a"), previous: THREAT("a", { maskLoading: true, maskError: "no" }), drawn: null, loading: true, error: "no" },
  { name: "and stops for another", threat: THREAT("a", { lon: -85 }), previous: THREAT("a", { maskLoading: true, maskError: "no" }), drawn: null, loading: false, error: null },
];

const changes = [
  { name: "a threat added", before: { threats: [THREAT("a")] }, after: { threats: [THREAT("a"), THREAT("b")] } },
  { name: "a threat with no name added", before: { threats: [] }, after: { threats: [THREAT("a", { name: "" })] } },
  { name: "a threat moved north alone", before: { threats: [THREAT("a")] }, after: { threats: [THREAT("a", { lat: 35 })] } },
  { name: "a threat moved east alone", before: { threats: [THREAT("a")] }, after: { threats: [THREAT("a", { lon: -85 })] } },
  { name: "a threat changed", before: { threats: [THREAT("a")] }, after: { threats: [THREAT("a", { information: "NEW" })] } },
  { name: "a threat removed", before: { threats: [THREAT("a")] }, after: { threats: [] } },
  { name: "the first of several says it", before: { threats: [THREAT("a"), THREAT("b")] }, after: { threats: [THREAT("c"), THREAT("a", { lat: 35 })] } },
  { name: "no change that can be named", before: { threats: [THREAT("a")] }, after: { threats: [THREAT("a")] } },
  { name: "no threats on either side", before: null, after: null },
  { name: "a set with no name", before: { threats: [THREAT("a")] }, after: { threats: [] }, setName: "" },
  { name: "nobody's name", before: { threats: [THREAT("a")] }, after: { threats: [] }, actor: "" },
];

const names = [
  { name: "", count: 0 },
  { name: "", count: 2 },
  { name: "  north ga  ", count: 2 },
];

const sentences = [
  { actor: "Colin M.", name: "AIR DEFENCE", count: 1 },
  { actor: "Colin M.", name: "AIR DEFENCE", count: 1234 },
  { actor: "", name: "THREATS", count: 0 },
];

const ownFields = [
  { name: "what is each person's own goes", value: THREAT("a", { visible: false, mask: maskFor(THREAT("a")), maskLoading: true, maskError: "no" }) },
  { name: "something that is not a threat stays", value: 7 },
  { name: "null stays", value: null },
  { name: "a list stays", value: [1] },
];

const sharedDocs = [
  { name: "the threats are stripped, the rest stays", value: { version: 1, note: "kept", threats: [THREAT("a", { visible: false, mask: null })] } },
  { name: "no list is not given one", value: { version: 1 } },
  { name: "not an object stays", value: [1] },
];

const out = () => ({
  sharedThreat: ownFields.map((c) => ({ ...c, expected: sharedThreat(c.value) })),
  sharedData: sharedDocs.map((c) => ({ ...c, expected: sharedThreatData(c.value) })),
  read: reads.map((c) => {
    const read = threatSetFromItem(c.data, c.previous);
    return { ...c, expected: read, doc: threatSetDoc(read) };
  }),
  masks: masks.map((c) => {
    const read = threatSetFromItem({ version: 1, threats: [c.threat] }, c.previous ? { threats: [c.previous] } : undefined).threats[0];
    const want = c.drawn === "item" ? c.threat.mask : c.drawn === "previous" ? c.previous.mask : null;
    expect(read.mask).toEqual(want);
    return { ...c, expected: { mask: read.mask, maskLoading: read.maskLoading, maskError: read.maskError } };
  }),
  describe: changes.map((c) => ({ ...c, expected: describeThreatChange(c.before, c.after, { name: c.setName ?? "AIR DEFENCE", actor: c.actor ?? "Colin M." }) })),
  newItemName: names.map((c) => ({ ...c, expected: newItemName("threatset", c.name, c.count) })),
  createSummary: sentences.map((c) => ({ ...c, expected: createSummary(c.actor, "threatset", c.name, c.count) })),
  newOp: [
    { name: "a new set is named, and carries what everyone shares", input: { name: "", count: 1, threats: [THREAT("a", { visible: false, mask: maskFor(THREAT("a")), maskLoading: false, maskError: null })], actor: "Colin M." } },
  ].map((c) => ({ ...c, expected: newThreatSetOp({ ...c.input, newId: () => "fixed" }) })),
});

describe("packs/threats.json", () => {
  it("holds what a set of threats is in a pack", () => {
    const made = JSON.parse(JSON.stringify(out()));
    if (process.env.UPDATE_CONTRACTS) fs.writeFileSync(file, `${JSON.stringify(made, null, 2)}\n`);
    expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(made);
  });
});
