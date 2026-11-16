import { computeRoutePlan, defaultRoutePlan } from "../msnxImport/routeCalc";
import { project0 } from "../missionOverlay/overlayPlan";
import {
  courseText,
  fitsAt,
  GAP_PX,
  HALF_LENGTH_PX,
  HALF_WIDTH_PX,
  legTime,
  MIN_LEG_PX,
  roofName,
  routeDoghouses,
  sideOffset,
} from "./routeDoghouses";

const point = (id, lat, lon, extra = {}) => ({ id, lat, lon, kind: "amps", ptType: "turn", name: `.${id}`, ...extra });

// SP, a shaping point that bends the first leg, CP1, then the target: three legs.
const ROUTE = {
  id: "r1",
  name: "ROUTE 1",
  color: "#ff0000",
  points: [
    point("SP", 34.6, -84.5, { ptType: "ip" }),
    { id: "s1", lat: 34.62, lon: -84.45, kind: "shaping" },
    point("CP1", 34.6, -84.4),
    point("TGT", 34.5, -84.4, { ptType: "target", name: ".TGT" }),
  ],
  plan: { airspeed: { value: 100, type: "true" }, wind: { dirTrue: 0, speedKts: 0 } },
};

describe("a route's doghouses", () => {
  it("puts one on each leg between route points, saying what the route's plan says", () => {
    const plan = computeRoutePlan(ROUTE, { ...defaultRoutePlan(), ...ROUTE.plan }, {});
    const doghouses = routeDoghouses([ROUTE]);
    expect(doghouses).toHaveLength(2);
    const [first, second] = doghouses;
    expect(first.box).toEqual({
      id: "CP1",
      heading: courseText(plan.legs[0].courseTrueDeg),
      ...legTime(plan.legs[0].timeSec),
      distance: (plan.legs[0].distNm * 1.852).toFixed(1),
      airspeed: "100",
    });
    // The second leg runs due south, and is turned so.
    expect(second.box.id).toBe("TGT");
    expect(second.box.heading).toBe("180");
    expect(second.rotation).toBeCloseTo(180, 6);
    // 0.1° of latitude at 100 kt: 6.0 nm, 3 min 36 s.
    expect(second.box.distance).toBe("11.1");
    expect(second.box).toMatchObject({ minutes: "03", seconds: "36" });
  });

  it("stands at the middle of the leg's longest straight stretch, not on a bend", () => {
    const bentAt = (lon) => ({ ...ROUTE, points: ROUTE.points.map((p) => (p.id === "s1" ? { ...p, lon } : p)) });
    // Bent near its end, the leg's longer stretch is its first.
    const [early] = routeDoghouses([bentAt(-84.42)]);
    expect(early.lon).toBeCloseTo(-84.46, 6);
    expect(early.lat).toBeGreaterThan(34.6);
    expect(early.lat).toBeLessThan(34.62);
    // Bent near its start, its second.
    const [late] = routeDoghouses([bentAt(-84.48)]);
    expect(late.lon).toBeCloseTo(-84.44, 6);
    // Its length is the whole leg's, both stretches.
    const [sp, s1, cp1] = [project0(34.6, -84.5), project0(34.62, -84.48), project0(34.6, -84.4)];
    expect(late.lengthPx0).toBeCloseTo(Math.hypot(s1.x - sp.x, s1.y - sp.y) + Math.hypot(cp1.x - s1.x, cp1.y - s1.y), 9);
  });

  it("stands clear of the line, on the right of the way it is flown", () => {
    // A straight leg: across the box's own width, to its right.
    expect(sideOffset(90, 90)).toEqual({ x: HALF_WIDTH_PX + GAP_PX, y: 0 });
    // A stretch at right angles to the course: the box's length is across it now, and it is moved along its own axis.
    expect(sideOffset(0, 90)).toEqual({ x: 0, y: HALF_LENGTH_PX + GAP_PX });
    expect(sideOffset(0, -90)).toEqual({ x: 0, y: -(HALF_LENGTH_PX + GAP_PX) });
    // Between, it is moved far enough that the turned box's nearest corner keeps the gap from the stretch's line.
    const { x, y } = sideOffset(59, 15);
    const off = ((15 - 59) * Math.PI) / 180;
    const fromLine = x * Math.cos(off) + y * Math.sin(off);
    const halfAcross = HALF_WIDTH_PX * Math.abs(Math.cos(off)) + HALF_LENGTH_PX * Math.abs(Math.sin(off));
    expect(fromLine - halfAcross).toBeCloseTo(GAP_PX, 0);
    // Each leg's doghouse carries its own; the straight leg due south is moved straight across.
    const [first, second] = routeDoghouses([ROUTE]);
    expect(second.offset).toEqual({ x: HALF_WIDTH_PX + GAP_PX, y: 0 });
    expect(first.offset.y).not.toBe(0);
  });

  it("is drawn only where the leg is long enough on the screen to hold it", () => {
    const [, second] = routeDoghouses([ROUTE]);
    const zoom = Math.log2(MIN_LEG_PX / second.lengthPx0);
    expect(fitsAt(second, zoom + 0.01)).toBe(true);
    expect(fitsAt(second, zoom - 0.01)).toBe(false);
    expect(fitsAt(second, undefined)).toBe(false);
  });

  it("has none for a route hidden, one of a single route point, or one with a place that is not one", () => {
    expect(routeDoghouses([{ ...ROUTE, visible: false }])).toEqual([]);
    expect(routeDoghouses([{ ...ROUTE, points: ROUTE.points.slice(0, 2) }])).toEqual([]);
    expect(routeDoghouses([{ ...ROUTE, points: [...ROUTE.points, { id: "x", lat: null, lon: 1 }] }])).toEqual([]);
    expect(routeDoghouses(null)).toEqual([]);
  });

  it("writes the roof, the time and the course as a crew reads them", () => {
    expect(roofName(".CP3")).toBe("CP3");
    expect(roofName("..TWO")).toBe(".TWO");
    expect(roofName(undefined)).toBe("");
    expect(legTime(425)).toEqual({ minutes: "07", seconds: "05" });
    expect(legTime(3725.4)).toEqual({ minutes: "62", seconds: "05" });
    expect(legTime(null)).toEqual({ minutes: "--", seconds: "--" });
    expect(courseText(4.6)).toBe("005");
    expect(courseText(359.7)).toBe("360");
  });
});
