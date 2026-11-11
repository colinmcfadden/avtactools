import {
  DRAWING_ALREADY,
  FINISH_BOUNDARY_FIRST,
  RAIL,
  STAGE,
  cardTools,
  frameHidden,
  frameTap,
  railCapacity,
  railFit,
  railRows,
  railStage,
  railTools,
  undoShown,
} from "./phoneFrame";

const keys = (tools) => tools.map((tool) => tool.key);
const byKey = (tools, key) => tools.find((tool) => tool.key === key);

describe("how far along the LZ/PZ is", () => {
  it("goes from nothing, to a target, to analysed, with drawing its own step", () => {
    expect(railStage()).toBe(STAGE.NONE);
    expect(railStage({ hasTarget: true })).toBe(STAGE.TARGETED);
    expect(railStage({ hasTarget: true, drawingBoundary: true })).toBe(STAGE.DRAWING);
    expect(railStage({ hasTarget: true, analyzed: true })).toBe(STAGE.ANALYZED);
  });
});

// The owner, 2026-11-11: the tools appear in the order the app is used, not all at once.
describe("the rail's tools, in the order the app is used", () => {
  it("offers only the route tool and Set Target with no target and no LZ/PZ active", () => {
    const tools = railTools();
    expect(keys(tools)).toEqual(["route", "target"]);
    expect(byKey(tools, "target")).toMatchObject({ label: "Set Target", enabled: true, wide: true, tone: "primary" });
    expect(byKey(tools, "target").hint).toMatch(/crosshair/);
    expect(byKey(tools, "route")).toMatchObject({ label: "Sketch route", enabled: true, pressed: false });
  });

  it("adds an active Analyze and Draw LZ once a target is set, Analyze the next step", () => {
    const tools = railTools({ hasTarget: true });
    expect(keys(tools)).toEqual(["route", "target", "analyze", "draw"]);
    expect(byKey(tools, "analyze")).toMatchObject({ label: "Analyze", enabled: true, wide: true, tone: "primary" });
    expect(byKey(tools, "draw")).toMatchObject({ label: "Draw LZ", enabled: true, pressed: false, wide: true });
    // Set Target is no longer the next step; it starts another LZ/PZ.
    expect(byKey(tools, "target").tone).toBeUndefined();
    expect(byKey(tools, "target").hint).toMatch(/new LZ\/PZ/);
  });

  it("greys Analyze out while the boundary is drawn, until Finish LZ", () => {
    const tools = railTools({ hasTarget: true, drawingBoundary: true });
    expect(keys(tools)).toEqual(["route", "target", "analyze", "draw"]);
    expect(byKey(tools, "analyze")).toMatchObject({ enabled: false, hint: FINISH_BOUNDARY_FIRST });
    expect(byKey(tools, "draw")).toMatchObject({ label: "Finish LZ", enabled: true, pressed: true, tone: "drawing" });
    // A new target would drop the boundary half drawn, and a route's taps would be its corners.
    expect(byKey(tools, "target")).toMatchObject({ enabled: false, hint: DRAWING_ALREADY });
    expect(byKey(tools, "route")).toMatchObject({ enabled: false, hint: DRAWING_ALREADY });

    const finished = railTools({ hasTarget: true });
    expect(byKey(finished, "analyze")).toMatchObject({ enabled: true, tone: "primary" });
  });

  it("adds the planning tools once the LZ/PZ is analysed, and not before", () => {
    const planning = ["helo", "pz", "sector", "backstop", "unit", "goAround"];
    expect(keys(railTools({ hasTarget: true })).filter((key) => planning.includes(key))).toEqual([]);
    const tools = railTools({ hasTarget: true, analyzed: true });
    expect(keys(tools)).toEqual(["route", "target", "analyze", "draw", ...planning]);
    expect(tools.every((tool) => tool.enabled)).toBe(true);
    expect(byKey(tools, "analyze").label).toBe("Re-analyze");
    // The steps behind the person are icons now, beside the tools they opened.
    expect(tools.some((tool) => tool.wide || tool.tone)).toBe(false);
  });

  it("never gates the route tool on the LZ/PZ", () => {
    for (const state of [{}, { hasTarget: true }, { hasTarget: true, analyzed: true }]) {
      expect(byKey(railTools(state), "route").enabled).toBe(true);
    }
  });

  it("lets one thing be drawn on the map at a time", () => {
    const sketching = railTools({ hasTarget: true, analyzed: true, sketchingRoute: true });
    expect(byKey(sketching, "route")).toMatchObject({ label: "End route", enabled: true, pressed: true });
    expect(byKey(sketching, "draw")).toMatchObject({ enabled: false, hint: DRAWING_ALREADY });
    expect(byKey(sketching, "backstop")).toMatchObject({ enabled: false, hint: DRAWING_ALREADY });
    expect(byKey(sketching, "target").enabled).toBe(false);
    // Analysing and placing draw nothing with taps: they stay.
    expect(byKey(sketching, "analyze").enabled).toBe(true);
    expect(byKey(sketching, "helo").enabled).toBe(true);

    const backstop = railTools({ hasTarget: true, analyzed: true, drawingBackstop: true });
    expect(byKey(backstop, "backstop")).toMatchObject({ label: "Finish backstop", enabled: true, pressed: true });
    expect(byKey(backstop, "route").enabled).toBe(false);
    expect(byKey(backstop, "draw").enabled).toBe(false);

    const flot = railTools({ hasTarget: true, drawingFlot: true });
    expect(byKey(flot, "route").enabled).toBe(false);
    expect(byKey(flot, "draw").enabled).toBe(false);
    expect(byKey(flot, "analyze").enabled).toBe(true);
  });

  it("offers nothing where nothing may be changed (a finished pack, a viewer)", () => {
    expect(railTools({ hasTarget: true, analyzed: true, editable: false })).toEqual([]);
  });

  it("follows the account's features", () => {
    expect(keys(railTools({ features: { routes: false } }))).toEqual(["target"]);
    expect(keys(railTools({ features: { lz_pz_tools: false }, hasTarget: true, analyzed: true }))).toEqual(["route"]);
    expect(railTools({ features: { routes: false, lz_pz_tools: false } })).toEqual([]);
  });

  it("gives every tool a name and a sentence, on or off", () => {
    const states = [{}, { hasTarget: true }, { hasTarget: true, drawingBoundary: true }, { hasTarget: true, analyzed: true, sketchingRoute: true }];
    for (const state of states) {
      for (const tool of railTools(state)) {
        expect([tool.key, Boolean(tool.label), Boolean(tool.hint)]).toEqual([tool.key, true, true]);
      }
    }
  });
});

