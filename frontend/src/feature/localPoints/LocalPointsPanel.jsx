import React, { useEffect, useRef, useState } from "react";

const formatTimestamp = (iso) => new Date(iso).toLocaleString();

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

/**
 * Local Points tab of the save menu: upload .LPS files, control visibility of
 * loaded sets, and save/load/delete cloud copies.
 */
const LocalPointsPanel = ({
  pointSets,
  importLpsFile,
  togglePointSetVisibility,
  removePointSet,
  savePointSet,
  savedPointSets,
  isLoadingSavedSets,
  fetchSavedPointSets,
  loadSavedPointSet,
  deleteSavedPointSet,
}) => {
  const fileInputRef = useRef(null);
  const [busyId, setBusyId] = useState(null);

  // Mounts each time its tab is selected, so this refreshes on tab entry.
  useEffect(() => {
    fetchSavedPointSets();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleFileChosen = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-uploading the same file
    if (!file) return;
    try {
      await importLpsFile(file);
    } catch (err) {
      alert("Error importing points: " + err.message);
    }
  };

  const handleSave = async (setId) => {
    setBusyId(setId);
    try {
      await savePointSet(setId);
    } catch (err) {
      alert("Error saving points: " + err.message);
    } finally {
      setBusyId(null);
    }
  };

  const handleLoad = async (entry) => {
    setBusyId(`saved-${entry.id}`);
    try {
      await loadSavedPointSet(entry);
    } catch (err) {
      alert("Error loading points: " + err.message);
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm("Delete this saved point set?")) return;
    try {
      await deleteSavedPointSet(id);
    } catch (err) {
      alert("Error deleting points: " + err.message);
    }
  };

  const rowStyle = {
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center",
    padding: "8px 10px",
    background: "rgba(255,255,255,0.05)",
    borderRadius: "6px",
  };

  return (
    <>
      <div className="form-divider">Import Local Points (.LPS)</div>
      <input
        ref={fileInputRef}
        type="file"
        accept=".lps,.LPS"
        style={{ display: "none" }}
        onChange={handleFileChosen}
      />
      <button className="export-btn" onClick={() => fileInputRef.current?.click()}>
        Upload .LPS File
      </button>

      <div className="form-divider">On the Map</div>
      {pointSets.length === 0 && (
        <p style={{ opacity: 0.7 }}>No local points loaded.</p>
      )}
      <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
        {pointSets.map((set) => (
          <div key={set.id} style={rowStyle}>
            <div style={{ display: "flex", alignItems: "center", gap: "8px", minWidth: 0 }}>
              <span
                style={{
                  width: "12px",
                  height: "12px",
                  background: set.color,
                  transform: "rotate(45deg)",
                  flexShrink: 0,
                }}
              />
              <div style={{ minWidth: 0 }}>
                <div
                  style={{
                    fontWeight: 600,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                    opacity: set.visible ? 1 : 0.5,
                  }}
                >
                  {set.name}
                </div>
                <div style={{ fontSize: "0.75rem", opacity: 0.6 }}>
                  {set.points.length} points
                  {set.savedId ? " · saved" : ""}
                </div>
              </div>
            </div>
            <div style={{ display: "flex", gap: "6px", alignItems: "center", flexShrink: 0 }}>
              <button
                onClick={() => togglePointSetVisibility(set.id)}
                style={{
                  background: "none",
                  border: "none",
                  color: "#9ca3af",
                  cursor: "pointer",
                  padding: "0 4px",
                  display: "flex",
                }}
                title={set.visible ? "Hide points" : "Show points"}
              >
                <EyeIcon visible={set.visible} />
              </button>
              <button
                className="export-btn"
                onClick={() => handleSave(set.id)}
                disabled={busyId === set.id}
                title={set.savedId ? "Update the cloud copy" : "Save to your account"}
              >
                {busyId === set.id ? "Saving..." : "Save"}
              </button>
              <button
                className="cancel-btn"
                style={{ color: "#ef4444" }}
                onClick={() => removePointSet(set.id)}
                title="Remove from map (cloud copy is kept)"
              >
                Remove
              </button>
            </div>
          </div>
        ))}
      </div>

      <div className="form-divider">Saved Point Sets</div>
      {isLoadingSavedSets && <p>Loading...</p>}
      {!isLoadingSavedSets && savedPointSets.length === 0 && (
        <p style={{ opacity: 0.7 }}>No saved point sets yet.</p>
      )}
      <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
        {savedPointSets.map((entry) => (
          <div key={entry.id} style={rowStyle}>
            <div style={{ minWidth: 0 }}>
              <div
                style={{
                  fontWeight: 600,
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                  whiteSpace: "nowrap",
                }}
              >
                {entry.name}
              </div>
              <div style={{ fontSize: "0.75rem", opacity: 0.6 }}>
                {entry.point_count} points · Updated {formatTimestamp(entry.updated_at)}
              </div>
            </div>
            <div style={{ display: "flex", gap: "6px", flexShrink: 0 }}>
              <button
                className="export-btn"
                onClick={() => handleLoad(entry)}
                disabled={busyId === `saved-${entry.id}`}
              >
                {busyId === `saved-${entry.id}` ? "Loading..." : "Load"}
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
    </>
  );
};

export default LocalPointsPanel;
