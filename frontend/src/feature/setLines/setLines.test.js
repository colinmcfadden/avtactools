import { MEASURES, filedLine, isMeasuresSet, isMeasuresSetId, linesFromData, newMeasuresSetId, sessionSets, setsWithLines } from "./setLines";

const opened = (id) => String(id).startsWith("opened-");

describe("a set's lines", () => {
  it("are read from the key of their kind, and no other", () => {
    const data = { flots: [{ id: "f" }], phaseLines: [{ id: "p" }] };
    expect(linesFromData(data, "flots")).toEqual([{ id: "f" }]);
    expect(linesFromData(data, "phaseLines")).toEqual([{ id: "p" }]);
    expect(linesFromData(data, "other")).toEqual([]);
  });

  it("are filed under a set, or under none for this session's own", () => {
    expect(filedLine({ id: "a", setId: "old" }, "new")).toEqual({ id: "a", setId: "new" });
    expect(filedLine({ id: "a", setId: "old" }, null)).toEqual({ id: "a" });
  });
});

describe("sets with their lines", () => {
  const flot = (id, setId) => ({ id, ...(setId ? { setId } : {}), points: [] });
  const pl = (id, setId) => ({ id, name: id.toUpperCase(), ...(setId ? { setId } : {}), points: [] });

  it("gives every set each kind of line, its own only", () => {
    const sets = setsWithLines(
      [{ setId: null, routes: ["r"] }, { setId: "opened-a", routes: [] }],
      { flots: [flot("f1"), flot("f2", "opened-a")], phaseLines: [pl("red", "opened-a")] },
      opened,
    );
    expect(sets).toEqual([
      { setId: null, routes: ["r"], flots: [flot("f1")], phaseLines: [] },
      { setId: "opened-a", routes: [], flots: [flot("f2", "opened-a")], phaseLines: [pl("red", "opened-a")] },
    ]);
  });

  it("makes a set of a phase line with no routes: this session's first, an opened one last, a pack's never", () => {
    const sets = setsWithLines(
      [{ setId: "opened-a", routes: ["r"] }],
      { flots: [], phaseLines: [pl("blue", "opened-b"), pl("red"), pl("dog", "pack:p:i")] },
      opened,
    );
    expect(sets.map((set) => [set.setId, set.phaseLines.map((line) => line.id)])).toEqual([
      [null, ["red"]],
      ["opened-a", []],
      ["opened-b", ["blue"]],
    ]);
  });

  it("makes one set of a FLOT and a phase line that share one with no routes", () => {
    const sets = setsWithLines([], { flots: [flot("f1")], phaseLines: [pl("red")] }, opened);
    expect(sets).toEqual([{ setId: null, routes: [], flots: [flot("f1")], phaseLines: [pl("red")] }]);
  });
});

describe("control measures: lines in a set of their own, with no routes", () => {
  const flot = (id, setId) => ({ id, ...(setId ? { setId } : {}), points: [] });
  const pl = (id, setId) => ({ id, name: id.toUpperCase(), ...(setId ? { setId } : {}), points: [] });

  it("have ids no route set has: this session's, and one for each set opened from the Library", () => {
    expect(isMeasuresSetId(MEASURES)).toBe(true);
    const opened = newMeasuresSetId();
    expect(isMeasuresSetId(opened)).toBe(true);
    expect(opened).not.toBe(newMeasuresSetId());
    [null, undefined, "", "opened-1", "pack:p:rt-1", "sketches", "measuresque"].forEach((id) => expect(isMeasuresSetId(id)).toBe(false));
  });

  it("are a set that holds lines and no route, or one made for them whose lines have gone", () => {
    expect(isMeasuresSet({ routes: [], flots: [flot("f")] })).toBe(true);
    expect(isMeasuresSet({ routes: [], phaseLines: [pl("red")] })).toBe(true);
    // A route set with a line saved inside it is a route set; so is one with nothing in it.
    expect(isMeasuresSet({ routes: ["r"], flots: [flot("f")] })).toBe(false);
    expect(isMeasuresSet({ routes: [], flots: [], phaseLines: [] })).toBe(false);
    expect(isMeasuresSet({ routes: [] }, true)).toBe(true);
    expect(isMeasuresSet({ routes: ["r"] }, true)).toBe(false);
  });

  it("stand beside the session's route sets, never in one: a line drawn with routes here joins none of them", () => {
    const sets = sessionSets(
      [{ setId: null, routes: ["r1"] }, { setId: "opened-a", routes: ["r2"] }],
      { flots: [flot("f1", MEASURES)], phaseLines: [pl("red", MEASURES), pl("blue", "measures-9")] },
      opened,
    );
    expect(sets).toEqual([
      { setId: null, routes: ["r1"], flots: [], phaseLines: [] },
      { setId: "opened-a", routes: ["r2"], flots: [], phaseLines: [] },
      { setId: MEASURES, routes: [], flots: [flot("f1", MEASURES)], phaseLines: [pl("red", MEASURES)], measures: true },
      { setId: "measures-9", routes: [], flots: [], phaseLines: [pl("blue", "measures-9")], measures: true },
    ]);
  });

  it("need no route at all, and are not there once their last line has gone", () => {
    expect(sessionSets([], { flots: [flot("f1", MEASURES)], phaseLines: [] }, opened)).toEqual([
      { setId: MEASURES, routes: [], flots: [flot("f1", MEASURES)], phaseLines: [], measures: true },
    ]);
    expect(sessionSets([], { flots: [], phaseLines: [] }, opened)).toEqual([]);
  });

  it("saved inside a route set, from before, are still that set's: it is a route set while it has a route", () => {
    const lists = { flots: [flot("old")], phaseLines: [] };
    expect(sessionSets([{ setId: null, routes: ["r"] }], lists, opened)).toEqual([{ setId: null, routes: ["r"], flots: [flot("old")], phaseLines: [] }]);
    // Its last route gone, what is left of it is control measures.
    expect(sessionSets([], lists, opened)).toEqual([{ setId: null, routes: [], flots: [flot("old")], phaseLines: [], measures: true }]);
  });
});
