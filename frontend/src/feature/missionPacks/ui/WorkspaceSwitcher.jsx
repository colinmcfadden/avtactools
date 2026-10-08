import React, { useEffect, useState } from "react";
import Chip from "../../ui/Chip";
import Icon from "../../ui/Icon";
import Menu from "../../ui/Menu";
import { shortDate } from "../../ui/time";
import "./packs.css";

/*
 * The workspace switcher (screen Switcher): where edits go. The Library first, then the packs this
 * person is in, with invitations waiting above them. Switching never discards the Library's open
 * work: it stays as it was, and is there again on the way back. Only the open pack says who else
 * is in it: the others would need a connection each to know. A pack's members are everyone who can
 * open it, the team it is shared with included.
 */

const ROLE_WORDS = { owner: "Owner", editor: "Editor", viewer: "Viewer" };
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

const countsText = (counts = {}) =>
  [
    counts.lz && `${counts.lz} LZ/PZ`,
    counts.route && plural(counts.route, "route set"),
    counts.pointset && plural(counts.pointset, "point set"),
  ]
    .filter(Boolean)
    .join(" · ");

const InviteCard = ({ invite, onAccept, onDecline }) => {
  const [busy, setBusy] = useState(false);
  const what = invite.pack?.name ?? invite.team?.name ?? "a pack";
  const run = async (fn) => {
    setBusy(true);
    try {
      await fn(invite);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="packs-switcher__invite">
      <span>
        <b>{invite.invited_by?.name || "Someone"}</b> invited you to {invite.team ? "the team " : ""}
        <b>{what}</b>
        {invite.pack ? ` as ${ROLE_WORDS[invite.role] ?? invite.role}` : ""}
      </span>
      <div className="packs-switcher__invite-actions">
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" disabled={busy} onClick={() => run(onDecline)}>Decline</button>
        <button type="button" className="ui-btn ui-btn--pack ui-btn--30" disabled={busy} onClick={() => run(onAccept)}>Accept</button>
      </div>
    </div>
  );
};

const WorkspaceSwitcher = ({
  open,
  onClose,
  anchorRef,
  current = null,
  here = 0,
  packs = [],
  invites = [],
  teams = [],
  library = {},
  canCreate = true,
  onPick,
  onNewPack,
  onManageTeams,
  onAccept,
  onDecline,
  onOpen,
}) => {
  // Someone may have added this person to a pack meanwhile: ask again each time it opens.
  useEffect(() => {
    if (open) onOpen?.();
    // Only on opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const pick = (uuid) => {
    onClose();
    onPick(uuid);
  };
  const active = packs.filter((p) => p.status !== "finished");
  const finished = packs.filter((p) => p.status === "finished");
  const libraryText = [
    library.lz != null && plural(library.lz, "LZ/PZ", "LZ/PZs"),
    library.routes != null && plural(library.routes, "route set"),
    library.points != null && plural(library.points, "point set"),
  ]
    .filter(Boolean)
    .join(", ");

  return (
    <Menu open={open} onClose={onClose} anchorRef={anchorRef} label="Workspace" width={400} className="packs-switcher">
      {invites.map((invite) => (
        <InviteCard key={invite.id} invite={invite} onAccept={onAccept} onDecline={onDecline} />
      ))}
      <div className="packs-switcher__head"><div className="ui-label">Workspace</div></div>
      <button
        type="button"
        role="menuitem"
        aria-current={current === null ? "true" : undefined}
        className={`packs-switcher__row${current === null ? " packs-switcher__row--current" : ""}`}
        onClick={() => pick(null)}
      >
        <span className="packs-switcher__icon"><Icon name="library" size={17} /></span>
        <span style={{ flex: 1, minWidth: 0 }}>
          <span className="packs-switcher__name">Library</span>
          <span className="packs-switcher__meta">Personal{libraryText ? ` · ${libraryText}` : ""}</span>
          {library.unsaved > 0 && (
            <span className="packs-switcher__chips">
              <Chip tone="warn">{plural(library.unsaved, "LZ/PZ")} with unsaved changes</Chip>
            </span>
          )}
        </span>
      </button>

      <div className="packs-switcher__head">
        <div className="ui-label">Mission Packs</div>
        {canCreate && (
          <button
            type="button"
            className="ui-link"
            style={{ fontSize: 12 }}
            onClick={() => {
              onClose();
              onNewPack();
            }}
          >
            New pack
          </button>
        )}
      </div>
      {packs.length === 0 && (
        <div className="packs-switcher__empty">
          No packs yet. A Mission Pack is a shared space for one operation: everyone in it edits the same LZ/PZs, routes and point sets.
        </div>
      )}
      {[...active, ...finished].map((p) => {
        const isCurrent = p.uuid === current;
        const done = p.status === "finished";
        const counts = countsText(p.item_counts);
        return (
          <button
            key={p.uuid}
            type="button"
            role="menuitem"
            aria-current={isCurrent ? "true" : undefined}
            className={`packs-switcher__row packs-switcher__row--pack${isCurrent ? " packs-switcher__row--current" : ""}${done ? " packs-switcher__row--finished" : ""}`}
            onClick={() => pick(p.uuid)}
          >
            <span className="packs-switcher__icon"><Icon name={done ? "lock" : "layers"} size={17} /></span>
            <span style={{ flex: 1, minWidth: 0 }}>
              <span className="packs-switcher__name">{p.name}</span>
              <span className="packs-switcher__meta">
                {done
                  ? `Finished ${shortDate(p.finished_at)} · read-only`
                  : `${plural(p.audience_count ?? p.member_count ?? 1, "member")} · you are ${ROLE_WORDS[p.role] ?? p.role}`}
              </span>
              {!done && (counts || (isCurrent && here > 0)) && (
                <span className="packs-switcher__chips">
                  {isCurrent && here > 0 && <Chip tone="ok">{here} here now</Chip>}
                  {counts && <Chip dot={false}>{counts}</Chip>}
                </span>
              )}
            </span>
          </button>
        );
      })}
      <div className="ui-menu__divider" style={{ margin: "6px 4px" }} />
      <div className="packs-switcher__foot">
        <span>
          {teams.length === 0
            ? "You are not in a team yet"
            : teams.length === 1
              ? teams[0].name
              : `${teams[0].name} and ${plural(teams.length - 1, "other team")}`}
        </span>
        <button
          type="button"
          className="ui-link"
          style={{ fontSize: 12 }}
          onClick={() => {
            onClose();
            onManageTeams();
          }}
        >
          Manage teams
        </button>
      </div>
    </Menu>
  );
};

export default WorkspaceSwitcher;
