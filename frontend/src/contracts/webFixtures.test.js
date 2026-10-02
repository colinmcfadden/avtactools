import {
  formatDecimal,
  looksLikeCoordinateText,
  looksLikeMgrs,
  parseCoordinate,
} from "../utils/coordParse";
import { calculateUH60Capacity } from "../utils/helicopterCapacity";
import { convertToLatLongString } from "../utils/Helpers";
import { lzAreaAndCapacity, slopeStatusFor } from "../components/MissionSummary";
import {
  FALLBACK_PROFILE,
  capacityForArea,
  centerSpacingFt,
  centerSpacingM,
  edgeGapFt,
  findProfile,
  isSeparationViolation,
  matchProfileToAircraft,
  normalizeProfile,
  pairSeparation,
  profileForAsset,
  rotorRadiusFt,
  rotorRadiusM,
  spotSizeSqFt,
  tipClearanceFt,
  tipClearanceM,
} from "../feature/aircraft/aircraftProfiles";
import {
  computeRoutePlan,
  defaultRoutePlan,
  distanceNm,
  ensureRoutePlan,
  formatClock,
  formatDuration,
  iasToTas,
  solveWindTriangle,
  trueCourseDeg,
} from "../feature/msnxImport/routeCalc";
import {
  canAnalyzeLzDiagram,
  canEditLzDiagramGraphics,
  createInitialLzWorkspace,
  createLzDiagramFromTarget,
  normalizeLegacyLzSnapshot,
  normalizeLzDiagram,
  normalizeLzTarget,
  serializeLzDiagram,
} from "../feature/lzWorkspace/useLzWorkspace";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// The web app is the reference implementation for everything here: coordinate
// parsing, aircraft geometry, capacity, separation and route planning. These
// fixtures record what it answers so the Android and iOS apps can be held to the
// same numbers. (MGRS is checked against PyGeodesy instead; see mgrsFixtures.)
// Regenerating them: see fixtureIO.js.

