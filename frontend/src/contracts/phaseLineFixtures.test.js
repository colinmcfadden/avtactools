import {
  cleanPhaseLineName, createPhaseLine, labelShift, nextPhaseLineName, PHASE_LINE_NAME_MAX, phaseLineEnds, phaseLineLabel, phaseLinesFromData, renamePhaseLine,
} from "../feature/phaseLines/phaseLines";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// A phase line's name, its label and where its two labels go on the screen, as the web works them out. Shifts are written to a
// billionth, so the arithmetic of an ARM build of V8 and an x86 one (AGENTS.md §11) writes the same file.

const NAME = "planning/phaseLines.json";
const round = (value, digits) => Number(value.toFixed(digits));

const PHONETIC = ["ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE",
  "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU"];
const named = (...names) => names.map((name, i) => ({ id: `pl-${i}`, name, points: [] }));

const CLEAN = [
  { name: "upper case", text: "red" },
  { name: "a PL typed in front is dropped, and the spaces round it", text: "  pl red  " },
  { name: "PL alone is no name", text: "PL" },
  { name: "PL with a space and nothing after is no name either", text: "pl " },
  { name: "only one PL is dropped", text: "PL PL RED" },
  { name: "a name that starts with PL is kept whole", text: "PLATTE" },
  { name: "runs of spaces and tabs are one space", text: "pl\t\tred   line" },
  { name: "JavaScript's spaces: a no-break space, an ideographic space, a line separator", text: " red　line " },
  { name: "a byte order mark is trimmed as JavaScript trims", text: "﻿blue" },
  { name: "a zero-width space is not a space", text: "​blue" },
  { name: "upper case as JavaScript has it, a sharp s two letters", text: "straße" },
  { name: `cut to ${PHASE_LINE_NAME_MAX} characters`, text: "abcdefghijklmnopqrstuvwxyz0123" },
  { name: "cut by characters, never through an emoji", text: `${"A".repeat(22)}\u{1F681}\u{1F681}B` },
  { name: "a space left at the cut is trimmed", text: `${"A".repeat(23)} BRAVO` },
  { name: "null is no name", text: null },
  { name: "a number is its text", text: 5 },
];

const LABELS = [
  { name: "PL and the name", line: { id: "pl-1", name: "RED", points: [] } },
  { name: "the name trimmed, not cleaned", line: { id: "pl-1", name: "  red ", points: [] } },
  { name: "a blank name is PL alone", line: { id: "pl-1", name: "   ", points: [] } },
  { name: "a name that is not text is PL alone", line: { id: "pl-1", name: 5, points: [] } },
  { name: "no name is PL alone", line: { id: "pl-1", points: [] } },
  { name: "no line is PL alone", line: null },
];

const NEXT = [
  { name: "the first is ALPHA", lines: [] },
  { name: "the first word no line has", lines: named("ALPHA", "CHARLIE") },
  { name: "names are compared cleaned", lines: named("pl alpha", " bravo") },
  { name: "past ZULU, the next number no line has", lines: named(...PHONETIC) },
  { name: "and the one after", lines: named(...PHONETIC, "27", "29") },
  { name: "lines with no name take nothing", lines: [{ id: "a" }, null, { id: "b", name: 7 }] },
  { name: "no lines at all", lines: null },
];

const RENAMES = [
  { name: "a new name, cleaned", line: { id: "pl-1", name: "RED", points: [{ lat: 1, lon: 2 }] }, text: "pl blue" },
  { name: "a blank name changes nothing", line: { id: "pl-1", name: "RED", points: [] }, text: "  " },
  { name: "PL alone changes nothing", line: { id: "pl-1", name: "RED", points: [] }, text: "PL" },
  { name: "the line's other fields are kept, in their order", line: { id: "pl-1", colour: "black", name: "RED", points: [] }, text: "gold" },
];

const CREATES = [
  { name: "the points as places only, the name cleaned", points: [{ lat: 34.8, lon: -84.09, at: 3 }, { lat: 34.81, lon: -84.07 }], id: "pl-1", text: "pl red" },
  { name: "a blank name is kept blank", points: [{ lat: 34.8, lon: -84.09 }, { lat: 34.81, lon: -84.07 }], id: "pl-2", text: "" },
];

