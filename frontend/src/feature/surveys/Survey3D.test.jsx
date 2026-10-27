import React from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import Survey3D, { surveyWords } from "./Survey3D";
import { captureFrame } from "./capture3d";

const FAKE_CESIUM = {
  Math: { toDegrees: (radians) => (radians * 180) / Math.PI },
  Cartesian3: { distance: (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z) },
};
const mockParts = {
  viewer: { camera: { heading: Math.PI / 2, pitch: -Math.PI / 9, positionWC: { x: 0, y: 550, z: 0 } } },
  tileset: { boundingSphere: { center: { x: 0, y: 0, z: 0 }, radius: 250 } },
  Cesium: FAKE_CESIUM,
};

jest.mock("../viewer3d/Viewer3D", () => {
  const ReactInMock = require("react");
  return function FakeViewer({ view, capturable, onReady }) {
    ReactInMock.useEffect(() => { onReady(mockParts); }, [onReady]);
    return ReactInMock.createElement("div", { "data-testid": "viewer", "data-view": JSON.stringify(view), "data-capturable": String(capturable) });
  };
});
jest.mock("../viewer3d/useLidarTileset", () => ({ useLidarTileset: jest.fn() }));
jest.mock("./capture3d", () => ({ ...jest.requireActual("./capture3d"), captureFrame: jest.fn() }));
jest.mock("./surveyApi", () => ({ ...jest.requireActual("./surveyApi"), fetchAsset: jest.fn() }));
const mockShown = [];
jest.mock("../ui/Toast", () => ({ useToast: () => ({ show: (toast) => mockShown.push(toast) }) }));

const { useLidarTileset } = jest.requireMock("../viewer3d/useLidarTileset");
const { fetchAsset } = jest.requireMock("./surveyApi");
const TARGET = { lat: 34.596, lon: -84.1278 };
const SURVEY = { name: "GA_Statewide_B3_2018", year: 2018 };

beforeEach(() => {
  mockShown.length = 0;
  URL.createObjectURL = jest.fn(() => "blob:thumb");
  URL.revokeObjectURL = jest.fn();
  useLidarTileset.mockReturnValue({ state: "available", url: "/lidar/tilesets/k/tileset.json", contextUrl: null, frame: null });
  captureFrame.mockReset();
  fetchAsset.mockResolvedValue(new Blob(["png"]));
});

describe("the survey's 3D view", () => {
  it("opens the 3D window down the best approach and keeps a frame with the camera that took it", async () => {
    const blob = new Blob(["png"], { type: "image/png" });
    captureFrame.mockResolvedValue(blob);
    const onCapture = jest.fn(async () => {});
    render(<Survey3D assets={[]} target={TARGET} approach="090" survey={SURVEY} onCapture={onCapture} onRemove={jest.fn()} />);
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
    expect(captureFrame).toHaveBeenCalledWith(mockParts.viewer);
    expect(onCapture).toHaveBeenCalledWith(blob, { headingDeg: 90, pitchDeg: -20, rangeM: 550, survey: SURVEY });
    expect(screen.queryByRole("dialog", { name: "3D view for the page" })).toBeNull();
    expect(mockShown).toEqual([{ message: "3D view kept: the page prints it." }]);
  });

  it("says why a frame could not be kept, and keeps the window open", async () => {
    captureFrame.mockRejectedValue(Object.assign(new Error("tainted"), { name: "SecurityError" }));
    render(<Survey3D assets={[]} target={TARGET} approach="090" survey={SURVEY} onCapture={jest.fn()} onRemove={jest.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
    const dialog = screen.getByRole("dialog", { name: "3D view for the page" });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("The browser would not let the view be read");
    expect(within(dialog).getByRole("button", { name: "Capture" })).toBeEnabled();
  });

  it("says how the point cloud stands while it is found or built", () => {
    useLidarTileset.mockReturnValue({ state: "building" });
    render(<Survey3D assets={[]} target={TARGET} approach="" survey={null} onCapture={jest.fn()} onRemove={jest.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Open 3D" }));
    const dialog = screen.getByRole("dialog", { name: "3D view for the page" });
    expect(within(dialog).getByRole("status")).toHaveTextContent("Building the point cloud");
    expect(within(dialog).getByText(/The survey has no best approach/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Capture" })).toBeDisabled();
  });

  it("shows the capture the page prints, and takes it off when asked", async () => {
    const onRemove = jest.fn(async () => {});
    const capture = { id: 9, kind: "view_3d", url: "/surveys/s-1/assets/9", heading_deg: 89.6, taken_on: "27 OCT 2026", pose: { survey: SURVEY } };
    await act(async () => {
      render(<Survey3D assets={[{ id: 3, kind: "photo_north" }, capture]} target={TARGET} approach="090" survey={SURVEY}
                       onCapture={jest.fn()} onRemove={onRemove} />);
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
    const { rerender } = render(<Survey3D assets={[]} target={null} approach="090" onCapture={jest.fn()} onRemove={jest.fn()} />);
    expect(screen.getByRole("button", { name: "Open 3D" })).toBeDisabled();
    expect(screen.getByText("Set the LZ's target to see it in 3D.")).toBeInTheDocument();
    rerender(<Survey3D assets={[]} target={TARGET} approach="090" readOnly onCapture={jest.fn()} onRemove={jest.fn()} />);
    expect(screen.queryByRole("button", { name: "Open 3D" })).toBeNull();
  });

  it("names a survey with its year where its name does not say it", () => {
    expect(surveyWords(SURVEY)).toBe("GA_Statewide_B3_2018");
    expect(surveyWords({ name: "GA_North", year: 2019 })).toBe("GA_North (2019)");
    expect(surveyWords(null)).toBeNull();
  });
});
