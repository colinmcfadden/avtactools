import {
  isMeasuresSet,
  isMeasuresSetId,
  lineCount,
  lineLengthKm,
  linePoints,
  linesFromData,
  savedLines,
  setsWithLines,
} from "../feature/setLines/setLines";
import { flipFlot, flotLabelSpot, scallopRuns } from "../feature/flot/flots";
import {
  cleanPhaseLineName,
  createPhaseLine,
  labelShift,
  nextPhaseLineName,
  phaseLineEnds,
  phaseLineLabel,
  renamePhaseLine,
} from "../feature/phaseLines/phaseLines";

const fs = require("fs");
const { UPDATE, compact, fixturePath, writeFixture } = require("./fixtureIO");

// Control measures (the owner, 2026-11-15): the FLOT and phase lines, and the set of routes that holds only them, as the web works them
// out (setLines.js, flots.js, phaseLines.js). Screen numbers are written to a billionth of a pixel and lengths to a millionth of a km, so
// a machine whose sine differs in the last digit (AGENTS.md §11) writes the same file; the native apps compare within that.

const NAME = "planning/controlMeasures.json";
const round = (value, digits) => (Number.isFinite(value) ? Number(value.toFixed(digits)) : value);
const pixels = (list) => list.map(({ x, y }) => ({ x: round(x, 9), y: round(y, 9) }));
const settled = (value) =>
  JSON.parse(JSON.stringify(value, (_k, v) => (typeof v === "number" ? round(v, 9) : v === undefined ? null : v)));

const SCREEN_LINES = {
  straight: [{ x: 0, y: 0 }, { x: 100, y: 0 }],
  shorterThanAScallop: [{ x: 10, y: 10 }, { x: 15, y: 10 }],
  aCorner: [{ x: 0, y: 0 }, { x: 60, y: 0 }, { x: 60, y: 45 }],
  diagonalUp: [{ x: 0, y: 100 }, { x: 70, y: 30 }, { x: 140, y: 40 }],
  aRepeatedPoint: [{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 50, y: 0 }],
  noLength: [{ x: 5, y: 5 }, { x: 5, y: 5 }],
  onePoint: [{ x: 5, y: 5 }],
  long: [{ x: -400, y: 50 }, { x: 900, y: 80 }],
};
const OPTIONS = {
  default: undefined,
  flipped: { flipped: true },
  smaller: { diameter: 10 },
  clipped: { within: { minX: 0, minY: 0, maxX: 400, maxY: 300 } },
  clippedAndFlipped: { flipped: true, within: { minX: 0, minY: 0, maxX: 400, maxY: 300 } },
};
const SCALLOP_CASES = [
  ...Object.keys(SCREEN_LINES).map((line) => [line, "default"]),
  ["straight", "flipped"], ["aCorner", "flipped"], ["straight", "smaller"], ["diagonalUp", "smaller"],
  ["long", "clipped"], ["long", "clippedAndFlipped"], ["diagonalUp", "clipped"],
];

const NAMES = [
  "red", "  Red  ", "pl red", "PL RED", "PL", "pl", "PLATTE", "PL  BLUE  LINE", "Blue\tline\n2", " green line﻿",
  "straße", "ǆemal", "PL RED", "plRED", "A".repeat(30), "😀".repeat(30), "AB😀".repeat(9), "  ", "", null, undefined, 7, "é".normalize("NFD"),
];

const LINE_SETS = {
  none: [],
  someTaken: [{ name: "ALPHA" }, { name: "bravo" }, { name: "PL CHARLIE" }],
  aGap: [{ name: "BRAVO" }],
  allTaken: ["ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU"].map((name) => ({ name })),
};
LINE_SETS.allTakenAnd27 = [...LINE_SETS.allTaken, { name: "27" }, { name: "29" }];
LINE_SETS.oddEntries = [null, { name: 7 }, {}, { name: "ALPHA" }];

const GROUND_LINES = {
  twoPoints: { id: "f1", points: [{ lat: 34.8, lon: -84.09 }, { lat: 34.8, lon: -84.07 }] },
  three: { id: "f2", points: [{ lat: 34.8, lon: -84.09 }, { lat: 34.801, lon: -84.089 }, { lat: 34.81, lon: -84.06 }] },
  someNotPlaces: { id: "f3", points: [{ lat: 34.8, lon: -84.09 }, { lat: "34.8", lon: -84.08 }, null, { lat: 34.81, lon: -84.06 }] },
  onePoint: { id: "f4", points: [{ lat: 34.8, lon: -84.09 }] },
  noPoints: { id: "f5" },
};

const SETS = {
  empty: [{ routes: [] }, false],
  emptyButMade: [{ routes: [] }, true],
  aFlot: [{ routes: [], flots: [{ id: "f" }] }, false],
  aPhaseLine: [{ routes: [], phaseLines: [{ id: "p" }] }, false],
  routesAndLines: [{ routes: [{ id: "r" }], flots: [{ id: "f" }] }, true],
  noRoutesKey: [{ flots: [{ id: "f" }] }, false],
  nothing: [null, false],
};

const SET_IDS = ["measures", "measures-1731700000000-abc", "measure", "routes-1", null, undefined, 7, ""];

