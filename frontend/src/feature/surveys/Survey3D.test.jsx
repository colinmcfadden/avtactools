import React from "react";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import Survey3D, { captionWords, captureFailure, surveyWords } from "./Survey3D";
import { captureFrame } from "./capture3d";
import { useLidarTileset } from "../viewer3d/useLidarTileset";
import { fetchAsset } from "./surveyApi";

const FAKE_CESIUM = {
  Math: { toDegrees: (radians) => (radians * 180) / Math.PI },
  Cartesian3: { distance: (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z), fromDegrees: () => ({ x: 0, y: 0, z: 0 }) },
  BoundingSphere: function BoundingSphere(center, radius) { this.center = center; this.radius = radius; },
};
const mockParts = {
  viewer: { camera: { heading: Math.PI / 2, pitch: -Math.PI / 9, positionWC: { x: 0, y: 550, z: 0 } } },
  tileset: { boundingSphere: { center: { x: 0, y: 0, z: 0 }, radius: 250 } },
  Cesium: FAKE_CESIUM,
};

vi.mock("../viewer3d/Viewer3D", async () => {
  const ReactInMock = await import("react");
  return { default: function FakeViewer({ view, capturable, onReady }) {
    ReactInMock.useEffect(() => { onReady(mockParts); }, [onReady]);
    return ReactInMock.createElement("div", { "data-testid": "viewer", "data-view": JSON.stringify(view), "data-capturable": String(capturable) });
  } };
});
vi.mock("../viewer3d/useLidarTileset", () => ({ useLidarTileset: vi.fn() }));
vi.mock("./capture3d", async (importOriginal) => ({ ...(await importOriginal()), captureFrame: vi.fn() }));
vi.mock("./surveyApi", async (importOriginal) => ({ ...(await importOriginal()), fetchAsset: vi.fn() }));
const mockShown = [];
vi.mock("../ui/Toast", () => ({ useToast: () => ({ show: (toast) => mockShown.push(toast) }) }));

const TARGET = { lat: 34.596, lon: -84.1278 };
const SURVEY = { name: "GA_Statewide_B3_2018", year: 2018 };
const FRAME = { lat: TARGET.lat, lon: TARGET.lon, height_m: 300, radius_m: 250 };
const STREAMED = { state: "available", url: "/lidar/tilesets/k/tileset.json", contextUrl: null, frame: FRAME, streamed: true };
const blob = new Blob(["png"], { type: "image/png" });
// The frame, and the camera read as it was drawn.
const kept = async (_viewer, { readPose }) => ({ blob, pose: readPose() });

const openWindow = (props = {}) => {
  render(<Survey3D assets={[]} target={TARGET} approach="090" survey={SURVEY} onCapture={vi.fn()} onRemove={vi.fn()} {...props} />);
  fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
  return screen.getByRole("dialog", { name: "3D view for the page" });
};

beforeEach(() => {
  mockShown.length = 0;
  URL.createObjectURL = vi.fn(() => "blob:thumb");
  URL.revokeObjectURL = vi.fn();
  useLidarTileset.mockReturnValue(STREAMED);
  captureFrame.mockReset();
  fetchAsset.mockResolvedValue(new Blob(["png"]));
});

