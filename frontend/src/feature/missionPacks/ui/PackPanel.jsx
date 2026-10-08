import React, { useEffect, useMemo, useRef, useState } from "react";
import { PanelHead } from "../../shell/Dock";
import Avatar, { AvatarStack, shortName } from "../../ui/Avatar";
import Chip from "../../ui/Chip";
import Icon from "../../ui/Icon";
import MoreMenu from "../../ui/MoreMenu";
import { clockTime, shortDate, timeAgo } from "../../ui/time";
import * as packApi from "../packApi";
import { changedSince } from "../usePackSeen";
import "./packs.css";

/*
 * The Pack panel of the dock (screens PackItems and PackHistory): what the pack is for, who is in it
 * and who has it open now, then its items grouped by kind, or its history, newest first. A violet dot
 * marks what someone else changed since this person last looked; an item copied from the Library
 * says so, and offers Update when the original has moved on.
 */

const KIND_ICON = { lz: "hexagon", route: "route", pointset: "mapPin" };
const GROUPS = [
  { kind: "lz", label: "LZ/PZ" },
  { kind: "route", label: "Routes" },
  { kind: "pointset", label: "Point sets" },
];
const ROLE_WORDS = { owner: "the owner", editor: "an editor", viewer: "a viewer" };

/** "4 min ago" within the hour, "13:21" today, else "Oct 4". */
export const whenText = (value, now = new Date()) => {
  if (!value) return "";
  const text = timeAgo(value, now);
  if (text === "just now" || text.endsWith("min ago")) return text;
  return text.endsWith("h ago") ? clockTime(value) : shortDate(value, now);
};

const itemDetail = (item) => {
  if (item.kind === "route") {
    const n = item.data?.routes?.length ?? 0;
    return `${n} ${n === 1 ? "route" : "routes"}`;
  }
  if (item.kind === "pointset") {
    const n = Array.isArray(item.data) ? item.data.length : 0;
    return `${n.toLocaleString("en-US")} ${n === 1 ? "point" : "points"}`;
  }
  return null;
};

const ItemRow = ({ item, isOpen, isNew, editing, readOnly, canEdit, onOpen, actions }) => {
  const who = item.updated_by ?? item.created_by;
  const detail = itemDetail(item);
  const original = item.source?.original;
  return (
    <div className={`packs-item${isOpen ? " packs-item--open" : ""}`}>
      <button type="button" className="packs-item__hit" onClick={() => onOpen(item)} aria-label={`${isOpen ? "Go to" : "Open"} ${item.name}`}>
        <span className="packs-item__icon" aria-hidden="true"><Icon name={KIND_ICON[item.kind] ?? "file"} size={16} /></span>
        <span className="packs-item__main">
          <span className="packs-item__name">
            <span>{item.name}</span>
            {isNew && <span className="packs-item__new" title="Changed since you looked" aria-label="changed since you looked" />}
          </span>
          <span className="packs-item__meta">
            {who && <Avatar person={who} size="xs" title="" />}
            <span>
              {[detail, item.pendingCreate ? "Sending…" : who ? shortName(who.name) : null, whenText(item.updated_at)].filter(Boolean).join(" · ")}
            </span>
          </span>
          {(item.source || editing) && (
            <span className="packs-item__chips">
              {editing && <Chip tone="pack">{shortName(editing.name)} editing</Chip>}
              {item.source && <Chip icon="library" dot={false}>From Library</Chip>}
              {original === "changed" && <Chip tone="warn" icon="refresh">Original changed</Chip>}
              {original === "deleted" && <Chip dot={false}>Original deleted</Chip>}
            </span>
          )}
        </span>
      </button>
      {original === "changed" && canEdit && !readOnly && (
        <button type="button" className="ui-link" style={{ marginRight: 4 }} onClick={() => actions.updateFromOriginal(item)}>Update</button>
      )}
      <MoreMenu
        label={`More actions for ${item.name}`}
        items={[
          { icon: isOpen ? "x" : "eye", title: isOpen ? "Close here" : "Open", text: isOpen ? "It stays in the pack" : undefined, onSelect: () => (isOpen ? actions.close(item) : onOpen(item)), hidden: item.kind === "lz" && isOpen },
          { icon: "pencil", title: "Rename", onSelect: () => actions.rename(item), hidden: readOnly || !canEdit },
          { icon: "copy", title: "Save a copy to Library…", onSelect: () => actions.saveCopy(item), disabled: item.pendingCreate },
          { icon: "refresh", title: "Update from original…", onSelect: () => actions.updateFromOriginal(item), hidden: original !== "changed" || readOnly || !canEdit },
          { divider: true, hidden: readOnly || !canEdit },
          { icon: "trash", title: "Remove from pack", danger: true, onSelect: () => actions.remove(item), hidden: readOnly || !canEdit },
        ]}
      />
    </div>
  );
};

