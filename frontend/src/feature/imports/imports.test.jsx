import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { ToastProvider } from "../ui/Toast";
import { detectImport, formatSize } from "./detectImport";
import ImportReviewDialog, { defaultDestination } from "./ImportReviewDialog";

// jsdom has neither TextDecoder nor Blob.arrayBuffer; the browser has both.
beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

const FIXTURES = path.join(__dirname, "../../../../contracts/fixtures");

/** A File as the parsers use one, over bytes read from disk, under any name. */
const fakeFile = (bytes, name) => {
  // Copied into this window's own ArrayBuffer: JSZip checks `instanceof ArrayBuffer`, and Node's is
  // another realm's.
  const buffer = new Uint8Array(bytes).buffer;
  return {
    name,
    size: bytes.byteLength,
    arrayBuffer: async () => buffer,
    slice: (start, end) => ({ arrayBuffer: async () => buffer.slice(start, end) }),
  };
};
const fixture = (relative, name = path.basename(relative)) => fakeFile(fs.readFileSync(path.join(FIXTURES, relative)), name);

describe("working out what a file is", () => {
  it("reads local points, threats and a mission by their content, whatever they are called", async () => {
    const points = await detectImport(fixture("sqlite/local-points.lps", "mailed-attachment.bin"));
    expect(points).toMatchObject({ kind: "lps", summary: expect.stringMatching(/^\d[\d,]* points?$/) });

    const threats = await detectImport(fixture("sqlite/threats.ths", "threats-from-s2.db"));
    expect(threats).toMatchObject({ kind: "ths", summary: expect.stringMatching(/^\d+ threats?$/) });

    const mission = await detectImport(fixture("msnx/sketch-export.msnx", "GOAT SUCKER.zip"));
    expect(mission).toMatchObject({ kind: "msnx", summary: expect.stringMatching(/route.*·.*point/) });
  });

  it("refuses what it cannot import, and says why", async () => {
    const text = await detectImport(fakeFile(Buffer.from("hello, this is a memo"), "notes.docx"));
    expect(text).toMatchObject({ kind: null, error: "Not supported. Nothing will be imported from this file." });

    const overlay = await detectImport(fakeFile(Buffer.from("not really a tiff"), "KJZP_CHART.tif"));
    expect(overlay.kind).toBeNull();
    expect(overlay.error).toMatch(/Map overlays are not supported yet/);

    const empty = await detectImport(fixture("sqlite/threats-empty.ths"));
    expect(empty.kind).toBeNull();
    expect(empty.error).toMatch(/could not be read/);
  });

  it("writes sizes as people read them", () => {
    expect(formatSize(512)).toBe("512 B");
    expect(formatSize(82 * 1024)).toBe("82 KB");
    expect(formatSize(1.5 * 1024 * 1024)).toBe("1.5 MB");
  });
});

describe("the import review", () => {
  const pack = { name: "OP DK", memberCount: 4 };
  const items = (inPack) => [
    { file: { name: "NORTH_GA.LPS" }, kind: "lps", summary: "1,234 points", size: "82 KB", name: "NORTH GA POINTS", destination: defaultDestination("lps", inPack) },
    { file: { name: "THREATS.ths" }, kind: "ths", summary: "7 threats", size: "12 KB", destination: defaultDestination("ths", inPack) },
    { file: { name: "GOAT.msnx" }, kind: "msnx", summary: "2 routes · 91 points", size: "1.5 MB", destination: defaultDestination("msnx", inPack) },
    { file: { name: "notes.docx" }, kind: null, error: "Not supported. Nothing will be imported from this file." },
  ];

  it("sends points to the open pack by default, never a mission, and threats only to this session", async () => {
    const onImport = jest.fn().mockResolvedValue();
    render(<ToastProvider><ImportReviewDialog items={items(pack)} pack={pack} onCancel={() => {}} onImport={onImport} /></ToastProvider>);

    expect(screen.getByText("Everyone in OP DK (4 members) will see these points.")).toBeInTheDocument();
    expect(screen.getByText("Threats are never saved or shared with a pack.")).toBeInTheDocument();
    expect(screen.getByText("Session only")).toBeInTheDocument();
    const missionWhere = within(screen.getByRole("radiogroup", { name: "Where GOAT.msnx goes" }));
    expect(missionWhere.getByRole("radio", { name: "Session" })).toBeChecked();
    expect(screen.getAllByRole("radio", { name: "OP DK" })[1]).toBeDisabled();
    expect(screen.getByText("1 to OP DK · 2 stay in this session")).toBeInTheDocument();

    // The unsupported file is left out, and can be taken off the list.
    fireEvent.click(screen.getByLabelText("Remove notes.docx from the list"));
    expect(screen.queryByText("notes.docx")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /Import 3 files/ }));
    await waitFor(() => expect(onImport).toHaveBeenCalledTimes(1));
    expect(onImport.mock.calls[0][0].map((item) => [item.kind, item.destination])).toEqual([
      ["lps", "pack"],
      ["ths", "session"],
      ["msnx", "session"],
    ]);
  });

  it("outside a pack, offers the Library or this session", () => {
    render(<ToastProvider><ImportReviewDialog items={items(null)} pack={null} onCancel={() => {}} onImport={() => {}} /></ToastProvider>);
    expect(screen.queryByRole("radio", { name: "OP DK" })).toBeNull();
    const pointsWhere = within(screen.getByRole("radiogroup", { name: "Where NORTH_GA.LPS goes" }));
    expect(pointsWhere.getByRole("radio", { name: "Library" })).toBeChecked();
    fireEvent.click(screen.getAllByRole("radio", { name: "Session" })[0]);
    expect(screen.getByText("3 stay in this session")).toBeInTheDocument();
  });
});
