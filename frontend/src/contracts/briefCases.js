import { compileBrief } from "../feature/brief/compile";
import { briefFormat } from "../feature/brief/templates";

// The cases of contracts/fixtures/brief/compile.json, kept apart from its test so the fixtures read from what compileBrief
// makes of them (brief/sandtable.json) are built from the same cases in the same run, whatever order Jest runs the tests in.
// Used only by the *.test.js files beside it; nothing in the app imports this.

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

export const CASES = [
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
      options: { lzCards: false, navLogs: false, strips: false, iimc: false },
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
  {
    // A gentle bend a strip holds (the shaping point), a dogleg of about 60° at BEND that ends a strip, a leg longer than a
    // strip, and an LZ/PZ and a threat by the route: where strips are cut, and what lies on each, at the second scale.
    name: "strip charts at 1:250,000: a dogleg, a gentle bend and a long leg",
    format: "amb",
    document: { options: { stripScale: 250000, lzCards: false, navLogs: false, iimc: false } },
    plan: {
      name: "DOGLEG",
      lzs: [{ id: "lz-end", diagram: diagram("LZ END", [35.62, -83.13], { elevation: "1200", landing: "045°" }) }],
      routeSets: [{
        id: "set-dog",
        name: "DOGLEG ROUTES",
        data: {
          version: 1,
          routes: [{
            ...bespin,
            id: "dog",
            name: "DOGLEG",
            color: "#30D158",
            points: [
              point("d1", "SP", "ip", 34.6, -84.3),
              shaping("d1s", 34.8, -84.17),
              point("d2", "BEND", "turn", 34.98, -84.1),
              point("d3", "DOG", "turn", 35.02, -83.7),
              point("d4", "FAR", "turn", 35.55, -83.2),
              point("d5", "END", "target", 35.62, -83.13),
            ],
            plan: plan({ d1: { clock: "09:00:00" } }),
            elevations: {},
          }],
        },
      }],
      threats: [{ name: "SA-8", lat: 35.3, lon: -83.5 }],
    },
    context: CONTEXT,
  },
  {
    // A start point given twice (a shaping point on it), a leg two and a half strips long that ends at a turn onto another
    // long leg, a threat on a strip the brief leaves out, and a scale strips are not drawn at (so 1:100,000).
    name: "strip charts: a doubled start, a long leg ending at a turn, threats left out, an unknown scale",
    format: "amb",
    document: { options: { stripScale: 50000, lzCards: false, navLogs: false, iimc: false }, include: { threats: false } },
    plan: {
      name: "LONG LEG",
      routeSets: [{
        id: "set-long",
        name: "LONG ROUTES",
        data: {
          version: 1,
          routes: [{
            ...bespin,
            id: "long",
            name: "LONG",
            color: "#BF5AF2",
            points: [
              point("l1", "SP", "ip", 34.0, -85.0),
              shaping("l1s", 34.0, -84.999999),
              point("l2", "TURN", "turn", 33.78, -84.53),
              point("l3", "END", "target", 34.0, -84.38),
            ],
            plan: plan({ l1: { clock: "10:00:00" } }),
            elevations: {},
          }],
        },
      }],
      threats: [{ name: "SA-6", lat: 33.9, lon: -84.75 }],
    },
    context: CONTEXT,
  },
  {
    // A route across the antimeridian is cut the short way, not round the world.
    name: "strip charts across the antimeridian",
    format: "amb",
    document: { options: { lzCards: false, navLogs: false, iimc: false } },
    plan: {
      name: "DATELINE",
      routeSets: [{
        id: "set-date",
        name: "DATELINE",
        data: {
          version: 1,
          routes: [{
            ...bespin,
            id: "date",
            name: "DATELINE",
            points: [point("x1", "WEST", "ip", 51.9, 179.95), point("x2", "EAST", "turn", 51.95, -179.95), point("x3", "ADAK", "target", 51.88, -179.8)],
            plan: plan({ x1: { clock: "10:00:00" } }),
            elevations: {},
          }],
        },
      }],
    },
    context: CONTEXT,
  },
  {
    // More strips than a packet holds: the first 60 are made, and setup says the rest have none.
    name: "strip charts stop at the most a packet holds",
    format: "amb",
    document: { options: { lzCards: false, navLogs: false, iimc: false } },
    plan: {
      name: "FERRY",
      routeSets: [{
        id: "set-ferry",
        name: "FERRY",
        data: {
          version: 1,
          routes: [{
            ...bespin,
            id: "ferry",
            name: "FERRY",
            points: [point("f1", "SOUTH", "ip", 30.0, -89.5), point("f2", "NORTH", "target", 42.0, -89.5)],
            plan: plan({ f1: { clock: "06:00:00" } }),
            elevations: {},
          }],
        },
      }],
    },
    context: CONTEXT,
  },
];

/** A case compiled, as the fixture holds it (JSON, so undefined is absent). */
export const compileCase = (c) => JSON.parse(JSON.stringify(compileBrief(briefFormat(c.format), c.document, c.plan, c.context)));
