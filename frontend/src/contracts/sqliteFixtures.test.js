import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { SqliteReader } from "../feature/localPoints/sqliteReader";
import { parseLpsFile } from "../feature/localPoints/parseLps";
import { parseThsFile } from "../feature/threats/parseThs";
import { threatToPayload } from "../feature/threats/threatModel";

const fs = require("fs");
const { UPDATE, compact, fixturePath, writeFixture } = require("./fixtureIO");

// AMPS `.LPS` (local points) and `.ths` (threats) files are SQLite databases, read by
// the web with its own small reader (sqliteReader.js). Three things are held together here:
//
//   1. the web's reader returns what SQLite itself says is in each table (tables.json,
//      written by contracts/scripts/sqlite_fixtures.py from Python's sqlite3);
//   2. parseLpsFile / parseThsFile read each file to the answer recorded in
//      localpoints/parse.json and threats/parse.json;
//   3. the native readers are checked against 1 and 2 in their own test suites.
//
// The ids the web invents for a point or a threat (a random suffix, the clock) are not part of
// the answer and are left out. The threat is recorded in the shape it is sent to the server
// and exported in (threatToPayload), which is the part that persists.

beforeAll(() => {
  // jsdom provides neither; the browser does.
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

const bytesOf = (name) => fs.readFileSync(fixturePath(`sqlite/${name}`));
const asFile = (name) => {
  const bytes = bytesOf(name);
  return { name, arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) };
};
const arrayBufferOf = (bytes) => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);

const LPS_FILES = ["local-points.lps", "local-points-large.lps"];
const THS_FILES = ["threats.ths"];

// The web's reader assumes UTF-8 text; these two files store it as UTF-16, which only the native readers handle.
const NATIVE_ONLY = ["utf16le.db", "utf16be.db"];

const tables = JSON.parse(fs.readFileSync(fixturePath("sqlite/tables.json"), "utf8"));

const hex = (bytes) => Buffer.from(bytes).toString("hex");
const fromSqlite = (rows) => rows.map((row) => {
  const out = {};
  Object.entries(row).forEach(([column, value]) => { out[column] = value instanceof Uint8Array ? { hex: hex(value) } : value; });
  return out;
});
const asRows = (table) => table.rows.map((values) => Object.fromEntries(table.columns.map((c, i) => [c, values[i]])));

const lpsAnswer = async (name) => {
  const set = await parseLpsFile(asFile(name));
  return { name: set.name, points: set.points.map(({ id, ...rest }) => rest) };      // eslint-disable-line no-unused-vars
};
const thsAnswer = async (name) => (await parseThsFile(asFile(name))).map(threatToPayload);

const message = async (promise) => {
  try { await promise; } catch (error) { return error.message; }
  return null;
};

const buildExpected = async () => ({
  lps: Object.fromEntries(await Promise.all(LPS_FILES.map(async (n) => [n, await lpsAnswer(n)]))),
  lpsErrors: {
    "not SQLite": await message(parseLpsFile({ name: "junk.lps", arrayBuffer: async () => new TextEncoder().encode("not a database").buffer })),
    "no Points table": await message(parseLpsFile(asFile("deep-tree.db"))),
    "no readable points": await message(parseLpsFile(asFile("local-points-unreadable.lps"))),
  },
  ths: Object.fromEntries(await Promise.all(THS_FILES.map(async (n) => [n, await thsAnswer(n)]))),
  thsErrors: {
    "not SQLite": await message(parseThsFile({ name: "junk.ths", arrayBuffer: async () => new TextEncoder().encode("not a database").buffer })),
    "no THREATS table": await message(parseThsFile(asFile("deep-tree.db"))),
    "no readable threats": await message(parseThsFile(asFile("threats-empty.ths"))),
  },
});

