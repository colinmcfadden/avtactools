import { fitInsets, indexOfStep, overviewBounds, roleColour, FIGURE_COLOURS } from "./sandtableSteps";

// The map's box on the page, and what lies over it, as the sandtable measures them.
const MAP = { left: 300, top: 56, right: 1440, bottom: 900 };
const CARD = { left: 480, top: 500, right: 1260, bottom: 884 }; // along the bottom, in the middle
const CONSTANTS = { left: 1158, top: 68, right: 1428, bottom: 260 }; // the top right corner

describe("how far in a figure is fitted", () => {
  it("keeps a margin from every edge when nothing lies over the map", () => {
    expect(fitInsets(MAP, [])).toEqual({ top: 16, right: 16, bottom: 16, left: 16 });
    expect(fitInsets(MAP, [null, undefined])).toEqual({ top: 16, right: 16, bottom: 16, left: 16 });
  });

  it("lifts the figure above the script card", () => {
    expect(fitInsets(MAP, [CARD])).toEqual({ top: 16, right: 16, bottom: 900 - 500 + 16, left: 16 });
  });

  it("keeps clear of the constants box from the side once the card has the bottom, never squeezing the height twice", () => {
    const insets = fitInsets(MAP, [CONSTANTS, CARD]);
    expect(insets.bottom).toBe(416);
    expect(insets.right).toBe(1440 - 1158 + 16);
    expect(insets.top).toBe(16);
  });

  it("takes the corner box from the top when that leaves more map", () => {
    const wide = { left: 300, top: 56, right: 2400, bottom: 900 };
    expect(fitInsets(wide, [{ left: 2100, top: 68, right: 2388, bottom: 140 }])).toMatchObject({ top: 140 - 56 + 16, right: 16 });
  });

  it("ignores what does not lie over the map, or lies only where the figure is already kept from", () => {
    expect(fitInsets(MAP, [{ left: 0, top: 56, right: 290, bottom: 900 }])).toEqual({ top: 16, right: 16, bottom: 16, left: 16 });
    const under = { left: 600, top: 700, right: 900, bottom: 880 }; // inside the band the card already keeps clear
    expect(fitInsets(MAP, [CARD, under])).toEqual(fitInsets(MAP, [CARD]));
  });

  it("keeps steps laid over the map (a narrow window) to the left", () => {
    const narrow = { left: 0, top: 56, right: 800, bottom: 860 };
    const steps = { left: 0, top: 56, right: 300, bottom: 860 };
    expect(fitInsets(narrow, [steps]).left).toBe(316);
  });

  it("always leaves some map, however much lies over it", () => {
    const insets = fitInsets(MAP, [{ left: 300, top: 120, right: 1440, bottom: 900 }]);
    expect(844 - insets.top - insets.bottom).toBeGreaterThanOrEqual(160);
    const small = { left: 0, top: 0, right: 100, bottom: 100 };
    const tiny = fitInsets(small, [{ left: 0, top: 10, right: 100, bottom: 100 }]);
    expect(Object.values(tiny).every((v) => v >= 0)).toBe(true);
  });

  it("gives the margins alone for a map with no size (before it is laid out)", () => {
    expect(fitInsets({ left: 0, top: 0, right: 0, bottom: 0 }, [CARD])).toEqual({ top: 16, right: 16, bottom: 16, left: 16 });
    expect(fitInsets(null, [CARD])).toEqual({ top: 16, right: 16, bottom: 16, left: 16 });
  });
});

describe("finding a step", () => {
  const model = { steps: [{ id: "1" }, { id: "2" }, { id: "3" }] };

  it("finds it by its id wherever it now is", () => {
    expect(indexOfStep(model, "3", 0)).toBe(2);
  });

  it("stays at the same place in the list when the step has gone", () => {
    expect(indexOfStep(model, "gone", 1)).toBe(1);
    expect(indexOfStep(model, "gone", 9)).toBe(2);
    expect(indexOfStep(model, null, 0)).toBe(0);
  });

  it("finds nothing in a brief with no steps", () => {
    expect(indexOfStep({ steps: [] }, "1", 0)).toBe(-1);
    expect(indexOfStep(null, "1", 0)).toBe(-1);
  });
});

describe("the overview", () => {
  it("is the first overview figure's area, else every figure's area together", () => {
    const area = (s, w, n, e) => [[s, w], [n, e]];
    expect(overviewBounds({ steps: [{ figure: { kind: "route", bounds: area(1, 1, 2, 2) } }, { figure: { kind: "overview", bounds: area(0, 0, 5, 5) } }] }))
      .toEqual(area(0, 0, 5, 5));
    expect(overviewBounds({ steps: [{ figure: { kind: "route", bounds: area(1, 1, 2, 2) } }, { figure: null }, { figure: { kind: "stop", bounds: area(3, -1, 4, 1.5) } }] }))
      .toEqual(area(1, -1, 4, 2));
    expect(overviewBounds({ steps: [{ figure: { kind: "overview", bounds: null } }] })).toBeNull();
  });
});

describe("the colours", () => {
  it("are the PDF's, a role the figures do not know drawn as an LZ", () => {
    expect(roleColour("PZ")).toBe("#6fe3a5");
    expect(roleColour("nope")).toBe(FIGURE_COLOURS.roles.LZ);
  });
});
