import React, { useEffect, useRef, useState } from "react";
import Avatar, { shortName } from "../ui/Avatar";
import Chip from "../ui/Chip";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import { dateAndTime, whenShort } from "../ui/time";
import { PanelHead, PanelSection } from "./Dock";
import "./shell.css";

/*
 * The LZ/PZ panel of the dock (docs/MENU_REDESIGN.md, screens Main, Saved, PackLive, PackFinished):
 * the LZ/PZs open in this session, the active one as a card with its save state, Save, 3D and ⋯; the
 * others as compact cards that make themselves active. In the Library workspace the three most recent
 * saved LZ/PZs not already open are listed under them. In a Mission Pack there is no Save: everything
 * is saved to the pack as it is made, and the cards say who else is there.
 */

export const isMac = () => typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");

const gridOf = (diagram) => diagram?.target?.mgrs || diagram?.mapData?.mgrs || "";

export const diagramTitle = (diagram, index) => diagram?.name || `LZ/PZ ${index + 1}`;

/** The chips that say how far along an LZ/PZ is, and whether it is saved. */
export const lzStateChips = (diagram, { savedAt, pack } = {}) => {
  const chips = [];
  const analyzed = diagram?.status === "analyzed";
  if (analyzed) chips.push({ key: "status", tone: "ok", text: "Analyzed" });
  else if (diagram?.target) chips.push({ key: "status", tone: "warn", text: "Target set" });
  else chips.push({ key: "status", text: "No target", hollow: true });

  if (pack) {
    if (pack.finished) chips.push({ key: "save", icon: "lock", text: "Read-only" });
    else if (pack.fromLibrary && pack.originalChanged) chips.push({ key: "save", tone: "warn", icon: "refresh", text: "Original changed" });
    else if (pack.fromLibrary) chips.push({ key: "save", icon: "library", text: "From Library" });
    else chips.push({ key: "save", tone: "pack", icon: "check", text: pack.waiting ? "Waiting to send" : "Synced" });
    return chips;
  }
  if (!analyzed && diagram?.savedId == null) chips.push({ key: "save", icon: "info", text: "Analyze to save" });
  else if (diagram?.savedId == null) chips.push({ key: "save", text: "Not saved yet", hollow: true });
  else if (diagram?.dirty) chips.push({ key: "save", tone: "warn", text: "Unsaved changes" });
  else chips.push({ key: "save", tone: "ok", icon: "check", text: savedAt ? `Saved · ${whenShort(savedAt)}` : "Saved" });
  return chips;
};

const Chips = ({ chips }) =>
  chips.map((chip) => (
    <Chip key={chip.key} tone={chip.tone} icon={chip.icon} hollow={chip.hollow} title={chip.title}>
      {chip.text}
    </Chip>
  ));

const RenameField = ({ name, onDone }) => {
  const input = useRef(null);
  const [value, setValue] = useState(name);
  useEffect(() => input.current?.select(), []);
  const commit = () => onDone(value.trim() && value.trim() !== name ? value.trim() : null);
  return (
    <input
      ref={input}
      className="ui-input shell-card__name-input"
      value={value}
      maxLength={100}
      aria-label="LZ/PZ name"
      onChange={(event) => setValue(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === "Enter") commit();
        if (event.key === "Escape") {
          event.stopPropagation();
          onDone(null);
        }
      }}
    />
  );
};

