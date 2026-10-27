import { APPROACH_PITCH_DEG, CAPTURE_HEIGHT, CAPTURE_WIDTH, approachView, capturedOn, captureFrame, poseOf } from "./capture3d";

const FAKE_CESIUM = {
  Math: { toDegrees: (radians) => (radians * 180) / Math.PI },
  Cartesian3: { distance: (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z) },
};

/** A Cesium viewer as far as a capture uses it: a scene whose canvas is its window's size times its resolution scale. */
const fakeViewer = (cssWidth = 800, cssHeight = 500) => {
  const scene = {
    canvas: { width: cssWidth, height: cssHeight },
    listener: null,
    postRender: {
      addEventListener(fn) {
        scene.listener = fn;
        return () => { scene.listener = null; };
      },
    },
    requestRender() { scene.listener?.(); },
  };
  const viewer = {
    scene,
    resolutionScale: 1,
    resize() {
      scene.canvas.width = cssWidth * viewer.resolutionScale;
      scene.canvas.height = cssHeight * viewer.resolutionScale;
    },
  };
  return viewer;
};

const fakeCanvas = ({ toBlob } = {}) => {
  const drawImage = jest.fn();
  const canvas = { getContext: () => ({ drawImage }), toBlob: toBlob ?? ((done) => done(new Blob(["png"], { type: "image/png" }))) };
  return { canvas, drawImage };
};

describe("the 3D view's capture", () => {
  it("opens down the best approach", () => {
    expect(approachView("090°")).toEqual({ headingDeg: 90, pitchDeg: APPROACH_PITCH_DEG });
    expect(approachView("360")).toEqual({ headingDeg: 0, pitchDeg: APPROACH_PITCH_DEG });
    expect(approachView("")).toBeNull();
    expect(approachView("see notes")).toBeNull();
  });

  it("draws one frame at the page's size, finer than its window, and puts the window back", async () => {
    const viewer = fakeViewer(800, 500);
    const { canvas, drawImage } = fakeCanvas();
    const blob = await captureFrame(viewer, { makeCanvas: () => canvas });
    expect(blob.type).toBe("image/png");
    expect([canvas.width, canvas.height]).toEqual([CAPTURE_WIDTH, CAPTURE_HEIGHT]);
    // Drawn at twice the window's resolution: 1600 × 1000, the whole of it.
    expect(drawImage).toHaveBeenCalledWith(viewer.scene.canvas, 0, 0, 1600, 1000, 0, 0, 1600, 1000);
    expect(viewer.resolutionScale).toBe(1);
    expect(viewer.scene.canvas.width).toBe(800);
  });

  it("cuts a window of another shape to the page's, from its middle", async () => {
    const viewer = fakeViewer(2000, 1000);                                       // wider than 16:10, and fine enough already
    const { canvas, drawImage } = fakeCanvas();
    await captureFrame(viewer, { makeCanvas: () => canvas });
    expect(drawImage).toHaveBeenCalledWith(viewer.scene.canvas, 200, 0, 1600, 1000, 0, 0, 1600, 1000);
  });

  it("says why a frame could not be read, and puts the window back", async () => {
    const viewer = fakeViewer(800, 500);
    const tainted = Object.assign(new Error("The canvas has been tainted"), { name: "SecurityError" });
    const { canvas } = fakeCanvas({ toBlob: () => { throw tainted; } });
    await expect(captureFrame(viewer, { makeCanvas: () => canvas })).rejects.toBe(tainted);
    expect(viewer.resolutionScale).toBe(1);
  });

  it("keeps the camera's heading, pitch and distance from the LZ", () => {
    const camera = { heading: -Math.PI / 2, pitch: -Math.PI / 9, positionWC: { x: 300, y: 400, z: 0 } };
    expect(poseOf(FAKE_CESIUM, camera, { x: 0, y: 0, z: 0 })).toEqual({ headingDeg: 270, pitchDeg: -20, rangeM: 500 });
    expect(poseOf(FAKE_CESIUM, camera, null).rangeM).toBeNull();
  });

  it("is dated as the book dates things", () => {
    expect(capturedOn(new Date(2026, 9, 27))).toBe("27 OCT 2026");
  });
});
