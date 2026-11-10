import React, { useState } from "react";
import Chip from "../ui/Chip";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { KIND_LABELS } from "./detectImport";
import "./imports.css";

/*
 * The one review every import goes through (docs/MENU_REDESIGN.md §6, screen ImportReview): what each
 * file is, what is in it, and where it should live. Local points can go to the open pack, the Library
 * or just this session; a mission file to the Library or this session (not a pack: v1 packs hold
 * sketched routes only); a flight plan or GPX, which opens as sketched routes, to any of the three; threats
 * to the open pack, or the Library as a set named for the file, or this tab where the account has no cloud
 * save; a map overlay to the Library or this session. A file that is not one of these says so and is left out.
 */

const ICONS = { lps: "mapPin", msnx: "route", ths: "diamond", routefile: "route", overlay: "image" };

// What the name typed in the review names, for the files that are named there.
const NAME_WORDS = { lps: "points", ths: "threats", routefile: "routes", overlay: "overlay" };

const Segmented = ({ options, value, onChange, label }) => (
  <div className="imports__seg" role="radiogroup" aria-label={label}>
    {options.map((option) => (
      <button
        key={option.value}
        type="button"
        role="radio"
        aria-checked={value === option.value}
        disabled={option.disabled}
        title={option.title}
        className={`imports__seg-item${value === option.value ? " imports__seg-item--on" : ""}${option.disabled ? " imports__seg-item--off" : ""}`}
        onClick={() => onChange(option.value)}
      >
        {option.label}
      </button>
    ))}
  </div>
);

// Why the open pack cannot take this file, or null if it can. A finished pack, or one this person may
// only view, refuses every change: offering it would only lose the points.
const packRefusal = (kind, pack) => {
  if (kind === "msnx") return "Mission files cannot go into a pack yet.";
  if (kind === "overlay") return "Overlays cannot go into a pack yet.";
  if (pack.finished) return "This pack is finished.";
  if (pack.role === "viewer") return "You can only view this pack.";
  return null;
};

const destinationsFor = (kind, pack, threatsSaved = false, overlaysSaved = false) => {
  // An account that cannot save has one place for an overlay, which is said rather than offered.
  if (kind === "overlay" && !overlaysSaved && !pack) return null;
  if (kind === "ths" && !pack) return null;
  const options = [];
  if (pack) {
    const refusal = packRefusal(kind, pack);
    options.push({ value: "pack", label: pack.name, disabled: Boolean(refusal), title: refusal ?? undefined });
  }
  // A set of threats kept by this account is in the Library; one that cannot be is in this tab.
  if (kind === "ths") options.push(threatsSaved ? { value: "library", label: "Library" } : { value: "session", label: "Session" });
  else if (kind === "overlay" && !overlaysSaved) options.push({ value: "session", label: "Session" });
  else options.push({ value: "library", label: "Library" }, { value: "session", label: "Session" });
  return options;
};

// While a pack is open only its own things are shown (docs/MISSION_PACKS.md §5a): what goes to the
// Library or stays in this session appears once the person switches back to the Library.
const noteFor = (item, pack, threatsSaved, overlaysSaved) => {
  if (item.kind === "ths") {
    if (item.destination === "pack") return { tone: "pack", text: `Everyone in ${pack.name} (${pack.memberCount} ${pack.memberCount === 1 ? "member" : "members"}) will see these threats and can change them.` };
    if (!threatsSaved) return { tone: "warn", text: "Cloud save is off for this account, so these stay in this tab." };
    return { tone: "", text: pack ? "Saved to your Library. Shown when you switch back to Library." : "Saved to your Library and shown on the map." };
  }
  // An overlay is this person's own map layer: drawn in a pack as out of one, and never sent to it.
  if (item.kind === "overlay") {
    if (item.destination === "library") return { tone: "", text: "Saved to your Library and drawn on the map, with a switch in Layers." };
    return { tone: overlaysSaved ? "" : "warn", text: `${overlaysSaved ? "" : "Cloud save is off for this account. "}Drawn on the map until you close this tab, with a switch in Layers.` };
  }
  if (item.kind === "msnx" && pack) return { tone: "warn", text: "Mission files cannot go into a pack yet (sketched routes only). Shown when you switch back to Library." };
  if (item.destination === "pack") return { tone: "pack", text: `Everyone in ${pack.name} (${pack.memberCount} ${pack.memberCount === 1 ? "member" : "members"}) will see these ${item.kind === "routefile" ? "routes" : "points"}.` };
  if (item.destination === "library") {
    return { tone: "", text: pack ? "Saved to your Library. Shown when you switch back to Library." : "Saved to your Library and shown on the map." };
  }
  return { tone: "", text: pack ? "Kept until you close this tab. Shown when you switch back to Library." : "Shown on the map until you close this tab." };
};

