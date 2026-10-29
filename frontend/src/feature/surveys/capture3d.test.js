import {
  APPROACH_PITCH_DEG, CAPTURE_HEIGHT, CAPTURE_WAIT_MS, CAPTURE_WIDTH, TILES_WAIT_MS, approachView, capturedOn, captureFrame, poseOf,
} from "./capture3d";

const FAKE_CESIUM = {
  Math: { toDegrees: (radians) => (radians * 180) / Math.PI },
  Cartesian3: { distance: (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z) },
};

/**
 * A Cesium viewer as far as a capture uses it: a scene whose canvas is its window's size times its resolution scale, which draws a
 * frame (its postRender) when asked unless ``draws`` is false, and its point cloud and globe with their detail.
 */
const fakeViewer = (cssWidth = 800, cssHeight = 500, { draws = true, tileset = null, globe = null } = {}) => {
  const scene = {
    canvas: { width: cssWidth, height: cssHeight },
    listener: null,
    primitives: { length: tileset ? 1 : 0, get: () => tileset },
    globe,
    postRender: {
      addEventListener(fn) {
        scene.listener = fn;
        return () => { scene.listener = null; };
      },
    },
    requestRender() { if (draws) scene.listener?.(); },
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
  const drawImage = vi.fn();
  const canvas = { getContext: () => ({ drawImage }), toBlob: toBlob ?? ((done) => done(new Blob(["png"], { type: "image/png" }))) };
  return { canvas, drawImage };
};

/** A clock and the timers a capture sets, run by hand: ``run(ms)`` runs those set for that long. */
const fakeTime = () => {
  const time = { at: 0, timers: [] };
  time.now = () => time.at;
  time.later = (fn, ms) => time.timers.push({ fn, ms });
  time.run = (ms) => {
    const due = time.timers.filter((timer) => timer.ms === ms);
    time.timers = time.timers.filter((timer) => timer.ms !== ms);
    due.forEach((timer) => timer.fn());
  };
  return time;
};

describe("the 3D view's capture", () => {
  it("opens down the best approach", () => {
    expect(approachView("090°")).toEqual({ headingDeg: 90, pitchDeg: APPROACH_PITCH_DEG });
    expect(approachView("360")).toEqual({ headingDeg: 0, pitchDeg: APPROACH_PITCH_DEG });
    expect(approachView("")).toBeNull();
    expect(approachView("see notes")).toBeNull();
  });

  it("is the page's 3D box's shape, so the page prints all of it", () => {
    expect([CAPTURE_WIDTH, CAPTURE_HEIGHT]).toEqual([1600, 887]);
    expect(CAPTURE_WIDTH / CAPTURE_HEIGHT).toBeCloseTo(3.79 / 2.10, 2);              // survey_style.css's .view3d, 3.79 × 2.10 in
  });

  it("draws one frame at the page's size, finer than its window, and puts the window back", async () => {
    const viewer = fakeViewer(800, 500);
    const { canvas, drawImage } = fakeCanvas();
    const { blob, pose } = await captureFrame(viewer, { makeCanvas: () => canvas, ...fakeTime() });
    expect(blob.type).toBe("image/png");
    expect(pose).toBeNull();
    expect([canvas.width, canvas.height]).toEqual([CAPTURE_WIDTH, CAPTURE_HEIGHT]);
    // Drawn at twice the window's resolution, 1600 × 1000, and its middle 887 rows kept.
    expect(drawImage).toHaveBeenCalledWith(viewer.scene.canvas, 0, 56.5, 1600, 887, 0, 0, 1600, 887);
    expect(viewer.resolutionScale).toBe(1);
    expect(viewer.scene.canvas.width).toBe(800);
  });

  it("cuts a window of another shape to the page's, from its middle", async () => {
    const viewer = fakeViewer(2000, 1000);                                       // wider than the page's, and fine enough already
    const { canvas, drawImage } = fakeCanvas();
    await captureFrame(viewer, { makeCanvas: () => canvas, ...fakeTime() });
    const [, sx, sy, sw, sh] = drawImage.mock.calls[0];
    expect(sy).toBe(0);
    expect(sh).toBe(1000);
    expect(sw).toBeCloseTo(1000 * (1600 / 887), 6);
    expect(sx).toBeCloseTo((2000 - sw) / 2, 6);
  });

  it("asks for the detail of the finer frame, waits for its tiles, and puts the window's detail back", async () => {
    // Cesium judges a tile's detail by its error over the pixel ratio: a frame drawn finer, its detail unchanged, draws the window's
    // tiles larger (the review measured the same 8 tiles in the window and in the capture, against 9 in a true 1600 px view).
    const tileset = { maximumScreenSpaceError: 16, tilesLoaded: false };
    const globe = { maximumScreenSpaceError: 2, tilesLoaded: true };
    const viewer = fakeViewer(800, 500, { tileset, globe });
    const time = fakeTime();
    const { canvas, drawImage } = fakeCanvas();
    const kept = captureFrame(viewer, { makeCanvas: () => canvas, ...time });
    expect([tileset.maximumScreenSpaceError, globe.maximumScreenSpaceError]).toEqual([8, 1]);   // twice as fine
    expect(drawImage).not.toHaveBeenCalled();                                                  // its tiles are still coming
    time.at = 500;
    tileset.tilesLoaded = true;
    time.run(100);                                                                             // the next frame
    await kept;
    expect(drawImage).toHaveBeenCalledTimes(1);
    expect([tileset.maximumScreenSpaceError, globe.maximumScreenSpaceError]).toEqual([16, 2]);
  });

  it("takes the frame as it is when its finer tiles are long in coming", async () => {
    const tileset = { maximumScreenSpaceError: 16, tilesLoaded: false };
    const viewer = fakeViewer(800, 500, { tileset });
    const time = fakeTime();
    const { canvas, drawImage } = fakeCanvas();
    const kept = captureFrame(viewer, { makeCanvas: () => canvas, ...time });
    time.at = TILES_WAIT_MS + 1;
    time.run(100);
    await kept;
    expect(drawImage).toHaveBeenCalledTimes(1);
    expect(tileset.maximumScreenSpaceError).toBe(16);
  });

  it("gives up on a frame that never comes, says so, and puts the window back", async () => {
    // A scene whose drawing stopped (a lost WebGL context, a render error) draws no frame: the capture once waited for ever.
    const tileset = { maximumScreenSpaceError: 16, tilesLoaded: true };
    const viewer = fakeViewer(800, 500, { draws: false, tileset });
    const time = fakeTime();
    const kept = captureFrame(viewer, { makeCanvas: () => fakeCanvas().canvas, ...time });
    time.run(CAPTURE_WAIT_MS);
    await expect(kept).rejects.toThrow("The 3D view drew no frame to capture");
    expect(viewer.resolutionScale).toBe(1);
    expect(tileset.maximumScreenSpaceError).toBe(16);
    expect(viewer.scene.listener).toBeNull();
  });

  it("reads the camera as the frame it keeps was drawn, not after", async () => {
    // A camera still moving (the LZ button's flight) was read once the picture was encoded, a frame or more later.
    const tileset = { maximumScreenSpaceError: 16, tilesLoaded: false };
    const viewer = fakeViewer(800, 500, { tileset });
    const time = fakeTime();
    let heading = 90;
    const kept = captureFrame(viewer, { makeCanvas: () => fakeCanvas().canvas, readPose: () => ({ headingDeg: heading }), ...time });
    heading = 93.7;                                                             // the frame it keeps
    tileset.tilesLoaded = true;
    time.run(100);
    heading = 94.9;                                                             // after it
    expect((await kept).pose).toEqual({ headingDeg: 93.7 });
  });

  it("says why a frame could not be read, and puts the window back", async () => {
    const viewer = fakeViewer(800, 500);
    const failed = new Error("toBlob failed");
    const { canvas } = fakeCanvas({ toBlob: () => { throw failed; } });
    await expect(captureFrame(viewer, { makeCanvas: () => canvas, ...fakeTime() })).rejects.toBe(failed);
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