describe("the rail's rows", () => {
  it("gives a step said in words a row to itself", () => {
    const rows = railRows(railTools({ hasTarget: true }));
    expect(rows.map(keys)).toEqual([["route"], ["target"], ["analyze"], ["draw"]]);
  });

  it("puts icons two to a row once analysed", () => {
    const rows = railRows(railTools({ hasTarget: true, analyzed: true }));
    expect(rows.map(keys)).toEqual([["route", "target"], ["analyze", "draw"], ["helo", "pz"], ["sector", "backstop"], ["unit", "goAround"]]);
  });

  it("starts a new row after a wide step, never pairing across it", () => {
    const rows = railRows([{ key: "a" }, { key: "b", wide: true }, { key: "c" }, { key: "d" }, { key: "e" }]);
    expect(rows.map(keys)).toEqual([["a"], ["b"], ["c", "d"], ["e"]]);
  });
});

describe("what fits", () => {
  const row = RAIL.size + RAIL.gap;
  const heightFor = (rows) => RAIL.top + RAIL.foot + rows * row - RAIL.gap;

  it("counts the rows a map has room for, above the row under the rail", () => {
    expect(railCapacity(heightFor(5))).toBe(5);
    expect(railCapacity(heightFor(5) - 1)).toBe(4);
    expect(railCapacity(heightFor(1))).toBe(1);
    expect(railCapacity(40)).toBe(0);
    // Not measured (no layout: a test, a browser without ResizeObserver): everything is offered.
    expect(railCapacity(undefined)).toBe(Infinity);
    expect(railCapacity(NaN)).toBe(Infinity);
  });

  const analysed = railRows(railTools({ hasTarget: true, analyzed: true }));

  it("draws every row where they fit, with no More", () => {
    expect(railFit(analysed, 5)).toEqual({ rows: analysed, more: [] });
    expect(railFit(analysed, Infinity)).toEqual({ rows: analysed, more: [] });
  });

  it("keeps the latest steps and puts the earliest behind More, which takes a place", () => {
    const { rows, more } = railFit(analysed, 4);
    expect(rows.map(keys)).toEqual([["helo", "pz"], ["sector", "backstop"], ["unit", "goAround"]]);
    expect(keys(more)).toEqual(["route", "target", "analyze", "draw"]);
  });

  it("keeps a tool whose mode is on in sight", () => {
    const sketching = railRows(railTools({ hasTarget: true, analyzed: true, sketchingRoute: true }));
    const { rows, more } = railFit(sketching, 3);
    expect(rows.map(keys)).toEqual([["route", "target"], ["unit", "goAround"]]);
    expect(keys(more)).not.toContain("route");

    const drawing = railRows(railTools({ hasTarget: true, drawingBoundary: true }));
    expect(railFit(drawing, 2).rows.map(keys)).toEqual([["draw"]]);
  });

  it("offers only the list where a single place is left, and nothing where there is none", () => {
    expect(railFit(analysed, 1)).toEqual({ rows: [], more: analysed.flat() });
    expect(railFit(analysed, 0)).toEqual({ rows: [], more: [] });
  });
});

