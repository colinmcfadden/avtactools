import {
  copyFromLibrary, copySummary, deleteItemOp, deleteSummary, libraryData, myEditsName, packItemId, renameSummary,
} from "../feature/missionPacks/packActions";
import { sameData } from "../feature/missionPacks/packDiff";
import { composeEdit } from "../feature/missionPacks/packEdit";
import { describeLzChange, lzDiagramFromItem, lzItemData, sharedLzData } from "../feature/missionPacks/packLz";
import { packLocalId } from "../feature/missionPacks/packRef";
import { describeRouteChange, routeSetData, routesFromItem, sharedRouteData } from "../feature/missionPacks/packRoutes";
import { actorName, createSummary, newItemOp, updateFromOriginalSummary } from "../feature/missionPacks/packSentences";
import { describePointsChange, newPointSetOp, pointsForPack } from "../feature/missionPacks/usePackPoints";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// copyFromLibrary posts through packApi and the app's axios client. The client is stood in for by one that answers at
// once with what it was sent, so the fixture holds the body packApi really posts, and where. A plain object, not
// jest.fn: CRA resets every mock's implementation before each test.
jest.mock("../feature/auth/api", () => ({
  __esModule: true,
  default: { post: (url, body) => ({ then: (take) => take({ data: { url, body } }) }) },
}));

// What of a mission-pack item is each person's own (never in a pack), and the sentences a client attaches to its edits
// for the pack's history. The web's feature/missionPacks/ is the reference; the native apps will be held to every case.

const GENERATED_BY = "frontend/src/contracts/packSharedFixtures.test.js (UPDATE_CONTRACTS=1)";

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

// None of these functions should need the clock or a random id. While a fixture is built, both throw, so a case that
// reached for either could not be written. `clock` (milliseconds) instead stops the clock there: edit.json is built
// that way twice, at two instants, and must come out the same (the LZ normaliser stamps a time it then drops).
const withoutClockOrRandomness = (build, { clock } = {}) => {
  const RealDate = global.Date;
  const random = Math.random;
  const hadCrypto = Object.prototype.hasOwnProperty.call(window, "crypto");
  const crypto = window.crypto;
  const refuse = (what) => () => {
    throw new Error(`a fixture case reached for ${what}: give it what it needs`);
  };
  const now = clock === undefined ? refuse("the clock") : () => clock;
  global.Date = new Proxy(RealDate, {
    construct: (target, args) => (args.length === 0 ? new target(now()) : new target(...args)),
    get: (target, key) => (key === "now" ? now : Reflect.get(target, key)),
  });
  Math.random = refuse("randomness");
  Object.defineProperty(window, "crypto", { configurable: true, value: { randomUUID: refuse("a random id") } });
  try {
    return build();
  } finally {
    global.Date = RealDate;
    Math.random = random;
    if (hadCrypto) Object.defineProperty(window, "crypto", { configurable: true, value: crypto });
    else delete window.crypto;
  }
};

const clone = (value) => JSON.parse(JSON.stringify(value));
const changed = (base, change) => {
  const copy = clone(base);
  change(copy);
  return copy;
};

const HELI = "\u{1F681}"; // outside the basic plane: two UTF-16 code units

// == shared.json ==================================================================================================

const DIAGRAM = {
  schemaVersion: 2,
  id: "d-1",
  savedId: 12,
  name: "LZ HAWK",
  dirty: true,
  createdAt: "2026-09-01T12:00:00.000Z",
  updatedAt: "2026-09-02T08:30:00.000Z",
  status: "analyzed",
  target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  mapData: { zoom: 17 },
  flightData: { callSign: "HAWK 6", landing_hdg: "270°", takeoff_hdg: "090°" },
  analysis: {
    latLong: "34.78382, -84.08219",
    gridElevation: "4050",
    customLZ: null,
    detectedLZ: [[34.7832, -84.0828], [34.7844, -84.0828], [34.7844, -84.0814]],
    terrainData: { width: 2, height: 1, slopes: [3.5, 7.25] },
    results: { status: "success", elevation: "4050" },
  },
  graphics: {
    doghouses: [],
    helicopters: [{ id: 1759900000001, lat: 34.7837, lon: -84.0823, heading: 270, profileRef: "uh60l" }],
    pzMarkers: [],
    sectorsOfFire: [],
    goArounds: [],
    units: [],
    measurements: [],
    exportBox: null,
  },
  view: { mapStyle: "satellite", showLZOutline: true, showHeatmap: false },
};

// [name, data]; data undefined is "not given" and is absent from the case.
const LZ_SHARED = [
  ["a diagram loses each person's own fields, its name and the slope raster", DIAGRAM],
  ["the same names deeper in the document are kept", {
    status: "analyzed",
    flightData: { name: "HAWK", view: "kept", id: 4 },
    analysis: { detectedLZ: null, terrainData: "RASTER", detail: { terrainData: "kept", id: 1 } },
    graphics: { units: [{ id: "u-1", name: "kept", view: "kept", dirty: true }] },
  }],
  ["an own field that is null is removed all the same", {
    id: null, savedId: null, dirty: false, createdAt: null, updatedAt: null, view: null, name: null, status: "targeted",
  }],
  ["a document of only own fields is empty", {
    id: "d-1", savedId: 3, dirty: true, createdAt: "2026-09-01T12:00:00.000Z", updatedAt: "2026-09-01T12:00:00.000Z",
    view: { mapStyle: "topo" }, name: "LZ HAWK",
  }],
  ["a document with none of them is copied as it is", {
    schemaVersion: 2, status: "targeted", target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  }],
  ["fields this version does not know are kept, at the top and in the analysis", {
    status: "targeted",
    weather: { wind: "270/12" },
    futureField: [1, { keep: null }],
    analysis: { terrainData: { width: 1 }, futureAnalysis: true },
  }],
  ["an analysis without a slope raster is unchanged", { status: "analyzed", analysis: { customLZ: [[34.5, -84.1], [34.6, -84.1], [34.6, -84.2]] } }],
  ["an analysis that is null is kept as null", { status: "targeted", analysis: null }],
  ["an analysis that is text is kept as it is", { status: "targeted", analysis: "pending" }],
  ["an analysis that is a number is kept as it is", { status: "targeted", analysis: 0 }],
  ["an analysis that is a list is kept as it is", { status: "targeted", analysis: [1, { terrainData: "kept" }] }],
  ["an empty document stays empty", {}],
  ["no document (null) is an empty one", null],
  ["no document at all (data missing) is an empty one", undefined],
  ["a document that is a list is returned as it is", [1, { id: "d-1", name: "kept", terrainData: "kept" }]],
  ["a document that is text is returned as it is", "LZ HAWK"],
  ["a document of the number 0 is returned as it is, not made empty", 0],
  ["a document of false is returned as it is, not made empty", false],
  ["a document of empty text is returned as it is, not made empty", ""],
];

const SKETCH_RED = {
  id: "sketch-1759900000001-k3j9",
  name: "RED 1",
  color: "#FF453A",
  visible: false,
  setId: "pack:p-1:rt-1",
  plan: { airspeed: { value: 100, type: "ground" }, altitude: { value: 50, ref: "agl" }, perPoint: {} },
  elevations: { p1: 1730 },
  points: [
    { id: "p1", lat: 34.7, lon: -84.1, ele: null, role: "start", kind: "amps", ptType: "target", name: ".TGT" },
    { id: "p2", lat: 34.71, lon: -84.09, ele: null, role: "waypoint", kind: "amps", ptType: "ip", name: ".RP" },
  ],
};
const SKETCH_BLUE = {
  id: "sketch-1759900000002-m2p8",
  name: "BLUE 1",
  color: "#0A84FF",
  visible: true,
  setId: null,
  plan: { airspeed: { value: 90, type: "ground" } },
  elevations: {},
  points: [],
};
const { visible: _redVisible, setId: _redSet, ...RED_SHARED } = SKETCH_RED;

const ROUTES_SHARED = [
  ["each route loses whether it is hidden and the set it is filed under", { version: 1, routes: [SKETCH_RED, SKETCH_BLUE] }],
  ["a route without them is unchanged", { version: 1, routes: [RED_SHARED] }],
  ["visible and setId on a point are kept: only a route's own are removed", {
    version: 1,
    routes: [{ id: "r-1", visible: true, points: [{ id: "p1", lat: 34.7, lon: -84.1, visible: false, setId: "x" }] }],
  }],
  ["fields this version does not know are kept, on the set and on a route", {
    version: 1, futureSetField: "kept", routes: [{ id: "r-1", visible: true, futureField: { keep: [1, null] } }],
  }],
  ["a version other than 1 is kept as it is", { version: 2, routes: [{ id: "r-1", setId: "s" }] }],
  ["routes that are not objects are kept as they are", {
    version: 1, routes: [null, 7, "RED 1", true, { id: "r-1", visible: false, setId: "s" }],
  }],
  ["a route that is a list becomes an object keyed by position, as JavaScript's spread makes it", {
    version: 1, routes: [["a", "b"], []],
  }],
  ["routes that are an object are kept as they are", { version: 1, routes: { "r-1": { visible: false, setId: "s" } } }],
  ["routes that are null are kept as null", { version: 1, routes: null }],
  ["a set with no routes is copied as it is", { version: 1 }],
  ["an empty object stays empty", {}],
  ["no data (null) is an empty object", null],
  ["no data at all (data missing) is an empty object", undefined],
  ["data that is a list is returned as it is, own fields and all", [{ id: "r-1", visible: false, setId: "s" }]],
  ["data that is text is returned as it is", "MISSION 1"],
  ["data of the number 0 is returned as it is, not made empty", 0],
  ["data of false is returned as it is, not made empty", false],
  ["data of empty text is returned as it is, not made empty", ""],
];

const ROUTE_SETS = [
  ["the sketch's routes become a version-1 set without their own fields", [SKETCH_RED, SKETCH_BLUE]],
  ["a route with neither field is kept whole", [RED_SHARED]],
  ["fields the sketch does not know are kept", [{ id: "sketch-1", name: "RED 1", visible: true, futureField: { keep: true }, points: [] }]],
  ["an empty list is an empty set", []],
  ["no routes (null) is an empty set", null],
  ["no routes at all (routes missing) is an empty set", undefined],
];

const POINT_LISTS = [
  ["points keep their own ids", [
    { id: "lps-0-a1b2c3", name: "ALPHA", description: "TAXI A", group: "KGVL", icon: 1, elevationFt: 1276, lat: 34.2726, lon: -83.8302 },
    { id: "lps-1-d4e5f6", name: "BRAVO", description: "TAXI B", group: "KGVL", icon: 1, elevationFt: 1280, lat: 34.2731, lon: -83.8297 },
  ]],
  ["a point with no id is given one from its place in the list", [{ name: "ALPHA", lat: 34.5, lon: -84.1 }, { id: "lps-1", name: "BRAVO" }, { name: "CHARLIE" }]],
  ["a number id is kept as a number, fractions, negatives and zero too", [{ id: 7 }, { id: 2.5 }, { id: -3 }, { id: 0 }, { id: 1759900000001 }]],
  ["the number 7 and the text \"7\" are different ids", [{ id: 7, name: "N" }, { id: "7", name: "T" }]],
  ["an empty text is an id like any other", [{ id: "", name: "A" }, { id: "", name: "B" }]],
  ["a repeated id has its place in the list appended", [{ id: "a" }, { id: "a" }, { id: "b" }, { id: "a" }]],
  ["an appended id that is taken has its place appended again", [{ id: "a" }, { id: "a-2" }, { id: "a" }]],
  ["a repeated number id becomes text when its place is appended", [{ id: 5 }, { id: 5 }]],
  ["an id made from a place can be taken already", [{ id: "pt-1" }, { name: "no id" }]],
  ["an id that is true, false, null, an object or a list is replaced from the place", [
    { id: true }, { id: false }, { id: null }, { id: { n: 1 } }, { id: [1] },
  ]],
  ["fields a point carries are kept, whatever they are", [{ id: "x", futureField: { keep: [1] }, visible: false, setId: "kept" }]],
  ["a point that is null becomes a point with only an id", [null, { id: "a" }]],
  ["an empty list stays empty", []],
  ["no points (null) is an empty list", null],
  ["no points at all (points missing) is an empty list", undefined],
];