// Why the server did not apply an edit (pack_ops.py), in words.
const REASON_WORDS = {
  item_missing: "the item had already been removed",
  target_missing: "what it changed had already been removed",
  item_exists: "the same item had already been added",
  element_exists: "the same thing had already been added",
  not_an_array: "the item had changed shape under it",
  not_an_object: "the item had changed shape under it",
};

const eventLine = (event, items) => {
  if (event.status === "skipped") {
    const why = REASON_WORDS[event.reason] ?? "it no longer applied";
    const what = event.summary ? ` (${event.summary.replace(/\.$/, "")})` : "";
    return `Edit skipped: ${why}. ${shortName(event.actor?.name)}’s change was not applied${what}.`;
  }
  return event.summary || `${shortName(event.actor?.name)} changed ${items.get(event.item)?.name ?? "the pack"}.`;
};

const dayLabel = (value, now = new Date()) => {
  const date = new Date(typeof value === "string" && !/[zZ]|[+-]\d\d:?\d\d$/.test(value) ? `${value}Z` : value);
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (date >= start) return "Earlier today";
  if (date >= new Date(start.getTime() - 86400000)) return "Yesterday";
  return shortDate(value, now);
};

const PAGE = 500; // the most events the server gives at once, and the most the history shows

// Events held and fetched, each once (two fetches can overlap), oldest first: the newest PAGE of them.
const mergeEvents = (held, more) => {
  const bySeq = new Map(held.map((event) => [event.seq, event]));
  more.forEach((event) => bySeq.set(event.seq, event));
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq).slice(-PAGE);
};

