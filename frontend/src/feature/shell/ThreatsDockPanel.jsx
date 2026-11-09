import React, { useState } from "react";
import { symbolDataUri } from "../symbols/milsym";
import { RADAR_TYPES } from "../threats/threatModel";
import Chip from "../ui/Chip";
import { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import MoreMenu from "../ui/MoreMenu";
import NameDialog from "../ui/NameDialog";
import { PanelHead, PanelSection } from "./Dock";
import "./shell.css";

/*
 * The Threats panel of the dock (screen Threats). Threats are kept in named sets in the Library, written as
 * they change (AGENTS.md §2; useThreats), each open set a section of its own here, and the panel says where
 * they are first. While a Mission Pack is open it shows the pack's sets instead (`pack`), which everyone in the
 * pack can change unless it is read-only. Add, import, the list with each one's ranges and terrain mask, the exports, and Remove all.
 */

const radarText = (threat) => {
  const detect = threat.radars?.find((r) => r.type === RADAR_TYPES.detection);
  const engage = threat.radars?.find((r) => r.type === RADAR_TYPES.engagement);
  const parts = [];
  if (detect) parts.push(`Detect ${detect.rangeNmi} nm`);
  if (engage) parts.push(`Engage ${engage.rangeNmi} nm`);
  return parts.join(" · ") || "No radars";
};

const maskChip = (threat) => {
  if (threat.maskLoading) return <Chip tone="info" dot={false}>Working out terrain mask…</Chip>;
  if (threat.maskError) return <Chip tone="danger" icon="alertTriangle" title={threat.maskError}>Mask failed</Chip>;
  const masked = threat.radars?.some((r) => r.showMask) && threat.mask;
  return masked ? <Chip tone="info" dot={false}>Terrain mask on</Chip> : <Chip dot={false}>Rings only</Chip>;
};

const ThreatRow = ({ threat, onEdit, onRemove, onToggleVisibility }) => {
  const hidden = threat.visible === false;
  const symbol = symbolDataUri(threat.milstdId, { size: 26 });
  return (
    <div className={`shell-threat${hidden ? " shell-threat--hidden" : ""}`}>
      <span className="shell-threat__icon" aria-hidden="true">
        {symbol ? <img src={symbol} alt="" /> : <Icon name="diamond" size={16} />}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="shell-threat__name">{threat.name}</div>
        <div className="shell-threat__meta">{radarText(threat)}</div>
        <div style={{ marginTop: 6 }}>{maskChip(threat)}</div>
      </div>
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`${hidden ? "Show" : "Hide"} ${threat.name}`} aria-pressed={hidden} onClick={() => onToggleVisibility(threat.id)}>
        <Icon name={hidden ? "eyeOff" : "eye"} size={15} />
      </button>
      {onEdit && <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Edit ${threat.name}`} onClick={() => onEdit(threat.id)}>
        <Icon name="pencil" size={15} />
      </button>}
      {onRemove && <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Remove ${threat.name}`} onClick={() => onRemove(threat.id)}>
        <Icon name="trash" size={15} />
      </button>}
    </div>
  );
};

const saveChip = (set, state) => {
  if (state === "saving") return <Chip tone="info" dot={false}>Saving…</Chip>;
  if (state === "failed") return <Chip tone="warn" icon="alertTriangle">Not saved yet</Chip>;
  if (state === "too_large") return <Chip tone="danger" icon="alertTriangle" title="More than 5 MB. Move some threats out of this set.">Too large to save</Chip>;
  if (state === "off") return <Chip tone="warn" dot={false}>Saving is off</Chip>;
  if (set.savedId) return <Chip tone="ok" dot={false}>Saved</Chip>;
  return set.threats.length ? <Chip dot={false}>Not saved yet</Chip> : <Chip dot={false}>Empty</Chip>;
};