const ActiveCard = ({ diagram, index, savedAt, saving, pack, people, onSave, onRename, onView3D, menuItems, renaming, setRenaming }) => {
  const title = diagramTitle(diagram, index);
  const analyzed = diagram.status === "analyzed";
  const clean = diagram.savedId != null && !diagram.dirty;
  const finished = pack?.finished;
  const tone = pack ? " shell-card--pack" : clean ? " shell-card--saved" : "";
  return (
    <section className={`shell-card shell-card--active${tone}`} aria-label={`${title}, active`}>
      <div className="shell-card__row">
        {renaming ? (
          <RenameField
            name={title}
            onDone={(next) => {
              setRenaming(false);
              if (next) onRename(diagram.id, next);
            }}
          />
        ) : (
          <h3 className="shell-card__name" style={{ margin: 0 }}>{title}</h3>
        )}
        {!renaming && !finished && (
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square" aria-label={`Rename ${title}`} onClick={() => setRenaming(true)}>
            <Icon name="pencil" size={15} />
          </button>
        )}
      </div>
      <div className="shell-card__grid">{gridOf(diagram) || "No target yet"}</div>
      <div className="shell-card__chips">
        <Chips chips={lzStateChips(diagram, { savedAt, pack })} />
      </div>
      {people?.length > 0 && (
        <div className="shell-card__people">
          <Avatar person={people[0]} size="sm" />
          <span>{people.length === 1 ? `${shortName(people[0].name)} is also here` : `${shortName(people[0].name)} and ${people.length - 1} more are also here`}</span>
        </div>
      )}
      <div className="shell-card__actions">
        {pack ? (
          finished ? (
            <button type="button" className="ui-btn ui-btn--grow" onClick={() => onSave(diagram.id, { copy: true })}>
              <Icon name="copy" size={15} />
              <span>Save a copy</span>
            </button>
          ) : null
        ) : (
          <button
            type="button"
            className={`ui-btn ui-btn--grow${clean ? "" : " ui-btn--primary"}`}
            disabled={!analyzed || clean || saving}
            title={!analyzed ? "Analyze this LZ/PZ before saving it." : undefined}
            onClick={() => onSave(diagram.id)}
          >
            <Icon name={clean ? "check" : "save"} size={15} />
            <span>{saving ? "Saving" : clean ? "Saved" : "Save"}</span>
          </button>
        )}
        <button
          type="button"
          className={`ui-btn${pack && !finished ? " ui-btn--grow" : ""}`}
          style={{ padding: "0 11px" }}
          disabled={!diagram.target}
          onClick={() => onView3D(diagram.id)}
        >
          <Icon name="box" size={15} />
          <span>3D</span>
        </button>
        <MoreMenu label={`More actions for ${title}`} size={36} items={menuItems} />
      </div>
      <div className="shell-card__foot">
        <Icon name={pack ? "layers" : "folder"} size={13} color={pack ? "var(--pack)" : undefined} />
        {pack ? (
          <span>{finished ? `Finished by ${pack.finishedBy || "the owner"}${pack.finishedAt ? ` on ${dateAndTime(pack.finishedAt).replace(" · ", " at ")}` : ""}` : `Saved to ${pack.name} as you work. ${pack.memberCount} ${pack.memberCount === 1 ? "member" : "members"} can see it.`}</span>
        ) : (
          <>
            <span>Saves to your Library</span>
            <span className="ui-kbd">{isMac() ? "⌘ S" : "Ctrl S"}</span>
          </>
        )}
      </div>
    </section>
  );
};

const CompactCard = ({ diagram, index, savedAt, pack, people, editing, onSelect, onView3D }) => {
  const title = diagramTitle(diagram, index);
  return (
    <div className={`shell-card${pack ? " shell-card--pack" : ""}`}>
      <button type="button" className="shell-card__main shell-card__hit" onClick={() => onSelect(diagram.id)} aria-label={`Open ${title}`}>
        <div className="shell-card__name">{title}</div>
        <div className="shell-card__grid">{gridOf(diagram) || "No target yet"}</div>
        <div className="shell-card__chips">
          <Chips chips={lzStateChips(diagram, { savedAt, pack }).filter((chip) => !(pack && chip.key === "save" && chip.text === "Synced"))} />
          {editing && (
            <Chip tone="pack" dot={false}>
              <Avatar person={editing} size="xs" title="" />
              {shortName(editing.name)} editing
            </Chip>
          )}
          {!editing && people?.length > 0 && <Chip tone="pack">{shortName(people[0].name)} is here</Chip>}
        </div>
      </button>
      <button type="button" className="ui-btn ui-btn--28" disabled={!diagram.target} onClick={() => onView3D(diagram.id)} aria-label={`3D view of ${title}`}>
        <Icon name="box" size={15} />
        <span aria-hidden="true">3D</span>
      </button>
    </div>
  );
};

/**
 * `pack`: null in the Library workspace, else { name, memberCount, finished, finishedBy, finishedAt }.
 * `itemInfo(diagramId)`: in a pack, { fromLibrary, originalChanged, waiting }; `presence(diagramId)`:
 * the other people who have that LZ/PZ open; `editing(diagramId)`: who is moving something on it.
 */