const History = ({ packUuid, headSeq, newSince, me, members, items, api }) => {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [person, setPerson] = useState("all");
  const [itemFilter, setItemFilter] = useState("all");

  // The last PAGE events, then whatever is newer each time the pack moves on (`headSeq` follows every
  // event as it arrives): one fetch at a time, from the newest held.
  const sync = useRef(null);
  const head = headSeq ?? 0;
  useEffect(() => {
    if (sync.current?.pack !== packUuid) {
      sync.current = { pack: packUuid, head, newest: null, busy: false };
      setEvents([]);
      setLoading(true);
    }
    const state = sync.current;
    state.head = Math.max(state.head, head);
    if (state.busy) return;
    state.busy = true;
    (async () => {
      try {
        while (sync.current === state && (state.newest === null || state.head > state.newest)) {
          const upTo = state.head;
          let page = await api.getEvents(packUuid, state.newest ?? Math.max(0, upTo - PAGE), PAGE);
          // More than a page behind (pages are oldest first): the newest page instead, never the oldest.
          if (page?.has_more) page = await api.getEvents(packUuid, Math.max(0, (page.head_seq ?? upTo) - PAGE), PAGE);
          if (sync.current !== state) return;
          const more = page?.events ?? [];
          state.newest = Math.max(upTo, ...more.map((event) => event.seq));
          setEvents((held) => mergeEvents(held, more));
          setFailed(false);
        }
      } catch {
        if (sync.current === state) setFailed(true);
      } finally {
        state.busy = false;
        if (sync.current === state) setLoading(false);
      }
    })();
  }, [packUuid, head, api]);
  // Closed: answers still on their way are dropped.
  useEffect(() => () => {
    sync.current = null;
  }, []);

  const byUuid = useMemo(() => new Map(items.map((item) => [item.uuid, item])), [items]);
  const shown = useMemo(
    () =>
      [...events]
        .reverse()
        .filter((e) => person === "all" || String(e.actor?.id) === person)
        .filter((e) => itemFilter === "all" || e.item === itemFilter),
    [events, person, itemFilter],
  );
  const isFresh = (e) => newSince != null && e.seq > newSince && e.actor?.id !== me;
  const fresh = shown.filter(isFresh);
  const older = shown.filter((e) => !isFresh(e));

  const groups = [];
  older.forEach((event) => {
    const label = dayLabel(event.created_at);
    if (groups.length === 0 || groups[groups.length - 1].label !== label) groups.push({ label, events: [] });
    groups[groups.length - 1].events.push(event);
  });

  const row = (event) => {
    const where = event.item
      ? byUuid.get(event.item)?.name ?? event.op?.name ?? "Removed item"
      : /^(member|invite)/.test(event.type ?? "")
        ? "Members"
        : "Pack";
    const system = !event.actor?.id;
    return (
      <div key={event.seq} className={`packs-history__event${event.status === "skipped" ? " packs-history__event--skipped" : ""}`}>
        {system ? (
          <span className="packs-history__system" aria-hidden="true"><Icon name="layers" size={12} /></span>
        ) : (
          <Avatar person={event.actor} size="sm" title="" />
        )}
        <div style={{ minWidth: 0 }}>
          <div className="packs-history__text">{eventLine(event, byUuid)}</div>
          <div className="packs-history__when">{clockTime(event.created_at)} · {where}</div>
        </div>
      </div>
    );
  };

  return (
    <>
      <div className="packs-history__filters">
        <select className="ui-input" aria-label="Whose changes" value={person} onChange={(event) => setPerson(event.target.value)}>
          <option value="all">Everyone</option>
          {members.map((m) => (
            <option key={m.user_id} value={String(m.user_id)}>{m.user_id === me ? "You" : m.name || m.email}</option>
          ))}
        </select>
        <select className="ui-input" aria-label="Which item" value={itemFilter} onChange={(event) => setItemFilter(event.target.value)}>
          <option value="all">All items</option>
          {items.map((item) => (
            <option key={item.uuid} value={item.uuid}>{item.name}</option>
          ))}
        </select>
      </div>
      {loading && events.length === 0 && <div className="shell-empty">Loading the history…</div>}
      {failed && <div className="shell-empty">The history could not be loaded. It is tried again when the pack changes.</div>}
      {!loading && shown.length === 0 && !failed && <div className="shell-empty">Nothing matches.</div>}
      {fresh.length > 0 && (
        <>
          <div className="packs-history__divider packs-history__divider--new">New since you looked · {fresh.length}</div>
          {fresh.map(row)}
        </>
      )}
      {groups.map((group) => (
        <React.Fragment key={group.label}>
          <div className="packs-history__divider">{group.label}</div>
          {group.events.map(row)}
        </React.Fragment>
      ))}
    </>
  );
};

/**
 * `pack`: the pack's summary; `items`: its visible items; `openIds`: the uuids open here; `people`:
 * the others who have it open now (presence); `me`: this person's user id; `newSince`: how far they
 * had looked when the panel opened. `audience`: how many can open it, its shared team included (the
 * members listed are only its own). `actions`: open, close, rename, saveCopy, updateFromOriginal,
 * remove, invite, members, addFromLibrary, finish, reopen.
 */