describe("the LZ card's buttons", () => {
  it("offers Capture once the LZ/PZ is analysed, and Export once a capture area is set", () => {
    expect(cardTools()).toEqual({ capture: false, export: false });
    expect(cardTools({ canExport: true })).toEqual({ capture: true, export: false });
    expect(cardTools({ canExport: true, hasCaptureArea: true })).toEqual({ capture: true, export: true });
    // A capture area left from before is no export of something not analysed.
    expect(cardTools({ canExport: false, hasCaptureArea: true })).toEqual({ capture: false, export: false });
  });

  it("offers neither to an account without exports", () => {
    expect(cardTools({ features: { exports: false }, canExport: true, hasCaptureArea: true })).toEqual({ capture: false, export: false });
  });
});

describe("undo and redo", () => {
  it("stand on the map only while there is something to take back or put back", () => {
    expect(undoShown(null)).toBe(false);
    expect(undoShown({ canUndo: false, canRedo: false })).toBe(false);
    expect(undoShown({ canUndo: true, canRedo: false })).toBe(true);
    expect(undoShown({ canUndo: false, canRedo: true })).toBe(true);
  });
});

// AGENTS.md §17, The phone's frame hidden: which tap is the frame's.
describe("a tap on the map", () => {
  const phone = { phone: true };

  it("hides the frame, and the next shows it, when it is nothing else", () => {
    expect(frameTap(phone)).toBe("frame");
  });

  it("is never the frame's off a phone", () => {
    expect(frameTap({})).toBe("none");
    expect(frameTap({ phone: false, menuOpen: true })).toBe("none");
  });

  it("is a point, not the frame's, while a boundary, a route, a backstop or a FLOT is drawn", () => {
    expect(frameTap({ ...phone, drawing: true })).toBe("none");
    expect(frameTap({ ...phone, drawing: true, menuOpen: true })).toBe("none");
  });

  it("does nothing to a brief being presented", () => {
    expect(frameTap({ ...phone, presenting: true })).toBe("none");
  });

  it("only folds the search while it is open", () => {
    expect(frameTap({ ...phone, searchOpen: true })).toBe("none");
    expect(frameTap({ ...phone, searchOpen: true, menuOpen: true })).toBe("none");
  });

  it("only closes the menu while it is open", () => {
    expect(frameTap({ ...phone, menuOpen: true })).toBe("menu");
    expect(frameTap({ ...phone, menuOpen: true, dismissed: true })).toBe("menu");
  });

  it("is not the frame's when it put something away", () => {
    expect(frameTap({ ...phone, dismissed: true })).toBe("none");
  });
});

describe("the frame put away", () => {
  it("is only ever a phone's", () => {
    expect(frameHidden({ phone: true, hidden: true })).toBe(true);
    expect(frameHidden({ phone: false, hidden: true })).toBe(false);
    expect(frameHidden({ phone: true, hidden: false })).toBe(false);
  });

  it("comes back for something being drawn, and for a brief presented", () => {
    expect(frameHidden({ phone: true, hidden: true, drawing: true })).toBe(false);
    expect(frameHidden({ phone: true, hidden: true, presenting: true })).toBe(false);
  });
});
