import { toMgrs } from "../utils/mgrs";
import { readFixture } from "./readFixture";

// contracts/fixtures/mgrs/forward.json is computed by PyGeodesy, the library
// behind /api/convert-to-mgrs. Every case here is a point where the browser's
// own conversion has to give the server's answer to the metre. (The inverse,
// grid to lat/lon, is held to inverse.json in mgrsInverseFixtures.test.js.)
const forward = readFixture("mgrs/forward.json");

describe("MGRS forward fixtures (PyGeodesy)", () => {
  it("has the cases the native apps are held to", () => {
    expect(forward.cases.length).toBeGreaterThan(5000);
  });

  it("matches PyGeodesy at every point", () => {
    const misses = [];
    forward.cases.forEach(([lat, lon, expected]) => {
      const actual = toMgrs(lat, lon);
      if (actual !== expected) misses.push(`${lat}, ${lon}: ${actual} (PyGeodesy ${expected})`);
    });
    expect(misses.slice(0, 10)).toEqual([]);
  });

  it.each(forward.named.map((n) => [n.name, n.lat, n.lon, n.mgrs]))(
    "matches PyGeodesy for %s",
    (_name, lat, lon, expected) => {
      expect(toMgrs(lat, lon)).toBe(expected);
    },
  );

  it("truncates coarser grids from the 1 m grid", () => {
    forward.cases.slice(0, 400).forEach(([lat, lon, grid]) => {
      const [zoneBand, square, e, n] = grid.split(" ");
      expect(toMgrs(lat, lon, { digits: 4 })).toBe(
        `${zoneBand} ${square} ${e.slice(0, 4)} ${n.slice(0, 4)}`,
      );
    });
  });

  it.each(forward.noAnswer)("has no answer at %f, %f (polar cap)", (lat, lon) => {
    expect(toMgrs(lat, lon)).toBeNull();
  });
});
