import { diffData, sameData } from "../feature/missionPacks/packDiff";
import { applyPackOp, validatePackOp } from "../feature/missionPacks/packOps";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What an editor's change to a mission-pack item is sent as. The web's packDiff.js is the reference: an editor's
// version of an item is compared with the version it was last in step with, and the difference goes to the pack
// as packOps operations (packs/ops.json says what each one does). The native apps will send their changes the
// same way, and are held to every case here: the same change must become the same operations, in the same order.
// A case gives the item's data before and after and the operations (or that diffData refuses); every case is
// also checked here to rebuild `after` when its operations are applied with packOps.

const GENERATED_BY = "frontend/src/contracts/packDiffFixtures.test.js (UPDATE_CONTRACTS=1)";

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
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

// diffData reads neither, but a fixture that ever came to depend on one could not be made again: while the
// file is built, both throw.
const withoutClockOrRandomness = (build) => {
  const refuse = (what) => () => {
    throw new Error(`a diff case relied on ${what}`);
  };
  const spies = [
    jest.spyOn(Date, "now").mockImplementation(refuse("the clock")),
    jest.spyOn(Math, "random").mockImplementation(refuse("randomness")),
  ];
  try {
    return build();
  } finally {
    spies.forEach((spy) => spy.mockRestore());
  }
};

const clone = (value) => JSON.parse(JSON.stringify(value));

/** A copy of `base` with `change` made to it in place. */
const edit = (base, change) => {
  const copy = clone(base);
  change(copy);
  return copy;
};

/** The elements of `list` in the order of `ids`. */
const inOrder = (list, ids) => ids.map((id) => list.find((element) => element.id === id));

// An LZ/PZ as a pack item holds it: the library's diagram document less what is each person's own (packLz.js).
// Aircraft ids are the clock's milliseconds, so numbers; the other graphics' ids are text.
const LZ = {
  schemaVersion: 2,
  status: "analyzed",
  target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  mapData: { mgrs: "16S GD 66993 52949" },
  flightData: { landing_hdg: "270°", takeoff_hdg: "090°", callSign: "HAWK 6", frequency: "251.0" },
  analysis: {
    customLZ: null,
    detectedLZ: [[34.7841, -84.0826], [34.7843, -84.0817], [34.7835, -84.0815], [34.7833, -84.0824]],
    results: { area: 5230, capacity: 4, slope: { max: 6.2, mean: 2.1 }, histogram: [12, 30, 41, 9, 2] },
    gridElevation: "4050",
    latLong: "N 34°47'01.7\" W 084°04'55.7\"",
  },
  graphics: {
    doghouses: [
      { id: "dh1", role: "landing", label: "[SP1]", heading: "270°", time: "03+20", distance: "12.5km", airspeed: "100 kts", lat: 34.7851, lon: -84.0752 },
      { id: "dh2", role: "takeoff", label: "[RP1]", heading: "090°", lat: 34.7825, lon: -84.0891 },
    ],
    helicopters: [
      { id: 1759327200001, lat: 34.78381, lon: -84.08231, heading: 270, profileRef: "uh60l" },
      { id: 1759327200002, lat: 34.78392, lon: -84.08187, heading: 270, profileRef: "uh60l" },
      { id: 1759327200003, lat: 34.78364, lon: -84.08176, heading: 270, profileRef: "uh60l" },
      { id: 1759327200004, lat: 34.78352, lon: -84.08215, heading: 270, profileRef: "uh60l" },
    ],
    pzMarkers: [{ id: "pz-1759327200100", lat: 34.7837, lon: -84.0821, tipLat: 34.7837, tipLon: -84.0842 }],
    // A sector's corners are objects without ids, so the list of them is sent whole.
    sectorsOfFire: [
      { id: "sec-1759327200200", points: [{ lat: 34.7848, lng: -84.0822 }, { lat: 34.7828, lng: -84.0812 }, { lat: 34.7828, lng: -84.0832 }] },
    ],
    goArounds: [],
    units: [{ id: "u-1759327200300", sidc: "SFGPUCI----D", uniqueDesignation: "A/1-75", higherFormation: "75 RGR", lat: 34.7846, lon: -84.0811 }],
    measurements: [],
    exportBox: null,
  },
};

const GO_AROUND = { id: "ga-1759327200400", lat: 34.7829, lon: -84.0822, direction: "left", rotation: 0 };
const HELO_5 = { id: 1759327200005, lat: 34.78341, lon: -84.08193, heading: 270, profileRef: "uh60l" };
const HELO_6 = { id: 1759327200006, lat: 34.78333, lon: -84.08171, heading: 270, profileRef: "uh60l" };

