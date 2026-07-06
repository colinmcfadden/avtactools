import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import "../export/ExportModal.css";

const formatTimestamp = (iso) => {
  const date = new Date(iso);
  return date.toLocaleString();
};

const HistoryModal = ({
  isOpen,
  onClose,
  history,
  isLoadingHistory,
  fetchHistory,
  saveMap,
  loadMap,
  updateMap,
  deleteMap,
  buildSnapshot,
  applySnapshot,
}) => {
  const nodeRef = useRef(null);
  const [saveName, setSaveName] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [loadedMapId, setLoadedMapId] = useState(null);
  const [isUpdating, setIsUpdating] = useState(false);

  useEffect(() => {
    if (isOpen) {
      fetchHistory();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSave = async () => {
    const name = saveName.trim();
    if (!name) {
      alert("Please enter a name for this saved map.");
      return;
    }
    setIsSaving(true);
    try {
      await saveMap(name, buildSnapshot());
      setSaveName("");
      await fetchHistory();
    } catch (err) {
      alert("Error saving map: " + err.message);
    } finally {
      setIsSaving(false);
    }
  };

  const handleLoad = async (id) => {
    try {
      const snapshot = await loadMap(id);
      applySnapshot(snapshot);
      setLoadedMapId(id);
      onClose();
    } catch (err) {
      alert("Error loading map: " + err.message);
    }
  };

  const handleUpdate = async (id) => {
    setIsUpdating(true);
    try {
      await updateMap(id, buildSnapshot());
      await fetchHistory();
    } catch (err) {
      alert("Error saving map: " + err.message);
    } finally {
      setIsUpdating(false);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this saved map?")) return;
    try {
      await deleteMap(id);
      if (id === loadedMapId) {
        setLoadedMapId(null);
      }
    } catch (err) {
      alert("Error deleting map: " + err.message);
    }
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        <div className="modal-header">
          <h3>Saved Maps</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          <div className="form-divider">Save Current Map</div>
          <div className="form-grid header-grid">
            <div className="input-group span-flex">
              <label>Name</label>
              <input
                value={saveName}
                onChange={(e) => setSaveName(e.target.value)}
                placeholder="e.g. HAWK LZ Setup"
              />
            </div>
            <div className="input-group" style={{ justifyContent: "flex-end" }}>
              <label>&nbsp;</label>
              <button
                className="export-btn"
                onClick={handleSave}
                disabled={isSaving}
              >
                {isSaving ? "Saving..." : "Save"}
              </button>
            </div>
          </div>

          <div className="form-divider">History</div>

          {isLoadingHistory && <p>Loading...</p>}
          {!isLoadingHistory && history.length === 0 && (
            <p style={{ opacity: 0.7 }}>No saved maps yet.</p>
          )}

          <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
            {history.map((entry) => (
              <div
                key={entry.id}
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  padding: "8px 10px",
                  background: "rgba(255,255,255,0.05)",
                  borderRadius: "6px",
                  border:
                    entry.id === loadedMapId
                      ? "1px solid rgba(59, 130, 246, 0.6)"
                      : "1px solid transparent",
                }}
              >
                <div>
                  <div style={{ fontWeight: 600 }}>
                    {entry.name}
                    {entry.id === loadedMapId && (
                      <span
                        style={{
                          marginLeft: "6px",
                          fontSize: "0.7rem",
                          fontWeight: 400,
                          color: "#60a5fa",
                        }}
                      >
                        (loaded)
                      </span>
                    )}
                  </div>
                  <div style={{ fontSize: "0.75rem", opacity: 0.6 }}>
                    Updated {formatTimestamp(entry.updated_at)}
                  </div>
                </div>
                <div style={{ display: "flex", gap: "6px" }}>
                  <button
                    className="export-btn"
                    onClick={() => handleLoad(entry.id)}
                  >
                    Load
                  </button>
                  {entry.id === loadedMapId && (
                    <button
                      className="export-btn"
                      onClick={() => handleUpdate(entry.id)}
                      disabled={isUpdating}
                    >
                      {isUpdating ? "Saving..." : "Save"}
                    </button>
                  )}
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

export default HistoryModal;
