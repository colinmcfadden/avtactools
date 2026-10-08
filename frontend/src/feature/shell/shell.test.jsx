import { act, fireEvent, render, renderHook, screen, waitFor, within } from "@testing-library/react";
import React from "react";
import useRouteSaves, { routesFingerprint } from "../saveDialog/useRouteSaves";
import { ToastProvider } from "../ui/Toast";
import Dock, { useDock } from "./Dock";
import LzPanel, { lzStateChips } from "./LzPanel";
import RoutesDockPanel from "./RoutesDockPanel";
import TopBar from "./TopBar";
import { useMenu } from "../ui/Menu";

const texts = (chips) => chips.map((chip) => chip.text);

describe("an LZ/PZ's state", () => {
  it("says how far along it is, and whether it is saved", () => {
    expect(texts(lzStateChips({ status: "draft" }))).toEqual(["No target", "Analyze to save"]);
    expect(texts(lzStateChips({ status: "targeted", target: {} }))).toEqual(["Target set", "Analyze to save"]);
    expect(texts(lzStateChips({ status: "analyzed", target: {}, savedId: null }))).toEqual(["Analyzed", "Not saved yet"]);
    expect(texts(lzStateChips({ status: "analyzed", target: {}, savedId: 4, dirty: true }))).toEqual(["Analyzed", "Unsaved changes"]);
    expect(texts(lzStateChips({ status: "analyzed", target: {}, savedId: 4, dirty: false }))).toEqual(["Analyzed", "Saved"]);
  });

  it("in a pack is synced, from the Library, or read-only, never unsaved", () => {
    const d = { status: "analyzed", target: {}, savedId: null, dirty: true };
    expect(texts(lzStateChips(d, { pack: { name: "OP DK" } }))).toEqual(["Analyzed", "Synced"]);
    expect(texts(lzStateChips(d, { pack: { fromLibrary: true, originalChanged: true } }))).toEqual(["Analyzed", "Original changed"]);
    expect(texts(lzStateChips(d, { pack: { finished: true } }))).toEqual(["Analyzed", "Read-only"]);
  });
});

