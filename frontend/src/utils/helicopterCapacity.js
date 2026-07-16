export const METERS_TO_FEET = 3.280839895;

// UH-60 main rotor diameter: 53 ft 8 in / 16.357 m.
export const UH60_ROTOR_DIAMETER_METERS = 16.357;
export const UH60_ROTOR_RADIUS_METERS = UH60_ROTOR_DIAMETER_METERS / 2;
export const UH60_ROTOR_DIAMETER_FEET = UH60_ROTOR_DIAMETER_METERS * METERS_TO_FEET;
export const UH60_ROTOR_RADIUS_FEET = UH60_ROTOR_DIAMETER_FEET / 2;

// Required clear space between the two rotor-tip paths, not between centers.
export const UH60_ROTOR_TIP_CLEARANCE_METERS = 60;
export const UH60_ROTOR_TIP_CLEARANCE_FEET =
  UH60_ROTOR_TIP_CLEARANCE_METERS * METERS_TO_FEET;

// Aircraft centers must be a rotor diameter plus the tip-to-tip clearance apart.
export const UH60_MIN_CENTER_SPACING_METERS =
  UH60_ROTOR_DIAMETER_METERS + UH60_ROTOR_TIP_CLEARANCE_METERS;
export const UH60_MIN_CENTER_SPACING_FEET =
  UH60_MIN_CENTER_SPACING_METERS * METERS_TO_FEET;

export const UH60_SPOT_SIZE_SQ_FT =
  UH60_MIN_CENTER_SPACING_FEET * UH60_MIN_CENTER_SPACING_FEET;

/**
 * Conservative square-grid planning estimate. It provides the number of
 * 76.357 m × 76.357 m aircraft cells in the supplied area. Actual placement
 * must still account for the LZ shape, obstacles, and the landing plan.
 */
export const calculateUH60Capacity = (areaSqFt) => {
  if (!Number.isFinite(areaSqFt) || areaSqFt <= 0) return 0;
  return Math.floor(areaSqFt / UH60_SPOT_SIZE_SQ_FT);
};