describe("sqlite fixtures (the web's reader and the .LPS / .ths parsers)", () => {
  if (UPDATE) {
    it("writes the answers", async () => {
      const expected = await buildExpected();
      writeFixture("localpoints/parse.json", `${compact({
        description: "What parseLpsFile reads out of each .LPS in ../sqlite: the set's name (the file name without .lps) and every "
          + "readable point, in table order. Ids are left out. A missing elevation is null; an elevation that is not a number is null.",
        generatedBy: "frontend/src/contracts/sqliteFixtures.test.js (UPDATE_CONTRACTS=1)",
        tolerance: 1e-9,
        files: expected.lps,
        errors: expected.lpsErrors,
      })}\n`);
      writeFixture("threats/parse.json", `${compact({
        description: "What parseThsFile reads out of each .ths in ../sqlite, as threatToPayload sends it: position, symbol, "
          + "text and radars with their bands. Colours and transparency come from the defaults for the radar type, not from the file.",
        generatedBy: "frontend/src/contracts/sqliteFixtures.test.js (UPDATE_CONTRACTS=1)",
        tolerance: 1e-9,
        files: expected.ths,
        errors: expected.thsErrors,
      })}\n`);
    });
    return;
  }

  describe.each(Object.keys(tables).filter((f) => !NATIVE_ONLY.includes(f)))("%s", (file) => {
    it.each(Object.keys(tables[file]))("the web reader returns table %s as SQLite does", (table) => {
      const reader = new SqliteReader(arrayBufferOf(bytesOf(file)));
      expect(fromSqlite(reader.readTable(table))).toEqual(asRows(tables[file][table]));
    });
  });

  it("finds no such table, and refuses a file that is not SQLite", () => {
    expect(new SqliteReader(arrayBufferOf(bytesOf("local-points.lps"))).readTable("Nope")).toBeNull();
    expect(() => new SqliteReader(new TextEncoder().encode("not a database").buffer)).toThrow(/not a SQLite database/);
  });

  it("covers what the reader is for: interior pages, overflow pages, every value type", () => {
    const large = tables["local-points-large.lps"].Points.rows;
    expect(large.length).toBeGreaterThan(400);
    expect(Math.max(...large.map((r) => (r[2] || "").length))).toBeGreaterThan(5000);
    expect(tables["deep-tree.db"].Numbers.rows).toHaveLength(3000);
    const kinds = new Set(tables["local-points.lps"].Points.rows.flat().map((v) => (v === null ? "null" : typeof v === "object" ? "blob" : typeof v)));
    expect([...kinds].sort()).toEqual(["blob", "null", "number", "string"]);
  });

  describe("the parsers", () => {
    let recordedLps;
    let recordedThs;
    beforeAll(() => {
      recordedLps = JSON.parse(fs.readFileSync(fixturePath("localpoints/parse.json"), "utf8"));
      recordedThs = JSON.parse(fs.readFileSync(fixturePath("threats/parse.json"), "utf8"));
    });

    it.each(LPS_FILES)("parseLpsFile still reads %s to the recorded answer", async (file) => {
      expect(await lpsAnswer(file)).toEqual(recordedLps.files[file]);
    });

    it.each(THS_FILES)("parseThsFile still reads %s to the recorded answer", async (file) => {
      expect(await thsAnswer(file)).toEqual(recordedThs.files[file]);
    });

    it("the refusals still say what is recorded", async () => {
      const expected = await buildExpected();
      expect(expected.lpsErrors).toEqual(recordedLps.errors);
      expect(expected.thsErrors).toEqual(recordedThs.errors);
      Object.values(recordedLps.errors).concat(Object.values(recordedThs.errors)).forEach((m) => expect(typeof m).toBe("string"));
    });

    it("has every file, no more and no fewer", () => {
      expect(Object.keys(recordedLps.files)).toEqual(LPS_FILES);
      expect(Object.keys(recordedThs.files)).toEqual(THS_FILES);
    });

    it("reads what the .LPS fixtures are there to show", () => {
      const small = recordedLps.files["local-points.lps"].points;
      expect(small.map((p) => p.name)).toEqual([
        "3MILE", "PAD 7", "BLANKGRP", "NOGROUP", "BIGENDIAN", "TEXTELEV", "", "ÀÉÎ ⛰", "EDGE",
      ]);                                                                      // the line, the empty and the short ones are skipped
      expect(small.find((p) => p.name === "BLANKGRP").group).toBe("");           // blank-but-present stays blank
      expect(small.find((p) => p.name === "NOGROUP").group).toBe("Default");      // empty becomes Default
      expect(small.find((p) => p.name === "TEXTELEV").elevationFt).toBeNull();
      expect(small.find((p) => p.name === "3MILE").elevationFt).toBe(921);
      expect(recordedLps.files["local-points-large.lps"].points).toHaveLength(422);
    });

    it("reads what the .ths fixture is there to show", () => {
      const threats = recordedThs.files["threats.ths"];
      expect(threats).toHaveLength(7);
      expect(threats[2].name).toBe("Threat 3");                                  // the exporter named it
      expect(threats[2].showThreat).toBe(true);                                   // the reader does not take it from the file
      expect(threats[1].radars).toHaveLength(1);
      expect(threats[5].radars).toHaveLength(2);                                  // no radars in the file: the two defaults
    });
  });
});
