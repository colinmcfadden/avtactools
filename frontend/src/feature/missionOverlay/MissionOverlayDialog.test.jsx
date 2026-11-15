import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { downloadBlob } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, shownBounds } from "./captureOverlay";
import MissionOverlayDialog from "./MissionOverlayDialog";
import { overlayPlans, USGS_MAX_ZOOM } from "./overlayPlan";

vi.mock("../auth/api", () => ({ default: { post: vi.fn() } }));
vi.mock("./captureOverlay", () => ({ captureOverlay: vi.fn(), missionBounds: vi.fn(), shownBounds: vi.fn() }));
vi.mock("../../utils/fileDownload", async (importOriginal) => ({ ...(await importOriginal()), downloadBlob: vi.fn() }));

const MISSION = { north: 34.75, south: 34.55, east: -84.35, west: -84.55 };
const SHOWN = { north: 34.7, south: 34.6, east: -84.3, west: -84.5 };
const LZ = { north: 34.6005, south: 34.5995, east: -84.4, west: -84.401 };
const map = { getMaxZoom: () => 18 };

// What captureOverlay gives back for a plan: a PNG for each of its pieces, row by row.
const piecesOf = (plan) => {
  const pieces = [];
  for (let row = 0; row < plan.rows; row += 1) {
    for (let column = 0; column < plan.columns; column += 1) pieces.push({ column, row, blob: new Blob(["png"], { type: "image/png" }) });
  }
  return pieces;
};

beforeEach(() => {
  missionBounds.mockReset().mockReturnValue(MISSION);
  shownBounds.mockReset().mockReturnValue(SHOWN);
  captureOverlay.mockReset().mockImplementation(async (_, plan) => piecesOf(plan));
  api.post.mockReset().mockResolvedValue({ data: new Blob(["tif"]) });
  downloadBlob.mockReset();
});

const open = (props = {}) => {
  const onClose = vi.fn();
  const notify = vi.fn();
  render(<MissionOverlayDialog map={map} defaultName="OP DK" mapStyle="satellite" onClose={onClose} notify={notify} {...props} />);
  return { onClose, notify };
};

const area = (form) => Object.fromEntries(["zoom", "left", "top", "width", "height"].map((key) => [key, Number(form.get(key))]));

describe("the mission overlay dialog", () => {
  it("draws the whole mission as finely as a GeoTIFF holds, sends its pieces and where they lie, and saves the file", async () => {
    const { onClose, notify } = open();
    fireEvent.click(screen.getByRole("radio", { name: /GeoTIFF/ }));
    fireEvent.change(screen.getByLabelText("Layer name"), { target: { value: "OP DK / night" } });
    fireEvent.click(screen.getByRole("button", { name: /Download \.tif/ }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const plan = overlayPlans(MISSION, { format: "geotiff", base: "none", mapMaxZoom: 18 }).finest;
    expect(captureOverlay).toHaveBeenCalledWith(map, plan, expect.objectContaining({ onPiece: expect.any(Function) }));
    const [url, form, options] = api.post.mock.calls[0];
    expect(url).toBe("/overlay-image");
    expect(options).toEqual({ responseType: "blob" });
    expect(form.get("format")).toBe("geotiff");
    expect(form.get("base")).toBe("none");
    expect(form.get("name")).toBe("OP DK night");
    expect(area(form)).toEqual({ zoom: plan.zoom, left: plan.left, top: plan.top, width: plan.width, height: plan.height });
    const sent = [...form.keys()].filter((key) => key.startsWith("piece-"));
    expect(sent).toHaveLength(plan.pieces);
    expect(sent[0]).toBe("piece-0-0");
    expect(form.get(`piece-${plan.columns - 1}-${plan.rows - 1}`)).toBeInstanceOf(Blob);
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), "OP DK night.tif");
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ tone: "ok" }));
  });

  it("puts the USGS topo map under it when that is the map on the screen, and says it goes no finer", async () => {
    missionBounds.mockReturnValue(LZ);
    open({ mapStyle: "usgs-topo" });
    expect(screen.getByRole("radio", { name: /^USGS topo/ })).toBeChecked();
    expect(screen.getByText(/The USGS topo map has no more detail than this/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Download \.mbtiles/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    const form = api.post.mock.calls[0][1];
    expect(form.get("base")).toBe("usgs-topo");
    expect(area(form).zoom).toBe(USGS_MAX_ZOOM);
  });

  it("starts with no base map over any other map, and takes a lighter detail when asked", async () => {
    open();
    expect(screen.getByRole("radio", { name: /^None/ })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: /Lighter/ }));
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    const plans = overlayPlans(MISSION, { format: "mbtiles", base: "none", mapMaxZoom: 18 });
    expect(area(api.post.mock.calls[0][1]).zoom).toBe(plans.lighter.zoom);
    expect(plans.lighter.zoom).toBe(plans.finest.zoom - 1);
  });

  it("offers the map as shown when nothing is planned, and draws that", async () => {
    missionBounds.mockReturnValue(null);
    open();
    expect(screen.getByRole("radio", { name: /The whole mission/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /The map as shown/ })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: /Download \.mbtiles/ }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    const plan = overlayPlans(SHOWN, { format: "mbtiles", base: "none", mapMaxZoom: 18 }).finest;
    expect(captureOverlay).toHaveBeenCalledWith(map, plan, expect.anything());
    expect(api.post.mock.calls[0][1].get("format")).toBe("mbtiles");
  });

  it("says which piece it is drawing", async () => {
    let finish;
    captureOverlay.mockImplementation(async (_, plan, { onPiece }) => {
      onPiece(1, 6);
      await new Promise((resolve) => {
        finish = resolve;
      });
      return piecesOf(plan);
    });
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByText("Drawing piece 2 of 6…")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Pieces of the overlay drawn" })).toHaveAttribute("aria-valuenow", "1");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    await act(async () => finish());
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("refuses to send more than the server takes, and says what to choose", async () => {
    captureOverlay.mockResolvedValue([{ column: 0, row: 0, blob: { size: 95 * 1024 * 1024 } }]);
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("comes to 95 MB, more than can be sent. Choose less detail or a smaller area.");
    expect(api.post).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("says what went wrong in its own words and stays open", async () => {
    const refusal = new Blob([JSON.stringify({ error: "The USGS topo map could not be fetched. Try again." })], { type: "application/json" });
    api.post.mockRejectedValue(Object.assign(new Error("502"), { response: { status: 502, data: refusal } }));
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The USGS topo map could not be fetched. Try again.");
    expect(onClose).not.toHaveBeenCalled();
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it("says when the map could not be drawn, and sends nothing", async () => {
    captureOverlay.mockRejectedValue(new Error("The map could not be set to a piece of the overlay."));
    open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The map could not be drawn into the overlay. Try again.");
    expect(api.post).not.toHaveBeenCalled();
  });
});