// A set of sketched routes as a pack item holds it (packRoutes.js). Each route's point ids start again at p1, so a
// point is told apart by its route's id.
const ROUTES = {
  version: 1,
  routes: [
    {
      id: "sketch-1759327200500-k3j9qa",
      name: "RED 1",
      color: "#FF453A",
      plan: {
        aircraft: "UH-60L Black Hawk",
        aircraftProfile: "uh60l",
        airspeed: { value: 100, type: "ground" },
        altitude: { value: 50, ref: "agl" },
        wind: { dirTrue: 270, speedKts: 12 },
        tempC: 15,
        fuelFlowLbHr: 960,
        date: "2026-10-09",
        perPoint: { p3: { altitude: { value: 200, ref: "agl" } } },
      },
      elevations: { p1: 1180, p3: 1415, p5: 4050 },
      points: [
        { id: "p1", lat: 34.6512, lon: -84.2931, ele: 1180, role: "start", kind: "amps", ptType: "ip", name: ".SP" },
        { id: "p2", lat: 34.6907, lon: -84.2405, ele: null, role: "waypoint", kind: "shaping", ptType: null, name: "" },
        { id: "p3", lat: 34.7214, lon: -84.1822, ele: 1415, role: "waypoint", kind: "amps", ptType: "turn", name: "ACP1" },
        { id: "p4", lat: 34.7601, lon: -84.1276, ele: null, role: "waypoint", kind: "shaping", ptType: null, name: "" },
        { id: "p5", lat: 34.783817, lon: -84.08219, ele: 4050, role: "end", kind: "amps", ptType: "target", name: ".TGT" },
      ],
    },
    {
      id: "sketch-1759327200600-x81mvp",
      name: "BLUE 1",
      color: "#0A84FF",
      plan: { aircraft: "UH-60L Black Hawk", aircraftProfile: "uh60l", airspeed: { value: 90, type: "ground" }, altitude: { value: 100, ref: "agl" } },
      elevations: {},
      points: [
        { id: "p1", lat: 34.783817, lon: -84.08219, ele: null, role: "start", kind: "amps", ptType: "target", name: ".TGT" },
        { id: "p2", lat: 34.6512, lon: -84.2931, ele: null, role: "end", kind: "amps", ptType: "ip", name: ".RP" },
      ],
    },
  ],
};

const NEW_POINT = { id: "p6", lat: 34.7432, lon: -84.1545, ele: null, role: "waypoint", kind: "shaping", ptType: null, name: "" };
const red = (doc) => doc.routes[0];
const redPoint = (doc, id) => red(doc).points.find((point) => point.id === id);
const reorderRed = (ids) => edit(ROUTES, (d) => { red(d).points = inOrder(red(d).points, ids); });

// A point set as a pack item holds it: the list of points itself, as an .LPS import made it.
const POINTS = [
  { id: "lps-0-3fa2c1", name: "ALPHA", description: "RELEASE POINT", group: "CP", icon: "", elevationFt: 1730, lat: 34.7023, lon: -84.2011 },
  { id: "lps-1-9be004", name: "BRAVO", description: "", group: "CP", icon: "", elevationFt: 1415, lat: 34.7214, lon: -84.1822 },
  { id: "lps-2-c51d7e", name: "CHARLIE", description: "", group: "", icon: "", elevationFt: 0, lat: 34.7489, lon: -84.1503 },
];

// Two elements whose ids have the same digits, one a number and one text: two different elements.
const LZ_ONE_AND_TEXT_ONE = edit(LZ, (d) => {
  d.graphics.pzMarkers = [
    { id: 1, lat: 34.7837, lon: -84.0821, tipLat: 34.7837, tipLon: -84.0842 },
    { id: "1", lat: 34.7839, lon: -84.0819, tipLat: 34.7839, tipLon: -84.084 },
  ];
});
const LZ_TWICE = edit(LZ, (d) => { d.graphics.units.push({ ...d.graphics.units[0], uniqueDesignation: "B/1-75" }); });

const change = (name, before, after) => ({ name, before, after });
const refused = (name, before, after) => ({ name, before, after, throws: true });
// The web drops a changed key named __proto__: diffObject assigns it into a plain object, which sets that object's
// prototype instead of adding a field. Pinned because the web is the reference; these do not rebuild `after`, and are
// marked `webBug` so a port's test can find them.
const dropped = (name, before, after) => ({ name, before, after, rebuilds: false, webBug: true });
// The web reads a field that a version does not have through JavaScript's prototype, where every object has a function
// called constructor (and toString, valueOf and others). So a field by such a name that the new version lacks is read as
// that function, which cannot be copied, and diffData fails. That is not a refusal; it is pinned because the web is the
// reference, and it goes once the web is fixed.
const fails = (name, before, after) => ({ name, before, after, throws: true, failure: /JSON/, webBug: true });

/** `object` with the keys in `first` moved to the front, as a writer other than the web might order them. */
const keysFirst = (object, first) => Object.fromEntries([
  ...first.map((key) => [key, object[key]]),
  ...Object.entries(object).filter(([key]) => !first.includes(key)),
]);

