import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import JSZip from "jszip";
import React from "react";
import { ToastProvider } from "../ui/Toast";
import { DOCUMENT_MAX_BYTES, detectImport } from "./detectImport";
import ImportReviewDialog, { defaultDestination, settleDestination } from "./ImportReviewDialog";
import { documentRules, useImports } from "./useImports";

/*
 * Documents through the import review (docs/DOCUMENTS.md §9.1): what a PDF or an Office file is taken for, where it may go, and an
 * upload that failed kept in the review under the same identity, so trying again is the same document on the server.
 */

/** A File as the readers use one, over the bytes given, with a size that may be said to be larger than the bytes. */
const fileOf = (bytes, name, size = bytes.byteLength) => {
  const buffer = new Uint8Array(bytes).buffer;
  return { name, size, arrayBuffer: async () => buffer, slice: (start, end) => ({ arrayBuffer: async () => buffer.slice(start, end) }) };
};
const text = (value) => new TextEncoder().encode(value);
const ole = (...streams) => {
  const head = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1, ...new Array(504).fill(0)];
  return new Uint8Array([...head, ...streams.flatMap((s) => [...new TextEncoder().encode(s)])]);
};
const ooxml = async (main) => {
  const zip = new JSZip();
  zip.file("[Content_Types].xml", "<Types/>");
  zip.file(main, "<root/>");
  return zip.generateAsync({ type: "uint8array" });
};

describe("what a document is taken for", () => {
  it("takes a PDF as a document, named for its file as it was written", async () => {
    const found = await detectImport(fileOf(text("%PDF-1.7\n..."), "OPORD 24-03 (DK).pdf"));
    expect(found).toMatchObject({ kind: "document", docType: "pdf", name: "OPORD 24-03 (DK)", summary: expect.any(String) });
  });

  it("finds a PDF's mark a little way into the file, as readers do", async () => {
    expect(await detectImport(fileOf(text("\n\n  %PDF-1.4\n"), "scan.pdf"))).toMatchObject({ kind: "document", docType: "pdf" });
  });

  it("takes a Word, Excel or PowerPoint zip for a document, never a broken mission", async () => {
    for (const [main, type] of [["word/document.xml", "docx"], ["xl/workbook.xml", "xlsx"], ["ppt/presentation.xml", "pptx"]]) {
      // eslint-disable-next-line no-await-in-loop
      const found = await detectImport(fileOf(await ooxml(main), `SYNC MATRIX.${type}`));
      expect(found).toMatchObject({ kind: "document", docType: type, name: "SYNC MATRIX" });
    }
  });

  it("takes a legacy Office file by its name, and one whose name does not say as Office", async () => {
    expect(await detectImport(fileOf(ole("Workbook"), "matrix.xls"))).toMatchObject({ kind: "document", docType: "xls" });
    expect(await detectImport(fileOf(ole("WordDocument"), "attachment.bin"))).toMatchObject({ kind: "document", docType: "office" });
  });

  it("refuses one larger than the server takes, before it is sent", async () => {
    const found = await detectImport(fileOf(text("%PDF-1.7\n"), "huge.pdf", DOCUMENT_MAX_BYTES + 1));
    expect(found).toMatchObject({ kind: null, error: "This file is larger than 25 MB, the most a document can be." });
  });
});

describe("where a document may go", () => {
  const pdf = { kind: "document", docType: "pdf", name: "OPORD" };
  const word = { kind: "document", docType: "docx", name: "OPORD" };
  const on = { on: true, saved: true, conversion: true };
  const editing = { name: "OP DK", role: "editor", finished: false };

  it("is not offered to an account without documents", () => {
    expect(documentRules(pdf, { documents: { ...on, on: false }, pack: null })).toMatchObject({ kind: null, error: /Not supported/ });
    expect(documentRules(pdf, { documents: null, pack: null })).toMatchObject({ kind: null });
  });

  it("takes an Office file only where the server converts it", () => {
    expect(documentRules(word, { documents: { ...on, conversion: false }, pack: null })).toMatchObject({ kind: null, error: /cannot turn Office files into PDFs/ });
    expect(documentRules(pdf, { documents: { ...on, conversion: false }, pack: null })).toMatchObject({ kind: "document" });
  });

  it("without cloud save goes only into a pack this person may change", () => {
    const off = { ...on, saved: false };
    expect(documentRules(pdf, { documents: off, pack: null })).toMatchObject({ kind: null, error: /Open a Mission Pack/ });
    expect(documentRules(pdf, { documents: off, pack: { ...editing, role: "viewer" } })).toMatchObject({ kind: null, error: /only view this pack/ });
    expect(documentRules(pdf, { documents: off, pack: { ...editing, finished: true } })).toMatchObject({ kind: null, error: /pack is finished/ });
    expect(documentRules(pdf, { documents: off, pack: editing })).toMatchObject({ kind: "document" });
  });

  it("gives each upload an identity of its own, and keeps the one it has", () => {
    const first = documentRules(pdf, { documents: on, pack: null });
    const second = documentRules(pdf, { documents: on, pack: null });
    expect(first.clientUuid).toEqual(expect.any(String));
    expect(second.clientUuid).not.toBe(first.clientUuid);
    expect(documentRules(first, { documents: on, pack: null }).clientUuid).toBe(first.clientUuid);
  });

  it("goes to the open pack where it can, else the Library, never this session", () => {
    expect(defaultDestination("document", editing)).toBe("pack");
    expect(defaultDestination("document", { ...editing, role: "viewer" })).toBe("library");
    expect(defaultDestination("document", { ...editing, finished: true })).toBe("library");
    expect(defaultDestination("document", null)).toBe("library");
  });
});