const EDIT_NAMES = [
  ["a name gets (my edits) after it", "LZ HAWK"],
  ["a name that comes to exactly 100 is kept whole", "X".repeat(89)],
  ["a longer one is cut to 100", "X".repeat(95)],
  ["a name of 100 loses the whole suffix", "X".repeat(100)],
  ["the cut counts UTF-16 units, so an emoji counts two", HELI.repeat(45)],
  // A web bug (webBug): the server cannot store a lone surrogate, so a library save under this name fails.
  ["an emoji across the cut leaves its first half", `${"X".repeat(99)}${HELI}`, true],
  ["an emoji that ends at the cut is kept whole", `${"X".repeat(98)}${HELI}`],
  ["a pack name of 100 emoji is cut to 50 of them", HELI.repeat(100)],
];

// [name, kind, data]: libraryData(kind, data), the form a kept version is saved to the library in.
const LIBRARY = [
  ["an LZ/PZ is saved as it is", "lz", { schemaVersion: 2, status: "analyzed", target: { lat: 34.783817, lon: -84.08219 }, futureField: 1 }],
  ["a point set is saved as it is", "pointset", [{ id: "lps-0", name: "ALPHA", lat: 34.5, lon: -84.1 }, { id: "x", futureField: true }]],
  ["a route set is saved as version 1 with its routes", "route", { version: 1, routes: [{ id: "r-1", name: "RED 1", points: [] }] }],
  ["a route set's other fields are not saved", "route", { version: 1, futureSetField: "dropped", routes: [] }],
  ["a route set of another version is saved as version 1", "route", { version: 2, routes: [{ id: "r-1" }] }],
  ["routes that are not a list are saved as none", "route", { version: 1, routes: { "r-1": { id: "r-1" } } }],
  ["a route set with no routes is saved with none", "route", { version: 1 }],
  ["no route set (null) is saved as one with no routes", "route", null],
];

const sharedFixture = () => withoutClockOrRandomness(() => ({
  description: "What of a mission-pack item is each person's own and never goes into a pack, as frontend/src/feature/missionPacks/ "
    + "works it out (the reference; docs/MISSION_PACKS.md §5a), and how a kept version of one is named and saved. Every case is "
    + "self-contained: its input, then what the web returns. "
    + "A key missing from a case is JavaScript's undefined (an argument not given), which JSON cannot hold; a port treats it as the "
    + "web treats undefined. Outputs are compared as JSON values (key order does not matter). "
    + "`lz`: packLz.sharedLzData(data) -> `shared`. The top-level fields id, savedId, dirty, createdAt, updatedAt, view and name are "
    + "removed whatever their value (null too), and analysis.terrainData when analysis is an object (a list or anything else is kept "
    + "as it is). The same names deeper in the document are kept, and so is everything else, fields this version does not know "
    + "included. data null or missing -> {}; data that is not an object (a list, text, a number, false) is returned as it is, "
    + "so 0, false and \"\" are not made {}. "
    + "`routes`: packRoutes.sharedRouteData(data) -> `shared`. When data.routes is a list, each element that is an object has its "
    + "`visible` and `setId` removed; null, numbers, text and booleans are kept as they are; an element that is itself a list becomes "
    + "an object keyed by position (\"0\", \"1\", ...), as JavaScript's {...list} makes it. routes that is not a list (missing, null, "
    + "an object) is kept as it is, and so is every other field. data null or missing -> {}; data that is not an object (a "
    + "list, text, a number, false) is returned as it is, a list's elements untouched. "
    + "`routeSet`: packRoutes.routeSetData(routes) -> `data`: {version: 1, routes} with `visible` and `setId` removed from each "
    + "route (the sketch's routes are always objects). routes null or missing -> {version: 1, routes: []}. "
    + "`points`: usePackPoints.pointsForPack(points) -> `forPack`: the same points in the same order, each with an id no other has. "
    + "An id is kept if it is text (empty text too) or a finite number; anything else (missing, null, true, false, an object, a "
    + "list) is replaced by \"pt-<index>\", index being the point's place in the list from 0. Ids are compared with their type, so "
    + "the number 7 and the text \"7\" are both kept. An id already taken has \"-<index>\" appended until it is free, which makes a "
    + "number id text (5 -> \"5-1\"). A point that is null becomes {id}. points null or missing -> []. A number too large for a double "
    + "(JSON.parse makes it Infinity) is not an id either; JSON cannot be written with one, so no case has it. "
    + "`myEditsName`: packSentences.myEditsName(name) (re-exported by packActions) -> `output`: the name a person's own version of "
    + "an item is saved to their library under when the pack would not take their edits (the native apps use it too): name "
    + "followed by \" (my edits)\", cut to 100 UTF-16 code units (JavaScript's slice), so a character outside the basic plane counts "
    + "two. One across the cut leaves its first half, a lone surrogate (written \\ud83d in this file): that case is marked "
    + "`webBug: true` and a port must not copy it. The server cannot store a lone surrogate (encoding it to UTF-8 fails, so the "
    + "save answers 500), and a port's UTF-8 encoder would send '?' in its place; the web is to be fixed (cut so that no "
    + "character is split) and this file regenerated (AGENTS.md §15). Until then a port's test skips the marked case. "
    + "`libraryData`: packActions.libraryData(kind, data) -> `saved`: the form that version is saved in (saveVersionToLibrary): "
    + "an LZ/PZ's document (lz_data) and a set's points (points) as they are; a route set as {version: 1, routes} whatever its "
    + "version and other fields were, routes being data.routes when that is a list and [] otherwise (route_data, sent as JSON "
    + "text with kind sketch). "
    + "Out of scope here: packRoutes.routesFromItem, the web sketch's own reading of a route item (it leaves out of the editor "
    + "any route with no id, and fills in the sketch's defaults); a port reads a route item with its own model.",
  generatedBy: GENERATED_BY,
  lz: LZ_SHARED.map(([name, data]) => ({ name, data, shared: sharedLzData(data) })),
  routes: ROUTES_SHARED.map(([name, data]) => ({ name, data, shared: sharedRouteData(data) })),
  routeSet: ROUTE_SETS.map(([name, routes]) => ({ name, routes, data: routeSetData(routes) })),
  points: POINT_LISTS.map(([name, points]) => ({ name, points, forPack: pointsForPack(points) })),
  myEditsName: EDIT_NAMES.map(([name, input, bug]) => ({ name, input, output: myEditsName(input), ...(bug ? { webBug: true } : {}) })),
  libraryData: LIBRARY.map(([name, kind, data]) => ({ name, kind, data, saved: libraryData(kind, data) })),
}));

// == describe.json ================================================================================================