const CASES = [
  // -- Objects ---------------------------------------------------------------------------------------------------
  change("nothing changed, so nothing is sent", LZ, clone(LZ)),
  change("a changed field is a patch at the object that holds it", LZ, edit(LZ, (d) => { d.status = "targeted"; })),
  change(
    "several changed fields of one object are one patch: the new version's keys first, then the keys only the old one had",
    LZ,
    edit(LZ, (d) => {
      delete d.flightData.landing_hdg;
      d.flightData.takeoff_hdg = "100°";
      d.flightData.pax = 44;
    }),
  ),
  change("a field that went is sent as null, since no operation deletes a key", LZ, edit(LZ, (d) => { delete d.analysis.latLong; })),
  change("a field that was missing and is now null has not changed", LZ, edit(LZ, (d) => { d.flightData.notes = null; })),
  change("a field that was null and is now missing has not changed", LZ, edit(LZ, (d) => { delete d.analysis.customLZ; })),
  change("an object inside is followed into: only its changed field is sent, at its own path", LZ, edit(LZ, (d) => { d.analysis.results.slope.max = 7.4; })),
  change(
    "an object's own fields are patched before anything inside it, whatever the order of its keys",
    LZ,
    edit(LZ, (d) => {
      d.analysis.results.area = 5410;
      d.analysis.gridElevation = "4060";
    }),
  ),
  change(
    "changes inside go in the order of the new version's keys",
    LZ,
    edit(LZ, (d) => {
      d.graphics.helicopters[1].heading = 280;
      d.flightData.callSign = "HAWK 7";
      d.target.lat = 34.783901;
    }),
  ),
  change("an object where there was null is sent whole", LZ, edit(LZ, (d) => { d.graphics.exportBox = { north: 34.7861, south: 34.7812, east: -84.0791, west: -84.0853 }; })),
  change("an object that went is sent as null", LZ, edit(LZ, (d) => { d.analysis.results = null; })),
  change("an object that became text is sent whole", LZ, edit(LZ, (d) => { d.mapData = "16S GD 66993 52949"; })),
  change("text that became an object is sent whole", LZ, edit(LZ, (d) => { d.analysis.gridElevation = { ft: 4050, source: "dem" }; })),
  change("an object that became a list is sent whole, even an empty one", LZ, edit(LZ, (d) => { d.mapData = []; })),
  change("an empty list that became an object is sent whole", LZ, edit(LZ, (d) => { d.graphics.measurements = {}; })),
  change(
    "an empty object that became empty text is sent whole",
    edit(LZ, (d) => { d.flightData.notes = {}; }),
    edit(LZ, (d) => { d.flightData.notes = ""; }),
  ),
  change(
    "changes inside go in the order of the new version's keys, even where it lists them in another order than the old one",
    LZ,
    keysFirst(
      edit(LZ, (d) => {
        d.target.lat = 34.783901;
        d.flightData.callSign = "HAWK 7";
        d.graphics.helicopters[1].heading = 280;
      }),
      ["graphics", "flightData"],
    ),
  ),

  // -- Lists sent whole --------------------------------------------------------------------------------------------
  change("a boundary of [lat, lon] pairs is sent whole when one corner moves", LZ, edit(LZ, (d) => { d.analysis.detectedLZ[1] = [34.7844, -84.0817]; })),
  change(
    "a boundary is sent whole when a corner is added, beside the other changes to its object",
    LZ,
    edit(LZ, (d) => {
      d.analysis.detectedLZ.push([34.7837, -84.0829]);
      d.analysis.gridElevation = "4055";
    }),
  ),
  change("a list of numbers is sent whole", LZ, edit(LZ, (d) => { d.analysis.results.histogram[2] = 40; })),
  change("a list of objects without ids is sent whole, here inside an element found by its id", LZ, edit(LZ, (d) => { d.graphics.sectorsOfFire[0].points[1] = { lat: 34.7826, lng: -84.0809 }; })),
  change(
    "a list with an element that has no id is sent whole",
    LZ,
    edit(LZ, (d) => { d.graphics.helicopters.push({ lat: 34.78341, lon: -84.08193, heading: 270, profileRef: "uh60l" }); }),
  ),
  change("a list in which two elements share an id is sent whole", LZ, LZ_TWICE),
  change("a list that had two elements sharing an id is sent whole, even once they no longer do", LZ_TWICE, edit(LZ_TWICE, (d) => { d.graphics.units[1].id = "u-1759327200301"; })),
  change("a list with an element that is not an object is sent whole", LZ, edit(LZ, (d) => { d.graphics.units.push("u-1759327200301"); })),
  change("a list with an element that is null is sent whole", LZ, edit(LZ, (d) => { d.graphics.helicopters.push(null); })),
  change("an empty list that gains an element that is a list is sent whole", LZ, edit(LZ, (d) => { d.graphics.measurements.push([34.7841, -84.0826]); })),
  change("an element whose id is null has no id, so its list is sent whole", LZ, edit(LZ, (d) => { d.graphics.goArounds.push({ ...GO_AROUND, id: null }); })),
  change("an element whose id is true has no id, so its list is sent whole", LZ, edit(LZ, (d) => { d.graphics.goArounds.push({ ...GO_AROUND, id: true }); })),
  change("an element whose id is an object has no id, so its list is sent whole", LZ, edit(LZ, (d) => { d.graphics.goArounds.push({ ...GO_AROUND, id: { n: 1 } }); })),
  change(
    "a list that was null is sent whole, even though its elements have ids",
    edit(LZ, (d) => { d.graphics.goArounds = null; }),
    edit(LZ, (d) => { d.graphics.goArounds = [GO_AROUND]; }),
  ),
  change("a list that went is sent as null", LZ, edit(LZ, (d) => { d.graphics.helicopters = null; })),

  // -- Lists with ids ----------------------------------------------------------------------------------------------
  change("an empty list is a list with ids, so its first element is inserted at the front", LZ, edit(LZ, (d) => { d.graphics.goArounds.push(GO_AROUND); })),
  change(
    "a helicopter that moved is a patch at it, found by its id, which is a number",
    LZ,
    edit(LZ, (d) => {
      d.graphics.helicopters[1].lat = 34.78395;
      d.graphics.helicopters[1].lon = -84.08182;
    }),
  ),
  change("an element is inserted at the front, after null", LZ, edit(LZ, (d) => { d.graphics.helicopters.unshift(HELO_5); })),
  change("an element is inserted between two, after the one before it", LZ, edit(LZ, (d) => { d.graphics.helicopters.splice(2, 0, HELO_5); })),
  change("an element is inserted at the end, after the last", LZ, edit(LZ, (d) => { d.graphics.helicopters.push(HELO_5); })),
  change("new elements in a row are each inserted after the one before, the first at the front after null", LZ, edit(LZ, (d) => { d.graphics.helicopters.unshift(HELO_5, HELO_6); })),
  change("new elements in a row in the middle are each inserted after the one before", LZ, edit(LZ, (d) => { d.graphics.helicopters.splice(1, 0, HELO_5, HELO_6); })),
  change("an element is removed by its id", LZ, edit(LZ, (d) => { d.graphics.helicopters.splice(2, 1); })),
  change("elements removed are removed in the old order", LZ, edit(LZ, (d) => { d.graphics.helicopters = [d.graphics.helicopters[1], d.graphics.helicopters[3]]; })),
  change("a list emptied is every element removed", LZ, edit(LZ, (d) => { d.graphics.helicopters = []; })),
  change(
    "an element whose id changed is a different element: the old one is removed and the new one inserted",
    LZ,
    edit(LZ, (d) => { d.graphics.helicopters[1].id = HELO_5.id; }),
  ),
  change("a field of an object inside an element is patched at that object", ROUTES, edit(ROUTES, (d) => { red(d).plan.airspeed.value = 120; })),
  change("a per-point value deep in a route's plan is patched where it is", ROUTES, edit(ROUTES, (d) => { red(d).plan.perPoint.p3.altitude.value = 300; })),
  change(
    "a route's own field is patched before the points inside it",
    ROUTES,
    edit(ROUTES, (d) => {
      red(d).name = "RED 2";
      redPoint(d, "p3").lat = 34.7219;
      redPoint(d, "p3").lon = -84.1815;
    }),
  ),
  change("the same point id in two routes is told apart by the route's id", ROUTES, edit(ROUTES, (d) => { d.routes[1].points[0].name = ".LZ"; })),
  change("a point inserted into a route goes after its neighbour, in that route", ROUTES, edit(ROUTES, (d) => { red(d).points.splice(3, 0, NEW_POINT); })),
  change(
    "patches and inserts in one list go in the new order: a point changed before a new one is patched before the insert",
    ROUTES,
    edit(ROUTES, (d) => {
      redPoint(d, "p2").lat = 34.6911;
      red(d).points.splice(3, 0, NEW_POINT);
    }),
  ),
  change("a point is removed from a route", ROUTES, edit(ROUTES, (d) => { red(d).points.splice(1, 1); })),
  change(
    "a new route is inserted whole, its points and all",
    ROUTES,
    edit(ROUTES, (d) => {
      d.routes.push({ id: "sketch-1759327200700-q0w2rt", name: "WHITE 1", color: "#FFD60A", plan: {}, elevations: {}, points: clone(d.routes[1].points) });
    }),
  ),
  change("a route that moved is removed and inserted whole, its points and all", ROUTES, edit(ROUTES, (d) => { d.routes.reverse(); })),

  // -- Moves: the elements whose order did not change stay; the others are removed and inserted ---------------------
  change("a list reversed keeps the element that ends up last and moves every other in front of it", ROUTES, reorderRed(["p5", "p4", "p3", "p2", "p1"])),
  change("a list rotated forward by one moves only the element that went round to the end", ROUTES, reorderRed(["p2", "p3", "p4", "p5", "p1"])),
  change("a list rotated back by one moves only the element that went round to the front", ROUTES, reorderRed(["p5", "p1", "p2", "p3", "p4"])),
  change("when two neighbours swap, the one now in front is the one moved", ROUTES, reorderRed(["p1", "p3", "p2", "p4", "p5"])),
  change(
    "an element that moved and changed is inserted with its new content, and no patch",
    ROUTES,
    edit(ROUTES, (d) => {
      red(d).points = inOrder(red(d).points, ["p1", "p2", "p4", "p3", "p5"]);
      redPoint(d, "p4").lat = 34.7588;
    }),
  ),
  change(
    "moves, inserts, removes and patches together: every remove first, then inserts and patches in the new order",
    ROUTES,
    edit(ROUTES, (d) => {
      const points = red(d).points;
      const [p1, , p3, p4, p5] = points;
      p4.lon = -84.1281;
      p5.ele = 4060;
      red(d).points = [p1, p5, NEW_POINT, p3, p4];
    }),
  ),

  // -- Ids ----------------------------------------------------------------------------------------------------------
  change(
    "a numeric id that became the same digits as text is a different element: removed and inserted",
    LZ,
    edit(LZ, (d) => { d.graphics.helicopters[0].id = String(d.graphics.helicopters[0].id); }),
  ),
  change(
    "the number 1 and the text \"1\" in one list are two elements, each patched by its own id",
    LZ_ONE_AND_TEXT_ONE,
    edit(LZ_ONE_AND_TEXT_ONE, (d) => { d.graphics.pzMarkers[1].tipLon = -84.0845; }),
  ),
  change(
    "the number 1 and the text \"1\" swapping places moves the text one",
    LZ_ONE_AND_TEXT_ONE,
    edit(LZ_ONE_AND_TEXT_ONE, (d) => { d.graphics.pzMarkers.reverse(); }),
  ),

  // -- Key order ----------------------------------------------------------------------------------------------------
  change(
    "keys that are array indices come first, in ascending order, as JavaScript lists an object's keys; \"042\" is not one",
    edit(LZ, (d) => { d.graphics.layers = JSON.parse('{"b": {"v": 1}, "10": {"v": 1}, "042": {"v": 1}, "2": {"v": 1}}'); }),
    edit(LZ, (d) => { d.graphics.layers = JSON.parse('{"b": {"v": 2}, "10": {"v": 2}, "042": {"v": 2}, "2": {"v": 2}}'); }),
  ),

  // -- Keys that would reach a prototype in JavaScript ------------------------------------------------------------
  change(
    "an object under a key named constructor is not followed: it is sent whole",
    edit(LZ, (d) => { d.flightData.constructor = { callSign: "HAWK 6", net: "A" }; }),
    edit(LZ, (d) => { d.flightData.constructor = { callSign: "HAWK 6", net: "B" }; }),
  ),
  change(
    "a list with ids under a key named prototype is not followed: it is sent whole",
    edit(LZ, (d) => { d.graphics.prototype = [{ id: "x-1", v: 1 }, { id: "x-2", v: 1 }]; }),
    edit(LZ, (d) => { d.graphics.prototype = [{ id: "x-1", v: 1 }, { id: "x-2", v: 2 }]; }),
  ),
  dropped(
    "an object under a key named __proto__ that changed is not sent at all (a web bug: rebuilds is false)",
    JSON.parse('{"schemaVersion": 2, "status": "analyzed", "__proto__": {"polluted": false}}'),
    JSON.parse('{"schemaVersion": 2, "status": "analyzed", "__proto__": {"polluted": true}}'),
  ),
  dropped(
    "a changed key named __proto__ is left out of its object's patch, and the other fields are sent (a web bug: rebuilds is false)",
    JSON.parse('{"schemaVersion": 2, "status": "analyzed", "__proto__": {"polluted": false}}'),
    JSON.parse('{"schemaVersion": 2, "status": "targeted", "__proto__": {"polluted": true}}'),
  ),
  change(
    "a key named __proto__ inside an element that is inserted travels with it as data",
    LZ,
    edit(LZ, (d) => { d.graphics.units.push(JSON.parse('{"id": "u-1759327200301", "sidc": "SHGPUCI----D", "__proto__": {"polluted": true}}')); }),
  ),
  fails(
    "a field named constructor that went makes the web fail: it reads the constructor every object inherits and cannot copy it (a web bug)",
    edit(LZ, (d) => { d.flightData.constructor = { callSign: "HAWK 6", net: "A" }; }),
    LZ,
  ),

  // -- A point set: the item's data is the list itself, so paths start at the top -------------------------------------
  change("a point in a point set is patched at a path that starts with its id", POINTS, edit(POINTS, (d) => { d[1].elevationFt = 1420; })),
  change("a point is inserted into a point set, into the list at the empty path", POINTS, edit(POINTS, (d) => { d.splice(1, 0, { id: "lps-3-0d4e11", name: "DELTA", description: "", group: "", icon: "", elevationFt: 980, lat: 34.7302, lon: -84.1688 }); })),
  change("a point is removed from a point set", POINTS, edit(POINTS, (d) => { d.splice(0, 1); })),
  change("a point moved to the front of a point set is removed and inserted after null", POINTS, edit(POINTS, (d) => { d.unshift(d.pop()); })),
  change("a list without ids that did not change sends nothing, and is not refused", [{ name: "ALPHA" }, { name: "BRAVO" }], [{ name: "ALPHA" }, { name: "BRAVO" }]),

  // -- Refused: the item's data would have to be replaced whole ---------------------------------------------------
  refused("an LZ cannot become a list", LZ, POINTS),
  refused("a point set cannot become an object", POINTS, { points: POINTS }),
  refused("a top-level list without ids cannot be changed", [{ name: "ALPHA" }, { name: "BRAVO" }], [{ name: "ALPHA" }, { name: "CHARLIE" }]),
  refused("a point set whose new version has two points with one id cannot be changed", POINTS, edit(POINTS, (d) => { d.push({ ...d[0], name: "ALPHA 2" }); })),
  refused("an LZ cannot become null", LZ, null),
  refused("null cannot become an LZ", null, LZ),
];

