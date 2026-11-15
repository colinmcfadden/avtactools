import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import QRCode from "qrcode";
import api from "../auth/api";
import ForeFlightModal from "./ForeFlightModal";
import {
  buildForeFlightRouteString,
  buildForeFlightUrl,
  buildFplXml,
  buildGpxXml,
  buildSetGpxXml,
  downloadFpl,
  downloadGpx,
  downloadSetGpx,
} from "./foreflight";

vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn() } }));
vi.mock("../auth/api", () => ({ default: { post: vi.fn(), defaults: { baseURL: "http://api.test/api" } } }));
vi.mock("./foreflight", async (importOriginal) => ({
  ...(await importOriginal()),
  downloadGpx: vi.fn(),
  downloadFpl: vi.fn(),
  downloadSetGpx: vi.fn(),
}));

const point = (id, lat, lon, name = "") => ({ id, lat, lon, name, kind: name ? "amps" : "shaping" });
const ARIEL = { id: "a", name: "ARIEL", points: [point("a1", 34.78382, -84.08219, "SP"), point("a2", 34.8, -84.1), point("a3", 35.0, -84.3, "TGT")] };
const BEEMIN = { id: "b", name: "BEEMIN", visible: false, points: [point("b1", 35.0, -84.3, "TGT"), point("b2", 34.78382, -84.08219, "LZ1")] };
const STUB = { id: "c", name: "STUB", points: [point("c1", 34.5, -84.5, "ONLY")] };
const MULTI = { name: "260818 Multi", routes: [ARIEL, BEEMIN] };

const SHARE_URL = "http://api.test/r/tok";

/** What the QR code on the screen says. */
const qrText = () => QRCode.toCanvas.mock.calls.at(-1)?.[1];
const tooLargeForTheDirectLink = () =>
  QRCode.toCanvas.mockImplementation((canvas, text) => (text.startsWith("foreflightmobile://") ? Promise.reject(new Error("too much data")) : Promise.resolve()));

beforeEach(() => {
  vi.clearAllMocks();
  QRCode.toCanvas.mockImplementation(() => Promise.resolve());
  api.post.mockResolvedValue({ data: { sharePath: "/r/tok", expiresInSeconds: 1800 } });
});

