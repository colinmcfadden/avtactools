import { findInDocument, foldForSearch } from "../feature/documents/documentSearch";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// Find in a document (docs/DOCUMENTS.md §8.2): the web's documentSearch.js is the reference, and the native apps port it and are held
// to every case here, so a query finds the same pages, at the same places, on every client. A case gives the pages' text (as the
// server read it), the query and its options, and what is found; `folds` gives text and its folded form.
//
//   cd frontend && UPDATE_CONTRACTS=1 npx vitest run src/contracts/documentSearch

const FIXTURE = "documents/search.json";
const GENERATED_BY = "frontend/src/contracts/documentSearchFixtures.test.js (UPDATE_CONTRACTS=1)";

const OPORD = [
  "OPORD 24-03 (OPERATION DARK KNIGHT) 1. SITUATION. a. Area of Interest. Murray County, GA.",
  "Annex C: LZ HAWK at 16S GC 31204 58811. PZ CROW at 16S GC 29880 61207. Alternate LZ FALCON.",
  "",
  "Annex D (Fires): no fires within 500 m of LZ HAWK after H-hour. Lz Hawk is the main effort.",
];
const LONG = `${"A".repeat(60)} LZ HAWK ${"B".repeat(60)}`;

const CASES = [
  { name: "a grid on its page", pages: OPORD, query: "16S GC 31204" },
  { name: "case does not matter, and every page is searched", pages: OPORD, query: "lz hawk" },
  { name: "nothing found", pages: OPORD, query: "LZ EAGLE" },
  { name: "white space in the query is one space, and none at its ends", pages: OPORD, query: "  lz \t  HAWK  " },
  { name: "white space in the text is one space too", pages: ["LZ\t\tHAWK at\n\ngrid"], query: "lz hawk at grid" },
  { name: "accents are taken out", pages: ["Café CAFÉ cafe Café"], query: "cafe" },
  { name: "an accent in the query is taken out too", pages: ["resume"], query: "résumé" },
  { name: "compatibility forms are folded (a ligature, full width, a superscript)", pages: ["ﬁle ＬＺ ＨＡＷＫ x²"], query: "file lz hawk x2" },
  { name: "a ligature is found by part of it", pages: ["ofﬁce"], query: "off" },
  { name: "a capital I with a dot is an i", pages: ["İSTANBUL"], query: "istanbul" },
  { name: "each letter is lower-cased alone: a capital sigma is a sigma", pages: ["ΟΔΟΣ"], query: "οδοσ" },
  { name: "a final sigma is not a sigma", pages: ["οδος"], query: "οδοσ" },
  { name: "sharp s is not ss", pages: ["Straße"], query: "strasse" },
  { name: "hits never overlap", pages: ["aaaaa"], query: "aa" },
  { name: "the context is cut where the page starts and ends", pages: [LONG], query: "lz hawk" },
  { name: "a smaller context", pages: OPORD, query: "falcon", options: { context: 5 } },
  { name: "places are counted in code points", pages: ["🚁 LZ HAWK 🚁"], query: "hawk", options: { context: 3 } },
  { name: "a query that is an emoji", pages: ["CH-47 🚁 and UH-60 🚁"], query: "🚁" },
  { name: "a limit lists the first hits and counts them all", pages: OPORD, query: "lz", options: { limit: 2 } },
  { name: "an empty query finds nothing", pages: OPORD, query: "" },
  { name: "a query of spaces finds nothing", pages: OPORD, query: " \t " },
  { name: "a query of marks alone finds nothing", pages: OPORD, query: "́̈" },
  { name: "a page that is not text is passed over", pages: [null, 7, "LZ HAWK"], query: "hawk" },
  { name: "no pages", pages: [], query: "hawk" },
];

const FOLDS = [
  "OPORD 24-03",
  "Café  CAFÉ",
  "ﬁle ＬＺ x²",
  "İSTANBUL ΟΔΟΣ οδος Straße",
  "  leading and trailing  ",
  "🚁",
  "",
];

const document = () => ({
  description:
    "Find in a document (docs/DOCUMENTS.md §8.2). Query and text are each folded one code point at a time (NFKD, combining marks out, " +
    "each code point lower-cased alone, a run of white space one space); a hit is where the folded query occurs, hits never overlap, " +
    "and each is given in the page's own code points with up to `context` (default 40) either side. `limit` (default 1000) caps the hits listed.",
  generatedBy: GENERATED_BY,
  cases: CASES.map((c) => ({ ...c, expected: findInDocument(c.pages, c.query, c.options) })),
  folds: FOLDS.map((text) => ({ text, folded: foldForSearch(text) })),
});

describe("documents/search.json", () => {
  it("is what the web finds", () => {
    const text = dumps(document());
    if (UPDATE) {
      writeFixture(FIXTURE, text);
      return;
    }
    const file = fixturePath(FIXTURE);
    if (!fs.existsSync(file)) throw new Error(`${FIXTURE} is missing; run with UPDATE_CONTRACTS=1 to create it`);
    expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  });

  it("finds what it says", () => {
    const grid = findInDocument(OPORD, "16S GC 31204");
    expect(grid.count).toBe(1);
    expect(grid.hits[0]).toMatchObject({ page: 2, match: "16S GC 31204" });

    const hawk = findInDocument(OPORD, "lz hawk");
    expect(hawk.hits.map((h) => h.page)).toEqual([2, 4, 4]);
    expect(hawk.hits[2].match).toBe("Lz Hawk");

    expect(findInDocument(["aaaaa"], "aa").count).toBe(2);
    expect(findInDocument(["Café CAFÉ cafe Café"], "cafe").count).toBe(4);
    expect(findInDocument(["οδος"], "οδοσ").count).toBe(0);

    const emoji = findInDocument(["🚁 LZ HAWK 🚁"], "hawk", { context: 3 }).hits[0];
    expect(emoji).toMatchObject({ start: 5, end: 9, before: "LZ ", match: "HAWK", after: " 🚁", cutBefore: true, cutAfter: false });
  });

  it("cuts the context and says so", () => {
    const [hit] = findInDocument([LONG], "lz hawk").hits;
    expect(hit.before).toBe(`${"A".repeat(39)} `);
    expect(hit.after).toBe(` ${"B".repeat(39)}`);
    expect([hit.cutBefore, hit.cutAfter]).toEqual([true, true]);
  });

  it("lists up to the limit and counts every hit", () => {
    const found = findInDocument(OPORD, "lz", { limit: 2 });
    expect([found.hits.length, found.count, found.more]).toEqual([2, 4, true]);
  });
});
