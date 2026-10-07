import { render, screen } from "@testing-library/react";
import React from "react";
import TopBar from "../../shell/TopBar";
import { topBarPack } from "./usePackWorkspace";

describe("the top bar while a pack opens", () => {
  const listed = [{ uuid: "a", name: "OP DK", status: "active" }];

  it("names the pack and says it is opening, or cannot be reached, never the Library", () => {
    const { rerender } = render(<TopBar pack={topBarPack("a", null, listed)} packStatus="loading" renderSwitcher={() => null} />);
    expect(screen.getByText("OP DK")).toBeInTheDocument();
    expect(screen.getByText("Opening…")).toBeInTheDocument();
    expect(screen.queryByText(/Personal workspace/)).toBeNull();
    // Not in the switcher's list (it could not be loaded either): still a pack, by a plain name.
    rerender(<TopBar pack={topBarPack("b", null, listed)} packStatus="error" renderSwitcher={() => null} />);
    expect(screen.getByText("Mission Pack")).toBeInTheDocument();
    expect(screen.getByText("Cannot reach the pack")).toBeInTheDocument();
  });

  it("names the open pack from the pack itself once loaded, and none in the Library", () => {
    expect(topBarPack("a", { name: "OP DK RENAMED", status: "finished" }, listed)).toEqual({ name: "OP DK RENAMED", status: "finished" });
    expect(topBarPack(null, null, listed)).toBeNull();
  });
});