const ITEM = "LZ HAWK";
const ACTOR = "Sam B.";
const TARGET = { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" };

// An analysed LZ/PZ's shared data, with two of most things: one named and one not, where a graphic can be named.
const LZ = {
  schemaVersion: 2,
  status: "analyzed",
  target: TARGET,
  mapData: { zoom: 17 },
  flightData: { callSign: "HAWK 6", landing_hdg: "270°", takeoff_hdg: "090°" },
  analysis: {
    gridElevation: "4050",
    customLZ: null,
    detectedLZ: [[34.7832, -84.0828], [34.7844, -84.0828], [34.7844, -84.0814]],
  },
  graphics: {
    doghouses: [
      { id: "lz-hawk-sp1", role: "takeoff", lat: 34.783817, lon: -84.08519, id_val: "[SP1]", heading: "090°", time: "01+57" },
      { id: "lz-hawk-rp1", role: "landing", label: "RP1", lat: 34.782317, lon: -84.08519, id_val: "[RP1]", heading: "270°", time: "02+10" },
    ],
    helicopters: [
      { id: 1759900000001, lat: 34.7837, lon: -84.0823, heading: 270, profileRef: "uh60l" },
      { id: 1759900000002, lat: 34.7839, lon: -84.0819, heading: 270, profileRef: "uh60l" },
    ],
    pzMarkers: [{ id: "pz-1759900000003", lat: 34.7832, lon: -84.0812, tipLat: 34.7832, tipLon: -84.0832 }],
    sectorsOfFire: [
      { id: "sec-1759900000004", name: "NORTH", color: "#ff0000", points: [{ lat: 34.7848, lng: -84.0822 }, { lat: 34.7838, lng: -84.0812 }, { lat: 34.7838, lng: -84.0832 }] },
      { id: "sec-1759900000005", color: "#ff0000", points: [{ lat: 34.7828, lng: -84.0822 }, { lat: 34.7818, lng: -84.0812 }, { lat: 34.7818, lng: -84.0832 }] },
    ],
    goArounds: [{ id: "ga-1759900000006", lat: 34.7808, lon: -84.08219, direction: "left", rotation: 0 }],
    units: [
      { id: "u-1759900000007", sidc: "SFGPUCI----D---", uniqueDesignation: "A/2-10", higherFormation: "2BCT", lat: 34.7851, lon: -84.0801 },
      { id: "u-1759900000008", sidc: "SHGPUCD----D---", uniqueDesignation: "", higherFormation: "", lat: 34.7901, lon: -84.0751 },
    ],
    measurements: [{ id: "m-1759900000009", start: [34.7837, -84.0823], end: [34.7808, -84.08219] }],
    exportBox: null,
  },
};

const TARGETED = {
  schemaVersion: 2,
  status: "targeted",
  target: TARGET,
  mapData: { zoom: 17 },
  flightData: { callSign: "HAWK 6" },
  analysis: { gridElevation: "", customLZ: null, detectedLZ: null },
  graphics: { doghouses: [], helicopters: [], pzMarkers: [], sectorsOfFire: [], goArounds: [], units: [], measurements: [], exportBox: null },
};

const RING = [[34.7831, -84.0829], [34.7845, -84.0829], [34.7845, -84.0813], [34.7831, -84.0813]];

// A case: the item before, the item after `change` (made to a copy), and what the item and the person are called.
// `who` may set itemName or actor to undefined, which leaves the key out of the case.
const lz = (name, change, { before = LZ, ...who } = {}) => ({ name, before, after: changed(before, change), itemName: ITEM, actor: ACTOR, ...who });
const lzPair = (name, before, after, who = {}) => ({ name, before, after, itemName: ITEM, actor: ACTOR, ...who });

const heli = (d) => d.graphics.helicopters;
const pz = (d) => d.graphics.pzMarkers;
const sectors = (d) => d.graphics.sectorsOfFire;
const ga = (d) => d.graphics.goArounds;
const units = (d) => d.graphics.units;
const dogs = (d) => d.graphics.doghouses;
const tape = (d) => d.graphics.measurements;
const turnHeli = (value) => (d) => { heli(d)[0].heading = value; };
// Two helicopters with one id, and one between them.
const TWINS = [
  { id: "h-x", lat: 34.7837, lon: -84.0823, heading: 270 },
  { id: "h-y", lat: 34.7839, lon: -84.0819, heading: 270 },
  { id: "h-x", lat: 34.7841, lon: -84.0815, heading: 270 },
];

const LZ_CASES = [
  // -- The LZ/PZ itself ------------------------------------------------------------------------------------------
  lz("analysing a targeted LZ/PZ says so", (d) => { d.status = "analyzed"; }, { before: changed(LZ, (d) => { d.status = "targeted"; }) }),
  lzPair("analysing is named first, whatever came with it", TARGETED, LZ),
  lzPair("a first version with nothing before is analysed if it is", null, LZ),
  lzPair("a first version with nothing before that is only targeted has had its target moved", null, TARGETED),
  lz("an LZ/PZ analysed again is not called analysed: its found boundary changed", (d) => { d.analysis.detectedLZ = RING; }),
  lz("the target moved", (d) => { d.target = { lat: 34.7841, lon: -84.0831, mgrs: "16S GD 66919 52975" }; }),
  lz("the target cleared", (d) => { d.target = null; }),
  lz("a target where there was none is moved", (d) => { d.target = TARGET; }, { before: changed(LZ, (d) => { d.target = null; }) }),
  lz("a boundary drawn by hand", (d) => { d.analysis.customLZ = RING; }),
  lz("a boundary drawn by hand and then cleared is still drawn", (d) => { d.analysis.customLZ = null; }, {
    before: changed(LZ, (d) => { d.analysis.customLZ = RING; }),
  }),
  lz("a boundary drawn by hand is named before a found one", (d) => { d.analysis.customLZ = RING; d.analysis.detectedLZ = RING; }),

  // -- Helicopters: Chalk n --------------------------------------------------------------------------------------
  lz("a helicopter added is named by its chalk number", (d) => { heli(d).push({ id: 1759900000010, lat: 34.7841, lon: -84.0815, heading: 270, profileRef: "uh60l" }); }),
  lz("a helicopter moved by its lat", (d) => { heli(d)[1].lat = 34.7841; }),
  lz("a helicopter turned to a whole heading", turnHeli(90)),
  lz("a heading of 44.5 rounds up to 45", turnHeli(44.5)),
  lz("a heading of 359.5 rounds up to 360, not wrapped", turnHeli(359.5)),
  lz("a heading of -0.5 rounds to 0, never -0", turnHeli(-0.5)),
  lz("a heading of -1.5 rounds up to -1", turnHeli(-1.5)),
  lz("a heading of the text \"090\" reads as 90", turnHeli("090")),
  lz("a heading with spaces round it reads as its number", turnHeli(" 45 ")),
  lz("a heading in exponent form reads as its number", turnHeli("1e2")),
  lz("a heading in hexadecimal reads as its number", turnHeli("0x5A")),
  lz("a heading of only spaces reads as 0", turnHeli(" ")),
  lz("a heading of true reads as 1", turnHeli(true)),
  lz("a heading in words is no heading", turnHeli("west")),
  lz("a heading of the text Infinity is no heading", turnHeli("Infinity")),
  lz("a heading set to null is no heading", turnHeli(null)),
  lz("a heading of false reads as 0", turnHeli(false)),
  lz("a heading of an empty list reads as 0", turnHeli([])),
  lz("a heading of a list of one number reads as that number", turnHeli([90])),
  lz("a heading with a plus sign reads as its number", turnHeli("+90")),
  lz("a heading after a no-break space reads as its number: JavaScript trims it", turnHeli("\u00A090")),
  lz("a heading after a byte order mark reads as its number: JavaScript trims it", turnHeli("\uFEFF90")),
  lz("a heading after a zero-width space is no heading: JavaScript does not trim it", turnHeli("\u200B90")),
  lz("a heading in binary reads as its number", turnHeli("0b1011010")),
  lz("a heading in octal reads as its number", turnHeli("0o132")),
  lz("a heading in hexadecimal with a minus sign is no heading", turnHeli("-0x5A")),
  lz("a heading with an underscore between its digits is no heading", turnHeli("1_000")),
  lz("a heading too large to write plainly is written as JavaScript's String writes it", turnHeli(1e21)),
  lz("an empty heading is passed over for the next turn key", (d) => { heli(d)[0].heading = ""; heli(d)[0].rotation = 15; }),
  lz("the heading is read first even when only another turn key changed", (d) => { heli(d)[0].rotation = 10; }),
  lz("a helicopter moved and turned is moved", (d) => { heli(d)[0].lat = 34.7835; heli(d)[0].heading = 90; }),
  lz("a helicopter changed in another field", (d) => { heli(d)[0].profileRef = "ch47f"; }),
  lz("a helicopter removed is named by its chalk number before", (d) => { heli(d).splice(1, 1); }),
  lz("the first of two helicopters removed is Chalk 1: its number is its place before the change", (d) => { heli(d).splice(0, 1); }),
  lz("chalk numbers count only helicopters with an id", (d) => { heli(d).push({ id: 1759900000010, lat: 34.7841, lon: -84.0815 }); }, {
    before: changed(LZ, (d) => { d.graphics.helicopters = [{ lat: 34.7835, lon: -84.0825 }, heli(d)[0]]; }),
  }),
  lz("a helicopter whose id is null is still followed", (d) => { heli(d)[0].lat = 34.7835; }, {
    before: changed(LZ, (d) => { d.graphics.helicopters = [{ id: null, lat: 34.7837, lon: -84.0823 }]; }),
  }),
  lz("helicopters with no id are not followed", (d) => { heli(d)[0].lat = 34.7835; }, {
    before: changed(LZ, (d) => { d.graphics.helicopters = [{ lat: 34.7837, lon: -84.0823 }]; }),
  }),
  lz("a null in a list of graphics is passed over and not counted", (d) => { heli(d)[2].lat = 34.7841; }, {
    before: changed(LZ, (d) => { d.graphics.helicopters.unshift(null); }),
  }),
  lz("the number 1 and the text \"1\" are two helicopters", (d) => { heli(d)[0].id = "1"; }, {
    before: changed(LZ, (d) => { d.graphics.helicopters = [{ id: 1, lat: 34.7837, lon: -84.0823 }]; }),
  }),
  lz("two helicopters sharing an id are read as the last of them, at the first one's place: Chalk 3 is named before Chalk 2", (d) => {
    heli(d)[1].lat = 34.784;
    heli(d)[2].lat = 34.7843;
  }, { before: changed(LZ, (d) => { d.graphics.helicopters = TWINS; }) }),
  lz("a change to the first of two helicopters sharing an id is not seen", (d) => { heli(d)[0].lat = 34.7835; }, {
    before: changed(LZ, (d) => { d.graphics.helicopters = TWINS; }),
  }),
  lz("a helicopter whose id is an object is never matched, so even unchanged it reads as added", () => {}, {
    before: changed(LZ, (d) => { d.graphics.helicopters = [{ id: { n: 1 }, lat: 34.7837, lon: -84.0823, heading: 270 }]; }),
  }),
  lz("a helicopter moved after another was removed has its new chalk number, and is named ahead of the removal", (d) => {
    heli(d)[1].lat = 34.7841;
    heli(d).splice(0, 1);
  }),

  // -- PZ markers -------------------------------------------------------------------------------------------------
  lz("a PZ marker added", (d) => { pz(d).push({ id: "pz-1759900000011", lat: 34.7829, lon: -84.0809, tipLat: 34.7829, tipLon: -84.0829 }); }),
  lz("a PZ marker moved by its lon", (d) => { pz(d)[0].lon = -84.0815; }),
  lz("dragging a PZ marker's tip only changes it: tipLat and tipLon are not position keys", (d) => { pz(d)[0].tipLat = 34.7842; pz(d)[0].tipLon = -84.0822; }),
  lz("a PZ marker kept with an anchor moves by it", (d) => { pz(d)[0].anchor = [34.7833, -84.0812]; }, {
    before: changed(LZ, (d) => { pz(d)[0].anchor = [34.7832, -84.0812]; }),
  }),
  lz("a PZ marker kept with a tip moves by it", (d) => { pz(d)[0].tip = [34.7842, -84.0812]; }, {
    before: changed(LZ, (d) => { pz(d)[0].tip = [34.7832, -84.0832]; }),
  }),
  lz("a PZ marker turned by its bearing", (d) => { pz(d)[0].bearing = 135; }),
  lz("a PZ marker's bearing emptied is a turn with no heading", (d) => { pz(d)[0].bearing = ""; }, {
    before: changed(LZ, (d) => { pz(d)[0].bearing = 45; }),
  }),
  lz("a PZ marker changed in another field", (d) => { pz(d)[0].color = "#00ff00"; }),
  lz("a PZ marker removed", (d) => { pz(d).splice(0, 1); }),

  // -- Sectors of fire: by name, else by place --------------------------------------------------------------------
  lz("a sector added with a name is named by it", (d) => { sectors(d).push({ id: "sec-1759900000012", name: "WEST", points: [] }); }),
  lz("a sector added with no name is named by its place among the sectors", (d) => { sectors(d).push({ id: "sec-1759900000012", points: [] }); }),
  lz("a named sector moved by its points", (d) => { sectors(d)[0].points[0].lat = 34.7851; }),
  lz("a sector with no name moved by its center", (d) => { sectors(d)[1].center = [34.7823, -84.0822]; }, {
    before: changed(LZ, (d) => { sectors(d)[1].center = [34.7821, -84.0822]; }),
  }),
  lz("a named sector turned by its rotation", (d) => { sectors(d)[0].rotation = 30; }),
  lz("a sector's rotation set to null is a turn with no heading", (d) => { sectors(d)[1].rotation = null; }, {
    before: changed(LZ, (d) => { sectors(d)[1].rotation = 30; }),
  }),
  lz("a named sector changed in another field", (d) => { sectors(d)[0].color = "#ffff00"; }),
  lz("a sector whose name is emptied is named by its place", (d) => { sectors(d)[0].name = ""; }),
  lz("a named sector removed", (d) => { sectors(d).splice(0, 1); }),
  lz("a sector with no name removed is named by its place before", (d) => { sectors(d).splice(1, 1); }),

  // -- Go-arounds -------------------------------------------------------------------------------------------------
  lz("a go-around added", (d) => { ga(d).push({ id: "ga-1759900000013", lat: 34.7868, lon: -84.08219, direction: "right", rotation: 0 }); }),
  lz("a go-around moved by its lat", (d) => { ga(d)[0].lat = 34.7811; }),
  lz("a go-around kept with lng moves by it", (d) => { ga(d)[0].lng = -84.0825; }, {
    before: changed(LZ, (d) => { delete ga(d)[0].lon; ga(d)[0].lng = -84.08219; }),
  }),
  lz("a go-around turned by its rotation", (d) => { ga(d)[0].rotation = 45; }),
  lz("a go-around's side changed reads its rotation, so it says 0°", (d) => { ga(d)[0].direction = "right"; }),
  lz("a go-around with no rotation whose side changed is a turn with no heading", (d) => { ga(d)[0].direction = "right"; }, {
    before: changed(LZ, (d) => { delete ga(d)[0].rotation; }),
  }),
  lz("a go-around changed in another field", (d) => { ga(d)[0].label = "GA 1"; }),
  lz("a go-around removed", (d) => { ga(d).splice(0, 1); }),

  // -- Units: by unique designation, else "a unit" ---------------------------------------------------------------
  lz("a unit added with a unique designation is named by it", (d) => { units(d).push({ id: "u-1759900000014", sidc: "SFGPUCI----D---", uniqueDesignation: "B/2-10", lat: 34.786, lon: -84.08 }); }),
  lz("a unit added with none is a unit", (d) => { units(d).push({ id: "u-1759900000014", sidc: "SFGPUCI----D---", lat: 34.786, lon: -84.08 }); }),
  lz("a unit with a designation moved by its lat", (d) => { units(d)[0].lat = 34.7855; }),
  lz("a unit with none moved by its lon", (d) => { units(d)[1].lon = -84.0755; }),
  lz("a unit turned by a heading given as text", (d) => { units(d)[0].heading = "045"; }),
  lz("a unit turned with no usable heading", (d) => { units(d)[1].heading = "NE"; }),
  lz("a unit's symbol changed", (d) => { units(d)[0].sidc = "SFGPUCIZ---D---"; }),
  lz("a unit whose designation changed is named by the new one", (d) => { units(d)[0].uniqueDesignation = "C/2-10"; }),
  lz("a unit with a designation removed", (d) => { units(d).splice(0, 1); }),
  lz("a unit with none removed", (d) => { units(d).splice(1, 1); }),

  // -- Doghouses: by `label`, else "a doghouse" (the box's own text is id_val, which is not read) ------------------
  lz("a doghouse added with a label is named by it", (d) => { dogs(d).push({ id: "dh-3", label: "SP2", lat: 34.784, lon: -84.0852, heading: "000°" }); }),
  lz("a doghouse added with none is a doghouse, whatever its id_val", (d) => { dogs(d).push({ id: "dh-3", id_val: "[SP2]", lat: 34.784, lon: -84.0852, heading: "000°" }); }),
  lz("a doghouse moved by its lat", (d) => { dogs(d)[0].lat = 34.7841; }),
  lz("a doghouse kept with a position moves by it", (d) => { dogs(d)[1].position = [34.7825, -84.0852]; }, {
    before: changed(LZ, (d) => { dogs(d)[1].position = [34.782317, -84.08519]; }),
  }),
  lz("a doghouse's heading carries a degree sign, so its turn has no heading", (d) => { dogs(d)[0].heading = "120°"; }),
  lz("a doghouse whose heading is a plain number is turned to it", (d) => { dogs(d)[1].heading = 275.5; }),
  lz("a doghouse changed in another field", (d) => { dogs(d)[0].time = "02+05"; }),
  lz("a doghouse with a label removed", (d) => { dogs(d).splice(1, 1); }),
  lz("a doghouse with none removed", (d) => { dogs(d).splice(0, 1); }),

  // -- Measurements -----------------------------------------------------------------------------------------------
  lz("a measurement added", (d) => { tape(d).push({ id: "m-1759900000015", start: [34.7837, -84.0823], end: [34.79, -84.08] }); }),
  lz("a measurement moved by its start", (d) => { tape(d)[0].start = [34.7838, -84.0823]; }),
  lz("a measurement moved by its end", (d) => { tape(d)[0].end = [34.781, -84.0822]; }),
  lz("a measurement turned by its bearing", (d) => { tape(d)[0].bearing = 200; }),
  lz("a measurement turned with no usable heading", (d) => { tape(d)[0].bearing = "N"; }),
  lz("a measurement changed in another field", (d) => { tape(d)[0].label = "330 m"; }),
  lz("a measurement removed", (d) => { tape(d).splice(0, 1); }),

  // -- Flight data -------------------------------------------------------------------------------------------------
  lz("the landing heading changed is named as it is written", (d) => { d.flightData.landing_hdg = "300°"; }),
  lz("the takeoff heading changed", (d) => { d.flightData.takeoff_hdg = "120°"; }),
  lz("both headings changed: the landing heading is named", (d) => { d.flightData.landing_hdg = "300°"; d.flightData.takeoff_hdg = "120°"; }),
  lz("an emptied landing heading falls through to the takeoff heading", (d) => { d.flightData.landing_hdg = ""; d.flightData.takeoff_hdg = "120°"; }),
  lz("an emptied landing heading alone is a change to the flight data", (d) => { d.flightData.landing_hdg = ""; }),
  lz("a landing heading of the number 0 is not named", (d) => { d.flightData.landing_hdg = 0; }),
  lz("a landing heading given as a number is written as JavaScript writes it", (d) => { d.flightData.landing_hdg = 90; }),
  lz("another field of the flight data", (d) => { d.flightData.callSign = "HAWK 7"; }),
  lz("flight data where there was none names its landing heading", (d) => { d.flightData = { callSign: "HAWK 6", landing_hdg: "270°" }; }, {
    before: changed(LZ, (d) => { delete d.flightData; }),
  }),

  // -- The LZ card area --------------------------------------------------------------------------------------------
  lz("the LZ card area set", (d) => { d.graphics.exportBox = { north: 34.79, south: 34.778, east: -84.075, west: -84.09 }; }),
  lz("the LZ card area cleared is still set", (d) => { d.graphics.exportBox = null; }, {
    before: changed(LZ, (d) => { d.graphics.exportBox = { north: 34.79, south: 34.778, east: -84.075, west: -84.09 }; }),
  }),

  // -- Nothing it names --------------------------------------------------------------------------------------------
  lz("a change to nothing it names is an edit", (d) => { d.mapData.zoom = 18; }),
  lz("no change at all is an edit", () => {}),
  lz("a missing field and a null one are no change", (d) => { d.graphics.exportBox = null; heli(d)[0].label = null; }, {
    before: changed(LZ, (d) => { delete d.graphics.exportBox; }),
  }),
  lz("helicopters in a new order are only an edit", (d) => { heli(d).reverse(); }),

  // -- Who and what ------------------------------------------------------------------------------------------------
  lz("an item with no name is the LZ/PZ", (d) => { heli(d)[1].lat = 34.7841; }, { itemName: undefined }),
  lz("an empty name is the LZ/PZ too", (d) => { heli(d)[1].lat = 34.7841; }, { itemName: "" }),
  lz("a null name is the LZ/PZ too", (d) => { heli(d)[1].lat = 34.7841; }, { itemName: null }),
  lz("no one named is Someone", (d) => { heli(d)[1].lat = 34.7841; }, { actor: undefined }),
  lz("an empty name for the person is Someone too", (d) => { heli(d)[1].lat = 34.7841; }, { actor: "" }),

  // -- Several changes at once: the first phrase is the sentence ---------------------------------------------------
  lz("the target is named before the boundary", (d) => { d.target = null; d.analysis.customLZ = RING; }),
  lz("the boundary is named before the graphics", (d) => { d.analysis.detectedLZ = RING; heli(d)[0].lat = 34.7835; }),
  lz("helicopters are named before PZ markers", (d) => { pz(d).push({ id: "pz-1759900000011", lat: 34.7829, lon: -84.0809 }); heli(d).splice(1, 1); }),
  lz("PZ markers are named before sectors", (d) => { sectors(d)[0].rotation = 30; pz(d)[0].color = "#00ff00"; }),
  lz("sectors are named before go-arounds", (d) => { ga(d)[0].lat = 34.7811; sectors(d)[0].color = "#ffff00"; }),
  lz("go-arounds are named before units", (d) => { units(d)[0].lat = 34.7855; ga(d)[0].label = "GA 1"; }),
  lz("doghouses come after units, so a unit is named first", (d) => { dogs(d)[0].lat = 34.7841; units(d)[1].lon = -84.0755; }),
  lz("doghouses are named before measurements", (d) => { tape(d)[0].label = "330 m"; dogs(d)[1].time = "02+12"; }),
  lz("turning the landing doghouse names the doghouse before the landing heading it sets", (d) => {
    dogs(d)[1].heading = "300°";
    d.flightData.landing_hdg = "300°";
  }),
  lz("graphics are named before the flight data", (d) => { tape(d)[0].label = "330 m"; d.flightData.takeoff_hdg = "120°"; }),
  lz("the flight data is named before the LZ card area", (d) => { d.flightData.callSign = "HAWK 7"; d.graphics.exportBox = { north: 34.79 }; }),
  lz("in a collection, the list after the change sets the order", (d) => { heli(d)[1].lat = 34.7841; heli(d)[0].heading = 90; }),

  // -- The cut at 300 ----------------------------------------------------------------------------------------------
  lz("a sentence over 300 is cut to 300", () => {}, { itemName: "X".repeat(296) }),
  // A web bug (webBug): the server cannot store the lone surrogate this leaves, so it answers the batch with 500.
  lz("an emoji across the cut leaves its first half", () => {}, { itemName: `${"X".repeat(285)}${HELI}X`, webBug: true }),
];

const RED = {
  id: "sketch-1759900000001-k3j9",
  name: "RED 1",
  color: "#FF453A",
  plan: { airspeed: { value: 100, type: "ground" }, altitude: { value: 50, ref: "agl" }, tempC: 15, perPoint: {} },
  elevations: { p1: 1730, p3: 1810 },
  points: [
    { id: "p1", lat: 34.7, lon: -84.1, ele: null, role: "start", kind: "amps", ptType: "target", name: ".TGT" },
    { id: "p2", lat: 34.71, lon: -84.09, ele: null, role: "waypoint", kind: "shaping" },
    { id: "p3", lat: 34.72, lon: -84.08, ele: null, role: "waypoint", kind: "amps", ptType: "ip", name: ".RP" },
  ],
};
const BLUE = {
  id: "sketch-1759900000002-m2p8",
  name: "BLUE 1",
  color: "#0A84FF",
  plan: { airspeed: { value: 90, type: "ground" }, altitude: { value: 100, ref: "agl" }, tempC: 15, perPoint: {} },
  elevations: {},
  points: [
    { id: "q1", lat: 34.75, lon: -84.05, ele: null, role: "start", kind: "amps", ptType: "target", name: ".TGT" },
    { id: "q2", lat: 34.76, lon: -84.04, ele: null, role: "waypoint", kind: "amps", ptType: "ip", name: ".SP" },
  ],
};
const ROUTES = { version: 1, routes: [RED, BLUE] };
const SET = "MISSION 1";
const WHITE = { id: "sketch-1759900000003-q7r1", name: "WHITE 1", color: "#30D158", points: [] };
const CP1 = { id: "p4", lat: 34.715, lon: -84.085, ele: null, role: "waypoint", kind: "amps", ptType: "turn", name: ".CP1" };

const rt = (name, change, { before = ROUTES, ...who } = {}) => ({ name, before, after: changed(before, change), itemName: SET, actor: ACTOR, ...who });
const rtPair = (name, before, after, who = {}) => ({ name, before, after, itemName: SET, actor: ACTOR, ...who });
const red = (d) => d.routes[0];
const blue = (d) => d.routes[1];
const withoutName = (route) => {
  const { name: _name, ...rest } = route;
  return rest;
};

const ROUTE_CASES = [
  rt("a route added is named", (d) => { d.routes.push(WHITE); }),
  rt("a route added with no name is \"a route\"", (d) => { d.routes.push(withoutName(WHITE)); }),
  rt("a route renamed", (d) => { red(d).name = "RED 2"; }),
  rt("a route given a name where it had none", (d) => { blue(d).name = "BLUE 1"; }, { before: { version: 1, routes: [RED, withoutName(BLUE)] } }),
  rt("a route whose name is emptied is renamed to \"a route\"", (d) => { red(d).name = ""; }),
  rt("a name that goes from missing to null is a change", (d) => { blue(d).name = null; }, { before: { version: 1, routes: [RED, withoutName(BLUE)] } }),
  rt("a named point added", (d) => { red(d).points.splice(2, 0, CP1); }),
  rt("a point added with no name", (d) => { red(d).points.push({ id: "p4", lat: 34.73, lon: -84.07, ele: null, role: "waypoint", kind: "shaping" }); }),
  rt("a named point moved by its lat", (d) => { red(d).points[0].lat = 34.701; }),
  rt("a point with no name moved by its lon", (d) => { red(d).points[1].lon = -84.091; }),
  rt("a point whose lat goes from missing to null has moved: lat and lon are compared with !==", (d) => { red(d).points[1].lat = null; }, {
    before: changed(ROUTES, (d) => { delete red(d).points[1].lat; }),
  }),
  rt("a point whose lat is the same number written as text has moved", (d) => { red(d).points[0].lat = "34.7"; }),
  rt("a named point changed", (d) => { red(d).points[2].ptType = "turn"; }),
  rt("a point with no name changed", (d) => { red(d).points[1].ele = 1750; }),
  rt("a point renamed is named by its new name", (d) => { red(d).points[2].name = ".LZ"; }),
  rt("a point whose name is emptied is \"a point\"", (d) => { red(d).points[0].name = ""; }),
  rt("a point given a field that is null is no change", (d) => { red(d).points[1].note = null; }),
  rt("a named point removed", (d) => { red(d).points.splice(2, 1); }),
  rt("a point with no name removed", (d) => { red(d).points.splice(1, 1); }),
  rt("the plan changed", (d) => { red(d).plan.airspeed.value = 110; }),
  rt("the ground elevations updated", (d) => { red(d).elevations.p2 = 1760; }),
  rt("a named route removed", (d) => { d.routes.splice(1, 1); }),
  rt("a route with no name removed", (d) => { d.routes.splice(1, 1); }, { before: { version: 1, routes: [RED, withoutName(BLUE)] } }),
  rt("a route's colour changed is only an edit", (d) => { red(d).color = "#FF9F0A"; }),
  rt("routes in a new order are only an edit", (d) => { d.routes.reverse(); }),
  rt("points in a new order are only an edit", (d) => { red(d).points.reverse(); }),
  rt("a route with no id is passed over", (d) => { d.routes.push({ name: "GHOST", points: [] }); }),
  rt("a route whose id is null is still followed", (d) => { d.routes.push({ id: null, name: "GHOST", points: [] }); }),
  rt("a point whose id is null is still followed", (d) => { red(d).points.push({ id: null, lat: 34.73, lon: -84.07, name: ".GHOST" }); }),
  rt("a route that is null is passed over", (d) => { d.routes.push(null); }),
  rt("a point that is null is passed over", (d) => { red(d).points.push(null); }),
  rtPair("the number 1 and the text \"1\" are two routes",
    { version: 1, routes: [{ id: 1, name: "ONE", points: [] }] }, { version: 1, routes: [{ id: "1", name: "ONE", points: [] }] }),
  rt("the number 1 and the text \"1\" are two points", (d) => { red(d).points[0].id = "1"; }, {
    before: changed(ROUTES, (d) => { red(d).points[0].id = 1; }),
  }),
  rt("two routes sharing an id are read as the last of them, at the first one's place", (d) => {
    d.routes[2].plan.tempC = 20;
    blue(d).plan.tempC = 25;
  }, { before: { version: 1, routes: [RED, BLUE, { ...RED, name: "RED 1 COPY" }] } }),
  rt("a point whose id is an object is never matched, so even unchanged it reads as added", () => {}, {
    before: changed(ROUTES, (d) => { red(d).points[1].id = { n: 2 }; }),
  }),
  rt("no change at all is an edit", () => {}),
  rtPair("with nothing before, every route is added and the first is named", null, ROUTES),
  rtPair("routes that are not a list are no routes", { version: 1, routes: null }, ROUTES),
  rtPair("with nothing after, every route is removed and the first is named", ROUTES, null),

  rt("a set with no name is the routes", (d) => { d.routes.splice(1, 1); }, { itemName: undefined }),
  rt("an empty name is the routes too", (d) => { d.routes.splice(1, 1); }, { itemName: "" }),
  rt("no one named is Someone", (d) => { d.routes.splice(1, 1); }, { actor: undefined }),
  rt("an empty name for the person is Someone too", (d) => { d.routes.splice(1, 1); }, { actor: "" }),

  rt("a rename is named before a change to the route's points", (d) => { red(d).name = "RED 2"; red(d).points[0].lat = 34.701; }),
  rt("points added are named before points removed, whatever their place", (d) => { red(d).points.splice(0, 1); red(d).points.push(CP1); }),
  rt("points are taken in their order after the change", (d) => { red(d).points[2].ptType = "turn"; red(d).points[0].ptType = "ip"; }),
  rt("a point's change is named before the plan", (d) => { red(d).plan.tempC = 20; red(d).points[2].lon = -84.081; }),
  rt("the plan is named before the elevations", (d) => { red(d).elevations.p2 = 1760; red(d).plan.tempC = 20; }),
  rt("a change to a route is named before a route removed, whichever came first", (d) => { blue(d).plan.tempC = 20; d.routes.splice(0, 1); }),
  rt("routes are taken in their order after the change", (d) => { d.routes.reverse(); d.routes[1].plan.tempC = 20; d.routes[0].plan.tempC = 25; }),
  rt("a route added after a changed one is named second", (d) => { red(d).plan.tempC = 20; d.routes.push(WHITE); }),

  rt("a sentence over 300 is cut to 300", () => {}, { itemName: "X".repeat(296) }),
  // A web bug (webBug): the server cannot store the lone surrogate this leaves, so it answers the batch with 500.
  rt("an emoji across the cut leaves its first half", () => {}, { itemName: `${"X".repeat(285)}${HELI}X`, webBug: true }),
];

const POINT_SET = "KGVL TAXI";
const POINTS = [
  { id: "lps-0-a1b2c3", name: "ALPHA", description: "TAXI A", group: "KGVL", icon: 1, elevationFt: 1276, lat: 34.2726, lon: -83.8302 },
  { id: "lps-1-d4e5f6", name: "BRAVO", description: "TAXI B", group: "KGVL", icon: 1, elevationFt: 1280, lat: 34.2731, lon: -83.8297 },
  { id: "lps-2-0789ab", description: "", group: "KGVL", icon: 2, elevationFt: 1290, lat: 34.2742, lon: -83.8288 },
];
const DELTA = { id: "lps-3-cd01ef", name: "DELTA", group: "KGVL", icon: 1, elevationFt: 1300, lat: 34.2751, lon: -83.8279 };
const ECHO = { id: "lps-4-2345ab", name: "ECHO", group: "KGVL", icon: 1, elevationFt: 1310, lat: 34.2761, lon: -83.8269 };

const pt = (name, change, { before = POINTS, ...who } = {}) => ({ name, before, after: changed(before, change), itemName: POINT_SET, actor: ACTOR, ...who });
const ptPair = (name, before, after, who = {}) => ({ name, before, after, itemName: POINT_SET, actor: ACTOR, ...who });

const POINT_CASES = [
  pt("one point added", (p) => { p.push(DELTA); }),
  pt("several points added are counted", (p) => { p.push(DELTA, ECHO); }),
  pt("one point removed", (p) => { p.splice(0, 1); }),
  pt("several points removed are counted", (p) => { p.splice(0, 2); }),
  pt("added and removed at once: the additions are named", (p) => { p.splice(0, 2); p.push(DELTA); }),
  pt("one named point changed is named", (p) => { p[0].elevationFt = 1277; }),
  pt("one point with no name changed", (p) => { p[2].elevationFt = 1291; }),
  pt("a point renamed is named by its new name", (p) => { p[0].name = "ALPHA 2"; }),
  pt("a point whose name is emptied is \"a point\"", (p) => { p[0].name = ""; }),
  pt("several points changed are counted", (p) => { p[0].elevationFt = 1277; p[2].icon = 1; }),
  pt("removed and changed at once: the removal is named", (p) => { p.splice(2, 1); p[0].elevationFt = 1277; }),
  pt("two points sharing an id count once, as the last of them", (p) => { p[3].elevationFt = 1277; }, {
    before: [...POINTS, { ...POINTS[0], name: "ALPHA 2" }],
  }),
  ptPair("a point whose id is an object is never matched, so even unchanged it reads as one added",
    [{ id: { n: 1 }, name: "A", lat: 34.5, lon: -84.1 }], [{ id: { n: 1 }, name: "A", lat: 34.5, lon: -84.1 }]),
  pt("no change at all is an edit", () => {}),
  pt("points in a new order are only an edit", (p) => { p.reverse(); }),
  pt("a field set to null where there was none is no change", (p) => { p[0].note = null; }),
  ptPair("with nothing before, every point is added", null, POINTS),
  ptPair("with nothing after, every point is removed", POINTS, null),
  ptPair("the number 1 and the text \"1\" are different points", [{ id: 1, name: "A", lat: 34.5, lon: -84.1 }], [{ id: "1", name: "A", lat: 34.5, lon: -84.1 }]),
  pt("a set with no name is the points", (p) => { p.push(DELTA); }, { itemName: undefined }),
  pt("an empty name is the points too", (p) => { p.push(DELTA); }, { itemName: "" }),
  pt("no one named is Someone", (p) => { p.push(DELTA); }, { actor: undefined }),
  pt("an empty name for the person is Someone too", (p) => { p.push(DELTA); }, { actor: "" }),
  pt("a sentence over 300 is cut to 300", () => {}, { itemName: "X".repeat(296) }),
  // A web bug (webBug): the server cannot store the lone surrogate this leaves, so it answers the batch with 500.
  pt("an emoji across the cut leaves its first half", () => {}, { itemName: `${"X".repeat(285)}${HELI}X`, webBug: true }),
];

const NEW_ID = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
// What a new item's data is does not change its name or sentence (but a point set's count of points).
const NEW_LZ = { schemaVersion: 2, status: "targeted", target: TARGET };
const TWO_POINTS = [
  { id: "lps-0-a1b2c3", name: "ALPHA", elevationFt: 1276, lat: 34.2726, lon: -83.8302 },
  { id: "lps-1-d4e5f6", name: "BRAVO", elevationFt: 1280, lat: 34.2731, lon: -83.8297 },
];

// The other sentences a client sends, each from the web function that sends it.
const SUMMARY_CASES = [
  { name: "a rename names the old name and the new", helper: "renameSummary", actor: ACTOR, from: "LZ HAWK", to: "LZ EAGLE" },
  { name: "a rename by no one named is Someone's", helper: "renameSummary", from: "LZ HAWK", to: "LZ EAGLE" },
  { name: "a rename by an empty name is Someone's too", helper: "renameSummary", actor: "", from: "RED ROUTES", to: "BLUE ROUTES" },
  { name: "quotes in a name are written as they are", helper: "renameSummary", actor: ACTOR, from: "LZ \"HAWK\"", to: "LZ 'EAGLE'" },
  { name: "a rename is never cut, however long", helper: "renameSummary", actor: ACTOR, from: HELI.repeat(100), to: "X".repeat(100) },
  { name: "a removal names the item", helper: "deleteItemOp", uuid: `lz-${NEW_ID(1)}`, itemName: "LZ HAWK", actor: ACTOR },
  { name: "a removal by no one named is Someone's", helper: "deleteItemOp", uuid: `rt-${NEW_ID(2)}`, itemName: "MISSION 1" },
  { name: "a removal by an empty name is Someone's too", helper: "deleteItemOp", uuid: `lz-${NEW_ID(11)}`, itemName: "LZ HAWK", actor: "" },
  { name: "a removal is never cut, however long", helper: "deleteItemOp", uuid: `ps-${NEW_ID(3)}`, itemName: HELI.repeat(100), actor: "X".repeat(150) },
  { name: "an LZ/PZ copied in is an lz- item", helper: "copyFromLibrary", kind: "lz", record: { id: 12, name: "LZ HAWK" }, actor: ACTOR, newId: NEW_ID(4) },
  { name: "a route set copied in is an rt- item", helper: "copyFromLibrary", kind: "route", record: { id: 4, name: "RED ROUTES" }, actor: ACTOR, newId: NEW_ID(5) },
  { name: "a point set copied in is a ps- item", helper: "copyFromLibrary", kind: "pointset", record: { id: 9, name: "KGVL TAXI" }, actor: ACTOR, newId: NEW_ID(6) },
  { name: "an LZ/PZ with no name is called LZ/PZ", helper: "copyFromLibrary", kind: "lz", record: { id: 13 }, actor: ACTOR, newId: NEW_ID(7) },
  { name: "a route set with an empty name is called ROUTE SET", helper: "copyFromLibrary", kind: "route", record: { id: 5, name: "" }, actor: ACTOR, newId: NEW_ID(8) },
  { name: "a point set whose name is null is called POINT SET", helper: "copyFromLibrary", kind: "pointset", record: { id: 10, name: null }, actor: ACTOR, newId: NEW_ID(9) },
  { name: "a copy by no one named is Someone's", helper: "copyFromLibrary", kind: "lz", record: { id: 14, name: "LZ CROW" }, newId: NEW_ID(10) },
  { name: "a copy by an empty name is Someone's too", helper: "copyFromLibrary", kind: "pointset", record: { id: 11, name: "KGVL TAXI" }, actor: "", newId: NEW_ID(12) },
  { name: "the id the client chose is used as it is", helper: "copyFromLibrary", kind: "route", record: { id: 6, name: "GREEN" }, actor: ACTOR, newId: "x" },

  // Who a person is in every sentence (usePackWorkspace: actorName(user)).
  { name: "a person is named by their name", helper: "actorName", user: { name: "Sam B.", email: "sam@unit.example" } },
  { name: "a person whose name is empty is named by their sign-in address", helper: "actorName", user: { name: "", email: "sam@unit.example" } },
  { name: "a person with no name is named by their sign-in address", helper: "actorName", user: { email: "sam@unit.example" } },
  { name: "a person with neither is Someone", helper: "actorName", user: { name: null, email: "" } },
  { name: "nobody signed in (null) is Someone", helper: "actorName", user: null },
  { name: "no user at all is Someone", helper: "actorName" },

  // An item made in the pack (usePackLz, usePackRoutes and usePackPoints createItem): its name and its sentence.
  { name: "an LZ/PZ made in the pack keeps the name it was given", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(13)}`, itemName: "LZ CROW", count: 2, data: NEW_LZ, actor: ACTOR },
  { name: "a blank name is LZ/PZ n, n one more than the LZ/PZs the pack has", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(14)}`, itemName: "   ", count: 2, data: NEW_LZ, actor: ACTOR },
  { name: "no name at all in a pack with no LZ/PZs is LZ/PZ 1", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(15)}`, count: 0, data: NEW_LZ, actor: ACTOR },
  { name: "a null name is the next LZ/PZ n too", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(16)}`, itemName: null, count: 9, data: NEW_LZ },
  { name: "a name is trimmed, not upper-cased", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(17)}`, itemName: "  lz crow\t\n", count: 0, data: NEW_LZ, actor: ACTOR },
  { name: "a name padded with no-break spaces, a byte order mark and an ideographic space is trimmed as JavaScript trims it", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(18)}`, itemName: "\u00A0\uFEFFLZ CROW\u3000\u2028", count: 0, data: NEW_LZ, actor: ACTOR },
  { name: "a name of only no-break spaces and a byte order mark is blank", helper: "newItemOp", kind: "route", item: `rt-${NEW_ID(19)}`, itemName: "\u00A0\uFEFF\u00A0", count: 3, data: { version: 1, routes: [] }, actor: ACTOR },
  { name: "a zero-width space is not trimmed: JavaScript does not count it as a space", helper: "newItemOp", kind: "lz", item: `lz-${NEW_ID(20)}`, itemName: "\u200BLZ CROW", count: 0, data: NEW_LZ, actor: ACTOR },
  { name: "a route set made in the pack keeps the name it was given", helper: "newItemOp", kind: "route", item: `rt-${NEW_ID(21)}`, itemName: "RED ROUTES", count: 0, data: { version: 1, routes: [] }, actor: ACTOR },
  { name: "a blank route set name is ROUTES n, n one more than the route sets the pack has", helper: "newItemOp", kind: "route", item: `rt-${NEW_ID(22)}`, itemName: "", count: 1, data: { version: 1, routes: [] }, actor: "" },
  { name: "a point set made in the pack says how many points it has", helper: "newPointSetOp", itemName: "KGVL TAXI", points: TWO_POINTS, actor: ACTOR, newId: NEW_ID(23) },
  { name: "one point is one point", helper: "newPointSetOp", itemName: "KGVL TAXI", points: TWO_POINTS.slice(0, 1), actor: ACTOR, newId: NEW_ID(24) },
  { name: "a blank point set name is LOCAL POINTS, however many sets the pack has", helper: "newPointSetOp", itemName: " ", points: TWO_POINTS, actor: ACTOR, newId: NEW_ID(25) },
  { name: "a point set's points are given ids as pointsForPack gives them", helper: "newPointSetOp", itemName: "MIXED", points: [{ name: "A" }, { id: 5 }, { id: 5 }, null], newId: NEW_ID(26) },
  { name: "no points are refused as empty_point_set, before an id is taken", helper: "newPointSetOp", itemName: "EMPTY", points: [], actor: ACTOR, newId: NEW_ID(27) },
  { name: "points that are null are refused as empty_point_set too", helper: "newPointSetOp", itemName: "EMPTY", points: null, actor: ACTOR, newId: NEW_ID(28) },
  { name: "a point set's count is written with a comma between thousands", helper: "createSummary", kind: "pointset", itemName: "KGVL TAXI", pointCount: 1234, actor: ACTOR },
  { name: "and between every three digits", helper: "createSummary", kind: "pointset", itemName: "KGVL TAXI", pointCount: 1234567, actor: ACTOR },
  { name: "999 points have no comma", helper: "createSummary", kind: "pointset", itemName: "KGVL TAXI", pointCount: 999, actor: ACTOR },
  { name: "1,000 points have one", helper: "createSummary", kind: "pointset", itemName: "KGVL TAXI", pointCount: 1000, actor: ACTOR },
  { name: "an LZ/PZ made by no one named is Someone's", helper: "createSummary", kind: "lz", itemName: "LZ CROW" },
  { name: "a route set made by an empty name is Someone's", helper: "createSummary", kind: "route", itemName: "RED ROUTES", actor: "" },

  // The item panel's ⋯ menu (usePackWorkspace): Rename is renameSummary above; Update from original is this.
  { name: "an update from the original names the item", helper: "updateFromOriginalSummary", actor: ACTOR, itemName: "LZ HAWK" },
  { name: "an update from the original by no one named is Someone's", helper: "updateFromOriginalSummary", itemName: "LZ HAWK" },
];

