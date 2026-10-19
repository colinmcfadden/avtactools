import { compileBrief, partsText, scriptText } from "../feature/brief/compile";
import { briefFormat } from "../feature/brief/templates";
import { UPDATE, compact, writeFixture } from "./fixtureIO";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/compile.json: what compileBrief makes of a plan, a format and the person's answers. The
// web is the reference (docs/MISSION_BRIEF.md §2.3); Android's core-brief is held to every case. Regenerate with
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/briefCompile
// and review the diff.

const point = (id, name, ptType, lat, lon) => ({ id, name, ptType, kind: "amps", lat, lon });
const shaping = (id, lat, lon) => ({ id, kind: "shaping", ptType: null, name: "", lat, lon });

const plan = (perPoint, overrides = {}) => ({
  aircraft: "UH-60L Black Hawk",
  aircraftProfile: "uh60l",
  airspeed: { value: 120, type: "indicated" },
  altitude: { value: 2500, ref: "msl" },
  wind: { dirTrue: 270, speedKts: 15 },
  tempC: 14,
  fuelFlowLbHr: 960,
  date: "2026-10-16",
  perPoint,
  ...overrides,
});

const BLUEBIRD = [34.6012, -84.0523];
const HAWK = [34.5501, -84.1102];
const IBIS = [34.5702, -84.1056];
const BUCEES = [34.4801, -84.0412];
const DECOY = [34.6501, -84.2001];

const alderaan = {
  id: "r-alderaan",
  name: "ALDERAAN",
  color: "#FF453A",
  visible: true,
  points: [
    point("a1", ".3MILE", "ip", 33.9151, -84.5163),
    shaping("a1s", 34.0, -84.45),
    point("a2", "TRACK", "turn", 34.1502, -84.3511),
    point("a3", "S-EP", "turn", 34.3204, -84.2408),
    point("a4", "ACP2", "turn", 34.5002, -84.1202),
    point("a5", ".RP1", "ip", 34.5803, -84.0702),
    point("a6", "BLUEBIRD", "target", BLUEBIRD[0], BLUEBIRD[1]),
    point("a7", "HAWK", "target", HAWK[0], HAWK[1]),
  ],
  plan: plan({
    a1: { clock: "11:56:32" },
    a3: { altitude: { value: 50, ref: "agl" }, airspeed: { value: 100, type: "indicated" } },
    a4: { altitude: { value: 50, ref: "agl" }, airspeed: { value: 100, type: "indicated" } },
    a5: { altitude: { value: 50, ref: "agl" }, airspeed: { value: 100, type: "indicated" } },
    a6: { altitude: { value: 50, ref: "agl" }, airspeed: { value: 60, type: "indicated" } },
    a7: { altitude: { value: 50, ref: "agl" }, airspeed: { value: 60, type: "indicated" } },
  }),
  elevations: { a1: 1068, a2: 1150, a3: 1420, a4: 1980, a5: 2010, a6: 2104, a7: 2230 },
};

const bespin = {
  id: "r-bespin",
  name: "BESPIN",
  color: "#0A84FF",
  visible: true,
  points: [
    point("b1", ".SP3", "ip", 34.5598, -84.1201),
    point("b2", "RP5", "ip", 34.5651, -84.1101),
    point("b3", "IBIS", "target", IBIS[0], IBIS[1]),
    point("b4", "KMGE", "target", 33.9151, -84.5163),
  ],
  plan: plan({
    b1: { clock: "16:02:57" },
    b3: { airspeed: { value: 60, type: "indicated" }, altitude: { value: 50, ref: "agl" } },
  }),
  elevations: {},
};

const diagram = (name, [lat, lon], { mgrs = "", elevation = "", landing = null, takeoff = null, aircraft = 0, goAround = null } = {}) => ({
  schemaVersion: 2,
  name,
  target: { lat, lon, mgrs },
  flightData: { ...(landing ? { landing_hdg: landing } : {}), ...(takeoff ? { takeoff_hdg: takeoff } : {}) },
  analysis: { customLZ: null, detectedLZ: [[lat - 0.001, lon - 0.001], [lat + 0.001, lon - 0.001], [lat + 0.001, lon + 0.001]], results: { status: "success" }, gridElevation: elevation, latLong: "" },
  graphics: {
    doghouses: [],
    helicopters: Array.from({ length: aircraft }, (_, i) => ({ id: `h${i + 1}`, lat, lon, heading: 0, profileId: "uh60l" })),
    pzMarkers: [],
    sectorsOfFire: [],
    goArounds: goAround ? [{ id: "g1", side: goAround }] : [],
    units: [],
  },
});