describe("the LZ/PZ panel", () => {
  const diagrams = [
    { id: "a", name: "LZ HAWK", status: "analyzed", target: { mgrs: "16S GC 1 2" }, savedId: 7, dirty: true },
    { id: "b", name: "", status: "targeted", target: { mgrs: "16S GC 3 4" }, savedId: null },
  ];

  it("saves the active one, opens the others, and offers what is recent in the Library", () => {
    const onSave = jest.fn();
    const onSelect = jest.fn();
    const onOpenRecent = jest.fn();
    render(
      <LzPanel
        diagrams={diagrams}
        activeDiagramId="a"
        recent={[{ id: 9, name: "PZ OAK", updated_at: "2026-08-25T21:02:00" }]}
        onSave={onSave}
        onSelect={onSelect}
        onOpenRecent={onOpenRecent}
        onView3D={() => {}}
        onClose={() => {}}
        onRename={() => {}}
        onSaveAs={() => {}}
        onBrowseAll={() => {}}
      />,
    );
    const active = screen.getByRole("region", { name: "LZ HAWK, active" });
    expect(within(active).getByText("Unsaved changes")).toBeInTheDocument();
    fireEvent.click(within(active).getByRole("button", { name: "Save" }));
    expect(onSave).toHaveBeenCalledWith("a");

    // An LZ/PZ without a name is numbered by its place in the session.
    fireEvent.click(screen.getByRole("button", { name: "Open LZ/PZ 2" }));
    expect(onSelect).toHaveBeenCalledWith("b");

    fireEvent.click(screen.getByRole("button", { name: "Open PZ OAK" }));
    expect(onOpenRecent).toHaveBeenCalledWith(expect.objectContaining({ id: 9 }));
  });

  it("exports a card from the ⋯ menu once the LZ/PZ is analysed", () => {
    const onExportCard = jest.fn();
    const panel = (activeDiagramId) => (
      <LzPanel
        diagrams={diagrams}
        activeDiagramId={activeDiagramId}
        onSave={() => {}}
        onSelect={() => {}}
        onView3D={() => {}}
        onClose={() => {}}
        onRename={() => {}}
        onSaveAs={() => {}}
        onExportCard={onExportCard}
      />
    );
    const { rerender } = render(panel("a"));
    fireEvent.click(screen.getByRole("button", { name: "More actions for LZ HAWK" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Export LZ card" }));
    expect(onExportCard).toHaveBeenCalledWith("a");

    // One with only a target has no card to export yet.
    rerender(panel("b"));
    fireEvent.click(screen.getByRole("button", { name: "More actions for LZ/PZ 2" }));
    expect(screen.getByRole("menuitem", { name: "Export LZ card" })).toBeDisabled();
  });

  it("has no Export LZ card where exports are turned off", () => {
    render(
      <LzPanel diagrams={diagrams} activeDiagramId="a" onSave={() => {}} onSelect={() => {}} onView3D={() => {}} onClose={() => {}} onRename={() => {}} onSaveAs={() => {}} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "More actions for LZ HAWK" }));
    expect(screen.queryByRole("menuitem", { name: "Export LZ card" })).toBeNull();
  });

  it("in a pack has no Save, and says where the work goes", () => {
    render(
      <LzPanel
        diagrams={diagrams}
        activeDiagramId="a"
        pack={{ name: "OP DK", memberCount: 4 }}
        presence={(id) => (id === "a" ? [{ id: 2, name: "Sam Bell" }] : [])}
        onSave={() => {}}
        onSelect={() => {}}
        onView3D={() => {}}
        onClose={() => {}}
        onRename={() => {}}
        onSaveAs={() => {}}
      />,
    );
    expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.getByText("Saved to OP DK as you work. 4 members can see it.")).toBeInTheDocument();
    expect(screen.getByText("Sam B. is also here")).toBeInTheDocument();
  });
});