const settle = (name, document) => {
  const file = fixturePath(name);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  // Compare parsed values first so a mismatch reports the case, not a wall of text.
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

const GENERATED_BY = "frontend/src/contracts/webFixtures.test.js (UPDATE_CONTRACTS=1)";

/* -------------------------------------------------------------------------
 * Coordinate text
 * ---------------------------------------------------------------------- */

// Dahlonega, GA — 34°32'44.4"N 084°07'24.4"W — written every way it arrives,
// then the things that must be refused.
const PARSE_TEXTS = [
  // decimal degrees
  "34.5457, -84.1234", "34.5457,-84.1234", "34.5457 -84.1234",
  "  34.5457 ,  -84.1234  ", "34.5457N 84.1234W", "N34.5457 W84.1234",
  "34.5457° N, 84.1234° W", "34.5457°N 084.1234°W", "+34.5457, -84.1234",
  "34.5457\t-84.1234", "34.5457\n-84.1234", "34.5457; -84.1234", "34.5457 / -84.1234",
  // degrees and decimal minutes
  "34°32.740'N 084°07.407'W", "N34°32.740' W084°07.407'", "34 32.740 N, 84 07.407 W",
  "34°32.740′N 084°07.407′W", "34 32.740N 84 07.407W", "N34°32.74' W084°07.41'",
  // degrees, minutes, seconds
  "34°32'44.4\"N 84°07'24.4\"W", "34°32'44.4″N 84°07'24.4″W", "34 32 44.4 N, 84 07 24.4 W",
  "N34 32 44.4 W084 07 24.4", "34°32'44.4\"N, 84°07'24.4\"W", "34°32’44.4”N 84°07’24.4”W",
  // packed flight-plan forms
  "3432.740N 08407.407W", "343244N 0840724W", "0034 -0084",
  // axis assignment
  "-84.1234, 34.5457", "34.5457 W084.1234", "W084.1234 N34.5457", "84.1234W 34.5457N",
  "S34.5457 E084.1234", "34.5457S, 84.1234E", "34N 084W",
  // things that are not coordinate pairs
  "", "   ", "34.5457", "34.5457 -84.1234 12.3", "Dahlonega", "hello world",
  "34N 84N", "91.0, -84.1234", "34.5457, -181.5", "34 75.5 N, 84 07 W",
  "34 32 61 N, 84 07 24 W", "0, 0", "-90, 180", "90.0001, 0", "0, 180.0001",
  // a grid is never reinterpreted as a coordinate
  "16S GC 28864 55349", "16SGC2886455349", "18T WL 123 456", "4QFJ12345678",
  "16S GC", "16sgc2886455349",
  // hemisphere letters after the value, on the equator and the meridian
  "0.5N 0.5E", "0.5S 0.5W", "N0 E0",
];

const MGRS_TEXTS = [
  "16S GC 28864 55349", "16SGC2886455349", "18T WL 123 456", "4QFJ12345678",
  "16S GC", "16sgc2886455349", "34N 084W", "34.5457, -84.1234",
  "16I GC 12345 67890", "", "16S", "16SGC12345678901", "1SGC", "99ZGC12",
  "16 S GC 28864 55349",
];

const COORDINATE_TEXTS = [
  "34.", "34.5, -", "34.5457, -84.1234", "34N 84W", "34 N", "16S GC 28864 55349",
  "N34", "", "  ", "hello", "34 32 44.4 N", "34°", "W", "N", "34S",
];

const coordinateFixture = () => ({
  description: "Free text to a latitude/longitude, as frontend/src/utils/coordParse.js reads it. "
    + "`expected` is null when the text is not a coordinate pair (including when it is a valid MGRS grid).",
  generatedBy: GENERATED_BY,
  toleranceDeg: 1e-9,
  parse: PARSE_TEXTS.map((text) => {
    const parsed = parseCoordinate(text);
    return {
      text,
      expected: parsed
        ? { lat: parsed.lat, lon: parsed.lon, format: parsed.format, label: parsed.label }
        : null,
    };
  }),
  looksLikeMgrs: MGRS_TEXTS.map((text) => ({ text, expected: looksLikeMgrs(text) })),
  looksLikeCoordinateText: COORDINATE_TEXTS.map((text) => ({
    text,
    expected: looksLikeCoordinateText(text),
  })),
  // `convertToLatLongString` (utils/Helpers.js): the degrees-minutes-seconds text a diagram keeps as its `latLong` after analysis. Seconds
  // are rounded to two places and never carried into the minute, so 34.999999 reads 59' 60.00": the web's quirk, kept on purpose.
  latLongString: [
    [34.783817, -84.08219], [0, 0], [-33.8688, 151.2093], [34.999999, -84.999999], [90, 180], [-90, -180], [-0.0000001, 0.0000001],
    [34.5457, -84.1234], [34.545678, -84.123456], [0.0078125, -0.0078125], [12.5, 100], [1e-9, -1e-9], [34.00001, -84.00001],
  ].map(([lat, lon]) => ({ lat, lon, expected: convertToLatLongString(lat, lon) })),
  formatDecimal: [
    { lat: 34.545678, lon: -84.123456, places: 5, expected: formatDecimal(34.545678, -84.123456) },
    { lat: -33.8688, lon: 151.2093, places: 5, expected: formatDecimal(-33.8688, 151.2093) },
    { lat: 0, lon: 0, places: 5, expected: formatDecimal(0, 0) },
    { lat: 34.5, lon: -84.25, places: 2, expected: formatDecimal(34.5, -84.25, 2) },
  ],
});

/* -------------------------------------------------------------------------
 * Aircraft geometry, capacity, separation
 * ---------------------------------------------------------------------- */

// The built-in UH-60L plus airframes chosen to stress the arithmetic: a much
// bigger rotor, a tighter clearance, and a profile missing its numbers.
const PROFILES = {
  uh60l: { ...FALLBACK_PROFILE },
  ch47f: {
    ...FALLBACK_PROFILE, id: 7, slug: "ch47f", name: "CH-47F Chinook", designation: "CH-47F",
    icon_key: "ch47", is_system: true, rotor_diameter_m: 18.29, rotor_tip_clearance_m: 75,
    amps_vehicle_description: "Air:Rotary Wing:CH47:1:Default:1.0:CH-47F",
  },
  oh6: {
    ...FALLBACK_PROFILE, id: 9, slug: "mh6", name: "MH-6 Little Bird", designation: "MH-6",
    icon_key: "mh6", is_system: true, rotor_diameter_m: 8.33, rotor_tip_clearance_m: 30,
  },
  custom: {
    id: 12, slug: "custom-1", name: "Custom Lifter", designation: "XH-1 A", is_system: false,
    rotor_diameter_m: 12.5, rotor_tip_clearance_m: 45.5,
  },
};

// What the API can really send: a whole profile, a profile missing fields (an
// older server, a cached copy), or none at all. The columns are NOT NULL, so a
// null, a string or a NaN never arrives — normalizeProfile's Number() would turn
// a null into 0, which is why those inputs are deliberately not fixtures: no
// client should have to reproduce that.
const rawProfiles = [
  null,
  undefined,
  {},
  { icon_key: "" },
  { slug: "partial", name: "Partial", rotor_diameter_m: 14.2 },
  { rotor_diameter_m: 0, default_altitude_ft: -50, default_fuel_flow_lb_hr: 0 },
  PROFILES.custom,
];

const AREAS_SQ_FT = [
  -1, 0, 1, 62757, 62758, 125000, 188272, 188273, 250000, 1000000, 4356000,
  Number.NaN, Number.POSITIVE_INFINITY,
];

const jsonSafe = (value) => (Number.isFinite(value) ? value : null);

const profileList = Object.values(PROFILES);

const aircraftFixture = () => ({
  description: "Aircraft profile defaults, geometry, LZ capacity and pairwise separation, as "
    + "frontend/src/feature/aircraft/aircraftProfiles.js and utils/helicopterCapacity.js compute them. "
    + "Non-finite numbers appear as null.",
  generatedBy: GENERATED_BY,
  tolerance: 1e-9,
  fallbackProfile: FALLBACK_PROFILE,
  profiles: Object.entries(PROFILES).map(([key, profile]) => ({ key, profile })),
  normalize: rawProfiles.map((raw) => ({
    raw: raw === undefined ? null : raw,
    expected: normalizeProfile(raw),
  })),
  geometry: Object.entries(PROFILES).map(([key, profile]) => ({
    key,
    rotorRadiusM: rotorRadiusM(profile),
    rotorRadiusFt: rotorRadiusFt(profile),
    tipClearanceM: tipClearanceM(profile),
    tipClearanceFt: tipClearanceFt(profile),
    centerSpacingM: centerSpacingM(profile),
    centerSpacingFt: centerSpacingFt(profile),
    spotSizeSqFt: spotSizeSqFt(profile),
  })),
  capacity: AREAS_SQ_FT.flatMap((areaSqFt) => [
    { areaSqFt: jsonSafe(areaSqFt), profile: null, expected: calculateUH60Capacity(areaSqFt), fn: "calculateUH60Capacity" },
    ...Object.keys(PROFILES).map((key) => ({
      areaSqFt: jsonSafe(areaSqFt),
      profile: key,
      expected: capacityForArea(areaSqFt, PROFILES[key]),
      fn: "capacityForArea",
    })),
  ]),
  separation: profileList.flatMap((a, i) => profileList.slice(i).flatMap((b) => {
    const pair = pairSeparation(a, b);
    // Centre distances either side of the exact limit, which is where a
    // rounding difference would flip the alert.
    const distances = [
      0, pair.radiiFt, pair.radiiFt + pair.requiredClearanceFt - 0.01, pair.minCenterDistanceFt,
      pair.radiiFt + pair.requiredClearanceFt + 0.01, 500, 1000,
    ];
    return distances.map((d) => ({
      a: a.slug, b: b.slug, centerDistanceFt: d,
      radiiFt: pair.radiiFt,
      requiredClearanceFt: pair.requiredClearanceFt,
      minCenterDistanceFt: pair.minCenterDistanceFt,
      edgeGapFt: edgeGapFt(d, a, b),
      violation: isSeparationViolation(d, a, b),
    }));
  })),
  lookups: {
    findProfile: [
      { ref: 7, expected: findProfile(profileList, 7)?.slug ?? null },
      { ref: "7", expected: findProfile(profileList, "7")?.slug ?? null },
      { ref: "mh6", expected: findProfile(profileList, "mh6")?.slug ?? null },
      { ref: "uh60l", expected: findProfile(profileList, "uh60l")?.slug ?? null },
      { ref: "nope", expected: findProfile(profileList, "nope")?.slug ?? null },
      { ref: null, expected: null },
      { ref: "", expected: null },
    ],
    // `profiles` is "all" (the four above) or "none" (an empty list, e.g. before the API answers).
    profileForAsset: [
      { profiles: "all", assetProfileId: 9, defaultProfile: null, expected: profileForAsset({ profileId: 9 }, profileList, null).slug },
      { profiles: "all", assetProfileId: "gone", defaultProfile: "ch47f", expected: profileForAsset({ profileId: "gone" }, profileList, PROFILES.ch47f).slug },
      { profiles: "all", assetProfileId: "gone", defaultProfile: null, expected: profileForAsset({ profileId: "gone" }, profileList, null).slug },
      { profiles: "all", assetProfileId: null, defaultProfile: null, expected: profileForAsset({}, profileList, null).slug },
      { profiles: "none", assetProfileId: 9, defaultProfile: null, expected: profileForAsset({ profileId: 9 }, [], null).slug },
      { profiles: "none", assetProfileId: null, defaultProfile: "oh6", expected: profileForAsset({}, [], PROFILES.oh6).slug },
    ],
    matchProfileToAircraft: [
      { aircraft: { description: PROFILES.ch47f.amps_vehicle_description }, expected: matchProfileToAircraft(profileList, { description: PROFILES.ch47f.amps_vehicle_description })?.slug ?? null },
      { aircraft: { designation: "ch 47 f" }, expected: matchProfileToAircraft(profileList, { designation: "ch 47 f" })?.slug ?? null },
      { aircraft: { designation: "UH60L" }, expected: matchProfileToAircraft(profileList, { designation: "UH60L" })?.slug ?? null },
      { aircraft: { designation: "xh-1a" }, expected: matchProfileToAircraft(profileList, { designation: "xh-1a" })?.slug ?? null },
      { aircraft: { designation: "AH-64" }, expected: null },
      { aircraft: { designation: "" }, expected: null },
    ],
  },
});

/* -------------------------------------------------------------------------
 * Route planning
 * ---------------------------------------------------------------------- */

const point = (id, lat, lon, kind = "amps", name = id, extra = {}) => ({
  id, lat, lon, kind, ptType: kind === "amps" ? "turn" : null, name, ...extra,
});

// ~60 nm north then ~60 nm further north, with a shaping point mid-leg one.
const NORTHBOUND = [
  point("a", 34, -84, "amps", ".SP"),
  point("s1", 34.5, -84.1, "shaping", ""),
  point("b", 35, -84, "amps", ".CP1"),
  point("c", 36, -84, "amps", ".LZ"),
];

// A dog-leg that turns the aircraft through every quadrant, so wind sometimes
// helps and sometimes hurts.
const DOGLEG = [
  point("p1", 34.5, -84.5, "amps", ".SP"),
  point("p2", 34.9, -83.9, "amps", ".CP1"),
  point("p3", 34.6, -83.2, "amps", ".CP2"),
  point("p4", 34.0, -83.4, "amps", ".RP"),
  point("p5", 33.6, -84.0, "amps", ".LZ"),
];

const base = (overrides = {}) => ({
  ...defaultRoutePlan(),
  date: "2026-07-15",
  ...overrides,
});

const routeCases = () => [
  {
    name: "ground speed with a clock on the LZ, shaping detour on leg one",
    route: NORTHBOUND,
    plan: base({ airspeed: { value: 120, type: "ground" }, perPoint: { c: { clock: "10:00:00" } } }),
    elevationsFt: {},
  },
  {
    name: "a per-point 'to' airspeed applies to the arriving leg only",
    route: NORTHBOUND,
    plan: base({
      airspeed: { value: 100, type: "ground" },
      perPoint: { c: { airspeed: { value: 60, type: "ground" } } },
    }),
    elevationsFt: {},
  },
  {
    name: "AGL and MSL altitudes resolve against ground elevations",
    route: NORTHBOUND,
    plan: base({
      altitude: { value: 300, ref: "agl" },
      perPoint: { c: { altitude: { value: 2000, ref: "msl" } } },
    }),
    elevationsFt: { a: 1000, b: 1200, c: 900 },
  },
  {
    name: "a charted elevation beats the DEM, and missing ground leaves AGL unknown",
    route: [
      point("a", 34, -84, "amps", ".SP", { chartElevationFt: 1500 }),
      point("b", 35, -84, "amps", ".CP1"),
      point("c", 36, -84, "amps", ".LZ"),
    ],
    plan: base({ altitude: { value: 500, ref: "msl" } }),
    elevationsFt: { a: 900, b: 1100 },
  },
  {
    name: "indicated airspeed through density altitude, with a tailwind",
    route: NORTHBOUND,
    plan: base({
      airspeed: { value: 100, type: "indicated" },
      altitude: { value: 5000, ref: "msl" },
      tempC: 15 - 1.98 * 5,
      wind: { dirTrue: 180, speedKts: 10 },
    }),
    elevationsFt: {},
  },
  {
    name: "hot day, high density altitude, crosswind on a dog-leg",
    route: DOGLEG,
    plan: base({
      airspeed: { value: 110, type: "indicated" },
      altitude: { value: 6500, ref: "msl" },
      tempC: 32,
      wind: { dirTrue: 270, speedKts: 25 },
      fuelFlowLbHr: 1100,
    }),
    elevationsFt: { p1: 1800, p2: 2200, p3: 1500, p4: 900, p5: 700 },
  },
  {
    name: "true airspeed, per-point wind changes, a mid-route clock, and a midnight crossing",
    route: DOGLEG,
    plan: base({
      airspeed: { value: 105, type: "true" },
      altitude: { value: 200, ref: "agl" },
      wind: { dirTrue: 90, speedKts: 15 },
      perPoint: {
        p2: { wind: { dirTrue: 0, speedKts: 30 } },
        p4: { wind: { dirTrue: 225, speedKts: 20 }, airspeed: { value: 95, type: "true" } },
        p3: { clock: "23:40:00" },
      },
    }),
    elevationsFt: { p1: 1000, p2: 1000, p3: 1000, p4: 1000, p5: 1000 },
  },
  {
    name: "a wind that cannot be corrected leaves the leg, the timing and the totals unknown",
    route: NORTHBOUND,
    plan: base({
      airspeed: { value: 40, type: "true" },
      wind: { dirTrue: 90, speedKts: 60 },
    }),
    elevationsFt: {},
  },
  {
    name: "ground speed ignores wind entirely",
    route: DOGLEG,
    plan: base({ airspeed: { value: 90, type: "ground" }, wind: { dirTrue: 0, speedKts: 80 } }),
    elevationsFt: {},
  },
  {
    name: "a point without an id is left out of the plan, like a shaping point",
    route: [
      point("a", 34, -84, "amps", ".SP"),
      { lat: 34.2, lon: -84.1, kind: "amps", ptType: "turn", name: "NO ID" },
      point("b", 35, -84, "amps", ".LZ"),
    ],
    plan: base({ airspeed: { value: 100, type: "ground" } }),
    elevationsFt: {},
  },
  {
    name: "fewer than two route points",
    route: [point("a", 34, -84, "amps", ".SP"), point("s", 34.5, -84, "shaping", "")],
    plan: base(),
    elevationsFt: {},
  },
  {
    name: "unnamed points fall back to PT numbering; zero fuel flow",
    route: [
      { id: "x", lat: 34, lon: -84, kind: "amps", ptType: "turn", name: "" },
      { id: "y", lat: 34.3, lon: -83.7, kind: "amps", ptType: "turn" },
      { id: "z", lat: 34.9, lon: -83.9, kind: "amps", ptType: "turn", name: "" },
    ],
    plan: base({ airspeed: { value: 100, type: "ground" }, fuelFlowLbHr: 0 }),
    elevationsFt: {},
  },
  {
    name: "a clock on the first point anchors every later point forward",
    route: NORTHBOUND,
    plan: base({
      date: "2026-12-31",
      airspeed: { value: 100, type: "ground" },
      perPoint: { a: { clock: "07:15:30" } },
    }),
    elevationsFt: {},
  },
];

const summarisePlan = (result) => ({
  warnings: result.warnings,
  totals: result.totals,
  legs: result.legs,
  points: result.points.map((p) => ({
    id: p.id, uiId: p.uiId, name: p.name ?? null, ptType: p.ptType ?? null, lat: p.lat, lon: p.lon,
    ref: p.ref, value: p.value, mslFt: p.mslFt, aglFt: p.aglFt, groundFt: p.groundFt,
    airspeed: p.airspeed, wind: p.wind,
    legDistNm: p.legDistNm, legCourseTrueDeg: p.legCourseTrueDeg, legGsKts: p.legGsKts,
    legTimeSec: p.legTimeSec, legFuelLb: p.legFuelLb,
    elapsedSec: p.elapsedSec,
    clock: p.clockTime ? formatClock(p.clockTime) : null,
    hasClock: p.hasClock, isTotAnchor: p.isTotAnchor,
  })),
});

// JSON has no undefined; the fixtures spell "absent" as null so every client
// reads the same thing.
const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (v === undefined ? null : v)));

