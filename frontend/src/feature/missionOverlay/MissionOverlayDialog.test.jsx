import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { downloadUrl } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, piecesWithContent, shownBounds } from "./captureOverlay";
import MissionOverlayDialog from "./MissionOverlayDialog";
import { overlayPlans, USGS_MAX_ZOOM } from "./overlayPlan";

vi.mock("../auth/api", () => ({
  default: { post: vi.fn(), get: vi.fn(), delete: vi.fn(), defaults: { baseURL: "https://api.test/api" } },
}));
vi.mock("./captureOverlay", () => ({
  captureOverlay: vi.fn(),
  missionBounds: vi.fn(),
  piecesWithContent: vi.fn(),
  shownBounds: vi.fn(),
}));
vi.mock("../../utils/fileDownload", async (importOriginal) => ({ ...(await importOriginal()), downloadUrl: vi.fn() }));

const MISSION = { north: 34.75, south: 34.55, east: -84.35, west: -84.55 };
const SHOWN = { north: 34.7, south: 34.6, east: -84.3, west: -84.5 };
const LZ = { north: 34.6005, south: 34.5995, east: -84.4, west: -84.401 };
const map = { getMaxZoom: () => 18 };
const DOWNLOAD = "/overlay-jobs/j1/file/token";

const everyPiece = (plan) => {
  const keys = new Set();
  for (let row = 0; row < plan.rows; row += 1) for (let column = 0; column < plan.columns; column += 1) keys.add(`${column},${row}`);
  return keys;
};

let pieceBytes;
let polls;

// The server: a job made, its pieces taken, started, then told as `polls` says, one answer a poll, the last repeated.
const serve = () => {
  api.post.mockReset().mockImplementation(async (url) => {
    if (url === "/overlay-jobs") return { data: { id: "j1", state: "receiving" } };
    if (url === "/overlay-jobs/j1/pieces") return { data: { pieces: 1 } };
    if (url === "/overlay-jobs/j1/start") return { data: { id: "j1", state: "queued" } };
    throw new Error(`unexpected ${url}`);
  });
  api.get.mockReset().mockImplementation(async () => ({ data: polls.length > 1 ? polls.shift() : polls[0] }));
  api.delete.mockReset().mockResolvedValue({ data: { cancelled: true } });
};

beforeEach(() => {
  pieceBytes = 3;
  polls = [{ id: "j1", state: "done", download: DOWNLOAD }];
  missionBounds.mockReset().mockReturnValue(MISSION);
  shownBounds.mockReset().mockReturnValue(SHOWN);
  piecesWithContent.mockReset().mockImplementation((_, plan) => everyPiece(plan));
  captureOverlay.mockReset().mockImplementation(async (_, plan, { pieces, onPiece, onDrawn }) => {
    let done = 0;
    for (const key of pieces) {
      onPiece?.(done, pieces.size);
      const [column, row] = key.split(",").map(Number);
      await onDrawn({ column, row, blob: new Blob(["x".repeat(pieceBytes)], { type: "image/png" }) });
      done += 1;
    }
    return [];
  });
  serve();
  downloadUrl.mockReset();
});

const open = (props = {}) => {
  const onClose = vi.fn();
  const notify = vi.fn();
  const view = render(
    <MissionOverlayDialog map={map} defaultName="OP DK" mapStyle="satellite" onClose={onClose} notify={notify} pollMs={1} {...props} />,
  );
  return { onClose, notify, ...view };
};

const calls = (url) => api.post.mock.calls.filter(([called]) => called === url);
const order = () => calls("/overlay-jobs")[0][1];
const area = (form) => Object.fromEntries(["zoom", "left", "top", "width", "height"].map((key) => [key, Number(form.get(key))]));
const sentPieces = () => calls("/overlay-jobs/j1/pieces").flatMap(([, form]) => [...form.keys()]);

