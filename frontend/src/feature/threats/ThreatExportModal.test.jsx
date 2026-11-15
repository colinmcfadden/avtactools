import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import api from "../auth/api";
import ThreatExportModal from "./ThreatExportModal";

vi.mock("../auth/api", () => ({ default: { post: vi.fn(), defaults: { baseURL: "http://127.0.0.1:5000/api" } } }));
vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn().mockResolvedValue(undefined) } }));

const threat = (name) => ({ id: name, name, milstdId: "SHGPEWMAI------", lat: 34.6, lon: -84.2, radars: [], visible: true });
const SETS = [
  { id: "a", name: "NORTH", threats: [threat("SA-8"), threat("ZSU-23")] },
  { id: "b", name: "EMPTY", threats: [] },
  { id: "c", name: "SOUTH", threats: [threat("SA-15")] },
];

beforeEach(() => {
  api.post.mockReset().mockResolvedValue({ data: { downloadPath: "/api/threats-kmz/abc", expiresInSeconds: 600, maxDownloads: 1 } });
});

describe("exporting threats", () => {
  it("sends every open set as one file by default, as the format chosen", async () => {
    const onDownload = vi.fn().mockResolvedValue(undefined);
    render(<ThreatExportModal sets={SETS} onDownload={onDownload} onDownloadThs={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByText("3 threats in 2 sets, each set a folder in the file.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: /KML/ }));
    fireEvent.click(screen.getByRole("button", { name: /Download \.kml/ }));
    await waitFor(() => expect(onDownload).toHaveBeenCalledWith({ setId: null, format: "kml" }));
    // The QR code's file holds both sets, each a folder.
    await waitFor(() => expect(api.post).toHaveBeenCalledWith("/threats-kmz-link", expect.anything()));
    expect(api.post.mock.calls[0][1].sets.map((set) => set.name)).toEqual(["NORTH", "SOUTH"]);
  });

  it("sends one set alone when asked, and its QR code holds that set", async () => {
    const onDownloadThs = vi.fn().mockResolvedValue(undefined);
    render(<ThreatExportModal sets={SETS} initialSetId="c" onDownload={vi.fn()} onDownloadThs={onDownloadThs} onClose={vi.fn()} />);
    expect(screen.getByLabelText("Send")).toHaveValue("c");
    fireEvent.click(screen.getByRole("button", { name: /Download \.ths/ }));
    await waitFor(() => expect(onDownloadThs).toHaveBeenCalledWith({ setId: "c" }));
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    expect(api.post.mock.calls.at(-1)[1].sets.map((set) => set.name)).toEqual(["SOUTH"]);

    fireEvent.change(screen.getByLabelText("Send"), { target: { value: "" } });
    await waitFor(() => expect(api.post.mock.calls.at(-1)[1].sets).toHaveLength(2));
  });

  it("names the only set with threats rather than offering a choice", () => {
    render(<ThreatExportModal sets={[SETS[0], SETS[1]]} onDownload={vi.fn()} onClose={vi.fn()} />);
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.getByText("NORTH")).toBeInTheDocument();
  });

  it("says what went wrong and keeps the dialog", async () => {
    const onDownload = vi.fn().mockRejectedValue(Object.assign(new Error("500"), { response: { status: 500, data: { error: "Too many threats." } } }));
    render(<ThreatExportModal sets={SETS} onDownload={onDownload} onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /Download \.kmz/ }));
    expect(await screen.findByText("Too many threats.")).toBeInTheDocument();
  });
});