const summarise = (c) => {
  switch (c.helper) {
    case "renameSummary": return { ...c, summary: renameSummary(c.actor, c.from, c.to) };
    case "deleteItemOp": return { ...c, op: deleteItemOp({ uuid: c.uuid, name: c.itemName, actor: c.actor }) };
    case "copyFromLibrary": {
      const { url, body } = copyFromLibrary("p-1", { kind: c.kind, record: c.record, actor: c.actor, newId: () => c.newId });
      expect(url).toBe("/packs/p-1/items");
      return { ...c, sent: body };
    }
    case "actorName": return { ...c, named: actorName(c.user) };
    case "newItemOp":
      return { ...c, op: newItemOp({ kind: c.kind, item: c.item, name: c.itemName, count: c.count, data: c.data, actor: c.actor }) };
    case "newPointSetOp": {
      let taken = 0;
      const newId = () => {
        taken += 1;
        return c.newId;
      };
      return { ...c, made: newPointSetOp({ name: c.itemName, points: c.points, actor: c.actor, newId }), idsTaken: taken };
    }
    case "createSummary": return { ...c, summary: createSummary(c.actor, c.kind, c.itemName, c.pointCount) };
    case "updateFromOriginalSummary": return { ...c, summary: updateFromOriginalSummary(c.actor, c.itemName) };
    default: throw new Error(`unknown helper ${c.helper}`);
  }
};

