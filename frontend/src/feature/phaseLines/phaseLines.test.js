import {
  PHASE_LINE_NAME_MAX,
  cleanPhaseLineName,
  createPhaseLine,
  labelShift,
  nextPhaseLineName,
  phaseLineEnds,
  phaseLineLabel,
  phaseLineLengthKm,
  phaseLinePoints,
  phaseLinesFromData,
  renamePhaseLine,
  savedPhaseLines,
} from "./phaseLines";

const line = (...xy) => xy.map(([x, y]) => ({ x, y }));

describe("a phase line's name", () => {
  it("is kept in capitals, trimmed, with single spaces", () => {
    expect(cleanPhaseLineName("  red   dog ")).toBe("RED DOG");
  });

  it("loses a PL typed in front of it, since the label says PL itself", () => {
    expect(cleanPhaseLineName("pl red")).toBe("RED");
    expect(cleanPhaseLineName("PL")).toBe("");
    // A name that only begins with those letters is a name.
    expect(cleanPhaseLineName("plum")).toBe("PLUM");
  });

  it("is cut by characters, never through one", () => {
    const long = `${"A".repeat(PHASE_LINE_NAME_MAX - 1)}🚁B`;
    expect([...cleanPhaseLineName(long)]).toHaveLength(PHASE_LINE_NAME_MAX);
    expect(cleanPhaseLineName(long).endsWith("🚁")).toBe(true);
  });

  it("is nothing for nothing", () => {
    expect(cleanPhaseLineName(undefined)).toBe("");
    expect(cleanPhaseLineName("   ")).toBe("");
  });

  it("is written after PL at each end, and a line with no name is PL alone", () => {
    expect(phaseLineLabel({ name: "RED" })).toBe("PL RED");
    expect(phaseLineLabel({ name: "  " })).toBe("PL");
    expect(phaseLineLabel({ name: 7 })).toBe("PL");
    expect(phaseLineLabel(undefined)).toBe("PL");
  });

  it("is offered as the first phonetic word no line here has, then a number", () => {
    expect(nextPhaseLineName([])).toBe("ALPHA");
    expect(nextPhaseLineName(undefined)).toBe("ALPHA");
    expect(nextPhaseLineName([{ name: "ALPHA" }, { name: "charlie" }])).toBe("BRAVO");
    const all = ["ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU"].map((name) => ({ name }));
    expect(nextPhaseLineName(all)).toBe("27");
    expect(nextPhaseLineName([...all, { name: "27" }])).toBe("28");
  });
});

describe("a phase line's data", () => {
  it("keeps its name and only lat and lon of what was drawn", () => {
    expect(createPhaseLine([{ lat: 34, lon: -84, extra: 1 }, { lat: 35, lon: -85 }], "pl-1", "pl red")).toEqual({
      id: "pl-1",
      name: "RED",
      points: [{ lat: 34, lon: -84 }, { lat: 35, lon: -85 }],
    });
  });

  it("is renamed, and keeps its name when the new one has nothing in it", () => {
    const red = { id: "pl-1", name: "RED", points: [] };
    expect(renamePhaseLine(red, "blue")).toEqual({ id: "pl-1", name: "BLUE", points: [] });
    expect(renamePhaseLine(red, "  ")).toBe(red);
    expect(renamePhaseLine(red, "PL")).toBe(red);
  });

  it("leaves out points that are no places", () => {
    expect(phaseLinePoints({ points: [{ lat: 1, lon: 2 }, { lat: "x", lon: 2 }, null] })).toEqual([{ lat: 1, lon: 2 }]);
    expect(phaseLinePoints(undefined)).toEqual([]);
  });

  it("reads only objects with an id from a set's data, under its own key", () => {
    expect(phaseLinesFromData({ phaseLines: [{ id: "a" }, null, 7, { points: [] }], flots: [{ id: "f" }] })).toEqual([{ id: "a" }]);
    expect(phaseLinesFromData({ phaseLines: "no" })).toEqual([]);
    expect(phaseLinesFromData(undefined)).toEqual([]);
  });

  it("saves nothing of where it is filed", () => {
    expect(savedPhaseLines([{ id: "a", name: "RED", setId: "s", points: [] }])).toEqual([{ id: "a", name: "RED", points: [] }]);
    expect(savedPhaseLines(undefined)).toEqual([]);
  });

  it("measures its length along its points", () => {
    expect(phaseLineLengthKm({ points: [{ lat: 34, lon: -84 }, { lat: 34.009, lon: -84 }] })).toBeCloseTo(1, 1);
  });
});

describe("where a phase line's labels go", () => {
  it("is each end, facing on out of the line", () => {
    const [first, last] = phaseLineEnds(line([0, 0], [100, 0]));
    expect(first).toEqual({ x: 0, y: 0, ux: -1, uy: 0 });
    expect(last).toEqual({ x: 100, y: 0, ux: 1, uy: 0 });
  });

  it("follows the leg each end is on, round a corner", () => {
    const [first, last] = phaseLineEnds(line([0, 0], [100, 0], [100, 50]));
    expect([first.ux, first.uy]).toEqual([-1, 0]);
    expect([last.ux, last.uy]).toEqual([0, 1]);
  });

  it("looks past points that sit on the end", () => {
    const [first, last] = phaseLineEnds(line([0, 0], [0, 0], [0, -40], [0, -40]));
    expect([first.ux, first.uy]).toEqual([0, 1]);
    expect([last.ux, last.uy]).toEqual([0, -1]);
  });

  it("is nowhere for a line with no length or one point", () => {
    expect(phaseLineEnds(line([5, 5], [5, 5]))).toEqual([]);
    expect(phaseLineEnds(line([5, 5]))).toEqual([]);
    expect(phaseLineEnds([])).toEqual([]);
  });

  it("puts the end on the label's edge: a whole half of it along the greater direction", () => {
    expect(labelShift({ ux: 1, uy: 0 })).toEqual({ fx: 1, fy: 0 });
    expect(labelShift({ ux: 0, uy: -1 })).toEqual({ fx: 0, fy: -1 });
    const diagonal = labelShift({ ux: Math.SQRT1_2, uy: -Math.SQRT1_2 });
    expect(diagonal.fx).toBeCloseTo(1);
    expect(diagonal.fy).toBeCloseTo(-1);
    const shallow = labelShift({ ux: -0.8, uy: 0.6 });
    expect(shallow.fx).toBeCloseTo(-1);
    expect(shallow.fy).toBeCloseTo(0.75);
    expect(labelShift({ ux: 0, uy: 0 })).toEqual({ fx: 0, fy: 0 });
  });
});
