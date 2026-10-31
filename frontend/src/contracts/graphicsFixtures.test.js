import {
  calculateAngle,
  calculateHandlePos,
  getDistanceFeet,
  getRotorEdgeCoords,
} from "../utils/Helpers";
import { FALLBACK_PROFILE, profileForAsset } from "../feature/aircraft/aircraftProfiles";
import { placeHelicopter, separationAlerts } from "../feature/helicopters/useHelicopters";
import { createPzMarker } from "../feature/pzMarker/usePzMarker";
import { createSectorOfFire } from "../feature/sectorsOfFire/useSectorsOfFire";
import { createGoAround } from "../feature/goAround/useGoAround";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// The planning graphics' geometry and placement, as the web does them: the distances and rotor-edge lines the separation alerts are built
// on, where a new aircraft is put so it clears the ones already down, which pairs raise an alert and what it says, and where a PZ
// marker, a sector of fire and a go-around start. The native apps are held to every case. Non-finite numbers appear as null.

const GENERATED_BY = "frontend/src/contracts/graphicsFixtures.test.js (UPDATE_CONTRACTS=1)";
const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (v === undefined || (typeof v === "number" && !Number.isFinite(v)) ? null : v)));

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

// The aircraft the cases place. Each case names its aircraft by key; the profiles themselves are in the fixture, so it stands alone.
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
};
const SLUG = { uh60l: "uh60l", ch47f: "ch47f", oh6: "mh6" };
const profileList = () => Object.values(PROFILES);
const resolver = (activeKey) => (helo) => profileForAsset(helo, profileList(), PROFILES[activeKey]);

const helo = (id, lat, lon, key = "uh60l", rotation = 0) => ({ id, lat, lon, rotation, type: "helo", profileId: SLUG[key] });

const DISTANCES = [
  [34.5, -84.1, 34.5, -84.1], [34.5, -84.1, 34.5001, -84.1], [34.5, -84.1, 34.5, -84.0999], [34, -84, 35, -84], [34, -84, 34, -83],
  [0, 0, 0, 1], [0, 0, 1, 0], [-33.8688, 151.2093, -34.5, 150.0], [34, 179.5, 34, -179.5], [89, 0, 89, 90], [10, 10, -10, -10],
  [34.783817, -84.08219, 34.596407, -84.128098],
];

const ROTOR_EDGES = [
  ["two aircraft well apart", [34.5, -84.1, 34.5, -84.099, 26.8, 26.8]],
  ["different rotors", [34.5, -84.1, 34.5005, -84.0995, 26.8, 30.0]],
  ["no radii given: the UH-60's for both", [34.5, -84.1, 34.5, -84.099, undefined, undefined]],
  ["only the first radius given: the second is the same", [34.5, -84.1, 34.5, -84.099, 30, undefined]],
  ["the same place gives no line and no gap", [34.5, -84.1, 34.5, -84.1, 26.8, 26.8]],
  ["rotors that overlap give no gap, and the line is not inverted", [34.5, -84.1, 34.50005, -84.1, 26.8, 26.8]],
  ["rotors overlapping by about a third", [34.5, -84.1, 34.50011, -84.1, 26.8, 26.8]],
  ["rotors that just touch", [34.5, -84.1, 34.5, -84.1 + 0.0002, 26.8, 26.8]],
  ["a long way apart", [34.5, -84.1, 35.5, -83.1, 26.8, 30]],
];

const ANGLES = [
  [34.5, -84.1, 34.501, -84.1], [34.5, -84.1, 34.5, -84.099], [34.5, -84.1, 34.499, -84.1], [34.5, -84.1, 34.5, -84.101],
  [34.5, -84.1, 34.501, -84.099], [34.5, -84.1, 34.5, -84.1], [0, 0, 1, 1], [0, 0, -1, -1],
];

const HANDLES = [[34.5, -84.1, 0], [34.5, -84.1, 90], [34.5, -84.1, 180], [34.5, -84.1, 270], [34.5, -84.1, 45], [34.5, -84.1, -30], [34.5, -84.1, 725]];

