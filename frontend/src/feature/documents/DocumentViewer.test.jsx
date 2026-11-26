import React from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import DocumentViewer, { fitPlace } from "./DocumentViewer";
import { openPdf } from "./pdfLoader";

vi.mock("./pdfLoader", () => ({ openPdf: vi.fn() }));

// pdf.js stands in as a document of `pages` US Letter pages; each page records what it was drawn at. jsdom has no canvas, no layout
// and no observers: the canvas gives a context that draws nothing, and the page area alone has a width, so a page is drawn 600 wide.
const fakePdf = (pages = 3) => {
  const rendered = [];
  const doc = {
    numPages: pages,
    getPage: vi.fn(async (number) => ({
      getViewport: ({ scale }) => ({ width: 612 * scale, height: 792 * scale }),
      render: vi.fn(({ viewport }) => {
        rendered.push({ number, width: viewport.width });
        return { promise: Promise.resolve(), cancel: vi.fn() };
      }),
    })),
  };
  const destroy = vi.fn();
  openPdf.mockResolvedValue({ promise: Promise.resolve(doc), destroy });
  return { doc, rendered, destroy };
};

const TEXT = {
  version: 1,
  truncated: false,
  pages: ["OPORD 24-03. LZ HAWK is the main effort.", "Annex C: LZ HAWK at 16S GC 31204 58811.", "Alternate LZ HAWK."],
};
const BYTES = new Uint8Array([37, 80, 68, 70]).buffer;

const show = (props = {}) => {
  const onClose = vi.fn();
  const loadPdf = props.loadPdf ?? vi.fn(async () => BYTES);
  const loadText = props.loadText ?? vi.fn(async () => TEXT);
  render(<DocumentViewer title="OPORD 24-03" detail="12 pages · from Word" loadPdf={loadPdf} loadText={loadText} onClose={onClose} {...props} />);
  return { onClose, loadPdf, loadText };
};

let originalContext;
let originalWidth;
beforeAll(() => {
  originalContext = Object.getOwnPropertyDescriptor(HTMLCanvasElement.prototype, "getContext");
  originalWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({}));
  Object.defineProperty(HTMLElement.prototype, "clientWidth", {
    configurable: true,
    get() {
      return this.classList?.contains("document-viewer__scroll") ? 624 : 0;
    },
  });
});
afterAll(() => {
  Object.defineProperty(HTMLCanvasElement.prototype, "getContext", originalContext);
  if (originalWidth) Object.defineProperty(HTMLElement.prototype, "clientWidth", originalWidth);
  else delete HTMLElement.prototype.clientWidth;
});
afterEach(() => {
  delete window.matchMedia;
  window.localStorage.clear();
  vi.clearAllMocks();
});

