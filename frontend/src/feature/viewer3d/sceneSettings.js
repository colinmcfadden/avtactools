/**
 * How the 3D view looks and moves, in one place so the native apps can be held to it (contracts/fixtures/viewer3d/scene.json, written by
 * src/contracts/viewer3dFixtures.test.js). Viewer3D.jsx and Compass3D.jsx read these; gathering them here changed nothing they do.
 */

// Opening view: looking down at 35 degrees from the north-west, far enough
// back to hold the whole area of interest. Shallow enough that treelines and
// obstruction heights read against the horizon, steep enough to still see the
// landing surface itself.
export const CAMERA_HEADING_DEG = -30;
export const CAMERA_PITCH_DEG = -35;
export const CAMERA_RANGE_FACTOR = 2.2;

// Point size and shading are a matter of taste and of display density, and
// getting them right by exchanging screenshots is slow. These are the
// defaults; window.__viewer3d.tune() changes them live in development.
export const DEFAULT_POINT_SIZE_PX = 5;
export const POINT_SIZE_RANGE = [2, 16];

// Points have no surface, so screen-space error is the only lever on how much
// detail streams in. Cesium's default is tuned for buildings; at 8 the viewer
// refused to load the fine tiles at all. An LZ is small enough to afford
// loading it properly.
export const CORE_SCREEN_SPACE_ERROR = 2;

// The ring is thinned landscape at several metres between points, so pulling
// it in at the core's fidelity would cost bandwidth for detail that is not
// there. Looser error means it streams coarse and stays cheap.
export const CONTEXT_SCREEN_SPACE_ERROR = 8;

// Full strength at a 1 px radius outlines every individual point, which is
// what made returns read as separate tiles with borders rather than as a
// continuous canopy. Enough shading to show relief, not so much that it draws
// the points.
export const EDL_STRENGTH = 0.5;
export const EDL_RADIUS = 0.5;

export const BACKGROUND = "#05080d";

// The LZ and Routes buttons' flights, and the compass's turn to face north.
export const FRAME_SECONDS = 1.2;
export const FACE_NORTH_SECONDS = 0.6;
