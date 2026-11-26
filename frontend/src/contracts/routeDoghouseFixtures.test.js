import { createDefaultDoghouses } from "../feature/doghouses/useDoghouses";
import {
  courseText,
  legTime,
  MIN_EXPORT_SCALE,
  MIN_SCALE,
  roofName,
  routeDoghouses,
  scaleAt,
  sideOffset,
} from "../feature/routeDoghouses/routeDoghouses";
import { doghousesBesideLegs, flightDataWithLegs, legWords, lzLegs } from "../feature/routeDoghouses/lzLegs";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// A doghouse on each leg of a route (routeDoghouses.js), and the legs that fly into and out of an LZ/PZ standing in for its own
// doghouses (lzLegs.js), as the web works them out. Places and turns are written to a billionth, lengths on the screen to a
// millionth and distances to a millimetre, so an ARM build of V8 and an x86 one (AGENTS.md §11) write the same file.

const NAME = "routes/doghouses.json";
const round = (value, digits) => (typeof value === "number" && Number.isFinite(value) ? Number(value.toFixed(digits)) + 0 : value);

const point = (id, lat, lon, extra = {}) => ({ id, lat, lon, kind: "amps", ptType: "turn", name: `.${id}`, ...extra });
const PLAN = { airspeed: { value: 100, type: "true" }, wind: { dirTrue: 0, speedKts: 0 } };
const route = (id, points, extra = {}) => ({ id, name: id.toUpperCase().replace("-", " "), color: "#ff0000", points, plan: PLAN, ...extra });

// The LZ the routes below fly into and out of, as in the owner's screenshot of 2026-11-26.
const LZ = { lat: 34.6, lon: -84.4 };

const INBOUND = route("route-in", [
  point("SP1", 34.5, -84.6, { ptType: "ip", name: ".SP" }),
  point("RP1", 34.58, -84.45, { ptType: "ip", name: ".RP" }),
  point("TGT1", 34.6, -84.4, { ptType: "target", name: ".TGT" }),
], { color: "#ef4444" });
const OUTBOUND = route("route-out", [
  point("TGT2", 34.6003, -84.3998, { ptType: "target", name: ".TGT" }),
  point("SP2", 34.62, -84.36, { ptType: "ip", name: ".SP" }),
  point("CP2", 34.7, -84.3),
], { color: "#3b82f6" });

const LEG_ROUTES = {
  bent: [route("bent", [
    point("SP", 34.6, -84.5, { ptType: "ip" }),
    { id: "s1", lat: 34.62, lon: -84.45, kind: "shaping" },
    point("CP1", 34.6, -84.4),
    point("TGT", 34.5, -84.4, { ptType: "target", name: ".TGT" }),
  ])],
  inAndOut: [INBOUND, OUTBOUND],
  windAndOwnSpeeds: [route("windy", [
    point("A", 34.5, -84.5), point("B", 34.55, -84.42), point("C", 34.52, -84.35),
  ], { plan: { airspeed: { value: 90, type: "true" }, wind: { dirTrue: 270, speedKts: 25 }, perPoint: { C: { airspeed: { value: 60, type: "true" } } } } })],
  // Just west of north: the course is written 360 on the box, and taken as 000° for a heading.
  northWithAWest: [route("north", [point("A", 34.5, -84.4), point("B", 34.6, -84.4004)])],
  // A point with no name of its own: its box says the planner's name for it.
  unnamedPoint: [route("unnamed", [point("A", 34.5, -84.4), point("B", 34.5, -84.3, { name: "" })])],
  hidden: [route("hidden", [point("A", 34.5, -84.4), point("B", 34.6, -84.4)], { visible: false })],
  oneNamedPoint: [route("one", [point("A", 34.5, -84.4), { id: "s", lat: 34.6, lon: -84.4, kind: "shaping" }])],
  noLength: [route("still", [point("A", 34.5, -84.4), point("B", 34.5, -84.4)])],
  aPointThatIsNoPlace: [route("bad", [point("A", 34.5, -84.4), { ...point("B", 34.6, -84.4), lat: "34.6" }])],
};

const shownLegDoghouse = (doghouse) => ({
  key: doghouse.key,
  lat: round(doghouse.lat, 9),
  lon: round(doghouse.lon, 9),
  rotation: round(doghouse.rotation, 9),
  offset: doghouse.offset,
  lengthPx0: round(doghouse.lengthPx0, 9),
  box: doghouse.box,
});

const OFFSETS = [[90, 90], [0, 90], [0, -90], [59, 15], [237, 240], [10, 350], [350, 10], [180, -180], [0, 45.5]];
const SCALES = [
  ...[8, 11, 12.5, 14, 17].map((zoom) => ({ lengthPx0: 0.0123, zoom, min: MIN_SCALE })),
  ...[8, 12, 14].map((zoom) => ({ lengthPx0: 0.0123, zoom, min: MIN_EXPORT_SCALE })),
  { lengthPx0: 0.0123, zoom: null, min: MIN_SCALE },
];