// [name, a, b]: whether sameData calls a and b the same.
const SAME = [
  ["a document and its copy are the same", LZ, clone(LZ)],
  ["the order of an object's keys does not matter", { a: 1, b: { c: 2, d: [3, 4] } }, { b: { d: [3, 4], c: 2 }, a: 1 }],
  ["a missing field and a null one are the same", { a: 1 }, { a: 1, b: null }],
  ["a null field and a missing one are the same", { a: 1, b: null }, { a: 1 }],
  ["a missing field and a null one are the same deep inside", { a: { b: [{ id: "x", c: null }] } }, { a: { b: [{ id: "x" }] } }],
  ["null and null are the same", null, null],
  ["an empty list is not null", [], null],
  ["an empty list is not a missing field", { a: [] }, {}],
  ["an empty object is not null", {}, null],
  ["an empty object is not a missing field", { a: {} }, {}],
  ["an empty list is not an empty object", [], {}],
  ["an empty object is not 0", {}, 0],
  ["an empty object is not empty text", {}, ""],
  ["an object with a length field is not a list", { length: 0 }, []],
  ["0 is not false", 0, false],
  ["the text \"1\" is not the number 1", "1", 1],
  ["empty text is not null", "", null],
  ["true is true", true, true],
  ["true is not the text \"true\"", true, "true"],
  ["a list's order matters", [1, 2], [2, 1]],
  ["lists inside lists of different lengths differ", [[34.5, -84.1], [34.6, -84.2]], [[34.5, -84.1], [34.6]]],
  ["a null in a list is not a missing element", [null], []],
  ["a null in a list is not 0", [null], [0]],
  ["a list is not an object with numbered keys", [7], { 0: 7 }],
  ["false is not a missing field", { a: false }, {}],
  ["0 is not a missing field", { a: 0 }, {}],
  ["one field changed deep inside is a difference", LZ, edit(LZ, (d) => { d.graphics.helicopters[3].heading = 271; })],
  // [name, a, b, webBug]: the web reads a missing field through JavaScript's prototype, so a field named after one of
  // its members, null on one side and missing on the other, reads as null against a function (or against the
  // prototype itself, for __proto__), and the two are called different. A plain reading of the JSON calls them the same.
  ["a null field named toString and a missing one are different to the web (a web bug)", { a: 1, toString: null }, { a: 1 }, true],
  ["a null field named constructor and a missing one are different to the web (a web bug)", { constructor: null }, {}, true],
  ["a null field named __proto__ and a missing one are different to the web (a web bug)", JSON.parse('{"__proto__": null}'), {}, true],
  ["fields named __proto__ that differ are a difference", JSON.parse('{"__proto__": {"x": 1}}'), JSON.parse('{"__proto__": {"x": 2}}')],
  ["fields named constructor that are equal are the same", { constructor: { x: 1 } }, { constructor: { x: 1 } }],
];