const OP_DK = {
  name: "OP DK",
  missionAircraft: "uh60l",
  aircraftProfiles: [{ id: 1, slug: "uh60l", name: "UH-60L Black Hawk", designation: "UH-60L" }],
  lzs: [
    { id: "lz-bluebird", diagram: diagram("LZ BLUEBIRD", BLUEBIRD, { mgrs: "16S GD 71020 49660", elevation: "2104", landing: "306°", takeoff: "126°", aircraft: 4, goAround: "right" }) },
    { id: "lz-hawk", diagram: diagram("LZ HAWK", HAWK, { mgrs: "16S GD 59833 44327", elevation: "2230", landing: "203°", takeoff: "157°", aircraft: 2, goAround: "R" }) },
    { id: "farp-bucees", diagram: diagram("FARP BUCEES", BUCEES, { elevation: "1890", landing: "340°" }) },
    { id: "pz-ibis", diagram: diagram("PZ IBIS", IBIS, { elevation: "2150", landing: "264°", aircraft: 4, goAround: "left" }) },
    { id: "lz-decoy", diagram: diagram("LZ DECOY", DECOY, {}) },
  ],
  routeSets: [{ id: "set-opdk", name: "OP DK ROUTES", data: { version: 1, routes: [alderaan, bespin] } }],
  threats: [{ name: "ZSU-23-4", lat: 34.4012, lon: -84.0011 }],
};

const CONTEXT = {
  generatedAt: "2026-10-16T18:02:00Z",
  utcOffsetMinutes: -240,
  weather: {
    departure: { station_id: "KMGE", raw_metar: "KMGE 161553Z 27008KT 10SM FEW045 18/09 A3002", pressure: 30.02, kind: "observation" },
    arrival: { station_id: "KMGE", raw_taf: "FM161600 27010KT P6SM SCT050", kind: "forecast" },
    stops: { "lz-hawk": { station_id: "KJZP", raw_metar: "KJZP 161553Z 24006KT 10SM CLR 17/08 A3001", wind_spd_kts: 6, wind_dir: 240 } },
  },
  notams: { Obstruction: ["!FDC 6/1234 CRANE 340FT AGL 3NM N", "!FDC 6/2222 TOWER LGT OTS"], Airspace: ["!ZTL 10/044 TFR"] },
};

const AMB_ANSWERS = {
  "1.b": { value: "Moderate" },
  "2.b": { value: "500-1 day" },
  "2.d": { value: "11:15, PC chalk 1" },
  "3.a": { value: "Insert two teams at LZ BLUEBIRD and LZ HAWK, then extract them from PZ IBIS." },
  "4.b.vii": { mode: "standard" },
  "4.e.ii@lz:lz-hawk": { value: "trail" },
  "4.e.iii@lz:lz-hawk": { value: "" },
  "5.a.i": { mode: "custom", value: "Lead calls it; old lead takes trail." },
  "5.a.iii": { mode: "standard" },
  "5.a.v": { mode: "standard", fields: { baseAltitude: "4,000 ft MSL", baseHeading: "the mountain heading", baseAirspeed: "100 KIAS" } },
  "5.a.vii": { mode: "standard" },
  "7.a": { value: "17:30" },
};