const GREAT_CIRCLE = [
  [34, -84, 35, -84], [34, -84, 34, -83], [34.5457, -84.1234, 34.8, -83.2],
  [0, 0, 0, 1], [0, 0, 1, 0], [60, 10, 60.5, 11], [-33.8688, 151.2093, -34.5, 150.0],
  [34, 179.5, 34, -179.5], [34, -84, 34, -84], [10, 10, -10, -10], [89, 0, 89, 90],
  [34.783817, -84.08219, 34.596407, -84.128098],
];

const WIND_TRIANGLES = [
  [120, 90, 0, 0], [120, 360, 360, 20], [120, 360, 180, 20], [120, 360, 90, 30],
  [20, 360, 90, 40], [100, 45, 315, 25], [100, 270, 100, 35], [150, 180, 180, 150],
  [150, 180, 0, 149], [80, 10, 280, 80], [80, 10, 280, 79.9], [0, 90, 0, 10],
];

const IAS_TO_TAS = [
  [100, 0, 15], [100, 5000, 15 - 1.98 * 5], [100, 5000, 30], [120, 10000, -5],
  [80, -1500, 40], [193, 12000, 0], [0, 5000, 15], [100, -20000, 15],
];

// Halves matter: JavaScript's Math.round sends 0.5, 2.5 and 60.5 up (to 1, 3, 61),
// where a round-half-even (Kotlin's round, Swift's .toNearestOrEven) goes to 0, 2, 60.
const DURATIONS = [null, 0, 0.5, 2.5, 5, 59.4, 59.5, 60, 60.5, 61, 3599.6, 3600, 3661, 7325.4, 7325.5, 86399];

