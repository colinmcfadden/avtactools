import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import "../export/ExportModal.css";

const formatTimestamp = (iso) => new Date(iso).toLocaleString();

const kindBadgeStyle = (kind) => ({
  fontSize: "0.65rem",
  fontWeight: 600,
  textTransform: "uppercase",
  letterSpacing: "0.05em",
  padding: "1px 6px",
  borderRadius: "4px",
  marginLeft: "8px",
  color: kind === "mission" ? "#00b5e2" : "#a3e635",
  border: `1px solid ${kind === "mission" ? "#00b5e2" : "#a3e635"}`,
  opacity: 0.85,
});

/**
 * Load/delete list for cloud-saved routes. Saving happens from the routes
 * panel (next to each group's Export button), not here — a save always
 * refers to routes currently on the map.
 */
const SavedRoutesModal = ({
  isOpen,
  onClose,
  savedRoutes,
  isLoadingSaved,
  fetchSavedRoutes,
  onLoad,
  onDelete,
}) => {
  const nodeRef = useRef(null);
  const [busyId, setBusyId] = useState(null);

  useEffect(() => {
    if (isOpen) {
      fetchSavedRoutes();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) return null;

  const handleLoad = async (entry) => {
    setBusyId(entry.id);
    try {
      await onLoad(entry);
      onClose();
    } catch (err) {
      alert("Error loading route: " + err.message);
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this saved route?")) return;
    try {
      await onDelete(id);
    } catch (err) {
      alert("Error deleting route: " + err.message);
    }
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        <div className="modal-header">
          <h3>Saved Routes</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          {isLoadingSaved && <p>Loading...</p>}
          {!isLoadingSaved && savedRoutes.length === 0 && (
            <p style={{ opacity: 0.7 }}>
              No saved routes yet. Use the Save button in the Routes panel to
              save sketched routes or an imported mission.
            </p>
          )}

          <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
            {savedRoutes.map((entry) => (
              <div
                key={entry.id}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  padding: "8px 10px",
                  background: "rgba(255,255,255,0.05)",
                  borderRadius: "6px",
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <div
                    style={{
                      fontWeight: 600,
                      display: "flex",
                      alignItems: "center",
                    }}
                  >
                    <span
                      style={{
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {entry.name}
                    </span>
                    <span style={kindBadgeStyle(entry.kind)}>{entry.kind}</span>
                  </div>
                  <div style={{ fontSize: "0.75rem", opacity: 0.6 }}>
                    Updated {formatTimestamp(entry.updated_at)}
                  </div>
                </div>
                <div style={{ display: "flex", gap: "6px", flexShrink: 0 }}>
                  <button
                    className="export-btn"
                    onClick={() => handleLoad(entry)}
                    disabled={busyId === entry.id}
                  >
                    {busyId === entry.id ? "Loading..." : "Load"}
                  </button>
                  <button
                    className="cancel-btn"
                    style={{ color: "#ef4444" }}
                    onClick={() => handleDelete(entry.id)}
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="modal-footer">
          <button className="cancel-btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </Draggable>
  );
};

export default SavedRoutesModal;
