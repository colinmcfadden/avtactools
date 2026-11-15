import { filedLine, linesFromData, setsWithLines } from "./setLines";

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