const CASES = [
  {
    name: "OP DK, air movement brief, the flow from the routes",
    format: "amb",
    document: {
      answers: AMB_ANSWERS,
      rallyPoints: [{ ref: "route:set-opdk/r-alderaan", pointId: "a4", label: "inbound" }, { ref: "route:set-opdk/r-bespin", pointId: "b2", label: "outbound" }],
      scripts: { 3: "Insert the teams, extract them, everyone home." },
    },
    plan: OP_DK,
    context: CONTEXT,
  },
  {
    name: "OP DK, hasty brief, the person's flow and roles",
    format: "hasty",
    document: {
      flow: [
        { ref: "route:set-opdk/r-alderaan" },
        { ref: "lz:lz-decoy", role: "FALSE_LZ" },
        { ref: "lz:lz-bluebird", role: "LZ" },
        { ref: "lz:lz-hawk", role: "LAAGER" },
        { ref: "lz:farp-bucees", role: "FARP" },
        { ref: "route:set-opdk/r-bespin" },
        { ref: "lz:pz-ibis", role: "PZ" },
      ],
      answers: { "8.a": { value: "12:45" }, "26.d": { mode: "standard", fields: { baseAltitude: "4,000 ft MSL" } }, "12.n@lz:lz-bluebird": { mode: "standard" } },
      constants: [{ id: "enrouteAirspeed" }, { id: "terrainFlightAirspeed", value: "100 KIAS" }, { id: "custom-1", label: "Formation", value: "Stag right, 3-5 disks" }],
    },
    plan: OP_DK,
    context: CONTEXT,
  },
  {
    name: "a flow naming what has gone, threats left out, no appendices",
    format: "amb",
    document: {
      flow: [{ ref: "route:set-opdk/r-alderaan" }, { ref: "lz:gone-lz", role: "LZ" }, { ref: "lz:lz-hawk", role: "BOGUS" }],
      include: { threats: false },
      options: { lzCards: false, navLogs: false, iimc: false },
      answers: { "5.a.v": { mode: "standard", fields: {} } },
    },
    plan: OP_DK,
    context: { ...CONTEXT, weather: { departure: { station_id: "TIMEOUT", raw_metar: null } }, notams: "NOTAMs currently unavailable." },
  },
  {
    name: "an empty plan: every value a write-in, no light without a place",
    format: "amb",
    document: {},
    plan: { name: "" },
    context: { generatedAt: "2026-10-16T03:30:00Z", utcOffsetMinutes: -240 },
  },
  {
    name: "a route with one named point, and an LZ no route reaches",
    format: "amb",
    document: {},
    plan: {
      name: "SHORT",
      lzs: [{ id: "lone", diagram: diagram("LZ LONE", [35.0, -85.0], { elevation: "900" }) }],
      routeSets: [{ id: "s", name: "S", data: { version: 1, routes: [{ ...bespin, id: "one", name: "ONE", points: [point("x1", "ONLY", "ip", 34.0, -84.0), shaping("x2", 34.1, -84.1)] }] } }],
    },
    context: CONTEXT,
  },
];

const compileCase = (c) => JSON.parse(JSON.stringify(compileBrief(briefFormat(c.format), c.document, c.plan, c.context)));

const FIXTURE = "brief/compile.json";

describe("compileBrief fixtures", () => {
  if (UPDATE) {
    it("writes contracts/fixtures/brief/compile.json", () => {
      const cases = CASES.map((c) => ({ ...c, expected: compileCase(c) }));
      writeFixture(FIXTURE, `${compact({ generatedBy: "frontend/src/contracts/briefCompileFixtures.test.js (UPDATE_CONTRACTS=1)", cases })}\n`);
    });
    return;
  }

  const fixture = readFixture(FIXTURE);

  it("has every case this test builds, with the same inputs", () => {
    expect(fixture.cases.map(({ expected, ...inputs }) => inputs)).toEqual(JSON.parse(JSON.stringify(CASES)));
  });

  it.each(fixture.cases.map((c) => [c.name, c]))("%s", (_name, c) => {
    expect(compileCase(c)).toEqual(c.expected);
  });
});

