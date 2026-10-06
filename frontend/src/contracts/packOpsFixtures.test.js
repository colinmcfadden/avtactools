import { applyPackOp, validatePackOp } from "../feature/missionPacks/packOps";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What one operation does to a mission pack's items. The web's packOps.js is the reference; the server
// (backend/pack_ops.py) applies every edit with the same rules and is held to every case here, and the native apps
// will be. A case gives the items before, the operation, how it went, and the items after. A skipped or malformed
// operation leaves `expected` equal to `items`.

const GENERATED_BY = "frontend/src/contracts/packOpsFixtures.test.js (UPDATE_CONTRACTS=1)";

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

const LZ = {
  kind: "lz",
  name: "LZ HAWK",
  deleted: false,
  data: {
    schemaVersion: 2,
    id: "d-1",
    name: "LZ HAWK",
    status: "analyzed",
    target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
    mapData: null,
    flightData: { callSign: "HAWK 6", landingHeading: 270 },
    analysis: { gridElevation: "4050" },
    graphics: {
      helicopters: [
        { id: "h-1", lat: 34.5, lon: -84.1, heading: 270 },
        { id: "h-2", lat: 34.51, lon: -84.11, heading: 90, label: "CHALK 2" },
      ],
      // The same pair the other way round, so a search that confused them would be caught whichever comes first.
      pzMarkers: [{ id: 2, tag: "number two" }, { id: "2", tag: "text two" }],
      sectorsOfFire: [{ id: "s-1", points: [[34.5, -84.1], [34.51, -84.09], [34.5, -84.08]] }],
      goArounds: null,
      // What an id search passes over (not an object, no id), ids of two types with the same digits, and two
      // elements sharing an id.
      units: [
        7,
        { tag: "no id" },
        { id: "1", tag: "text one" },
        { id: 1, tag: "number one" },
        { id: "u-2", tag: "first" },
        { id: "u-2", tag: "second" },
      ],
      exportBox: null,
    },
    view: { mapStyle: "satellite" },
  },
};

const ROUTES = {
  kind: "route",
  name: "MISSION 1",
  deleted: false,
  data: {
    version: 1,
    routes: [
      {
        id: "r-1",
        name: "RED 1",
        color: "#e53935",
        points: [
          { id: "p-1", lat: 34.5, lon: -84.1, kind: "amps", name: ".SP" },
          { id: "p-2", lat: 34.55, lon: -84.15 },
          { id: "p-3", lat: 34.6, lon: -84.2, kind: "amps", name: ".RP" },
        ],
        plan: { airspeedKts: 100 },
      },
    ],
  },
};

const POINTS = {
  kind: "pointset",
  name: "LOCAL",
  deleted: false,
  data: [
    { id: "lps-0", name: "ALPHA", lat: 34.5, lon: -84.1 },
    { id: "lps-1", name: "BRAVO", lat: 34.6, lon: -84.2 },
  ],
};

const GONE = { kind: "lz", name: "", data: null, deleted: true };

const lz = { "lz-1": LZ };
const routes = { "rt-1": ROUTES };
const points = { "ps-1": POINTS };
const gone = { "gone-1": GONE };

const HELO = (id) => ["graphics", "helicopters", { id }];
const POINT = (id) => ["routes", { id: "r-1" }, "points", { id }];
const ROUTE_POINTS = ["routes", { id: "r-1" }, "points"];
const set = (item, path, value) => ({ type: "set", item, path, value });
const patch = (item, path, value) => ({ type: "patch", item, path, value });
const upsert = (item, path, value) => ({ type: "upsert", item, path, value });
const insert = (item, path, after, value) => ({ type: "insert", item, path, after, value });
const remove = (item, path) => ({ type: "remove", item, path });

