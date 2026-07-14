import React, { useRef, useState } from "react";
import Draggable from "react-draggable";
import { buildSidc, symbolDataUri, parseSidc } from "../symbols/milsym";
import { AFFILIATIONS, UNIT_FUNCTIONS, ECHELONS } from "../symbols/presets";
import "../export/ExportModal.css";

/**
 * MIL-STD-2525 unit symbol composer (milsymbol). Pick affiliation, function,
 * and echelon, add labels, and see a live preview — then drop the symbol on the
 * map. Reused for editing a placed unit (pass `initial` + `onDelete`).
 */
const UnitBuilder = ({ initial, onSubmit, onDelete, onClose }) => {
  const nodeRef = useRef(null);
  const isEdit = Boolean(initial);
  const init = initial?.sidc ? parseSidc(initial.sidc) : {};
  const [affiliation, setAffiliation] = useState(init.affiliation || "F");
  const [functionId, setFunctionId] = useState(init.functionId || UNIT_FUNCTIONS[0].functionId);
  const [echelon, setEchelon] = useState(init.echelon || "-");
  const [uniqueDesignation, setUniqueDesignation] = useState(initial?.uniqueDesignation || "");
  const [higherFormation, setHigherFormation] = useState(initial?.higherFormation || "");

  const sidc = buildSidc({ affiliation, functionId, echelon });
  const labelOpts = { uniqueDesignation, higherFormation };
  const previewUri = symbolDataUri(sidc, { size: 64, ...labelOpts });

  const label = { fontSize: "0.7rem", opacity: 0.7, display: "block", marginBottom: "2px" };
  const input = {
    width: "100%",
    background: "rgba(255,255,255,0.08)",
    border: "1px solid rgba(255,255,255,0.15)",
    borderRadius: "4px",
    color: "inherit",
    padding: "6px 8px",
    fontSize: "0.85rem",
    boxSizing: "border-box",
  };

  const handleSubmit = () => {
    onSubmit({ sidc, uniqueDesignation, higherFormation });
    onClose();
  };

  const handleDelete = () => {
    onDelete?.();
    onClose();
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel" style={{ width: "min(420px, 94vw)" }}>
        <div className="modal-header">
          <h3>{isEdit ? "Edit Unit Symbol" : "Build Unit Symbol"}</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body" style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
          {/* preview */}
          <div
            style={{
              display: "flex",
              justifyContent: "center",
              padding: "10px",
              background: "rgba(255,255,255,0.04)",
              borderRadius: "8px",
              minHeight: "90px",
              alignItems: "center",
            }}
          >
            {previewUri ? (
              <img src={previewUri} alt="symbol preview" style={{ maxHeight: "90px", maxWidth: "100%" }} />
            ) : (
              <span style={{ opacity: 0.6 }}>Invalid symbol</span>
            )}
          </div>

          {/* affiliation */}
          <div>
            <label style={label}>Affiliation</label>
            <div style={{ display: "flex", gap: "6px" }}>
              {AFFILIATIONS.map((a) => (
                <button
                  key={a.id}
                  onClick={() => setAffiliation(a.id)}
                  style={{
                    flex: 1,
                    padding: "6px",
                    borderRadius: "4px",
                    border: affiliation === a.id ? `2px solid ${a.color}` : "1px solid rgba(255,255,255,0.15)",
                    background: affiliation === a.id ? a.color : "rgba(255,255,255,0.06)",
                    color: "white",
                    fontSize: "0.75rem",
                    cursor: "pointer",
                  }}
                >
                  {a.label}
                </button>
              ))}
            </div>
          </div>

          {/* function + echelon */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" }}>
            <div>
              <label style={label}>Function</label>
              <select style={input} value={functionId} onChange={(e) => setFunctionId(e.target.value)}>
                {UNIT_FUNCTIONS.map((f) => (
                  <option key={f.id} value={f.functionId}>
                    {f.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label style={label}>Echelon</label>
              <select style={input} value={echelon} onChange={(e) => setEchelon(e.target.value)}>
                {ECHELONS.map((ech) => (
                  <option key={ech.id} value={ech.code}>
                    {ech.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* labels */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" }}>
            <div>
              <label style={label}>Designation (e.g. A/1-171)</label>
              <input
                style={input}
                value={uniqueDesignation}
                onChange={(e) => setUniqueDesignation(e.target.value)}
              />
            </div>
            <div>
              <label style={label}>Higher formation</label>
              <input
                style={input}
                value={higherFormation}
                onChange={(e) => setHigherFormation(e.target.value)}
              />
            </div>
          </div>

          <div style={{ fontSize: "0.65rem", opacity: 0.5, fontFamily: "monospace" }}>SIDC: {sidc}</div>
        </div>

        <div className="modal-footer">
          {isEdit && onDelete && (
            <button
              className="cancel-btn"
              style={{ color: "#ef4444", marginRight: "auto" }}
              onClick={handleDelete}
            >
              Delete
            </button>
          )}
          <button className="cancel-btn" onClick={onClose}>
            Cancel
          </button>
          <button className="export-btn" onClick={handleSubmit} disabled={!previewUri}>
            {isEdit ? "Save" : "Add to map"}
          </button>
        </div>
      </div>
    </Draggable>
  );
};

export default UnitBuilder;