// An LZ/PZ's doghouses as an analysis makes them, turned by hand as the owner's were.
const turned = (doghouses) => doghouses.map((dh) => ({ ...dh, heading: dh.role === "landing" ? "065°" : "050°" }));
const LZ_DOGHOUSES = turned(createDefaultDoghouses(LZ, "lz"));
// Doghouses an older release wrote, known by their ids.
const OLD_DOGHOUSES = [
  { id: "dh1", lat: 34.6, lon: -84.403, id_val: "SP", heading: "050°", time: "01+57", dist: "3.13km", airspeed: "60 KIAS" },
  { id: "dh2", lat: 34.5985, lon: -84.403, id_val: "RP", heading: "065°", time: "02+10", dist: "5.20km", airspeed: "40 KIAS" },
];
const FLIGHT = { landing_hdg: "065°", takeoff_hdg: "050°", lz_name: "HAWK" };
const THROUGH = route("through", [
  point("RP", 34.58, -84.45, { ptType: "ip", name: ".RP" }),
  point("TGT", 34.6001, -84.4001, { ptType: "target", name: ".TGT" }),
  point("SP", 34.62, -84.36, { ptType: "ip", name: ".SP" }),
]);
const NEARER = route("nearer", [point("RP", 34.57, -84.42, { name: ".RP2" }), point("TGT", 34.60001, -84.40001, { name: ".LZ" })]);

const LZ_CASES = {
  inAndOut: { target: LZ, routes: [INBOUND, OUTBOUND] },
  inOnly: { target: LZ, routes: [INBOUND] },
  outOnly: { target: LZ, routes: [OUTBOUND] },
  throughTheLz: { target: LZ, routes: [THROUGH] },
  theNearerArrivalWins: { target: LZ, routes: [THROUGH, NEARER] },
  aHiddenRouteStandsInForNothing: { target: LZ, routes: [{ ...INBOUND, visible: false }, OUTBOUND] },
  tooFarFromTheTarget: { target: { lat: 34.606, lon: -84.4 }, routes: [INBOUND, OUTBOUND] },
  justInReach: { target: { lat: 34.6044, lon: -84.4 }, routes: [INBOUND] },
  targetAsAPair: { target: [34.6, -84.4], routes: [INBOUND, OUTBOUND] },
  noTarget: { target: null, routes: [INBOUND, OUTBOUND] },
  noRoutes: { target: LZ, routes: [] },
  olderDoghouses: { target: LZ, routes: [INBOUND, OUTBOUND], doghouses: OLD_DOGHOUSES },
  noDoghouses: { target: LZ, routes: [INBOUND], doghouses: [], flightData: {} },
};

const shownLeg = (leg) => leg && { ...leg, courseDeg: round(leg.courseDeg, 9), metres: round(leg.metres, 3) };

const lzCase = (name, { target, routes, doghouses = LZ_DOGHOUSES, flightData = FLIGHT }) => {
  const legs = lzLegs(target, routes);
  return {
    name,
    target,
    routes,
    doghouses,
    flightData,
    legs: { landing: shownLeg(legs.landing), takeoff: shownLeg(legs.takeoff) },
    judged: flightDataWithLegs(flightData, legs),
    shownDoghouses: doghousesBesideLegs(doghouses, legs).map((dh) => dh.id),
    words: legWords(legs.landing),
  };
};

const fixture = () => ({
  generatedBy: "frontend/src/contracts/routeDoghouseFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "legs: a doghouse on each leg between two named points of each route shown (routeDoghouses): key, its place (lat, lon), rotation (the leg's true course, "
    + "0 with none), offset ({x, y} in CSS px in the box's own frame, x across to the right of the way flown, y back along it), lengthPx0 (the leg on the screen at "
    + "zoom 0, doubled each zoom) and box (what it says). offsets: sideOffset(course, stretch). scales: scaleAt for a leg lengthPx0 at a Leaflet zoom (null: not a "
    + "number) with its floor. texts: legTime(seconds), courseText(degrees), roofName(name). lz: the legs of the routes shown that arrive at an LZ/PZ's target "
    + "(landing) and leave it (takeoff), within 500 m of it, the nearer end winning (lzLegs); the flight data the slope is judged on with their headings "
    + "(judged); the ids of the LZ/PZ's doghouses still drawn beside them (shownDoghouses); and what the slope tile says of the landing leg (words).",
  legs: Object.entries(LEG_ROUTES).map(([name, routes]) => ({ name, routes, doghouses: routeDoghouses(routes).map(shownLegDoghouse) })),
  offsets: OFFSETS.map(([course, stretch]) => ({ course, stretch, offset: sideOffset(course, stretch) })),
  scales: SCALES.map((c) => ({ ...c, scale: scaleAt({ lengthPx0: c.lengthPx0 }, c.zoom ?? Number.NaN, c.min) })),
  texts: {
    legTime: [0, 59.4, 59.5, 425, 3600.2, null].map((seconds) => ({ seconds, ...legTime(seconds ?? undefined) })),
    courseText: [0, 4.5, 45.49, 359.4, 359.6, null].map((degrees) => ({ degrees, text: courseText(degrees ?? undefined) })),
    roofName: [".CP3", "CP3", " .TGT ", "..X", "", null].map((name) => ({ name, text: roofName(name) })),
  },
  lz: Object.entries(LZ_CASES).map(([name, c]) => lzCase(name, c)),
});

describe("the route doghouse fixture", () => {
  it(NAME, () => {
    const text = dumps(fixture());
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
