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
    const onSave = vi.fn();
    const onSelect = vi.fn();
    const onOpenRecent = vi.fn();
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
    const onExportCard = vi.fn();
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
  // As in App.jsx: the switcher's menu is held outside the bar, so the Library's footer can open it.
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

// On a phone the search is the top of the screen and the rest of the bar a menu under it (feature/phoneFrame).
describe("the top bar as a phone's menu", () => {
  const menu = (props = {}) =>
    render(
      <TopBar
        user={{ name: "Dev Pilot", email: "pilot@example.mil" }}
        renderSwitcher={() => null}
        searchSlot={null}
        phone={{ open: true, onDone: () => {} }}
        {...props}
      />,
    );

  it("holds the workspace, the planning tools, Import, the Library and the account, each by name", () => {
    menu({ onOpenMenu: () => {} });
    const region = screen.getByRole("region", { name: "Menu" });
    expect(region).toHaveAttribute("id", "phone-menu");
    expect(region).not.toHaveAttribute("inert");
    for (const name of [/Change workspace/, "Account: Dev Pilot", "Planning tools", /Import/, "Library"]) {
      expect(within(region).getByRole("button", { name })).toBeInTheDocument();
    }
    // The map's own undo and redo are on the map on a phone, and the search is above.
    expect(within(region).queryByRole("button", { name: /Undo|Redo/ })).toBeNull();
    expect(document.querySelector(".shell-topbar")).toBeNull();
  });

  it("is out of reach while it is closed, and still there for a menu opened from elsewhere to find its button", () => {
    menu({ phone: { open: false, onDone: () => {} } });
    const region = document.getElementById("phone-menu");
    expect(region).toHaveAttribute("inert");
    expect(region).not.toHaveClass("phone-menu--open");
    expect(region.querySelector(".shell-topbar__switcher")).not.toBeNull();
  });

  it("closes once something in it is chosen", () => {
    const onDone = vi.fn();
    const onOpenLibrary = vi.fn();
    const onOpenMenu = vi.fn();
    const onImport = vi.fn();
    menu({ phone: { open: true, onDone }, onOpenLibrary, onOpenMenu, onImport });
    fireEvent.click(screen.getByRole("button", { name: "Library" }));
    expect(onOpenLibrary).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Planning tools" }));
    expect(onOpenMenu).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(2);
    // A menu opened from it leaves it open until one of its items is chosen.
    fireEvent.click(screen.getByRole("button", { name: /Import/ }));
    expect(onDone).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("menuitem", { name: /Mission file/ }));
    expect(onImport).toHaveBeenCalledWith("msnx");
    expect(onDone).toHaveBeenCalledTimes(3);
  });

  it("says how a Mission Pack stands, in words", () => {
    menu({ pack: { name: "OP DK", status: "active" }, packStatus: "sending", people: [{ id: 2, name: "Sam B." }] });
    const region = screen.getByRole("region", { name: "Menu" });
    expect(within(region).getByText("Saving changes…")).toBeInTheDocument();
    expect(region).toHaveClass("phone-menu--pack");
  });

  it("leaves the wide window's bar as it was", () => {
    render(<TopBar user={{ name: "Dev Pilot" }} renderSwitcher={() => null} undo={{ canUndo: false, canRedo: false, undoTitle: "Nothing to undo", redoTitle: "Nothing to redo" }} />);
    expect(document.querySelector(".shell-topbar")).not.toBeNull();
    expect(document.getElementById("phone-menu")).toBeNull();
    expect(screen.getByRole("button", { name: "Nothing to undo" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Planning tools" })).toBeNull();
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

describe("control measures in the Routes panel", () => {
  const ROUTE = { id: "r1", name: "ROUTE 1", color: "#e85", points: [{ id: "p1", lat: 34, lon: -84 }], plan: {} };
  const FLOT = { id: "flot-1", points: [{ lat: 34, lon: -84 }, { lat: 34.009, lon: -84 }] };
  const RED = { id: "pl-1", name: "RED", points: [{ lat: 34, lon: -84 }, { lat: 34.018, lon: -84 }] };
  const routes = (extra = {}) => ({ key: "sketches", kind: "sketch", routes: [ROUTE], ...extra });
  // A FLOT and a phase line are in a set of their own, which holds no route.
  const measures = (extra = {}) => ({ key: "measures", kind: "sketch", measures: true, routes: [], flots: [FLOT], phaseLines: [RED], ...extra });
  const NAMES = { sketches: "SKETCHED ROUTES", measures: "CONTROL MEASURES" };
  const panel = (sets, actions = {}, state = {}) => (
    <RoutesDockPanel
      sets={sets}
      stateOf={(key) => ({ name: NAMES[key], link: null, dirty: true, saving: false, savedAt: null, ...state })}
      actions={{ save: () => {}, saveAs: () => {}, rename: () => {}, close: () => {}, export: () => {}, toggleVisibility: () => {}, removeRoute: () => {}, ...actions }}
      plan={() => ({})}
      sketch={{ active: false, name: "ROUTE 2", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
    />
  );
  const routeCard = () => screen.getByRole("region", { name: "SKETCHED ROUTES" });
  const measuresCard = (name = "CONTROL MEASURES") => screen.getByRole("region", { name: `${name}, control measures` });

  it("are listed in a section of their own, under the route sets, and no route set's card lists them", () => {
    render(panel([routes(), measures()]));
    expect(within(routeCard()).getByText("1 route · 1 point")).toBeInTheDocument();
    expect(within(routeCard()).queryByText("PL RED")).toBeNull();
    expect(within(routeCard()).queryByText("FLOT")).toBeNull();
    const card = measuresCard();
    expect(within(card).getByText("1 FLOT · 1 phase line")).toBeInTheDocument();
    expect(within(card).getByText("FLOT")).toBeInTheDocument();
    expect(within(card).getByText("PL RED")).toBeInTheDocument();
    expect(within(card).getByText("Phase line · 2.0 km")).toBeInTheDocument();
    // The route sets are counted without them, and they are counted by the line.
    expect(screen.getByText("1 route set in this session")).toBeInTheDocument();
    expect(screen.getByText("Control measures").textContent).toBe("Control measures2");
    expect(routeCard().compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("need no route: with none in the session they are still there, and no route set is made for them", () => {
    render(panel([measures()]));
    expect(screen.getByText("0 route sets in this session")).toBeInTheDocument();
    expect(screen.getByText("No routes yet. Sketch one on the map, or import an AMPS mission file.")).toBeInTheDocument();
    expect(screen.queryByText("Route sets")).toBeNull();
    expect(within(measuresCard()).getByText("PL RED")).toBeInTheDocument();
  });

  it("are saved, renamed and closed by themselves, and are never exported or sketched into", () => {
    const save = vi.fn();
    const rename = vi.fn();
    const close = vi.fn();
    const saveAs = vi.fn();
    const set = measures();
    render(panel([routes(), set], { save, rename, close, saveAs, shareSet: () => {}, sketchInto: () => {} }));
    const card = measuresCard();
    fireEvent.click(within(card).getByRole("button", { name: "Save" }));
    expect(save).toHaveBeenCalledWith(set);

    fireEvent.click(within(card).getByRole("button", { name: "Rename the control measures CONTROL MEASURES" }));
    const field = within(card).getByLabelText("Name of these control measures");
    fireEvent.change(field, { target: { value: "op dk graphics" } });
    fireEvent.keyDown(field, { key: "Enter" });
    expect(rename).toHaveBeenCalledWith(set, "OP DK GRAPHICS");

    expect(within(card).queryByRole("button", { name: "Export .msnx" })).toBeNull();
    expect(within(card).queryByRole("button", { name: /ForeFlight/ })).toBeNull();
    fireEvent.click(within(card).getByRole("button", { name: "More actions for the control measures CONTROL MEASURES" }));
    const items = within(screen.getByRole("menu")).getAllByRole("menuitem").map((item) => item.textContent);
    expect(items.some((text) => text.startsWith("Save as…"))).toBe(true);
    expect(items.some((text) => /Sketch a route|ForeFlight/.test(text))).toBe(false);
    fireEvent.click(within(screen.getByRole("menu")).getByRole("menuitem", { name: /^Close/ }));
    expect(close).toHaveBeenCalledWith(set);
  });

  it("says Saved, with nothing to press, once they are", () => {
    render(panel([measures()], {}, { link: { id: 7, name: "CONTROL MEASURES" }, dirty: false }));
    expect(within(measuresCard()).getByRole("button", { name: "Saved" })).toBeDisabled();
  });

  it("renames and removes a phase line, and flips and removes a FLOT, from their rows", () => {
    const actions = { renamePhaseLine: vi.fn(), removePhaseLine: vi.fn(), flipFlot: vi.fn(), removeFlot: vi.fn() };
    render(panel([measures()], actions));
    fireEvent.click(screen.getByRole("button", { name: "Rename PL RED" }));
    expect(actions.renamePhaseLine).toHaveBeenCalledWith("pl-1");
    fireEvent.click(screen.getByRole("button", { name: "Remove PL RED" }));
    expect(actions.removePhaseLine).toHaveBeenCalledWith("pl-1");
    fireEvent.click(screen.getByRole("button", { name: "Flip the scallops of this FLOT to the other side" }));
    expect(actions.flipFlot).toHaveBeenCalledWith("flot-1");
    fireEvent.click(screen.getByRole("button", { name: "Remove this FLOT" }));
    expect(actions.removeFlot).toHaveBeenCalledWith("flot-1");
  });

  it("are only shown in a finished pack, which saves nothing by hand", () => {
    render(panel([measures({ pack: { finished: true } })]));
    const card = measuresCard();
    expect(within(card).getByText("PL RED")).toBeInTheDocument();
    expect(within(card).getByText("Read-only")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Save" })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Rename/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove PL RED" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove this FLOT" })).toBeNull();
  });

  describe("saved inside a route set, from before they were kept on their own", () => {
    const old = () => routes({ flots: [FLOT], phaseLines: [RED] });

    it("are listed with the control measures under that set's name, which is where they are saved", () => {
      render(panel([old()]));
      // The set still says what it is saved with; its lines are listed with the control measures.
      expect(within(routeCard()).getByText("1 route · 1 point · 1 FLOT · 1 phase line")).toBeInTheDocument();
      expect(within(routeCard()).queryByText("PL RED")).toBeNull();
      const card = measuresCard("SKETCHED ROUTES");
      expect(within(card).getByText("PL RED")).toBeInTheDocument();
      expect(within(card).getByText(/^Saved with the route set SKETCHED ROUTES/)).toBeInTheDocument();
      // They are the set's to save, rename and close.
      expect(within(card).queryByRole("button", { name: "Save" })).toBeNull();
      expect(within(card).queryByRole("button", { name: /^Rename the control measures/ })).toBeNull();
      expect(within(card).queryByRole("button", { name: /^More actions/ })).toBeNull();
    });

    it("are moved out of it, to stand on their own, from that card", () => {
      const keepLinesOnTheirOwn = vi.fn();
      const set = old();
      render(panel([set], { keepLinesOnTheirOwn }));
      fireEvent.click(within(measuresCard("SKETCHED ROUTES")).getByRole("button", { name: "Keep on their own" }));
      expect(keepLinesOnTheirOwn).toHaveBeenCalledWith(set);
    });

    it("stay where they are where nothing may be changed", () => {
      const { unmount } = render(panel([old()]));
      expect(screen.queryByRole("button", { name: "Keep on their own" })).toBeNull();
      unmount();
      render(panel([routes({ flots: [FLOT], pack: { finished: true } })], { keepLinesOnTheirOwn: () => {} }));
      expect(screen.queryByRole("button", { name: "Keep on their own" })).toBeNull();
    });
  });

  it("are not drawn from the panel's foot: FLOT and PL are the sidebar's, beside Route", () => {
    render(panel([measures()]));
    expect(screen.queryByRole("button", { name: /^(Draw|Cancel) FLOT$/ })).toBeNull();
    expect(screen.getByRole("button", { name: "Sketch a route" })).toBeInTheDocument();
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
      saveSketch: vi.fn().mockResolvedValue({ id: 31, name: "RED", updated_at: "2026-10-07T12:00:00" }),
      updateSketch: vi.fn().mockResolvedValue({ id: 31, updated_at: "2026-10-07T12:05:00" }),
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
    await waitFor(() => expect(library.saveSketch).toHaveBeenCalledWith("RED", routes, [], []));
    await waitFor(() => expect(result.current.stateOf("sketches")).toMatchObject({ link: { id: 31, name: "RED" }, dirty: false }));

    routes = [route("a", { plan: { tempC: 21 } })];
    rerender({ sets: [{ key: "sketches", kind: "sketch", routes }] });
    expect(result.current.stateOf("sketches").dirty).toBe(true);

    await act(async () => {
      await result.current.save("sketches");
    });
    expect(library.updateSketch).toHaveBeenCalledWith(31, routes, "RED", [], []);
    expect(result.current.stateOf("sketches").dirty).toBe(false);
  });
});