const sentence = (say) => ({ name, before, after, itemName, actor, webBug }) => ({
  name, before, after, itemName, actor, sentence: say(before, after, { name: itemName, actor }), ...(webBug ? { webBug: true } : {}),
});

const describeFixture = () => withoutClockOrRandomness(() => ({
  description: "The one-sentence summaries a client attaches to its edits for a mission pack's history (docs/MISSION_PACKS.md §5a), "
    + "and the operation a new item is made with, as frontend/src/feature/missionPacks/ writes them (the reference). Every case is "
    + "self-contained, and sentences are compared exactly. A key missing from a case is JavaScript's undefined (not given), which "
    + "JSON cannot hold; for `itemName` and `actor`, missing, null and empty text all give the default. Values are compared \"as "
    + "JSON\" by packDiff.sameData: deep equality in which a missing field and a null one are the same. Each sentence is (actor, or "
    + "\"Someone\") + \" \" + a phrase + \".\", cut to 300 UTF-16 code units by JavaScript's slice. "
    + "An emoji across the cut leaves a lone surrogate (written \\ud83d in this file). Those cases are marked `webBug: true`, and "
    + "a port must not copy them: the server cannot store a lone surrogate (encoding it to UTF-8 fails, so POST .../ops answers "
    + "500, and since a batch that met a 5xx is sent again unchanged, every later edit to the pack waits behind it), and a port's "
    + "UTF-8 encoder would send '?' in its place. The web is to be fixed (cut so that no character is split) and this file "
    + "regenerated (AGENTS.md §15); until then a port's test skips the marked cases. "
    + "Where a change has several phrases, the FIRST in the order below is the sentence: the order is part of the contract. "
    + "Matching by id, everywhere here, is a JavaScript Map's: ids compare with SameValueZero (the number 1 is not the text \"1\"), "
    + "and an id that is an object or a list compares by reference, which two versions never share, so such an element always "
    + "reads as one removed and one added, even unchanged. When two elements of one list share an id, the id keeps the place of "
    + "the first of them in the order things are named, but stands for the last of them (its content and, for an LZ graphic, its "
    + "place n), so a change to any but the last is not seen. "
    + "`lz`: packLz.describeLzChange(before, after, {name: itemName, actor}); before and after are the item's shared data; X is "
    + "itemName or \"the LZ/PZ\". (1) \"analyzed X\" when after.status is \"analyzed\" and before's is not. (2) If target differs: "
    + "\"moved the target of X\" when after.target is truthy, else \"cleared the target of X\". (3) \"drew the boundary of X\" if "
    + "analysis.customLZ differs, else \"changed the boundary of X\" if analysis.detectedLZ differs. (4) The graphics, one collection "
    + "at a time in this order: helicopters (\"Chalk n\"), pzMarkers (\"a PZ marker\"), sectorsOfFire (\"sector NAME\", or \"sector n\" "
    + "when name is missing or empty), goArounds (\"a go-around\"), units (\"unit D\" from a non-empty uniqueDesignation, else \"a unit\"), "
    + "doghouses (\"doghouse L\" from a non-empty `label`, else \"a doghouse\"; id_val is not read), measurements (\"a measurement\"). The "
    + "noun is taken from the element after the change, or before it for one removed. Only elements that are truthy and whose `id` is "
    + "not undefined are followed (an id of null is followed; a missing one is not), matched by id as above; n is the element's "
    + "place among those followed, from 1, in the list after the change, or before it for one removed. For each followed element "
    + "after the change, in list order: not there before -> \"added N to X\"; else the "
    + "keys that differ: any of lat, lon, lng, position, points, center, start, end, tip, anchor -> \"moved N on X\"; else any of "
    + "heading, rotation, direction, bearing -> \"turned N to D° on X\", where D is String(Math.round(Number(v))) for the first of "
    + "heading, rotation, direction, bearing (in that order, whether it changed or not) whose v is not null, not \"\" and has a "
    + "finite Number(v), or \"turned N on X\" when none has; else \"changed N on X\". Number(v) is JavaScript's: text is trimmed as "
    + "String.prototype.trim trims (a no-break space and a byte order mark too, a zero-width space not), \" \" is 0, \"+90\" is 90, "
    + "\"0x5A\", \"0b1011010\" and \"0o132\" are 90, \"1e2\" is 100, but \"-0x5A\", \"1_000\", \"Infinity\" and \"270°\" are no number; "
    + "true is 1, false is 0, [] is 0 and [90] is 90. Math.round rounds halves up, -0 is written 0, and String writes a number as "
    + "JavaScript does (1e21 is \"1e+21\"). Then each followed element only before -> \"removed N from X\". (5) If any flight data "
    + "key differs: \"changed the landing heading to V on X\" when landing_hdg differs and is truthy after (V as JavaScript writes it), "
    + "else the same for takeoff_hdg (\"the takeoff heading\"), else \"changed the flight data on X\". (6) \"set the LZ card area on X\" "
    + "if graphics.exportBox differs. None -> \"edited X\". "
    + "`routes`: packRoutes.describeRouteChange(before, after, {name: itemName, actor}); X is itemName or \"the routes\". Routes, and "
    + "each route's points, are followed and matched by id as graphics are. For each route after the change, in list order, "
    + "with L its name or \"a route\": not there before -> \"added the route L to X\"; else (a) name differs by JavaScript's !== "
    + "(a missing name and a null one differ) -> \"renamed the route OLD to L\" when the old name is truthy, else \"named a route L\"; "
    + "(b) each point after, in list order, with P its name or, when that is missing, null or empty, \"a point\": new -> "
    + "\"added P to L\"; lat or lon differs by !== (so a missing lat and a null one differ, and so do 34.7 and \"34.7\") -> \"moved P "
    + "on L\"; otherwise differs as JSON -> \"changed P on L\"; (c) each point only before -> \"removed P from L\"; (d) plan differs -> "
    + "\"changed the plan of L\"; (e) elevations differ -> \"updated the ground elevations of L\". Then each route only before -> "
    + "\"removed the route NAME from X\", or \"removed a route from X\" when it had no name. None -> \"edited X\" (a route's colour, "
    + "or a new order of routes or points, is not named). "
    + "`points`: usePackPoints.describePointsChange(before, after, {name: itemName, actor}); before and after are lists of points "
    + "(null or missing: none), matched by id as above (a pack's points always have distinct ids: pointsForPack); X is "
    + "itemName or \"the points\". The first that applies: k ids only after -> \"added k point(s) to X\"; k ids only before -> "
    + "\"removed k point(s) from X\"; one point differs -> \"changed NAME in X\" (its name after the change, or \"a point\"); k > 1 "
    + "differ -> \"changed k points in X\"; else \"edited X\". \"point\" when k is 1, \"points\" otherwise. "
    + "`summaries`: the other fixed sentences and names, by `helper`, the web function (packSentences.js unless said) that makes "
    + "them. None of these is cut. "
    + "renameSummary(actor, from, to) -> `summary`, sent with an item.rename (usePackItemSync, and the item panel's Rename). "
    + "deleteItemOp({uuid, name: itemName, actor}) -> `op`, the operation sent. "
    + "packActions.copyFromLibrary(pack, {kind, record, actor, newId}) -> `sent`, the body packApi posts to "
    + "/api/packs/<uuid>/items: `source` is {kind, id: record.id}; `item` is the kind's prefix (lz-, rt-, ps-) and the id the "
    + "client chose (`newId`: the web's crypto.randomUUID); `summary` names the record by its name, or when that is missing, null "
    + "or empty by its kind (LZ/PZ, ROUTE SET, POINT SET). Of `sent`, only the item's prefix and the summary are the contract: the "
    + "id after the prefix is any the client chooses, and the server also takes a source named {kind, client_uuid} "
    + "(contracts/openapi.yaml), which suits a native app whose records are keyed by uuid. "
    + "actorName(user) -> `named`: who a person is in every sentence (usePackWorkspace): user.name, else user.email, else "
    + "\"Someone\" (missing, null and empty text all pass over). "
    + "newItemOp({kind, item, name: itemName, count, data, actor}) -> `op`: the item.create a new LZ/PZ or route set is made "
    + "with (usePackLz, usePackRoutes). Its name is itemName trimmed as String.prototype.trim trims (spaces, tabs, line ends, "
    + "no-break spaces, the byte order mark, U+2028, U+2029, U+3000 and the other Unicode spaces; not a zero-width space, and "
    + "Java's trim() is not the same), never upper-cased, or when that is blank \"LZ/PZ n\" or \"ROUTES n\", n being count + 1 "
    + "(count: the items of that kind the pack has); its summary is createSummary's. "
    + "usePackPoints.newPointSetOp({name: itemName, points, actor, newId}) -> `made`, and `idsTaken`, how many times newId was "
    + "called: the points made ready as pointsForPack makes them (shared.json); none -> {refused: \"empty_point_set\"}, before an "
    + "id is taken; else {refused: null, op}, op the item.create with item \"ps-\" + newId and the name as newItemOp makes it, a "
    + "blank one being \"LOCAL POINTS\". "
    + "createSummary(actor, kind, itemName, pointCount) -> `summary`: for an LZ/PZ `A added the LZ/PZ \"N\".`, for a route set "
    + "`A added the route set \"N\".`, for a point set `A added the point set \"N\" (k points).`, k written with a comma between "
    + "every three digits (toLocaleString(\"en-US\"): 1,234,567), and \"point\" when k is 1. "
    + "updateFromOriginalSummary(actor, itemName) -> `summary`, sent with POST /api/packs/<uuid>/items/<item>/update-from-original.",
  generatedBy: GENERATED_BY,
  lz: LZ_CASES.map(sentence(describeLzChange)),
  routes: ROUTE_CASES.map(sentence(describeRouteChange)),
  points: POINT_CASES.map(sentence(describePointsChange)),
  summaries: SUMMARY_CASES.map(summarise),
}));

