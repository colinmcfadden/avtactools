import { squaresAlong, squaresBeside, stripCorners, stripGrid, withStripGrids } from "../feature/brief/stripGrid";
import { CASES, compileCase } from "./briefCases";
import { UPDATE, compact, writeFixture } from "./fixtureIO";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/strip-grid.json: the MGRS 1 km grid on each strip chart of the briefs in brief/compile.json,
// as the web adds it when the PDF is asked for (feature/brief/stripGrid.js, over mgrsGrid.js). Android's PDF (docs/
// MISSION_BRIEF.md, B8) reads each strip from compile.json and is held to every case, within the tolerances
// contracts/fixtures/mgrs/grid.json is compared with (a line sampled with other points, an end a millimetre off).
// Regenerate with
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/brief
// and review the diff.

const FIXTURE = "brief/strip-grid.json";
// Every brief with strip charts but the one that makes the most a packet holds: its 60 strips' grids would be most of
// the file and pin nothing the others do not.
const COMPILED = CASES.map((c) => ({ name: c.name, model: compileCase(c) }))
  .filter((c) => c.model.strips.length > 0 && c.model.strips.length < 60);
const gridCase = (c) => ({
  name: c.name,
  strips: c.model.strips.map((strip) => ({ id: strip.id, corners: stripCorners(strip).map(([lat, lon]) => [Math.round(lat * 1e7) / 1e7, Math.round(lon * 1e7) / 1e7]), grid: stripGrid(strip) })),
});
const GRIDS = COMPILED.map(gridCase);

describe("brief/strip-grid.json", () => {
  if (UPDATE) {
    it("writes the fixture", () => {
      writeFixture(FIXTURE, `${compact({ generatedBy: "frontend/src/contracts/briefStripGridFixtures.test.js (UPDATE_CONTRACTS=1)", from: "brief/compile.json", cases: GRIDS })}\n`);
    });
    return;
  }

  const fixture = readFixture(FIXTURE);

  it("holds a case for every brief with strip charts", () => {
    expect(fixture.cases.map((c) => c.name)).toEqual(COMPILED.map((c) => c.name));
  });

  it.each(GRIDS.map((c, i) => [c.name, i]))("%s", (_name, i) => {
    expect(fixture.cases[i]).toEqual(JSON.parse(JSON.stringify(GRIDS[i])));
  });
});

describe("the strip charts' grid", () => {
  const amb = COMPILED[0].model;
  const first = amb.strips[0];

  it("draws every 1 km line over a strip, with its principal digits", () => {
    const { lines } = stripGrid(first);
    const eastings = lines.filter((l) => l.axis === "easting");
    const northings = lines.filter((l) => l.axis === "northing");
    // A 21.5 by 5.5 km strip turned 31°: about 20 eastings and 20 northings cross the box round it.
    expect(eastings.length).toBeGreaterThan(10);
    expect(northings.length).toBeGreaterThan(10);
    lines.filter((l) => l.kind !== "zone").forEach((l) => {
      expect(l.text).toMatch(/^\d\d$/);
      expect(Number(l.text)).toBe(Math.floor((((l.value % 100000) + 100000) % 100000) / 1000));
      expect(l.points.length).toBeGreaterThanOrEqual(2);
    });
  });

  it("names the 100 km squares the route crosses, in order", () => {
    // 3MILE is in GC; the route passes into GD between TRACK and S-EP (docs: 16S GC 29619 55520, GD 53881 01130).
    const all = amb.strips.filter((s) => s.ref === "route:set-opdk/r-alderaan").flatMap((s) => squaresAlong(s.path));
    expect(all[0]).toBe("16S GC");
    expect(all).toContain("16S GD");
    expect(squaresAlong([])).toEqual([]);
  });

  it("names every other square whose digits the strip prints, as where it reaches into zone 17 beside BLUEBIRD", () => {
    const fifth = amb.strips.find((s) => s.id === "route:set-opdk/r-alderaan#5");
    const grid = stripGrid(fifth);
    expect(grid.squares).toEqual(["16S GD"]);
    expect(grid.others.some((q) => q.startsWith("17S"))).toBe(true);
    expect(grid.lines.some((l) => l.zone === 17)).toBe(true);
    expect(squaresBeside(first, squaresAlong(first.path))).toEqual([]);
  });

  it("puts the corners round the strip's centre, its length up its course", () => {
    const [bl, tl, tr, br] = stripCorners(first);
    const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
    const centre = mid(mid(bl, tr), mid(tl, br));
    // Within a few metres (the plane is fitted to each point's own latitude).
    expect(Math.abs(centre[0] - first.centre[0]) * 111320).toBeLessThan(5);
    expect(Math.abs(centre[1] - first.centre[1]) * 111320 * Math.cos((first.centre[0] * Math.PI) / 180)).toBeLessThan(5);
    // The top lies ahead, up the course (031°: north and east of the bottom).
    const top = mid(tl, tr);
    const bottom = mid(bl, br);
    expect(top[0]).toBeGreaterThan(bottom[0]);
    expect(top[1]).toBeGreaterThan(bottom[1]);
  });

  it("is added only to a model with strips, changing nothing else", () => {
    const withGrids = withStripGrids(amb);
    expect(withGrids.strips.every((s) => Array.isArray(s.grid.lines) && Array.isArray(s.grid.squares) && Array.isArray(s.grid.others))).toBe(true);
    expect({ ...withGrids, strips: withGrids.strips.map(({ grid, ...s }) => s) }).toEqual(amb);
    const none = { ...amb, strips: [] };
    expect(withStripGrids(none)).toBe(none);
  });
});
