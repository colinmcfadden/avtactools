import { conflictName, documentOf, MASK_BUDGET, maskStillFits, maskSubject, readDocument, sameApartFromMasks, setNameFromFile } from "./threatSets";
import { makeThreat } from "./threatModel";

const threat = (over = {}) => makeThreat(34.5, -84.1, { name: "SA-11", ...over });
const maskFor = (t, png = "data:image/png;base64,AAAA") => ({ bounds: [[34, -85], [35, -84]], radars: [{ type: 0, png }], for: maskSubject(t) });
const set = (threats, over = {}) => ({ threats, unreadable: [], extras: {}, ...over });

describe("a saved document", () => {
  it("keeps a threat and its mask, and never what is only under way", () => {
    const t = threat();
    const [saved] = documentOf(set([{ ...t, mask: maskFor(t), maskLoading: true, maskError: "x" }])).threats;
    expect(saved.mask.radars).toHaveLength(1);
    expect(saved).not.toHaveProperty("maskLoading");
    expect(saved).not.toHaveProperty("maskError");
    expect(saved.id).toBe(t.id);
  });

  it("leaves out a mask made for somewhere the threat no longer is", () => {
    const t = threat();
    const moved = { ...t, lat: 35, mask: maskFor(t) };
    expect(maskStillFits(moved, moved.mask)).toBe(false);
    expect(documentOf(set([moved])).threats[0]).not.toHaveProperty("mask");
  });

  it("keeps masks only while they fit the budget, and every threat regardless", () => {
    const a = threat();
    const b = threat({ name: "SA-6" });
    const big = "x".repeat(MASK_BUDGET - 1000);
    const doc = documentOf(set([{ ...a, mask: maskFor(a, big) }, { ...b, mask: maskFor(b, big) }]));
    expect(doc.threats).toHaveLength(2);
    expect(doc.threats[0].mask).toBeTruthy();
    expect(doc.threats[1]).not.toHaveProperty("mask");
  });

  it("reads back what it wrote, though the API sorts the keys", () => {
    const t = threat();
    const doc = documentOf(set([{ ...t, visible: false, mask: maskFor(t) }]));
    const sorted = JSON.parse(JSON.stringify(doc, (key, value) => (value && typeof value === "object" && !Array.isArray(value) ? Object.fromEntries(Object.entries(value).sort()) : value)));
    const { threats } = readDocument(sorted);
    expect(threats[0].mask).toBeTruthy();
    expect(threats[0].visible).toBe(false);
    expect(threats[0].maskLoading).toBe(false);
  });

  it("gives a threat a new id when another open set has that one", () => {
    const t = threat();
    const { threats } = readDocument(documentOf(set([t])), new Set([t.id]));
    expect(threats[0].id).not.toBe(t.id);
  });

  it("writes back what it could not read, and what a newer version added", () => {
    const odd = { id: "odd", lat: "north" };
    const read = readDocument({ version: 3, later: 1, threats: [odd] });
    expect(read.threats).toEqual([]);
    expect(documentOf(set(read.threats, read))).toEqual({ later: 1, version: 3, threats: [odd] });
  });
});

describe("the small rules", () => {
  it("is not a conflict when only the masks differ", () => {
    const t = threat();
    const plain = documentOf(set([t]));
    const masked = documentOf(set([{ ...t, mask: maskFor(t) }]));
    expect(sameApartFromMasks(plain, masked)).toBe(true);
    expect(sameApartFromMasks(plain, documentOf(set([{ ...t, name: "OTHER" }])))).toBe(false);
  });

  it("keeps a conflict copy's name within the server's limit, whole characters only", () => {
    const name = conflictName("😀".repeat(100), new Date(2026, 10, 8, 9, 5));
    expect(name.endsWith(" (from this browser, 09:05)")).toBe(true);
    expect(Array.from(name)).toHaveLength(100);
    expect(name.isWellFormed?.() ?? true).toBe(true);
  });

  it("names a set for its file", () => {
    expect(setNameFromFile("north_ga.ths")).toBe("NORTH_GA");
    expect(setNameFromFile(".ths")).toBe("THREATS");
  });
});
