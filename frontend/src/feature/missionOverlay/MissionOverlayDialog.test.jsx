import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { downloadBlob } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, shownBounds } from "./captureOverlay";
import MissionOverlayDialog from "./MissionOverlayDialog";

vi.mock("../auth/api", () => ({ default: { post: vi.fn() } }));
vi.mock("./captureOverlay", () => ({ captureOverlay: vi.fn(), missionBounds: vi.fn(), shownBounds: vi.fn() }));
vi.mock("../../utils/fileDownload", async (importOriginal) => ({ ...(await importOriginal()), downloadBlob: vi.fn() }));

const MISSION = { north: 34.75, south: 34.55, east: -84.35, west: -84.55 };
const SHOWN = { north: 34.7, south: 34.6, east: -84.3, west: -84.5 };
const map = { getMaxZoom: () => 18 };
const image = new Blob(["png"], { type: "image/png" });

beforeEach(() => {
  missionBounds.mockReset().mockReturnValue(MISSION);
  shownBounds.mockReset().mockReturnValue(SHOWN);
  captureOverlay.mockReset().mockResolvedValue({ blob: image, bounds: { north: 34.76, south: 34.54, east: -84.34, west: -84.56 }, width: 3200, height: 2400 });
  api.post.mockReset().mockResolvedValue({ data: new Blob(["tif"]) });
  downloadBlob.mockReset();
});

const open = (props = {}) => {
  const onClose = vi.fn();
  const notify = vi.fn();
  render(<MissionOverlayDialog map={map} defaultName="OP DK" onClose={onClose} notify={notify} {...props} />);
  return { onClose, notify };
};

describe("the mission overlay dialog", () => {
  it("draws the whole mission and sends it with its corners, then saves the file under the layer's name", async () => {
    const { onClose, notify } = open();
    fireEvent.click(screen.getByRole("radio", { name: /GeoTIFF/ }));
    fireEvent.change(screen.getByLabelText("Layer name"), { target: { value: "OP DK / night" } });
    fireEvent.click(screen.getByRole("button", { name: /Download \.tif/ }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(captureOverlay).toHaveBeenCalledWith(map, MISSION, "standard");
    const [url, form, options] = api.post.mock.calls[0];
    expect(url).toBe("/overlay-image");
    expect(options).toEqual({ responseType: "blob" });
    expect(form.get("image")).toBeInstanceOf(Blob);
    expect(form.get("format")).toBe("geotiff");
    expect(form.get("name")).toBe("OP DK night");
    expect(["north", "south", "east", "west"].map((key) => form.get(key))).toEqual(["34.76", "34.54", "-84.34", "-84.56"]);
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), "OP DK night.tif");
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ tone: "ok" }));
  });

  it("offers the map as shown when nothing is planned, and draws that", async () => {
    missionBounds.mockReturnValue(null);
    open();
    expect(screen.getByRole("radio", { name: /The whole mission/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /The map as shown/ })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: /Download \.mbtiles/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    expect(captureOverlay).toHaveBeenCalledWith(map, SHOWN, "standard");
    expect(api.post.mock.calls[0][1].get("format")).toBe("mbtiles");
  });

  it("says what went wrong in its own words and stays open", async () => {
    const refusal = new Blob([JSON.stringify({ error: "The image is too large." })], { type: "application/json" });
    api.post.mockRejectedValue(Object.assign(new Error("413"), { response: { status: 413, data: refusal } }));
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The image is too large.");
    expect(onClose).not.toHaveBeenCalled();
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it("tells a drawing that failed at High detail to try Standard", async () => {
    captureOverlay.mockRejectedValue(new Error("canvas"));
    open();
    fireEvent.click(screen.getByRole("radio", { name: /High/ }));
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose Standard detail");
    expect(captureOverlay).toHaveBeenCalledWith(map, MISSION, "high");
    expect(api.post).not.toHaveBeenCalled();
  });

  it("offers no High detail where Standard is already the deepest zoom", () => {
    missionBounds.mockReturnValue({ north: 34.6, south: 34.6, east: -84.4, west: -84.4 });
    open();
    expect(screen.getByRole("radio", { name: /High/ })).toBeDisabled();
    expect(screen.getByText(/Standard is already as sharp/)).toBeInTheDocument();
  });
});
