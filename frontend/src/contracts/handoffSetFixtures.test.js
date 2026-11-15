import { buildGpxXml, buildSetGpxXml, exportableRoutes } from "../feature/msnxImport/foreflight";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What a SET of routes is handed to another app as: one GPX file with a <rte> for each of its routes, in the set's order. Each <rte> is what
// routes/handoff.json says of that route alone, so this file pins only what a set adds: the order, which routes are left out (one of fewer than two
// points is no line), that a route hidden on the map is written like any other, and that identifiers are numbered within a route and never across the
// file. A Garmin flight plan and ForeFlight's URL hold one route, so a set has neither.
//
// A file of its own, not more cases in handoff.json: every case there is one route with a flight plan, a route string and a URL, and the native tests
// read every case that way.

const GENERATED_BY = "frontend/src/contracts/handoffSetFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));

const point = (lat, lon, name) => (name === undefined ? { lat, lon } : { lat, lon, name });

const ARIEL = { name: "ARIEL", points: [point(34.78382, -84.08219, ".SP"), point(34.8, -84.1), point(34.9, -84.2, "CP 1"), point(35.0, -84.3, ".TGT")] };
const BEEMIN = { name: "BEEMIN", points: [point(35.0, -84.3, ".TGT"), point(34.95, -84.4, "RP"), point(34.78382, -84.08219, ".LZ HAWK")] };

const CASES = [
  {
    name: "two routes, in the order listed",
    routes: [ARIEL, BEEMIN],
  },
  {
    name: "the same routes the other way round",
    routes: [BEEMIN, ARIEL],
  },
  {
    name: "a set of one route is that route's own file",
    routes: [ARIEL],
  },
  {
    name: "a route hidden on the map is written like any other",
    routes: [{ ...ARIEL, visible: false }, { ...BEEMIN, visible: true }],
  },
  {
    name: "a route of fewer than two points is left out, and the rest keep their order",
    routes: [{ name: "ONE POINT", points: [point(34.5, -84.5, "ONLY")] }, ARIEL, { name: "NO POINTS", points: [] }, BEEMIN],
  },
  {
    name: "names needing XML escaping, in two routes",
    routes: [
      { name: "A&B <C> \"D\"", points: [point(34.5, -84.5, "R&D <site>"), point(34.6, -84.6, "it's \"quoted\"")] },
      { name: "</rte><rte>", points: [point(1, 1, "<name>"), point(2, 2, "&amp;")] },
    ],
  },
  {
    name: "identifiers are numbered within a route, never across the file",
    routes: [
      { name: "FIRST", points: [point(1, 1, "CP"), point(2, 2, "CP"), point(3, 3)] },
      { name: "SECOND", points: [point(4, 4, "CP"), point(5, 5), point(6, 6, "CP")] },
    ],
  },
  {
    name: "two routes whose names are the same once cut to 25 characters",
    routes: [
      { name: "a route with a rather long name indeed", points: [point(34.5, -84.5, "A"), point(34.6, -84.6, "B")] },
      { name: "A ROUTE WITH A RATHER LONG NAME AS WELL", points: [point(34.7, -84.7, "C"), point(34.8, -84.8, "D")] },
    ],
  },
  {
    name: "no route of two points: a file with no route in it",
    routes: [{ name: "ONE POINT", points: [point(34.5, -84.5, "ONLY")] }],
  },
  {
    name: "no routes",
    routes: [],
  },
];

const run = (c) => {
  const routes = saved(c.routes);
  return {
    name: c.name,
    routes: c.routes,
    expected: {
      // Which of `routes` the file holds, by their place in the list.
      written: exportableRoutes(routes).map((route) => routes.indexOf(route)),
      gpx: buildSetGpxXml(routes),
    },
  };
};

describe("what a set of routes is handed to another app as", () => {
  it("is what the fixture says (and the committed fixture is current)", () => {
    const document = { generatedBy: GENERATED_BY, cases: CASES.map(run) };
    const name = "routes/handoff-set.json";
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

  it("a set of one route is byte for byte what handoff.json says of that route", () => {
    const single = JSON.parse(fs.readFileSync(fixturePath("routes/handoff.json"), "utf8"));
    const routable = single.cases.filter((c) => c.route.points.length >= 2);
    expect(routable.length).toBeGreaterThanOrEqual(8);
    routable.forEach((c) => expect(buildSetGpxXml([c.route])).toBe(c.expected.gpx));
  });

  it("every <rte> of a set is that route's own, in the set's order", () => {
    // The part of a route's own file between the <gpx> tags.
    const body = (route) => buildGpxXml(route).split("\n").slice(2, -2).join("\n");
    CASES.forEach((c) => {
      const written = exportableRoutes(c.routes);
      const lines = buildSetGpxXml(c.routes).split("\n");
      expect(lines.slice(2, -2).join("\n")).toBe(written.map(body).join("\n"));
    });
  });
});
