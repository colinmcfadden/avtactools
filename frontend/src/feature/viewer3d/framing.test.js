import { framingRange } from "./framing";
import { CAMERA_RANGE_FACTOR } from "./sceneSettings";

const FOV = Math.PI / 3;
// The half-angle across a view of this shape: Cesium's field of view runs across the wider side.
const acrossHalf = (aspect) => (aspect >= 1 ? FOV / 2 : Math.atan(Math.tan(FOV / 2) * aspect));

describe("framingRange", () => {
  test("a view at least as wide as tall frames a sphere from 2.2 radii, as it always has", () => {
    for (const aspect of [1, 16 / 9, 2.2, 3]) {
      expect(framingRange(150, aspect, FOV)).toBe(150 * CAMERA_RANGE_FACTOR);
    }
  });

  test("a view narrower than tall stands back until the sphere fits across it, with 2.2 radii's margin", () => {
    for (const aspect of [0.99, 0.75, 412 / 915, 360 / 740, 0.3]) {
      const range = framingRange(150, aspect, FOV);
      expect(range).toBeGreaterThan(150 * CAMERA_RANGE_FACTOR);
      // The sphere's half-angle seen from there is the narrower half-angle's, a tenth inside it as 2.2 radii are inside 60 degrees.
      expect(Math.sin(acrossHalf(aspect)) / (150 / range)).toBeCloseTo(CAMERA_RANGE_FACTOR * Math.sin(FOV / 2), 12);
      expect(Math.asin(150 / range)).toBeLessThan(acrossHalf(aspect));
    }
    // An upright phone (412 by 915 points): twice as far back, near enough.
    expect(framingRange(150, 412 / 915, FOV) / 150).toBeCloseTo(4.372, 3);
  });

  test("the narrower the view, the further back; and nothing changes at a square", () => {
    const ranges = [1, 0.9, 0.6, 0.4, 0.2].map((aspect) => framingRange(100, aspect, FOV));
    for (let i = 1; i < ranges.length; i += 1) expect(ranges[i]).toBeGreaterThan(ranges[i - 1]);
    expect(framingRange(100, 0.999999, FOV)).toBeCloseTo(framingRange(100, 1, FOV), 3);
  });

  test("a sphere with no size, or a view with no shape, keeps what it gave before", () => {
    expect(framingRange(0, 0.5, FOV)).toBe(0);
    expect(framingRange(150, 0, FOV)).toBe(150 * CAMERA_RANGE_FACTOR);
    expect(framingRange(150, Number.NaN, FOV)).toBe(150 * CAMERA_RANGE_FACTOR);
  });
});
