import {
  AGL_MISMATCH_FT,
  FT_TO_M,
  MAX_SAMPLES,
  SAMPLE_STEP_M,
  distanceM,
  placeRoutes,
  prepareRoutes,
} from "./routeScene";

const amps = (id, lat, lon, name, ptType = "turn") =>
  ({ id, lat, lon, name, kind: "amps", ptType });

const makeRoute = (overrides = {}) => ({
  id: "r1",
  name: "Route 1",
  color: "#22c55e",
  visible: true,
  points: [
    amps("a", 34.6, -84.1, ".SP", "ip"),
    { lat: 34.605, lon: -84.095, kind: "shaping", name: "" },
    amps("b", 34.61, -84.09, ".CP1"),
    amps("c", 34.62, -84.08, ".RP", "target"),
  ],
  plan: { altitude: { value: 1500, ref: "msl" } },
  // The planner's ground, in feet, per AMPS point.
  elevations: { a: 1000, b: 1100, c: 1200 },
  ...overrides,
});

const GEOID_M = -30;

/** A server answer: the same ground and geoid everywhere. */
const answer = (prepared, groundM = 300, geoidM = GEOID_M) => ({
  groundM: prepared.samples.map(() => groundM),
  geoidM: prepared.samples.map(() => geoidM),
});

const markerNamed = (route, name) => route.markers.find((m) => m.label.startsWith(name));

describe("prepareRoutes", () => {
  test("samples every vertex and the ground between them", () => {
    const prepared = prepareRoutes([makeRoute()]);
    const [route] = prepared.routes;
    expect(route.count).toBe(prepared.samples.length);

    for (const point of makeRoute().points) {
      expect(prepared.samples).toContainEqual({ lat: point.lat, lon: point.lon });
    }
    // Close enough together that a curtain's foot follows the ground.
    for (let i = 1; i < prepared.samples.length; i++) {
      expect(distanceM(prepared.samples[i - 1], prepared.samples[i]))
        .toBeLessThanOrEqual(SAMPLE_STEP_M + 0.5);
    }
  });

  test("anchors only the AMPS points, not shaping geometry", () => {
    const [route] = prepareRoutes([makeRoute()]).routes;
    expect(route.anchors.map((a) => a.name)).toEqual([".SP", ".CP1", ".RP"]);
  });

  test("leaves out hidden routes and routes that cannot be planned", () => {
    const prepared = prepareRoutes([
      makeRoute({ id: "hidden", visible: false }),
      makeRoute({ id: "short", points: [amps("a", 34.6, -84.1, ".SP")] }),
      makeRoute({ id: "broken", points: [amps("a", 34.6, -84.1, ".SP"),
                                         amps("b", Number.NaN, -84.09, ".CP1")] }),
      makeRoute({ id: "kept" }),
    ]);
    expect(prepared.routes.map((r) => r.id)).toEqual(["kept"]);
  });

  test("samples long routes more coarsely rather than exceeding the server cap", () => {
    const long = makeRoute({
      points: [amps("a", 34.0, -84.5, ".SP"), amps("b", 35.5, -83.0, ".RP")],
      elevations: {},
    });
    const prepared = prepareRoutes([long, { ...long, id: "r2" }]);
    expect(prepared.samples.length).toBeLessThanOrEqual(MAX_SAMPLES);
    expect(prepared.samples.length).toBeGreaterThan(MAX_SAMPLES * 0.9);
  });

  test("has nothing to ask for when there are no routes", () => {
    expect(prepareRoutes([]).samples).toEqual([]);
    expect(prepareRoutes(undefined).samples).toEqual([]);
  });
});