describe("the mission overlay dialog", () => {
  it("makes a job for the whole mission as finely as a GeoTIFF holds, sends its pieces, and downloads the file it made", async () => {
    const { onClose, notify } = open();
    fireEvent.click(screen.getByRole("radio", { name: /GeoTIFF/ }));
    fireEvent.change(screen.getByLabelText("Layer name"), { target: { value: "OP DK / night" } });
    fireEvent.click(screen.getByRole("button", { name: /Download \.tif/ }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const plan = overlayPlans(MISSION, { format: "geotiff", base: "none", mapMaxZoom: 18 }).finest;
    expect(order().get("format")).toBe("geotiff");
    expect(order().get("base")).toBe("none");
    expect(order().get("name")).toBe("OP DK night");
    expect(area(order())).toEqual({ zoom: plan.zoom, left: plan.left, top: plan.top, width: plan.width, height: plan.height });
    expect(captureOverlay).toHaveBeenCalledWith(map, plan, expect.objectContaining({ pieces: everyPiece(plan), signal: expect.anything() }));
    expect(sentPieces()).toHaveLength(plan.pieces);
    expect(sentPieces()[0]).toBe("piece-0-0");
    expect(calls("/overlay-jobs/j1/start")).toHaveLength(1);
    expect(downloadUrl).toHaveBeenCalledWith(`https://api.test/api${DOWNLOAD}`, "OP DK night.tif");
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ tone: "ok" }));
    expect(api.delete).not.toHaveBeenCalled();
  });

  it("draws only the pieces with something in them, and says so", async () => {
    piecesWithContent.mockReturnValue(new Set(["0,0"]));
    const { onClose } = open();
    const plan = overlayPlans(MISSION, { format: "mbtiles", base: "none", mapMaxZoom: 18 }).finest;
    expect(plan.pieces).toBeGreaterThan(1);
    expect(screen.getByText(new RegExp(`Draws 1 of its ${plan.pieces} pieces of map`))).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(sentPieces()).toEqual(["piece-0-0"]);
  });

  it("sends the pieces in batches under a request's size", async () => {
    pieceBytes = 10 * 1024 * 1024;
    piecesWithContent.mockReturnValue(new Set(["0,0", "1,0", "0,1", "1,1", "2,1"]));
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(calls("/overlay-jobs/j1/pieces").map(([, form]) => [...form.keys()].length)).toEqual([2, 2, 1]);
  });

  it("puts the USGS topo map under it at the map's own level, and says how large and how long", async () => {
    missionBounds.mockReturnValue(LZ);
    open({ mapStyle: "usgs-topo" });
    expect(screen.getByRole("radio", { name: /^USGS topo/ })).toBeChecked();
    expect(screen.getByText(/The USGS topo map has no more detail than this/)).toBeInTheDocument();
    expect(screen.getByText(/tiles, about \d+ MB; the server takes (under a minute|about)/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Download \.mbtiles/ }));
    await waitFor(() => expect(calls("/overlay-jobs")).toHaveLength(1));
    expect(order().get("base")).toBe("usgs-topo");
    expect(area(order()).zoom).toBe(USGS_MAX_ZOOM);
  });

  it("starts with no base map over any other map, and takes a lighter detail when asked", async () => {
    open();
    expect(screen.getByRole("radio", { name: /^None/ })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: /Lighter/ }));
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    await waitFor(() => expect(calls("/overlay-jobs")).toHaveLength(1));
    const plans = overlayPlans(MISSION, { format: "mbtiles", base: "none", mapMaxZoom: 18 });
    expect(area(order()).zoom).toBe(plans.lighter.zoom);
    expect(plans.lighter.zoom).toBe(plans.finest.zoom - 1);
  });

  it("offers the map as shown when nothing is planned, and draws that", async () => {
    missionBounds.mockReturnValue(null);
    open();
    expect(screen.getByRole("radio", { name: /The whole mission/ })).toBeDisabled();
    expect(screen.getByRole("radio", { name: /The map as shown/ })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: /Download \.mbtiles/ }));
    await waitFor(() => expect(captureOverlay).toHaveBeenCalled());
    const plan = overlayPlans(SHOWN, { format: "mbtiles", base: "none", mapMaxZoom: 18 }).finest;
    expect(captureOverlay).toHaveBeenCalledWith(map, plan, expect.anything());
    expect(order().get("format")).toBe("mbtiles");
  });

  it("says which piece it is drawing, and a stop stops the job on the server", async () => {
    captureOverlay.mockImplementation(async (_, plan, { onPiece, signal }) => {
      onPiece(1, 6);
      await new Promise((resolve, reject) => signal.addEventListener("abort", () => reject(new DOMException("stop", "AbortError"))));
    });
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByText("Drawing piece 2 of 6…")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Pieces of the overlay drawn" })).toHaveAttribute("aria-valuenow", "1");
    fireEvent.click(screen.getByRole("button", { name: "Stop" }));
    await waitFor(() => expect(api.delete).toHaveBeenCalledWith("/overlay-jobs/j1"));
    expect(await screen.findByRole("button", { name: /Download/ })).toBeEnabled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(downloadUrl).not.toHaveBeenCalled();
  });

  it("says how the job stands on the server as it is made", async () => {
    polls = [
      { id: "j1", state: "queued", ahead: 2, done: 0, total: 0 },
      { id: "j1", state: "running", stage: "writing", done: 1200, total: 41500 },
    ];
    missionBounds.mockReturnValue(LZ);
    open({ mapStyle: "usgs-topo" });
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect((await screen.findAllByText("Waiting for 2 other overlays to be made…")).length).toBeGreaterThan(0);
    expect((await screen.findAllByText("Writing tile 1,200 of 41,500")).length).toBeGreaterThan(0);
    expect(screen.getByRole("progressbar", { name: "The overlay being made" })).toHaveAttribute("aria-valuenow", "1200");
    await act(async () => {
      polls = [{ id: "j1", state: "done", download: DOWNLOAD }];
    });
    await waitFor(() => expect(downloadUrl).toHaveBeenCalled());
  });

  it("says why the server could not make it, and downloads nothing", async () => {
    polls = [{ id: "j1", state: "failed", code: "base_unavailable", error: "The USGS topo map could not be fetched. Try again." }];
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The USGS topo map could not be fetched. Try again.");
    expect(onClose).not.toHaveBeenCalled();
    expect(downloadUrl).not.toHaveBeenCalled();
    expect(api.delete).not.toHaveBeenCalled();
  });

  it("says what the server refused in its own words and stays open", async () => {
    api.post.mockRejectedValue(
      Object.assign(new Error("503"), { isAxiosError: true, response: { status: 503, data: { code: "busy", error: "Too many overlays are being made. Try again in a few minutes." } } }),
    );
    const { onClose } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many overlays are being made.");
    expect(onClose).not.toHaveBeenCalled();
    expect(captureOverlay).not.toHaveBeenCalled();
  });

  it("says a job the server lost was lost", async () => {
    api.get.mockRejectedValue(Object.assign(new Error("404"), { isAxiosError: true, response: { status: 404, data: { error: "Not found" } } }));
    open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The server lost the overlay");
  });

  it("says when the map could not be drawn, and lets go of the job", async () => {
    captureOverlay.mockRejectedValue(new Error("The map could not be set to a piece of the overlay."));
    open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The map could not be drawn into the overlay. Try again.");
    expect(calls("/overlay-jobs/j1/start")).toHaveLength(0);
    expect(api.delete).toHaveBeenCalledWith("/overlay-jobs/j1");
  });

  it("stops the job on the server when it closes part way", async () => {
    polls = [{ id: "j1", state: "running", stage: "fetching", done: 1, total: 10 }];
    const { unmount } = open();
    fireEvent.click(screen.getByRole("button", { name: /Download/ }));
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    unmount();
    expect(api.delete).toHaveBeenCalledWith("/overlay-jobs/j1");
  });
});