const LzPanel = ({
  diagrams,
  activeDiagramId,
  savedAt = {},
  savingId = null,
  recent = [],
  pack = null,
  itemInfo,
  presence,
  editing,
  onSelect,
  onSave,
  onSaveAs,
  onRename,
  onClose,
  onView3D,
  onOpenRecent,
  onBrowseAll,
  onAddToPack,
  onExportCard,
  onCollapse,
}) => {
  const [renamingId, setRenamingId] = useState(null);
  const active = diagrams.find((diagram) => diagram.id === activeDiagramId) ?? null;
  const subtitle = pack
    ? `${pack.name} · ${diagrams.length} ${diagrams.length === 1 ? "item" : "items"} · ${pack.finished ? "finished · read-only" : "live"}`
    : `${diagrams.length} open · this session`;
  const packFor = (id) => (pack ? { ...pack, ...(itemInfo?.(id) ?? {}) } : null);

  const menuFor = (diagram, index) => {
    const title = diagramTitle(diagram, index);
    const finished = pack?.finished;
    return [
      { icon: "pencil", title: "Rename", onSelect: () => setRenamingId(diagram.id), hidden: finished },
      { icon: "copy", title: pack ? "Save a copy to Library…" : "Save as…", onSelect: () => (pack ? onSave(diagram.id, { copy: true }) : onSaveAs(diagram.id)), disabled: diagram.status !== "analyzed" },
      { icon: "layers", title: "Add to Mission Pack…", onSelect: () => onAddToPack?.(diagram.id), hidden: Boolean(pack) || !onAddToPack, disabled: diagram.savedId == null, text: diagram.savedId == null ? "Save it first" : undefined },
      { icon: "download", title: "Export LZ card", onSelect: () => onExportCard?.(diagram.id), hidden: !onExportCard, disabled: diagram.status !== "analyzed" },
      { divider: true },
      { icon: "x", title: pack ? "Close here" : "Close", text: pack ? "It stays in the pack" : `Takes ${title} out of this session`, onSelect: () => onClose(diagram.id) },
    ];
  };

  return (
    <>
      <PanelHead title={pack ? "LZ/PZ" : "LZ/PZ"} subtitle={subtitle} onCollapse={onCollapse} />
      <div className="shell-dock__body">
        {diagrams.length === 0 ? (
          <div className="shell-empty">
            {pack
              ? `Nothing in ${pack.name} yet. Set a target with the MGRS box, or right-click the map and choose Set as target, and the new LZ/PZ goes into the pack.`
              : "No LZ/PZ open. Enter a grid in the MGRS target box, or right-click the map and choose Set as target."}
          </div>
        ) : (
          <PanelSection label={pack ? `In ${pack.name}` : "Open"} count={diagrams.length}>
            <div className="shell-list">
              {diagrams.map((diagram, index) =>
                diagram.id === active?.id ? (
                  <ActiveCard
                    key={diagram.id}
                    diagram={diagram}
                    index={index}
                    savedAt={savedAt[diagram.id]}
                    saving={savingId === diagram.id}
                    pack={packFor(diagram.id)}
                    people={presence?.(diagram.id)}
                    onSave={onSave}
                    onRename={onRename}
                    onView3D={onView3D}
                    menuItems={menuFor(diagram, index)}
                    renaming={renamingId === diagram.id}
                    setRenaming={(on) => setRenamingId(on ? diagram.id : null)}
                  />
                ) : (
                  <CompactCard
                    key={diagram.id}
                    diagram={diagram}
                    index={index}
                    savedAt={savedAt[diagram.id]}
                    pack={packFor(diagram.id)}
                    people={presence?.(diagram.id)}
                    editing={editing?.(diagram.id)}
                    onSelect={onSelect}
                    onView3D={onView3D}
                  />
                ),
              )}
            </div>
          </PanelSection>
        )}

        {!pack && recent.length > 0 && (
          <>
            <div className="shell-spacer" />
            <PanelSection label="Recent in Library" action={<button type="button" className="ui-link" onClick={onBrowseAll}>Browse all</button>}>
              <div>
                {recent.map((entry) => (
                  <div key={entry.id} className="shell-row">
                    <Icon name="hexagon" size={15} />
                    <div className="shell-row__main">
                      <div className="shell-row__name">{entry.name}</div>
                      <div className="shell-row__meta">Updated {dateAndTime(entry.updated_at)}</div>
                    </div>
                    <button type="button" className="ui-btn ui-btn--ghost ui-btn--28" aria-label={`Open ${entry.name}`} onClick={() => onOpenRecent(entry)}>
                      Open
                    </button>
                  </div>
                ))}
              </div>
            </PanelSection>
          </>
        )}

        <div className="shell-hint">
          <Icon name="info" size={14} />
          <span>
            {pack
              ? pack.finished
                ? "This pack is finished. Viewing and export still work; nothing can be changed."
                : `New LZ/PZs you start while ${pack.name} is open are added to it.`
              : "Set a new target with the MGRS box, or right-click the map, to open another LZ/PZ."}
          </span>
        </div>
      </div>
    </>
  );
};

export default LzPanel;
