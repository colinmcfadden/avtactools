import { headingOf } from "./surveyDiagram";

/*
 * The 3D view's picture for an LZ survey's page (docs/LZ_SURVEYS.md §5, step L3). The server has no GPU, so the editor draws it: the
 * 3D window opens looking down the best approach, the admin turns it to show the LZ as a crew would see it, and Capture draws one frame
 * at the page's size and keeps it with the camera that took it (its heading, pitch and distance) and the LiDAR survey it shows.
 *
 * Pure but for the scene and the canvas it is handed, so it is tested without WebGL.
 */

// The page's 3D box is 3.79 × 2.10 in (backend/survey/templates/survey_style.css's .view3d, which crops a picture of another shape to
// fill it): the capture is that shape, so the page prints all of what the admin framed, and the capture window shows the same shape.
export const CAPTURE_WIDTH = 1600;
export const CAPTURE_HEIGHT = 887;
// Down at 20°: lower than the 3D view's own opening look (35°), nearer the way the LZ is seen on the approach, high enough to see in.
export const APPROACH_PITCH_DEG = -20;
// The most the frame is drawn finer than its window to reach the capture's width.
const MAX_FINER = 4;
// How long a capture waits for its frame, its finer tiles included: a scene whose drawing stopped (a lost WebGL context, a render
// error) draws none, and the capture would otherwise wait for ever.
export const CAPTURE_WAIT_MS = 15000;
// Of that, how long it waits for the finer tiles before it takes the frame as it is.
export const TILES_WAIT_MS = 8000;

/** The view down the best approach: the camera facing the way the aircraft flies in. Null where the survey has no best approach. */
export const approachView = (approachText) => {
  const heading = headingOf(approachText);
  return heading === null ? null : { headingDeg: heading, pitchDeg: APPROACH_PITCH_DEG };
};

const round1 = (value) => Math.round(value * 10) / 10;

/** Where the camera stands, as the capture keeps it: heading (° true) and pitch (°), and how far it is from the LZ's middle (m). */
export const poseOf = (Cesium, camera, centre) => ({
  headingDeg: round1(((Cesium.Math.toDegrees(camera.heading) % 360) + 360) % 360),
  pitchDeg: round1(Cesium.Math.toDegrees(camera.pitch)),
  rangeM: centre ? Math.round(Cesium.Cartesian3.distance(camera.positionWC, centre)) : null,
});

/** The date a capture was taken, as the book writes one: "27 OCT 2026". */
export const capturedOn = (when = new Date()) =>
  `${when.getDate()} ${["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"][when.getMonth()]} ${when.getFullYear()}`;

/** What of the scene chooses its detail by screen-space error: its 3D tilesets and its globe, each saying whether it has its tiles. */
const detailed = (scene) => {
  const parts = [];
  for (let i = 0; i < (scene.primitives?.length ?? 0); i += 1) {
    const primitive = scene.primitives.get(i);
    if (primitive && typeof primitive.maximumScreenSpaceError === "number" && "tilesLoaded" in primitive) parts.push(primitive);
  }
  if (scene.globe && typeof scene.globe.maximumScreenSpaceError === "number") parts.push(scene.globe);
  return parts;
};

/**
 * One frame of the scene as a PNG at the capture's size, the middle of the view cut to its shape, with what ``readPose`` says of the
 * camera as that very frame was drawn: { blob, pose }. Drawn finer than the window where the window is narrower than the capture, and
 * with the detail to match: Cesium judges a tile by its error over the pixel ratio, so a finer frame alone draws the window's tiles
 * larger. Their error is lowered by as much while the capture waits (``tilesWaitMs`` at most) for the finer tiles to come, then the
 * window's resolution and detail are put back. Rejects with what went wrong, a frame that never came (``waitMs``) included.
 */
export const captureFrame = (viewer, {
  width = CAPTURE_WIDTH, height = CAPTURE_HEIGHT, makeCanvas, readPose, waitMs = CAPTURE_WAIT_MS, tilesWaitMs = TILES_WAIT_MS,
  now = () => Date.now(), later = (fn, ms) => setTimeout(fn, ms),
} = {}) =>
  new Promise((resolve, reject) => {
    const { scene } = viewer;
    const before = viewer.resolutionScale;
    const finer = Math.min(width / Math.max(1, scene.canvas.width), MAX_FINER);
    const parts = finer > 1 ? detailed(scene) : [];
    const errors = parts.map((part) => part.maximumScreenSpaceError);
    if (finer > 1) {
      viewer.resolutionScale = before * finer;
      viewer.resize?.();
      parts.forEach((part) => { part.maximumScreenSpaceError /= finer; });
    }
    const started = now();
    let done = false;
    let stop = () => {};
    const finish = (error, value) => {
      if (done) return;
      done = true;
      stop();
      parts.forEach((part, i) => { part.maximumScreenSpaceError = errors[i]; });
      if (viewer.resolutionScale !== before) {
        viewer.resolutionScale = before;
        viewer.resize?.();
      }
      if (error) reject(error);
      else resolve(value);
    };
    later(() => finish(new Error("The 3D view drew no frame to capture: close it and open it again.")), waitMs);
    stop = scene.postRender.addEventListener(() => {
      if (done) return;
      if (parts.some((part) => !part.tilesLoaded) && now() - started < tilesWaitMs) {
        later(() => { if (!done) scene.requestRender(); }, 100);
        return;
      }
      try {
        const pose = readPose ? readPose() : null;
        const out = makeCanvas ? makeCanvas() : document.createElement("canvas");
        out.width = width;
        out.height = height;
        const source = scene.canvas;
        const scale = Math.max(width / source.width, height / source.height);
        const cutW = width / scale;
        const cutH = height / scale;
        out.getContext("2d").drawImage(source, (source.width - cutW) / 2, (source.height - cutH) / 2, cutW, cutH, 0, 0, width, height);
        stop();
        out.toBlob((blob) => (blob ? finish(null, { blob, pose }) : finish(new Error("The 3D view could not be captured."))), "image/png");
      } catch (error) {
        finish(error);
      }
    });
    scene.requestRender();
  });
