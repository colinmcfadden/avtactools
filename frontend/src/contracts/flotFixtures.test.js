import { flipFlot, flotLabelSpot, flotsFromData, scallopRuns, SCALLOP_PX } from "../feature/flot/flots";
import { isMeasuresSet, lineCount, lineLengthKm, linePoints, plainPoints } from "../feature/setLines/setLines";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// A FLOT's scallops and label on the screen, flipping it, and what the line helpers it shares with a phase line read of a set's
// saved data (feature/setLines), as the web works them out. Pixels are written to a millionth and distances to a millionth of a km,
// so the trigonometry of an ARM build of V8 and an x86 one (AGENTS.md §11) writes the same file.

const NAME = "planning/flots.json";
const round = (value, digits) => Number(value.toFixed(digits));
const pixel = ({ x, y }) => ({ x: round(x, 6), y: round(y, 6) });

const STRAIGHT = [{ x: 10, y: 50 }, { x: 100, y: 50 }];
const SCALLOPS = [
  { name: "a straight line of five scallops, to the right of the way it was drawn (below it, since screen y runs down)", pixels: STRAIGHT },
  { name: "flipped, they are on the other side", pixels: STRAIGHT, flipped: true },
  { name: "round a corner each scallop is between two places on the line", pixels: [{ x: 0, y: 0 }, { x: 40, y: 0 }, { x: 40, y: 40 }] },
  { name: "a line shorter than a scallop is one scallop", pixels: [{ x: 0, y: 0 }, { x: 7, y: 0 }] },
  { name: "a whole number of scallops as near the diameter as fits: 50 px is three of 16.7", pixels: [{ x: 0, y: 0 }, { x: 50, y: 0 }] },
  { name: "half a scallop over rounds up: 63 px is four", pixels: [{ x: 0, y: 0 }, { x: 63, y: 0 }] },
  { name: "another diameter", pixels: [{ x: 0, y: 0 }, { x: 0, y: 72 }], diameter: 24 },
  { name: "a leg with no length in the middle is passed over", pixels: [{ x: 0, y: 0 }, { x: 30, y: 0 }, { x: 30, y: 0 }, { x: 30, y: 36 }] },
  { name: "drawn right to left, they are above it", pixels: [{ x: 100, y: 50 }, { x: 10, y: 50 }] },
  { name: "a scallop whose middle is off the box is left out, which breaks the line into runs", pixels: [{ x: 10, y: 20 }, { x: 10, y: 160 }, { x: 90, y: 160 }, { x: 90, y: 20 }], within: { minX: 0, minY: 0, maxX: 100, maxY: 100 } },
  { name: "a box's edges are inside it", pixels: [{ x: 0, y: 0 }, { x: 36, y: 0 }], within: { minX: 9, minY: 0, maxX: 27, maxY: 0 } },
  { name: "a box with every scallop outside it draws none", pixels: STRAIGHT, within: { minX: 200, minY: 200, maxX: 300, maxY: 300 } },
  { name: "one point draws nothing", pixels: [{ x: 5, y: 5 }] },
  { name: "points all in one place draw nothing", pixels: [{ x: 5, y: 5 }, { x: 5, y: 5 }, { x: 5, y: 5 }] },
];

const LABELS = [
  { name: "the middle of a straight line, away from its scallops", pixels: STRAIGHT },
  { name: "flipped, away from the other side", pixels: STRAIGHT, flipped: true },
  { name: "the middle by distance, round a corner", pixels: [{ x: 0, y: 0 }, { x: 30, y: 0 }, { x: 30, y: 90 }] },
  { name: "a first leg with no length is passed over", pixels: [{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 0, y: 40 }] },
  { name: "the middle on a corner is on the leg it ends", pixels: [{ x: 0, y: 0 }, { x: 20, y: 0 }, { x: 20, y: 20 }] },
  { name: "one point has no label", pixels: [{ x: 5, y: 5 }] },
  { name: "points all in one place have no label", pixels: [{ x: 5, y: 5 }, { x: 5, y: 5 }] },
];

const FLOT = { id: "flot-1", points: [{ lat: 34.8, lon: -84.09 }, { lat: 34.81, lon: -84.07 }] };
const FLIPS = [
  { name: "an unflipped FLOT is flipped, the key put last", flot: FLOT },
  { name: "a flipped FLOT loses the key, so an unflipped one keeps one shape", flot: { ...FLOT, flipped: true } },
  { name: "flipped false is not flipped: flipping it makes it true", flot: { id: "flot-2", flipped: false, points: FLOT.points } },
  { name: "the fields a newer version wrote are kept, in their order", flot: { id: "flot-3", flipped: true, colour: "#2f8fff", points: FLOT.points } },
];

