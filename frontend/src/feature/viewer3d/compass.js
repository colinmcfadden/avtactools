/**
 * How the 3D compass should look for a camera orientation. Pure, so it can be
 * tested; Compass3D applies it.
 *
 * The dial behaves like a compass lying on the ground: turned so its N points
 * at true north, and tilted by the same amount the view is — flat when looking
 * straight down, leaning back as the camera pitches toward the horizon.
 */

// Past this the dial is too edge-on to read; Google Earth's stops near here too.
export const MAX_TILT_DEG = 62;

const toDegrees = (radians) => (radians * 180) / Math.PI;

/**
 * @param headingRad  Cesium camera heading: 0 north, clockwise, radians
 * @param pitchRad    Cesium camera pitch: -π/2 straight down, 0 level
 * @returns rotateDeg (turn the dial by this so N points north on screen),
 *          tiltDeg (lean the dial back by this), headingText ("330°T")
 */
export const compassPose = (headingRad, pitchRad) => {
  const heading = ((toDegrees(headingRad) % 360) + 360) % 360;
  const tilt = Math.min(MAX_TILT_DEG, Math.max(0, 90 + toDegrees(pitchRad)));
  // 359.6° reads as 000, not 360.
  const shown = Math.round(heading) % 360;
  return {
    rotateDeg: -heading,
    tiltDeg: tilt,
    headingText: `${String(shown).padStart(3, "0")}°T`,
  };
};