describe("DocumentViewer", () => {
  it("opens the PDF and draws the pages near the view to fit the window", async () => {
    const { rendered } = fakePdf(3);
    const { loadPdf } = show();

    expect(screen.getByRole("dialog", { name: "Document: OPORD 24-03" })).toBeInTheDocument();
    expect(screen.getByText("Opening the document…")).toBeInTheDocument();
    await screen.findByText("Page 1 of 3");
    expect(screen.getAllByRole("img", { name: /^Page \d$/ })).toHaveLength(3);
    expect(loadPdf).toHaveBeenCalledTimes(1);
    expect(openPdf).toHaveBeenCalledWith(BYTES);
    // Without IntersectionObserver the pages round the current one are drawn: here all three, each 624 less the margins wide.
    await waitFor(() => expect(rendered.map((r) => r.number).sort()).toEqual([1, 2, 3]));
    expect(rendered.every((r) => r.width === 600)).toBe(true);
  });

  it("finds in the document and steps through what it found", async () => {
    fakePdf(3);
    show();
    await screen.findByText("Page 1 of 3");
    const find = screen.getByRole("searchbox", { name: "Find in the document" });
    await waitFor(() => expect(find).toBeEnabled());

    fireEvent.change(find, { target: { value: "lz hawk" } });
    expect(screen.getByRole("status")).toHaveTextContent("1 of 3");
    const hits = screen.getAllByRole("button", { name: /^p\. \d/ });
    expect(hits.map((h) => h.textContent)).toEqual([
      "p. 1OPORD 24-03. LZ HAWK is the main effort.",
      "p. 2Annex C: LZ HAWK at 16S GC 31204 58811.",
      "p. 3Alternate LZ HAWK.",
    ]);
    expect(hits[0]).toHaveAttribute("aria-current", "true");

    fireEvent.keyDown(find, { key: "Enter" });
    expect(screen.getByRole("status")).toHaveTextContent("2 of 3");
    fireEvent.keyDown(find, { key: "Enter", shiftKey: true });
    fireEvent.keyDown(find, { key: "Enter", shiftKey: true });
    expect(screen.getByRole("status")).toHaveTextContent("3 of 3");
    fireEvent.click(screen.getByRole("button", { name: "Next match" }));
    expect(screen.getByRole("status")).toHaveTextContent("1 of 3");
    fireEvent.click(hits[1]);
    expect(screen.getByRole("status")).toHaveTextContent("2 of 3");

    // A new search shows its first match, wherever it is.
    fireEvent.change(find, { target: { value: "annex c" } });
    expect(screen.getByRole("status")).toHaveTextContent("1 of 1");
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();

    fireEvent.change(find, { target: { value: "eagle" } });
    expect(screen.getByRole("status")).toHaveTextContent("Not found");
    expect(screen.getByRole("button", { name: "Next match" })).toBeDisabled();
  });

  it("says a document with no text cannot be searched, and still shows it", async () => {
    fakePdf(2);
    show({ loadText: vi.fn(async () => Promise.reject({ response: { status: 404, data: { code: "no_text" } } })) });
    await screen.findByText("Page 1 of 2");
    await screen.findByText("No text to search (a scan?)");
    expect(screen.getByRole("searchbox", { name: "Find in the document" })).toBeDisabled();
  });

  it("says when the text could not be loaded, apart from a scan", async () => {
    fakePdf(2);
    show({ loadText: vi.fn(async () => Promise.reject(new Error("Network Error"))) });
    await screen.findByText("The text could not be loaded");
  });

  it("says when only part of the text was read", async () => {
    fakePdf(1);
    show({ loadText: vi.fn(async () => ({ ...TEXT, truncated: true })) });
    await screen.findByText("Only the first pages' text was read");
  });

  it("says a document that could not be opened, in words", async () => {
    show({ loadPdf: vi.fn(async () => Promise.reject(new Error("Network Error"))) });
    expect(await screen.findByRole("alert")).toHaveTextContent("This document could not be opened.");
    expect(screen.getByRole("button", { name: "Download PDF" })).toBeDisabled();
  });

  it("zooms in steps and stops at the ends", async () => {
    const { rendered } = fakePdf(1);
    show();
    await screen.findByText("Page 1 of 1");
    const zoomIn = screen.getByRole("button", { name: "Zoom in" });
    const zoomOut = screen.getByRole("button", { name: "Zoom out" });
    expect(screen.getByText("100%")).toBeInTheDocument();
    fireEvent.click(zoomIn);
    expect(screen.getByText("125%")).toBeInTheDocument();
    await waitFor(() => expect(rendered.at(-1).width).toBe(750));
    for (let i = 0; i < 8; i++) fireEvent.click(zoomOut);
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(zoomOut).toBeDisabled();
  });

  it("closes on Escape, but first clears what the find box holds", async () => {
    fakePdf(1);
    const { onClose } = show();
    await screen.findByText("Page 1 of 1");
    const find = screen.getByRole("searchbox", { name: "Find in the document" });
    await waitFor(() => expect(find).toBeEnabled());
    fireEvent.change(find, { target: { value: "hawk" } });
    fireEvent.keyDown(find, { key: "Escape" });
    expect(find).toHaveValue("");
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(find, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("downloads the PDF it was given, named for the document", async () => {
    fakePdf(1);
    const created = [];
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn((blob) => {
      created.push(blob);
      return "blob:document";
    });
    URL.revokeObjectURL = vi.fn();
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function click() {
      clicked.download = this.download;
    });
    try {
      show({ title: "OPORD 24/03: DARK KNIGHT" });
      await screen.findByText("Page 1 of 1");
      fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
      expect(created).toHaveLength(1);
      expect(created[0].type).toBe("application/pdf");
      expect(created[0].size).toBe(4);
      expect(clicked.download).toBe("OPORD 24 03 DARK KNIGHT.pdf");
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
      clicked.mockRestore();
    }
  });

  it("lets go of pdf.js when it closes", async () => {
    const { destroy } = fakePdf(1);
    const { unmount } = render(<DocumentViewer title="X" loadPdf={async () => BYTES} loadText={async () => TEXT} onClose={() => {}} />);
    await screen.findByText("Page 1 of 1");
    unmount();
    expect(destroy).toHaveBeenCalledTimes(1);
  });

  it("lets go of pdf.js when it closes while pdf.js is still loading", async () => {
    const destroy = vi.fn();
    let loaded;
    openPdf.mockReturnValue(new Promise((resolve) => { loaded = resolve; }));
    const { unmount } = render(<DocumentViewer title="X" loadPdf={async () => BYTES} loadText={async () => TEXT} onClose={() => {}} />);
    await waitFor(() => expect(openPdf).toHaveBeenCalled());
    unmount();
    await act(async () => loaded({ promise: new Promise(() => {}), destroy }));
    expect(destroy).toHaveBeenCalledTimes(1);
  });

  it("fits a remembered place to a smaller browser, its title bar always in reach", () => {
    const laptop = { width: 1280, height: 800 };
    // Left low and to the right on a large screen.
    const fitted = fitPlace({ x: 1400, y: 1100, width: 900, height: 1200 }, laptop);
    expect(fitted.width).toBe(900);
    expect(fitted.height).toBe(720);
    const left = (laptop.width - fitted.width) / 2 + fitted.x;
    const top = laptop.height * 0.05 + fitted.y;
    expect(left).toBeLessThanOrEqual(laptop.width - 120);
    expect(top).toBeLessThanOrEqual(laptop.height - 48);
    // Dragged off the top and the left: back to where its bar shows.
    const back = fitPlace({ x: -2000, y: -500, width: 600, height: 700 }, laptop);
    expect((laptop.width - 600) / 2 + back.x + 600).toBeGreaterThanOrEqual(120);
    expect(laptop.height * 0.05 + back.y).toBeGreaterThanOrEqual(0);
    // A place that already fits stays as it was.
    expect(fitPlace({ x: 40, y: 20, width: 500, height: 640 }, laptop)).toEqual({ x: 40, y: 20, width: 500, height: 640 });
    expect(fitPlace(null, laptop)).toBeNull();
  });

  it("opens at the size it was left in this browser", async () => {
    fakePdf(1);
    window.localStorage.setItem("ezpz.documentViewer", JSON.stringify({ x: 40, y: 20, width: 500, height: 640 }));
    show();
    await screen.findByText("Page 1 of 1");
    const dialog = screen.getByRole("dialog");
    expect(dialog.style.width).toBe("500px");
    expect(dialog.style.height).toBe("640px");
    expect(dialog.style.transform).toBe("translate(40px,20px)");
  });

  it("is the whole screen on a narrow window, and not moved", async () => {
    fakePdf(1);
    window.matchMedia = vi.fn(() => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} }));
    window.localStorage.setItem("ezpz.documentViewer", JSON.stringify({ x: 40, y: 20, width: 500, height: 640 }));
    show();
    await screen.findByText("Page 1 of 1");
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveClass("document-viewer--full");
    expect(dialog.style.width).toBe("");
    expect(dialog.style.transform).toBe("");
  });

  it("reads nothing again when it draws again", async () => {
    fakePdf(1);
    const { loadPdf, loadText } = show();
    await screen.findByText("Page 1 of 1");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    });
    expect(loadPdf).toHaveBeenCalledTimes(1);
    expect(loadText).toHaveBeenCalledTimes(1);
  });
});