describe("placeRoutes", () => {
  test("places AMPS points at their planned MSL, corrected to the ellipsoid", () => {
    const prepared = prepareRoutes([makeRoute()]);
    const [route] = placeRoutes(prepared, answer(prepared));
    const sp = markerNamed(route, "SP");
    // 1,500 ft MSL is 457.2 m; Cesium wants it 30 m lower here.
    expect(sp.heightM).toBeCloseTo(1500 * FT_TO_M + GEOID_M, 6);
  });

  test("climbs in a straight line between AMPS points, shaping points included", () => {
    const route = makeRoute({ plan: { altitude: { value: 1500, ref: "msl" },
                                      perPoint: { b: { altitude: { value: 2500, ref: "msl" } } } } });
    const prepared = prepareRoutes([route]);
    const [placed] = placeRoutes(prepared, answer(prepared));
    const line = placed.lines[0];
    const heights = line.map(([, , h]) => h);

    expect(heights[0]).toBeCloseTo(1500 * FT_TO_M + GEOID_M, 6);
    // Rising all the way to CP1 at 2,500 ft, then level to the RP.
    const cp1 = markerNamed(placed, "CP1");
    expect(cp1.heightM).toBeCloseTo(2500 * FT_TO_M + GEOID_M, 6);
    const cp1Index = line.findIndex(([lon, lat]) => lon === cp1.lon && lat === cp1.lat);
    for (let i = 1; i <= cp1Index; i++) expect(heights[i]).toBeGreaterThan(heights[i - 1]);

    // The shaping vertex sits partway up, by distance — not at either end.
    const shaping = line.find(([lon, lat]) => lon === -84.095 && lat === 34.605);
    expect(shaping[2]).toBeGreaterThan(heights[0]);
    expect(shaping[2]).toBeLessThan(cp1.heightM);
  });

  test("labels give the planned MSL and the AGL over the 3D ground", () => {
    const prepared = prepareRoutes([makeRoute()]);
    // Ground where the planner thought it was: 1,000 ft at the SP.
    const groundM = 1000 * FT_TO_M + GEOID_M;
    const [route] = placeRoutes(prepared, answer(prepared, groundM));
    const sp = markerNamed(route, "SP");
    expect(sp.label).toBe("SP\n1,500' MSL · 500' AGL");
    expect(sp.mismatch).toBe(false);
  });

  test("flags a point where the planner's ground was wrong", () => {
    const prepared = prepareRoutes([makeRoute()]);
    // The 3D ground is 100 ft higher than the planner assumed at the SP.
    const groundM = 1100 * FT_TO_M + GEOID_M;
    const [route] = placeRoutes(prepared, answer(prepared, groundM));
    const sp = markerNamed(route, "SP");
    expect(sp.measuredAglFt).toBeCloseTo(400, 6);
    expect(Math.abs(sp.measuredAglFt - 500)).toBeGreaterThan(AGL_MISMATCH_FT);
    expect(sp.mismatch).toBe(true);
    expect(sp.label).toBe("SP\n1,500' MSL · 400' AGL\nplanned 500' AGL");
  });

  test("an AGL point with no planned MSL sits on the 3D ground plus its AGL", () => {
    const route = makeRoute({ plan: { altitude: { value: 300, ref: "agl" } }, elevations: {} });
    const prepared = prepareRoutes([route]);
    const [placed] = placeRoutes(prepared, answer(prepared, 400));
    const sp = markerNamed(placed, "SP");
    expect(sp.heightM).toBeCloseTo(400 + 300 * FT_TO_M, 6);
    expect(sp.label).toBe("SP\n300' AGL · no planned MSL");
  });

  test("never invents a height where there is no ground to place against", () => {
    const route = makeRoute({ plan: { altitude: { value: 300, ref: "agl" } }, elevations: {} });
    const prepared = prepareRoutes([route]);
    const heights = answer(prepared, 400);
    // No DEM under the RP.
    const rp = prepared.routes[0].anchors[2].sample;
    heights.groundM[rp] = null;

    const [placed] = placeRoutes(prepared, heights);
    expect(markerNamed(placed, "RP")).toBeUndefined();
    // The leg into the RP is not drawn at a guessed height.
    const drawn = placed.lines.flat();
    expect(drawn.every(([, , h]) => Number.isFinite(h))).toBe(true);
    expect(drawn.find(([lon, lat]) => lon === -84.08 && lat === 34.62)).toBeUndefined();
  });

  test("an MSL route still draws where the ground is unknown, without its curtain", () => {
    const prepared = prepareRoutes([makeRoute()]);
    const heights = answer(prepared);
    const middle = Math.floor(prepared.samples.length / 2);
    heights.groundM[middle] = null;

    const [placed] = placeRoutes(prepared, heights);
    expect(placed.lines).toHaveLength(1);
    expect(placed.lines[0]).toHaveLength(prepared.samples.length);
    expect(placed.walls).toHaveLength(2);
    for (const wall of placed.walls) {
      expect(wall.groundM.every((g) => g != null)).toBe(true);
      expect(wall.groundM).toHaveLength(wall.positions.length);
    }
  });

  test("ignores heights that belong to a different set of routes", () => {
    const prepared = prepareRoutes([makeRoute()]);
    const stale = { groundM: [300, 300], geoidM: [-30, -30] };
    expect(placeRoutes(prepared, stale)).toEqual([]);
    expect(placeRoutes(prepared, null)).toEqual([]);
  });

  test("keeps each route's colour", () => {
    const prepared = prepareRoutes([makeRoute(), makeRoute({ id: "r2", color: "#f97316" })]);
    const placed = placeRoutes(prepared, answer(prepared));
    expect(placed.map((r) => r.color)).toEqual(["#22c55e", "#f97316"]);
  });
});
