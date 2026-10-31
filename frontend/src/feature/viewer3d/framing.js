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

/**
 * The sphere the view opens on, and the *LZ* button flies back to: the
 * server's `frame` where it gave one (a streamed survey is a whole state's
 * octree, whose own sphere is the state), else the tileset's own. A frame
 * whose ground height nobody knows falls back too, rather than open on a
 * height made up.
 */
export const lzSphere = (Cesium, tileset, frame) => {
  const usable = frame && Number.isFinite(frame.lat) && Number.isFinite(frame.lon)
    && Number.isFinite(frame.height_m) && Number.isFinite(frame.radius_m);
  if (!usable) return tileset.boundingSphere;
  return new Cesium.BoundingSphere(
    Cesium.Cartesian3.fromDegrees(frame.lon, frame.lat, frame.height_m), frame.radius_m);
};
