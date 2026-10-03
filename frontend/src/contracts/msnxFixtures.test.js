import JSZip from "jszip";
import { buildSketchMsnxZip } from "../feature/msnxImport/createMsnx";
import { applyPlanToMsnxDocs, buildMsnxBlob } from "../feature/msnxImport/mutateMsnx";
import { parseMsnxFile, readMissionAircraft } from "../feature/msnxImport/parseMsnx";
import { extractLegPlanData } from "../feature/msnxImport/msnxDocs";
import { parseAmpsAirspeed, parseAmpsClock, parseAmpsMeters, parseAmpsWind } from "../feature/msnxImport/ampsParse";
import { defaultRoutePlan, planPoints } from "../feature/msnxImport/routeCalc";

const fs = require("fs");
const path = require("path");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// AMPS mission files (.msnx) the web app reads, and files it writes. Each case is a
// real package: the web's own export, or the bundled template edited the way the app
// does. The expected answer is what parseMsnxFile reads out of it, which is what the
// native apps must read out of the same bytes.
//
// Verified two ways: the committed bytes still parse to the recorded answer, and
// rebuilding the file with today's web exporter still does. The second is what notices
// the exporter changing.
//
// Files are cut down to the five parts the reader uses, plus the three the writer changes (the template's 650 KB .vidx
// vehicle model is never read), about 100 KB each.

jest.setTimeout(180000);

const TEMPLATE = fs.readFileSync(path.join(__dirname, "../../public/msnx_template.msnx"));
const KEEP = [
  "mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml", "mission/vehicles.xml",
  // The three parts only the writer touches (the reader does not read them), so a native writer can be held to every part it changes.
  "mission/routes.xml", "mission/mission.xml", "mission/missionsummary.xml",
];

const reduce = async (bytes) => {
  const source = await JSZip.loadAsync(bytes);
  const reduced = new JSZip();
  const date = new Date(Date.UTC(2026, 9, 2));
  for (const name of KEEP) {
    reduced.file(name, await source.file(name).async("uint8array"), { date });
  }
  return reduced.generateAsync({ type: "nodebuffer", compression: "DEFLATE", compressionOptions: { level: 9 } });
};

// The exporter names every point, leg and route with crypto.randomUUID(). Stubbed
// while building, so the ids in the file (and in the expected answer) can be reproduced.
const withDeterministicIds = async (build) => {
  let counter = 0;
  const hadCrypto = Object.prototype.hasOwnProperty.call(window, "crypto");
  const original = window.crypto;
  Object.defineProperty(window, "crypto", {
    configurable: true,
    value: { randomUUID: () => `00000000-0000-4000-8000-${String((counter += 1)).padStart(12, "0")}` },
  });
  try {
    return await build();
  } finally {
    if (hadCrypto) Object.defineProperty(window, "crypto", { configurable: true, value: original });
    else delete window.crypto;
  }
};

const point = (id, lat, lon, kind, ptType, name) => ({ id, lat, lon, kind, ptType, name });

const SKETCH = {
  name: "FIXTURE ROUTE",
  points: [
    point("s1", 34.5, -84.2, "amps", "turn", ".SP"),
    point("sh1", 34.52, -84.15, "shaping", null, ""),
    point("c1", 34.55, -84.05, "amps", "ip", ".IP1"),
    point("sh2", 34.58, -84.02, "shaping", null, ""),
    point("sh3", 34.6, -84.0, "shaping", null, ""),
    point("t1", 34.65, -83.95, "amps", "target", ".LZ1"),
  ],
  elevations: { s1: 1200, c1: 1500, t1: 1350 },
  plan: {
    ...defaultRoutePlan(),
    airspeed: { value: 110, type: "indicated" },
    altitude: { value: 500, ref: "agl" },
    wind: { dirTrue: 270, speedKts: 15 },
    tempC: 25,
    date: "2026-07-15",
    perPoint: {
      c1: { altitude: { value: 1800, ref: "msl" }, wind: { dirTrue: 300, speedKts: 20 } },
      t1: { clock: "10:00:00" },
    },
  },
};

const SECOND = {
  name: "SECOND ROUTE",
  points: [
    point("a1", 34.4, -84.3, "amps", "turn", ".SP"),
    point("a2", 34.45, -84.1, "amps", "turn", ".CP1"),
    point("a3", 34.5, -83.9, "amps", "target", ".PZ1"),
  ],
  elevations: {},
  plan: { ...defaultRoutePlan(), airspeed: { value: 90, type: "ground" }, altitude: { value: 200, ref: "agl" }, date: "2026-07-15" },
};