describe("the export screen of a route", () => {
  it("is what it was: the direct QR code, both files and the route text", async () => {
    const onClose = vi.fn();
    render(<ForeFlightModal route={ARIEL} onClose={onClose} />);
    const dialog = screen.getByRole("dialog", { name: "Send to ForeFlight — ARIEL" });
    await screen.findByText(/it will offer to open the route directly in ForeFlight/);
    expect(qrText()).toBe(buildForeFlightUrl(ARIEL));
    expect(api.post).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("Send")).toBeNull();

    fireEvent.click(within(dialog).getByRole("button", { name: "Download GPX" }));
    expect(downloadGpx).toHaveBeenCalledWith(ARIEL);
    fireEvent.click(within(dialog).getByRole("button", { name: "Download .fpl" }));
    expect(downloadFpl).toHaveBeenCalledWith(ARIEL);
    expect(downloadSetGpx).not.toHaveBeenCalled();
    expect(within(dialog).getByText(buildForeFlightRouteString(ARIEL))).toBeInTheDocument();
    expect(within(dialog).getByText("Route files (import into ForeFlight)")).toBeInTheDocument();

    // The × in the bar and Close at the foot.
    within(dialog).getAllByRole("button", { name: "Close" }).forEach((button) => fireEvent.click(button));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("too large for the direct link goes to a download link of its GPX and its flight plan", async () => {
    tooLargeForTheDirectLink();
    render(<ForeFlightModal route={ARIEL} onClose={() => {}} />);
    await screen.findByText(/This route is large, so the QR opens a page to download the route file \(GPX or \.fpl\)/);
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith("/route-share", { name: "ARIEL", gpx: buildGpxXml(ARIEL), fpl: expect.any(String) });
    expect(api.post.mock.calls[0][1].fpl).toContain("<route-name>ARIEL</route-name>");
    expect(qrText()).toBe(SHARE_URL);
  });

  it("says so when that link cannot be made, in the words it always used", async () => {
    tooLargeForTheDirectLink();
    api.post.mockRejectedValue({ response: { status: 401 } });
    const { unmount } = render(<ForeFlightModal route={ARIEL} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("This route has too many points for a direct QR code. Sign in to generate a scannable download link — or use the file downloads below.");
    unmount();
    api.post.mockRejectedValue(new Error("offline"));
    render(<ForeFlightModal route={ARIEL} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("This route has too many points for a direct QR code. Use the file downloads below to import it into ForeFlight.");
  });

  it("is nothing with nothing to show", () => {
    const { container } = render(<ForeFlightModal onClose={() => {}} />);
    expect(container).toBeEmptyDOMElement();
    expect(QRCode.toCanvas).not.toHaveBeenCalled();
  });
});

describe("the export screen of a whole route set", () => {
  it("is the same screen, named for the set, and sends every route as one GPX file", async () => {
    render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    const dialog = screen.getByRole("dialog", { name: "Send to ForeFlight — 260818 Multi" });
    await screen.findByText(/it opens a page to download all 2 routes as one GPX file/);

    // The QR code is the download link: ForeFlight's own link holds one route.
    expect(api.post).toHaveBeenCalledTimes(1);
    const [url, body] = api.post.mock.calls[0];
    expect(url).toBe("/route-share");
    // No flight plan: a .fpl holds one route.
    expect(Object.keys(body)).toEqual(["name", "gpx"]);
    expect(body).toEqual({ name: "260818 Multi", gpx: buildSetGpxXml([ARIEL, BEEMIN]) });
    // A route hidden on the map is in it like any other.
    expect(body.gpx).toContain("<name>BEEMIN</name>");
    expect(qrText()).toBe(SHARE_URL);
    expect(QRCode.toCanvas.mock.calls.every((call) => !call[1].startsWith("foreflightmobile://"))).toBe(true);

    fireEvent.click(within(dialog).getByRole("button", { name: "Download GPX · 2 routes" }));
    expect(downloadSetGpx).toHaveBeenCalledWith({ name: "260818 Multi", routes: [ARIEL, BEEMIN] });
    expect(downloadGpx).not.toHaveBeenCalled();

    // What holds one route is not offered for two.
    expect(within(dialog).queryByRole("button", { name: "Download .fpl" })).toBeNull();
    expect(within(dialog).queryByText(/Route text/)).toBeNull();
    expect(within(dialog).getByText("Route set file (import into ForeFlight)")).toBeInTheDocument();
    expect(within(dialog).getByText(/All 2 routes are in the one GPX file, hidden on the map or not/)).toBeInTheDocument();
  });

  it("offers each of its routes on its own, for what holds one route", async () => {
    render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    const dialog = screen.getByRole("dialog");
    const picker = within(dialog).getByLabelText("Send");
    expect([...picker.options].map((option) => option.textContent)).toEqual(["All 2 routes, as one GPX file", "ARIEL only", "BEEMIN only"]);
    await waitFor(() => expect(qrText()).toBe(SHARE_URL));

    fireEvent.change(picker, { target: { value: "1" } });
    await screen.findByText(/it will offer to open the route directly in ForeFlight/);
    expect(qrText()).toBe(buildForeFlightUrl(BEEMIN));
    fireEvent.click(within(dialog).getByRole("button", { name: "Download GPX" }));
    expect(downloadGpx).toHaveBeenCalledWith(BEEMIN);
    fireEvent.click(within(dialog).getByRole("button", { name: "Download .fpl" }));
    expect(downloadFpl).toHaveBeenCalledWith(BEEMIN);
    expect(within(dialog).getByText(buildForeFlightRouteString(BEEMIN))).toBeInTheDocument();
    expect(downloadSetGpx).not.toHaveBeenCalled();
    // The title is still the set's.
    expect(screen.getByRole("dialog", { name: "Send to ForeFlight — 260818 Multi" })).toBe(dialog);

    // Back to the whole set: the link made a moment ago is the one shown, not a second one.
    fireEvent.change(picker, { target: { value: "-1" } });
    await screen.findByText(/all 2 routes as one GPX file/);
    expect(qrText()).toBe(SHARE_URL);
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(within(dialog).queryByRole("button", { name: "Download .fpl" })).toBeNull();
  });

  it("a route of the set too large for the direct link gets a link of its own files", async () => {
    tooLargeForTheDirectLink();
    render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Send"), { target: { value: "0" } });
    await screen.findByText(/This route is large/);
    expect(api.post).toHaveBeenCalledTimes(2);
    expect(api.post.mock.calls[1][1]).toEqual({ name: "ARIEL", gpx: buildGpxXml(ARIEL), fpl: expect.stringContaining("<flight-plan") });
  });

  it("another set starts whole again", async () => {
    const other = { name: "OTHER", routes: [BEEMIN, ARIEL] };
    const { rerender } = render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    fireEvent.change(screen.getByLabelText("Send"), { target: { value: "1" } });
    expect(screen.getByLabelText("Send")).toHaveValue("1");
    rerender(<ForeFlightModal set={other} onClose={() => {}} />);
    expect(screen.getByLabelText("Send")).toHaveValue("-1");
    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith("/route-share", { name: "OTHER", gpx: buildSetGpxXml([BEEMIN, ARIEL]) }));
    expect(screen.getByRole("button", { name: "Download GPX · 2 routes" })).toBeInTheDocument();
  });

  it.each([
    [{ response: { status: 401 } }, "A whole set's QR code is a link to its file. Sign in to make one, or use Download GPX below: it is the same file."],
    [{ response: { status: 413 } }, "This set is too large for a download link. Use Download GPX below."],
    [new Error("offline"), "A whole set's QR code is a link to its file, and the link could not be made. Use Download GPX below: it is the same file."],
    [{ response: { status: 403 } }, "A whole set's QR code is a link to its file, and the link could not be made. Use Download GPX below: it is the same file."],
  ])("says why there is no QR code when the link cannot be made, and still downloads the file", async (failure, words) => {
    api.post.mockRejectedValue(failure);
    render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    fireEvent.click(screen.getByRole("button", { name: "Download GPX · 2 routes" }));
    expect(downloadSetGpx).toHaveBeenCalledWith({ name: "260818 Multi", routes: [ARIEL, BEEMIN] });
  });

  it("an answer with no link in it is a link that could not be made", async () => {
    api.post.mockResolvedValue({ data: {} });
    render(<ForeFlightModal set={MULTI} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("the link could not be made");
  });
});

describe("a set of one route", () => {
  it("is offered everything a route is, its file named for the set", async () => {
    const set = { name: "SKETCHED ROUTES", routes: [ARIEL] };
    render(<ForeFlightModal set={set} onClose={() => {}} />);
    const dialog = screen.getByRole("dialog", { name: "Send to ForeFlight — SKETCHED ROUTES" });
    await screen.findByText(/it will offer to open the route directly in ForeFlight/);
    expect(qrText()).toBe(buildForeFlightUrl(ARIEL));
    expect(api.post).not.toHaveBeenCalled();
    expect(within(dialog).queryByLabelText("Send")).toBeNull();

    fireEvent.click(within(dialog).getByRole("button", { name: "Download GPX" }));
    expect(downloadSetGpx).toHaveBeenCalledWith({ name: "SKETCHED ROUTES", routes: [ARIEL] });
    fireEvent.click(within(dialog).getByRole("button", { name: "Download .fpl" }));
    expect(downloadFpl).toHaveBeenCalledWith(ARIEL);
    expect(within(dialog).getByText(buildForeFlightRouteString(ARIEL))).toBeInTheDocument();
  });

  it("too large for the direct link shares that route's own files", async () => {
    tooLargeForTheDirectLink();
    render(<ForeFlightModal set={{ name: "ONE", routes: [ARIEL] }} onClose={() => {}} />);
    await screen.findByText(/This route is large/);
    expect(api.post).toHaveBeenCalledWith("/route-share", { name: "ARIEL", gpx: buildGpxXml(ARIEL), fpl: buildFplXmlWithAnyTime(ARIEL) });
  });
});

// A flight plan carries the time it was made; everything else of it is the route's.
const buildFplXmlWithAnyTime = (route) => {
  const [head, tail] = buildFplXml(route).split(/<created>.*<\/created>/);
  return expect.stringMatching(new RegExp(`^${escapeRegExp(head)}<created>.*</created>${escapeRegExp(tail)}$`));
};
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

describe("a set with a route too short to send", () => {
  it("leaves it out of the file and the choices, and says which", async () => {
    render(<ForeFlightModal set={{ name: "THREE", routes: [ARIEL, STUB, BEEMIN] }} onClose={() => {}} />);
    expect(screen.getByRole("note")).toHaveTextContent("Left out: STUB. A route needs two points to be sent.");
    expect([...screen.getByLabelText("Send").options].map((option) => option.textContent)).toEqual(["All 2 routes, as one GPX file", "ARIEL only", "BEEMIN only"]);
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    const { gpx } = api.post.mock.calls[0][1];
    expect(gpx).toBe(buildSetGpxXml([ARIEL, BEEMIN]));
    expect(gpx).not.toContain("STUB");
    fireEvent.click(screen.getByRole("button", { name: "Download GPX · 2 routes" }));
    expect(downloadSetGpx).toHaveBeenCalledWith({ name: "THREE", routes: [ARIEL, BEEMIN] });
  });

  it("with one route left is that route's screen, and still says what was left out", async () => {
    render(<ForeFlightModal set={{ name: "TWO", routes: [STUB, BEEMIN] }} onClose={() => {}} />);
    expect(screen.getByRole("note")).toHaveTextContent("Left out: STUB.");
    expect(screen.queryByLabelText("Send")).toBeNull();
    await waitFor(() => expect(qrText()).toBe(buildForeFlightUrl(BEEMIN)));
    expect(screen.getByRole("button", { name: "Download .fpl" })).toBeInTheDocument();
  });

  it("with none left has nothing to send, and says so", async () => {
    render(<ForeFlightModal set={{ name: "STUBS", routes: [STUB, { id: "d", name: "EMPTY", points: [] }] }} onClose={() => {}} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No route of this set has two points yet, so there is nothing to send.");
    expect(screen.getByRole("button", { name: "Download GPX" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Download .fpl" })).toBeNull();
    expect(api.post).not.toHaveBeenCalled();
    expect(QRCode.toCanvas).not.toHaveBeenCalled();
  });
});

describe("names that XML would read as markup", () => {
  it("are shown as they are, sent as they are, and escaped in the file", async () => {
    const amp = { id: "x", name: 'A&B <C> "D"', points: [point("x1", 34.5, -84.5, "R&D <site>"), point("x2", 34.6, -84.6, "X")] };
    const tag = { id: "y", name: "</rte><rte>", points: [point("y1", 1, 1, "<name>"), point("y2", 2, 2, "Y")] };
    const set = { name: 'OP "A&B" <1>', routes: [amp, tag] };
    render(<ForeFlightModal set={set} onClose={() => {}} />);
    expect(screen.getByRole("dialog", { name: 'Send to ForeFlight — OP "A&B" <1>' })).toBeInTheDocument();
    expect([...screen.getByLabelText("Send").options].map((option) => option.textContent)).toEqual(["All 2 routes, as one GPX file", 'A&B <C> "D" only', "</rte><rte> only"]);
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    const body = api.post.mock.calls[0][1];
    expect(body.name).toBe('OP "A&B" <1>');
    expect(body.gpx).toContain('<name>A&amp;B &lt;C&gt; "D"</name>');
    expect(body.gpx).toContain("<name>&lt;/RTE&gt;&lt;RTE&gt;</name>");
    expect(body.gpx.match(/<rte>/g)).toHaveLength(2);
  });
});
