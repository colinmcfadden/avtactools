import React, { useRef } from "react";
import { RADAR_TYPES } from "./threatModel";

const EyeIcon = ({ visible }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    {visible ? (
      <>
        <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" />
        <circle cx="12" cy="12" r="3" />
      </>
    ) : (
      <>
        <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
        <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
        <line x1="1" y1="1" x2="23" y2="23" />
      </>
    )}
  </svg>
);

const boxStyle = {
  display: "flex",
  flexDirection: "column",
  gap: "6px",
  padding: "8px",
  background: "rgba(255,255,255,0.03)",
  borderRadius: "6px",
};

/**
 * Threats section of the routes panel: import a .ths, add a threat, and manage
 * the loaded threats (visibility, edit, remove, mask status). Threats are
 * export-only — they can't be saved.
 */
const ThreatsPanel = ({
  threats,
  onImportThs,
  onAddThreat,
  onEdit,
  onRemove,
  onToggleVisibility,
}) => {
  const fileRef = useRef(null);

  const handleFile = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    try {
      const count = await onImportThs(file);
      if (!count) alert("No threats found in that .ths file.");
    } catch (err) {
      alert("Error importing threats: " + err.message);
    }
  };

  return (
    <div style={boxStyle}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={{ fontSize: "0.75rem", opacity: 0.7 }}>Threats ({threats.length})</span>
        <div style={{ display: "flex", gap: "6px" }}>
          <input
            ref={fileRef}
            type="file"
            accept=".ths,.THS"
            style={{ display: "none" }}
            onChange={handleFile}
          />
          <button
            className="export-btn"
            style={{ fontSize: "0.7rem", padding: "2px 6px" }}
            onClick={() => fileRef.current?.click()}
            title="Import threats from an AMPS .ths file"
          >
            Import .ths
          </button>
          <button
            className="export-btn"
            style={{ fontSize: "0.7rem", padding: "2px 6px" }}
            onClick={onAddThreat}
            title="Add a threat at the map center"
          >
            + Add
          </button>
        </div>
      </div>

      {threats.length === 0 && (
        <div style={{ fontSize: "0.72rem", opacity: 0.55 }}>
          Add a threat or right-click the map → “Add threat here”. Threats export
          to .ths alongside the mission but aren’t saved.
        </div>
      )}

      {threats.map((threat) => (
        <div
          key={threat.id}
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "5px 6px",
            background: "rgba(255,255,255,0.05)",
            borderRadius: "6px",
            opacity: threat.visible === false ? 0.5 : 1,
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div
              style={{
                fontSize: "0.82rem",
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
                display: "flex",
                alignItems: "center",
                gap: "6px",
              }}
            >
              <span style={{ color: threat.color }}>◆</span>
              {threat.name}
            </div>
            <div style={{ fontSize: "0.68rem", opacity: 0.6 }}>
              {threat.maskLoading
                ? "Analyzing terrain…"
                : threat.maskError
                  ? "Mask error"
                  : threat.radars.map((r) => `${r.rangeNmi}nmi`).join(" / ")}
            </div>
          </div>
          <div style={{ display: "flex", gap: "2px", flexShrink: 0 }}>
            <button
              onClick={() => onToggleVisibility(threat.id)}
              style={{ background: "none", border: "none", color: "#9ca3af", cursor: "pointer", padding: "0 3px", display: "flex" }}
              title={threat.visible === false ? "Show" : "Hide"}
            >
              <EyeIcon visible={threat.visible !== false} />
            </button>
            <button
              onClick={() => onEdit(threat.id)}
              style={{ background: "none", border: "none", color: "#60a5fa", cursor: "pointer", padding: "0 4px", fontSize: "0.8rem" }}
              title="Edit threat"
            >
              ✎
            </button>
            <button
              onClick={() => onRemove(threat.id)}
              style={{ background: "none", border: "none", color: "#ef4444", cursor: "pointer", padding: "0 4px", fontSize: "1rem" }}
              title="Remove threat"
            >
              ×
            </button>
          </div>
        </div>
      ))}
    </div>
  );
};

export default ThreatsPanel;
