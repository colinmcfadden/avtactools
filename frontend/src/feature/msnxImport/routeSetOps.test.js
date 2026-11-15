import { ROUTE_COLORS } from "./colorPalette";
import { ROUTE_COLOR_CHOICES, combineRoutes, isRouteColorChoice, routeColorName, routesMeet, whyNotCombined, withRouteColor } from "./routeSetOps";
import { computeRoutePlan, defaultRoutePlan, planPoints } from "./routeCalc";

const amps = (id, lat, lon, name, ptType = "turn", role = "waypoint") => ({ id, lat, lon, ele: null, role, kind: "amps", ptType, name });
const shaping = (id, lat, lon) => ({ id, lat, lon, ele: null, role: "waypoint", kind: "shaping", ptType: null, name: "" });

const plan = (extra = {}) => ({ ...defaultRoutePlan(), ...extra });

const A = {
  id: "sketch-a",
  name: "ROUTE 3",
  color: "#FFD60A",
  visible: true,
  setId: "opened-1",
  elevations: { a1: 1200, a3: 1500 },
  plan: plan({ date: "2026-12-01", tempC: 10, perPoint: { a3: { altitude: { value: 500, ref: "AGL" } } } }),
  points: [amps("a1", 34.5, -84.5, ".TGT", "target", "start"), shaping("a2", 34.55, -84.45), amps("a3", 34.6, -84.4, ".RP", "ip"), amps("a4", 34.65, -84.35, ".TGT", "target")],
};
const B = {
  id: "sketch-b",
  name: "ROUTE 4",
  color: "#BF5AF2",
  visible: false,
  setId: "opened-1",
  elevations: { b1: 1800, b2: 1900, b3: 2000 },
  plan: plan({ date: "2026-12-02", tempC: 20, perPoint: { b3: { wind: { dirTrue: 270, speedKts: 12 } } } }),
  points: [amps("b1", 34.7, -84.3, ".TGT", "target", "start"), amps("b2", 34.75, -84.25, ".SP", "ip"), shaping("b3x", 34.78, -84.22), amps("b3", 34.8, -84.2, ".LZ", "target")],
};

describe("two routes made one", () => {
  it("is the first route grown longer: its id, name, colour, set and whether it is shown", () => {
    const one = combineRoutes(A, B);
    expect(one.id).toBe("sketch-a");
    expect(one.name).toBe("ROUTE 3");
    expect(one.color).toBe("#FFD60A");
    expect(one.visible).toBe(true);
    expect(one.setId).toBe("opened-1");
  });

  it("has the second's points after the first's, each in the order drawn, every one as it was", () => {
    const one = combineRoutes(A, B);
    expect(one.points.map((p) => p.id)).toEqual(["a1", "a2", "a3", "a4", "b1", "b2", "b3x", "b3"]);
    expect(one.points.map((p) => p.name)).toEqual([".TGT", "", ".RP", ".TGT", ".TGT", ".SP", "", ".LZ"]);
    expect(one.points.map((p) => p.kind)).toEqual(["amps", "shaping", "amps", "amps", "amps", "amps", "shaping", "amps"]);
    expect(one.points.map((p) => p.ptType)).toEqual(["target", null, "ip", "target", "target", "ip", null, "target"]);
    expect(one.points.slice(0, 4)).toEqual(A.points);
    // One point starts the route.
    expect(one.points.filter((p) => p.role === "start").map((p) => p.id)).toEqual(["a1"]);
    expect(one.points[4]).toEqual({ ...B.points[0], role: "waypoint" });
  });

  it("the other way round is the other route", () => {
    const one = combineRoutes(B, A);
    expect(one.id).toBe("sketch-b");
    expect(one.name).toBe("ROUTE 4");
    expect(one.points.map((p) => p.id)).toEqual(["b1", "b2", "b3x", "b3", "a1", "a2", "a3", "a4"]);
  });

  it("changes neither route it was given", () => {
    const a = JSON.stringify(A);
    const b = JSON.stringify(B);
    combineRoutes(A, B);
    expect(JSON.stringify(A)).toBe(a);
    expect(JSON.stringify(B)).toBe(b);
  });

  it("keeps the first's plan, and the ground under both", () => {
    const one = combineRoutes(A, B);
    expect(one.plan.date).toBe("2026-12-01");
    expect(one.plan.tempC).toBe(10);
    expect(one.plan.perPoint.a3).toEqual({ altitude: { value: 500, ref: "AGL" } });
    expect(one.plan.perPoint.b3).toEqual({ wind: { dirTrue: 270, speedKts: 12 } });
    expect(one.elevations).toEqual({ a1: 1200, a3: 1500, b1: 1800, b2: 1900, b3: 2000 });
  });

  it("is a route the planner can plan: more distance than either, and both ends named", () => {
    const one = combineRoutes(A, B);
    const totals = (route) => computeRoutePlan(route, route.plan, route.elevations).totals;
    expect(totals(one).distNm).toBeGreaterThan(totals(A).distNm + totals(B).distNm);
    expect(planPoints(one).map((p) => p.id)).toEqual(["a1", "a3", "a4", "b1", "b2", "b3"]);
  });
});

