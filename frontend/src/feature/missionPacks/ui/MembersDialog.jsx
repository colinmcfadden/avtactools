import React, { useCallback, useEffect, useRef, useState } from "react";
import Avatar from "../../ui/Avatar";
import Dialog, { ConfirmDialog } from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import { dateAtTime, shortDate, timeAgo } from "../../ui/time";
import { useToast } from "../../ui/Toast";
import * as packApi from "../packApi";
import PersonPicker from "./PersonPicker";
import "./packs.css";

/*
 * Members and invitations of a pack (screen Members). The owner invites people (teammates by name,
 * anyone else by email), shares the pack with a team, changes roles and takes people off; everyone
 * else sees the list. "Here now" comes from who has the pack open, not from being signed in.
 */

const ROLE_WORDS = { owner: "Owner", editor: "Editor", viewer: "Viewer" };

const daysLeft = (iso) => {
  const ms = new Date(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`) - new Date();
  const days = Math.ceil(ms / 86400000);
  return days <= 1 ? "expires today" : `expires in ${days} days`;
};

/** `focusInvite`: opened from Invite, so the invite field takes the focus (else the dialog's first control). */
const MembersDialog = ({ pack, members: initialMembers = [], teams = [], here = [], me, focusInvite = false, api = packApi, onChanged, onClose }) => {
  const { show } = useToast();
  const owner = pack.role === "owner";
  // The live session knows members by the events that added them (no email, no last look): the
  // list is read again from the pack itself when the dialog opens and after every change.
  const [members, setMembers] = useState(initialMembers);
  const loadMembers = useCallback(() => {
    api.getPack(pack.uuid).then((full) => full?.members && setMembers(full.members), () => {});
  }, [api, pack.uuid]);
  useEffect(loadMembers, [loadMembers]);
  const [invites, setInvites] = useState([]);
  const [role, setRole] = useState("editor");
  // Who the invite field holds: a teammate chosen from the suggestions, or an email address typed.
  const [choice, setChoice] = useState(null);
  const picker = useRef(null);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [handingTo, setHandingTo] = useState(null);
  // The live session learns of a share from its event, which names the team by id and role only.
  // The name is the one the server gave in its answer to the share, else the owner's own team list
  // has it (a pack can only be shared with a team its owner is in).
  const [answered, setAnswered] = useState(null);
  const sharedWith = pack.team && {
    ...pack.team,
    name: [answered, ...teams].find((t) => t?.id === pack.team.id)?.name ?? pack.team.name,
  };
  const shareable = teams.filter((t) => t.id !== pack.team?.id);
  const [shareTeam, setShareTeam] = useState(shareable[0]?.id ?? null);
  // What the team may do, as when a pack is made: edit, or only view.
  const [shareRole, setShareRole] = useState("editor");
  // What was picked, or the first team once the one picked (or none, while it was shared) is not offered.
  const shareTo = shareable.some((t) => t.id === shareTeam) ? shareTeam : shareable[0]?.id ?? null;

  const loadInvites = useCallback(() => {
    if (!owner) return;
    api.listPackInvites(pack.uuid).then(
      (answer) => setInvites((answer?.invites ?? []).filter((i) => i.status === "pending")),
      () => {},
    );
  }, [api, owner, pack.uuid]);
  useEffect(loadInvites, [loadInvites]);

  const run = async (work, done, after) => {
    setBusy(true);
    try {
      await work();
      after?.();
      if (done) show({ message: done });
      onChanged?.();
      loadInvites();
      loadMembers();
    } catch (err) {
      show({ tone: "error", message: api.packErrorMessage?.(err) || "That did not work. Check the connection and try again." });
    } finally {
      setBusy(false);
    }
  };

  const share = (fields, done) =>
    run(async () => setAnswered((await api.updatePack(pack.uuid, fields))?.team ?? null), done);

  // Sent only from the Invite button or Enter, with the role beside it; the field empties once it is done.
  const invite = (pick) => {
    if (!pick || busy) return;
    run(
      () => (pick.user ? api.addMember(pack.uuid, pick.user.id, role) : api.inviteToPack(pack.uuid, pick.email, role)),
      pick.user ? `${pick.user.name || pick.user.email} is in ${pack.name} now` : `Invited ${pick.email}`,
      () => {
        picker.current?.clear();
        picker.current?.focus();
      },
    );
  };

  const hereIds = new Set(here.map((p) => p.user_id));
  return (
    <>
      <Dialog
        title={`Members of ${pack.name}`}
        subtitle="Owners manage who can see and edit this pack. Viewers can look but not change anything."
        icon="users"
        iconTone="pack"
        width="xwide"
        onClose={onClose}
        initialFocus={focusInvite && owner ? picker : undefined}
        note={
          <span style={{ display: "inline-flex", gap: 6, alignItems: "flex-start" }}>
            <Icon name="info" size={13} style={{ marginTop: 2, flex: "none" }} />
            Everyone with access sees every item in this pack. Threats are never shared.
          </span>
        }
        footer={<button type="button" className="ui-btn ui-btn--38" onClick={onClose}>Done</button>}
      >
        {owner && (
          <>
            <div className="packs-invite">
              <PersonPicker
                select
                handle={picker}
                exclude={members.map((m) => m.user_id)}
                search={api.searchPeople}
                onChange={setChoice}
                onPick={invite}
                label="Invite"
              />
              <div className="ui-field packs-invite__as">
                <label className="ui-field__label" htmlFor="members-invite-as">As</label>
                <select id="members-invite-as" className="ui-input ui-input--36" value={role} onChange={(event) => setRole(event.target.value)}>
                  <option value="editor">Editor</option>
                  <option value="viewer">Viewer</option>
                </select>
              </div>
              <div className="ui-field packs-invite__go">
                <span className="ui-field__label" aria-hidden="true">&nbsp;</span>
                <button type="button" className="ui-btn ui-btn--primary" disabled={busy || !choice} onClick={() => invite(choice)}>
                  <Icon name="userPlus" size={15} />
                  Invite
                </button>
              </div>
            </div>
            {sharedWith ? (
              <div className="packs-team-share" style={{ marginTop: 14 }}>
                <Icon name="users" size={16} />
                <div className="packs-team-share__main">
                  <div className="packs-person__name">{sharedWith.name || "A team"}</div>
                  <div className="packs-person__meta">Shared with this team</div>
                </div>
                <select
                  className="ui-input ui-input--36 packs-team-share__role"
                  aria-label={`What ${sharedWith.name || "the team"} can do`}
                  value={sharedWith.role === "viewer" ? "viewer" : "editor"}
                  disabled={busy}
                  onChange={(event) => {
                    const next = event.target.value;
                    share({ team_id: sharedWith.id, team_role: next }, `${sharedWith.name || "The team"} can ${next === "viewer" ? "only view" : "edit"} this pack now`);
                  }}
                >
                  <option value="editor">Can edit</option>
                  <option value="viewer">Can view</option>
                </select>
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" disabled={busy} onClick={() => share({ team_id: null }, `${sharedWith.name || "The team"} no longer has this pack`)}>
                  Stop sharing
                </button>
              </div>
            ) : (
              shareable.length > 0 && (
                <div className="packs-team-share" style={{ marginTop: 14 }}>
                  <Icon name="users" size={16} />
                  <div className="packs-team-share__main">
                    <select className="ui-input ui-input--36" aria-label="Team to share with" value={shareTo ?? ""} onChange={(event) => setShareTeam(Number(event.target.value))}>
                      {shareable.map((t) => (
                        <option key={t.id} value={t.id}>{t.name} · {t.member_count} {t.member_count === 1 ? "member" : "members"} · not shared with this pack</option>
                      ))}
                    </select>
                  </div>
                  <select
                    className="ui-input ui-input--36 packs-team-share__role"
                    aria-label="What the team can do"
                    value={shareRole}
                    onChange={(event) => setShareRole(event.target.value)}
                  >
                    <option value="editor">Can edit</option>
                    <option value="viewer">Can view</option>
                  </select>
                  <button
                    type="button"
                    className="ui-btn ui-btn--pack ui-btn--34"
                    disabled={busy || shareTo == null}
                    onClick={() =>
                      share(
                        { team_id: shareTo, team_role: shareRole },
                        `Shared with ${shareable.find((t) => t.id === shareTo).name}${shareRole === "viewer" ? " to view" : ""}`,
                      )
                    }
                  >
                    Share with team
                  </button>
                </div>
              )
            )}
          </>
        )}

        <div className="ui-label packs-section-label">Members · {members.length}</div>
        <div className="packs-people">
          {members.map((member) => {
            const isMe = member.user_id === me;
            const isHere = hereIds.has(member.user_id);
            const seen = member.seen_at ? `Seen ${timeAgo(member.seen_at)}` : "Not opened yet";
            return (
              <div key={member.user_id} className="packs-person">
                <Avatar person={{ id: member.user_id, name: member.name || member.email }} online={isHere} />
                <div className="packs-person__main">
                  <div className="packs-person__name">{member.name || member.email}{isMe ? " (you)" : ""}</div>
                  <div className="packs-person__meta">
                    {member.email}
                    {!isMe && (
                      <>
                        {member.email ? " · " : ""}
                        {isHere ? <span className="packs-person__here">● Here now</span> : seen}
                      </>
                    )}
                  </div>
                </div>
                {owner && member.role !== "owner" ? (
                  <>
                    <select
                      className="ui-input packs-person__role"
                      aria-label={`Role of ${member.name || member.email}`}
                      value={member.role}
                      disabled={busy}
                      onChange={(event) => {
                        const next = event.target.value;
                        // A pack has one owner: handing it over is asked first, as it cannot be taken back here.
                        if (next === "owner") setHandingTo(member);
                        else run(() => api.changeMember(pack.uuid, member.user_id, next), `${member.name || member.email} is ${next === "viewer" ? "a viewer" : "an editor"} now`);
                      }}
                    >
                      <option value="editor">Editor</option>
                      <option value="viewer">Viewer</option>
                      <option value="owner">Owner</option>
                    </select>
                    <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Take ${member.name || member.email} off the pack`} onClick={() => setRemoving(member)}>
                      <Icon name="x" size={15} />
                    </button>
                  </>
                ) : (
                  <span className="packs-person__fixed">{ROLE_WORDS[member.role] ?? member.role}</span>
                )}
              </div>
            );
          })}
        </div>

        {owner && invites.length > 0 && (
          <>
            <div className="ui-label packs-section-label">Pending · {invites.length}</div>
            <div className="packs-people">
              {invites.map((inv) => (
                <div key={inv.id} className="packs-person">
                  <span className="packs-history__system" style={{ width: 28, height: 28, borderRadius: 14 }}><Icon name="mail" size={14} /></span>
                  <div className="packs-person__main">
                    <div className="packs-person__name">{inv.email}</div>
                    <div className="packs-person__meta">Invited {shortDate(inv.created_at)} as {ROLE_WORDS[inv.role]} · {daysLeft(inv.expires_at)}</div>
                  </div>
                  <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" disabled={busy} onClick={() => run(() => api.resendPackInvite(pack.uuid, inv.id), `Sent the invitation to ${inv.email} again`)}>
                    Resend
                  </button>
                  <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" disabled={busy} onClick={() => run(() => api.revokePackInvite(pack.uuid, inv.id), `Withdrew the invitation to ${inv.email}`)}>
                    Revoke
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </Dialog>
      {removing && (
        <ConfirmDialog
          title={`Take ${removing.name || removing.email} off ${pack.name}?`}
          text={`They can no longer open it. What they added stays in the pack. Last seen ${removing.seen_at ? dateAtTime(removing.seen_at) : "never"}.`}
          icon="users"
          iconTone="danger"
          confirmLabel="Take off"
          confirmTone="danger"
          onCancel={() => setRemoving(null)}
          onConfirm={() => {
            const member = removing;
            setRemoving(null);
            run(() => api.removeMember(pack.uuid, member.user_id), `${member.name || member.email} is no longer in ${pack.name}`);
          }}
        />
      )}
      {handingTo && (
        <ConfirmDialog
          title={`Make ${handingTo.name || handingTo.email} the owner of ${pack.name}?`}
          text="A pack has one owner. You become an editor: you can still change what is in it, but only they can manage members, finish, reopen or delete it, or hand it back to you."
          icon="users"
          iconTone="warn"
          confirmLabel="Make owner"
          onCancel={() => setHandingTo(null)}
          onConfirm={() => {
            const member = handingTo;
            setHandingTo(null);
            run(() => api.changeMember(pack.uuid, member.user_id, "owner"), `${member.name || member.email} owns ${pack.name} now. You are an editor.`);
          }}
        />
      )}
    </>
  );
};

export default MembersDialog;
