import { act, render, screen } from "@testing-library/react";
import React from "react";
import { randomId } from "./documentsApi";
import { useDocuments } from "./useDocuments";

vi.mock("./documentsApi", async (importOriginal) => ({
  ...(await importOriginal()),
  listDocuments: vi.fn(async () => []),
}));
vi.mock("./DocumentViewer", () => ({ default: ({ title, detail }) => <div role="dialog" aria-label={`Document: ${title}`}>{detail}</div> }));

/*
 * The documents' state on the web (docs/DOCUMENTS.md §9.1): who may read, what a window says as its document changes, and Back.
 */

let docs;
const Harness = (props) => {
  docs = useDocuments(props);
  return docs.dialogs;
};
const target = (patch = {}) => ({ key: "pack:p-1:doc-1", title: "OPORD", detail: "3 pages · PDF", loadPdf: vi.fn(), loadText: vi.fn(), ...patch });

describe("documents on the web", () => {
  const originalMatch = window.matchMedia;
  afterEach(() => {
    window.matchMedia = originalMatch;
  });

  it("reads a pack's document for a member without the feature, and lets it go at sign-out", () => {
    const { rerender } = render(<Harness enabled={false} signedIn />);
    act(() => docs.open(target()));
    expect(screen.getByRole("dialog", { name: "Document: OPORD" })).toBeInTheDocument();
    rerender(<Harness enabled={false} signedIn={false} />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("follows a rename in the window's title without reading the document again", () => {
    render(<Harness enabled={false} signedIn />);
    act(() => docs.open(target()));
    const before = docs.viewing.n;
    act(() => docs.retitle("pack:p-1:doc-1", { title: "OPORD (CHANGE 1)", detail: "4 pages · PDF" }));
    expect(screen.getByRole("dialog", { name: "Document: OPORD (CHANGE 1)" })).toHaveTextContent("4 pages · PDF");
    expect(docs.viewing.n).toBe(before);
    act(() => docs.retitle("library:9", { title: "OTHER", detail: "" }));
    expect(screen.getByRole("dialog", { name: "Document: OPORD (CHANGE 1)" })).toBeInTheDocument();
  });

  it("on a small screen is a step of the history: Back closes it, and closing it takes the step off", () => {
    window.matchMedia = vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
    const back = vi.spyOn(window.history, "back").mockImplementation(() => {});
    try {
      render(<Harness enabled signedIn />);
      act(() => docs.open(target()));
      expect(window.history.state?.ezpzDocument).toEqual(expect.any(String));
      act(() => window.dispatchEvent(new PopStateEvent("popstate")));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(back).not.toHaveBeenCalled();

      act(() => docs.open(target()));
      act(() => docs.close());
      expect(back).toHaveBeenCalledTimes(1);
    } finally {
      back.mockRestore();
    }
  });

  it("makes an upload's identity a UUID the server takes, even where crypto.randomUUID is missing", () => {
    const shape = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(randomId()).toMatch(shape);
    const original = crypto.randomUUID;
    Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true });
    try {
      expect(randomId()).toMatch(shape);
      expect(randomId()).not.toBe(randomId());
    } finally {
      Object.defineProperty(crypto, "randomUUID", { value: original, configurable: true });
    }
  });
});