describe("where the second begins on the first's last point", () => {
  const onto = { ...B, points: [{ ...B.points[0], lat: 34.65, lon: -84.35 }, ...B.points.slice(1)] };

  it("that place is one point, the first's", () => {
    expect(routesMeet(A, onto)).toBe(true);
    const one = combineRoutes(A, onto);
    expect(one.points.map((p) => p.id)).toEqual(["a1", "a2", "a3", "a4", "b2", "b3x", "b3"]);
    expect(one.elevations).toEqual({ a1: 1200, a3: 1500, b2: 1900, b3: 2000 });
    expect(one.plan.perPoint.b1).toBeUndefined();
  });

  it("the same place is to about a metre, not two clicks near each other", () => {
    const near = (dLat, dLon) => ({ ...B, points: [{ ...B.points[0], lat: 34.65 + dLat, lon: -84.35 + dLon }, ...B.points.slice(1)] });
    expect(routesMeet(A, near(0.000005, -0.000005))).toBe(true);
    expect(routesMeet(A, near(0.0002, 0))).toBe(false);
    expect(routesMeet(A, near(0, 0.0002))).toBe(false);
    expect(combineRoutes(A, near(0.0002, 0)).points).toHaveLength(8);
    // The first's end and the second's start, never the other ends.
    expect(routesMeet(onto, A)).toBe(false);
    expect(routesMeet(A, { ...B, points: [] })).toBe(false);
    expect(routesMeet({ ...A, points: [] }, B)).toBe(false);
  });

  it("the clock the second kept at that point goes to it", () => {
    const clocked = { ...onto, plan: plan({ perPoint: { b1: { clock: "10:00:00" } } }) };
    const one = combineRoutes(A, clocked);
    expect(one.plan.perPoint.a4).toEqual({ clock: "10:00:00" });
    // The first's own clock stands: one point holds it.
    const first = { ...A, plan: plan({ perPoint: { a1: { clock: "09:00:00" } } }) };
    const two = combineRoutes(first, clocked);
    expect(Object.entries(two.plan.perPoint).filter(([, entry]) => entry.clock)).toEqual([["a1", { clock: "09:00:00" }]]);
  });
});

describe("how the second's legs are flown once joined", () => {
  const slow = { ...B, plan: plan({ airspeed: { value: 80, type: "IAS" }, altitude: { value: 300, ref: "AGL" }, perPoint: { b3: { airspeed: { value: 60, type: "IAS" } } } }) };

  it("as they were planned: what a point took from its route, it takes as its own where the first's differs", () => {
    const one = combineRoutes(A, slow);
    // The leg to b2 was flown at the second's 80 kt and 300 ft; the first's are others.
    expect(one.plan.perPoint.b2).toEqual({ altitude: { value: 300, ref: "AGL" }, airspeed: { value: 80, type: "IAS" } });
    // A value of the point's own stands; only what it took from its route is filled in.
    expect(one.plan.perPoint.b3).toEqual({ airspeed: { value: 60, type: "IAS" }, altitude: { value: 300, ref: "AGL" } });
    // The point the second started at had no leg arriving: the new leg to it is the first's to fly.
    expect(one.plan.perPoint.b1).toBeUndefined();
    // The first's own route values are as they were.
    expect(one.plan.airspeed).toEqual(A.plan.airspeed);
    expect(one.plan.altitude).toEqual(A.plan.altitude);
  });

  it("nothing is written where the two routes fly the same", () => {
    const one = combineRoutes(A, { ...B, plan: plan() });
    expect(one.plan.perPoint).toEqual(A.plan.perPoint);
  });

  it("a shaping point has no values of its own", () => {
    expect(combineRoutes(A, slow).plan.perPoint.b3x).toBeUndefined();
  });

  it("the second's clock is kept only when the first has none", () => {
    const clocked = { ...B, plan: plan({ perPoint: { b2: { clock: "11:30:00" } } }) };
    expect(combineRoutes(A, clocked).plan.perPoint.b2).toEqual({ clock: "11:30:00" });
    const first = { ...A, plan: plan({ perPoint: { a3: { clock: "09:00:00" } } }) };
    expect(combineRoutes(first, clocked).plan.perPoint.b2).toBeUndefined();
  });
});