const DATA = [
  { flots: [{ id: "a", points: [] }, null, { points: [] }, "x", { id: 0 }, { id: null }, [], { id: "b", setId: "s" }] },
  { flots: "not a list" },
  null,
  { phaseLines: [{ id: "p", name: "RED", points: [] }] },
];

const fixture = () => ({
  generatedBy: "frontend/src/contracts/controlMeasuresFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "The FLOT, phase lines and sets of control measures as the web works them out. Screen points are CSS pixels with y down; numbers to a billionth, a line's length to a millionth "
    + "of a km. scallopRuns(pixels, {diameter = 18, flipped, within}) is the runs of points each unbroken stretch of scallops is drawn through (each run's first point, then 8 points "
    + "per semicircle), right of the way drawn unless flipped; flotLabelSpot is the line's middle and the unit vector away from its scallops; phaseLineEnds the two ends each with the "
    + "unit vector out of the line; labelShift how far a label's middle is from its end in halves of its width and height. cleanPhaseLineName: trim, capitals, white space single, a "
    + "leading PL taken, cut to 24 code points and trimmed again. Inputs that are undefined are written as null with \"undefined\": true.",
  scallops: SCALLOP_CASES.map(([line, option]) => ({
    line,
    option,
    pixels: SCREEN_LINES[line],
    ...(OPTIONS[option] ? { options: OPTIONS[option] } : {}),
    runs: scallopRuns(SCREEN_LINES[line], OPTIONS[option]).map(pixels),
  })),
  labelSpots: Object.entries(SCREEN_LINES).flatMap(([line, points]) =>
    [false, true].map((flipped) => ({ line, flipped, spot: settled(flotLabelSpot(points, flipped)) })),
  ),
  phaseLineEnds: Object.entries(SCREEN_LINES).map(([line, points]) => ({ line, ends: settled(phaseLineEnds(points)) })),
  labelShifts: [[1, 0], [0, -1], [0.6, 0.8], [-0.6, 0.8], [Math.SQRT1_2, -Math.SQRT1_2], [0, 0], [-1, 0]].map(([ux, uy]) => ({ ux: round(ux, 9), uy: round(uy, 9), shift: settled(labelShift({ ux, uy })) })),
  names: NAMES.map((text) => ({ text: text === undefined ? null : text, ...(text === undefined ? { undefined: true } : {}), name: cleanPhaseLineName(text), label: phaseLineLabel({ name: cleanPhaseLineName(text) }) })),
  labels: [{ name: " RED " }, { name: "" }, { name: 7 }, {}, null].map((line) => ({ line, label: phaseLineLabel(line) })),
  nextNames: Object.entries(LINE_SETS).map(([name, lines]) => ({ name, lines, next: nextPhaseLineName(lines) })),
  created: [createPhaseLine([{ lat: 34.8, lon: -84.09, extra: 1 }, { lat: 34.81, lon: -84.08 }], "pl-1", "pl red")],
  renamed: [["blue", "BLUE"], ["  ", "RED"], ["PL", "RED"], ["pl green", "GREEN"]].map(([to, name]) => ({ to, name: renamePhaseLine({ id: "p", name: "RED", points: [] }, to).name, same: renamePhaseLine({ id: "p", name: "RED", points: [] }, to).name === name })),
  flipped: [{ id: "f", points: [] }, { id: "f", points: [], flipped: true }, { id: "f", flipped: false, points: [] }].map((flot) => ({ flot, result: flipFlot(flot) })),
  lines: Object.entries(GROUND_LINES).map(([name, line]) => ({ name, line, points: linePoints(line), lengthKm: round(lineLengthKm(line), 6) })),
  linesFromData: DATA.flatMap((data) => ["flots", "phaseLines"].map((key) => ({ data, key, lines: linesFromData(data, key) }))),
  savedLines: [{ lines: [{ id: "a", setId: "s", points: [] }, { id: "b", points: [] }], saved: savedLines([{ id: "a", setId: "s", points: [] }, { id: "b", points: [] }]) }, { lines: null, saved: savedLines(null) }],
  measuresSets: Object.entries(SETS).map(([name, [set, made]]) => ({ name, set, made, lineCount: lineCount(set), measures: isMeasuresSet(set, made) })),
  measuresSetIds: SET_IDS.map((setId) => ({ setId: setId === undefined ? null : setId, ...(setId === undefined ? { undefined: true } : {}), measures: isMeasuresSetId(setId) })),
  setsWithLines: [
    {
      sketchSets: [{ setId: "s1", routes: [{ id: "r1" }] }, { setId: "s2", routes: [] }],
      lists: { flots: [{ id: "f1", setId: "s1" }, { id: "f2" }, { id: "f3", setId: "opened-1" }, { id: "f4", setId: "closed-1" }], phaseLines: [{ id: "p1", setId: "s2" }, { id: "p2", setId: "opened-1" }] },
      opened: ["opened-1"],
    },
    { sketchSets: [], lists: { flots: [], phaseLines: [] }, opened: [] },
    { sketchSets: [{ setId: null, routes: [] }], lists: { flots: [{ id: "f", setId: null }] }, opened: [] },
  ].map((c) => ({ ...c, result: setsWithLines(c.sketchSets, c.lists, (setId) => c.opened.includes(setId)) })),
});

describe("the control measures fixture", () => {
  it(NAME, () => {
    const text = `${compact(fixture())}\n`;
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