describe("the top bar's workspace switcher", () => {
  // As in App.js: the switcher's menu is held outside the bar, so the Library's footer can open it.
  const Harness = () => {
    const switcher = useMenu();
    return (
      <>
        <TopBar
          switcher={switcher}
          renderSwitcher={({ open, close }) => (open ? <div role="menu" aria-label="Workspace"><button type="button" onClick={close}>Done</button></div> : null)}
        />
        <button type="button" onClick={() => !switcher.open && switcher.toggle()}>Open a Mission Pack</button>
      </>
    );
  };

  it("opens from elsewhere as well as from its own button", () => {
    render(<Harness />);
    expect(screen.queryByRole("menu", { name: "Workspace" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Open a Mission Pack" }));
    expect(screen.getByRole("menu", { name: "Workspace" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Change workspace/ })).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("menu", { name: "Workspace" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Change workspace/ }));
    expect(screen.getByRole("menu", { name: "Workspace" })).toBeInTheDocument();
  });
});

describe("an imported mission's card in the Routes panel", () => {
  const mission = {
    key: "msnxfile-1",
    kind: "mission",
    fileName: "GOAT SUCKER.msnx",
    routes: [{ id: "r1", name: "ROUTE 1", color: "#e85", points: [{ id: "p1", lat: 34, lon: -84 }], plan: {} }],
  };
  const panel = (state) => (
    <RoutesDockPanel
      sets={[mission]}
      stateOf={() => ({ dirty: false, saving: false, savedAt: null, ...state })}
      actions={{ save: () => {}, saveAs: () => {}, rename: () => {}, close: () => {}, export: () => {}, toggleVisibility: () => {}, removeRoute: () => {} }}
      plan={() => ({})}
      sketch={{ active: false, name: "ROUTE 2", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
    />
  );

  it("goes by its file name until it is given a name of its own, and says it was imported", () => {
    const { rerender } = render(panel({ name: "GOAT SUCKER", link: null, dirty: true }));
    const card = screen.getByRole("region", { name: "GOAT SUCKER.msnx" });
    expect(within(card).getByRole("heading", { name: "GOAT SUCKER.msnx" })).toBeInTheDocument();
    expect(within(card).getByText("Imported").closest(".ui-chip")).toHaveClass("ui-chip--info");

    // Saved under the name the file gave it, it is still known by the file (mockup Routes).
    rerender(panel({ name: "GOAT SUCKER", link: { id: 4, name: "GOAT SUCKER" } }));
    expect(screen.getByRole("heading", { name: "GOAT SUCKER.msnx" })).toBeInTheDocument();

    rerender(panel({ name: "OP GOAT", link: { id: 4, name: "OP GOAT" } }));
    expect(screen.getByRole("heading", { name: "OP GOAT" })).toBeInTheDocument();
    expect(screen.queryByText("GOAT SUCKER.msnx")).toBeNull();
  });
});

describe("the dock", () => {
  const items = [
    { key: "lz", label: "LZ/PZ", icon: "hexagon", dot: "warn" },
    { key: "threats", label: "Threats", icon: "diamond", count: 2 },
  ];
  const Harness = () => {
    const dock = useDock("lz");
    return (
      <Dock items={items} dock={dock}>
        <div>panel {dock.panel}</div>
      </Dock>
    );
  };

  it("shows one panel, picks another from the rail, and folds away when its own icon is clicked", () => {
    render(<Harness />);
    expect(screen.getByText("panel lz")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Threats" })).toHaveTextContent("2");
    fireEvent.click(screen.getByRole("button", { name: "Threats" }));
    expect(screen.getByText("panel threats")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Threats" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "Threats" }));
    expect(screen.getByRole("button", { name: "Threats" })).toHaveAttribute("aria-pressed", "false");
  });
});

describe("whether a route set is saved", () => {
  const route = (id, extra = {}) => ({ id, name: id.toUpperCase(), points: [{ id: `${id}-1`, lat: 34, lon: -84 }], plan: { tempC: 15 }, ...extra });

  it("ignores hiding a route", () => {
    expect(routesFingerprint([route("a", { visible: false })])).toBe(routesFingerprint([route("a", { visible: true })]));
    expect(routesFingerprint([route("a", { plan: { tempC: 20 } })])).not.toBe(routesFingerprint([route("a")]));
  });

  it("is clean once saved, unsaved after a change, and later saves go to the same record without asking", async () => {
    const library = {
      savedRoutes: [],
      saveSketch: jest.fn().mockResolvedValue({ id: 31, name: "RED", updated_at: "2026-10-07T12:00:00" }),
      updateSketch: jest.fn().mockResolvedValue({ id: 31, updated_at: "2026-10-07T12:05:00" }),
    };
    let routes = [route("a")];
    const { result, rerender } = renderHook(
      ({ sets }) => useRouteSaves({ sets, library, signedIn: true }),
      { initialProps: { sets: [{ key: "sketches", kind: "sketch", routes }] }, wrapper: ToastProvider },
    );
    expect(result.current.stateOf("sketches")).toMatchObject({ link: null, dirty: true });

    // The first save asks for a name in the Save dialog.
    act(() => {
      result.current.save("sketches");
    });
    render(<ToastProvider>{result.current.dialogs}</ToastProvider>);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "RED" } });
    fireEvent.click(screen.getByRole("button", { name: /^Save$/ }));
    await waitFor(() => expect(library.saveSketch).toHaveBeenCalledWith("RED", routes));
    await waitFor(() => expect(result.current.stateOf("sketches")).toMatchObject({ link: { id: 31, name: "RED" }, dirty: false }));

    routes = [route("a", { plan: { tempC: 21 } })];
    rerender({ sets: [{ key: "sketches", kind: "sketch", routes }] });
    expect(result.current.stateOf("sketches").dirty).toBe(true);

    await act(async () => {
      await result.current.save("sketches");
    });
    expect(library.updateSketch).toHaveBeenCalledWith(31, routes, "RED");
    expect(result.current.stateOf("sketches").dirty).toBe(false);
  });
});
