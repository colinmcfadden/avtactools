import { backstopAnchor, backstopReading, moveBackstopPoints, rotateBackstopPoints } from "../feature/backstops/backstops";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What a backstop's label says and how its line moves and turns, as the web works them out. Places are written to a billionth of a degree
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

const fixture = () => ({
  generatedBy: "frontend/src/contracts/backstopFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "A backstop keeps only its line ({id, points: [{lat, lon}, ...]}). Its arrow starts at the line's middle by distance (anchor) and points at the LZ/PZ's target "
    + "[lat, lon]; azimuth is that initial great-circle bearing in whole degrees true, 360 written as 0; text is the label: three digits, \"°T · \", the distance in km to one decimal, \" km\". "
    + "move adds to every point; rotate turns clockwise about a point on a local flat plane (east is scaled by the cosine of its latitude).",
  target: LZ,
  readings: Object.entries(LINES).map(([name, points]) => {
    const reading = backstopReading(points, LZ);
    return { name, points, anchor: place(backstopAnchor(points)), azimuth: reading.azimuth, distanceKm: round(reading.distanceKm, 6), text: reading.text };
  }),
  moves: [{ points: LINES.uneven, dLat: 0.01, dLon: -0.02 }].map((c) => ({ ...c, result: moveBackstopPoints(c.points, c.dLat, c.dLon).map(place) })),
  rotations: [15, 90, -37, 180].map((degrees) => {
    const about = backstopAnchor(LINES.uneven);
    return { points: LINES.uneven, about: place(about), degrees, result: rotateBackstopPoints(LINES.uneven, about, degrees).map(place) };
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