describe("points the two routes share by id", () => {
  it("get new ids in the second, their values and ground with them", () => {
    let n = 0;
    const twin = { ...A, id: "sketch-twin", name: "TWIN", elevations: { a1: 1111, a4: 4444 }, plan: plan({ perPoint: { a3: { clock: "12:00:00" } } }) };
    const one = combineRoutes({ ...A, points: A.points.slice(0, 3) }, twin, () => `new-${(n += 1)}`);
    expect(one.points.map((p) => p.id)).toEqual(["a1", "a2", "a3", "new-1", "new-2", "new-3", "a4"]);
    expect(new Set(one.points.map((p) => p.id)).size).toBe(one.points.length);
    expect(one.elevations).toEqual({ a1: 1200, a3: 1500, "new-1": 1111, a4: 4444 });
    expect(one.plan.perPoint["new-3"]).toEqual({ clock: "12:00:00" });
    expect(one.plan.perPoint.a3).toEqual({ altitude: { value: 500, ref: "AGL" } });
  });
});

describe("what cannot be joined", () => {
  it("is said in words", () => {
    expect(whyNotCombined(A, B)).toBeNull();
    expect(whyNotCombined(A, A)).toBe("A route cannot be joined to itself.");
    expect(whyNotCombined(A, null)).toBe("That route is no longer here.");
    expect(whyNotCombined(undefined, B)).toBe("That route is no longer here.");
  });
});

describe("a route's colour", () => {
  it("is one of twelve: the eight new routes take in turn, then four more, each with a name", () => {
    expect(ROUTE_COLOR_CHOICES.slice(0, 8).map((c) => c.color)).toEqual(ROUTE_COLORS);
    expect(ROUTE_COLOR_CHOICES).toHaveLength(12);
    expect(new Set(ROUTE_COLOR_CHOICES.map((c) => c.color.toLowerCase())).size).toBe(12);
    expect(new Set(ROUTE_COLOR_CHOICES.map((c) => c.name)).size).toBe(12);
    // #RRGGBB, as every reader of a saved route expects (the map, the 3D view, Android).
    ROUTE_COLOR_CHOICES.forEach((c) => expect(c.color).toMatch(/^#[0-9A-F]{6}$/));
  });

  it("is named whatever its case, and has no name when it is none of them", () => {
    expect(routeColorName("#ff453a")).toBe("Red");
    expect(routeColorName("#FFFFFF")).toBe("White");
    expect(routeColorName("#123456")).toBeNull();
    expect(routeColorName(undefined)).toBeNull();
    expect(isRouteColorChoice("#0a84ff")).toBe(true);
    expect(isRouteColorChoice("red")).toBe(false);
  });

  it("changes to one of the choices, and to nothing else", () => {
    expect(withRouteColor(A, "#0a84ff")).toEqual({ ...A, color: "#0A84FF" });
    // The same colour, or one that is not a choice, is the same route: no step to undo.
    expect(withRouteColor(A, "#ffd60a")).toBe(A);
    expect(withRouteColor(A, "#123456")).toBe(A);
    expect(withRouteColor(A, "url(javascript:alert(1))")).toBe(A);
    expect(withRouteColor(A, null)).toBe(A);
  });
});
