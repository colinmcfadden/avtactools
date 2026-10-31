import { createDefaultDoghouses } from "../feature/doghouses/useDoghouses";
import {
  doghouseDisplay,
  doghouseFieldUpdates,
  doghouseHeadingDegrees,
  doghouseHeadingText,
  doghouseRotation,
  flightDataFromDoghouses,
} from "../feature/doghouses/doghouseFields";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What a doghouse shows, how a typed value is written into it, and how the two standard doghouses give the flight data its landing and
// takeoff headings: the web's own functions, run over the cases that are fragile (a heading with a degree sign, a missing field, a
// very negative heading, the older ids). The native apps are held to every case. Beside them, JavaScript's parseInt and parseFloat over
// the strings they are applied to, because the Kotlin port of those two is a small parser of its own and needs a reference that is not itself.
// Non-finite numbers appear as null (as the words "NaN" and "Infinity" in the parse cases); a key the web leaves undefined is absent, as it is in the saved JSON.

const GENERATED_BY = "frontend/src/contracts/doghouseFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));
// A number as the fixture writes it where NaN and the infinities must be told apart: those three as words.
const word = (n) => (Number.isNaN(n) ? "NaN" : n === Infinity ? "Infinity" : n === -Infinity ? "-Infinity" : n);
const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (typeof v === "number" && !Number.isFinite(v) ? null : v)));

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

const dh = (fields) => ({ id: "x", role: "other", lat: 34.5, lon: -84.1, id_val: "[X]", heading: "000°", time: "01+57", dist: "3.13km", airspeed: "60 KIAS", ...fields });

const PARSE_INPUTS = [
  "270°", "  42", "-90°", "+5", "0x1F", "0X1f", "0x", "12.7", ".5", "5.", "1e3", "1e", "-.5e-2x", "Infinity", "-Infinity", "+Infinity", "infinity", "abc", "",
  " ", "3.13km", "60 kts", "007", "\u0663", "\u00a042", "\ufeff7", "\u20281.5", "9007199254740993", "1_000", "--5", "- 5", "0.0000001", "1e400",
  "000°", "450°", "3.", "..5", "0.5.5", "1e+2", "1E-2", "-0", "0b11", "0o7", "12abc", "\u0663\u0663", "1,5",
];

const heading = (text) => ({ input: text, degrees: doghouseHeadingDegrees(text), stored: doghouseHeadingText(doghouseHeadingDegrees(text)) });