export const defaultDestination = (kind, pack, threatsSaved = false, overlaysSaved = false) => {
  if (kind === "lps") return pack && !packRefusal(kind, pack) ? "pack" : "library";
  if (kind === "overlay") return overlaysSaved ? "library" : "session";
  if (kind === "routefile") return pack && !packRefusal(kind, pack) ? "pack" : "session";
  if (kind === "ths") {
    if (pack && !packRefusal(kind, pack)) return "pack";
    return threatsSaved ? "library" : "session";
  }
  return "session";
};

const ImportReviewDialog = ({ items: initial, pack, threatsSaved = false, overlaysSaved = false, onCancel, onImport }) => {
  const [items, setItems] = useState(initial);
  const [busy, setBusy] = useState(false);
  const usable = items.filter((item) => item.kind);
  const update = (index, patch) => setItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));

  const toPack = usable.filter((item) => item.destination === "pack").length;
  const toLibrary = usable.filter((item) => item.destination === "library").length;
  const toSession = usable.length - toPack - toLibrary;
  const summary = [
    toPack && `${toPack} to ${pack?.name}`,
    toLibrary && `${toLibrary} to your Library`,
    toSession && `${toSession} ${toSession === 1 ? "stays" : "stay"} in this session`,
  ]
    .filter(Boolean)
    .join(" · ");

  const label = usable.length === 1 ? "Import 1 file" : `Import ${usable.length} files`;
  return (
    <Dialog
      title={items.length === 1 ? "Import 1 file" : `Import ${items.length} files`}
      subtitle="We worked out what each file is. Choose where it should live."
      icon="download"
      width="xwide"
      className="imports__review"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      note={summary || "Nothing here can be imported."}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className="ui-btn ui-btn--primary ui-btn--38"
            disabled={busy || usable.length === 0}
            onClick={async () => {
              setBusy(true);
              try {
                await onImport(usable);
              } finally {
                setBusy(false);
              }
            }}
          >
            <Icon name="download" size={15} />
            {busy ? "Importing" : label}
          </button>
        </>
      }
    >
      <div className="imports__rows">
        {items.map((item, index) =>
          item.kind ? (
            <div key={`${item.file.name}-${index}`} className="imports__row">
              <span className="imports__row-icon" aria-hidden="true"><Icon name={ICONS[item.kind]} size={17} /></span>
              <div style={{ minWidth: 0 }}>
                <div className="imports__row-head">
                  <span className="imports__row-name">{item.file.name}</span>
                  <Chip dot={false}>{KIND_LABELS[item.kind]}</Chip>
                </div>
                <div className="imports__row-meta">{[item.summary, item.size].filter(Boolean).join(" · ")}</div>
                {item.notes?.length > 0 && <div className="imports__note imports__note--warn">Left out: {item.notes.join(", ")}</div>}
                {NAME_WORDS[item.kind] && (
                  <input
                    className="ui-input imports__row-input"
                    aria-label={`Name for the ${NAME_WORDS[item.kind]} in ${item.file.name}`}
                    value={item.name}
                    maxLength={100}
                    onChange={(event) => update(index, { name: event.target.value.toUpperCase() })}
                  />
                )}
              </div>
              <div>
                {destinationsFor(item.kind, pack, threatsSaved, overlaysSaved) ? (
                  <Segmented
                    label={`Where ${item.file.name} goes`}
                    options={destinationsFor(item.kind, pack, threatsSaved, overlaysSaved)}
                    value={item.destination}
                    onChange={(destination) => update(index, { destination })}
                  />
                ) : (
                  <div className={`imports__fixed${item.destination === "library" ? "" : " imports__fixed--warn"}`}>
                    <Icon name={item.destination === "library" ? "folder" : "lock"} size={13} />
                    {item.destination === "library" ? "Library" : "Session only"}
                  </div>
                )}
                {(() => {
                  const note = noteFor(item, pack, threatsSaved, overlaysSaved);
                  return <div className={`imports__note${note.tone ? ` imports__note--${note.tone}` : ""}`}>{note.text}</div>;
                })()}
              </div>
            </div>
          ) : (
            <div key={`${item.file.name}-${index}`} className="imports__reject">
              <span className="imports__reject-name">{item.file.name}</span>
              <span className="imports__reject-why">{item.error}</span>
              <button
                type="button"
                className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square"
                aria-label={`Remove ${item.file.name} from the list`}
                onClick={() => setItems((prev) => prev.filter((_, i) => i !== index))}
              >
                <Icon name="x" size={15} />
              </button>
            </div>
          ),
        )}
      </div>
    </Dialog>
  );
};

export default ImportReviewDialog;
