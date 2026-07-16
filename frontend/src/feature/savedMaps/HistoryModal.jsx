import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import "../export/ExportModal.css";
import SavedRoutesList from "../msnxImport/SavedRoutesList";
import LocalPointsPanel from "../localPoints/LocalPointsPanel";

const formatTimestamp = (iso) => {
  const date = new Date(iso);
  return date.toLocaleString();
};

const sameMapId = (left, right) =>
  left != null && right != null && String(left) === String(right);

const getSavedMapId = (result) => result?.id ?? result?.data?.id ?? null;

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
  // When supplied (including null), this makes the modal controlled by the
  // active LZ/PZ workspace rather than by the last history item it loaded.
  activeMapId,
  activeName = "",
  canSave = true,
  saveDisabledReason = "",
  onSaved,
  onLoaded,
  onDeleted,
  savedRoutes,
  isLoadingSaved,
  fetchSavedRoutes,
  onLoadRoute,
  onDeleteRoute,
  localPoints,
}) => {
  const nodeRef = useRef(null);
  const [activeTab, setActiveTab] = useState("maps");
  const [saveName, setSaveName] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [loadedMapId, setLoadedMapId] = useState(null);
  const [isUpdating, setIsUpdating] = useState(false);
  const hasActiveMapControl = activeMapId !== undefined;
  const currentMapId = hasActiveMapControl ? activeMapId : loadedMapId;
  const activeHistoryEntry = history.find((entry) => sameMapId(entry.id, currentMapId));
  const activeDisplayName = activeName || activeHistoryEntry?.name || "";

  useEffect(() => {
    if (isOpen) {
      fetchHistory();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  useEffect(() => {
    if (isOpen && hasActiveMapControl && activeMapId == null) {
      setSaveName(activeName);
    }
  }, [activeMapId, activeName, hasActiveMapControl, isOpen]);

  if (!isOpen) return null;

  const ensureCanSave = () => {
    if (canSave) return true;
    alert(saveDisabledReason || "Set a target and complete LZ analysis before saving.");
    return false;
  };

  const getActiveSnapshot = () => {
    if (typeof buildSnapshot !== "function") {
      throw new Error("No active LZ/PZ diagram is available to save.");
    }
    const snapshot = buildSnapshot();
    if (!snapshot) {
      throw new Error("No active LZ/PZ diagram is available to save.");
    }
    return snapshot;
  };

  const handleSave = async () => {
    if (!ensureCanSave()) return;
    const name = saveName.trim();
    if (!name) {
      alert("Please enter a name for this LZ/PZ diagram.");
      return;
    }
    setIsSaving(true);
    try {
      const result = await saveMap(name, getActiveSnapshot());
      const id = getSavedMapId(result);
      if (id != null) setLoadedMapId(id);
      setSaveName("");
      await fetchHistory();
      await onSaved?.({ id, name, isUpdate: false, result });
    } catch (err) {
      alert("Error saving LZ/PZ diagram: " + err.message);
    } finally {
      setIsSaving(false);
    }
  };

  const handleLoad = async (id) => {
    try {
      const snapshot = await loadMap(id);
      const entry = history.find((item) => sameMapId(item.id, id));
      if (onLoaded) {
        await onLoaded(snapshot, entry);
      } else if (applySnapshot) {
        applySnapshot(snapshot);
      } else {
        throw new Error("No handler is configured to open this LZ/PZ diagram.");
      }
      setLoadedMapId(id);
      onClose();
    } catch (err) {
      alert("Error loading LZ/PZ diagram: " + err.message);
    }
  };

  const handleUpdate = async (id) => {
    if (!ensureCanSave()) return;
    setIsUpdating(true);
    try {
      const result = await updateMap(id, getActiveSnapshot());
      await fetchHistory();
      await onSaved?.({
        id,
        name: activeDisplayName,
        isUpdate: true,
        result,
      });
    } catch (err) {
      alert("Error saving LZ/PZ diagram: " + err.message);
    } finally {
      setIsUpdating(false);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this saved map?")) return;
    try {
      await deleteMap(id);
      if (sameMapId(id, loadedMapId)) {
        setLoadedMapId(null);
      }
      await onDeleted?.({ id });
    } catch (err) {
      alert("Error deleting LZ/PZ diagram: " + err.message);
    }
  };

  const isUpdatingActiveMap = hasActiveMapControl && currentMapId != null;
  const saveActionDisabled = !canSave || isSaving || (isUpdatingActiveMap && isUpdating);
  const saveActionTitle = !canSave ? saveDisabledReason || "Set a target and complete LZ analysis before saving." : undefined;

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        <div className="modal-header">
          <h3>Save Menu</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-tabs">
          <button
            className={`modal-tab ${activeTab === "maps" ? "active" : ""}`}
            onClick={() => setActiveTab("maps")}
          >
            LZ/PZ
          </button>
          <button
            className={`modal-tab ${activeTab === "routes" ? "active" : ""}`}
            onClick={() => setActiveTab("routes")}
          >
            Routes
          </button>
          <button
            className={`modal-tab ${activeTab === "points" ? "active" : ""}`}
            onClick={() => setActiveTab("points")}
          >
            Local Points
          </button>
        </div>

        <div className="modal-body">
          {activeTab === "points" ? (
            <LocalPointsPanel {...localPoints} />
          ) : activeTab === "routes" ? (
            <SavedRoutesList
              savedRoutes={savedRoutes}
              isLoadingSaved={isLoadingSaved}
              fetchSavedRoutes={fetchSavedRoutes}
              onLoad={onLoadRoute}
              onDelete={onDeleteRoute}
              onClose={onClose}
            />
          ) : (
            <>
              <div className="form-divider">
                {isUpdatingActiveMap ? "Update Active LZ/PZ" : "Save Active LZ/PZ"}
              </div>
              {!canSave && (
                <p style={{ margin: "0 0 10px", fontSize: "0.8rem", opacity: 0.75 }}>
                  {saveDisabledReason || "Set a target and complete LZ analysis before saving."}
                </p>
              )}
              <div className="form-grid header-grid">
                <div className="input-group span-flex ">
                  <label>{isUpdatingActiveMap ? "Active diagram" : "Name"}</label>
                  <input
                    value={isUpdatingActiveMap ? activeDisplayName : saveName}
                    onChange={(e) => setSaveName(e.target.value)}
                    disabled={isUpdatingActiveMap || !canSave || isSaving}
                    placeholder="e.g. HAWK LZ Setup"
                  />
                </div>
                <div className="input-group" style={{ justifyContent: "flex-end" }}>
                  <label>&nbsp;</label>
                  <button
                    className="export-btn"
                    onClick={
                      isUpdatingActiveMap
                        ? () => handleUpdate(currentMapId)
                        : handleSave
                    }
                    disabled={saveActionDisabled}
                    title={saveActionTitle}
                  >
                    {isSaving || (isUpdatingActiveMap && isUpdating)
                      ? "Saving..."
                      : isUpdatingActiveMap
                        ? "Update"
                        : "Save"}
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
                        sameMapId(entry.id, currentMapId)
                          ? "1px solid rgba(59, 130, 246, 0.6)"
                          : "1px solid transparent",
                    }}
                  >
                    <div>
                      <div style={{ fontWeight: 600 }}>
                        {entry.name}
                        {sameMapId(entry.id, currentMapId) && (
                          <span
                            style={{
                              marginLeft: "6px",
                              fontSize: "0.7rem",
                              fontWeight: 400,
                              color: "#60a5fa",
                            }}
                          >
                            {hasActiveMapControl ? "(active)" : "(loaded)"}
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
                        Open
                      </button>
                      {!hasActiveMapControl && sameMapId(entry.id, currentMapId) && (
                        <button
                          className="export-btn"
                          onClick={() => handleUpdate(entry.id)}
                          disabled={!canSave || isUpdating}
                          title={saveActionTitle}
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
            </>
          )}
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
