import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import React, { useRef } from "react";
import { ToastProvider } from "../../ui/Toast";
import PackPanel, { whenText } from "./PackPanel";
import WorkspaceSwitcher from "./WorkspaceSwitcher";

const now = new Date();
const minutesAgo = (n) => new Date(now.getTime() - n * 60000).toISOString();

describe("when something changed", () => {
  it("says minutes within the hour, the clock today, else the date", () => {
    const at = new Date(2026, 9, 7, 15, 0);
    expect(whenText(new Date(2026, 9, 7, 14, 56), at)).toBe("4 min ago");
    expect(whenText(new Date(2026, 9, 7, 13, 21), at)).toBe("13:21");
    expect(whenText(new Date(2026, 9, 4, 9, 0), at)).toBe("Oct 4");
  });
});

describe("the workspace switcher", () => {
  const Harness = (props) => {
    const anchor = useRef(null);
    return (
      <>
        <button type="button" ref={anchor}>anchor</button>
        <WorkspaceSwitcher open onClose={() => {}} anchorRef={anchor} {...props} />
      </>
    );
  };
  const packs = [
    { uuid: "a", name: "OP DK", status: "active", role: "owner", member_count: 4, item_counts: { lz: 3, route: 2 } },
    { uuid: "b", name: "OP RAZORBILL", status: "finished", role: "editor", member_count: 2, finished_at: "2026-09-22T18:00:00" },
  ];

  it("lists the Library and the packs, says who is in the open one, and switches", () => {
    const onPick = jest.fn();
    render(
      <Harness
        current="a"
        here={2}
        packs={packs}
        teams={[{ id: 1, name: "B Co 2-10 AVN" }, { id: 2, name: "HHC" }]}
        library={{ lz: 12, routes: 4, points: 3, unsaved: 1 }}
        onPick={onPick}
        onNewPack={() => {}}
        onManageTeams={() => {}}
      />,
    );
    expect(screen.getByText("Personal · 12 LZ/PZs, 4 route sets, 3 point sets")).toBeInTheDocument();
    expect(screen.getByText("1 LZ/PZ with unsaved changes")).toBeInTheDocument();
    expect(screen.getByText("4 members · you are Owner")).toBeInTheDocument();
    expect(screen.getByText("2 here now")).toBeInTheDocument();
    expect(screen.getByText("3 LZ/PZ · 2 route sets")).toBeInTheDocument();
    expect(screen.getByText(/Finished Sep 22 · read-only/)).toBeInTheDocument();
    expect(screen.getByText("B Co 2-10 AVN and 1 other team")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("menuitem", { name: /^Library/ }));
    expect(onPick).toHaveBeenCalledWith(null);
  });

  it("answers an invitation", async () => {
    const onAccept = jest.fn().mockResolvedValue();
    render(
      <Harness
        packs={[]}
        invites={[{ id: 7, role: "editor", invited_by: { name: "Alex Park" }, pack: { uuid: "c", name: "OP IBIS" } }]}
        onAccept={onAccept}
        onDecline={() => {}}
        onPick={() => {}}
        onNewPack={() => {}}
        onManageTeams={() => {}}
      />,
    );
    expect(screen.getByText(/invited you to/)).toHaveTextContent("Alex Park invited you to OP IBIS as Editor");
    fireEvent.click(screen.getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(onAccept).toHaveBeenCalledWith(expect.objectContaining({ id: 7 })));
  });
});

