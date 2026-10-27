import { headingOf } from "./surveyDiagram";

/*
 * The 3D view's picture for an LZ survey's page (docs/LZ_SURVEYS.md §5, step L3). The server has no GPU, so the editor draws it: the
 * 3D window opens looking down the best approach, the admin turns it to show the LZ as a crew would see it, and Capture draws one frame
 * at the page's size and keeps it with the camera that took it (its heading, pitch and distance) and the LiDAR survey it shows.
 *
 * Pure but for the scene and the canvas it is handed, so it is tested without WebGL.
 */

export const CAPTURE_WIDTH = 1600;
export const CAPTURE_HEIGHT = 1000;
// Down at 20°: lower than the 3D view's own opening look (35°), nearer the way the LZ is seen on the approach, high enough to see in.
export const APPROACH_PITCH_DEG = -20;
// The most the frame is drawn finer than its window to reach the capture's width.
const MAX_FINER = 4;

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

/**
 * One frame of the scene as a PNG at the capture's size: the middle of the view, cut to its shape. Drawn finer than the window where
 * the window is narrower than the capture (so it is never a small frame stretched), then the window's own resolution is put back. Read
 * as soon as it is drawn, in the frame's own postRender. Rejects with what went wrong: a browser that will not let a canvas be read
 * (imagery without leave to) throws a SecurityError.
 */
export const captureFrame = (viewer, { width = CAPTURE_WIDTH, height = CAPTURE_HEIGHT, makeCanvas } = {}) =>
  new Promise((resolve, reject) => {
    const { scene } = viewer;
    const before = viewer.resolutionScale;
    const finer = width / Math.max(1, scene.canvas.width);
    if (finer > 1) {
      viewer.resolutionScale = before * Math.min(finer, MAX_FINER);
      viewer.resize?.();
    }
    const putBack = () => {
      if (viewer.resolutionScale !== before) {
        viewer.resolutionScale = before;
        viewer.resize?.();
      }
    };
    const stop = scene.postRender.addEventListener(() => {
      stop();
      try {
        const out = makeCanvas ? makeCanvas() : document.createElement("canvas");
        out.width = width;
        out.height = height;
        const source = scene.canvas;
        const scale = Math.max(width / source.width, height / source.height);
        const cutW = width / scale;
        const cutH = height / scale;
        out.getContext("2d").drawImage(source, (source.width - cutW) / 2, (source.height - cutH) / 2, cutW, cutH, 0, 0, width, height);
        putBack();
        out.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("The 3D view could not be captured."))), "image/png");
      } catch (error) {
        putBack();
        reject(error);
      }
    });
    scene.requestRender();
  });
