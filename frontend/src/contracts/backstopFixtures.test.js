import { backstopArrow, backstopReading, moveBackstopArrow } from "../feature/backstops/backstops";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// Where a backstop's arrow runs, what its label says and how it moves, as the web works them out. Places are written to a billionth of a degree
// and distances to a millionth of a km, so the sine of an ARM build of V8 and an x86 one (AGENTS.md §11) write the same file.

const NAME = "planning/backstops.json";
const round = (value, digits) => Number(value.toFixed(digits));
const place = ({ lat, lon }) => ({ lat: round(lat, 9), lon: round(lon, 9) });

const LZ = [34.7838, -84.0822];
const LINES = {
  twoPoints: [{ lat: 34.8, lon: -84.09 }, { lat: 34.8, lon: -84.07 }],
  uneven: [{ lat: 34.8, lon: -84.09 }, { lat: 34.801, lon: -84.089 }, { lat: 34.81, lon: -84.06 }],
  southOfTheLz: [{ lat: 34.75, lon: -84.0822 }, { lat: 34.75, lon: -84.0822 }],
  noLength: [{ lat: 34.8, lon: -84.09 }, { lat: 34.8, lon: -84.09 }],
};

const ARROWS = {
  tipOnTheTarget: { tail: { lat: 34.82, lon: -84.1 } },
  movedWhole: { tail: { lat: 34.82, lon: -84.1 }, tip: { lat: 34.8, lon: -84.05 } },
  // A pack sends a field that went as null.
  tipTakenBackInAPack: { tail: { lat: 34.82, lon: -84.1 }, tip: null },
};

const reading = (name, backstop) => {
  const arrow = backstopArrow(backstop, LZ);
  const read = backstopReading(arrow);
  return { name, ...backstop, tail: place(arrow.tail), tip: place(arrow.tip), onTarget: arrow.onTarget, azimuth: read.azimuth, distanceKm: round(read.distanceKm, 6), text: read.text };
};

const fixture = () => ({
  generatedBy: "frontend/src/contracts/backstopFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "A backstop keeps its line ({id, points: [{lat, lon}, ...]}), which stays where it was sketched, and once its arrow has moved {arrow: {tail, tip?}}. With no arrow the tail is the line's middle by distance; "
    + "with no tip (or a null one) the tip is on the LZ/PZ's target [lat, lon] and follows it (onTarget). azimuth is the initial great-circle bearing from tail to tip in whole degrees true, 360 written as 0; "
    + "text is the label: three digits, \"°T · \", the arrow's length in km to one decimal, \" km\". A move adds to both ends and always leaves a tip of its own. A tip dragged within 20 screen pixels of the target goes back onto it "
    + "(no tip), and one that would leave the arrow under 24 pixels long is refused.",
  target: LZ,
  readings: [
    ...Object.entries(LINES).map(([name, points]) => reading(name, { points })),
    ...Object.entries(ARROWS).map(([name, arrow]) => reading(name, { points: LINES.twoPoints, arrow })),
  ],
  moves: [{ points: LINES.uneven, dLat: 0.01, dLon: -0.02 }, { points: LINES.twoPoints, arrow: ARROWS.movedWhole, dLat: -0.005, dLon: 0.003 }].map(({ dLat, dLon, ...backstop }) => {
    const moved = moveBackstopArrow(backstopArrow(backstop, LZ), dLat, dLon);
    return { ...backstop, dLat, dLon, result: { tail: place(moved.tail), tip: place(moved.tip) } };
  }),
});

describe("the backstop fixture", () => {
  it(NAME, () => {
    const text = dumps(fixture());
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
