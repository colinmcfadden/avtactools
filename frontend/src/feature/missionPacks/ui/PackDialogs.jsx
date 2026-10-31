import React, { useMemo, useState } from "react";
import Chip from "../../ui/Chip";
import Dialog from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import { dateAndTime, dateAtTime, shortDate } from "../../ui/time";
import "./packs.css";

/*
 * The pack dialogs (screen PackDialogs) that are more than a confirmation: copying a Library item into
 * a pack, choosing several Library items to add to the open pack, and the banner a finished pack shows.
 */

const KIND_WORDS = { lz: "LZ/PZ", route: "route set", pointset: "point set" };

// Everyone who can open the pack, the team it is shared with included, as the switcher counts them.
const people = (p) => {
  const n = p.audience_count ?? p.member_count;
  return `${n} ${n === 1 ? "member" : "members"}`;
};

/** A: copying one Library item into a pack of the person's choice. */
export const AddToPackDialog = ({ itemName, packs = [], onAdd, onCancel }) => {
  const usable = (p) => p.status !== "finished" && p.role !== "viewer";
  const [chosen, setChosen] = useState(packs.find(usable)?.uuid ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  return (
    <Dialog
      title="Add to a Mission Pack"
      subtitle={`Adding “${itemName}”.`}
      icon="layers"
      iconTone="pack"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      note="A copy goes into the pack. Your Library item stays as it is, and you can update the copy from it later."
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className="ui-btn ui-btn--pack ui-btn--38"
            disabled={busy || !chosen}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await onAdd(packs.find((p) => p.uuid === chosen));
              } catch (err) {
                setError(err?.userMessage || err?.message || "It could not be added. Try again.");
                setBusy(false);
              }
            }}
          >
            <Icon name="plus" size={15} />
            {busy ? "Adding" : "Add copy"}
          </button>
        </>
      }
    >
      {packs.length === 0 ? (
        <p className="ui-dialog__text">You are not in a pack yet. Make one from the workspace switcher at the top left.</p>
      ) : (
        <div role="radiogroup" aria-label="Pack" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {packs.map((p) => {
            const can = usable(p);
            return (
              <label key={p.uuid} className={`ui-choice${chosen === p.uuid ? " ui-choice--selected" : ""}`} style={can ? undefined : { opacity: 0.6 }}>
                <input type="radio" name="add-to-pack" checked={chosen === p.uuid} disabled={!can} onChange={() => setChosen(p.uuid)} />
                <span>
                  <span className="ui-choice__title">{p.name}</span>
                  <span className="ui-choice__text">
                    {p.status === "finished"
                      ? `Finished ${shortDate(p.finished_at)}. Reopen it to add items.`
                      : `${people(p)} · ${p.role === "viewer" ? "you can view only" : "you can edit"}`}
                  </span>
                </span>
              </label>
            );
          })}
        </div>
      )}
      {error && <p className="ui-dialog__text" style={{ color: "#f3b1ac", marginTop: 10 }}>{error}</p>}
    </Dialog>
  );
};

const TABS = [
  { key: "lz", label: "LZ/PZ" },
  { key: "route", label: "Routes" },
  { key: "pointset", label: "Local points" },
];

/**
 * Choosing Library items to copy into the open pack (the pack panel's Add from Library). `library`:
 * { lz: [...], route: [...], pointset: [...] } of Library entries. Imported AMPS missions cannot go
 * into a pack yet and are listed as such.
 */
