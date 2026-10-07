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
 * sketched routes only); threats only ever to this session. A file that is not one of these says so
 * and is left out.
 */

const ICONS = { lps: "mapPin", msnx: "route", ths: "diamond" };

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

const destinationsFor = (kind, pack) => {
  if (kind === "ths") return null;
  const options = [];
  if (pack) {
    options.push({
      value: "pack",
      label: pack.name,
      disabled: kind === "msnx" || pack.finished,
      title: kind === "msnx" ? "Mission files cannot go into a pack yet." : pack.finished ? "This pack is finished." : undefined,
    });
  }
  options.push({ value: "library", label: "Library" }, { value: "session", label: "Session" });
  return options;
};

const noteFor = (item, pack) => {
  if (item.kind === "ths") return { tone: "warn", text: "Threats are never saved or shared with a pack." };
  if (item.kind === "msnx" && pack) return { tone: "warn", text: "Mission files cannot go into a pack yet. Sketched routes only." };
  if (item.destination === "pack") return { tone: "pack", text: `Everyone in ${pack.name} (${pack.memberCount} ${pack.memberCount === 1 ? "member" : "members"}) will see these points.` };
  if (item.destination === "library") return { tone: "", text: "Saved to your Library and shown on the map." };
  return { tone: "", text: "Shown on the map until you close this tab." };
};

export const defaultDestination = (kind, pack) => {
  if (kind === "lps") return pack && !pack.finished ? "pack" : "library";
  if (kind === "msnx") return "session";
  return "session";
};

const ImportReviewDialog = ({ items: initial, pack, onCancel, onImport }) => {
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
                {item.kind === "lps" && (
                  <input
                    className="ui-input imports__row-input"
                    aria-label={`Name for the points in ${item.file.name}`}
                    value={item.name}
                    maxLength={100}
                    onChange={(event) => update(index, { name: event.target.value.toUpperCase() })}
                  />
                )}
              </div>
              <div>
                {destinationsFor(item.kind, pack) ? (
                  <Segmented
                    label={`Where ${item.file.name} goes`}
                    options={destinationsFor(item.kind, pack)}
                    value={item.destination}
                    onChange={(destination) => update(index, { destination })}
                  />
                ) : (
                  <div className="imports__fixed imports__fixed--warn">
                    <Icon name="lock" size={13} />
                    Session only
                  </div>
                )}
                {(() => {
                  const note = noteFor(item, pack);
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
