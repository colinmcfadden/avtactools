import { CAMERA_RANGE_FACTOR } from "./sceneSettings";

/**
 * How far back the camera stands to frame a sphere of `radius` (the opening view, LZ and Routes), for a view of `aspectRatio` (its width
 * over its height) whose field of view is `fov` (Cesium's, 60 degrees by default, which runs across the view's wider side).
 *
 * A view at least as wide as it is tall has that field across it, and 2.2 radii hold the sphere across with a tenth to spare: the look the
 * view has always had. A view narrower than tall (a phone held upright, a narrow window) is narrower across than 60 degrees, and 2.2 radii
 * cut the sphere's sides off; there the camera stands back until the sphere fits across the narrower width with the same margin, as
 * Cesium's own default range fits the narrower field (Camera's distanceToBoundingSphere3D).
 */
export const framingRange = (radius, aspectRatio, fov) => {
  const range = radius * CAMERA_RANGE_FACTOR;
  if (!(aspectRatio > 0 && aspectRatio < 1)) return range;
  const across = Math.atan(Math.tan(fov / 2) * aspectRatio);
  return range * Math.sin(fov / 2) / Math.sin(across);
};
