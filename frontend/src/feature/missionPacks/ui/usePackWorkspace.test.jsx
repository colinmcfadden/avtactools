import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import LzPanel from "../../shell/LzPanel";
import TopBar from "../../shell/TopBar";
import { dateAtTime } from "../../ui/time";
import { packAudience, topBarPack, UpdateFromOriginalDialog } from "./usePackWorkspace";

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

describe("updating a copy from its Library original", () => {
  const changedAt = "2026-10-03T09:12:00";
  // As the pack was loaded: what it says about pack edits may be behind, so it is never what is shown.
  const item = { uuid: "lz-1", name: "LZ HAWK (ROCK)", source: { kind: "lz", uuid: "u-1", revision: 1, original: "changed", pack_changes: 1 } };
  const answer = (fields) =>
    jest.fn().mockResolvedValue({ item: { ...item, source: { kind: "lz", uuid: "u-1", revision: 1, original: "changed", ...fields } }, head_seq: 9 });
  const show = (getItem, onConfirm = jest.fn()) =>
    render(<UpdateFromOriginalDialog packUuid="p-1" item={item} me={1} getItem={getItem} onCancel={jest.fn()} onConfirm={onConfirm} />);
  const last = (id, name, summary) => ({ actor: { id, name }, summary, created_at: "2026-10-04T10:00:00" });
  const warning = () => document.querySelector(".ui-callout");

  it("says when the original changed and how many pack edits go, naming the last, as the server has it now", async () => {
    const getItem = answer({
      original_updated_at: changedAt,
      pack_changes: 3,
      last_pack_change: last(7, "Jess Robinson", "Jess Robinson set the landing heading of LZ HAWK to 270°."),
    });
    const onConfirm = jest.fn();
    show(getItem, onConfirm);
    expect(await screen.findByText(`Your Library version changed ${dateAtTime(changedAt)}. Updating replaces the pack copy with it.`)).toBeInTheDocument();
    expect(getItem).toHaveBeenCalledWith("p-1", "lz-1");
    expect(screen.getByText("3 changes made in the pack will be replaced").tagName).toBe("B");
    expect(warning()).toHaveTextContent(
      "3 changes made in the pack will be replaced, including Jess R.’s last, “set the landing heading of LZ HAWK to 270°”. The history keeps what was there.",
    );
    const button = screen.getByRole("button", { name: "Update from original" });
    expect(button.querySelector("svg")).not.toBeNull();
    fireEvent.click(button);
    expect(onConfirm).toHaveBeenCalled();
  });

  it("gives one change's sentence whole, and calls the person's own last change theirs", async () => {
    const { unmount } = show(answer({ original_updated_at: changedAt, pack_changes: 1, last_pack_change: last(7, "Jess Robinson", "Jess Robinson moved Chalk 2 on LZ HAWK.") }));
    await screen.findByText("1 change made in the pack will be replaced");
    expect(warning()).toHaveTextContent("1 change made in the pack will be replaced: Jess Robinson moved Chalk 2 on LZ HAWK. The history keeps what was there.");
    unmount();
    show(answer({ original_updated_at: changedAt, pack_changes: 2, last_pack_change: last(1, "Colin McFadden", "Colin McFadden moved Chalk 2 on LZ HAWK.") }));
    await screen.findByText("2 changes made in the pack will be replaced");
    expect(warning()).toHaveTextContent("including your last, “moved Chalk 2 on LZ HAWK”.");
  });

  it("warns of nothing when nothing was changed in the pack", async () => {
    show(answer({ original_updated_at: changedAt, pack_changes: 0, last_pack_change: null }));
    await screen.findByText(/Your Library version changed/);
    expect(warning()).toBeNull();
  });

  it("leaves out what it was not told, and warns in plain words rather than guess a count", async () => {
    const { unmount } = show(answer({}));
    expect(await screen.findByText("Changes made to it in the pack will be replaced. The history keeps what was there.")).toBeInTheDocument();
    expect(screen.getByText("Updating replaces the pack copy with your Library version.")).toBeInTheDocument();
    expect(screen.queryByText(/Your Library version changed/)).toBeNull();
    expect(screen.queryByText(/1 change/)).toBeNull();
    unmount();
    show(jest.fn().mockRejectedValue(new Error("offline")));
    expect(await screen.findByText("Changes made to it in the pack will be replaced. The history keeps what was there.")).toBeInTheDocument();
  });

  it("says nothing about pack edits until the server has answered", () => {
    show(jest.fn(() => new Promise(() => {})));
    expect(screen.getByText("Updating replaces the pack copy with your Library version.")).toBeInTheDocument();
    expect(warning()).toBeNull();
  });
});