// A row of aircraft running east from the target, close together, so that every nudge lands on another.
const ROW = Array.from({ length: 140 }, (_, i) => helo(1000 + i, 34.5, -84.1 + i * 0.00005));

const PLACEMENTS = [
  ["nothing down: the aircraft goes at the target", [34.5, -84.1], [], "uh60l"],
  ["one aircraft at the target: nudged east until clear", [34.5, -84.1], [helo(1, 34.5, -84.1)], "uh60l"],
  ["one aircraft well away: no nudge", [34.5, -84.1], [helo(1, 34.51, -84.1)], "uh60l"],
  ["a Chinook beside a Black Hawk needs more room than two Black Hawks", [34.5, -84.1], [helo(1, 34.5, -84.1, "uh60l")], "ch47f"],
  ["a Little Bird beside a Black Hawk", [34.5, -84.1], [helo(1, 34.5, -84.1, "uh60l")], "oh6"],
  ["two in the way", [34.5, -84.1], [helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995)], "uh60l"],
  ["the aircraft already east are measured by their own rotors", [34.5, -84.1], [helo(1, 34.5, -84.1, "ch47f")], "uh60l"],
  ["an aircraft with no profile uses the default", [34.5, -84.1], [{ id: 5, lat: 34.5, lon: -84.1, rotation: 0, type: "helo" }], "ch47f"],
  ["it gives up after fifty nudges", [34.5, -84.1], ROW, "uh60l"],
  ["south of the equator, west of the meridian", [-33.8688, -151.2093], [helo(1, -33.8688, -151.2093)], "uh60l"],
];

const ALERTS = [
  ["no aircraft", [], "uh60l"],
  ["one aircraft", [helo(1, 34.5, -84.1)], "uh60l"],
  ["two far apart", [helo(1, 34.5, -84.1), helo(2, 34.51, -84.1)], "uh60l"],
  ["two Black Hawks too close", [helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995)], "uh60l"],
  ["two Black Hawks with their rotors overlapping say zero", [helo(1, 34.5, -84.1), helo(2, 34.50005, -84.1)], "uh60l"],
  ["a Black Hawk and a Chinook name both platforms", [helo(1, 34.5, -84.1, "uh60l"), helo(2, 34.5, -84.0995, "ch47f")], "uh60l"],
  ["the stricter clearance of the two decides", [helo(1, 34.5, -84.1, "oh6"), helo(2, 34.5, -84.099, "ch47f")], "uh60l"],
  ["three in a tight row make a pair each, in the order the pairs are met", [helo(1, 34.5, -84.1), helo(2, 34.5, -84.0995), helo(3, 34.5, -84.099)], "uh60l"],
  ["an aircraft with no profile is measured as the default", [{ id: 1, lat: 34.5, lon: -84.1 }, { id: 2, lat: 34.5, lon: -84.0995 }], "ch47f"],
  ["string ids are kept as they are", [helo("a", 34.5, -84.1), helo("b", 34.5, -84.0995)], "uh60l"],
];

const TARGETS = [
  [34.5, -84.1], ["34.5", "-84.1"], [34.545678, -84.123456], [-33.8688, 151.2093], [0, 0], [34.5, "abc"], ["x", 1], [null, 1], [34.5], [],
];

