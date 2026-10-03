import { buildSketchRoute, withPointClock, withPointOverride, withPlanPatch, designatePoint } from "../feature/msnxImport/sketchOps";
import { defaultRoutePlan } from "../feature/msnxImport/routeCalc";
import { windRequest, mergeWindsIntoPlan } from "../feature/msnxImport/routeWinds";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// Forecast winds for a sketched route: which instant each point's wind is asked for, and how the answer is merged into the plan. The server picks the
// station and the METAR or TAF (that is the backend's, recorded in the network fixtures); what is the *app's* to get right is the question it sends and
// what it does with the answer, and both are pure here.
//
// A time is asked for as an instant (an ISO string in UTC), and which instant a wall-clock time is depends on the device's time zone. The fixture holds
// the wall-clock time (`localTime`, no zone), which does not depend on where it is generated or checked; the apps are held to that, and to turning
// it into an instant for their own zone.

const GENERATED_BY = "frontend/src/contracts/routeWindsFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));

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

const ids = () => {
  let n = 0;
  return () => `p${++n}`;
};

const two = (n) => String(n).padStart(2, "0");
/** An instant as the wall-clock time it is in this zone, to the millisecond (a Date holds whole milliseconds, and the request carries them), so the fixture does not depend on the zone it was made in. */
const localTime = (iso) => {
  if (iso == null) return null;
  const d = new Date(iso);
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, "0")}`;
};

const draftOf = (n) => Array.from({ length: n }, (_, i) => ({ lat: 34.7 + i * 0.01, lon: -84.1 + i * 0.01, designation: null }));
const build = (n, plan = defaultRoutePlan(null)) => buildSketchRoute(draftOf(n), { name: "ROUTE 1", id: "sketch-1", color: "#FF453A", plan, newPointId: ids() });

const FIVE = build(5);
const DATED = withPlanPatch(FIVE, { date: "2026-10-03" });
const CLOCKED = withPointClock(DATED, "p4", "12:30:00");
const FAST = withPlanPatch(CLOCKED, { airspeed: { value: 140, type: "true" } });
const TRUE_AS = withPlanPatch(CLOCKED, { airspeed: { value: 100, type: "true" } });
const WINDY = withPointOverride(TRUE_AS, "p2", { wind: { dirTrue: 270, speedKts: 40 } });
const INDICATED = withPlanPatch(CLOCKED, { airspeed: { value: 100, type: "indicated" } });
const ALL_SHAPING = { ...FIVE, points: FIVE.points.map((p) => ({ ...p, kind: "shaping" })) };
const NO_IDS = { ...FIVE, points: FIVE.points.map((p, i) => (i === 2 ? { ...p, kind: "amps", id: undefined } : p)) };

const request = (label, route, elevations) => {
  const input = elevations ? { ...route, elevations } : route;
  const result = windRequest(input);
  return {
    label,
    route: saved(input),
    expected: result.error
      ? { error: result.error }
      : { points: result.points.map((p) => ({ id: p.id, lat: p.lat, lon: p.lon, localTime: localTime(p.time) })), ampIds: result.amps.map((p) => p.id) },
  };
};

const merge = (label, plan, ampIds, winds) => ({
  label,
  plan: saved(plan),
  ampIds,
  winds: saved(winds),
  expected: saved(mergeWindsIntoPlan(plan, ampIds.map((id) => ({ id })), winds)),
});

const MERGE_PLAN = withPointOverride(withPointOverride(withPlanPatch(FIVE, { tempC: 22 }), "p2", { altitude: { value: 200, ref: "agl" } }), "p4", { wind: { dirTrue: 10, speedKts: 3 }, airspeed: { value: 90, type: "indicated" } }).plan;

const windsFixture = () => ({
  generatedBy: GENERATED_BY,
  request: [
    request("with a date and no clock every point is asked for at local midday", DATED),
    request("with a clock the points are asked for at the times they will be there", CLOCKED),
    request("a faster airspeed brings the later points forward", FAST),
    request("a true airspeed in no wind", TRUE_AS),
    request("the same with a wind on the leg to one point: a headwind slows it, and the points around it move", WINDY),
    request("an indicated airspeed with no ground elevations", INDICATED),
    request("ground elevations change the true airspeed, and so the times", INDICATED, { p1: 5000, p2: 6000, p4: 7000, p5: 7500 }),
    request("with no date and no clock there is no time to ask for", FIVE),
    request("a date that is not one is no time either", withPlanPatch(FIVE, { date: "next tuesday" })),
    request("a clock with a date: the date is the day it falls on", withPointClock(withPlanPatch(FIVE, { date: "2027-01-15" }), "p2", "23:50:00")),
    request("shaping points are not asked about", designatePoint(DATED, "p3", { kind: "shaping" })),
    request("a point with no id is left out", withPlanPatch(NO_IDS, { date: "2026-10-03" })),
    request("a route with no named points has nothing to ask", ALL_SHAPING),
  ],
  merge: [
    merge("a wind for each point becomes that point's wind override", MERGE_PLAN, ["p1", "p2", "p4", "p5"], {
      p1: { dirTrue: 270, speedKts: 12, tempC: 18, station: "KRYY", source: "METAR", distanceMiles: 4.2 },
      p2: { dirTrue: 280, speedKts: 14, tempC: 17, station: "KRYY", source: "METAR", distanceMiles: 4.2 },
      p4: { dirTrue: 300, speedKts: 20, tempC: 16, station: "KCNI", source: "TAF", distanceMiles: 20.1 },
      p5: { dirTrue: 310, speedKts: 22, tempC: 15, station: "KCNI", source: "TAF", distanceMiles: 20.1 },
    }),
    merge("what the point already had stays, and only its wind is replaced", MERGE_PLAN, ["p2", "p4"], { p2: { dirTrue: 100, speedKts: 8 }, p4: { dirTrue: 200, speedKts: 9 } }),
    merge("the temperature is the first point's that has one", MERGE_PLAN, ["p1", "p2", "p4"], {
      p1: { dirTrue: 1, speedKts: 1, tempC: null }, p2: { dirTrue: 2, speedKts: 2, tempC: 5 }, p4: { dirTrue: 3, speedKts: 3, tempC: 9 },
    }),
    merge("with no temperature anywhere the plan's own stays", MERGE_PLAN, ["p1", "p2"], { p1: { dirTrue: 1, speedKts: 1 }, p2: { dirTrue: 2, speedKts: 2, tempC: "warm" } }),
    merge("a point nobody answered for is left alone", MERGE_PLAN, ["p1", "p2", "p4"], { p1: { dirTrue: 90, speedKts: 5, tempC: 12 } }),
    merge("an answer for a point that is not in the route is ignored", MERGE_PLAN, ["p1"], { p1: { dirTrue: 90, speedKts: 5 }, zzz: { dirTrue: 1, speedKts: 1, tempC: 99 } }),
    merge("variable wind comes back as direction 0 and is kept as such", MERGE_PLAN, ["p1"], { p1: { dirTrue: 0, speedKts: 3, variable: true, tempC: 20 } }),
    merge("no answers at all changes nothing", MERGE_PLAN, ["p1", "p2"], {}),
    merge("a plan that lacks a field takes the default for it", { airspeed: { value: 120, type: "true" } }, ["p1"], { p1: { dirTrue: 45, speedKts: 7 } }),
  ],
});

describe("forecast winds for a route", () => {
  it("are what the fixture says (and the committed fixture is current)", () => {
    settle("routes/winds.json", windsFixture());
  });

  it("start from the routes the fixture lists", () => {
    const f = windsFixture();
    const by = (start) => f.request.find((c) => c.label.startsWith(start)).expected;
    const times = (start) => by(start).points.map((p) => p.localTime);
    expect(times("an indicated airspeed")).not.toEqual(times("ground elevations"));                    // the elevations matter to an indicated airspeed
    expect(times("a true airspeed")).not.toEqual(times("the same with a wind"));                       // and a wind to a true one
    expect(times("with a date and no clock")).toEqual(Array(4).fill("2026-10-03T12:00:00.000"));            // five points, one of them shaping
    expect(times("with a clock")[2]).toBe("2026-10-03T12:30:00.000");                                        // the anchor (p4, the third named point) is where it was asked to be
    expect(times("with no date").every((t) => t === null)).toBe(true);
    expect(by("a route with no named points")).toEqual({ error: "Route has no points." });
  });
});
