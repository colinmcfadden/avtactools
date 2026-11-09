import { createFlot, flipFlot, flotLabelSpot, flotLengthKm, flotPoints, flotsFromData, savedFlots, scallopRuns, setsWithFlots, SCALLOP_PX } from "./flots";

const line = (...xy) => xy.map(([x, y]) => ({ x, y }));

describe("a FLOT's data", () => {
  it("keeps only lat and lon of what was drawn", () => {
    expect(createFlot([{ lat: 34, lon: -84, extra: 1 }, { lat: 35, lon: -85 }], "f")).toEqual({ id: "f", points: [{ lat: 34, lon: -84 }, { lat: 35, lon: -85 }] });
  });

  it("is flipped with a key that is only there when true", () => {
    const flot = { id: "f", points: [] };
    const flipped = flipFlot(flot);
    expect(flipped.flipped).toBe(true);
    expect(flipFlot(flipped)).toEqual(flot);
    expect(flipFlot(flipped)).not.toHaveProperty("flipped");
  });

  it("leaves out points that are no places", () => {
    expect(flotPoints({ points: [{ lat: 1, lon: 2 }, { lat: "x", lon: 2 }, null] })).toEqual([{ lat: 1, lon: 2 }]);
    expect(flotPoints(undefined)).toEqual([]);
  });

  it("reads only objects with an id from a set's data", () => {
    expect(flotsFromData({ flots: [{ id: "a" }, null, 7, { points: [] }] })).toEqual([{ id: "a" }]);
    expect(flotsFromData({ flots: "no" })).toEqual([]);
    expect(flotsFromData(undefined)).toEqual([]);
  });

  it("saves nothing of where it is filed", () => {
    expect(savedFlots([{ id: "a", setId: "s", points: [] }])).toEqual([{ id: "a", points: [] }]);
    expect(savedFlots(undefined)).toEqual([]);
  });

  it("measures its length along its points", () => {
    expect(flotLengthKm({ points: [{ lat: 34, lon: -84 }, { lat: 34.009, lon: -84 }] })).toBeCloseTo(1, 1);
  });
});

describe("sets with their FLOTs", () => {
  const opened = (id) => String(id).startsWith("opened-");

  it("gives each sketched set its own", () => {
    const sets = setsWithFlots([{ setId: null, routes: [1] }, { setId: "opened-a", routes: [2] }], [{ id: "x" }, { id: "y", setId: "opened-a" }], opened);
    expect(sets.map((set) => set.flots.map((f) => f.id))).toEqual([["x"], ["y"]]);
  });

  it("has a set for FLOTs with no routes, this session's first", () => {
    const sets = setsWithFlots([{ setId: "opened-a", routes: [2] }], [{ id: "x" }], opened);
    expect(sets.map((set) => [set.setId, set.routes.length])).toEqual([[null, 0], ["opened-a", 1]]);
  });

  it("leaves a pack's FLOTs to the pack", () => {
    expect(setsWithFlots([], [{ id: "x", setId: "pack:p:i" }], opened)).toEqual([]);
  });
});

describe("the scallops", () => {
  it("are a whole number along the line, ending where it ends", () => {
    const [run] = scallopRuns(line([0, 0], [100, 0]), { diameter: 20 });
    expect(run[0]).toEqual({ x: 0, y: 0 });
    expect(run[run.length - 1].x).toBeCloseTo(100, 6);
    expect(run[run.length - 1].y).toBeCloseTo(0, 6);
    // Five scallops of eight steps each, after the first point.
    expect(run).toHaveLength(1 + 5 * 8);
  });

  it("are to the right of the direction drawn, and to the left when flipped", () => {
    // Going east on the screen, right is down, which is +y.
    const below = scallopRuns(line([0, 0], [40, 0]), { diameter: 40 })[0];
    expect(Math.max(...below.map((p) => p.y))).toBeCloseTo(20, 6);
    expect(Math.min(...below.map((p) => p.y))).toBeCloseTo(0, 6);
    const above = scallopRuns(line([0, 0], [40, 0]), { diameter: 40, flipped: true })[0];
    expect(Math.min(...above.map((p) => p.y))).toBeCloseTo(-20, 6);
  });

  it("swap sides when the line is drawn the other way", () => {
    const west = scallopRuns(line([40, 0], [0, 0]), { diameter: 40 })[0];
    expect(Math.min(...west.map((p) => p.y))).toBeCloseTo(-20, 6);
  });

  it("follow the line round a corner without a break", () => {
    const runs = scallopRuns(line([0, 0], [50, 0], [50, 50]), { diameter: 10 });
    expect(runs).toHaveLength(1);
    expect(runs[0][runs[0].length - 1].x).toBeCloseTo(50, 6);
    expect(runs[0][runs[0].length - 1].y).toBeCloseTo(50, 6);
  });

  it("are one scallop on a line shorter than one", () => {
    expect(scallopRuns(line([0, 0], [3, 0]))[0]).toHaveLength(9);
    expect(SCALLOP_PX).toBeGreaterThan(3);
  });

  it("are none on a line with no length or no second point", () => {
    expect(scallopRuns(line([5, 5], [5, 5]))).toEqual([]);
    expect(scallopRuns(line([5, 5]))).toEqual([]);
  });

  it("step over a repeated point", () => {
    const [run] = scallopRuns(line([0, 0], [20, 0], [20, 0], [40, 0]), { diameter: 20 });
    expect(run.every((p) => Number.isFinite(p.x) && Number.isFinite(p.y))).toBe(true);
    expect(run[run.length - 1].x).toBeCloseTo(40, 6);
  });

  it("are left out off the view, so a long line stays cheap", () => {
    const within = { minX: 1000, minY: -100, maxX: 1100, maxY: 100 };
    const runs = scallopRuns(line([0, 0], [100000, 0]), { diameter: 20, within });
    expect(runs).toHaveLength(1);
    expect(runs[0].length).toBeLessThan(5 * 8 + 2);
    expect(runs[0][0].x).toBeGreaterThanOrEqual(980);
  });

  it("break into runs where a line leaves the view and comes back", () => {
    const within = { minX: -50, minY: -50, maxX: 50, maxY: 50 };
    const runs = scallopRuns(line([0, 0], [0, 500], [10, 500], [10, 0]), { diameter: 20, within });
    expect(runs).toHaveLength(2);
  });
});

describe("the FLOT's label", () => {
  it("is at the middle, away from the scallops", () => {
    const spot = flotLabelSpot(line([0, 0], [100, 0]));
    expect([spot.x, spot.y]).toEqual([50, 0]);
    expect(spot.awayX + 0).toBeCloseTo(0, 6);
    expect(spot.awayY).toBe(-1);
    expect(flotLabelSpot(line([0, 0], [100, 0]), true).awayY).toBe(1);
  });

  it("has no place on a line with no length", () => {
    expect(flotLabelSpot(line([1, 1], [1, 1]))).toBeNull();
    expect(flotLabelSpot(line([1, 1]))).toBeNull();
  });
});
