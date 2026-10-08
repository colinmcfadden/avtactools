import { render, screen } from "@testing-library/react";
import React from "react";
import LzPanel from "../../shell/LzPanel";
import TopBar from "../../shell/TopBar";
import { packAudience, topBarPack } from "./usePackWorkspace";

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

describe("how many people can open the pack", () => {
  const members = [{ user_id: 1 }, { user_id: 2 }];
  const shared = { uuid: "a", member_count: 2, audience_count: 18, team: { id: 7, role: "editor" } };

  it("counts the team it is shared with, from the server, and never fewer than its own members", () => {
    expect(packAudience(shared, members, undefined)).toBe(18);
    // The switcher's line is asked again after a change, so it is the newer count.
    expect(packAudience(shared, members, { uuid: "a", audience_count: 19 })).toBe(19);
    // A count from before someone was added cannot be fewer than the members known now.
    expect(packAudience({ ...shared, audience_count: 1 }, [...members, { user_id: 3 }], { uuid: "a", audience_count: 1 })).toBe(3);
  });

  it("is the members known live when there is no team, whatever a list says", () => {
    expect(packAudience({ ...shared, team: null }, members, { uuid: "a", audience_count: 18 })).toBe(2);
    expect(packAudience(null, members)).toBeNull();
  });

  it("is what the LZ/PZ card says can see it", () => {
    const diagrams = [{ id: "a", name: "LZ HAWK", status: "analyzed", target: { mgrs: "16S GC 1 2" }, savedId: null }];
    const pack = { name: "OP DK", memberCount: packAudience(shared, [members[0]]), role: "editor", finished: false, readOnly: false };
    render(
      <LzPanel diagrams={diagrams} activeDiagramId="a" pack={pack} onSave={() => {}} onSelect={() => {}} onView3D={() => {}} onClose={() => {}} onRename={() => {}} onSaveAs={() => {}} />,
    );
    expect(screen.getByText("Saved to OP DK as you work. 18 members can see it.")).toBeInTheDocument();
  });
});
