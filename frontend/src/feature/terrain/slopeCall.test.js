import { UH60_SLOPE_LIMITS, landingHeadingOf, slopeCallFor, slopeLimitsFor, slopeWindOf } from "./slopeCall";

// An LZ of [deg] rising toward [toward], as /api/terrain-analysis measures it: at heading h the ground ahead rises
// atan(tan deg · cos(h - toward)) (nose up; falling is nose down) and across it atan(tan deg · sin(h - toward)).
const RAD = Math.PI / 180;
const tenth = (v) => Math.round(v * 10) / 10 + 0;
const plane = (deg, toward) => ({
  stats: { maxDeg: deg },
  byHeading: Array.from({ length: 360 }, (_, heading) => {
    const rise = Math.tan(deg * RAD);
    const along = Math.atan(rise * Math.cos((heading - toward) * RAD)) / RAD;
    return {
      heading,
      noseHighMaxDeg: tenth(Math.max(along, 0)),
      noseLowMaxDeg: tenth(Math.max(-along, 0)),
      crossSlopeMaxDeg: tenth(Math.abs(Math.atan(rise * Math.sin((heading - toward) * RAD)) / RAD)),
    };
  }),
});
const EAST8 = plane(8, 90);

describe("the UH-60's slope limits", () => {
  it("are 15° nose up and 6° nose down, not the other way round", () => {
    expect(UH60_SLOPE_LIMITS).toEqual({ noseUp: 15, noseDown: 6, cross: 15 });
    expect(slopeCallFor(EAST8, { landingHdg: "090°" }).label).toBe("LANDING");          // facing up an 8° slope
    expect(slopeCallFor(EAST8, { landingHdg: "270°" }).label).toBe("LIMIT EXCEEDED");   // facing down it
  });

  it("lose 2° for each full 5 knots of wind, and nose down goes above 15 knots", () => {
    expect(slopeLimitsFor({ speedKts: 14, fromDeg: 90, variable: false })).toMatchObject({ noseUp: 11, noseDown: 2, cross: 11 });
    expect(slopeLimitsFor({ speedKts: 16, fromDeg: 90, variable: false })).toMatchObject({ noseUp: 9, noseDown: 0, cross: 9, noseDownProhibited: true });
    expect(slopeLimitsFor(null)).toMatchObject(UH60_SLOPE_LIMITS);
  });
});

describe("the wind of a weather report", () => {
  const report = (fields) => ({ station_id: "KRYY", wind_spd_kts: 12, wind_dir: 270, wind_gust_kts: null, ...fields });

  it("is the steady wind: a gust is not counted", () => {
    expect(slopeWindOf(report({ wind_gust_kts: 28 }))).toEqual({ speedKts: 12, fromDeg: 270, variable: false });
  });

  it("from 360° is from the north, 000°", () => {
    expect(slopeWindOf(report({ wind_dir: 360 })).fromDeg).toBe(0);
  });

  it("VRB is variable and has no direction to land into", () => {
    expect(slopeWindOf(report({ wind_spd_kts: 5, wind_dir: "VRB" }))).toEqual({ speedKts: 5, fromDeg: null, variable: true });
  });

  it("a calm is a wind of 0, not no report", () => {
    expect(slopeWindOf(report({ wind_spd_kts: 0, wind_dir: 0 }))).toEqual({ speedKts: 0, fromDeg: 0, variable: false });
  });

  it("is no report when no station answered, or the speed is missing or not a number", () => {
    expect(slopeWindOf(report({ station_id: "TIMEOUT", wind_spd_kts: null, wind_dir: null }))).toBeNull();
    expect(slopeWindOf(report({ wind_spd_kts: undefined }))).toBeNull();
    expect(slopeWindOf(report({ wind_spd_kts: "12" }))).toBeNull();
    expect(slopeWindOf(report({ wind_spd_kts: -3 }))).toBeNull();
    expect(slopeWindOf(null)).toBeNull();
  });

  it("is a speed with no direction when the direction is neither a number nor VRB", () => {
    expect(slopeWindOf(report({ wind_dir: null }))).toEqual({ speedKts: 12, fromDeg: null, variable: false });
  });
});

describe("the landing heading", () => {
  it("is read as the doghouse box reads it, but an unreadable one is none, not north", () => {
    expect(landingHeadingOf("270°")).toBe(270);
    expect(landingHeadingOf("-90°")).toBe(270);
    expect(landingHeadingOf("000°")).toBe(0);
    expect(landingHeadingOf("RWY")).toBeNull();
    expect(landingHeadingOf(undefined)).toBeNull();
  });
});

describe("the call", () => {
  it("judges the landing heading again when it turns, with no new request", () => {
    // Nothing here asks the server: the table was measured once, for every heading.
    const calls = ["270°", "250°", "225°", "090°"].map((landingHdg) => slopeCallFor(EAST8, { landingHdg }).label);
    expect(calls).toEqual(["LIMIT EXCEEDED", "LIMIT EXCEEDED", "LANDING", "LANDING"]);
  });

  it("says under the slope what was judged at the landing heading and what the wind did to the limits", () => {
    const call = slopeCallFor(EAST8, { landingHdg: "270°", wind: { speedKts: 12, fromDeg: 270, variable: false } });
    expect(call.lines).toEqual(["Landing 270°: nose down 8.0°, limit 2°", "Wind 12 kt: limits −4°"]);
    expect(slopeCallFor(EAST8, { landingHdg: "090°" }).lines[0]).toBe("Landing 090°: within limits");
  });

  it("judges nothing by heading with no landing heading", () => {
    expect(slopeCallFor(EAST8, {})).toMatchObject({ label: "LANDING", heading: null, lines: ["No landing heading: limits not checked"] });
    expect(slopeCallFor(plane(16, 90), { landingHdg: "RWY" }).label).toBe("HEADING REQUIRED");
  });

  it("asks for a heading only of an older server, which sends no table", () => {
    expect(slopeCallFor({ stats: { maxDeg: 16 } }, { landingHdg: "090°" })).toMatchObject({
      label: "HEADING REQUIRED", lines: ["Heading limits not checked (server not updated)"],
    });
    expect(slopeCallFor(plane(16, 90), { landingHdg: "090°" }).label).toBe("LIMIT EXCEEDED");   // 16° nose up is over 15°
  });
});