const CLOCKS = [
  [2026, 6, 15, 0, 0, 0], [2026, 6, 15, 9, 5, 7], [2026, 6, 15, 23, 59, 59], [2026, 6, 16, 0, 0, 1],
];

const routeFixture = () => ({
  description: "Route planning math from frontend/src/feature/msnxImport/routeCalc.js: great-circle "
    + "distance and true course, IAS to TAS, the wind triangle, and computeRoutePlan over whole routes. "
    + "Clock times are the local time-of-day on the plan's date (no daylight-saving change on the dates used).",
  generatedBy: GENERATED_BY,
  tolerance: 1e-9,
  greatCircle: GREAT_CIRCLE.map(([lat1, lon1, lat2, lon2]) => ({
    lat1, lon1, lat2, lon2,
    distanceNm: distanceNm(lat1, lon1, lat2, lon2),
    trueCourseDeg: trueCourseDeg(lat1, lon1, lat2, lon2),
  })),
  windTriangle: WIND_TRIANGLES.map(([tasKts, courseDeg, windFromDeg, windKts]) => ({
    tasKts, courseDeg, windFromDeg, windKts,
    expected: clean(solveWindTriangle(tasKts, courseDeg, windFromDeg, windKts)),
  })),
  iasToTas: IAS_TO_TAS.map(([iasKts, pressAltFt, oatC]) => ({
    iasKts, pressAltFt, oatC, expected: iasToTas(iasKts, pressAltFt, oatC),
  })),
  formatDuration: DURATIONS.map((sec) => ({ sec, expected: formatDuration(sec) })),
  formatClock: CLOCKS.map(([y, m, d, hh, mm, ss]) => ({
    time: `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}:${String(ss).padStart(2, "0")}`,
    expected: formatClock(new Date(y, m, d, hh, mm, ss)),
  })),
  defaultPlan: [
    { profile: null, expected: clean(defaultRoutePlan()) },
    { profile: "ch47f", expected: clean(defaultRoutePlan(PROFILES.ch47f)) },
    { profile: "custom", expected: clean(defaultRoutePlan(PROFILES.custom)) },
  ],
  ensurePlan: [
    {
      name: "a pre-inline TOT migrates onto its point",
      route: { points: NORTHBOUND, plan: { tot: { pointId: "c", time: "10:00:00", date: "2026-07-15" } } },
      expected: clean(ensureRoutePlan({ points: NORTHBOUND, plan: { tot: { pointId: "c", time: "10:00:00", date: "2026-07-15" } } }).plan),
    },
    {
      name: "a TOT with no point anchors the first AMPS point",
      route: { points: NORTHBOUND, plan: { tot: { time: "08:30:00" } } },
      expected: clean(ensureRoutePlan({ points: NORTHBOUND, plan: { tot: { time: "08:30:00" } } }).plan),
    },
    {
      name: "an existing clock wins over a leftover TOT",
      route: { points: NORTHBOUND, plan: { perPoint: { b: { clock: "09:00:00" } }, tot: { pointId: "c", time: "10:00:00" } } },
      expected: clean(ensureRoutePlan({ points: NORTHBOUND, plan: { perPoint: { b: { clock: "09:00:00" } }, tot: { pointId: "c", time: "10:00:00" } } }).plan),
    },
    {
      name: "a route with no plan gets the defaults",
      route: { points: NORTHBOUND },
      expected: clean(ensureRoutePlan({ points: NORTHBOUND }).plan),
    },
  ],
  plans: routeCases().map((c) => ({
    name: c.name,
    route: c.route,
    plan: clean(c.plan),
    elevationsFt: c.elevationsFt,
    expected: clean(summarisePlan(computeRoutePlan({ points: c.route }, c.plan, c.elevationsFt))),
  })),
});

