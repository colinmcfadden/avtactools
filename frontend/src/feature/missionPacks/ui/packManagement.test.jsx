import { act, fireEvent, render, screen, within } from "@testing-library/react";
import React from "react";
import * as packApi from "../packApi";
import { useInviteLink } from "../useInviteLink";
import { useMissionPack } from "../useMissionPack";
import { usePackLz } from "../usePackLz";
import { usePackPoints } from "../usePackPoints";
import { usePackRoutes } from "../usePackRoutes";
import { usePackSeen } from "../usePackSeen";
import PackPanel from "./PackPanel";
import { usePackWorkspace } from "./usePackWorkspace";

// The pack's own ⋯ menu (rename, describe, duplicate, leave, delete), through the workspace that
// carries it out. The live pack and the editors it feeds are stood in for; every request is a mock.
jest.mock("../packApi", () => {
  const actual = jest.requireActual("../packApi");
  const mocked = {};
  Object.keys(actual).forEach((key) => {
    // The words for a refusal are the real ones.
    mocked[key] = typeof actual[key] === "function" && !["packErrorMessage", "failureOf"].includes(key) ? jest.fn() : actual[key];
  });
  return mocked;
});
jest.mock("../useMissionPack", () => ({ useMissionPack: jest.fn() }));
jest.mock("../usePackLz", () => ({ usePackLz: jest.fn() }));
jest.mock("../usePackRoutes", () => ({ usePackRoutes: jest.fn() }));
jest.mock("../usePackPoints", () => ({ usePackPoints: jest.fn() }));
jest.mock("../useInviteLink", () => ({ useInviteLink: jest.fn() }));
jest.mock("../usePackSeen", () => ({ ...jest.requireActual("../usePackSeen"), usePackSeen: jest.fn() }));

const settle = () => act(async () => {});
const bco = { id: 7, name: "B CO", member_count: 18, role: "member" };
const colin = { user_id: 1, name: "Colin McFadden", role: "owner" };
const sam = { user_id: 2, name: "Sam Bell", role: "editor" };

// CRA resets mocks before each test, so each test sets up its own.
const setup = ({ role = "owner", status = "active", members = [colin, sam], team = null, teams = [bco], me = 1 } = {}) => {
  const pack = { uuid: "p1", name: "OP DK", description: "Night LZs.", role, status, team, member_count: members.length, audience_count: team ? 19 : members.length };
  const live = {
    status: "live",
    session: { pack, members, readOnly: role === "viewer" || status === "finished", pending: [], dropped: [], seq: 5 },
    items: [],
    people: [],
    error: null,
    edit: jest.fn(),
    setFocus: jest.fn(),
    refresh: jest.fn(),
  };
  useMissionPack.mockReturnValue(live);
  usePackLz.mockReturnValue({ flush: jest.fn(), openItem: jest.fn() });
  usePackRoutes.mockReturnValue({ flush: jest.fn(), open: [], openItem: jest.fn(), closeItem: jest.fn() });
  usePackPoints.mockReturnValue({ flush: jest.fn(), openItem: jest.fn(), closeItem: jest.fn() });
  useInviteLink.mockReturnValue({ status: "none" });
  usePackSeen.mockReturnValue({ seenSeq: 0, newSince: 0 });
  packApi.listPacks.mockResolvedValue({ packs: [{ ...pack }] });
  packApi.listMyInvites.mockResolvedValue({ invites: [] });
  packApi.listTeams.mockResolvedValue({ teams });
  packApi.getPack.mockResolvedValue({ ...pack, members });
  packApi.listPackInvites.mockResolvedValue({ invites: [] });

  const toast = jest.fn();
  const dock = { show: jest.fn(), panel: "pack", collapsed: false };
  const lz = {
    workspace: { diagramOrder: [], diagramsById: {}, activeDiagramId: null },
    hydrateWorkspace: jest.fn(),
    importDiagram: jest.fn(),
    applyRemoteDiagram: jest.fn(),
    removeDiagram: jest.fn(),
    setActiveDiagram: jest.fn(),
  };
  const sketch = { sketchedRoutes: [], loadSketchRoutes: jest.fn(), replaceRouteSet: jest.fn(), removeRouteSet: jest.fn() };
  const points = { pointSets: [], setPointSets: jest.fn() };
  const library = {};
  const user = { id: me, name: "Colin McFadden" };

  const Harness = () => {
    const packs = usePackWorkspace({ enabled: true, user, toast, dock, lz, sketch, points, library, onOpenLibrary: () => {} });
    return (
      <>
        <div data-testid="workspace">{packs.open ?? "Library"}</div>
        {packs.meta && (
          <PackPanel
            pack={packs.meta}
            members={packs.session.members}
            audience={packs.packForPanels.memberCount}
            items={[]}
            openIds={new Set()}
            me={me}
            readOnly={packs.readOnly}
            actions={packs.actions}
            tab="items"
            setTab={() => {}}
            api={packApi}
          />
        )}
        {packs.dialogs}
      </>
    );
  };
  window.localStorage.setItem("ezpz.openPack", "p1");
  render(<Harness />);
  return { toast, dock, live };
};

