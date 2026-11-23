import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { ToastProvider } from "../ui/Toast";
import LibraryDialog from "./LibraryDialog";

vi.mock("../auth/api", () => ({ __esModule: true, default: { get: vi.fn(), put: vi.fn(), post: vi.fn() } }));

const source = (patch = {}) => ({
  entries: [],
  loading: false,
  error: null,
  openIds: new Set(),
  refresh: vi.fn(),
  onOpen: vi.fn(),
  onDelete: vi.fn(),
  ...patch,
});

const show = (sources, props = {}) =>
  render(
    <ToastProvider>
      <LibraryDialog sources={{ lz: source(), routes: source(), points: source(), ...sources }} onClose={() => {}} {...props} />
    </ToastProvider>,
  );

describe("the Library", () => {
  it("says a list that failed to load could not be loaded, never that nothing is saved, and tries again", () => {
    const lz = source({ error: new Error("Request failed with status code 500") });
    show({ lz });
    expect(lz.refresh).toHaveBeenCalledTimes(1); // on opening

    expect(screen.getByText(/Your saved LZ\/PZs could not be loaded/)).toBeInTheDocument();
    expect(screen.queryByText(/Nothing saved yet/)).toBeNull();
    expect(screen.queryByText("0 saved LZ/PZs")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(lz.refresh).toHaveBeenCalledTimes(2);
  });

  it("says a list that loaded empty has nothing saved yet", () => {
    show({});
    expect(screen.getByText("Nothing saved yet. Save an LZ/PZ from its card in the LZ/PZ panel.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });

  it("keeps showing a list that failed to refresh, marked as possibly out of date", () => {
    const routes = source({
      entries: [{ id: 7, name: "ROUTE ALPHA", kind: "sketch", updated_at: "2026-10-01T12:00:00Z" }],
      error: new Error("Network Error"),
    });
    show({ routes });
    fireEvent.click(screen.getByRole("tab", { name: /Routes/ }));
    expect(screen.getByText("ROUTE ALPHA")).toBeInTheDocument();
    expect(screen.getByText(/could not be refreshed, so it may be out of date/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(routes.refresh).toHaveBeenCalledTimes(2);
  });

  it("shows Loading while a retry is under way", () => {
    show({ points: source({ loading: true, error: new Error("Network Error") }) });
    fireEvent.click(screen.getByRole("tab", { name: /Local points/ }));
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded/)).toBeNull();
  });

  it("links its footer to Mission Packs: the Library closes and the workspace switcher opens", () => {
    const onClose = vi.fn();
    const onOpenPacks = vi.fn();
    show({}, { onClose, onOpenPacks });
    expect(screen.getByText(/^0 saved LZ\/PZs/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Working with a team? Open a Mission Pack" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onOpenPacks).toHaveBeenCalledTimes(1);
  });

  it("has no Mission Pack link where packs are not offered", () => {
    show({});
    expect(screen.queryByText(/Working with a team/)).toBeNull();
  });

  it("has a Threats tab only for an account given its source, and a set can go to a pack", () => {
    const { unmount } = show({});
    expect(screen.queryByRole("tab", { name: /Threats/ })).toBeNull();
    unmount();

    const threats = source({ entries: [{ id: 3, name: "OP DK THREATS", threat_count: 1, updated_at: "2026-11-08T10:00:00" }] });
    show({ threats }, { initialTab: "threats", onAddToPack: vi.fn() });
    expect(screen.getByRole("tab", { name: /Threats/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText(/^1 threat · Updated/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "More actions for OP DK THREATS" }));
    expect(screen.getByRole("menuitem", { name: /Duplicate/ })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: /Add to Mission Pack/ })).toBeInTheDocument();
  });
});

describe("the Library's documents", () => {
  const opord = { id: 4, name: "OPORD 24-03", pages: 14, source_type: "docx", has_text: true, updated_at: "2026-11-20T12:00:00Z" };

  it("lists each with its pages and source, uploads from the tab, and offers a new version and the PDF but no duplicate", () => {
    const upload = vi.fn();
    const newVersion = vi.fn();
    const download = vi.fn();
    const documents = source({
      entries: [opord],
      action: { icon: "upload", label: "Upload document", onClick: upload },
      menu: (entry) => [
        { icon: "upload", title: "New version…", onSelect: () => newVersion(entry) },
        { icon: "download", title: "Download PDF", onSelect: () => download(entry) },
      ],
      duplicable: false,
    });
    show({ documents }, { initialTab: "documents" });
    expect(screen.getByText("OPORD 24-03")).toBeInTheDocument();
    expect(screen.getByText(/14 pages · from Word/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    expect(upload).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: /More actions for OPORD 24-03/ }));
    const titles = screen.getAllByRole("menuitem").map((item) => item.textContent);
    expect(titles.some((t) => t.includes("Duplicate"))).toBe(false);
    fireEvent.click(screen.getByRole("menuitem", { name: "New version…" }));
    expect(newVersion).toHaveBeenCalledWith(opord);
  });

  it("says what to do when there are none yet", () => {
    show({ documents: source() }, { initialTab: "documents" });
    expect(screen.getByText(/No documents yet\. Upload an OPORD or any other paper/)).toBeInTheDocument();
  });
});