// The edges the two sketches above do not reach: a point with no name, no kind or an unknown type, shaping points before the first and after the last AMPS
// point (dropped), the equator and the meridian, a southern and an eastern hemisphere, special characters in names, a charted elevation, an unknown airspeed
// type, MSL altitudes, and clock times either side of noon and midnight (and into the next day).
const EDGES = {
  name: 'R&D <TEST> "edges"',
  points: [
    point("e0", 34.1, -84.1, "shaping", null, ""),
    { ...point("e1", 0, 0, "amps", "weird", ""), ele: 12.5 },
    { ...point("e2", -12.5, -84.2, "shaping", null, ""), ele: 7 },
    { id: "e3", lat: -12.4, lon: 20.5, name: ".A&B" },
    { ...point("e4", -12.3, 20.6, "amps", "target", ".LZ2"), chartElevationFt: 800 },
    point("e5", -12.2, 20.7, "shaping", null, ""),
  ],
  elevations: { e1: 500, e3: 1500 },
  plan: {
    ...defaultRoutePlan(),
    airspeed: { value: 95, type: "true" },
    altitude: { value: 300, ref: "msl" },
    wind: { dirTrue: 90, speedKts: 5 },
    date: "2026-12-31",
    perPoint: {
      e3: { airspeed: { value: 70, type: "weird" }, altitude: { value: 200, ref: "agl" } },
      e1: { clock: "23:50:00" },
    },
  },
};

// Short legs at 60 knots (a minute a mile, give or take a rounding) so the clock walks across noon, and across midnight into the next day.
const clockWalk = (name, first) => ({
  name,
  points: [0, 1, 2, 3].map((i) => point(`${name[0]}${i}`, 34.5 + i * 0.05, -84.2, "amps", i === 3 ? "target" : "turn", `.${name[0]}${i}`)),
  elevations: {},
  plan: { ...defaultRoutePlan(), airspeed: { value: 60, type: "ground" }, wind: { dirTrue: 0, speedKts: 0 }, date: "2026-07-15", perPoint: { [`${name[0]}0`]: { clock: first } } },
});
const NOON = clockWalk("NOON", "11:58:30");
const MIDNIGHT = clockWalk("MIDNIGHT", "23:58:30");

const CASES = [
  {
    name: "template",
    description: "The bundled AMPS template (frontend/public/msnx_template.msnx), an AMPS-authored mission.",
    build: () => reduce(TEMPLATE),
  },
  {
    name: "anonymous-points",
    description: "Two GPX route points with no AMPS point behind them: they keep distinct UI ids and are not given invented AMPS ids.",
    build: async () => {
      const zip = await JSZip.loadAsync(TEMPLATE);
      const gpx = await zip.file("mission.gpx").async("string");
      let remaining = 2;
      zip.file("mission.gpx", gpx.replace(/<msnx:point\b[\s\S]*?<\/msnx:point>/g, (block) => {
        if (remaining === 0) return block;
        remaining -= 1;
        return "";
      }));
      return reduce(await zip.generateAsync({ type: "nodebuffer" }));
    },
  },
  {
    name: "plan-edited",
    description: "The template with one arrival point's plan edited and written back by the web (applyPlanToMsnxDocs).",
    build: async () => {
      const { zip, docs, routes } = await parseMsnxFile(TEMPLATE);
      const route = routes[0];
      const target = planPoints(route)[1];
      route.plan.perPoint[target.id] = {
        ...route.plan.perPoint[target.id],
        airspeed: { value: 123, type: "ground" },
        altitude: { value: 1500, ref: "msl" },
        wind: { dirTrue: 250, speedKts: 20 },
      };
      applyPlanToMsnxDocs(docs, route);
      await buildMsnxBlob(zip, docs);
      return reduce(await zip.generateAsync({ type: "nodebuffer" }));
    },
  },
  {
    name: "sketch-export",
    description: "A sketched route exported by the web: shaping points, an IP and a target, indicated airspeed, winds and a time-on-target.",
    build: () => withDeterministicIds(async () => reduce(
      await (await buildSketchMsnxZip(TEMPLATE, [SKETCH], "Fixture mission")).generateAsync({ type: "nodebuffer" }),
    )),
  },
  {
    name: "sketch-edges",
    description: "Sketched routes with the cases the others do not reach: unnamed points, an unknown type, dropped shaping points, both hemispheres, special characters, MSL, and clock times around noon and midnight.",
    build: () => withDeterministicIds(async () => reduce(
      await (await buildSketchMsnxZip(TEMPLATE, [EDGES, NOON, MIDNIGHT], 'Edges & <more>')).generateAsync({ type: "nodebuffer" }),
    )),
  },
  {
    name: "sketch-two-routes",
    description: "Two sketched routes in one mission.",
    build: () => withDeterministicIds(async () => reduce(
      await (await buildSketchMsnxZip(TEMPLATE, [SKETCH, SECOND], "Two routes")).generateAsync({ type: "nodebuffer" }),
    )),
  },
];

