import { mgrsToLatLon, parseMgrs, toMgrs, toUtmInZone } from "../utils/mgrs";
import { readFixture } from "./readFixture";

// contracts/fixtures/mgrs/inverse.json is PyGeodesy's parseMGRS(grid).toLatLon(), what
// /api/convert-grid answers: the centre of the square a grid names. The Android app's
// MgrsConverter is held to the same file; the browser's inverse draws the map's MGRS grid.
const inverse = readFixture("mgrs/inverse.json");
const forward = readFixture("mgrs/forward.json");

// Metres between two nearby points, near enough to judge a square's half-diagonal.
const metresApart = (a, b) => {
  const dLat = (a.lat - b.lat) * 111320;
  const dLon = (((a.lon - b.lon + 540) % 360) - 180) * 111320 * Math.cos((a.lat * Math.PI) / 180);
  return Math.hypot(dLat, dLon);
};

describe("MGRS inverse fixtures (PyGeodesy)", () => {
  it("has the cases the native apps are held to, in the columns this test reads", () => {
    expect(inverse.columns).toEqual(["mgrs", "lat", "lon"]);
    expect(inverse.cases.length).toBeGreaterThan(3000);
  });

  it("matches PyGeodesy at every grid, within the fixture's tolerance", () => {
    const tolerance = inverse.toleranceDeg;
    expect(tolerance).toBeGreaterThan(0);
    const misses = [];
    inverse.cases.forEach(([grid, lat, lon]) => {
      const actual = mgrsToLatLon(grid);
      if (!actual) misses.push(`${grid}: no answer`);
      else if (Math.abs(actual.lat - lat) > tolerance || Math.abs(actual.lon - lon) > tolerance) {
        misses.push(`${grid}: ${actual.lat}, ${actual.lon} (PyGeodesy ${lat}, ${lon})`);
      }
    });
    expect(misses.slice(0, 10)).toEqual([]);
    expect(misses).toHaveLength(0);
  });

  it.each(inverse.invalid)("has no answer for %j, which the server refuses", (grid) => {
    expect(mgrsToLatLon(grid)).toBeNull();
  });
});

describe("forward then inverse, over the forward fixture's points", () => {
  it("brings every point back to the centre of the 1 m square it was read in", () => {
    const misses = [];
    forward.cases.forEach(([lat, lon, grid]) => {
      const back = mgrsToLatLon(grid);
      const parts = parseMgrs(grid);
      if (!back || !parts) {
        misses.push(`${grid}: no answer`);
        return;
      }
      // No further from the point than the square's half-diagonal (0.71 m).
      if (metresApart(back, { lat, lon }) > 1) misses.push(`${grid}: ${metresApart(back, { lat, lon })} m away`);
      // A square read at exactly 80°S straddles the edge of the UTM range, and its
      // centre can fall just outside it, where nothing projects.
      if (back.lat < -80) {
        if (lat !== -80) misses.push(`${grid}: centre at ${back.lat}`);
        return;
      }
      // Projected into the grid's own zone, the centre is half a metre in from the
      // square's corner on both axes, to well under a millimetre.
      const utm = toUtmInZone(back.lat, back.lon, parts.zone);
      const easting = utm.easting % 100000;
      const northing = utm.northing % 100000;
      if (
        Math.abs(easting - (Number(parts.easting) + 0.5)) > 1e-6
        || Math.abs(northing - (Number(parts.northing) + 0.5)) > 1e-6
      ) {
        misses.push(`${grid}: centre at ${easting}, ${northing}`);
      }
    });
    expect(misses.slice(0, 10)).toEqual([]);
  });

  it("reads the centre back as the same grid, except within a metre of a zone or band edge", () => {
    const differ = [];
    forward.cases.forEach(([lat, lon, grid]) => {
      const back = mgrsToLatLon(grid);
      if (back.lat < -80) return; // beyond 80°S: see above
      const again = toMgrs(back.lat, back.lon);
      if (again === grid) return;
      // The centre is half a metre east and north of the corner, so a point that close
      // to an edge can come back across it. Every such case must be at an edge.
      // Probed 3 m away each way: further than the half metre, near enough to be an edge.
      const step = 3 / 111320;
      const across = step / Math.cos((back.lat * Math.PI) / 180);
      const atEdge = [[0, across], [step, 0], [-step, 0], [0, -across]].some(
        ([dLat, dLon]) => toMgrs(back.lat + dLat, back.lon + dLon)?.slice(0, 3) !== again.slice(0, 3),
      );
      if (!atEdge) differ.push(`${grid}: ${again}`);
    });
    expect(differ).toEqual([]);
  });
});
