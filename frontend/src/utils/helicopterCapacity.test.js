import {
  calculateUH60Capacity,
  UH60_MIN_CENTER_SPACING_FEET,
  UH60_MIN_CENTER_SPACING_METERS,
  UH60_ROTOR_TIP_CLEARANCE_METERS,
  UH60_SPOT_SIZE_SQ_FT,
} from './helicopterCapacity';

describe('UH-60 landing-zone capacity', () => {
  test('uses 60 m clearance between rotor-tip paths', () => {
    expect(UH60_ROTOR_TIP_CLEARANCE_METERS).toBe(60);
    expect(UH60_MIN_CENTER_SPACING_METERS).toBeCloseTo(76.357, 3);
    expect(UH60_MIN_CENTER_SPACING_FEET).toBeCloseTo(250.515, 2);
  });

  test('uses a 76.357 m square planning cell per helicopter', () => {
    expect(calculateUH60Capacity(UH60_SPOT_SIZE_SQ_FT * 2.99)).toBe(2);
    expect(calculateUH60Capacity(UH60_SPOT_SIZE_SQ_FT * 3)).toBe(3);
  });

  test('does not report capacity for invalid areas', () => {
    expect(calculateUH60Capacity(0)).toBe(0);
    expect(calculateUH60Capacity(-1)).toBe(0);
  });
});
