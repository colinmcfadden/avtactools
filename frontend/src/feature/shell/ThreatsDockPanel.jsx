import React, { useState } from "react";
import { symbolDataUri } from "../symbols/milsym";
import { RADAR_TYPES } from "../threats/threatModel";
import Chip from "../ui/Chip";
import { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import { PanelHead, PanelSection } from "./Dock";
import "./shell.css";

/*
 * The Threats panel of the dock (screen Threats). Threats are never saved or shared (AGENTS.md §2),
 * and the panel says so first. Add, import, the list with each one's ranges and terrain mask, the
 * exports, and Remove all, which asks first because nothing can bring them back.
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
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Edit ${threat.name}`} onClick={() => onEdit(threat.id)}>
        <Icon name="pencil" size={15} />
      </button>
      <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label={`Remove ${threat.name}`} onClick={() => onRemove(threat.id)}>
        <Icon name="trash" size={15} />
      </button>
    </div>
  );
};

const ThreatsDockPanel = ({ threats, onAdd, onImport, onEdit, onRemove, onRemoveAll, onToggleVisibility, onExportThs, onExportKmz, onCollapse }) => {
  const [confirmAll, setConfirmAll] = useState(false);
  const count = threats.length;
  return (
    <>
      <PanelHead title="Threats" subtitle={`${count} on the map · this session only`} onCollapse={onCollapse} />
      <div className="shell-dock__body">
        <div className="shell-notice">
          <Icon name="lock" size={15} />
          <div>
            <b>Session only.</b> Threats stay in this browser tab. They are never saved to your account or shared with a Mission Pack.
          </div>
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <button type="button" className="ui-btn ui-btn--grow" onClick={onAdd}>
            <Icon name="plus" size={15} />
            <span>Add threat</span>
          </button>
          <button type="button" className="ui-btn ui-btn--grow" onClick={onImport}>
            <Icon name="download" size={15} />
            <span>Import .ths</span>
          </button>
        </div>
        <div style={{ marginTop: 8, fontSize: 12, color: "var(--ff-text-muted)" }}>
          Add threat puts one in the middle of the map. You can also right-click the map and choose Add threat here.
        </div>

        {count > 0 && (
          <>
            <div style={{ height: 16 }} />
            <PanelSection label="On the map" count={count}>
              <div className="shell-list">
                {threats.map((threat) => (
                  <ThreatRow key={threat.id} threat={threat} onEdit={onEdit} onRemove={onRemove} onToggleVisibility={onToggleVisibility} />
                ))}
              </div>
            </PanelSection>
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
                Building a file sends threat positions to the server once, to draw the terrain mask. Nothing is kept.
              </div>
            </div>
            <div style={{ marginTop: 14, textAlign: "center" }}>
              <button type="button" className="shell-danger-link" onClick={() => setConfirmAll(true)}>Remove all threats</button>
            </div>
          </>
        )}
      </div>
      {confirmAll && (
        <ConfirmDialog
          title="Remove all threats?"
          text={`${count} ${count === 1 ? "threat comes" : "threats come"} off the map. Threats are never saved, so they cannot be brought back.`}
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