const openMenu = () => fireEvent.click(screen.getByRole("button", { name: "More actions for OP DK" }));
const menuTitles = () => screen.getAllByRole("menuitem").map((item) => item.textContent);
const dialog = () => screen.getByRole("dialog");

afterEach(() => window.localStorage.clear());

describe("the pack's own menu", () => {
  it("offers the owner everything, and an editor all but delete", async () => {
    setup({ role: "owner" });
    await settle();
    openMenu();
    expect(menuTitles()).toEqual(["Rename", "Edit description", "Duplicate as a new pack", "Leave pack", "Delete pack"]);
  });

  it("offers an editor rename, describe and leave, but not delete", async () => {
    setup({ role: "editor", me: 2 });
    await settle();
    openMenu();
    expect(menuTitles()).toEqual(["Rename", "Edit description", "Duplicate as a new pack", "Leave pack"]);
  });

  it("offers a viewer, and anyone in a finished pack, no rename or description", async () => {
    setup({ role: "viewer", me: 2, members: [colin, { ...sam, role: "viewer" }] });
    await settle();
    openMenu();
    expect(menuTitles()).toEqual(["Duplicate as a new pack", "Leave pack"]);
  });

  it("lets the owner of a finished pack still delete it", async () => {
    setup({ role: "owner", status: "finished" });
    await settle();
    openMenu();
    expect(menuTitles()).toEqual(["Duplicate as a new pack", "Leave pack", "Delete pack"]);
  });
});