const kindOf = (data) => {
  if (Array.isArray(data)) return "pointset";
  return Array.isArray(data?.routes) ? "route" : "lz";
};

/** Applies `ops` to `data` as the server would, insisting each one is well formed and applies. */
const applyAll = (data, ops) => {
  let items = { it: { kind: kindOf(data), name: "IT", data: clone(data), deleted: false } };
  ops.forEach((op, i) => {
    const full = { ...op, item: "it" };
    expect([i, validatePackOp(full)]).toEqual([i, null]);
    const result = applyPackOp(items, full);
    expect([i, op.type, result.status, result.reason]).toEqual([i, op.type, "applied", null]);
    items = result.items;
  });
  return items.it.data;
};

const outcome = (before, after) => {
  try {
    // As written to the file: what a port reads.
    return { ops: clone(diffData(before, after)) };
  } catch (error) {
    return { throws: true, error };
  }
};

const diffFixture = () => withoutClockOrRandomness(() => ({
  description: "What an editor's change to a mission-pack item is sent as, as frontend/src/feature/missionPacks/packDiff.js "
    + "works it out (the reference). `cases`: an item's data `before` and `after` (an LZ or a set of routes is an object, "
    + "a point set the list of points itself) and `ops`, exactly what diffData(before, after) returns, in order (the order "
    + "is part of the contract; key order inside an operation's `value` is not: compare values as JSON objects). An "
    + "operation here has no `item` or `client_op_id`; it is applied with the rules of packs/ops.json. `throws: true` "
    + "instead of `ops` where diffData refuses, because the data would have to be replaced whole: the two top-level values "
    + "differ and are neither both objects nor both lists with ids. "
    + "`webBug: true` marks a case (and a `sameData` pair) that pins a known web bug, kept because the web is the reference "
    + "until it is fixed and this file regenerated (AGENTS.md §15); a port's test can find them by the mark, and nothing "
    + "else is marked. They all come from the web reading a field through JavaScript's prototype: every object inherits "
    + "functions called constructor, toString, toLocaleString, valueOf, hasOwnProperty, isPrototypeOf, "
    + "propertyIsEnumerable, __defineGetter__, __defineSetter__, __lookupGetter__ and __lookupSetter__, and __proto__ "
    + "reads as the prototype itself. So (1) a field by one of those names that the new version lacks (gone, or null "
    + "before and missing now), in an object that is diffed field by field rather than sent whole, is read as that "
    + "function, which cannot be copied, and diffData fails (`throws: true, webBug: true`); (2) sameData calls a null "
    + "field by such a name (or __proto__) and a missing one different, and a field that was missing and is now null is "
    + "sent as null; (3) a changed key named __proto__ is dropped from its object's patch (`rebuilds: false, webBug: "
    + "true`: diffObject assigns it into a plain object, which sets that object's prototype instead of adding a field). "
    + "The fix (reading fields only as the object's own, and keeping __proto__ as data) belongs on the web first. "
    + "The rules: nothing is sent when sameData(before, after). An object's changed fields go as one `patch` at that "
    + "object, keyed in the new version's key order and then the keys only the old one had (a field that went is sent as "
    + "null: no operation deletes a key). After that patch come the changes inside it, key by key in the same order: a "
    + "field that is an object on both sides is followed into, and so is one that is a list with ids on both sides; any "
    + "other changed field is sent whole in the patch, as is a field named __proto__, constructor or prototype, which is "
    + "never followed. Key order is JavaScript's: a port must read JSON keeping each object's keys in order, and list them "
    + "as Object.keys does: first the keys that are canonical array indices (\"0\", or a digit 1-9 followed by digits, "
    + "with a value at most 4294967294), in ascending numeric order, then every other key in the order it was written "
    + "(\"042\", \"00\" and \"4294967295\" are other keys). This file is written in that order already, but a "
    + "document read from elsewhere (the server keeps keys as sent; a port's own store) need not be. "
    + "A list with ids is a list whose every element is an object with an `id` that is text or a finite "
    + "number, no id twice (an empty list is one). Ids compare as JavaScript compares them: the number 1 and the text "
    + "\"1\" are different ids, so a number must stay a number in a path; numbers compare by value, so 1 and 1.0 (which "
    + "JSON.parse reads as the same number) are one id, and a port comparing JSON numbers by their text must not. "
    + "In a list with ids: first a `remove` for every element that went or moved, in the old "
    + "order; then, going through the new list in order, an `insert` for every element that is new or moved (whole, with "
    + "`after` the id of the element before it in the new list, or null at the front), or the changes inside an element "
    + "that stayed, at the path [..., {id}]. The elements that stayed are the longest run of the remaining ones whose old "
    + "positions only go up, found as packDiff.js longestRising does: for each remaining element in the new order, "
    + "binary-search the first run end whose old position is not less than its own, put it there, and remember the run end "
    + "before it; then read back from the last run end. When several runs are as long, that is the run it picks, and a "
    + "port must pick the same. Every case without `throws` rebuilds `after` when its ops are applied in order to "
    + "`before` (checked when this file is written), except those with `rebuilds: false`. JSON has no undefined: a key the "
    + "web leaves out is missing here, and a missing key reads as null wherever these documents are read. A non-finite "
    + "number cannot be written in JSON, so no case has one; as an id it is no id. `sameData`: two values `a` and `b` and "
    + "whether sameData calls them the same: equal as JSON, a missing field and a null one the same (but see webBug), key "
    + "order ignored, list order and length not.",
  generatedBy: GENERATED_BY,
  cases: CASES.map((c) => {
    const before = clone(c.before);
    const after = clone(c.after);
    const { ops, throws } = outcome(before, after);
    const bug = c.webBug ? { webBug: true } : {};
    if (throws) return { name: c.name, before, after, throws: true, ...bug };
    return { name: c.name, before, after, ops, ...(c.rebuilds === false ? { rebuilds: false } : {}), ...bug };
  }),
  sameData: SAME.map(([name, a, b, bug]) => ({
    name, a: clone(a), b: clone(b), same: sameData(clone(a), clone(b)), ...(bug ? { webBug: true } : {}),
  })),
}));