const ENDS = [
  { name: "a straight line: each end points away from the other", pixels: [{ x: 10, y: 50 }, { x: 110, y: 50 }] },
  { name: "a bent line: each end away from its own leg", pixels: [{ x: 0, y: 0 }, { x: 30, y: 40 }, { x: 90, y: 40 }] },
  { name: "a point on top of the end is passed over for the next", pixels: [{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 0, y: 50 }, { x: 0, y: 50 }] },
  { name: "points all in one place have no ends", pixels: [{ x: 4, y: 4 }, { x: 4, y: 4 }] },
  { name: "one point has no ends", pixels: [{ x: 4, y: 4 }] },
];

const SHIFTS = [
  { name: "leaving to the right, the label is to the right of its end", out: { ux: 1, uy: 0 } },
  { name: "leaving up", out: { ux: 0, uy: -1 } },
  { name: "leaving at an angle, the end is on the label's edge", out: { ux: 0.6, uy: 0.8 } },
  { name: "leaving to the lower left", out: { ux: -0.8, uy: 0.6 } },
  { name: "no way out is no shift", out: { ux: 0, uy: 0 } },
];

const DATA = [
  { name: "lines are the entries that are objects with an id", data: { phaseLines: [null, "PL RED", { name: "RED" }, { id: "pl-1", name: "RED", points: [] }, { id: 7 }] } },
  { name: "no key is no lines", data: { flots: [{ id: "a" }] } },
];

const fixture = () => ({
  generatedBy: "frontend/src/contracts/phaseLineFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "A phase line ({id, name, points: [{lat, lon}, ...]}, under `phaseLines` of a route set's document) is named without its PL and drawn as PL and its name beyond each end. "
    + `\`clean\`: cleanPhaseLineName(text) -> \`clean\`: String(text ?? ""), trimmed as JavaScript trims, upper-cased as JavaScript does, runs of JavaScript's spaces (\\s) made one space, one "PL" at the start followed by a space or nothing dropped, cut to ${PHASE_LINE_NAME_MAX} characters (code points) and trimmed again. `
    + "`labels`: phaseLineLabel(line) -> `label`: \"PL \" and its name trimmed, or \"PL\" when that is blank or not text. "
    + "`next`: nextPhaseLineName(lines) -> `next`: the first word of the phonetic alphabet (XRAY, JULIETT) no line's cleaned name is, past ZULU the first number from 27 none is. "
    + "`renames`: renamePhaseLine(line, text) -> `renamed`, the line unchanged when the cleaned name is blank. `creates`: createPhaseLine(points, id, text) -> `line`. "
    + "`ends`: phaseLineEnds(pixels) -> `ends`, at each end of the line on the screen {x, y, ux, uy}, the unit vector leaving it, away from the first point that is not on it; none when either has no such point. "
    + "`shifts`: labelShift({ux, uy}) -> `shift` {fx, fy}: the vector over its larger part, the share of a label's width and height to move its middle by so the end is on its edge. "
    + "`data`: phaseLinesFromData(data) -> `lines`.",
  clean: CLEAN.map(({ name, text }) => ({ name, text, clean: cleanPhaseLineName(text) })),
  labels: LABELS.map(({ name, line }) => ({ name, line, label: phaseLineLabel(line) })),
  next: NEXT.map(({ name, lines }) => ({ name, lines, next: nextPhaseLineName(lines) })),
  renames: RENAMES.map(({ name, line, text }) => ({ name, line, text, renamed: renamePhaseLine(line, text) })),
  creates: CREATES.map(({ name, points, id, text }) => ({ name, points, id, text, line: createPhaseLine(points, id, text) })),
  ends: ENDS.map(({ name, pixels }) => ({
    name, pixels, ends: phaseLineEnds(pixels).map(({ x, y, ux, uy }) => ({ x: round(x, 9), y: round(y, 9), ux: round(ux, 9), uy: round(uy, 9) })),
  })),
  shifts: SHIFTS.map(({ name, out }) => {
    const { fx, fy } = labelShift(out);
    return { name, out, shift: { fx: round(fx, 9), fy: round(fy, 9) } };
  }),
  data: DATA.map(({ name, data }) => ({ name, data, lines: phaseLinesFromData(data) })),
});

describe("the phase line fixture", () => {
  it(NAME, () => {
    const text = dumps(fixture());
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
