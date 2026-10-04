import { buildFplXml, buildGpxXml, buildForeFlightRouteString, buildForeFlightUrl } from "../feature/msnxImport/foreflight";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What a route is handed to another app as: a Garmin flight plan (`.fpl`) and a GPX route, both of ALL the route's points (shaping points too, so the
// path is what is flown), and the ForeFlight route string and URL. The fragile parts are the waypoint identifiers (uppercase alphanumerics, ten at most,
// duplicates numbered), JavaScript's `toFixed(6)` (which rounds the exact binary value and writes `-0.000000` for a tiny negative), the XML escaping of
// names, and the route name cut to 25. The FPL carries a creation time, which the generator fixes. The native apps are held to every case.
//
// Not covered, because the two languages differ and the case is not one that matters: a route name whose 25th UTF-16 unit is half of an emoji. The web
// writes the lone half and the browser's encoder turns it into U+FFFD; a JVM encoder writes `?`.

const GENERATED_BY = "frontend/src/contracts/handoffFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));

const point = (lat, lon, name) => (name === undefined ? { lat, lon } : { lat, lon, name });

const CASES = [
  {
    name: "plain route, a shaping point with no name",
    now: "2026-10-03T12:34:56.789Z",
    route: { name: "Neptune", points: [point(34.78382, -84.08219, ".LZ HAWK"), point(34.8, -84.1), point(34.9, -84.2, "IP 1"), point(35.0, -84.3, ".TGT")] },
  },
  {
    name: "identifiers: lowercase, punctuation, an empty result, and non-ASCII letters",
    now: "2026-01-01T00:00:00.000Z",
    route: { name: "ids", points: [point(1, 1, "cp-1"), point(2, 2, "A.B/C"), point(3, 3, "---"), point(4, 4, "Élan"), point(5, 5, "  "), point(6, 6, ""), point(7, 7, "straße")] },
  },
  {
    name: "duplicate identifiers are numbered, and the number fits inside ten characters",
    now: "2026-01-01T00:00:00.000Z",
    route: {
      name: "dups",
      points: [
        ...Array.from({ length: 3 }, (_, i) => point(10 + i, 10, "CP")),
        ...Array.from({ length: 11 }, (_, i) => point(20 + i, 20, "ABCDEFGHIJK")),
      ],
    },
  },
  {
    name: "a name already numbered collides with a generated number",
    now: "2026-01-01T00:00:00.000Z",
    route: { name: "collide", points: [point(1, 1, "CP"), point(2, 2, "CP2"), point(3, 3, "CP")] },
  },
  {
    name: "XML-special characters in names are escaped in the comment and the route name, and dropped from identifiers",
    now: "2026-01-01T00:00:00.000Z",
    route: { name: "A&B <C> \"D\"", points: [point(34.5, -84.5, "R&D <site>"), point(34.6, -84.6, "it's \"quoted\"")] },
  },
  {
    name: "the route name is uppercased and cut to 25 characters",
    now: "2026-01-01T00:00:00.000Z",
    route: { name: "a route with a rather long name indeed", points: [point(34.5, -84.5, "A"), point(34.6, -84.6, "B")] },
  },
  {
    name: "coordinates are written with toFixed(6): rounding of the exact binary value, a tiny negative, a whole number",
    now: "2026-01-01T00:00:00.000Z",
    route: {
      name: "digits",
      points: [
        point(34.1234565, -84.1234565, "HALF"), point(0.0000005, -0.0000005, "TINY"), point(-0.0000001, 0.0000001, "NEGZERO"), point(34, -84, "WHOLE"),
        point(1.0000005, 2.0000005, "ROUND"), point(-33.9249, 18.4241, "SOUTH"), point(89.9999995, 179.9999995, "EDGE"),
      ],
    },
  },
  {
    name: "a single point",
    now: "2026-12-31T23:59:59.007Z",
    route: { name: "one", points: [point(34.5, -84.5, "ONLY")] },
  },
  {
    name: "a route with no points",
    now: "2026-01-01T00:00:00.000Z",
    route: { name: "empty", points: [] },
  },
  {
    name: "a hundred points",
    now: "2026-06-15T08:00:00.500Z",
    route: { name: "long", points: Array.from({ length: 100 }, (_, i) => point(34 + i * 0.001, -84 - i * 0.002, i % 3 === 0 ? `.PT ${i}` : undefined)) },
  },
];

const run = (c) => {
  const spy = jest.spyOn(Date.prototype, "toISOString").mockReturnValue(c.now);
  try {
    const route = saved(c.route);
    return {
      name: c.name,
      now: c.now,
      route: c.route,
      expected: {
        fpl: buildFplXml(route),
        gpx: buildGpxXml(route),
        routeString: buildForeFlightRouteString(route),
        url: buildForeFlightUrl(route),
      },
    };
  } finally {
    spy.mockRestore();
  }
};

describe("what a route is handed to another app as", () => {
  it("is what the fixture says (and the committed fixture is current)", () => {
    const document = { generatedBy: GENERATED_BY, cases: CASES.map(run) };
    const name = "routes/handoff.json";
    const text = dumps(document);
    const file = fixturePath(name);
    if (UPDATE) {
      writeFixture(name, text);
      return;
    }
    if (!fs.existsSync(file)) throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
    expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
    expect(fs.readFileSync(file, "utf8")).toBe(text);
  });
});