describe("mission pack diff fixture", () => {
  it("packs/diff.json", () => settle("packs/diff.json", diffFixture()));

  it("throws exactly where a case says the data would be replaced whole, and nowhere else", () => {
    CASES.forEach((c) => {
      const result = outcome(clone(c.before), clone(c.after));
      const refusal = result.throws ? result.error.message : null;
      expect([c.name, refusal]).toEqual([c.name, c.throws ? expect.stringMatching(c.failure ?? /never replaced whole/) : null]);
    });
  });

  it("rebuilds every case's `after` from its operations, applied in order with packOps", () => {
    const sent = diffFixture().cases.filter((c) => !c.throws);
    sent.forEach((c) => {
      const rebuilt = applyAll(c.before, c.ops);
      // A case marked as a web bug must really be one, so the mark goes once the web is fixed.
      expect([c.name, sameData(rebuilt, c.after)]).toEqual([c.name, c.rebuilds !== false]);
    });
    // And nothing is sent exactly when nothing changed.
    sent
      .filter((c) => c.rebuilds !== false)
      .forEach((c) => expect([c.name, c.ops.length === 0]).toEqual([c.name, sameData(c.before, c.after)]));
  });

  it("covers every kind of operation and every branch it was written for", () => {
    const { cases, sameData: pairs } = diffFixture();
    const ops = cases.flatMap((c) => c.ops ?? []);
    const count = (predicate) => ops.filter(predicate).length;
    // diffData never needs `set` or `upsert`: a change goes whole inside its object's patch.
    expect(new Set(ops.map((op) => op.type))).toEqual(new Set(["patch", "insert", "remove"]));
    expect(count((op) => op.type === "patch")).toBeGreaterThanOrEqual(30);
    expect(count((op) => op.type === "insert" && op.after === null)).toBeGreaterThanOrEqual(5);
    expect(count((op) => op.type === "insert" && op.after !== null)).toBeGreaterThanOrEqual(10);
    expect(count((op) => op.type === "remove")).toBeGreaterThanOrEqual(15);
    expect(count((op) => op.path.some((segment) => typeof segment === "object" && typeof segment.id === "number"))).toBeGreaterThanOrEqual(5);
    expect(count((op) => op.path.length > 0 && typeof op.path[0] === "object")).toBeGreaterThanOrEqual(2);
    expect(cases.filter((c) => c.ops && c.ops.length === 0).length).toBeGreaterThanOrEqual(4);
    // Six refusals and the one web bug that fails (`fails`).
    expect(cases.filter((c) => c.throws).length).toBe(7);
    expect(pairs.filter((p) => p.same).length).toBeGreaterThanOrEqual(7);
    expect(pairs.filter((p) => !p.same).length).toBeGreaterThanOrEqual(12);
    const names = [...cases, ...pairs].map((c) => c.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it("changes nothing it was given", () => {
    const before = JSON.stringify([CASES, SAME]);
    diffFixture();
    CASES.forEach((c) => outcome(c.before, c.after));
    expect(JSON.stringify([CASES, SAME])).toBe(before);
  });

  it("marks as webBug exactly the cases and pairs that pin a web bug, so each mark goes once the web is fixed", () => {
    const own = (object, key) => (Object.prototype.hasOwnProperty.call(object, key) ? object[key] : undefined);
    // Equal as JSON, reading only an object's own fields: what sameData is meant to say.
    const plainSame = (a, b) => {
      if (a === b) return true;
      if (a == null || b == null) return a == null && b == null;
      if (Array.isArray(a) || Array.isArray(b)) {
        return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, i) => plainSame(value, b[i]));
      }
      if (typeof a !== "object" || typeof b !== "object") return false;
      return [...new Set([...Object.keys(a), ...Object.keys(b)])].every((key) => plainSame(own(a, key), own(b, key)));
    };
    const { cases, sameData: pairs } = diffFixture();
    cases.forEach((c) => {
      const failed = c.throws && CASES.find((source) => source.name === c.name).failure !== undefined;
      expect([c.name, Boolean(c.webBug)]).toEqual([c.name, c.rebuilds === false || Boolean(failed)]);
    });
    pairs.forEach((p) => expect([p.name, Boolean(p.webBug)]).toEqual([p.name, p.same !== plainSame(p.a, p.b)]));
    expect(pairs.filter((p) => p.webBug).length).toBeGreaterThanOrEqual(3);
  });

  it("says the same whichever way round sameData is asked", () => {
    diffFixture().sameData.forEach((p) => expect([p.name, sameData(p.b, p.a)]).toEqual([p.name, p.same]));
  });
});
