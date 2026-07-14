import React, { useState } from "react";
import Draggable from "react-draggable";
import { RADAR_TYPES } from "./threatModel";
import "../export/ExportModal.css";

const num = (v, fallback = 0) => {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : fallback;
};

const radarLabel = (type) =>
  type === RADAR_TYPES.engagement ? "Engagement" : "Detection";

/**
 * Add/edit dialog for a threat. Exposes the editable fields carried in the
 * .ths (identity, position, and — per detection/engagement radar — range,
 * antenna height above ground, AGL/MSL, and the three mask altitude bands).
 */
const ThreatDialog = ({ editing, onSave, onCancel }) => {
  const nodeRef = React.useRef(null);
  const [draft, setDraft] = useState(editing.threat);

  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));
  const setRadar = (idx, patch) =>
    setDraft((d) => ({
      ...d,
      radars: d.radars.map((r, i) => (i === idx ? { ...r, ...patch } : r)),
    }));
  const setBand = (ri, bi, patch) =>
    setDraft((d) => ({
      ...d,
      radars: d.radars.map((r, i) =>
        i === ri
          ? { ...r, bands: r.bands.map((b, j) => (j === bi ? { ...b, ...patch } : b)) }
          : r,
      ),
    }));

  const label = { fontSize: "0.7rem", opacity: 0.7, display: "block", marginBottom: "2px" };
  const input = {
    width: "100%",
    background: "rgba(255,255,255,0.08)",
    border: "1px solid rgba(255,255,255,0.15)",
    borderRadius: "4px",
    color: "inherit",
    padding: "4px 6px",
    fontSize: "0.82rem",
    boxSizing: "border-box",
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div
        ref={nodeRef}
        className="export-modal-container glass-panel"
        style={{ width: "min(440px, 94vw)", maxHeight: "82vh", overflowY: "auto" }}
      >
        <div className="modal-header">
          <h3>{editing.isNew ? "Add Threat" : "Edit Threat"}</h3>
          <button className="close-btn" onClick={onCancel}>
            ×
          </button>
        </div>

        <div className="modal-body" style={{ display: "flex", flexDirection: "column", gap: "10px" }}>
          {/* identity */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" }}>
            <div>
              <label style={label}>Name</label>
              <input style={input} value={draft.name} onChange={(e) => set({ name: e.target.value })} />
            </div>
            <div>
              <label style={label}>MIL-STD symbol ID</label>
              <input
                style={input}
                value={draft.milstdId}
                onChange={(e) => set({ milstdId: e.target.value })}
              />
            </div>
            <div>
              <label style={label}>Source</label>
              <input style={input} value={draft.source} onChange={(e) => set({ source: e.target.value })} />
            </div>
            <div>
              <label style={label}>Latitude</label>
              <input
                style={input}
                type="number"
                value={draft.lat}
                onChange={(e) => set({ lat: num(e.target.value, draft.lat) })}
              />
            </div>
            <div>
              <label style={label}>Longitude</label>
              <input
                style={input}
                type="number"
                value={draft.lon}
                onChange={(e) => set({ lon: num(e.target.value, draft.lon) })}
              />
            </div>
            <div style={{ gridColumn: "1 / -1" }}>
              <label style={label}>Information</label>
              <input
                style={input}
                value={draft.information}
                onChange={(e) => set({ information: e.target.value })}
              />
            </div>
          </div>

          {/* radars */}
          {draft.radars.map((radar, ri) => (
            <div
              key={radar.type}
              style={{
                border: "1px solid rgba(255,255,255,0.12)",
                borderRadius: "6px",
                padding: "8px",
                display: "flex",
                flexDirection: "column",
                gap: "8px",
              }}
            >
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong style={{ fontSize: "0.85rem" }}>{radarLabel(radar.type)} radar</strong>
                <label style={{ fontSize: "0.72rem", display: "flex", gap: "4px", alignItems: "center" }}>
                  <input
                    type="checkbox"
                    checked={radar.showMask}
                    onChange={(e) => setRadar(ri, { showMask: e.target.checked })}
                  />
                  show mask
                </label>
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: "8px" }}>
                <div>
                  <label style={label}>Range (nmi)</label>
                  <input
                    style={input}
                    type="number"
                    min="0"
                    value={radar.rangeNmi}
                    onChange={(e) => setRadar(ri, { rangeNmi: num(e.target.value) })}
                  />
                </div>
                <div>
                  <label style={label}>Ant. ht (ft)</label>
                  <input
                    style={input}
                    type="number"
                    value={radar.antennaHeightFt}
                    onChange={(e) => setRadar(ri, { antennaHeightFt: num(e.target.value) })}
                  />
                </div>
                <div>
                  <label style={label}>Ref</label>
                  <select
                    style={input}
                    value={radar.aglNotMsl ? "agl" : "msl"}
                    onChange={(e) => setRadar(ri, { aglNotMsl: e.target.value === "agl" })}
                  >
                    <option value="agl">AGL</option>
                    <option value="msl">MSL</option>
                  </select>
                </div>
              </div>

              <div>
                <label style={label}>Mask altitude bands (aircraft ft AGL)</label>
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                  {radar.bands.map((band, bi) => (
                    <div key={bi} style={{ display: "flex", gap: "6px", alignItems: "center" }}>
                      <input
                        type="checkbox"
                        checked={band.viewable}
                        onChange={(e) => setBand(ri, bi, { viewable: e.target.checked })}
                        title="Show this band"
                      />
                      <input
                        style={{ ...input, width: "70px" }}
                        type="number"
                        value={band.altFt}
                        onChange={(e) => setBand(ri, bi, { altFt: num(e.target.value) })}
                      />
                      <span style={{ fontSize: "0.72rem", opacity: 0.6 }}>ft</span>
                      <input
                        type="color"
                        value={band.color}
                        onChange={(e) => setBand(ri, bi, { color: e.target.value })}
                        style={{ width: "50px", height: "50px", border: "none", background: "none" }}
                        title="Band color"
                      />
                      <input
                        style={{ ...input, width: "58px" }}
                        type="number"
                        min="0"
                        max="1"
                        step="0.05"
                        value={band.alpha}
                        onChange={(e) => setBand(ri, bi, { alpha: num(e.target.value, 0.3) })}
                        title="Opacity 0–1"
                      />
                    </div>
                  ))}
                </div>
              </div>

              <label style={{ fontSize: "0.72rem", display: "flex", gap: "4px", alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={radar.showRangeRings}
                  onChange={(e) => setRadar(ri, { showRangeRings: e.target.checked })}
                />
                show range ring
              </label>
            </div>
          ))}
        </div>

        <div className="modal-footer" style={{ display: "flex", gap: "8px", justifyContent: "flex-end" }}>
          <button className="cancel-btn" onClick={onCancel}>
            Cancel
          </button>
          <button className="export-btn" onClick={() => onSave(draft)}>
            {editing.isNew ? "Add & analyze terrain" : "Save & re-analyze"}
          </button>
        </div>
      </div>
    </Draggable>
  );
};

export default ThreatDialog;
