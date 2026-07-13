import {
  distanceNm,
  trueCourseDeg,
  iasToTas,
  solveWindTriangle,
  computeRoutePlan,
  defaultRoutePlan,
} from "./routeCalc";

describe("distance and course", () => {
  test("one degree of latitude is ~60 nm, due north", () => {
    expect(distanceNm(34, -84, 35, -84)).toBeCloseTo(60.04, 0);
    expect(trueCourseDeg(34, -84, 35, -84)).toBeCloseTo(0, 5);
  });

  test("due east course at 34N", () => {
    expect(trueCourseDeg(34, -84, 34, -83)).toBeCloseTo(90, 0);
  });
});

describe("wind triangle", () => {
  test("no wind returns TAS as GS", () => {
    expect(solveWindTriangle(120, 90, 0, 0).gsKts).toBe(120);
  });

  test("direct headwind subtracts fully", () => {
    const { gsKts, windCorrectionDeg } = solveWindTriangle(120, 360, 360, 20);
    expect(gsKts).toBeCloseTo(100, 5);
    expect(windCorrectionDeg).toBeCloseTo(0, 5);
  });

  test("direct tailwind adds fully", () => {
    expect(solveWindTriangle(120, 360, 180, 20).gsKts).toBeCloseTo(140, 5);
  });

  test("pure crosswind slows GS and crabs into the wind", () => {
    const { gsKts, windCorrectionDeg } = solveWindTriangle(120, 360, 90, 30);
    expect(windCorrectionDeg).toBeCloseTo(14.48, 1);
    expect(gsKts).toBeCloseTo(120 * Math.cos((14.48 * Math.PI) / 180), 1);
  });

  test("unflyable wind returns null", () => {
    expect(solveWindTriangle(20, 360, 90, 40)).toBeNull();
  });
});

describe("IAS to TAS", () => {
  test("sea level ISA is unchanged", () => {
    expect(iasToTas(100, 0, 15)).toBeCloseTo(100, 5);
  });

  test("5000 ft ISA gains ~2 percent per 1000 ft", () => {
    expect(iasToTas(100, 5000, 15 - 1.98 * 5)).toBeCloseTo(110, 5);
  });
});

describe("computeRoutePlan", () => {
  const mkPoint = (id, lat, lon, kind = "amps", name = id) => ({
    id,
    lat,
    lon,
    kind,
    ptType: kind === "amps" ? "turn" : null,
    name,
  });

  // ~60 nm north then ~60 nm further north, with a shaping point mid-leg 1.
  const route = {
    id: "r1",
    points: [
      mkPoint("a", 34, -84, "amps", ".SP"),
      mkPoint("s1", 34.5, -84.1, "shaping", ""),
      mkPoint("b", 35, -84, "amps", ".CP1"),
      mkPoint("c", 36, -84, "amps", ".LZ"),
    ],
  };

  test("legs follow shaping geometry and a point clock anchors clock times", () => {
    const plan = {
      ...defaultRoutePlan(),
      airspeed: { value: 120, type: "ground" },
      date: "2026-07-07",
      perPoint: { c: { clock: "10:00:00" } },
    };
    const { points, legs, totals } = computeRoutePlan(route, plan, {});

    expect(legs).toHaveLength(2);
    // Leg 1 detours through the shaping point, so it's longer than direct.
    expect(legs[0].distNm).toBeGreaterThan(60.5);
    expect(legs[1].distNm).toBeCloseTo(60.04, 0);
    expect(legs[0].gsKts).toBe(120);

    // The LZ hits exactly at 10:00:00; earlier points back-computed.
    const lz = points.find((p) => p.id === "c");
    expect(lz.isTotAnchor).toBe(true);
    expect(lz.hasClock).toBe(true);
    expect(lz.clockTime.getHours()).toBe(10);
    expect(lz.clockTime.getMinutes()).toBe(0);
    const sp = points.find((p) => p.id === "a");
    expect(sp.elapsedSec).toBe(0);
    const totalSec = (lz.clockTime - sp.clockTime) / 1000;
    // Clock times round to whole milliseconds, so compare at ms precision.
    expect(totalSec).toBeCloseTo(totals.timeSec, 2);

    // ~121nm at 120 GS is just over an hour.
    expect(totals.timeSec).toBeGreaterThan(3600);
    expect(totals.timeSec).toBeLessThan(3700);
  });

  test("per-point 'to' airspeed overrides the arriving leg only", () => {
    const plan = {
      ...defaultRoutePlan(),
      airspeed: { value: 100, type: "ground" },
      // The leg arriving at the LZ is flown at 60 kts; earlier legs at 100.
      perPoint: { c: { airspeed: { value: 60, type: "ground" } } },
    };
    const { legs } = computeRoutePlan(route, plan, {});
    expect(legs[0].gsKts).toBe(100); // a→b
    expect(legs[1].gsKts).toBe(60); // b→c (arriving at LZ)
  });

  test("AGL altitudes resolve to MSL with elevations", () => {
    const plan = {
      ...defaultRoutePlan(),
      altitude: { value: 300, ref: "agl" },
      perPoint: { c: { altitude: { value: 2000, ref: "msl" } } },
    };
    const { points } = computeRoutePlan(route, plan, { a: 1000, b: 1200, c: 900 });
    expect(points.find((p) => p.id === "a").mslFt).toBe(1300);
    expect(points.find((p) => p.id === "b").mslFt).toBe(1500);
    const lz = points.find((p) => p.id === "c");
    expect(lz.mslFt).toBe(2000);
    expect(lz.aglFt).toBe(1100);
  });

  test("indicated airspeed converts through TAS with wind", () => {
    const plan = {
      ...defaultRoutePlan(),
      airspeed: { value: 100, type: "indicated" },
      altitude: { value: 5000, ref: "msl" },
      tempC: 15 - 1.98 * 5,
      wind: { dirTrue: 180, speedKts: 10 }, // tailwind for northbound legs
    };
    const { legs } = computeRoutePlan(route, plan, {});
    // 100 KIAS @ 5000 ft ISA ≈ 110 KTAS, +10 tailwind ≈ 120 GS.
    expect(legs[1].tasKts).toBeCloseTo(110, 0);
    expect(legs[1].gsKts).toBeCloseTo(120, 0);
  });
});
