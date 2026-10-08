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

  it("shows another of their teams once one is deleted, not 'not in a team'", async () => {
    const cco = { id: 8, name: "C CO", member_count: 2, role: "member" };
    const api = {
      ...teamApi(),
      getTeam: jest.fn((id) =>
        Promise.resolve({
          ...(id === 7 ? bco : cco),
          members: [{ user_id: 1, name: "Colin McFadden", email: "colin@army.mil", role: id === 7 ? "owner" : "member" }],
        }),
      ),
      deleteTeam: jest.fn().mockResolvedValue({}),
    };
    const dialog = (teams) => (
      <ToastProvider>
        <TeamsDialog teams={teams} me={1} api={api} onChanged={() => {}} onClose={() => {}} />
      </ToastProvider>
    );
    const { rerender } = render(dialog([bco, cco]));
    await settle();
    expect(screen.getByRole("heading", { name: "B CO" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete team" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Delete team" }).at(-1));
    await settle();
    expect(api.deleteTeam).toHaveBeenCalledWith(7);

    // The list has not caught up yet, and still has B CO.
    expect(screen.queryByText(/You are not in a team yet/)).toBeNull();
    expect(screen.getByRole("heading", { name: "C CO" })).toBeInTheDocument();

    rerender(dialog([cco]));
    expect(screen.getByRole("heading", { name: "C CO" })).toBeInTheDocument();
    expect(screen.getByText("C CO", { selector: ".packs-teams__team-name" }).closest("button")).toHaveAttribute("aria-current", "true");
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

describe("inviting from the Members dialog", () => {
  const pack = { uuid: "p1", name: "OP DK", role: "owner", team: null };
  const jamie = { id: 5, name: "Jamie Ortiz", email: "jamie.ortiz@army.mil" };
  const inviteApi = () => ({
    getPack: jest.fn().mockResolvedValue({ members: [{ user_id: 1, name: "Colin McFadden", email: "colin@army.mil", role: "owner" }] }),
    listPackInvites: jest.fn().mockResolvedValue({ invites: [] }),
    searchPeople: jest.fn().mockResolvedValue({ users: [jamie] }),
    addMember: jest.fn().mockResolvedValue({ member: { user_id: 5, role: "viewer" } }),
    inviteToPack: jest.fn().mockResolvedValue({}),
  });
  const dialog = (api, extra = {}) => (
    <ToastProvider>
      <MembersDialog pack={pack} teams={[]} me={1} api={api} onChanged={() => {}} onClose={() => {}} {...extra} />
    </ToastProvider>
  );
  const field = () => screen.getByRole("combobox", { name: "Invite" });
  const inviteButton = () => screen.getByRole("button", { name: "Invite" });

  it("chooses a teammate from the suggestions, and adds them only from Invite, with the role chosen after", async () => {
    const api = inviteApi();
    render(dialog(api));
    await settle();
    expect(inviteButton()).toBeDisabled();

    fireEvent.change(field(), { target: { value: "Jam" } });
    fireEvent.click(await screen.findByRole("option", { name: /Jamie Ortiz/ }));
    // Chosen, not added: the field names them and nothing was sent.
    expect(api.addMember).not.toHaveBeenCalled();
    expect(field()).toHaveValue("Jamie Ortiz");
    expect(screen.queryByRole("option", { name: /Jamie Ortiz/ })).toBeNull();
    expect(inviteButton()).toBeEnabled();

    fireEvent.change(screen.getByLabelText("As"), { target: { value: "viewer" } });
    fireEvent.click(inviteButton());
    expect(api.addMember).toHaveBeenCalledWith("p1", 5, "viewer");
    await settle();
    expect(screen.getByText("Jamie Ortiz is in OP DK now")).toBeInTheDocument();
    expect(field()).toHaveValue("");
    expect(inviteButton()).toBeDisabled();
  });

  it("takes a suggestion on Enter and sends on the next, and sends a typed email on Enter", async () => {
    const api = inviteApi();
    render(dialog(api));
    await settle();

    fireEvent.change(field(), { target: { value: "Jam" } });
    await screen.findByRole("option", { name: /Jamie Ortiz/ });
    fireEvent.keyDown(field(), { key: "Enter" });
    expect(field()).toHaveValue("Jamie Ortiz");
    expect(api.addMember).not.toHaveBeenCalled();
    fireEvent.keyDown(field(), { key: "Enter" });
    expect(api.addMember).toHaveBeenCalledWith("p1", 5, "editor");
    await settle();

    // Typing over the name forgets the person chosen.
    fireEvent.change(field(), { target: { value: "sgt.lopez@army" } });
    expect(inviteButton()).toBeDisabled();
    fireEvent.change(field(), { target: { value: "Sgt.Lopez@army.mil" } });
    expect(inviteButton()).toBeEnabled();
    fireEvent.keyDown(field(), { key: "Enter" });
    expect(api.inviteToPack).toHaveBeenCalledWith("p1", "sgt.lopez@army.mil", "editor");
    await settle();
    expect(screen.getByText("Invited sgt.lopez@army.mil")).toBeInTheDocument();
  });

  it("keeps what was typed when the invitation is refused", async () => {
    const api = inviteApi();
    api.inviteToPack.mockRejectedValue({ response: { status: 429, data: { code: "rate_limited" } } });
    api.packErrorMessage = () => "Too many invitations for now. Try again later.";
    render(dialog(api));
    await settle();
    fireEvent.change(field(), { target: { value: "sgt.lopez@army.mil" } });
    fireEvent.click(inviteButton());
    await settle();
    expect(screen.getByText("Too many invitations for now. Try again later.")).toBeInTheDocument();
    expect(field()).toHaveValue("sgt.lopez@army.mil");
  });

  it("puts the focus in the invite field when opened from Invite, and on the dialog's first control otherwise", async () => {
    const api = inviteApi();
    const { unmount } = render(dialog(api, { focusInvite: true }));
    await settle();
    expect(field()).toHaveFocus();
    unmount();

    render(dialog(inviteApi()));
    await settle();
    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
  });
});

describe("handing a pack over", () => {
  const pack = { uuid: "p1", name: "OP DK", role: "owner", team: null };
  const handApi = () => ({
    getPack: jest.fn().mockResolvedValue({
      members: [
        { user_id: 1, name: "Colin McFadden", email: "colin@army.mil", role: "owner" },
        { user_id: 2, name: "Sam Bell", email: "sam.bell@army.mil", role: "editor" },
      ],
    }),
    listPackInvites: jest.fn().mockResolvedValue({ invites: [] }),
    changeMember: jest.fn().mockResolvedValue({}),
  });
  const dialog = (api) => (
    <ToastProvider>
      <MembersDialog pack={pack} teams={[]} me={1} api={api} onChanged={() => {}} onClose={() => {}} />
    </ToastProvider>
  );

  it("asks before making someone the owner, says the owner becomes an editor, then hands it over", async () => {
    const api = handApi();
    render(dialog(api));
    await settle();
    fireEvent.change(screen.getByLabelText("Role of Sam Bell"), { target: { value: "owner" } });
    expect(api.changeMember).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Make Sam Bell the owner of OP DK?" })).toBeInTheDocument();
    expect(screen.getByText(/You become an editor/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Make owner" }));
    expect(api.changeMember).toHaveBeenCalledWith("p1", 2, "owner");
    await settle();
    expect(screen.getByText("Sam Bell owns OP DK now. You are an editor.")).toBeInTheDocument();
  });

  it("changes nothing on Cancel", async () => {
    const api = handApi();
    render(dialog(api));
    await settle();
    fireEvent.change(screen.getByLabelText("Role of Sam Bell"), { target: { value: "owner" } });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.changeMember).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Role of Sam Bell")).toHaveValue("editor");
  });
});
