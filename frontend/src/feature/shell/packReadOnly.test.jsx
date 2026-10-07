import { fireEvent, render, screen, within } from "@testing-library/react";
import React from "react";
import LzPanel, { lzStateChips } from "./LzPanel";
import RoutesDockPanel from "./RoutesDockPanel";

// A viewer in a live pack: the pack is not finished, but takes no change from them (packForPanels).
const viewerPack = { name: "OP DK", memberCount: 4, role: "viewer", finished: false, readOnly: true };
const editorPack = { name: "OP DK", memberCount: 4, role: "editor", finished: false, readOnly: false };

const diagrams = [{ id: "a", name: "LZ HAWK", status: "analyzed", target: { mgrs: "16S GC 1 2" }, savedId: null }];

const renderLzPanel = (pack) =>
  render(
    <LzPanel
      diagrams={diagrams}
      activeDiagramId="a"
      pack={pack}
      onSave={() => {}}
      onSelect={() => {}}
      onView3D={() => {}}
      onClose={() => {}}
      onRename={() => {}}
      onSaveAs={() => {}}
    />,
  );

describe("the LZ/PZ panel for a viewer of a live pack", () => {
  it("is read-only, like a finished pack", () => {
    const d = { status: "analyzed", target: {} };
    expect(lzStateChips(d, { pack: viewerPack }).map((chip) => chip.text)).toEqual(["Analyzed", "Read-only"]);
  });

  it("offers no rename, says why, and keeps Save a copy", () => {
    renderLzPanel(viewerPack);
    const active = screen.getByRole("region", { name: "LZ HAWK, active" });
    expect(within(active).queryByRole("button", { name: "Rename LZ HAWK" })).toBeNull();
    expect(within(active).getByRole("button", { name: "Save a copy" })).toBeInTheDocument();
    expect(screen.getByText("OP DK · 1 item · live · view only")).toBeInTheDocument();
    expect(screen.getByText(/You are a viewer in this pack/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "More actions for LZ HAWK" }));
    expect(screen.queryByRole("menuitem", { name: /^Rename/ })).toBeNull();
    expect(screen.getByRole("menuitem", { name: /^Save a copy to Library/ })).toBeInTheDocument();
  });

  it("still lets an editor rename", () => {
    renderLzPanel(editorPack);
    expect(screen.getByRole("button", { name: "Rename LZ HAWK" })).toBeInTheDocument();
    expect(screen.getByText("OP DK · 1 item · live")).toBeInTheDocument();
  });
});

describe("the Routes panel for a viewer of a live pack", () => {
  const renderRoutes = (pack) =>
    render(
      <RoutesDockPanel
        sets={[]}
        stateOf={() => ({})}
        actions={{}}
        plan={() => ({})}
        sketch={{ active: false, name: "ROUTE 1", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
        pack={pack}
      />,
    );

  it("offers nothing to sketch into the pack", () => {
    renderRoutes(viewerPack);
    expect(screen.queryByRole("button", { name: "Sketch a route" })).toBeNull();
    expect(screen.getByText("No routes in OP DK.")).toBeInTheDocument();
  });

  it("still offers an editor a sketch", () => {
    renderRoutes(editorPack);
    expect(screen.getByRole("button", { name: "Sketch a route" })).toBeInTheDocument();
  });
});