// JSON has no NaN or undefined; an absent value is written null.
const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (v === undefined ? null : v)));

const summarise = ({ routes, aircraft }) => clean({
  aircraft,
  routes: routes.map(({ name, segmentId, points, plan, elevations }) => ({ name, segmentId, points, plan, elevations })),
});


// The AMPS attribute value formats, and the little readers around them. Inputs are the
// documented examples plus the edges a reverse-engineered format invites: rounding on a
// half, the 12 o'clock boundaries, case, spacing, and text that is not the format at all.
const AIRSPEEDS = [
  "raw 80 Ground Knot", "raw 40.5 Ground Knot", "raw 99.5 Indicated Knot", "RAW 120 true KNOT", "raw 0 Ground Knot",
  "raw 100.4 Indicated Knot", "80 Ground Knot", "raw 80 Ground", "raw eighty Ground Knot", "", null, "raw 1e3 Ground Knot",
  "raw 82.5  True  Knot",
];
const WINDS = [
  "0 T/0 m/s", "270 T/10.5 m/s", "270T/10.5m/s", "-90 T/5 m/s", "359.5 T/2.5 m/s", "180 t / 7.7 M/S",
  "90 T/0.257222 m/s", "090 T/10 m/s", "windy", "", null, "270 T/ m/s", "45.5 T/12.9 m/s",
];
const METERS = [
  "341.0712 MM", "325.8312 m DAFIF", "-12.5 m User", "924.7632000000001 m User", "0", "MM 5", "", null, "abc",
  "1,000 m", ".5 m", "12.5.5 m", "3.280839895 m", "1000.4 MM",
];
const CLOCKS = [
  "9/17/2025 12:00:00.0000 AM", "9/17/2025 1:30:00.0000 PM", "1/7/2026 6:24:09.7698 PM", "12/31/2026 12:59:59 PM",
  "7/4/2026 11:59:59.9999 PM", "7/4/2026 12:00:00 PM", "7/4/2026 12:00:00 AM", "07/04/2026 09:05:07 am",
  "7/4/2026 9:05 PM", "not a time", "", null, "13/45/2026 25:61:61 PM", "7/4/26 9:05:07 AM",
];
const PARSE_FLOATS = [
  "12.5px", "", "   42  ", ".5", "-3", "abc", "1e3", "5.", "+7.25", "-.5e-2x", "0x10", "1,5", "34.66431", "-84.12792",
  "  \n9.5", "Infinity", "--5", "3.14.15", "1e", "e5",
];
const VEHICLES = [
  "<vehicles><vehicle><vehicledescription>Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L</vehicledescription></vehicle></vehicles>",
  "<vehicledescription>  Air:Rotary Wing:CH47:1:Default:1.0:CH-47F  </vehicledescription>",
  "<VEHICLEDESCRIPTION>Air:Rotary Wing:H60:1:x:1:UH-60M</VEHICLEDESCRIPTION>",
  "<vehicledescription></vehicledescription>", "<vehicledescription>NoColons</vehicledescription>",
  "<vehicledescription>trailing:</vehicledescription>", "<other/>", "", null,
];
const LEGS = [
  "<legs><leg><id>L1</id><endpt>P2</endpt><key>AirspeedValue</key><attribute><value>raw 80 Ground Knot</value></attribute>"
    + "<key>CruiseWind</key><attribute><value>270 T/5 m/s</value></attribute></leg></legs>",
  "<legs><leg><id>A</id><endpt>X</endpt></leg><leg><id>B</id><endpt>Y</endpt><key>CruiseWind</key><value>0 T/0 m/s</value></leg></legs>",
  "<leg><id></id><endpt>Z</endpt></leg><leg><endpt>NOID</endpt></leg><leg><id>K</id></leg>",
  "<leg><id>D</id><endpt>E</endpt><key>AirspeedValue</key><value>first</value><key>AirspeedValue</key><value>second</value></leg>",
  "", "no legs here",
];