// What the fixture cannot show at a glance: the rules a brief must keep, asked of the compiled cases directly.
describe("compileBrief", () => {
  const amb = compileCase(CASES[0]);
  const hasty = compileCase(CASES[1]);
  const step = (model, id) => model.steps.find((s) => s.id === id);

  it("briefs the routes and the LZ/PZs in the order the flight meets them", () => {
    expect(amb.steps.map((s) => s.id)).toEqual([
      "1", "2", "3", "4.a", "4.b",
      "4.c@route:set-opdk/r-alderaan", "4.e@lz:lz-bluebird", "4.e@lz:lz-hawk",
      "4.c@route:set-opdk/r-bespin", "4.d@lz:pz-ibis",
      "4.f@lz:farp-bucees", "4.e@lz:lz-decoy",
      "5.a", "5.b", "6", "7", "8", "9",
    ]);
  });

  it("gives every step a page, the first the cover, then the appendices", () => {
    expect(amb.steps.map((s) => s.page)).toEqual(amb.steps.map((_, i) => i + 1));
    expect(amb.pages[0].kind).toBe("cover");
    expect(amb.pages.filter((p) => p.kind === "lzCard")).toHaveLength(5);
    expect(amb.pages.filter((p) => p.kind === "navLog")).toHaveLength(2);
    expect(amb.pages[amb.pages.length - 1].kind).toBe("iimc");
    // The packet by section, never by page: Chromium decides where pages break.
    expect(scriptText(step(amb, "1")).join(" ")).toContain("The packet holds the brief (18 steps), 5 LZ/PZ cards, 2 nav logs and the IIMC card");
    expect(scriptText(step(amb, "1")).join(" ")).not.toMatch(/\d+–\d+/);
  });

  it("finds what the plan holds", () => {
    const hawk = step(amb, "4.e@lz:lz-hawk");
    const fact = (key) => hawk.facts.find((f) => f.key === key).value;
    expect(fact("stop.landingHeading")).toBe("203°");
    expect(fact("stop.elevation")).toBe("2,230 ft MSL");
    expect(fact("stop.goAround")).toBe("right");
    expect(hawk.items.find((i) => i.id === "4.e.i").text).toBe("16S GD 59833 44327");
    expect(step(amb, "4.b").items.find((i) => i.id === "4.b.i").text).toBe("11:31");
    expect(amb.constants.find((c) => c.id === "spOutboundAirspeed")).toMatchObject({ value: "60 KIAS", source: "standard" });
  });

  it("prints a blank as a write-in, in the item and in the script", () => {
    const hawk = step(amb, "4.e@lz:lz-hawk");
    expect(hawk.items.find((i) => i.id === "4.e.iii")).toMatchObject({ writeIn: true, text: null });
    expect(hawk.script.some((s) => s.some((p) => p.writeIn === "iii"))).toBe(true);
  });

  it("writes a standard procedure with the person's fields, and their own words for a custom one", () => {
    const contingencies = scriptText(step(amb, "5.a")).join(" ");
    expect(contingencies).toContain("Base altitude 4,000 ft MSL, adjusted 500 feet");
    expect(contingencies).toContain("Lead calls it; old lead takes trail.");
    expect(contingencies).toContain("Rally points are ACP2 (inbound), RP5 (outbound).");
    expect(contingencies).toContain("Bump plan: ________");
  });

  it("keeps an edited script as the person wrote it", () => {
    expect(step(amb, "3")).toMatchObject({ scriptEdited: true, script: [[{ text: "Insert the teams, extract them, everyone home." }]] });
  });

  it("briefs sections that share a step together, once per stop", () => {
    const lz = hasty.steps.filter((s) => s.sections.join() === "12,13");
    expect(lz.map((s) => s.ref)).toEqual(["lz:lz-bluebird"]);
    expect(hasty.steps.find((s) => s.id === "laager@lz:lz-hawk")).toBeTruthy();
    expect(hasty.steps.find((s) => s.id === "false@lz:lz-decoy")).toBeTruthy();
  });

  it("says what it could not brief", () => {
    const gone = compileCase(CASES[2]);
    expect(gone.flow[1]).toEqual({ ref: "lz:gone-lz", gone: true });
    expect(gone.problems.map((p) => p.ref)).toContain("lz:gone-lz");
    const short = compileCase(CASES[4]);
    expect(short.problems.map((p) => p.message).join(" ")).toMatch(/ONE needs at least two route points/);
    expect(short.problems.map((p) => p.message).join(" ")).toMatch(/LZ LONE, so it has no arrival time|reaches LZ LONE/);
  });

  it("takes nothing from the device's clock or zone", () => {
    const empty = compileCase(CASES[3]);
    expect(empty.mission.date).toBe("15 Oct 2026");
    expect(empty.steps.find((s) => s.id === "2").items.find((i) => i.id === "2.c").writeIn).toBe(true);
    expect(partsText([{ text: "a " }, { writeIn: "x" }])).toBe("a ________");
  });
});
