import React from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import ShareGridDialog from "./ShareGridDialog";
import { captureGridLink, takeGridLink } from "./gridLinkCapture";
import { ToastProvider } from "../ui/Toast";

vi.mock("qrcode", () => ({ default: { toCanvas: vi.fn(() => Promise.resolve()) } }));
import QRCode from "qrcode";

const PLACE = { mgrs: "16S GC 28864 55349", lat: 33.913724026697224, lon: -84.52450547388797, label: "LZ HAWK" };
const TEXT = "LZ HAWK\n16S GC 28864 55349\n33.91372° N, 84.52451° W\nhttps://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK";

const show = () => render(<ToastProvider><ShareGridDialog place={PLACE} onClose={() => {}} /></ToastProvider>);

describe("ShareGridDialog", () => {
  afterEach(() => {
    delete navigator.share;
    vi.clearAllMocks();
  });

  it("shows the grid and its lat/long, and draws a QR code of the whole text", () => {
    show();
    expect(screen.getByText("16S GC 28864 55349")).toBeInTheDocument();
    expect(screen.getByText("33.91372° N, 84.52451° W")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "QR code of LZ HAWK, 16S GC 28864 55349" })).toBeInTheDocument();
    expect(QRCode.toCanvas).toHaveBeenCalledWith(expect.anything(), TEXT, expect.any(Object));
  });

  it("copies the text, and the link alone", async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    show();
    await act(async () => fireEvent.click(screen.getByRole("button", { name: /^Copy$/ })));
    expect(writeText).toHaveBeenLastCalledWith(TEXT);
    await act(async () => fireEvent.click(screen.getByRole("button", { name: /Copy link/ })));
    expect(writeText).toHaveBeenLastCalledWith("https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK");
  });

  it("offers the system's share sheet only where the browser has one, with the text", async () => {
    show();
    expect(screen.queryByRole("button", { name: /Share…/ })).toBeNull();
    const share = vi.fn(() => Promise.resolve());
    navigator.share = share;
    render(<ToastProvider><ShareGridDialog place={PLACE} onClose={() => {}} /></ToastProvider>);
    await act(async () => fireEvent.click(screen.getByRole("button", { name: /Share…/ })));
    expect(share).toHaveBeenCalledWith({ title: "LZ HAWK", text: TEXT });
  });

  it("says nothing when the share sheet is closed without sharing", async () => {
    navigator.share = vi.fn(() => Promise.reject(Object.assign(new Error("closed"), { name: "AbortError" })));
    show();
    await act(async () => fireEvent.click(screen.getByRole("button", { name: /Share…/ })));
    expect(screen.queryByText(/could not be shared/)).toBeNull();
  });
});

describe("a grid link", () => {
  const fakeWindow = (href) => {
    const url = new URL(href);
    const store = new Map();
    return {
      location: { href: url.href, hash: url.hash },
      history: { state: null, replaceState: vi.fn((state, title, path) => { url.href = new URL(path, url).href; }) },
      sessionStorage: { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) },
      url,
    };
  };

  it("is taken out of the address before anything renders, and taken once", () => {
    const win = fakeWindow("https://ezpztac.app/?x=1#grid=16SGC2886455349&name=LZ%20HAWK");
    expect(captureGridLink(win)).toBe("#grid=16SGC2886455349&name=LZ%20HAWK");
    expect(win.history.replaceState).toHaveBeenCalledWith(null, "", "/?x=1");
    const link = takeGridLink(win);
    expect(link.choice.mgrs).toBe("16S GC 28864 55349");
    expect(link.name).toBe("LZ HAWK");
    expect(takeGridLink(win)).toBeNull();
  });

  it("leaves any other fragment alone", () => {
    const win = fakeWindow("https://ezpztac.app/#section");
    expect(captureGridLink(win)).toBeNull();
    expect(win.history.replaceState).not.toHaveBeenCalled();
  });

  it("is let go when its grid does not read as one place", () => {
    const win = fakeWindow("https://ezpztac.app/#grid=34%2084");
    captureGridLink(win);
    expect(takeGridLink(win)).toBeNull();
  });
});
