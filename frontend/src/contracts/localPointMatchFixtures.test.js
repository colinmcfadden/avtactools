import { indexLocalPointsByName, matchLocalPointName } from "../feature/localPoints/localPointMatch";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What typing a name on a route point does with the loaded local points (the web's `RoutePlanSection`, whose rule is `localPointMatch`): the cases
// that are fragile are a leading dot (stripped from what is typed, never from a local point's own name), a name in lower case, two points of one
// name (the later wins), a point with no name, a point whose elevation is zero or is not a number, and the characters whose capital is not the
// obvious one. The native apps are held to every case.

const GENERATED_BY = "frontend/src/contracts/localPointMatchFixtures.test.js (UPDATE_CONTRACTS=1)";
const NAME = "localpoints/match.json";

const POINTS = [
  { name: "BLUE 1", lat: 34.5123, lon: -84.2231, elevationFt: 1730 },
  { name: "Farp", lat: 34.601, lon: -84.119, elevationFt: null },
  { name: "SEA", lat: 34.7, lon: -84.3, elevationFt: 0 },
  { name: "DUP", lat: 35.0, lon: -84.0, elevationFt: 100 },
  { name: "DUP", lat: 35.1, lon: -84.1, elevationFt: 200 },
  { name: ".DOTTED", lat: 34.8, lon: -84.4, elevationFt: 900 },
  { name: "", lat: 34.9, lon: -84.5, elevationFt: 500 },
  { name: "STRASSE", lat: 36.0, lon: -85.0, elevationFt: 1 },
  { name: "ISTANBUL", lat: 36.1, lon: -85.1, elevationFt: 2 },
  { name: "NOELEV", lat: 36.2, lon: -85.2 },
];

const TYPED = [
  "BLUE 1",
  "blue 1",
  ".BLUE 1",
  ".blue 1",
  "..BLUE 1",
  "BLUE 1 ",
  " BLUE 1",
  "farp",
  ".Farp",
  "SEA",
  "DUP",
  ".DOTTED",
  "DOTTED",
  "..DOTTED",
  "",
  ".",
  "NOPE",
  "straße",
  "ıstanbul",
  "noelev",
];

const saved = (value) => JSON.parse(JSON.stringify(value));

const settle = (document) => {
  const file = fixturePath(NAME);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(NAME, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${NAME} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

test("what a typed name does with the loaded local points", () => {
  const byName = indexLocalPointsByName(POINTS);
  const cases = TYPED.map((typed) => {
    const { name, coords, chartElevationFt } = matchLocalPointName(byName, typed);
    // What the web leaves undefined is null here: a JSON fixture has no undefined.
    return saved({ typed, name, lat: coords?.lat ?? null, lon: coords?.lon ?? null, chartElevationFt: chartElevationFt ?? null });
  });
  expect(cases.filter((c) => c.lat !== null).length).toBeGreaterThan(8);
  expect(cases.filter((c) => c.lat === null).length).toBeGreaterThan(5);
  settle({ description: "What typing a name on a route point does with the loaded local points.", generatedBy: GENERATED_BY, points: saved(POINTS), cases });
});