const doghouseFixture = () => ({
  generatedBy: GENERATED_BY,
  jsParse: PARSE_INPUTS.map((input) => ({ input, parseInt: word(parseInt(input)), parseFloat: word(parseFloat(input)) })),
  defaults: [
    { target: [34.5, -84.1], namespace: "lz-1", expected: createDefaultDoghouses([34.5, -84.1], "lz-1") },
    { target: { lat: 34.5, lng: -84.1 }, namespace: "lz-1", expected: createDefaultDoghouses({ lat: 34.5, lng: -84.1 }, "lz-1") },
  ],
  rotation: [
    "270°", "000°", "-90°", "450°", "abc", "", "007", "12.7°", 270, null,
  ].map((value) => ({ heading: value, expected: doghouseRotation({ heading: value }) })),
  display: [
    { name: "a default takeoff doghouse", dh: createDefaultDoghouses([34.5, -84.1], "d")[0] },
    { name: "a default landing doghouse", dh: createDefaultDoghouses([34.5, -84.1], "d")[1] },
    { name: "edited values", dh: dh({ id_val: "[SP2]", heading: "270°", time: "03+20", dist: "12.5km", airspeed: "55 kts" }) },
    { name: "no time, airspeed or distance", dh: { id: "x", id_val: "[X]", heading: "090°" } },
    { name: "empty strings", dh: dh({ time: "", dist: "", airspeed: "", heading: "" }) },
    { name: "a time with no seconds", dh: dh({ time: "12" }) },
    { name: "a time with no minutes", dh: dh({ time: "+30" }) },
    { name: "a time with more parts", dh: dh({ time: "01+02+03" }) },
    { name: "a distance that is not a number", dh: dh({ dist: "far" }) },
    { name: "a distance with a unit and a space", dh: dh({ dist: "3.5 km" }) },
    { name: "an airspeed that is not a number", dh: dh({ airspeed: "fast" }) },
    { name: "an airspeed of zero falls back to 90", dh: dh({ airspeed: "0 kts" }) },
    { name: "a distance of zero", dh: dh({ dist: "0km" }) },
    { name: "a negative heading is not wrapped", dh: dh({ heading: "-90°" }) },
    { name: "a heading past a full turn is not wrapped", dh: dh({ heading: "450°" }) },
    { name: "a heading of one digit", dh: dh({ heading: "5°" }) },
    { name: "a small negative heading pads in front of the sign", dh: dh({ heading: "-5°" }) },
    { name: "a label that is empty", dh: dh({ id_val: "" }) },
  ].map(({ name, dh: d }) => {
    const rotation = doghouseRotation(d);
    return { name, dh: d, rotation, expected: clean(doghouseDisplay(d, rotation)) };
  }),
  displayRotations: [0, 90, 270, 359.4, 359.5, 0.4, -0.4, 449.6, -90, 0.5, 2.5, 90.5, -0.5, -2.5].map((rotation) => ({ rotation, expected: doghouseDisplay(dh({}), rotation).heading })),
  headings: ["270", "0", "000", "5", "-90", "360", "450", "720", "-360", "-400", "-720", "12.7", "abc", "", "  42  ", "0x1F", "90°", "3e2", "1e3", "-0", "+45"].map(heading),
  headingText: [0, 5, 45, 90, 270, 359, 360, 450, -40].map((degrees) => ({ degrees, expected: doghouseHeadingText(degrees) })),
  fields: [
    ["id", "[SP2]", undefined], ["id", "", undefined], ["id", "RP 1", undefined],
    ["dist", "3.5", undefined], ["dist", "", undefined], ["dist", "far", undefined], ["dist", "12.50", undefined],
    ["airspeed", "55", undefined], ["airspeed", "", undefined], ["airspeed", "fast", undefined],
    ["time-m", "03", { minutes: "03", seconds: "20" }], ["time-s", "45", { minutes: "01", seconds: "45" }],
    ["time-m", "", { minutes: "", seconds: "20" }], ["time-s", "7", { minutes: "9", seconds: "7" }],
    ["heading", "270", undefined], ["unknown", "x", undefined],
  ].map(([type, value, time]) => ({ type, value, time: time ?? null, expected: doghouseFieldUpdates(type, value, time) })),
  flightHeadings: [
    { name: "the two default doghouses", doghouses: createDefaultDoghouses([34.5, -84.1], "d"), flightData: {} },
    { name: "the defaults, edited, over existing flight data", doghouses: [dh({ role: "takeoff", heading: "090°" }), dh({ role: "landing", heading: "270°" })], flightData: { lz_name: "HAWK", goAround: "LEFT" } },
    { name: "only a landing doghouse leaves the takeoff heading alone", doghouses: [dh({ role: "landing", heading: "270°" })], flightData: { takeoff_hdg: "045°", landing_hdg: "000°" } },
    { name: "only a takeoff doghouse leaves the landing heading alone", doghouses: [dh({ role: "takeoff", heading: "180°" })], flightData: { takeoff_hdg: "045°", landing_hdg: "010°" } },
    { name: "the older ids dh1 and dh2", doghouses: [dh({ role: undefined, id: "dh1", id_val: "[A]", heading: "100°" }), dh({ role: undefined, id: "dh2", id_val: "[B]", heading: "200°" })], flightData: {} },
    { name: "the older labels [SP1] and [RP1]", doghouses: [dh({ role: "other", id: "p", id_val: "[SP1]", heading: "111°" }), dh({ role: "other", id: "q", id_val: "[RP1]", heading: "222°" })], flightData: {} },
    { name: "the first doghouse that matches wins", doghouses: [dh({ id: "a", role: "landing", heading: "010°" }), dh({ id: "b", role: "landing", heading: "020°" })], flightData: {} },
    { name: "a doghouse that is both: the landing and the takeoff", doghouses: [dh({ role: "landing", id_val: "[SP1]", heading: "123°" })], flightData: {} },
    { name: "no doghouse that matches changes nothing", doghouses: [dh({}), dh({ id: "y", role: "waypoint" })], flightData: { landing_hdg: "000°" } },
    { name: "no doghouses changes nothing", doghouses: [], flightData: { landing_hdg: "000°" } },
    { name: "doghouses that are not a list change nothing", doghouses: null, flightData: { landing_hdg: "000°" } },
    { name: "an object in place of the list changes nothing", doghouses: { role: "landing", heading: "010°" }, flightData: { landing_hdg: "000°" } },
    { name: "a landing doghouse with no heading takes the key away", doghouses: [{ id: "a", role: "landing", lat: 1, lon: 2 }], flightData: { landing_hdg: "090°", takeoff_hdg: "180°" } },
    { name: "a heading that is null is kept as null", doghouses: [dh({ role: "takeoff", heading: null })], flightData: { takeoff_hdg: "180°" } },
    { name: "a heading that is a number is carried as it is", doghouses: [dh({ role: "takeoff", heading: 270 })], flightData: {} },
  ].map(({ name, doghouses, flightData }) => ({ name, doghouses: saved(doghouses), flightData, expected: saved(flightDataFromDoghouses(doghouses, flightData)) })),
});