const PackPanel = ({ pack, members = [], audience, items = [], openIds, people = [], me, newSince = 0, readOnly, actions, tab, setTab, dropped = 0, onKeepDropped, status, api = packApi, onCollapse }) => {
  const role = pack.role;
  const memberCount = audience ?? members.length;
  const canEdit = role === "owner" || role === "editor";
  const finished = pack.status === "finished";
  const editingOf = (uuid) => people.find((p) => p.focus?.item === uuid && p.focus?.holding);
  const newCount = items.filter((item) => changedSince(item, newSince, me)).length;
  const here = people.length;

  return (
    <>
      <PanelHead title={pack.name} subtitle={`Mission Pack · you are ${ROLE_WORDS[role] ?? "a member"}`} onCollapse={onCollapse}>
        {finished ? <Chip icon="lock">Finished</Chip> : <Chip tone="pack">Active</Chip>}
      </PanelHead>
      <div className="shell-dock__body">
        {dropped > 0 && (
          <div className="shell-notice" style={{ marginBottom: 14 }}>
            <Icon name="alertTriangle" size={15} />
            <div style={{ flex: 1 }}>
              <b>{dropped === 1 ? "One of your changes was" : `${dropped} of your changes were`} not taken.</b> The pack was finished, or you can only view it now. Keep your version so the work is not lost.
              <div style={{ marginTop: 8 }}>
                <button type="button" className="ui-btn ui-btn--30" onClick={onKeepDropped}>
                  <Icon name="save" size={14} />
                  Save my version to Library
                </button>
              </div>
            </div>
          </div>
        )}
        {status === "error" && (
          <div className="shell-notice" style={{ marginBottom: 14 }}>
            <Icon name="alertTriangle" size={15} />
            <div>The pack cannot be reached. Your changes stay here and are sent when it can.</div>
          </div>
        )}
        {pack.description && <div className="packs-panel__intro">{pack.description}</div>}
        <div className="packs-panel__people">
          <AvatarStack people={members.map((m) => ({ id: m.user_id, name: m.name || m.email }))} max={4} size="sm" />
          <span>
            <b>{memberCount} {memberCount === 1 ? "member" : "members"}</b>
            {here > 0 && <span className="packs-panel__here"> · {here} here now</span>}
          </span>
          <span style={{ flex: 1 }} />
          <button type="button" className="ui-btn ui-btn--28" onClick={role === "owner" ? actions.invite : actions.members}>
            <Icon name={role === "owner" ? "userPlus" : "users"} size={14} />
            <span>{role === "owner" ? "Invite" : "Members"}</span>
          </button>
        </div>
        <div className="packs-tabs" role="tablist" aria-label="Pack">
          <button type="button" role="tab" aria-selected={tab === "items"} className={`packs-tabs__tab${tab === "items" ? " packs-tabs__tab--on" : ""}`} onClick={() => setTab("items")}>
            Items
          </button>
          <button type="button" role="tab" aria-selected={tab === "history"} className={`packs-tabs__tab${tab === "history" ? " packs-tabs__tab--on" : ""}`} onClick={() => setTab("history")}>
            History
            {newCount > 0 && tab !== "history" && <span className="packs-tabs__dot" aria-label="new changes" />}
          </button>
        </div>

        {tab === "history" ? (
          <History packUuid={pack.uuid} headSeq={pack.head_seq} newSince={newSince} me={me} members={members} items={items} api={api} />
        ) : items.length === 0 ? (
          <div className="shell-empty">
            {readOnly
              ? "This pack is empty."
              : "Nothing in this pack yet. Add from your Library, or set a target, sketch a route or import local points while the pack is open."}
          </div>
        ) : (
          GROUPS.map((group) => {
            const list = items.filter((item) => item.kind === group.kind);
            if (list.length === 0) return null;
            return (
              <React.Fragment key={group.kind}>
                <div className="shell-section" style={{ marginTop: 6 }}>
                  <div className="ui-label">
                    {group.label}
                    <span className="ui-label__count">{list.length}</span>
                  </div>
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 2, marginBottom: 12 }}>
                  {list.map((item) => (
                    <ItemRow
                      key={item.uuid}
                      item={item}
                      isOpen={openIds.has(item.uuid)}
                      isNew={changedSince(item, newSince, me)}
                      editing={editingOf(item.uuid)}
                      readOnly={readOnly}
                      canEdit={canEdit}
                      onOpen={actions.open}
                      actions={actions}
                    />
                  ))}
                </div>
              </React.Fragment>
            );
          })
        )}
      </div>
      {(canEdit || role === "owner") && (
        <div className="shell-dock__foot">
          {!finished && canEdit && (
            <button type="button" className="ui-btn ui-btn--38 ui-btn--grow" onClick={actions.addFromLibrary}>
              <Icon name="library" size={15} />
              <span>Add from Library</span>
            </button>
          )}
          {role === "owner" &&
            (finished ? (
              <button type="button" className="ui-btn ui-btn--pack ui-btn--38 ui-btn--grow" onClick={actions.reopen}>
                <Icon name="refresh" size={15} />
                <span>Reopen pack</span>
              </button>
            ) : (
              <button type="button" className="ui-btn ui-btn--38" onClick={actions.finish}>
                <Icon name="flag" size={15} />
                <span>Finish pack</span>
              </button>
            ))}
        </div>
      )}
    </>
  );
};

export default PackPanel;