describe("the survey's 3D view", () => {
  it("opens the 3D window down the best approach and keeps a frame with the camera that took it", async () => {
    captureFrame.mockImplementation(kept);
    const onCapture = vi.fn(async () => {});
    render(<Survey3D assets={[]} target={TARGET} approach="090" survey={SURVEY} onCapture={onCapture} onRemove={vi.fn()} />);
    expect(screen.getByText("No 3D view captured yet.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
    const dialog = screen.getByRole("dialog", { name: "3D view for the page" });
    expect(within(dialog).getByText("Opens looking down the best approach, 090°")).toBeInTheDocument();
    const viewer = within(dialog).getByTestId("viewer");
    expect(JSON.parse(viewer.dataset.view)).toEqual({ headingDeg: 90, pitchDeg: -20 });
    expect(viewer.dataset.capturable).toBe("true");
    expect(useLidarTileset).toHaveBeenCalledWith({ lat: TARGET.lat, lon: TARGET.lon, stream: true });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    });
    expect(captureFrame).toHaveBeenCalledWith(mockParts.viewer, expect.objectContaining({ readPose: expect.any(Function) }));
    expect(onCapture).toHaveBeenCalledWith(blob, { headingDeg: 90, pitchDeg: -20, rangeM: 550, survey: SURVEY },
      { signal: expect.any(AbortSignal) });
    expect(screen.queryByRole("dialog", { name: "3D view for the page" })).toBeNull();
    expect(mockShown).toEqual([{ message: "3D view kept: the page prints it." }]);
  });

  it("names a survey only where the window streams the one the obstacles were read from, and a range only from the LZ", async () => {
    captureFrame.mockImplementation(kept);
    const onCapture = vi.fn(async () => {});
    // A point cloud built for the LZ (perhaps from a downloaded collection): its survey is not the snapshot's to say.
    useLidarTileset.mockReturnValue({ ...STREAMED, streamed: false, frame: null });
    let dialog = openWindow({ onCapture });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Capture" })); });
    expect(onCapture.mock.calls[0][1]).toEqual({ headingDeg: 90, pitchDeg: -20, rangeM: 550, survey: null });   // its own sphere is the LZ's
    // A streamed survey with no frame: its own sphere is a whole state, so no range is said rather than one the server refuses.
    cleanup();
    useLidarTileset.mockReturnValue({ ...STREAMED, frame: null });
    dialog = openWindow({ onCapture });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Capture" })); });
    expect(onCapture.mock.calls[1][1]).toEqual({ headingDeg: 90, pitchDeg: -20, rangeM: null, survey: SURVEY });
  });

  it("says why a frame could not be kept in its own words, and keeps the window open", async () => {
    captureFrame.mockRejectedValue(new Error("The 3D view drew no frame to capture: close it and open it again."));
    const dialog = openWindow();
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("The 3D view drew no frame to capture");
    expect(within(dialog).getByRole("button", { name: "Capture" })).toBeEnabled();
    // Nothing of the browser's is called a lost connection; the server's answer is the server's.
    expect(captureFailure(new TypeError("Cannot read properties of null"))).toBe("The 3D view could not be captured: close it and open it again.");
    expect(captureFailure({ isAxiosError: true, response: { status: 400, data: { error: "The camera pose's rangeM is a number from 0 to 100000" } } }))
      .toBe("The camera pose's rangeM is a number from 0 to 100000");
    expect(captureFailure({ isAxiosError: true, request: {} })).toBe("No connection to the server. Try again when you have one.");
  });

  it("closes while it captures, and keeps nothing it gave up", async () => {
    let finish;
    captureFrame.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const onCapture = vi.fn(async () => {});
    const dialog = openWindow({ onCapture });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Capture" })); });
    expect(within(dialog).getByRole("button", { name: "Keeping…" })).toBeDisabled();
    fireEvent.keyDown(dialog, { key: "Escape" });                                    // once refused while it waited, for ever
    expect(screen.queryByRole("dialog", { name: "3D view for the page" })).toBeNull();
    await act(async () => { finish({ blob, pose: { headingDeg: 90, pitchDeg: -20, rangeM: 550 } }); });
    expect(onCapture).not.toHaveBeenCalled();
  });

  it("gives up the upload too when it closes", async () => {
    captureFrame.mockImplementation(kept);
    let signal;
    const onCapture = vi.fn((_blob, _pose, options) => {
      ({ signal } = options);
      return new Promise(() => {});
    });
    const dialog = openWindow({ onCapture });
    await act(async () => { fireEvent.click(within(dialog).getByRole("button", { name: "Capture" })); });
    expect(signal.aborted).toBe(false);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));          // once disabled while it waited
    expect(screen.queryByRole("dialog", { name: "3D view for the page" })).toBeNull();
    expect(signal.aborted).toBe(true);
  });

  it("offers Capture only while its viewer stands", async () => {
    const { rerender } = render(<Survey3D assets={[]} target={TARGET} approach="090" survey={SURVEY} onCapture={vi.fn()} onRemove={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
    expect(screen.getByRole("button", { name: "Capture" })).toBeEnabled();
    // The target moved: another point cloud, its viewer not yet ready.
    useLidarTileset.mockReturnValue({ ...STREAMED, url: "/lidar/tilesets/m/tileset.json" });
    rerender(<Survey3D assets={[]} target={{ lat: 34.6, lon: -84.13 }} approach="090" survey={SURVEY} onCapture={vi.fn()} onRemove={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Capture" })).toBeDisabled();
  });

  it("says how the point cloud stands while it is found or built", () => {
    useLidarTileset.mockReturnValue({ state: "building" });
    render(<Survey3D assets={[]} target={TARGET} approach="" survey={null} onCapture={vi.fn()} onRemove={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
    const dialog = screen.getByRole("dialog", { name: "3D view for the page" });
    expect(within(dialog).getByRole("status")).toHaveTextContent("Building the point cloud");
    expect(within(dialog).getByText(/The survey has no best approach/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Capture" })).toBeDisabled();
  });

  it("shows the capture the page prints, and takes it off when asked", async () => {
    const onRemove = vi.fn(async () => {});
    const capture = { id: 9, kind: "view_3d", url: "/surveys/s-1/assets/9", heading_deg: 89.6, taken_on: "27 OCT 2026", pose: { survey: SURVEY } };
    await act(async () => {
      render(<Survey3D assets={[{ id: 3, kind: "photo_north" }, capture]} target={TARGET} approach="090" survey={SURVEY}
                       onCapture={vi.fn()} onRemove={onRemove} />);
    });
    expect(screen.getByText("Looking 090° true · 27 OCT 2026 · LiDAR GA_Statewide_B3_2018")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Capture again" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Take off the survey" }));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Take it off" }));
    });
    expect(onRemove).toHaveBeenCalledWith(9);
  });

  it("offers nothing without a target, or in an archived survey", () => {
    const { rerender } = render(<Survey3D assets={[]} target={null} approach="090" onCapture={vi.fn()} onRemove={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Open 3D" })).toBeDisabled();
    expect(screen.getByText("Set the LZ's target to see it in 3D.")).toBeInTheDocument();
    rerender(<Survey3D assets={[]} target={TARGET} approach="090" readOnly onCapture={vi.fn()} onRemove={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Open 3D" })).toBeNull();
  });

  it("names a survey with its year where its name does not say it", () => {
    expect(surveyWords(SURVEY)).toBe("GA_Statewide_B3_2018");
    expect(surveyWords({ name: "GA_North", year: 2019 })).toBe("GA_North (2019)");
    expect(surveyWords(null)).toBeNull();
    expect(captionWords({ heading_deg: 359.6, taken_on: "27 OCT 2026" })).toBe("Looking 360° true · 27 OCT 2026");   // as the page says north
  });
});