const CASES = [
  // -- Items ---------------------------------------------------------------------------------------------------
  ["an LZ is created", {}, { type: "item.create", item: "lz-2", kind: "lz", name: "LZ CROW", data: { schemaVersion: 2, id: "d-2" } }],
  ["a set of routes is created", {}, { type: "item.create", item: "rt-2", kind: "route", name: "MISSION 2", data: { version: 1, routes: [] } }],
  ["a point set is created from a list of points", {}, { type: "item.create", item: "ps-2", kind: "pointset", name: "MORE", data: [{ id: "lps-0" }] }],
  ["creating an item that is already there is skipped", lz, { type: "item.create", item: "lz-1", kind: "lz", name: "LZ HAWK", data: {} }],
  ["a deleted item's uuid is never used again", gone, { type: "item.create", item: "gone-1", kind: "lz", name: "BACK", data: {} }],
  ["an item is renamed", lz, { type: "item.rename", item: "lz-1", name: "LZ EAGLE" }],
  ["a name of exactly 100 characters is allowed", lz, { type: "item.rename", item: "lz-1", name: "X".repeat(100) }],
  ["a name's length counts characters outside the basic plane once", lz, { type: "item.rename", item: "lz-1", name: "\u{1F681}".repeat(100) }],
  ["a name is kept as sent, spaces and all", lz, { type: "item.rename", item: "lz-1", name: "  LZ EAGLE " }],
  ["renaming a deleted item is skipped", gone, { type: "item.rename", item: "gone-1", name: "X" }],
  ["renaming an item that is not there is skipped", {}, { type: "item.rename", item: "nope", name: "X" }],
  ["an item is deleted, and its name and content go with it", lz, { type: "item.delete", item: "lz-1" }],
  ["deleting a deleted item is skipped", gone, { type: "item.delete", item: "gone-1" }],
  ["deleting an item that is not there is skipped", {}, { type: "item.delete", item: "nope" }],
  ["an item's content is replaced whole", lz, { type: "item.replace", item: "lz-1", data: { schemaVersion: 2, id: "d-1", name: "FROM LIBRARY" } }],
  ["a point set's content is replaced with a list", points, { type: "item.replace", item: "ps-1", data: [{ id: "lps-9" }] }],
  ["replacing an LZ with a list is skipped", lz, { type: "item.replace", item: "lz-1", data: [] }],
  ["replacing a point set with an object is skipped", points, { type: "item.replace", item: "ps-1", data: {} }],
  ["replacing a deleted item is skipped", gone, { type: "item.replace", item: "gone-1", data: {} }],

  // -- Malformed: the server refuses the whole batch ------------------------------------------------------------
  ["an operation that is not an object is malformed", lz, "set"],
  ["an unknown type is malformed", lz, { type: "move", item: "lz-1" }],
  ["a type is matched exactly", lz, { type: "SET", item: "lz-1", path: ["view", "mapStyle"], value: "topo" }],
  ["an operation with no item is malformed", lz, { type: "item.delete" }],
  ["an item id with a space is malformed", lz, { type: "item.delete", item: "lz 1" }],
  ["an item id longer than 64 characters is malformed", lz, { type: "item.delete", item: "a".repeat(65) }],
  ["an item id of 64 characters is fine", {}, { type: "item.delete", item: "a".repeat(64) }],
  ["a threat is not a kind of item", {}, { type: "item.create", item: "t-1", kind: "threat", name: "SA-8", data: {} }],
  ["an LZ whose content is a list is malformed", {}, { type: "item.create", item: "lz-2", kind: "lz", name: "X", data: [] }],
  ["a point set whose content is an object is malformed", {}, { type: "item.create", item: "ps-2", kind: "pointset", name: "X", data: {} }],
  ["an item with no content is malformed", {}, { type: "item.create", item: "lz-2", kind: "lz", name: "X" }],
  ["a blank name is malformed", lz, { type: "item.rename", item: "lz-1", name: " \t\r\n" }],
  ["a name of 101 characters is malformed", lz, { type: "item.rename", item: "lz-1", name: "X".repeat(101) }],
  ["a name that is not text is malformed", lz, { type: "item.rename", item: "lz-1", name: 7 }],
  ["replacing with something that is neither an object nor a list is malformed", lz, { type: "item.replace", item: "lz-1", data: "x" }],
  ["setting with an empty path is malformed", lz, set("lz-1", [], {})],
  ["a path that is not a list is malformed", lz, set("lz-1", "flightData.landingHeading", 90)],
  ["a position in a list is never a path", lz, set("lz-1", ["graphics", "helicopters", 0, "heading"], 45)],
  ["an id segment whose id is null is malformed", lz, set("lz-1", ["graphics", "helicopters", { id: null }, "heading"], 45)],
  ["an id segment whose id is true is malformed", lz, set("lz-1", ["graphics", "helicopters", { id: true }, "heading"], 45)],
  ["an id segment with another key is malformed", lz, set("lz-1", ["graphics", "helicopters", { id: "h-1", at: 0 }, "heading"], 45)],
  ["a key that reaches JavaScript's prototype is malformed", lz, set("lz-1", ["__proto__", "polluted"], true)],
  ["so is a constructor", lz, set("lz-1", ["view", "constructor"], true)],
  ["a set with no value is malformed", lz, { type: "set", item: "lz-1", path: ["view", "mapStyle"] }],
  ["setting an element whole under another id is malformed", lz, set("lz-1", HELO("h-1"), { id: "h-9", lat: 1, lon: 2 })],
  ["setting an element whole with no id is malformed", lz, set("lz-1", HELO("h-1"), { lat: 1, lon: 2 })],
  ["setting an element whole to something that is not an object is malformed", lz, set("lz-1", HELO("h-1"), null)],
  ["a patch whose value is not an object is malformed", lz, patch("lz-1", ["flightData"], 5)],
  ["a patch that would change an element's id is malformed", lz, patch("lz-1", HELO("h-1"), { id: "h-9" })],
  ["an upsert with no id is malformed", lz, upsert("lz-1", ["graphics", "helicopters"], { lat: 1 })],
  ["an upsert whose id is null is malformed", lz, upsert("lz-1", ["graphics", "helicopters"], { id: null })],
  ["an insert that does not say where is malformed", routes, { type: "insert", item: "rt-1", path: ROUTE_POINTS, value: { id: "p-4" } }],
  ["an insert after something that is not an id is malformed", routes, insert("rt-1", ROUTE_POINTS, {}, { id: "p-4" })],
  ["an insert with no id is malformed", routes, insert("rt-1", ROUTE_POINTS, "p-1", { lat: 1 })],
  ["a remove that does not end at an id is malformed", lz, remove("lz-1", ["graphics", "helicopters"])],
  ["a remove with an empty path is malformed", points, remove("ps-1", [])],

  // -- set ------------------------------------------------------------------------------------------------------
  ["a field is set", lz, set("lz-1", ["flightData", "landingHeading"], 90)],
  ["a field is set to null", lz, set("lz-1", ["flightData", "landingHeading"], null)],
  ["a field that is not there yet is added", lz, set("lz-1", ["flightData", "frequency"], "251.0")],
  ["a top-level field is set to an object", lz, set("lz-1", ["view"], { mapStyle: "topo" })],
  ["a null object on the way is made", lz, set("lz-1", ["mapData", "zoom"], 17)],
  ["several missing objects on the way are made", lz, set("lz-1", ["view", "layers", "slope", "opacity"], 0.5)],
  ["an id cannot be found inside an object that is not there", lz, set("lz-1", ["nothing", { id: "x" }, "a"], 1)],
  ["an element cannot be set whole inside an object that is not there", lz, set("lz-1", ["nothing", { id: "x" }], { id: "x" })],
  ["a key cannot be set inside a number", lz, set("lz-1", ["flightData", "landingHeading", "deg"], 1)],
  ["a key cannot be set inside a list", lz, set("lz-1", ["graphics", "helicopters", "length"], 0)],
  ["a field of an element is set by its id", lz, set("lz-1", [...HELO("h-2"), "heading"], 45)],
  ["a field of an element that is not there is skipped", lz, set("lz-1", [...HELO("h-9"), "heading"], 45)],
  ["an element is set whole by its id", lz, set("lz-1", HELO("h-1"), { id: "h-1", lat: 34.52, lon: -84.12 })],
  ["an element that is not there cannot be set whole", lz, set("lz-1", HELO("h-9"), { id: "h-9" })],
  ["an id cannot be found in an object", lz, set("lz-1", ["flightData", { id: "x" }, "a"], 1)],
  ["the number 1 does not find the text \"1\"", lz, set("lz-1", ["graphics", "units", { id: 1 }, "hit"], true)],
  ["the text \"1\" does not find the number 1", lz, set("lz-1", ["graphics", "units", { id: "1" }, "hit"], true)],
  ["the text \"2\" does not find the number 2", lz, set("lz-1", ["graphics", "pzMarkers", { id: "2" }, "hit"], true)],
  ["the number 2 does not find the text \"2\"", lz, set("lz-1", ["graphics", "pzMarkers", { id: 2 }, "hit"], true)],
  ["of two elements with one id, the first is found", lz, set("lz-1", ["graphics", "units", { id: "u-2" }, "hit"], true)],
  ["a route point is moved", routes, set("rt-1", [...POINT("p-2"), "lat"], 34.56)],
  ["a route's plan value is set", routes, set("rt-1", ["routes", { id: "r-1" }, "plan", "airspeedKts"], 120)],
  ["a point set's content is a list, so a key on it is skipped", points, set("ps-1", ["name"], "X")],
  ["a field of a point in a point set is set", points, set("ps-1", [{ id: "lps-1" }, "name"], "CHARLIE")],
  ["a field of a deleted item is skipped", gone, set("gone-1", ["view", "mapStyle"], "topo")],
  ["a field of an item that is not there is skipped", {}, set("nope", ["view", "mapStyle"], "topo")],

  // -- patch ----------------------------------------------------------------------------------------------------
  ["an element is patched, keeping the fields it does not name", lz, patch("lz-1", HELO("h-2"), { heading: 180, lat: 34.52 })],
  ["a patch may repeat the element's own id", lz, patch("lz-1", HELO("h-2"), { id: "h-2", heading: 10 })],
  ["the document itself is patched", lz, patch("lz-1", [], { name: "LZ EAGLE", status: "targeted" })],
  ["a nested object is patched", lz, patch("lz-1", ["flightData"], { callSign: "HAWK 7" })],
  ["an empty patch changes nothing but is applied", lz, patch("lz-1", ["flightData"], {})],
  ["a key named __proto__ in a patch is kept as data", lz, patch("lz-1", ["view"], JSON.parse('{"__proto__": {"x": 1}}'))],
  ["patching an element that is gone is skipped", lz, patch("lz-1", HELO("h-9"), { heading: 1 })],
  ["patching a key that is null is skipped", lz, patch("lz-1", ["mapData"], { zoom: 17 })],
  ["patching a key that is not there is skipped", lz, patch("lz-1", ["weather"], { wind: 1 })],
  ["patching a number is skipped", lz, patch("lz-1", ["flightData", "landingHeading"], { a: 1 })],
  ["patching a list is skipped", lz, patch("lz-1", ["graphics", "helicopters"], { a: 1 })],
  ["a point set's root is a list, so patching it is skipped", points, patch("ps-1", [], { a: 1 })],
  ["a point in a point set is patched", points, patch("ps-1", [{ id: "lps-1" }], { name: "CHARLIE" })],
  ["patching a deleted item is skipped", gone, patch("gone-1", [], { a: 1 })],

  // -- upsert ---------------------------------------------------------------------------------------------------
  ["a new element is added at the end", lz, upsert("lz-1", ["graphics", "helicopters"], { id: "h-3", lat: 34.53, lon: -84.13, heading: 0 })],
  ["an element with a known id is merged, keeping the fields it does not name", lz, upsert("lz-1", ["graphics", "helicopters"], { id: "h-2", heading: 0 })],
  ["a collection that is not there is made", lz, upsert("lz-1", ["graphics", "measurements"], { id: "m-1", a: [34.5, -84.1], b: [34.6, -84.2] })],
  ["a collection that is null is made", lz, upsert("lz-1", ["graphics", "goArounds"], { id: "g-1", side: "L" })],
  ["upserting into an object is skipped", lz, upsert("lz-1", ["flightData"], { id: "x" })],
  ["upserting into a number is skipped", lz, upsert("lz-1", ["flightData", "landingHeading"], { id: "x" })],
  ["upserting where the parent is gone is skipped", lz, upsert("lz-1", ["nothing", "here"], { id: "x" })],
  ["upserting into an element is skipped", lz, upsert("lz-1", HELO("h-1"), { id: "x" })],
  ["upserting into an element that is gone is skipped", lz, upsert("lz-1", HELO("h-9"), { id: "x" })],
  ["upserting into an LZ's root is skipped", lz, upsert("lz-1", [], { id: "x" })],
  ["a point is added to a point set", points, upsert("ps-1", [], { id: "lps-2", name: "CHARLIE", lat: 34.7, lon: -84.3 })],
  ["a point set's point is merged by id", points, upsert("ps-1", [], { id: "lps-0", elevationFt: 1730 })],
  ["an upsert by the number 1 merges into the number, not the text", lz, upsert("lz-1", ["graphics", "units"], { id: 1, hit: true })],
  ["an upsert by the text \"2\" merges into the text, not the number", lz, upsert("lz-1", ["graphics", "pzMarkers"], { id: "2", hit: true })],
  ["an upsert into a deleted item is skipped", gone, upsert("gone-1", ["graphics", "helicopters"], { id: "h-1" })],

  // -- insert ---------------------------------------------------------------------------------------------------
  ["a route point is inserted after another", routes, insert("rt-1", ROUTE_POINTS, "p-1", { id: "p-4", lat: 34.52, lon: -84.12 })],
  ["a point inserted after the last goes last", routes, insert("rt-1", ROUTE_POINTS, "p-3", { id: "p-4", lat: 34.65, lon: -84.25 })],
  ["a point inserted after null goes first", routes, insert("rt-1", ROUTE_POINTS, null, { id: "p-0", lat: 34.45, lon: -84.05 })],
  ["a point inserted after one that is gone goes last", routes, insert("rt-1", ROUTE_POINTS, "p-9", { id: "p-4", lat: 34.65, lon: -84.25 })],
  ["inserting an id that is already there is skipped", routes, insert("rt-1", ROUTE_POINTS, "p-1", { id: "p-2", lat: 0, lon: 0 })],
  ["inserting into a route that is gone is skipped", routes, insert("rt-1", ["routes", { id: "r-9" }, "points"], "p-1", { id: "p-4" })],
  ["a list that is not there is made", routes, insert("rt-1", ["routes", { id: "r-1" }, "legs"], "x", { id: "leg-1" })],
  ["a route is inserted into the set", routes, insert("rt-1", ["routes"], "r-1", { id: "r-2", name: "BLUE 1", points: [] })],
  ["inserting into a number is skipped", routes, insert("rt-1", ["version"], null, { id: "x" })],
  ["a point is inserted into a point set", points, insert("ps-1", [], "lps-0", { id: "lps-2", name: "CHARLIE" })],
  ["inserting into a deleted item is skipped", gone, insert("gone-1", ["graphics", "units"], null, { id: "x" })],

  // -- remove ---------------------------------------------------------------------------------------------------
  ["an element is removed", lz, remove("lz-1", HELO("h-1"))],
  ["removing an element that is gone is skipped", lz, remove("lz-1", HELO("h-9"))],
  ["removing from an object is skipped", lz, remove("lz-1", ["flightData", { id: "x" }])],
  ["removing from a collection that is null is skipped", lz, remove("lz-1", ["graphics", "goArounds", { id: "g-1" }])],
  ["removing from a collection that is not there is skipped", lz, remove("lz-1", ["graphics", "measurements", { id: "m-1" }])],
  ["removing by the number 1 leaves the text \"1\"", lz, remove("lz-1", ["graphics", "units", { id: 1 }])],
  ["removing by the text \"2\" leaves the number 2", lz, remove("lz-1", ["graphics", "pzMarkers", { id: "2" }])],
  ["of two elements with one id, only the first is removed", lz, remove("lz-1", ["graphics", "units", { id: "u-2" }])],
  ["a route point is removed", routes, remove("rt-1", POINT("p-2"))],
  ["a whole route is removed", routes, remove("rt-1", ["routes", { id: "r-1" }])],
  ["a point is removed from a point set", points, remove("ps-1", [{ id: "lps-0" }])],
  ["removing from an LZ's root is skipped", lz, remove("lz-1", [{ id: "x" }])],
  ["removing from a deleted item is skipped", gone, remove("gone-1", HELO("h-1"))],
];

