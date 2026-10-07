import React, { useCallback, useEffect, useId, useState } from "react";
import Avatar from "../../ui/Avatar";
import Dialog, { ConfirmDialog } from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import NameDialog from "../../ui/NameDialog";
import { shortDate } from "../../ui/time";
import { useToast } from "../../ui/Toast";
import * as packApi from "../packApi";
import "./packs.css";

/*
 * Manage teams (designed here; the mockups only link to it). A team is a standing group, such as a
 * company, that lets people find each other by name and share a pack with everyone in it at once.
 * The person's teams are listed on the left and the chosen one is shown on the right: its members
 * and their roles, invitations by email, and leaving or deleting it. What each role may do follows
 * the server: the owner does everything, an admin invites and removes members, a member can leave.
 */

const ROLE_WORDS = { owner: "Owner", admin: "Admin", member: "Member" };
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const TeamDetail = ({ teamId, me, api, onChanged, onGone }) => {
  const { show } = useToast();
  const emailId = useId();
  const [team, setTeam] = useState(null);
  const [invites, setInvites] = useState([]);
  const [email, setEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("member");
  const [emailError, setEmailError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [confirm, setConfirm] = useState(null);

  const load = useCallback(async () => {
    try {
      const full = await api.getTeam(teamId);
      setTeam(full);
      if (full.role === "owner" || full.role === "admin") {
        const answer = await api.listTeamInvites(teamId);
        setInvites((answer?.invites ?? []).filter((i) => i.status === "pending"));
      } else {
        setInvites([]);
      }
    } catch (err) {
      setTeam(null);
      show({ tone: "error", message: api.packErrorMessage?.(err) || "The team could not be loaded." });
    }
  }, [api, show, teamId]);
  useEffect(() => {
    load();
  }, [load]);

  if (!team) return <div className="shell-empty">Loading…</div>;
  const manager = team.role === "owner" || team.role === "admin";
  const owner = team.role === "owner";

  const run = async (work, done, after) => {
    setBusy(true);
    try {
      await work();
      if (done) show({ message: done });
      if (after) after();
      else {
        await load();
        onChanged();
      }
    } catch (err) {
      show({ tone: "error", message: api.packErrorMessage?.(err) || "That did not work. Check the connection and try again." });
    } finally {
      setBusy(false);
    }
  };

  const invite = () => {
    const address = email.trim().toLowerCase();
    if (!EMAIL.test(address)) {
      setEmailError("Type their whole email address.");
      return;
    }
    run(() => api.inviteToTeam(team.id, { email: address, role: inviteRole }), `Invited ${address} to ${team.name}`).then(() => setEmail(""));
  };

  return (
    <div className="packs-teams__detail">
      <div className="packs-teams__title">
        <h3>{team.name}</h3>
        {manager && (
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--28 ui-btn--square" aria-label={`Rename ${team.name}`} onClick={() => setRenaming(true)}>
            <Icon name="pencil" size={14} />
          </button>
        )}
      </div>
      <div className="packs-person__meta" style={{ marginBottom: 14 }}>
        {team.members.length} {team.members.length === 1 ? "member" : "members"} · you are {ROLE_WORDS[team.role]?.toLowerCase() ?? "a member"}
        {team.created_at ? ` · since ${shortDate(team.created_at)}` : ""}
      </div>

      {manager && (
        <form
          className="packs-invite"
          onSubmit={(event) => {
            event.preventDefault();
            invite();
          }}
        >
          <div className="ui-field">
            <label className="ui-field__label" htmlFor={emailId}>Invite by email</label>
            <input
              id={emailId}
              className={`ui-input ui-input--36${emailError ? " ui-input--error" : ""}`}
              type="email"
              autoComplete="off"
              placeholder="name@army.mil"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                setEmailError(null);
              }}
            />
            <div className={`ui-field__hint${emailError ? " ui-field__hint--error" : ""}`}>
              {emailError ?? "They get an email with a link to join. Invitations last 14 days."}
            </div>
          </div>
          <div className="ui-field packs-invite__as">
            <label className="ui-field__label" htmlFor={`${emailId}-as`}>As</label>
            <select id={`${emailId}-as`} className="ui-input ui-input--36" value={inviteRole} onChange={(event) => setInviteRole(event.target.value)}>
              <option value="member">Member</option>
              {owner && <option value="admin">Admin</option>}
            </select>
          </div>
          <div className="ui-field packs-invite__go">
            <span className="ui-field__label" aria-hidden="true">&nbsp;</span>
            <button type="submit" className="ui-btn ui-btn--primary" disabled={busy} style={{ height: 36 }}>
              <Icon name="send" size={14} />
              Invite
            </button>
          </div>
        </form>
      )}

      <div className="ui-label packs-section-label">Members · {team.members.length}</div>
      <div className="packs-people">
        {team.members.map((member) => {
          const isMe = member.user_id === me;
          const label = member.name || member.email;
          // An admin may remove members only; nobody removes the owner (they hand the team on first).
          const removable = !isMe && member.role !== "owner" && (owner || (team.role === "admin" && member.role === "member"));
          return (
            <div key={member.user_id} className="packs-person">
              <Avatar person={{ id: member.user_id, name: label }} />
              <div className="packs-person__main">
                <div className="packs-person__name">{label}{isMe ? " (you)" : ""}</div>
                <div className="packs-person__meta">{member.email}{member.joined_at ? ` · joined ${shortDate(member.joined_at)}` : ""}</div>
              </div>
              {owner && !isMe ? (
                <select
                  className="ui-input packs-person__role"
                  aria-label={`Role of ${label}`}
                  value={member.role}
                  disabled={busy}
                  onChange={(event) => {
                    const next = event.target.value;
                    if (next === "owner") setConfirm({ kind: "owner", member });
                    else run(() => api.changeTeamMember(team.id, member.user_id, next), `${label} is ${next === "admin" ? "an admin" : "a member"} now`);
                  }}
                >
                  <option value="member">Member</option>
                  <option value="admin">Admin</option>
                  <option value="owner">Owner</option>
                </select>
              ) : (
                <span className="packs-person__fixed">{ROLE_WORDS[member.role] ?? member.role}</span>
              )}
              {removable ? (
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Remove ${label} from ${team.name}`} onClick={() => setConfirm({ kind: "remove", member })}>
                  <Icon name="x" size={15} />
                </button>
              ) : (
                <span style={{ width: 30, flex: "none" }} />
              )}
            </div>
          );
        })}
      </div>

      {manager && invites.length > 0 && (
        <>
          <div className="ui-label packs-section-label">Pending · {invites.length}</div>
          <div className="packs-people">
            {invites.map((inv) => (
              <div key={inv.id} className="packs-person">
                <span className="packs-history__system" style={{ width: 28, height: 28, borderRadius: 14 }}><Icon name="mail" size={14} /></span>
                <div className="packs-person__main">
                  <div className="packs-person__name">{inv.email}</div>
                  <div className="packs-person__meta">Invited {shortDate(inv.created_at)} as {ROLE_WORDS[inv.role]?.toLowerCase()}</div>
                </div>
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" disabled={busy} onClick={() => run(() => api.revokeTeamInvite(team.id, inv.id), `Withdrew the invitation to ${inv.email}`)}>
                  Revoke
                </button>
              </div>
            ))}
          </div>
        </>
      )}

      <div style={{ marginTop: 18 }}>
        {owner ? (
          <button type="button" className="shell-danger-link" onClick={() => setConfirm({ kind: "delete" })}>Delete team</button>
        ) : (
          <button type="button" className="shell-danger-link" onClick={() => setConfirm({ kind: "leave" })}>Leave team</button>
        )}
      </div>

      {renaming && (
        <NameDialog
          title={`Rename ${team.name}`}
          initialName={team.name}
          confirmLabel="Rename"
          hint="Everyone in the team sees the new name."
          onCancel={() => setRenaming(false)}
          onConfirm={async (name) => {
            await api.renameTeam(team.id, name);
            setRenaming(false);
            await load();
            onChanged();
          }}
        />
      )}
      {confirm?.kind === "remove" && (
        <ConfirmDialog
          title={`Remove ${confirm.member.name || confirm.member.email} from ${team.name}?`}
          text="They stay in any pack they were added to by name; packs shared with the team stop opening for them."
          icon="users"
          iconTone="danger"
          confirmLabel="Remove"
          confirmTone="danger"
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            const { member } = confirm;
            setConfirm(null);
            run(() => api.removeTeamMember(team.id, member.user_id), `${member.name || member.email} is no longer in ${team.name}`);
          }}
        />
      )}
      {confirm?.kind === "owner" && (
        <ConfirmDialog
          title={`Make ${confirm.member.name || confirm.member.email} the owner of ${team.name}?`}
          text="A team has one owner. You become an admin: you can still invite and remove members, but not delete the team or change roles."
          icon="users"
          iconTone="warn"
          confirmLabel="Make owner"
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            const { member } = confirm;
            setConfirm(null);
            run(() => api.changeTeamMember(team.id, member.user_id, "owner"), `${member.name || member.email} owns ${team.name} now`);
          }}
        />
      )}
      {confirm?.kind === "leave" && (
        <ConfirmDialog
          title={`Leave ${team.name}?`}
          text="Packs shared with the team stop opening for you, unless you were added to them by name. Someone in the team can invite you back."
          icon="logOut"
          iconTone="danger"
          confirmLabel="Leave"
          confirmTone="danger"
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            setConfirm(null);
            run(() => api.removeTeamMember(team.id, me), `You left ${team.name}`, onGone);
          }}
        />
      )}
      {confirm?.kind === "delete" && (
        <ConfirmDialog
          title={`Delete ${team.name}?`}
          text={`It goes for all ${team.members.length} members. Packs shared with it are not deleted, but stop opening for anyone who was only in them through the team.`}
          icon="trash"
          iconTone="danger"
          confirmLabel="Delete team"
          confirmTone="danger"
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            setConfirm(null);
            run(() => api.deleteTeam(team.id), `Deleted ${team.name}`, onGone);
          }}
        />
      )}
    </div>
  );
};

const TeamsDialog = ({ teams = [], me, api = packApi, onChanged, onClose }) => {
  const [chosen, setChosen] = useState(teams[0]?.id ?? null);
  const [creating, setCreating] = useState(false);
  // A team made here is chosen at once, before the refreshed list that has it comes back: missing
  // from the list for now is not gone, so the choice stays.
  const [created, setCreated] = useState(null);
  useEffect(() => {
    if (chosen != null && chosen !== created && !teams.some((t) => t.id === chosen)) setChosen(teams[0]?.id ?? null);
  }, [teams, chosen, created]);

  return (
    <>
      <Dialog
        title="Teams"
        subtitle="A team lets you find each other by name and share a Mission Pack with everyone in it at once."
        icon="users"
        className="packs-teams"
        onClose={onClose}
        footer={<button type="button" className="ui-btn ui-btn--38" onClick={onClose}>Done</button>}
      >
        <div className="packs-teams__body">
          <div className="packs-teams__list" role="list" aria-label="Your teams">
            {teams.map((team) => (
              <button
                key={team.id}
                type="button"
                role="listitem"
                aria-current={team.id === chosen ? "true" : undefined}
                className={`packs-teams__team${team.id === chosen ? " packs-teams__team--on" : ""}`}
                onClick={() => setChosen(team.id)}
              >
                <Avatar person={{ id: `team-${team.id}`, name: team.name }} size="sm" title="" />
                <span style={{ minWidth: 0 }}>
                  <span className="packs-teams__team-name" style={{ display: "block" }}>{team.name}</span>
                  <span className="packs-teams__team-meta">{team.member_count} {team.member_count === 1 ? "member" : "members"} · {ROLE_WORDS[team.role]}</span>
                </span>
              </button>
            ))}
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--34" style={{ justifyContent: "flex-start", marginTop: 4 }} onClick={() => setCreating(true)}>
              <Icon name="plus" size={15} />
              New team
            </button>
          </div>
          {chosen != null ? (
            <TeamDetail key={chosen} teamId={chosen} me={me} api={api} onChanged={onChanged} onGone={() => {
              // Another of their teams, if they have one: the list still has the one just left until it refreshes.
              setChosen(teams.find((t) => t.id !== chosen)?.id ?? null);
              onChanged();
            }} />
          ) : (
            <div className="packs-teams__empty">
              <span>
                You are not in a team yet. Make one for your company or section, then invite people by email. Once they join, you can find them by name and share packs with everyone at once.
              </span>
              <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={() => setCreating(true)}>
                <Icon name="plus" size={15} />
                New team
              </button>
            </div>
          )}
        </div>
      </Dialog>
      {creating && (
        <NameDialog
          title="New team"
          subtitle="You will be its owner. Invite people once it is made."
          icon="users"
          label="Team name"
          placeholder="B Co 2-10 AVN"
          confirmLabel="Create team"
          hint="A unit or section people will recognise, such as B Co 2-10 AVN."
          onCancel={() => setCreating(false)}
          onConfirm={async (name) => {
            const team = await api.createTeam(name);
            setCreating(false);
            onChanged();
            if (team?.id != null) {
              setCreated(team.id);
              setChosen(team.id);
            }
          }}
        />
      )}
    </>
  );
};

export default TeamsDialog;