const ThreatsDockPanel = ({
  threats,
  sets = [],
  activeId,
  saveStates = {},
  canSave = false,
  pack = null,
  onAdd,
  onImport,
  onEdit,
  onRemove,
  onRemoveAll,
  onToggleVisibility,
  onExportThs,
  onExportKmz,
  onRenameSet,
  onCloseSet,
  onMakeActive,
  onCollapse,
}) => {
  const [confirmAll, setConfirmAll] = useState(false);
  const [renaming, setRenaming] = useState(null);
  const count = threats.length;
  const many = sets.length > 1;
  const readOnly = Boolean(pack?.readOnly);
  const keeps = canSave || Boolean(pack);
  const canRename = pack ? !readOnly : canSave;
  return (
    <>
      <PanelHead title="Threats" subtitle={`${count} on the map${keeps ? "" : " · this session only"}`} onCollapse={onCollapse} />
      <div className="shell-dock__body">
        <div className="shell-notice">
          <Icon name={pack ? "layers" : canSave ? "folder" : "lock"} size={15} />
          {pack ? (
            <div>
              <b>In {pack.name}.</b> {readOnly ? "This pack is read-only, so these threats can be looked at and exported, not changed." : "Everyone in the pack sees these threats and can change them."} Hiding one, and its terrain mask, stay on your device.
            </div>
          ) : canSave ? (
            <div>
              <b>Saved as you work.</b> Threats are kept in your Library, and the sets open now come back when you next sign in.
            </div>
          ) : (
            <div>
              <b>Session only.</b> Cloud save is off for this account, so threats stay in this browser tab.
            </div>
          )}
        </div>
        {!readOnly && <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--grow" onClick={onAdd}>
            <Icon name="plus" size={15} />
            <span>Add threat</span>
          </button>
          <button type="button" className="ui-btn ui-btn--grow" onClick={onImport}>
            <Icon name="download" size={15} />
            <span>Import .ths</span>
          </button>
        </div>}
        {!readOnly && <div style={{ marginTop: 8, fontSize: 12, color: "var(--ff-text-muted)" }}>
          Add threat puts one in the middle of the map. You can also right-click the map and choose Add threat here.
        </div>}

        {sets.map((set) => (
          <div key={set.id} style={{ marginTop: 16 }}>
            <PanelSection
              label={set.name}
              count={set.threats.length}
              action={
                <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                  {canSave && !pack && saveChip(set, saveStates[set.id])}
                  {many && !readOnly && set.id === activeId && <Chip tone="info" dot={false}>New threats go here</Chip>}
                  {keeps && (
                    <MoreMenu
                      label={`More actions for ${set.name}`}
                      size={30}
                      items={[
                        { icon: "pencil", title: "Rename", onSelect: () => setRenaming(set), hidden: !canRename },
                        { icon: "plus", title: "Put new threats here", onSelect: () => onMakeActive(set.id), hidden: readOnly || !many || set.id === activeId },
                        { icon: "x", title: "Close", text: pack ? "It stays in the pack" : "It stays in your Library", onSelect: () => onCloseSet(set.id) },
                      ]}
                    />
                  )}
                </span>
              }
            >
              <div className="shell-list">
                {set.threats.map((threat) => (
                  <ThreatRow key={threat.id} threat={threat} onEdit={readOnly ? undefined : onEdit} onRemove={readOnly ? undefined : onRemove} onToggleVisibility={onToggleVisibility} />
                ))}
              </div>
            </PanelSection>
          </div>
        ))}

        {count > 0 && (
          <>
            <div style={{ height: 16 }} />
            <div className="shell-box">
              <div className="ui-label" style={{ marginBottom: 10 }}>Export</div>
              <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                <button type="button" className="ui-btn" onClick={onExportThs}>
                  <Icon name="upload" size={15} />
                  <span>Export .ths for AMPS</span>
                </button>
                {/* "/ Aero app" does not fit at the 11 px floor; the dialog this opens names all three. */}
                <button type="button" className="ui-btn" onClick={onExportKmz}>
                  <Icon name="send" size={15} />
                  <span>Export for ForeFlight / ATAK</span>
                </button>
              </div>
              <div style={{ marginTop: 10, fontSize: 11.5, lineHeight: 1.45, color: "#8a94a1" }}>
                Building a file sends threat positions to the server once, to make the file. The file is not kept.
              </div>
            </div>
            {!readOnly && <div style={{ marginTop: 14, textAlign: "center" }}>
              <button type="button" className="shell-danger-link" onClick={() => setConfirmAll(true)}>Remove all threats</button>
            </div>}
          </>
        )}
      </div>
      {renaming && (
        <NameDialog
          title={`Rename “${renaming.name}”`}
          initialName={renaming.name}
          confirmLabel="Rename"
          hint={pack ? "Press Enter to rename. Everyone in the pack sees the new name." : "Press Enter to rename. It changes on every device you use."}
          onCancel={() => setRenaming(null)}
          onConfirm={(name) => {
            onRenameSet(renaming.id, name);
            setRenaming(null);
          }}
        />
      )}
      {confirmAll && (
        <ConfirmDialog
          title="Remove all threats?"
          text={`${count} ${count === 1 ? "threat comes" : "threats come"} off the map${pack ? ` and out of ${pack.name}, for everyone. The sets stay, empty.` : canSave ? " and out of the sets they are saved in. The sets stay, empty." : ", and cannot be brought back."}`}
          icon="trash"
          iconTone="danger"
          confirmLabel="Remove all"
          confirmTone="danger"
          onCancel={() => setConfirmAll(false)}
          onConfirm={() => {
            setConfirmAll(false);
            onRemoveAll();
          }}
        />
      )}
    </>
  );
};

export default ThreatsDockPanel;