const opsFixture = () => ({
  description: "What one mission-pack operation does to a pack's items, as frontend/src/feature/missionPacks/packOps.js does it "
    + "(the reference). `items` maps an item's uuid to {kind, name, data, deleted}; `op` is what a client sends (or, for "
    + "item.replace, what the server writes); `status` is applied, skipped or invalid, with `reason` for the last two; "
    + "`expected` is the items afterwards, equal to `items` unless the operation was applied. See docs/MISSION_PACKS.md.",
  generatedBy: GENERATED_BY,
  cases: CASES.map(([name, items, op]) => {
    const result = applyPackOp(items, op);
    return { name, items, op, status: result.status, reason: result.reason, expected: result.items };
  }),
});

describe("mission pack operations fixture", () => {
  it("packs/ops.json", () => settle("packs/ops.json", opsFixture()));

  it("covers every outcome and every reason", () => {
    const { cases } = opsFixture();
    const count = (status) => cases.filter((c) => c.status === status).length;
    expect(count("applied")).toBeGreaterThanOrEqual(40);
    expect(count("skipped")).toBeGreaterThanOrEqual(30);
    expect(count("invalid")).toBeGreaterThanOrEqual(25);
    const reasons = new Set(cases.map((c) => c.reason).filter(Boolean));
    [
      "item_exists", "item_missing", "target_missing", "not_an_object", "not_an_array", "element_exists",
      "bad_op", "unknown_type", "bad_item", "bad_kind", "bad_name", "bad_data", "bad_path", "bad_value", "bad_after",
    ].forEach((reason) => expect(reasons).toContain(reason));
  });

  it("changes nothing it was given", () => {
    const before = JSON.stringify(CASES);
    opsFixture();
    expect(JSON.stringify(CASES)).toBe(before);
  });

  it("leaves the items alone unless an operation is applied", () => {
    opsFixture().cases
      .filter((c) => c.status !== "applied")
      .forEach((c) => expect(c.expected).toBe(c.items));
  });

  it("validates exactly what it refuses as malformed", () => {
    opsFixture().cases.forEach((c) => {
      expect(validatePackOp(c.op)).toBe(c.status === "invalid" ? c.reason : null);
    });
  });

  it("never lets a key named __proto__ reach a prototype", () => {
    const { cases } = opsFixture();
    const kept = cases.find((c) => c.name === "a key named __proto__ in a patch is kept as data").expected["lz-1"].data.view;
    expect(Object.getPrototypeOf(kept)).toBe(Object.prototype);
    expect(Object.prototype.hasOwnProperty.call(kept, "__proto__")).toBe(true);
    expect({}.x).toBeUndefined();
  });
});
