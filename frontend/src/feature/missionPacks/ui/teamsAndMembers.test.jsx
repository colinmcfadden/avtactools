import { act, fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import { ToastProvider } from "../../ui/Toast";
import MembersDialog from "./MembersDialog";
import TeamsDialog from "./TeamsDialog";

// CRA resets mocks before each test, so each test makes its own answers.
// The dialogs load as they open and again after each change; let those answers land.
const settle = () => act(async () => {});
const bco = { id: 7, name: "B CO", member_count: 3, role: "owner" };

describe("manage teams", () => {
  const teamApi = () => ({
    createTeam: jest.fn().mockResolvedValue({ id: 7, name: "B CO", role: "owner", members: [] }),
    getTeam: jest.fn().mockResolvedValue({
      id: 7,
      name: "B CO",
      role: "owner",
      members: [{ user_id: 1, name: "Colin McFadden", email: "colin@army.mil", role: "owner" }],
    }),
    listTeamInvites: jest.fn().mockResolvedValue({ invites: [] }),
  });

  it("shows a team just made, before and after the list of teams catches up", async () => {
    const api = teamApi();
    const dialog = (teams) => (
      <ToastProvider>
        <TeamsDialog teams={teams} me={1} api={api} onChanged={() => {}} onClose={() => {}} />
      </ToastProvider>
    );
    const { rerender } = render(dialog([]));
    expect(screen.getByText(/You are not in a team yet/)).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "New team" })[0]);
    fireEvent.change(screen.getByLabelText("Team name"), { target: { value: "B CO" } });
    fireEvent.click(screen.getByRole("button", { name: "Create team" }));
    expect(api.createTeam).toHaveBeenCalledWith("B CO");
    await settle();

    // The list it came from does not have it yet.
    expect(screen.getByRole("heading", { name: "B CO" })).toBeInTheDocument();
    expect(screen.queryByText(/You are not in a team yet/)).toBeNull();

    rerender(dialog([bco]));
    expect(screen.getByRole("heading", { name: "B CO" })).toBeInTheDocument();
    expect(screen.getByText("B CO", { selector: ".packs-teams__team-name" }).closest("button")).toHaveAttribute("aria-current", "true");
  });
});

describe("sharing a pack with a team", () => {
  const pack = { uuid: "p1", name: "OP DK", role: "owner", team: null };
  const membersApi = () => ({
    getPack: jest.fn().mockResolvedValue({ members: [{ user_id: 1, name: "Colin McFadden", email: "colin@army.mil", role: "owner" }] }),
    listPackInvites: jest.fn().mockResolvedValue({ invites: [] }),
    updatePack: jest.fn(),
    searchPeople: jest.fn().mockResolvedValue({ people: [] }),
  });
  const dialog = (api, packNow, teams) => (
    <ToastProvider>
      <MembersDialog pack={packNow} teams={teams} me={1} api={api} onChanged={() => {}} onClose={() => {}} />
    </ToastProvider>
  );
  // What the live session makes of a pack.share event: the team's id and role, and no name.
  const fromEvent = { id: 7, role: "editor" };

  it("names the team it was shared with from the server's answer, and when sharing stops", async () => {
    const api = membersApi();
    api.updatePack.mockResolvedValue({ ...pack, team: { id: 7, name: "B CO", member_count: 3, role: "editor" } });
    const { rerender } = render(dialog(api, pack, [bco]));
    await settle();

    fireEvent.click(screen.getByRole("button", { name: "Share with team" }));
    expect(api.updatePack).toHaveBeenCalledWith("p1", { team_id: 7, team_role: "editor" });
    await settle();
    expect(screen.getByText("Shared with B CO")).toBeInTheDocument();

    // Even with no list of teams to look in, the answer to the share names it.
    rerender(dialog(api, { ...pack, team: fromEvent }, []));
    expect(screen.getByText("B CO", { selector: ".packs-person__name" })).toBeInTheDocument();
    expect(screen.getByText("Shared with this team · they can edit")).toBeInTheDocument();

    api.updatePack.mockResolvedValue({ ...pack, team: null });
    fireEvent.click(screen.getByRole("button", { name: "Stop sharing" }));
    await settle();
    expect(screen.getByText("B CO no longer has this pack")).toBeInTheDocument();
    expect(api.updatePack).toHaveBeenLastCalledWith("p1", { team_id: null });
  });

  it("names a team shared before it opened from the owner's teams, and can share again once stopped", async () => {
    const api = membersApi();
    api.updatePack.mockResolvedValue({ ...pack, team: null });
    const { rerender } = render(dialog(api, { ...pack, team: fromEvent }, [bco]));
    await settle();
    expect(screen.getByText("B CO", { selector: ".packs-person__name" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Stop sharing" }));
    await settle();
    expect(screen.getByText("B CO no longer has this pack")).toBeInTheDocument();

    // The pack.share event that ends it: the only team is on offer again, and chosen.
    rerender(dialog(api, pack, [bco]));
    expect(screen.getByRole("button", { name: "Share with team" })).toBeEnabled();
    expect(screen.getByRole("combobox", { name: "Team to share with" })).toHaveValue("7");
    fireEvent.click(screen.getByRole("button", { name: "Share with team" }));
    expect(api.updatePack).toHaveBeenLastCalledWith("p1", { team_id: 7, team_role: "editor" });
    await settle();
  });
});