const valuesFixture = () => ({
  description: "The AMPS attribute value readers in ampsParse.js (and parseFloat, which they lean on), "
    + "readMissionAircraft and extractLegPlanData in parseMsnx.js / msnxDocs.js. A value the format does not fit is null; "
    + "NaN is written null. Halves are the point of several cases: JavaScript rounds them up.",
  generatedBy: "frontend/src/contracts/msnxFixtures.test.js (UPDATE_CONTRACTS=1)",
  tolerance: 1e-9,
  airspeed: AIRSPEEDS.map((input) => ({ input, expected: clean(parseAmpsAirspeed(input)) })),
  wind: WINDS.map((input) => ({ input, expected: clean(parseAmpsWind(input)) })),
  meters: METERS.map((input) => ({ input, expected: clean(parseAmpsMeters(input)) })),
  clock: CLOCKS.map((input) => ({ input, expected: clean(parseAmpsClock(input)) })),
  parseFloat: PARSE_FLOATS.map((input) => ({ input, expected: clean(Number.isNaN(parseFloat(input)) ? null : parseFloat(input)) })),
  aircraft: VEHICLES.map((input) => ({ input, expected: clean(readMissionAircraft(input)) })),
  legPlan: LEGS.map((input) => ({ input, expected: clean(Object.fromEntries(extractLegPlanData(input))) })),
});

const read = (name) => fs.readFileSync(fixturePath(`msnx/${name}.msnx`));

describe("msnx fixtures (the web's reader and exporter)", () => {
  if (UPDATE) {
    it("writes the files and the answers", async () => {
      const cases = [];
      for (const c of CASES) {
        const bytes = await c.build();
        writeFixture(`msnx/${c.name}.msnx`, bytes);
        cases.push({ name: c.name, file: `${c.name}.msnx`, description: c.description, expected: summarise(await parseMsnxFile(bytes)) });
      }
      writeFixture("msnx/amps_values.json", dumps(valuesFixture()));
      writeFixture("msnx/parse.json", dumps({
        description: "What frontend/src/feature/msnxImport/parseMsnx.js reads out of each .msnx beside this file: "
          + "the routes (points, the inline plan, ground elevations) and the airframe. Absent values are null.",
        generatedBy: "frontend/src/contracts/msnxFixtures.test.js (UPDATE_CONTRACTS=1)",
        tolerance: 1e-9,
        cases,
      }));
    });
    return;
  }

  const recorded = JSON.parse(fs.readFileSync(fixturePath("msnx/parse.json"), "utf8"));

  it("the AMPS value fixtures still equal what the web readers answer", () => {
    const file = fixturePath("msnx/amps_values.json");
    expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(dumps(valuesFixture())));
    expect(fs.readFileSync(file, "utf8")).toBe(dumps(valuesFixture()));
  });

  it("has every case, no more and no fewer", () => {
    expect(recorded.cases.map((c) => c.name)).toEqual(CASES.map((c) => c.name));
  });

  describe.each(CASES.map((c) => [c.name, c]))("%s", (_name, c) => {
    const expected = recorded.cases.find((r) => r.name === c.name).expected;

    it("the committed file still parses to the recorded answer", async () => {
      expect(summarise(await parseMsnxFile(read(c.name)))).toEqual(expected);
    });

    it("rebuilding it with today's web code still gives the same answer", async () => {
      expect(summarise(await parseMsnxFile(await c.build()))).toEqual(expected);
    });
  });

  it("covers what the reader is for", () => {
    const byName = Object.fromEntries(recorded.cases.map((c) => [c.name, c.expected]));
    expect(byName.template.aircraft.designation).toBe("UH-60L");
    expect(byName["sketch-two-routes"].routes).toHaveLength(2);
    const sketch = byName["sketch-export"].routes[0];
    expect(sketch.points.filter((p) => p.kind === "shaping")).toHaveLength(3);
    expect(sketch.points.map((p) => p.ptType).filter(Boolean)).toEqual(expect.arrayContaining(["ip", "target"]));
    expect(byName["anonymous-points"].routes.flatMap((r) => r.points).filter((p) => !p.id)).toHaveLength(2);
  });
});