const POINTS = [
  { lat: 34.8, lon: -84.09 }, { lat: "34.81", lon: -84.08 }, null, { lat: 34.82 }, { lat: NaN, lon: -84 }, { lat: 34.83, lon: -84.06, name: "kept by points" },
];
const DATA = [
  { name: "lines are the entries that are objects with an id", data: { flots: [null, 5, "flot", { points: [] }, { id: "a", points: POINTS }, { id: 0 }, { id: null }, [1]] } },
  { name: "no key is no lines", data: { routes: [] } },
  { name: "a key that is not a list is no lines", data: { flots: { id: "a" } } },
  { name: "no data at all is no lines", data: null },
];

const LENGTHS = [
  { name: "two points", line: FLOT },
  { name: "the legs added up, points that are not places passed over", line: { id: "a", points: POINTS } },
  { name: "one point has no length", line: { id: "a", points: [{ lat: 34.8, lon: -84.09 }] } },
  { name: "no points", line: { id: "a" } },
];

const SETS = [
  { name: "a set of routes is no control measures", set: { routes: [{ id: "r1" }], flots: [FLOT] } },
  { name: "no routes and a FLOT is", set: { routes: [], flots: [FLOT] } },
  { name: "no routes and a phase line is", set: { routes: [], phaseLines: [{ id: "pl-1", name: "RED", points: [] }] } },
  { name: "no routes and no lines is an empty route set", set: { routes: [] } },
  { name: "unless it was made for them", set: { routes: [] }, made: true },
  { name: "a set with routes is never control measures, made or not", set: { routes: [{ id: "r1" }] }, made: true },
  { name: "no routes key is no routes", set: { flots: [FLOT, FLOT], phaseLines: [{ id: "pl-1" }] } },
  { name: "no set at all", set: null },
];

const fixture = () => ({
  generatedBy: "frontend/src/contracts/flotFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "A FLOT ({id, points: [{lat, lon}, ...], flipped?: true}, under `flots` of a route set's document) is drawn as scallops worked out on the screen. "
    + "`scallops`: scallopRuns(pixels, {diameter, flipped, within}) -> `runs`, lists of pixels, one for each unbroken run: count = max(1, round(length / diameter)) scallops "
    + `(diameter ${SCALLOP_PX} px unless given, round as JavaScript's Math.round), each a semicircle of 8 steps between two places on the line a chord apart along it, `
    + "to the right of the way the line was drawn (screen y runs down) or the left when flipped; a run starts with its first place, then each arc's 8 points; "
    + "one whose chord's middle is outside `within` ({minX, minY, maxX, maxY}, inclusive) is left out, breaking the run. "
    + "`labels`: flotLabelSpot(pixels, flipped) -> `spot`, the middle of the line by distance {x, y} and the unit vector away from its scallops {awayX, awayY}, or null. "
    + "`flips`: flipFlot(flot) -> `flipped`. `data`: linesFromData(data, \"flots\") -> `lines`, then linePoints of each -> `points` (only finite numbers are places) "
    + "and plainPoints of those -> `plain`. `lengths`: lineLengthKm(line) -> `km`, the legs' great-circle feet (the web's getDistanceFeet) over 3,280.84. "
    + "`sets`: lineCount(set) -> `count`, isMeasuresSet(set, made) -> `measures`: no routes, and lines or made for them.",
  scallops: SCALLOPS.map(({ name, pixels, diameter, flipped, within }) => ({
    name, pixels, ...(diameter ? { diameter } : {}), ...(flipped ? { flipped } : {}), ...(within ? { within } : {}),
    runs: scallopRuns(pixels, { ...(diameter ? { diameter } : {}), flipped: !!flipped, within: within ?? null }).map((run) => run.map(pixel)),
  })),
  labels: LABELS.map(({ name, pixels, flipped }) => {
    const spot = flotLabelSpot(pixels, !!flipped);
    return {
      name, pixels, ...(flipped ? { flipped } : {}),
      spot: spot && { x: round(spot.x, 6), y: round(spot.y, 6), awayX: round(spot.awayX, 9), awayY: round(spot.awayY, 9) },
    };
  }),
  flips: FLIPS.map(({ name, flot }) => ({ name, flot, flipped: flipFlot(flot) })),
  data: DATA.map(({ name, data }) => {
    const lines = flotsFromData(data);
    return { name, data, lines, points: lines.map(linePoints), plain: lines.map((line) => plainPoints(linePoints(line))) };
  }),
  lengths: LENGTHS.map(({ name, line }) => ({ name, line, km: round(lineLengthKm(line), 6) })),
  sets: SETS.map(({ name, set, made }) => ({ name, set, ...(made ? { made } : {}), count: lineCount(set), measures: isMeasuresSet(set, !!made) })),
});

describe("the FLOT fixture", () => {
  it(NAME, () => {
    const text = dumps(fixture());
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