export const AddFromLibraryDialog = ({ packName, library, onAdd, onCancel }) => {
  const [tab, setTab] = useState("lz");
  const [picked, setPicked] = useState({});
  const [busy, setBusy] = useState(false);
  const count = Object.values(picked).filter(Boolean).length;
  const rows = useMemo(() => library[tab] ?? [], [library, tab]);
  const key = (kind, entry) => `${kind}:${entry.id}`;

  return (
    <Dialog
      title={`Add to ${packName}`}
      subtitle="Choose what to copy in from your Library. Your Library items stay as they are."
      icon="library"
      iconTone="pack"
      width="xwide"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      note={count ? `${count} chosen` : "Nothing chosen yet"}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className="ui-btn ui-btn--pack ui-btn--38"
            disabled={busy || count === 0}
            onClick={async () => {
              setBusy(true);
              const chosen = [];
              Object.entries(picked).forEach(([k, on]) => {
                if (!on) return;
                const [kind, id] = k.split(":");
                const entry = (library[kind] ?? []).find((e) => String(e.id) === id);
                if (entry) chosen.push({ kind, entry });
              });
              try {
                await onAdd(chosen);
              } finally {
                setBusy(false);
              }
            }}
          >
            <Icon name="plus" size={15} />
            {busy ? "Adding" : count > 1 ? `Add ${count} copies` : "Add copy"}
          </button>
        </>
      }
    >
      <div className="packs-tabs" role="tablist" aria-label="What to show">
        {TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={tab === t.key} className={`packs-tabs__tab${tab === t.key ? " packs-tabs__tab--on" : ""}`} onClick={() => setTab(t.key)}>
            {t.label}
            <span style={{ color: "#8a94a1", fontWeight: 600 }}>{(library[t.key] ?? []).length}</span>
          </button>
        ))}
      </div>
      <div style={{ maxHeight: 320, overflowY: "auto" }}>
        {rows.length === 0 && <div className="shell-empty">Nothing saved here yet.</div>}
        {rows.map((entry) => {
          const mission = tab === "route" && entry.kind === "mission";
          const k = key(tab, entry);
          return (
            <label key={k} className="packs-person" style={{ cursor: mission ? "not-allowed" : "pointer", opacity: mission ? 0.6 : 1 }}>
              <input
                type="checkbox"
                disabled={mission}
                checked={Boolean(picked[k])}
                onChange={(event) => setPicked((prev) => ({ ...prev, [k]: event.target.checked }))}
                style={{ width: 15, height: 15, margin: 0, accentColor: "var(--pack)" }}
              />
              <span className="packs-person__main">
                <span className="packs-person__name" style={{ display: "block" }}>{entry.name}</span>
                <span className="packs-person__meta" style={{ display: "block" }}>
                  {mission ? "AMPS mission files cannot go into a pack yet" : `Updated ${dateAndTime(entry.updated_at)}`}
                </span>
              </span>
              {mission && <Chip dot={false}>Mission file</Chip>}
            </label>
          );
        })}
      </div>
    </Dialog>
  );
};

/** The banner over the map while a finished pack is open (screen PackFinished). */
export const FinishedBanner = ({ pack, isOwner, onSaveCopy, onDuplicate, onReopen }) => (
  <div className="packs-banner" role="status">
    <div className="packs-banner__text">
      <Icon name="lock" size={16} />
      <span>
        <b>{pack.name}</b> was finished by {pack.finished_by?.name || "its owner"}
        {pack.finished_at ? ` on ${dateAtTime(pack.finished_at)}` : ""}. It is read-only for everyone.
      </span>
    </div>
    <div className="packs-banner__actions">
      {onSaveCopy && (
        <button type="button" className="ui-btn ui-btn--30" onClick={onSaveCopy}>
          <Icon name="copy" size={14} />
          Save a copy to Library
        </button>
      )}
      <button type="button" className="ui-btn ui-btn--30" onClick={onDuplicate}>
        <Icon name="layers" size={14} />
        Duplicate as new pack
      </button>
      {isOwner && (
        <button type="button" className="ui-btn ui-btn--pack ui-btn--30" onClick={onReopen}>
          <Icon name="refresh" size={14} />
          Reopen pack
        </button>
      )}
    </div>
  </div>
);

export const kindWords = (kind) => KIND_WORDS[kind] ?? "item";
