import { DEFAULT_REFERENCE, graphicScale, MAX_REFERENCE, MIN_REFERENCE, referenceZoom } from "./scaleRule";

// An LZ of about 150 m near Ellijay, as an analysis gives it.
const LZ = [[34.6005, -84.4008], [34.6005, -84.3992], [34.5992, -84.3992], [34.5992, -84.4008]];

describe("the planning graphics' scale", () => {
  it("takes as its reference the zoom at which the LZ is about 100 px across", () => {
    const reference = referenceZoom(LZ);
    // 0.0016° of longitude at zoom z is 0.0016 / 360 × 256 × 2^z px.
    const across = (0.0016 / 360) * 256 * 2 ** reference;
    expect(across).toBeGreaterThan(98);
    expect(across).toBeLessThan(102);
    // Any shape of corner reads the same.
    expect(referenceZoom(LZ.map(([lat, lon]) => ({ lat, lon })))).toBe(reference);
    expect(referenceZoom(LZ.map(([lat, lng]) => ({ lat, lng })))).toBe(reference);
  });

  it("keeps the reference between 15 and 18, and is 17 with no boundary to measure", () => {
    expect(referenceZoom(null)).toBe(DEFAULT_REFERENCE);
    expect(referenceZoom([])).toBe(DEFAULT_REFERENCE);
    expect(referenceZoom([[34.6, -84.4]])).toBe(DEFAULT_REFERENCE);
    expect(referenceZoom([[34.6, -84.4], [34.6, -84.4]])).toBe(DEFAULT_REFERENCE);
    expect(referenceZoom([[34.6, -84.4], ["x", null], [34.6, NaN]])).toBe(DEFAULT_REFERENCE);
    // A field of a few metres, and one of kilometres.
    expect(referenceZoom([[34.6, -84.4], [34.60001, -84.40001]])).toBe(MAX_REFERENCE);
    expect(referenceZoom([[34.6, -84.4], [34.65, -84.35]])).toBe(MIN_REFERENCE);
  });

  it("draws them full size at the reference and nearer, half two levels out, and puts them away past that", () => {
    expect(graphicScale(17, 17)).toEqual({ scale: 1, hidden: false });
    expect(graphicScale(19.5, 17)).toEqual({ scale: 1, hidden: false });
    expect(graphicScale(16, 17)).toEqual({ scale: 0.707, hidden: false });
    expect(graphicScale(15, 17)).toEqual({ scale: 0.5, hidden: false });
    expect(graphicScale(14.9, 17).hidden).toBe(true);
    expect(graphicScale(10, 16.4).hidden).toBe(true);
    // A zoom not known yet changes nothing.
    expect(graphicScale(undefined, 17)).toEqual({ scale: 1, hidden: false });
  });
});