// == edit.json ====================================================================================================

const PACK_UUID = "6f1c2a9e-3b7d-4c55-9a10-2d8e5f7b4c31";
const copyJson = (value) => JSON.parse(JSON.stringify(value ?? []));

// What each editor's hook hands composeEdit (usePackLz, usePackRoutes, usePackPoints): the shared part of an item's
// raw data, today's shape of it, and the sentence.
const KINDS = {
  lz: { shared: sharedLzData, currentShape: (item) => lzItemData(lzDiagramFromItem(PACK_UUID, item)), describe: describeLzChange },
  route: {
    shared: sharedRouteData,
    currentShape: (item) => routeSetData(routesFromItem(item, packLocalId(PACK_UUID, item.uuid))),
    describe: describeRouteChange,
  },
  pointset: { shared: copyJson, currentShape: (item) => copyJson(item.data), describe: describePointsChange },
};

// An LZ/PZ as an old library save copied into a pack may hold it: no mapData, no PZ markers or other graphics lists,
// an analysis without its boundaries. The editor works on today's shape of it.
const OLD_LZ = {
  schemaVersion: 2,
  status: "analyzed",
  target: TARGET,
  flightData: { callSign: "HAWK 6" },
  analysis: { gridElevation: "4050" },
  graphics: { helicopters: [{ id: 1759900000001, lat: 34.7837, lon: -84.0823, heading: 270, profileRef: "uh60l" }] },
};