const graphicsFixture = () => ({
  description: "Planning graphics as the web does them: distances in feet and the line between two rotor edges, the angle and handle "
    + "of a rotated graphic, where a new aircraft is placed so it clears those already down, which pairs raise a separation alert "
    + "and its message, and where a PZ marker, a sector of fire and a go-around start. Ids are supplied (the web draws them from the "
    + "clock). `profiles` are normal aircraft profiles; a case names its active aircraft by key.",
  generatedBy: GENERATED_BY,
  tolerance: 1e-9,
  profiles: Object.entries(PROFILES).map(([key, profile]) => ({ key, profile })),
  distanceFeet: DISTANCES.map(([lat1, lon1, lat2, lon2]) => ({ lat1, lon1, lat2, lon2, expected: getDistanceFeet(lat1, lon1, lat2, lon2) })),
  rotorEdge: ROTOR_EDGES.map(([name, [lat1, lon1, lat2, lon2, r1, r2]]) => ({
    name, lat1, lon1, lat2, lon2, radius1Ft: r1 === undefined ? null : r1, radius2Ft: r2 === undefined ? null : r2,
    expected: clean(getRotorEdgeCoords(lat1, lon1, lat2, lon2, r1, r2)),
  })),
  angle: ANGLES.map(([cLat, cLon, mLat, mLon]) => ({ cLat, cLon, mLat, mLon, expected: calculateAngle(cLat, cLon, mLat, mLon) })),
  handle: HANDLES.map(([lat, lon, rotation]) => ({ lat, lon, rotation, expected: calculateHandlePos(lat, lon, rotation) })),
  place: PLACEMENTS.map(([name, target, helicopters, active]) => ({
    name, target, helicopters, active, id: 424242,
    expected: clean(placeHelicopter({ target, helicopters, activeProfile: PROFILES[active], resolveProfile: resolver(active), id: 424242 })),
  })),
  alerts: ALERTS.map(([name, helicopters, active]) => ({
    name, helicopters, active, expected: clean(separationAlerts(helicopters, resolver(active))),
  })),
  pzMarker: TARGETS.map((target) => ({ target, id: "pz-1", expected: clean(createPzMarker(target, "pz-1")) })),
  sectorOfFire: TARGETS.map((target) => ({ target, id: "sec-1", expected: clean(createSectorOfFire(target, "sec-1")) })),
  // The web's own spellings are "left" and "right"; it only tests for "N", which nothing sends, so every one starts south of the target.
  goAround: [["left", [34.5, -84.1]], ["right", [34.5, -84.1]], ["N", [34.5, -84.1]], [undefined, [34.5, -84.1]], ["left", ["x", 1]], ["right", [-33.8688, 151.2093]]]
    .map(([direction, target]) => ({
      target, direction: direction === undefined ? null : direction, id: "ga-1", expected: clean(createGoAround(target, direction, "ga-1")),
    })),
});

describe("planning graphics fixture", () => {
  it("planning/graphics.json", () => settle("planning/graphics.json", graphicsFixture()));

  it("exercises the cases the placement and the alerts are fragile about", () => {
    const f = graphicsFixture();
    const byName = (list, needle) => list.find((c) => c.name.includes(needle));
    // A nudge really happens, and the cap really bites.
    expect(byName(f.place, "nudged east").expected.lon).toBeGreaterThan(-84.1);
    expect(byName(f.place, "no nudge").expected.lon).toBe(-84.1);
    expect(byName(f.place, "fifty nudges").expected.lon).toBeCloseTo(-84.1 + 50 * 0.0001, 9);
    // Overlapping rotors say zero, never a negative distance.
    expect(byName(f.alerts, "overlapping").expected[0].message).toContain("only 0 ft apart");
    expect(byName(f.alerts, "no aircraft").expected).toEqual([]);
    // Only the spelling "N" starts north, and nothing sends it: "left" and "right" both start south. The quirk is on record.
    const north = f.goAround.filter((c) => c.expected && c.expected.lat > c.target[0]).map((c) => c.direction);
    expect(north).toEqual(["N"]);
    expect(f.goAround.filter((c) => c.direction === "left" || c.direction === "right").every((c) => !c.expected || c.expected.lat < c.target[0])).toBe(true);
    // A PZ marker is made even for a target that is not a position (its fields are NaN, which JSON writes as null).
    expect(f.pzMarker.some((c) => c.expected && c.expected.lat === null)).toBe(true);
    expect(f.sectorOfFire.some((c) => c.expected === null)).toBe(true);
  });
});