describe("a document in the review", () => {
  const pack = { name: "OP DK", memberCount: 4, role: "editor", finished: false };
  const item = (where, extra = {}) => ({
    file: { name: "OPORD 24-03.docx" },
    kind: "document",
    docType: "docx",
    summary: "Word document · turned into a PDF",
    size: "84 KB",
    name: "OPORD 24-03",
    clientUuid: "u-1",
    destination: defaultDestination("document", where),
    ...extra,
  });

  it("outside a pack goes to the Library, said rather than offered, and says the original is not kept", () => {
    render(<ToastProvider><ImportReviewDialog items={[item(null)]} pack={null} documentsSaved onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(screen.getByText(/Kept in your Library, to read on any device you use\. The server turns it into a PDF/)).toBeInTheDocument();
    expect(screen.getByText("1 to your Library")).toBeInTheDocument();
  });

  it("in a pack goes to it by default, with the Library beside it, and never this session", () => {
    render(<ToastProvider><ImportReviewDialog items={[item(pack)]} pack={pack} documentsSaved onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    const where = within(screen.getByRole("radiogroup", { name: "Where OPORD 24-03.docx goes" }));
    expect(where.getByRole("radio", { name: "OP DK" })).toBeChecked();
    expect(where.getByRole("radio", { name: "Library" })).not.toBeChecked();
    expect(where.queryByRole("radio", { name: "Session" })).toBeNull();
    expect(screen.getByText(/Everyone in OP DK \(4 members\) can read it\./)).toBeInTheDocument();
  });

  it("in a pack, without cloud save, goes only to the pack", () => {
    render(<ToastProvider><ImportReviewDialog items={[item(pack)]} pack={pack} onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    expect(screen.queryByRole("radiogroup")).toBeNull();
    expect(screen.getByText("1 to OP DK")).toBeInTheDocument();
  });

  it("says, while it uploads an Office file, that turning it into a PDF can take a minute", async () => {
    let finish;
    const onImport = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
    render(<ToastProvider><ImportReviewDialog items={[item(null)]} pack={null} documentsSaved onCancel={() => {}} onImport={onImport} /></ToastProvider>);
    fireEvent.click(screen.getByRole("button", { name: /Import 1 file/ }));
    expect(await screen.findByText("Uploading. Turning an Office file into a PDF can take a minute.")).toBeInTheDocument();
    await act(async () => finish());
    expect(screen.getByText("1 to your Library")).toBeInTheDocument();
  });

  it("keeps the name's case as typed, and says why an upload did not go", () => {
    render(
      <ToastProvider>
        <ImportReviewDialog items={[item(null, { failure: "The file has a password." })]} pack={null} documentsSaved onCancel={() => {}} onImport={() => {}} />
      </ToastProvider>,
    );
    const name = screen.getByLabelText("Name for the document in OPORD 24-03.docx");
    fireEvent.change(name, { target: { value: "Opord 24-03 (draft)" } });
    expect(name).toHaveValue("Opord 24-03 (draft)");
    expect(screen.getByRole("alert")).toHaveTextContent("Not uploaded: The file has a password.");
  });
});

describe("uploading documents from the review", () => {
  const harness = ({ upload, packUpload, pack = null }) => {
    let imports;
    const onDone = vi.fn();
    const Harness = () => {
      imports = useImports({
        pack,
        documents: { on: true, saved: true, conversion: true },
        importers: { document: upload },
        destinations: { library: {}, pack: { document: packUpload } },
        onDone,
      });
      return imports.dialog;
    };
    render(<ToastProvider><Harness /></ToastProvider>);
    return { begin: (files) => act(() => imports.begin(files)), onDone };
  };

  it("keeps one that failed in the review with the server's words, and tries it again as the same upload", async () => {
    const upload = vi
      .fn()
      .mockRejectedValueOnce({ response: { status: 503, data: { error: "The converter is busy. Try again in a minute.", code: "converter_busy" } } })
      .mockResolvedValueOnce({ id: 7 });
    const { begin, onDone } = harness({ upload });
    await begin([fileOf(text("%PDF-1.7\n"), "OPORD.pdf")]);

    fireEvent.click(screen.getByRole("button", { name: /Import 1 file/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Not uploaded: The converter is busy. Try again in a minute.");
    const first = upload.mock.calls[0][1];
    expect(first).toMatchObject({ name: "OPORD", clientUuid: expect.any(String) });
    expect(onDone).toHaveBeenLastCalledWith([], []);

    fireEvent.click(screen.getByRole("button", { name: /Import 1 file/ }));
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
    expect(upload.mock.calls[1][1].clientUuid).toBe(first.clientUuid);
    await waitFor(() => expect(screen.queryByRole("button", { name: /Import 1 file/ })).toBeNull());
    expect(onDone).toHaveBeenLastCalledWith(["document"], [{ kind: "document", destination: "library", fileName: "OPORD.pdf" }]);
  });

  it("sends one for the open pack as an item named for its upload", async () => {
    const packUpload = vi.fn().mockResolvedValue({});
    const { begin } = harness({ upload: vi.fn(), packUpload, pack: { name: "OP DK", memberCount: 2, role: "editor", finished: false } });
    await begin([fileOf(text("%PDF-1.7\n"), "ANNEX C.pdf")]);
    fireEvent.click(screen.getByRole("button", { name: /Import 1 file/ }));
    await waitFor(() => expect(packUpload).toHaveBeenCalledTimes(1));
    const [, options] = packUpload.mock.calls[0];
    expect(options.name).toBe("ANNEX C");
    expect(options.item).toMatch(/^doc-.+/);
  });
});

describe("a review kept open while its pack changes", () => {
  const doc = { file: { name: "OPORD.pdf" }, kind: "document", docType: "pdf", name: "OPORD", clientUuid: "u-1", destination: "pack" };
  const editing = { name: "OP DK", memberCount: 3, role: "editor", finished: false };

  it("sends a document whose pack went to the Library, or says it has nowhere to go", () => {
    expect(settleDestination(doc, { pack: null, documentsSaved: true })).toMatchObject({ kind: "document", destination: "library" });
    expect(settleDestination(doc, { pack: { ...editing, finished: true }, documentsSaved: true })).toMatchObject({ destination: "library" });
    expect(settleDestination(doc, { pack: null, documentsSaved: false })).toMatchObject({ kind: null, error: /no longer open.*nowhere to go/ });
    expect(settleDestination(doc, { pack: editing, documentsSaved: false })).toBe(doc);
  });

  it("renders, rather than failing, when the pack goes from under it", () => {
    const { rerender } = render(<ToastProvider><ImportReviewDialog items={[doc]} pack={editing} documentsSaved onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    expect(screen.getByText("1 to OP DK")).toBeInTheDocument();
    rerender(<ToastProvider><ImportReviewDialog items={[doc]} pack={null} documentsSaved onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    expect(screen.getByText("1 to your Library")).toBeInTheDocument();
  });
});

describe("the Library's own upload", () => {
  it("sends a document to the Library even with a pack open, which the review still offers", async () => {
    let imports;
    const Harness = () => {
      imports = useImports({
        pack: { name: "OP DK", memberCount: 2, role: "editor", finished: false },
        documents: { on: true, saved: true, conversion: true },
        importers: {},
        destinations: { library: {}, pack: {} },
      });
      return imports.dialog;
    };
    render(<ToastProvider><Harness /></ToastProvider>);
    await act(() => imports.begin([fileOf(text("%PDF-1.7\n"), "OPORD.pdf")], { prefer: "library" }));
    const where = within(screen.getByRole("radiogroup", { name: "Where OPORD.pdf goes" }));
    expect(where.getByRole("radio", { name: "Library" })).toBeChecked();
    expect(where.getByRole("radio", { name: "OP DK" })).not.toBeChecked();
  });
});