/* -------------------------------------------------------------------------
 * LZ diagram documents
 * ---------------------------------------------------------------------- */

// Timestamps are always supplied: normalizeLzDiagram reads the clock when they
// are absent, and a fixture cannot depend on the clock. Ids likewise.
const T0 = "2026-09-01T12:00:00.000Z";
const T1 = "2026-09-02T08:30:00.000Z";

const HELO = { id: "h1", lat: 34.5, lon: -84.1, heading: 270, profileId: "uh60l", futureField: { keep: ["me"] } };
const SECTOR = { id: "s1", points: [[34.5, -84.1], [34.51, -84.09], [34.5, -84.08]], color: "#ff0000" };

const FULL_DIAGRAM = {
  schemaVersion: 2,
  id: "lz-full",
  savedId: 41,
  name: "LZ HAWK",
  dirty: true,
  createdAt: T0,
  updatedAt: T1,
  status: "analyzed",
  target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  mapData: { mgrs: "16S GD 66993 52949", latLong: "34.78382, -84.08219", zoom: 17, anything: { else: true } },
  flightData: { callSign: "HAWK 6", frequency: "251.0", landingHeading: 270, extra: [1, 2, 3] },
  analysis: {
    customLZ: [[34.7, -84.1], [34.7, -84.0], [34.8, -84.0]],
    detectedLZ: [[34.71, -84.09], [34.71, -84.01], [34.79, -84.01]],
    terrainData: { image: "BASE64...", bounds: [[34.7, -84.1], [34.8, -84.0]] },
    results: { areaSqFt: 188300, maxSlope: 4.2, elevationFt: 1320 },
    gridElevation: "1320",
    latLong: "34.78382, -84.08219",
  },
  graphics: {
    doghouses: [{ id: "d1", kind: "SP" }, { id: "d2", kind: "RP" }],
    helicopters: [HELO],
    pzMarkers: [{ id: "p1", lat: 34.5, lon: -84.1 }],
    sectorsOfFire: [SECTOR],
    goArounds: [{ id: "g1", side: "L" }],
    units: [{ id: "u1", sidc: "SFGPUCI----D---" }],
    measurements: [{ id: "m1", a: [34.5, -84.1], b: [34.6, -84.2] }],
    exportBox: { north: 34.8, south: 34.7, east: -84.0, west: -84.1 },
  },
  view: { mapStyle: "outdoors", showLZOutline: false, showHeatmap: true },
};

// What App.js saved before diagrams were versioned: one flat object.
const LEGACY_SNAPSHOT = {
  targetLocation: [34.545678, -84.123456],
  gridInput: "16S GD 52000 30000",
  mapData: { mgrs: "16S GD 52000 30000" },
  flightData: { callSign: "OLD 1" },
  customLZ: [[34.5, -84.1], [34.5, -84.0], [34.6, -84.0]],
  detectedLZ: [[34.51, -84.09], [34.51, -84.01], [34.59, -84.01]],
  analysisResults: { areaSqFt: 90000 },
  gridElevation: 1100,
  latLong: "34.54568, -84.12346",
  helicopters: [HELO],
  doghouses: [{ id: "d1" }],
  pzMarker: [{ id: "p-old", lat: 34.5, lon: -84.1 }],
  sectorsOfFire: [SECTOR],
  goAround: [{ id: "g-old" }],
  units: [],
  exportBox: { north: 1, south: 0, east: 1, west: 0 },
  mapStyle: "satellite",
  showLZOutline: false,
};