describe("doghouse fixture", () => {
  it("workspace/doghouses.json", () => settle("workspace/doghouses.json", doghouseFixture()));

  it("exercises the cases the doghouse rules are fragile about", () => {
    const f = doghouseFixture();
    const display = (needle) => f.display.find((c) => c.name.includes(needle)).expected;
    // The four rows read what the doghouse says, with the web's fallbacks.
    expect(display("default takeoff")).toEqual({ id: "[SP1]", heading: "000", minutes: "01", seconds: "57", distance: 3.13, airspeed: 60 });
    expect(display("no time, airspeed or distance")).toMatchObject({ minutes: "00", seconds: "00", distance: 0, airspeed: 90 });
    expect(display("airspeed of zero")).toMatchObject({ airspeed: 90 });
    expect(display("not wrapped").heading).toBe("-90");                                   // padStart(3) leaves a 3-character "-90" as it is
    expect(display("small negative").heading).toBe("0-5");                                // and pads "-5" in front of its sign
    // A heading is wrapped once only: a very negative one stays negative, as JavaScript's % does.
    const stored = Object.fromEntries(f.headings.map((h) => [h.input, h.stored]));
    expect(stored["-90"]).toBe("270°");
    expect(stored["450"]).toBe("090°");
    expect(stored["-400"]).toBe("-40°");
    expect(stored["abc"]).toBe("000°");
    expect(stored["0x1F"]).toBe("031°");                                                  // parseInt reads a hexadecimal prefix
    // The flight data follows the doghouses.
    const flight = (needle) => f.flightHeadings.find((c) => c.name.includes(needle)).expected;
    expect(flight("two default doghouses")).toEqual({ landing_hdg: "000°", takeoff_hdg: "000°" });
    expect(flight("only a landing")).toEqual({ landing_hdg: "270°", takeoff_hdg: "045°" });
    expect(flight("no doghouse that matches")).toEqual({ landing_hdg: "000°" });
    expect(flight("first doghouse that matches")).toMatchObject({ landing_hdg: "010°" });
    expect(flight("no heading takes the key away")).toEqual({ takeoff_hdg: "180°" });
  });
});
