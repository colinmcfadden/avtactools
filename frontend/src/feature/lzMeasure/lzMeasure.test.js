import { normalizeLzDiagram } from "../lzWorkspace/useLzWorkspace";
import {
  LZ_MEASURE_ID,
  defaultLzMeasure,
  lzMeasureFor,
  savedLzMeasure,
  withLzMeasure,
} from "./lzMeasure";

const LZ = [
  [34.71, -84.21],
  [34.71, -84.2],
  [34.7, -84.2],
  [34.7, -84.21],
];

const turned = {
  corner: { lat: 34.708, lon: -84.209 },
  lengthTip: { lat: 34.701, lon: -84.207 },
  widthTip: { lat: 34.709, lon: -84.201 },
  lengthLabelAt: 0.4,
  widthLabelAt: 0.6,
};

describe("the LZ measurement line", () => {
  it("starts along the box's north-west corner when none is saved", () => {
    expect(lzMeasureFor([], LZ)).toEqual({
      corner: { lat: 34.71, lon: -84.21 },
      lengthTip: { lat: 34.7, lon: -84.21 },
      widthTip: { lat: 34.71, lon: -84.2 },
      lengthLabelAt: 0.1,
      widthLabelAt: 0.1,
    });
    expect(defaultLzMeasure([[1, 2]])).toBeNull();
  });

  it("is drawn where it was saved, not where the default would put it", () => {
    const measurements = withLzMeasure([], turned);
    expect(lzMeasureFor(measurements, LZ)).toEqual(turned);
  });

  it("changes the saved item in place and keeps every other measurement and unknown field", () => {
    const other = { id: "m-1", type: "ruler", points: [] };
    const first = withLzMeasure([other], turned);
    expect(first).toHaveLength(2);
    const withExtra = first.map((m) => (m.id === LZ_MEASURE_ID ? { ...m, note: "from a newer version" } : m));
    const moved = withLzMeasure(withExtra, { ...turned, lengthLabelAt: 2 });
    expect(moved).toHaveLength(2);
    expect(moved[0]).toBe(other);
    expect(moved[1]).toMatchObject({ id: LZ_MEASURE_ID, note: "from a newer version", lengthLabelAt: 1 });
  });

  it("ignores a saved item it cannot draw", () => {
    expect(savedLzMeasure([{ id: LZ_MEASURE_ID, type: "lzDimensions", corner: { lat: 1 } }])).toBeNull();
    expect(lzMeasureFor([{ id: LZ_MEASURE_ID, type: "lzDimensions" }], LZ).corner).toEqual({ lat: 34.71, lon: -84.21 });
  });

  it("survives the saved diagram's normalizer, so it comes back when the LZ/PZ is opened again", () => {
    const diagram = normalizeLzDiagram({
      id: "lz-1",
      target: { lat: 34.705, lon: -84.205 },
      status: "analyzed",
      analysis: { detectedLZ: LZ },
      graphics: { measurements: withLzMeasure([], turned) },
    });
    const reopened = normalizeLzDiagram(JSON.parse(JSON.stringify(diagram)));
    expect(lzMeasureFor(reopened.graphics.measurements, LZ)).toEqual(turned);
  });
});