const diagramCases = () => [
  ["an empty object is a draft", {}, { id: "d-empty", createdAt: T0 }],
  ["a full current diagram round-trips, extra fields in graphics and map data kept", FULL_DIAGRAM, {}],
  ["a legacy flat snapshot becomes one diagram", LEGACY_SNAPSHOT, { id: "d-legacy", createdAt: T0 }],
  ["an object target with latitude and lng", { id: "d-obj", createdAt: T0, target: { latitude: "34.5", lng: -84.25 } }, {}],
  ["a target with longitude", { id: "d-lon", createdAt: T0, target: { lat: 10, longitude: 20, mgrs: "  " } }, {}],
  ["an unparseable target leaves a draft even when analysis exists", { id: "d-bad", createdAt: T0, target: { lat: "north", lon: 1 }, analysis: { results: { a: 1 } } }, {}],
  ["analysis results make a targeted diagram analyzed", { id: "d-res", createdAt: T0, target: [1, 2], analysis: { results: { a: 1 } }, status: "draft" }, {}],
  ["a detected LZ alone counts as analysis", { id: "d-det", createdAt: T0, target: [1, 2], analysis: { detectedLZ: [[1, 2]] } }, {}],
  ["a requested analyzed status holds without results", { id: "d-req", createdAt: T0, target: [1, 2], status: "analyzed" }, {}],
  ["an unknown status falls back to targeted", { id: "d-unk", createdAt: T0, target: [1, 2], status: "weird" }, {}],
  ["the target mgrs comes from the target, then mapData, then gridInput", { id: "d-m1", createdAt: T0, targetLocation: [1, 2], mapData: { mgrs: "MAPDATA" }, gridInput: "GRID" }, {}],
  ["mapData's mgrs is used when the target has none", { id: "d-m2", createdAt: T0, target: { lat: 1, lon: 2 }, mapData: { mgrs: "MAPDATA" }, gridInput: "GRID" }, {}],
  ["only gridInput", { id: "d-m3", createdAt: T0, targetLocation: [1, 2], gridInput: "GRID" }, {}],
  ["dirty follows JavaScript truthiness: 'yes' is dirty", { id: "d-t1", createdAt: T0, dirty: "yes" }, {}],
  ["dirty follows JavaScript truthiness: 0 and empty are clean", { id: "d-t2", createdAt: T0, dirty: 0 }, {}],
  ["an option overrides the input's dirty, name and id", { id: "ignored", name: "from input", dirty: true, createdAt: T0 }, { id: "from-option", name: "from option", dirty: false }],
  ["snake_case timestamps from an API record are read", { id: "d-ts", created_at: T0, updated_at: T1 }, {}],
  ["updatedAt defaults to createdAt", { id: "d-ts2", createdAt: T0 }, {}],
  ["non-object graphics items are kept", { id: "d-g", createdAt: T0, graphics: { helicopters: [HELO, 7, "x", null], pzMarkers: "not an array" } }, {}],
  ["a graphics object hides the flat names beside it", { id: "d-g2", createdAt: T0, graphics: { pzMarker: [{ id: "legacy" }] }, pzMarker: [{ id: "flat" }] }, {}],
  ["the plural name wins when a graphics object has both", { id: "d-g3", createdAt: T0, graphics: { pzMarkers: [{ id: "new" }], pzMarker: [{ id: "old" }], goArounds: [{ id: "new" }], goAround: [{ id: "old" }] } }, {}],
  ["a null plural falls back to the legacy name", { id: "d-g4", createdAt: T0, graphics: { pzMarkers: null, pzMarker: [{ id: "old" }], goArounds: null, goAround: [{ id: "old-go" }] } }, {}],
  ["an empty plural does not fall back", { id: "d-g5", createdAt: T0, graphics: { pzMarkers: [], pzMarker: [{ id: "old" }] } }, {}],
  ["analysis results under their legacy name", { id: "d-a1", createdAt: T0, target: [1, 2], analysis: { analysisResults: { legacy: true } } }, {}],
  ["results win over the legacy name", { id: "d-a2", createdAt: T0, target: [1, 2], analysis: { results: { new: true }, analysisResults: { legacy: true } } }, {}],
  ["a nested analysis hides the flat fields beside it", { id: "d-a3", createdAt: T0, target: [1, 2], analysis: { gridElevation: "nested" }, gridElevation: "flat", customLZ: [[9, 9]] }, {}],
  ["a view given flat or nested", { id: "d-v", createdAt: T0, view: { mapStyle: "vfr" } }, {}],
  ["a numeric id becomes a string", { id: 12, createdAt: T0 }, {}],
  ["a non-object source is an empty diagram", "nonsense", { id: "d-str", createdAt: T0 }],
];

const legacySnapshotCases = () => [
  // A saved record carries no client id: the caller gives one when it loads it (the web draws a random one).
  ["an API record: saved id, name and timestamps come from the wrapper", { id: 77, name: "Saved LZ", created_at: T0, updated_at: T1, lz_data: LEGACY_SNAPSHOT }, { id: "loaded-77" }],
  ["an API record holding a current diagram", { id: 78, name: "Saved v2", created_at: T0, updated_at: T1, lz_data: { ...FULL_DIAGRAM, id: "inner" } }, {}],
  ["a bare legacy snapshot with a client id", { ...LEGACY_SNAPSHOT, clientId: "client-1", savedId: 5, name: "Bare", createdAt: T0 }, {}],
  ["options override the wrapper", { id: 9, name: "Wrapper", created_at: T0, lz_data: LEGACY_SNAPSHOT }, { id: "opt-id", savedId: 99, name: "Opt", dirty: true }],
];

const fromTargetCases = () => [
  ["a blank diagram bound to a target", { target: [34.5, -84.1], mgrs: "16S GD 1 2", id: "t1", name: "New", createdAt: T0 }],
  ["with map data and a view", { target: { lat: 1, lon: 2 }, id: "t2", createdAt: T0, mapData: { zoom: 15, mgrs: "OLD" }, view: { mapStyle: "vfr" } }],
  ["no usable target gives nothing", { target: null, id: "t3", createdAt: T0 }],
  ["a saved id", { target: [1, 2], id: "t4", savedId: 12, createdAt: T0 }],
];

