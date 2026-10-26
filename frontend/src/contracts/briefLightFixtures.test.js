import { lightData, localMidnight, moonIllumination } from "../feature/brief/light";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/light.json is Skyfield over JPL DE421 (contracts/scripts/light_fixtures.py): an
// independent reference for the brief's light data. Android's core-brief is held to the same file.
const fixture = readFixture("brief/light.json");
const SUN = ["bmnt", "sunrise", "sunset", "eent"];
const MOON = ["moonrise", "moonset"];

const misses = (c) => {
  const actual = lightData({
    lat: c.lat, lon: c.lon, date: c.date, utcOffsetMinutes: c.utcOffsetMinutes, at: Date.parse(c.at),
  });
  const out = [];
  [...SUN, ...MOON].forEach((key) => {
    const want = c.expected[key];
    const got = actual[key];
    const tolerance = (SUN.includes(key) ? fixture.tolerance.sunSeconds : fixture.tolerance.moonSeconds) * 1000;
    if (want === null || got === null) {
      if (want !== got) out.push(`${c.name}: ${key} ${got === null ? "none" : new Date(got).toISOString()}, Skyfield ${want ?? "none"}`);
    } else if (Math.abs(got - Date.parse(want)) > tolerance) {
      out.push(`${c.name}: ${key} ${new Date(got).toISOString()}, Skyfield ${want}`);
    }
  });
  if (Math.abs(actual.illumination - c.expected.illumination) > fixture.tolerance.illumination) {
    out.push(`${c.name}: illumination ${actual.illumination.toFixed(4)}, Skyfield ${c.expected.illumination}`);
  }
  return out;
};

describe("brief light data against Skyfield", () => {
  it("has the cases the native apps are held to, with a missing event among them", () => {
    expect(fixture.cases.length).toBeGreaterThanOrEqual(20);
    const nulls = fixture.cases.flatMap((c) => Object.values(c.expected).filter((v) => v === null));
    expect(nulls.length).toBeGreaterThan(5);
  });

  it("agrees at every case within the fixture's tolerance", () => {
    expect(fixture.cases.flatMap(misses)).toEqual([]);
  });

  it("puts every event inside the local day it was asked for", () => {
    const outside = [];
    fixture.cases.forEach((c) => {
      const start = localMidnight(c.date, c.utcOffsetMinutes);
      const actual = lightData({ lat: c.lat, lon: c.lon, date: c.date, utcOffsetMinutes: c.utcOffsetMinutes });
      [...SUN, ...MOON].forEach((key) => {
        const t = actual[key];
        if (t !== null && (t < start || t >= start + 86400000)) outside.push(`${c.name}: ${key}`);
      });
    });
    expect(outside).toEqual([]);
  });

  it("answers nothing for a date or place it cannot read, rather than guess", () => {
    expect(lightData({ lat: 34, lon: -84, date: "16 Oct 2026", utcOffsetMinutes: -240 })).toBeNull();
    expect(lightData({ lat: 95, lon: -84, date: "2026-10-16", utcOffsetMinutes: -240 })).toBeNull();
    expect(lightData({ lat: Number.NaN, lon: -84, date: "2026-10-16", utcOffsetMinutes: -240 })).toBeNull();
    expect(lightData({ lat: 34, lon: -84, date: "2026-10-16", utcOffsetMinutes: Number.NaN })).toBeNull();
  });

  it("takes illumination at local noon when no instant is given", () => {
    const c = fixture.cases[0];
    const noon = localMidnight(c.date, c.utcOffsetMinutes) + 43200000;
    expect(lightData({ lat: c.lat, lon: c.lon, date: c.date, utcOffsetMinutes: c.utcOffsetMinutes }).illumination)
      .toBe(moonIllumination(noon));
  });
});