const item = (kind, name, data, uuid = `${packItemId(kind, "1")}`) => ({ uuid, kind, name, data });
/** An item and the editor's version of it: `base` as last in step (today's shape of the item), `mine` after `change`. */
const editCase = (name, it, { mineName = it.name, change = () => {}, mineDoc, baseName = it.name, ...who } = {}) => {
  const doc = KINDS[it.kind].currentShape(it);
  return { name, item: it, base: { name: baseName, doc }, mine: { name: mineName, doc: mineDoc ?? changed(doc, change) }, actor: ACTOR, ...who };
};

const moveChalk1 = (d) => { d.graphics.helicopters[0].lat = 34.7835; };
/** An item whose data is already in today's shape, as one made or last changed by this version is. */
const today = (kind, name, data) => item(kind, name, KINDS[kind].currentShape(item(kind, name, data)));

// Built when the fixture is (today's shape of an LZ is made by the normaliser, which reads the clock).
const EDIT_CASES = () => {
  const CURRENT_LZ = today("lz", "LZ HAWK", LZ);
  const OLD_ITEM = item("lz", "LZ HAWK", OLD_LZ);
  const ROUTE_ITEM = today("route", "MISSION 1", ROUTES);
  const POINT_ITEM = today("pointset", "KGVL TAXI", POINTS);
  return [
  editCase("nothing changed and the same name: nothing is sent", CURRENT_LZ),
  editCase("a rename alone is one item.rename, with the rename's sentence", CURRENT_LZ, { mineName: "LZ EAGLE" }),
  editCase("a change alone is its operations, each with the change's sentence", CURRENT_LZ, { change: moveChalk1 }),
  editCase("a rename and a change: the rename goes first, and the change's sentence names the new name", CURRENT_LZ, {
    mineName: "LZ EAGLE", change: moveChalk1,
  }),
  editCase("an item in an older shape is brought to today's shape first, then the change, all with the change's sentence", OLD_ITEM, {
    change: moveChalk1,
  }),
  editCase("an item in an older shape that is only renamed is not reshaped", OLD_ITEM, { mineName: "LZ EAGLE" }),
  editCase("an item in an older shape that was only opened sends nothing", OLD_ITEM),
  editCase("a name of only spaces is no rename, and the change's sentence names the item as the pack has it", CURRENT_LZ, {
    mineName: " \t ", change: moveChalk1,
  }),
  editCase("a name padded with a no-break space and a byte order mark is trimmed as JavaScript trims it, so it is no rename", CURRENT_LZ, {
    mineName: "\u00A0LZ HAWK\uFEFF",
  }),
  editCase("a new name is sent trimmed", CURRENT_LZ, { mineName: "  LZ EAGLE\u00A0" }),
  editCase("a zero-width space is not trimmed, so a name with one is a new name", CURRENT_LZ, { mineName: "LZ HAWK\u200B" }),
  editCase("a name the pack changed meanwhile is not renamed back, and the change's sentence names the editor's name", { ...CURRENT_LZ, name: "LZ CROW" }, {
    baseName: "LZ HAWK", mineName: "LZ HAWK", change: moveChalk1,
  }),
  editCase("a route set renamed and a point moved: the rename first", ROUTE_ITEM, {
    mineName: "MISSION 2", change: (d) => { d.routes[0].points[0].lat = 34.701; },
  }),
  editCase("a route set's plan changed", ROUTE_ITEM, { change: (d) => { d.routes[1].plan.tempC = 20; } }),
  editCase("a point set renamed", POINT_ITEM, { mineName: "KGVL TAXI 2" }),
  editCase("a point set with a point changed", POINT_ITEM, { change: (d) => { d[0].elevationFt = 1277; } }),
  editCase("a change by no one named is Someone's", CURRENT_LZ, { change: moveChalk1, actor: undefined }),
  editCase("a change that would replace the content whole is refused: composeEdit throws", CURRENT_LZ, { mineDoc: [] }),
  ];
};

const composed = ({ item: it, base, mine, actor }, functions = KINDS[it.kind]) => {
  try {
    return { sent: composeEdit({ uuid: it.uuid, base, mine, item: it, actor, ...functions }) };
  } catch (error) {
    return { throws: true, error };
  }
};

const editFixture = (clock) => withoutClockOrRandomness(() => ({
  description: "What one change an editor made to a mission-pack item is sent as: frontend/src/feature/missionPacks/packEdit.js "
    + "composeEdit, which usePackItemSync calls once the item has been still (the reference; docs/MISSION_PACKS.md §5a). Each "
    + "case: `item`, the pack's item ({uuid, kind, name, data}); `base`, the version the editor was last in step with ({name, "
    + "doc}); `mine`, the editor's version now ({name, doc}); `actor`; `shared`, the shared part of the item's raw data, and "
    + "`current`, today's shape of it, which the editor's own code works out (usePackLz: sharedLzData, and lzItemData of the "
    + "normalised diagram; usePackRoutes: sharedRouteData, and the sketch's routes of the set; usePackPoints: the list as it is); "
    + "a port takes them as given. Then `sent`: null when there is nothing to send, else {ops, name}, or `throws: true` where the "
    + "content would have to be replaced whole (packs/diff.json). The rules: the editor's name is trimmed as JavaScript's "
    + "String.prototype.trim trims (no-break spaces and the byte order mark too, a zero-width space not); it is a rename when "
    + "that is not blank and is not base.name (not the item's name: a name the pack changed meanwhile is not a rename here). The "
    + "content changed when diffData(base.doc, mine.doc) has operations (packs/diff.json). Nothing changed and no rename -> null. "
    + "Else `ops`, in this order: an item.rename {type, item, name} with the trimmed name, when renamed; then, only when the "
    + "content changed, diffData(shared, current) (none for an item already in today's shape) and then diffData(base.doc, "
    + "mine.doc), each with `item` added after its own fields. Every operation carries the same `summary`, added last: when the "
    + "content changed, the kind's sentence (packs/describe.json: describeLzChange, describeRouteChange, describePointsChange) of "
    + "(base.doc, mine.doc) with the trimmed name, or item.name when that is blank, and actor; else renameSummary(actor, "
    + "base.name, trimmed name). `name` is what the item is called once they are taken: the trimmed name when renamed, else "
    + "base.name. Operations carry no client_op_id: the session gives one to each (packs/session.json). Compare `ops` in order, "
    + "each as a JSON object.",
  generatedBy: GENERATED_BY,
  cases: EDIT_CASES().map((c) => {
    const functions = KINDS[c.item.kind];
    const shared = functions.shared(c.item.data);
    const current = functions.currentShape(c.item);
    const result = composed(c);
    return { ...c, shared, current, ...(result.throws ? { throws: true } : { sent: result.sent }) };
  }),
}), { clock });

// -- Checks on the cases themselves -----------------------------------------------------------------------------

const COLLECTIONS = ["helicopters", "pzMarkers", "sectorsOfFire", "goArounds", "units", "doghouses", "measurements"];
const POSITION_KEYS = ["lat", "lon", "lng", "position", "points", "center", "start", "end", "tip", "anchor"];

// The keys of a graphic that differ between before and after, across every LZ case.
const movedKeys = () => {
  const keys = new Set();
  LZ_CASES.forEach(({ before, after }) => {
    COLLECTIONS.forEach((collection) => {
      const was = new Map((before?.graphics?.[collection] ?? []).filter((e) => e && e.id !== undefined).map((e) => [e.id, e]));
      (after?.graphics?.[collection] ?? []).forEach((element) => {
        const old = element && was.get(element.id);
        if (!old) return;
        new Set([...Object.keys(old), ...Object.keys(element)]).forEach((key) => {
          if (JSON.stringify(old[key]) !== JSON.stringify(element[key])) keys.add(key);
        });
      });
    });
  });
  return keys;
};