describe("the pack panel", () => {
  const pack = { uuid: "p", name: "OP DK", role: "editor", status: "active", description: "Night LZs.", head_seq: 12 };
  const items = [
    { uuid: "lz-1", kind: "lz", name: "LZ IBIS", seq: 11, updated_by: { id: 2, name: "Sam Bell" }, updated_at: minutesAgo(4) },
    { uuid: "lz-2", kind: "lz", name: "LZ HAWK", seq: 3, updated_by: { id: 1, name: "Colin McFadden" }, updated_at: minutesAgo(90), source: { kind: "lz", original: "changed" } },
    { uuid: "rt-1", kind: "route", name: "INGRESS", seq: 5, data: { routes: [{}, {}] }, updated_by: { id: 2, name: "Sam Bell" }, updated_at: minutesAgo(30) },
  ];
  const actions = Object.fromEntries(
    ["open", "close", "rename", "saveCopy", "updateFromOriginal", "remove", "invite", "members", "addFromLibrary", "finish", "reopen"].map((k) => [k, jest.fn()]),
  );
  // CRA resets mocks before each test, so the history's answer is made fresh for each.
  const events = () => ({
    getEvents: jest.fn().mockResolvedValue({
      events: [
        { seq: 10, type: "set", item: "lz-1", actor: { id: 3, name: "Jess Reyes" }, summary: "Jess R. moved a sector.", status: "skipped", reason: "target_missing", created_at: minutesAgo(20) },
        { seq: 11, type: "set", item: "lz-1", actor: { id: 2, name: "Sam Bell" }, summary: "Sam B. moved Chalk 2 on LZ IBIS.", status: "applied", created_at: minutesAgo(4) },
      ],
    }),
  });

  const renderPanel = (tab = "items", extra = {}) =>
    render(
      <ToastProvider>
        <PackPanel
          pack={pack}
          members={[{ user_id: 1, name: "Colin McFadden" }, { user_id: 2, name: "Sam Bell" }]}
          items={items}
          openIds={new Set(["lz-1"])}
          people={[{ session: "s", user_id: 2, name: "Sam Bell", focus: { item: "lz-1" } }]}
          me={1}
          newSince={8}
          readOnly={false}
          actions={actions}
          tab={tab}
          setTab={() => {}}
          api={events()}
          {...extra}
        />
      </ToastProvider>,
    );

  it("groups the items, marks what others changed since this person looked, and offers Update on a moved original", () => {
    renderPanel();
    expect(screen.getByText("1 here now", { exact: false })).toBeInTheDocument();
    const ibis = screen.getByRole("button", { name: "Go to LZ IBIS" });
    expect(within(ibis).getByLabelText("changed since you looked")).toBeInTheDocument();
    // Their own change is not "new" to them.
    expect(within(screen.getByRole("button", { name: "Open LZ HAWK" })).queryByLabelText("changed since you looked")).toBeNull();
    expect(screen.getByText("2 routes · Sam B. · 30 min ago")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Update" }));
    expect(actions.updateFromOriginal).toHaveBeenCalledWith(expect.objectContaining({ uuid: "lz-2" }));
    fireEvent.click(screen.getByRole("button", { name: "Open LZ HAWK" }));
    expect(actions.open).toHaveBeenCalledWith(expect.objectContaining({ uuid: "lz-2" }));
    // An editor adds from the Library; only an owner finishes.
    expect(screen.getByRole("button", { name: /Add from Library/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Finish pack/ })).toBeNull();
  });

  it("shows the history newest first, what is new, and an edit the pack skipped in plain words", async () => {
    renderPanel("history");
    expect(await screen.findByText("Sam B. moved Chalk 2 on LZ IBIS.")).toBeInTheDocument();
    expect(screen.getByText("New since you looked · 2")).toBeInTheDocument();
    expect(screen.getByText(/Edit skipped: what it changed had already been removed\. Jess R\.’s change was not applied/)).toBeInTheDocument();
    const lines = screen.getAllByText(/Sam B\. moved|Edit skipped/).map((el) => el.textContent);
    expect(lines[0]).toMatch(/Sam B\. moved/);
  });

  it("adds to the history as the pack moves on, fetching only what is newer", async () => {
    const api = events();
    const view = renderPanel("history", { api });
    expect(await screen.findByText("Sam B. moved Chalk 2 on LZ IBIS.")).toBeInTheDocument();
    expect(api.getEvents).toHaveBeenCalledWith("p", 0, 500);
    api.getEvents.mockResolvedValue({ events: [
      { seq: 13, type: "set", item: "lz-1", actor: { id: 3, name: "Jess Reyes" }, summary: "Jess R. turned LZ IBIS.", status: "applied", created_at: minutesAgo(0) },
    ], has_more: false, head_seq: 13 });
    view.rerender(
      <ToastProvider>
        <PackPanel pack={{ ...pack, head_seq: 13 }} members={[]} items={items} openIds={new Set()} me={1} newSince={8} actions={actions} tab="history" setTab={() => {}} api={api} />
      </ToastProvider>,
    );
    expect(await screen.findByText("Jess R. turned LZ IBIS.")).toBeInTheDocument();
    expect(api.getEvents).toHaveBeenLastCalledWith("p", 12, 500);
    expect(screen.getByText("Sam B. moved Chalk 2 on LZ IBIS.")).toBeInTheDocument(); // still there
    expect(screen.getByText("New since you looked · 3")).toBeInTheDocument();
  });

  it("shows the newest events of a long history, not the oldest page after where it started", async () => {
    const api = { getEvents: jest.fn((uuid, since) => Promise.resolve(since === 100
      ? { events: [{ seq: 101, type: "set", item: "lz-1", actor: { id: 2, name: "Sam Bell" }, summary: "An old change.", status: "applied", created_at: minutesAgo(60) }], has_more: true, head_seq: 1000 }
      : { events: [{ seq: 1000, type: "set", item: "lz-1", actor: { id: 2, name: "Sam Bell" }, summary: "The newest change.", status: "applied", created_at: minutesAgo(1) }], has_more: false, head_seq: 1000 })) };
    renderPanel("history", { api, pack: { ...pack, head_seq: 600 } });
    expect(await screen.findByText("The newest change.")).toBeInTheDocument();
    expect(api.getEvents.mock.calls.map((call) => call[1])).toEqual([100, 500]);
    expect(screen.queryByText("An old change.")).toBeNull();
  });

  it("offers to keep edits the pack would not take", () => {
    const onKeepDropped = jest.fn();
    renderPanel("items", { dropped: 2, onKeepDropped });
    expect(screen.getByText(/2 of your changes were/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Save my version to Library/ }));
    expect(onKeepDropped).toHaveBeenCalled();
  });
});
