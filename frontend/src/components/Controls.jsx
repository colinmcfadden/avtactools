import React, { useState } from "react";
import axios from "axios";
import MissionSummary from "./MissionSummary";
import { UNIT_TYPES } from "./UnitIcons";
import { convertToLatLongString } from "../utils/Helpers";

const Controls = ({
  setTargetLocation,
  setDetectedLZ,
  addHelo,
  showHeatmap,
  setShowHeatmap,
  terrainData,
  addGoAround,
  targetLocation,
  detectedLZ,
  addPZMarker,
  addUnit,
  setLoading,
  showLZOutline,
  setShowLZOutline,
  addSector,
  exportBox,
  enableExportMode,
  isExporting,
  setIsExporting,
  exportProgress,
  setMapData,
  setLatLong,
  setGridElevation,
  mapData
}) => {
  const [gridInput, setGridInput] = useState("16S GD 6338 3202");
  const [showUnitMenu, setShowUnitMenu] = useState(false);

  const API_BASE_URL = process.env.REACT_APP_API_URL;

  // Reusable SVG for PZ Button
  const pzButtonSvg = (
    <svg
      width="24"
      height="24"
      viewBox="0 0 100 80"
      style={{ overflow: "visible" }}
    >
      <path
        d="M 40,25 L 10,25 L 10,15 L -10,40 L 10,65 L 10,55 L 40,55 Z"
        fill="#00b5e2"
        stroke="none"
      />
      <circle
        cx="70"
        cy="40"
        r="15"
        fill="none"
        stroke="#ef4444"
        strokeWidth="8"
      />
    </svg>
  );

  const maxSlope = terrainData?.heatmap
    ? Math.max(...terrainData.heatmap.map((tile) => tile.slope))
    : 0;

  // Simple handler to trigger the MapView logic
  const onDownloadClick = () => {
    setIsExporting(true);
  };

  const handleSearch = async () => {
    setLoading(true);
    setMapData(prev => ({...prev, mgrs: gridInput}));
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-grid`, {
        grid: gridInput,
      });
      const { lat, lon } = res.data;
      setTargetLocation([lat, lon]);
      const analysis = await axios.post(
        `${API_BASE_URL}/analyze-field`,
        {
          lat,
          lon,
        },
      );
      setDetectedLZ(analysis.data.suggested_lz);

      setLatLong(convertToLatLongString(lat, lon));

      if (analysis.data.elevation) {
        setGridElevation(analysis.data.elevation); 
      }
    } catch (err) {
      alert("Error finding grid: " + err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="ff-panel">
      {/* Search Header */}
      <div className="ff-section search-section">
        <label className="ff-label">MGRS Target</label>
        <div className="ff-input-group">
          <input
            className="ff-input"
            value={gridInput}
            onChange={(e) => setGridInput(e.target.value)}
          />
          <button onClick={handleSearch} className="ff-btn primary">
            GO
          </button>
        </div>
      </div>

      <div className="ff-scroll-content">
        {/* Terrain & Mission Data Tile */}
        <div className="ff-card">
          <div className="ff-card-header">LZ/PZ Analysis</div>
          <div className="mission-data-grid">
            <MissionSummary
              detectedLZ={detectedLZ}
              terrainData={terrainData}
              targetLocation={targetLocation}
              mapData={mapData}
            />
          </div>

          <div className="toggle-row">
            <div className="toggle-item">
              <label className="switch">
                <input
                  type="checkbox"
                  checked={showHeatmap}
                  onChange={(e) => setShowHeatmap(e.target.checked)}
                />
                <span className="slider round"></span>
              </label>
              <span>Slope Map</span>
            </div>

            <div className="toggle-item">
              <label className="switch">
                <input
                  type="checkbox"
                  checked={showLZOutline}
                  onChange={(e) => setShowLZOutline(e.target.checked)}
                />
                <span className="slider round"></span>
              </label>
              <span>LZ Box</span>
            </div>
          </div>

          {showHeatmap && terrainData && (
            <div className="ff-stat-block">
              <div className="stat-row">
                <span>Max Slope</span>
                <span
                  style={{
                    color: maxSlope > 10 ? "#ef4444" : "#22c55e",
                    fontWeight: "bold",
                  }}
                >
                  {maxSlope.toFixed(1)}°
                </span>
              </div>
            </div>
          )}
        </div>

        {/* Tools Grid */}
        <div className="ff-card ff-card-tools">
          <div className="ff-card-header">LZ/PZ Tools</div>
          <div className="tool-grid">
            <button onClick={addHelo} className="ff-tool-btn" title="Add Helo">
              <img
                src="/icons/helicopter.png"
                alt="Helo"
                className="icon-img"
              />
              <span className="btn-label">Helo</span>
            </button>

            <button
              onClick={addPZMarker}
              className="ff-tool-btn"
              title="PZ/Pickup"
            >
              {pzButtonSvg}
              <span className="btn-label">PZ</span>
            </button>

            <button onClick={addSector} className="ff-tool-btn" title="Sector">
              <svg viewBox="0 0 50 50" width="24" height="24">
                <polygon
                  points="25,5 45,40 5,40"
                  fill="rgba(147, 112, 219, 0.5)"
                  stroke="#9370DB"
                  strokeWidth="3"
                />
              </svg>
              <span className="btn-label">Sector</span>
            </button>

            <div className="relative-wrapper">
              <button
                onClick={() => setShowUnitMenu(!showUnitMenu)}
                className={`ff-tool-btn ${showUnitMenu ? "active" : ""}`}
              >
                <span style={{ fontSize: "20px" }}>+</span>
                <span className="btn-label">Unit</span>
              </button>

              {showUnitMenu && (
                <div className="ff-dropdown">
                  {UNIT_TYPES.map((unit) => (
                    <div
                      key={unit.id}
                      onClick={() => {
                        addUnit(unit);
                        setShowUnitMenu(false);
                      }}
                      className="dropdown-item"
                    >
                      <img src={unit.path} alt={unit.label} />
                      <span>{unit.label}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
          <div className="tool-grid">
            <button className="ff-tool-btn" onClick={() => addGoAround("left")}>
              <svg width="24" height="24" viewBox="0 0 100 100">
                <path
                  d="M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z"
                  fill="#FFC107"
                  stroke="black"
                  strokeWidth="4"
                />
              </svg>
              <span className="btn-label">L-GA</span>
            </button>

            <button
              className="ff-tool-btn"
              onClick={() => addGoAround("right")}
            >
              <svg width="24" height="24" viewBox="0 0 100 100">
                <path
                  d="M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z"
                  fill="#FFC107"
                  stroke="black"
                  strokeWidth="4"
                />
              </svg>
              <span className="btn-label">R-GA</span>
            </button>
          </div>
        </div>

        {/* Export Controls */}
        <div className="ff-card ff-card-export">
          <div className="ff-card-header">Export</div>
          <div className="export-controls">
            <button
              onClick={enableExportMode}
              className={`ff-action-btn blue`}
            >
              Set Capture Area
            </button>

            <button
              onClick={onDownloadClick}
              disabled={!exportBox || isExporting}
              className={`ff-action-btn green ${!exportBox || isExporting ? "disabled" : ""}`}
            >
              {isExporting ? `Processing...` : "Export LZ Card"}
            </button>
          </div>
          {isExporting && (
            <div className="progress-bar-bg">
              <div
                className="progress-bar-fill"
                style={{ width: `${exportProgress}%` }}
              ></div>
            </div>
          )}
        </div>
        <div style={{ height: '100px' }}></div>
      </div>
    </div>
  );
};

export default Controls;