const sentences = (cases) => cases.map((c) => c.sentence);
const said = (cases, pattern) => sentences(cases).some((s) => pattern.test(s));
const byName = (cases, name) => cases.find((c) => c.name === name).sentence;

describe("mission pack shared-data and history fixtures", () => {
  it("packs/shared.json", () => settle("packs/shared.json", sharedFixture()));

  it("packs/describe.json", () => settle("packs/describe.json", describeFixture()));

  it("packs/edit.json", () => {
    // The LZ normaliser stamps the time on a diagram it makes, and the item's shape drops it again: built at two
    // instants, the file must be the same.
    const built = editFixture(Date.UTC(2026, 9, 5, 13, 0, 0));
    expect(editFixture(Date.UTC(2031, 0, 1, 0, 0, 0))).toEqual(built);
    settle("packs/edit.json", built);
  });

  it("composes every edit from the case alone, with the shapes it gives", () => {
    const { cases } = editFixture(0);
    cases.forEach((c) => {
      const given = { shared: () => c.shared, currentShape: () => c.current, describe: KINDS[c.item.kind].describe };
      const result = composed(c, given);
      expect([c.name, result.throws ? "throws" : result.sent]).toEqual([c.name, c.throws ? "throws" : c.sent]);
    });
  });

  it("covers a rename, a change, both, a reshape and nothing, for every kind", () => {
    const { cases } = editFixture(0);
    const sent = cases.filter((c) => c.sent);
    const types = (c) => c.sent.ops.map((op) => op.type);
    const renames = (c) => types(c).filter((type) => type === "item.rename").length;
    expect(new Set(sent.map((c) => c.item.kind))).toEqual(new Set(["lz", "route", "pointset"]));
    expect(sent.some((c) => renames(c) === 1 && c.sent.ops.length === 1)).toBe(true);
    expect(sent.some((c) => renames(c) === 0)).toBe(true);
    expect(sent.some((c) => renames(c) === 1 && c.sent.ops.length > 1)).toBe(true);
    sent.forEach((c) => {
      // The rename, when there is one, goes first, and every operation carries the one sentence.
      expect([c.name, types(c).indexOf("item.rename")]).toEqual([c.name, renames(c) ? 0 : -1]);
      expect([c.name, new Set(c.sent.ops.map((op) => op.summary)).size]).toEqual([c.name, 1]);
      c.sent.ops.forEach((op) => expect([c.name, op.item]).toEqual([c.name, c.item.uuid]));
    });
    // An item in an older shape is reshaped before the change is sent, and only then.
    const reshaped = sent.filter((c) => !sameData(c.shared, c.current) && !sameData(c.base.doc, c.mine.doc));
    expect(reshaped.some((c) => c.item.data === OLD_LZ)).toBe(true);
    // Every other item is in today's shape already, so the only operations reshaping anything are the old LZ's.
    cases.filter((c) => c.item.data !== OLD_LZ).forEach((c) => expect([c.name, sameData(c.shared, c.current)]).toEqual([c.name, true]));
    sent.filter((c) => sameData(c.base.doc, c.mine.doc)).forEach((c) => expect([c.name, types(c)]).toEqual([c.name, ["item.rename"]]));
    expect(cases.filter((c) => c.sent === null).length).toBeGreaterThanOrEqual(3);
    expect(cases.filter((c) => c.throws).length).toBe(1);
  });

  it("names every kind of change to an LZ/PZ, for every collection", () => {
    const { lz: cases } = describeFixture();
    [
      /^Sam B\. analyzed LZ HAWK\.$/, / moved the target of /, / cleared the target of /, / drew the boundary of /,
      / changed the boundary of /, / changed the landing heading to /, / changed the takeoff heading to /,
      / changed the flight data on /, / set the LZ card area on /, / edited LZ HAWK\.$/, /^Someone /, / the LZ\/PZ\.$/,
    ].forEach((pattern) => expect(said(cases, pattern)).toBe(true));
    // Each collection, with its noun either way it can be named: added, moved, turned with and without a heading,
    // changed, removed.
    [
      "Chalk \\d+", "a PZ marker", "sector (?:[A-Z]+|\\d+)", "a go-around", "(?:unit [A-C]/2-10|a unit)",
      "(?:doghouse [A-Z]+\\d|a doghouse)", "a measurement",
    ].forEach((noun) => {
      [`added ${noun} to`, `moved ${noun} on`, `turned ${noun} to -?\\d+° on`, `turned ${noun} on`, `changed ${noun} on`, `removed ${noun} from`]
        .forEach((phrase) => expect([phrase, said(cases, new RegExp(` ${phrase} `))]).toEqual([phrase, true]));
    });
    // And both ways of naming a sector, a unit and a doghouse.
    ["sector [A-Z]+", "sector \\d+", "unit [A-C]/2-10", "a unit", "doghouse [A-Z]+\\d", "a doghouse"].forEach((noun) => {
      expect([noun, said(cases, new RegExp(` added ${noun} to `))]).toEqual([noun, true]);
      expect([noun, said(cases, new RegExp(` removed ${noun} from `))]).toEqual([noun, true]);
    });
  });

  it("moves a graphic by every position key", () => {
    const keys = movedKeys();
    POSITION_KEYS.forEach((key) => expect(keys).toContain(key));
  });

  it("rounds a heading as JavaScript does", () => {
    const { lz: cases } = describeFixture();
    expect(byName(cases, "a heading of 44.5 rounds up to 45")).toBe("Sam B. turned Chalk 1 to 45° on LZ HAWK.");
    expect(byName(cases, "a heading of -0.5 rounds to 0, never -0")).toBe("Sam B. turned Chalk 1 to 0° on LZ HAWK.");
    expect(byName(cases, "a heading of -1.5 rounds up to -1")).toBe("Sam B. turned Chalk 1 to -1° on LZ HAWK.");
    expect(byName(cases, "a heading of the text \"090\" reads as 90")).toBe("Sam B. turned Chalk 1 to 90° on LZ HAWK.");
  });

  it("names every kind of change to routes and points", () => {
    const { routes, points } = describeFixture();
    [
      / added the route WHITE 1 to /, / added the route a route to /, / renamed the route RED 1 to RED 2\./, / named a route BLUE 1\./,
      / added \.CP1 to /, / added a point to /, / moved \.TGT on /, / moved a point on /, / changed \.RP on /, / changed a point on /,
      / removed \.RP from /, / removed a point from /, / changed the plan of /, / updated the ground elevations of /,
      / removed the route BLUE 1 from /, / removed a route from /, / edited MISSION 1\.$/, /^Someone /, / the routes\.$/,
    ].forEach((pattern) => expect(said(routes, pattern)).toBe(true));
    [
      / added 1 point to /, / added [2-9] points to /, / removed 1 point from /, / removed [2-9] points from /,
      / changed ALPHA in /, / changed a point in /, / changed [2-9] points in /, / edited KGVL TAXI\.$/, /^Someone /, / the points\.$/,
    ].forEach((pattern) => expect(said(points, pattern)).toBe(true));
  });

  it("cuts every sentence at 300, leaving half an emoji where one is across the cut", () => {
    const fixture = describeFixture();
    [fixture.lz, fixture.routes, fixture.points].forEach((cases) => {
      sentences(cases).forEach((s) => expect(s.length).toBeLessThanOrEqual(300));
      expect(byName(cases, "a sentence over 300 is cut to 300")).toHaveLength(300);
      const halved = byName(cases, "an emoji across the cut leaves its first half");
      expect(halved).toHaveLength(300);
      expect(halved.charCodeAt(299)).toBe(0xd83d);
    });
    const names = sharedFixture().myEditsName;
    expect(names.find((c) => c.name === "an emoji across the cut leaves its first half").output.charCodeAt(99)).toBe(0xd83d);
    names.forEach((c) => expect(c.output.length).toBeLessThanOrEqual(100));
  });

  it("marks as webBug exactly the cases that leave a lone surrogate, so each mark goes once the web is fixed", () => {
    const loneSurrogate = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/;
    const { lz, routes, points, summaries } = describeFixture();
    const { myEditsName: names } = sharedFixture();
    [...lz, ...routes, ...points].forEach((c) => expect([c.name, Boolean(c.webBug)]).toEqual([c.name, loneSurrogate.test(c.sentence)]));
    names.forEach((c) => expect([c.name, Boolean(c.webBug)]).toEqual([c.name, loneSurrogate.test(c.output)]));
    summaries.forEach((c) => expect([c.name, loneSurrogate.test(JSON.stringify(c))]).toEqual([c.name, false]));
    expect([...lz, ...routes, ...points, ...names].filter((c) => c.webBug)).toHaveLength(4);
  });

  it("covers every helper of the other sentences, and they agree with the pure ones", () => {
    const { summaries } = describeFixture();
    [
      "renameSummary", "deleteItemOp", "copyFromLibrary", "actorName", "newItemOp", "newPointSetOp", "createSummary", "updateFromOriginalSummary",
    ].forEach((helper) => expect(summaries.some((c) => c.helper === helper)).toBe(true));
    summaries.filter((c) => c.helper === "newItemOp").forEach((c) => {
      expect(c.op.summary).toBe(createSummary(c.actor, c.kind, c.op.name));
    });
    summaries.filter((c) => c.helper === "newPointSetOp").forEach((c) => {
      expect([c.name, c.idsTaken]).toEqual([c.name, c.made.refused ? 0 : 1]);
    });
    summaries.filter((c) => c.helper === "newPointSetOp" && !c.made.refused).forEach((c) => {
      expect(c.made.op).toEqual(newItemOp({
        kind: "pointset", item: `ps-${c.newId}`, name: c.itemName, count: 0, data: pointsForPack(c.points), actor: c.actor,
      }));
    });
    expect(summaries.filter((c) => c.helper === "newPointSetOp" && c.made.refused).map((c) => c.made.refused))
      .toEqual(["empty_point_set", "empty_point_set"]);
    // The names a JavaScript trim and a Java trim read differently are all here.
    const named = summaries.filter((c) => c.helper === "newItemOp").map((c) => c.itemName ?? "");
    ["\u00A0", "\uFEFF", "\u3000", "\u2028", "\u200B"].forEach((ch) => expect(named.some((n) => n.includes(ch))).toBe(true));
    summaries.filter((c) => c.helper === "deleteItemOp").forEach((c) => {
      expect(c.op).toEqual({ type: "item.delete", item: c.uuid, summary: deleteSummary(c.actor, c.itemName) });
    });
    summaries.filter((c) => c.helper === "copyFromLibrary").forEach((c) => {
      expect(c.sent).toEqual({
        source: { kind: c.kind, id: c.record.id }, item: packItemId(c.kind, c.newId), summary: copySummary(c.actor, c.kind, c.record.name),
      });
    });
    expect(new Set(summaries.filter((c) => c.helper === "copyFromLibrary").map((c) => c.sent.item.split("-")[0]))).toEqual(new Set(["lz", "rt", "ps"]));
  });

  it("changes nothing it was given", () => {
    const inputs = () => JSON.stringify([
      LZ_SHARED, ROUTES_SHARED, ROUTE_SETS, POINT_LISTS, EDIT_NAMES, LIBRARY, LZ_CASES, ROUTE_CASES, POINT_CASES, SUMMARY_CASES,
      OLD_LZ, LZ, ROUTES, POINTS,
    ]);
    const before = inputs();
    sharedFixture();
    describeFixture();
    editFixture(0);
    expect(inputs()).toBe(before);
  });

  it("refuses the clock and randomness while a fixture is built", () => {
    expect(() => withoutClockOrRandomness(() => Date.now())).toThrow(/the clock/);
    expect(() => withoutClockOrRandomness(() => new Date())).toThrow(/the clock/);
    expect(() => withoutClockOrRandomness(() => Math.random())).toThrow(/randomness/);
    expect(() => withoutClockOrRandomness(() => window.crypto.randomUUID())).toThrow(/a random id/);
    expect(new Date(0).toISOString()).toBe("1970-01-01T00:00:00.000Z");
    expect(withoutClockOrRandomness(() => [Date.now(), new Date().getTime()], { clock: 7 })).toEqual([7, 7]);
    expect(() => withoutClockOrRandomness(() => Math.random(), { clock: 7 })).toThrow(/randomness/);
  });
});