const TARGETS = [
  [[34.5, -84.1], ""], [[34.5, -84.1], "16S GD 1 2"], [[34.5, -84.1], "   "],
  [{ lat: 1, lon: 2 }, ""], [{ latitude: 3, longitude: 4, mgrs: "M" }, ""], [{ lat: 5, lng: 6 }, "X"],
  [{ lat: "7.5", lon: "-8.5" }, ""], [{ lat: "x", lon: 1 }, ""], [{ lat: 1 }, ""], [[1], ""],
  [null, ""], [undefined, ""], [[], ""], [{ lat: 1, lon: 2, mgrs: 5 }, ""], [{ lat: 1, lon: 2, mgrs: "own" }, "param"],
];

const workspaceCases = () => {
  const d = (id, extra = {}) => ({ id, createdAt: T0, target: [34.5, -84.1], ...extra });
  return [
    ["nothing", undefined],
    ["an empty object", {}],
    ["an array of diagrams", [d("a"), d("b")]],
    ["an object with a diagrams array", { diagrams: [d("a"), d("b")], activeDiagramId: "a" }],
    ["a saved workspace", { diagramOrder: ["b", "a"], diagramsById: { a: d("a"), b: d("b") }, activeDiagramId: "a" }],
    ["a saved workspace naming a diagram that is gone", { diagramOrder: ["a"], diagramsById: { a: d("a") }, activeDiagramId: "zzz" }],
    ["a single legacy snapshot", { ...LEGACY_SNAPSHOT, id: "legacy-1", createdAt: T0 }],
    ["duplicate ids are made unique, in order", [d("x"), d("x"), d("x"), d("x-2")]],
    ["falsy entries are skipped", [d("a"), null, undefined, d("b")]],
    ["the active diagram defaults to the last", [d("a"), d("b"), d("c")]],
    ["dirty diagrams stay dirty", [d("a", { dirty: true }), d("b")]],
  ];
};

// The web falls back to the clock and a random id when a document has no timestamp
// or id. A fixture that reached for either could never be reproduced, so while one is
// built both throw: the case must supply what it needs.
const withoutClockOrRandomness = (build) => {
  const toISOString = Date.prototype.toISOString;
  const hadCrypto = Object.prototype.hasOwnProperty.call(window, "crypto");
  const crypto = window.crypto;
  Date.prototype.toISOString = () => {
    throw new Error("a fixture case relied on the clock: give it createdAt/updatedAt");
  };
  Object.defineProperty(window, "crypto", {
    configurable: true,
    value: { randomUUID: () => { throw new Error("a fixture case relied on a random id: give it an id"); } },
  });
  try {
    return build();
  } finally {
    Date.prototype.toISOString = toISOString;
    if (hadCrypto) Object.defineProperty(window, "crypto", { configurable: true, value: crypto });
    else delete window.crypto;
  }
};

const diagramFixture = () => withoutClockOrRandomness(() => ({
  description: "The saved LZ diagram document, as useLzWorkspace.js normalizes it: current v2 diagrams, "
    + "legacy flat snapshots, API records (lz_data) and whole workspaces. Graphics are opaque objects the "
    + "normalizer copies without reading, so fields it does not know survive. Timestamps and ids are "
    + "always supplied (the web falls back to the clock and a random id).",
  generatedBy: GENERATED_BY,
  schemaVersion: 2,
  normalize: diagramCases().map(([name, source, options]) => ({
    name, source, options, expected: clean(normalizeLzDiagram(source, options)),
  })),
  legacySnapshot: legacySnapshotCases().map(([name, snapshot, options]) => ({
    name, snapshot, options, expected: clean(normalizeLegacyLzSnapshot(snapshot, options)),
  })),
  fromTarget: fromTargetCases().map(([name, args]) => ({
    name, args, expected: clean(createLzDiagramFromTarget(args)),
  })),
  serialize: [
    ["strips the terrain raster and the dirty flag", FULL_DIAGRAM, {}],
    ["keeps the raster when asked", FULL_DIAGRAM, { includeTerrainData: true }],
    ["nothing", null, {}],
  ].map(([name, diagram, options]) => ({
    name, diagram, options, expected: clean(serializeLzDiagram(diagram, options)),
  })),
  target: TARGETS.map(([target, mgrs]) => ({
    target: target === undefined ? null : target, mgrs, expected: clean(normalizeLzTarget(target, mgrs)),
  })),
  capabilities: [
    ["no diagram", null], ["a draft", { target: null, status: "draft" }],
    ["targeted", { target: { lat: 1, lon: 2 }, status: "targeted" }],
    ["analyzed", { target: { lat: 1, lon: 2 }, status: "analyzed" }],
    ["analyzed but no target", { target: null, status: "analyzed" }],
  ].map(([name, diagram]) => ({
    name, diagram,
    canAnalyze: canAnalyzeLzDiagram(diagram),
    canEditGraphics: canEditLzDiagramGraphics(diagram),
  })),
  workspace: workspaceCases().map(([name, source]) => ({
    name, source: source === undefined ? null : source, expected: clean(createInitialLzWorkspace(source)),
  })),
}));

/* -------------------------------------------------------------------------
 * The mission summary: area, capacity and the slope call
 * ---------------------------------------------------------------------- */

