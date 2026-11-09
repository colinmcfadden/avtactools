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

  it("says so where the account cannot save, and offers no set's menu", () => {
    show({ canSave: false, sets: [set({ savedId: null })] });
    expect(screen.getByText("Session only.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "More actions for NORTH" })).toBeNull();
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