describe("renaming and describing the pack", () => {
  it("renames it for everyone, in capitals as packs are named", async () => {
    const { toast, live } = setup();
    packApi.updatePack.mockResolvedValue({});
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Rename" }));
    fireEvent.change(within(dialog()).getByLabelText("Name"), { target: { value: "op eagle" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Rename" }));
    await settle();
    expect(packApi.updatePack).toHaveBeenCalledWith("p1", { name: "OP EAGLE" });
    expect(live.refresh).toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith({ tone: "pack", message: "Renamed OP DK to OP EAGLE." });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("keeps the dialog open and says why when the server refuses the name", async () => {
    setup();
    packApi.updatePack.mockRejectedValue({ response: { status: 423, data: { code: "pack_finished" } } });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Rename" }));
    fireEvent.change(within(dialog()).getByLabelText("Name"), { target: { value: "OP EAGLE" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Rename" }));
    await settle();
    expect(within(dialog()).getByText("This pack is finished. It is read-only until its owner reopens it.")).toBeInTheDocument();
  });

  it("saves a description, or none", async () => {
    const { toast } = setup({ role: "editor", me: 2 });
    packApi.updatePack.mockResolvedValue({});
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Edit description" }));
    const field = within(dialog()).getByLabelText("Description");
    expect(field).toHaveValue("Night LZs.");
    fireEvent.change(field, { target: { value: "  " } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Save" }));
    await settle();
    expect(packApi.updatePack).toHaveBeenCalledWith("p1", { description: "" });
    expect(toast).toHaveBeenCalledWith({ tone: "pack", message: "Took the description off OP DK." });
  });
});

describe("leaving the pack", () => {
  it("asks, leaves, and goes back to the Library with the list asked again", async () => {
    const { toast, dock } = setup({ role: "editor", me: 2 });
    packApi.removeMember.mockResolvedValue({ status: "removed" });
    await settle();
    const listed = packApi.listPacks.mock.calls.length;
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Leave pack" }));
    expect(within(dialog()).getByText("It stops opening for you, and only its owner can add you again.")).toBeInTheDocument();
    // A destructive answer is never where the focus lands.
    expect(within(dialog()).getByRole("button", { name: "Cancel" })).toHaveFocus();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Leave pack" }));
    await settle();
    expect(packApi.removeMember).toHaveBeenCalledWith("p1", 2);
    expect(screen.getByTestId("workspace")).toHaveTextContent("Library");
    expect(dock.show).toHaveBeenLastCalledWith("lz");
    expect(packApi.listPacks.mock.calls.length).toBeGreaterThan(listed);
    expect(toast).toHaveBeenCalledWith({ message: "You left OP DK." });
  });

  it("says a member who is also in the shared team keeps it through the team", async () => {
    setup({ role: "editor", me: 2, team: { id: 7, name: "B CO", role: "viewer" } });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Leave pack" }));
    expect(within(dialog()).getByText("You can still open it through B CO, which it is shared with, as a viewer.")).toBeInTheDocument();
  });

  it("stays in the pack and says why when leaving is refused", async () => {
    const { toast } = setup({ role: "editor", me: 2 });
    packApi.removeMember.mockRejectedValue({ response: { status: 409, data: { code: "owner_must_transfer" } } });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Leave pack" }));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Leave pack" }));
    await settle();
    expect(toast).toHaveBeenCalledWith({ tone: "error", message: "Make someone else the owner first." });
    expect(screen.getByTestId("workspace")).toHaveTextContent("p1");
  });

  it("tells the owner to hand it over first, and opens Members to do it", async () => {
    setup({ role: "owner" });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Leave pack" }));
    expect(screen.getByRole("heading", { name: "Hand OP DK over first" })).toBeInTheDocument();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Open Members" }));
    await settle();
    expect(screen.getByRole("heading", { name: "Members of OP DK" })).toBeInTheDocument();
    expect(packApi.removeMember).not.toHaveBeenCalled();
  });

  it("tells someone who has it only through a team that there is nothing to leave", async () => {
    setup({ role: "editor", me: 3, team: { id: 7, role: "editor" } });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Leave pack" }));
    expect(screen.getByRole("heading", { name: "You have OP DK through B CO" })).toBeInTheDocument();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Manage teams" }));
    await settle();
    expect(packApi.removeMember).not.toHaveBeenCalled();
  });
});

describe("deleting the pack", () => {
  it("says it is for everyone and cannot be undone, then deletes it and goes back to the Library", async () => {
    const { toast, live } = setup({ role: "owner", team: { id: 7, name: "B CO", role: "editor" } });
    // The live service says the pack has gone before the answer to the delete arrives.
    packApi.deletePack.mockImplementation(() => {
      useMissionPack.mockReturnValue({ ...live, status: "gone" });
      return new Promise((resolve) => setTimeout(() => resolve({ status: "deleted" }), 0));
    });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete pack" }));
    expect(screen.getByRole("heading", { name: "Delete OP DK?" })).toBeInTheDocument();
    expect(within(dialog()).getByText("It is deleted for all 19 people who can open it, with every item in it and its history.")).toBeInTheDocument();
    expect(within(dialog()).getByText(/This cannot be undone/)).toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Cancel" })).toHaveFocus();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Delete pack" }));
    await act(() => new Promise((resolve) => setTimeout(resolve, 5)));
    expect(packApi.deletePack).toHaveBeenCalledWith("p1");
    expect(screen.getByTestId("workspace")).toHaveTextContent("Library");
    expect(toast).toHaveBeenCalledWith({ message: "Deleted OP DK." });
    // Its going is this person's own doing, not news.
    expect(toast).not.toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringMatching(/no longer available/) }));
  });

  it("does nothing on Cancel", async () => {
    setup({ role: "owner" });
    await settle();
    openMenu();
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete pack" }));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));
    expect(packApi.deletePack).not.toHaveBeenCalled();
    expect(screen.getByTestId("workspace")).toHaveTextContent("p1");
  });
});