// Polygons are [lat, lon] pairs, as an analysis stores them. The area is the web's spherical-excess formula in square feet, and the
// formula does not carry a polygon across the antimeridian (the longitude jump is taken at face value): the case is here so a port
// does not "fix" it silently.
const SUMMARY_POLYGONS = [
  ["a rectangle about 300 m by 250 m", [[34.7800, -84.0900], [34.7800, -84.0866], [34.7777, -84.0866], [34.7777, -84.0900]]],
  ["the same walked the other way round", [[34.7800, -84.0900], [34.7777, -84.0900], [34.7777, -84.0866], [34.7800, -84.0866]]],
  ["a closed ring with the first point repeated", [[34.7800, -84.0900], [34.7800, -84.0866], [34.7777, -84.0866], [34.7777, -84.0900], [34.7800, -84.0900]]],
  ["a triangle", [[34.5, -84.1], [34.5, -84.09], [34.51, -84.095]]],
  ["a sliver", [[34.5, -84.1], [34.5, -84.0999], [34.51, -84.0999], [34.51, -84.1]]],
  ["a kilometre square", [[34.50, -84.10], [34.50, -84.0887], [34.4910, -84.0887], [34.4910, -84.10]]],
  ["on the equator", [[0.001, 10.0], [0.001, 10.002], [-0.001, 10.002], [-0.001, 10.0]]],
  ["near the pole, where a degree of longitude is short", [[80.0, 10.0], [80.0, 10.01], [79.999, 10.01], [79.999, 10.0]]],
  ["south of the equator", [[-33.8688, 151.2093], [-33.8688, 151.2133], [-33.8718, 151.2133], [-33.8718, 151.2093]]],
  ["across the antimeridian", [[10, 179.999], [10, -179.999], [9.999, -179.999], [9.999, 179.999]]],
  ["a point repeated three times has no area", [[34.5, -84.1], [34.5, -84.1], [34.5, -84.1]]],
  ["a straight line of three points has no area", [[34.5, -84.1], [34.5, -84.09], [34.5, -84.08]]],
  ["two points are not a polygon", [[34.5, -84.1], [34.5, -84.09]]],
  ["no points", []],
  ["nothing", null],
];

const TERRAIN_DATA = [
  ["nothing measured", null],
  ["no statistics", {}],
  ["flat ground", { stats: { maxDeg: 0 } }],
  ["a gentle slope", { stats: { maxDeg: 4.2 } }],
  ["exactly ten degrees is not yet a caution", { stats: { maxDeg: 10 } }],
  ["just over ten", { stats: { maxDeg: 10.1 } }],
  ["just under fifteen", { stats: { maxDeg: 14.9 } }],
  ["fifteen with no heading asks for one", { stats: { maxDeg: 15 } }],
  ["steep with no heading asks for one", { stats: { maxDeg: 32 } }],
  ["a landing heading with everything inside the limits", { stats: { maxDeg: 4 }, directional: { noseHighMaxDeg: 3, noseLowMaxDeg: 2, crossSlopeMaxDeg: 1 } }],
  ["a heading, nose high just inside the limit", { stats: { maxDeg: 6 }, directional: { noseHighMaxDeg: 5.9, noseLowMaxDeg: 0, crossSlopeMaxDeg: 0 } }],
  ["a heading, nose high at the limit", { stats: { maxDeg: 6 }, directional: { noseHighMaxDeg: 6, noseLowMaxDeg: 0, crossSlopeMaxDeg: 0 } }],
  ["a heading, nose low at the limit", { stats: { maxDeg: 15 }, directional: { noseHighMaxDeg: 0, noseLowMaxDeg: 15, crossSlopeMaxDeg: 0 } }],
  ["a heading, nose low just inside", { stats: { maxDeg: 14 }, directional: { noseHighMaxDeg: 0, noseLowMaxDeg: 14.9, crossSlopeMaxDeg: 0 } }],
  ["a heading, cross slope at the limit", { stats: { maxDeg: 15 }, directional: { noseHighMaxDeg: 0, noseLowMaxDeg: 0, crossSlopeMaxDeg: 15 } }],
  ["a heading, steep but inside the directional limits, is still a caution", { stats: { maxDeg: 20 }, directional: { noseHighMaxDeg: 5, noseLowMaxDeg: 5, crossSlopeMaxDeg: 5 } }],
];

const summaryFixture = () => ({
  description: "What the mission summary says about a landing zone, as frontend/src/components/MissionSummary.jsx computes it: area "
    + "(square feet, rounded) and capacity for an aircraft, and the call the slope tile makes. `profile` is a key into "
    + "planning/aircraft.json's `profiles`, or null for the default UH-60L.",
  generatedBy: GENERATED_BY,
  area: SUMMARY_POLYGONS.flatMap(([name, polygon]) => [null, "ch47f", "oh6"].map((key) => ({
    name, polygon, profile: key,
    expected: clean(lzAreaAndCapacity(polygon, key ? PROFILES[key] : undefined)),
  }))),
  slope: TERRAIN_DATA.map(([name, terrainData]) => ({
    name, terrainData, expected: clean(slopeStatusFor(terrainData)),
  })),
});

/* ---------------------------------------------------------------------- */

describe("web reference fixtures", () => {
  it("coords/parse.json", () => settle("coords/parse.json", coordinateFixture()));
  it("planning/aircraft.json", () => settle("planning/aircraft.json", aircraftFixture()));
  it("planning/route.json", () => settle("planning/route.json", routeFixture()));
  it("workspace/diagram.json", () => settle("workspace/diagram.json", diagramFixture()));
  it("planning/summary.json", () => settle("planning/summary.json", summaryFixture()));
});

describe("fixture sanity", () => {
  it("covers both outcomes of every coordinate-text function", () => {
    const f = coordinateFixture();
    expect(f.parse.some((c) => c.expected === null)).toBe(true);
    expect(f.parse.filter((c) => c.expected !== null).length).toBeGreaterThanOrEqual(40);
    expect(f.looksLikeMgrs.some((c) => c.expected)).toBe(true);
    expect(f.looksLikeMgrs.some((c) => !c.expected)).toBe(true);
  });

  it("exercises the cases route planning is fragile about", () => {
    const f = routeFixture();
    const byName = (needle) => f.plans.find((p) => p.name.includes(needle));
    // An uncorrectable wind must leave timing unknown rather than invent it.
    expect(byName("cannot be corrected").expected.totals.timeSec).toBeNull();
    expect(byName("cannot be corrected").expected.warnings).toHaveLength(2);
    // The midnight crossing really crosses.
    const clocks = byName("midnight").expected.points.map((p) => p.clock);
    expect(clocks).toContain("23:40:00");
    expect(clocks.some((c) => c && c < "23:40:00" && c.startsWith("0"))).toBe(true);
    expect(byName("fewer than two").expected.legs).toEqual([]);
  });
});
