import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { makeThreat } from "../threats/threatModel";
import ThreatsDockPanel from "./ThreatsDockPanel";

const threat = makeThreat(34.5, -84.1, { name: "SA-11" });
const set = (over = {}) => ({ id: "a", savedId: 3, name: "NORTH", threats: [threat], ...over });
const show = (props = {}) =>
  render(<ThreatsDockPanel threats={[threat]} sets={[set()]} activeId="a" canSave onRenameSet={vi.fn()} onCloseSet={vi.fn()} onMakeActive={vi.fn()} onRemoveAll={vi.fn()} {...props} />);

describe("the Threats panel", () => {
  it("says the threats are saved, and shows each set with how its save went", () => {
    show({ saveStates: { a: "failed" } });
    expect(screen.getByText("Saved as you work.")).toBeInTheDocument();
    expect(screen.queryByText("Session only.")).toBeNull();
    expect(screen.getByText("NORTH")).toBeInTheDocument();
    expect(screen.getByText("Not saved yet")).toBeInTheDocument();
  });

  it("says so where the account cannot save, and offers a set only its exports", () => {
    const onExportKmz = vi.fn();
    const onExportThs = vi.fn();
    show({ canSave: false, sets: [set({ savedId: null })], onExportKmz, onExportThs });
    expect(screen.getByText("Session only.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "More actions for NORTH" }));
    expect(screen.queryByRole("menuitem", { name: /Close/ })).toBeNull();
    expect(screen.queryByRole("menuitem", { name: /Rename/ })).toBeNull();
    fireEvent.click(screen.getByRole("menuitem", { name: /Export for ForeFlight/ }));
    expect(onExportKmz).toHaveBeenCalledWith("a");
  });

  it("offers an empty set nothing to export, and one with nothing else no menu", () => {
    show({ canSave: false, sets: [set({ savedId: null, threats: [] })] });
    expect(screen.queryByRole("button", { name: "More actions for NORTH" })).toBeNull();
  });

  it("exports every open set from the foot, or one set from its menu", () => {
    const onExportKmz = vi.fn();
    const onExportThs = vi.fn();
    show({ onExportKmz, onExportThs, threats: [threat, threat], sets: [set(), set({ id: "b", savedId: 4, name: "SOUTH" })] });
    expect(screen.getByText(/Every open set goes in one file/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "More actions for SOUTH" }));
    fireEvent.click(screen.getByRole("menuitem", { name: /Export \.ths for AMPS/ }));
    expect(onExportThs).toHaveBeenCalledWith("b");
  });

  it("closes a set from its menu, and says which takes new threats when more than one is open", () => {
    const onCloseSet = vi.fn();
    show({ onCloseSet, sets: [set(), set({ id: "b", savedId: 4, name: "SOUTH", threats: [] })], activeId: "b" });
    expect(screen.getAllByText("New threats go here")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "More actions for NORTH" }));
    fireEvent.click(screen.getByRole("menuitem", { name: /Close/ }));
    expect(onCloseSet).toHaveBeenCalledWith("a");
  });

  it("tells the truth about what Remove all does", () => {
    show();
    fireEvent.click(screen.getByRole("button", { name: "Remove all threats" }));
    expect(screen.getByText(/out of the sets they are saved in\. The sets stay, empty\./)).toBeInTheDocument();
  });
});
