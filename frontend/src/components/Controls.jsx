import React, { useRef, useState } from "react";
import MissionSummary from "./MissionSummary";
import { UNIT_TYPES } from "../feature/unit/UnitIcons";
import packageJson from "../../package.json";

const Controls = ({
  onImportMsnx,
  isSketching,
  toggleRouteSketch,
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
  mapData,
  closeMobileMenu,
  gridInput,
  setGridInput,
  handleSearch,
  setDetectedLZ,
  setLatLong,
  setGridElevation,
  isDrawingLZ,
  toggleDrawingMode,
  mapStyle,
  setMapStyle,
  performTerrainAnalysis,
  setActiveNotams,
  winds,
  loadingWeather,
}) => {
  const [showUnitMenu, setShowUnitMenu] = useState(false);
  const msnxInputRef = useRef(null);

  const handleMsnxFileChange = (e) => {
    const file = e.target.files[0];
    if (file) {
      onImportMsnx(file);
    }
    e.target.value = "";
  };

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

  const API_BASE_URL = process.env.REACT_APP_API_URL;

  const maxSlope = terrainData?.heatmap
    ? Math.max(...terrainData.heatmap.map((tile) => tile.slope))
    : 0;

  // Simple handler to trigger the MapView logic
  const onDownloadClick = () => {
    setIsExporting(true);
  };

  const handleAddHelo = () => {
    addHelo();
    closeMobileMenu();
  };
  const handleAddPZMarker = () => {
    addPZMarker();
    closeMobileMenu();
  };
  const handleAddSector = () => {
    addSector();
    closeMobileMenu();
  };
  const handleAddGoAround = (dir) => {
    addGoAround(dir);
    closeMobileMenu();
  };
  const handleAddUnit = (unit) => {
    addUnit(unit);
    setShowUnitMenu(false);
    closeMobileMenu();
  };
  const handleEnableExportMode = () => {
    enableExportMode();
    closeMobileMenu();
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
              setActiveNotams={setActiveNotams}
              winds={winds}
              loadingWeather={loadingWeather}
            />
          </div>

          {mapData && targetLocation && (
            <div className="toggle-row">
              <button
                onClick={() =>
                  performTerrainAnalysis(targetLocation[0], targetLocation[1])
                }
                className={`ff-action-btn ff-btn primary`}
              >
                Analyze the LZ
              </button>
            </div>
          )}

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
          <div className="toggle-row">
            <div className="toggle-item">
              <label className="switch">
                <input
                  type="checkbox"
                  checked={mapStyle === "topo"}
                  onChange={(e) =>
                    setMapStyle(e.target.checked ? "topo" : "satellite")
                  }
                />
                <span className="slider round"></span>
              </label>
              <span>Topo Map</span>
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
            <button
              onClick={toggleDrawingMode}
              className={`ff-tool-btn ${isDrawingLZ ? "active" : ""}`}
              title="Draw Custom LZ"
              style={{ borderColor: isDrawingLZ ? "#FFC107" : "" }}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke={isDrawingLZ ? "#FFC107" : "currentColor"}
                strokeWidth="2"
                width="24"
                height="24"
              >
                <polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5 12 2" />
              </svg>
              <span
                className="btn-label"
                style={{ color: isDrawingLZ ? "#FFC107" : "" }}
              >
                {isDrawingLZ ? "Finish LZ" : "Draw LZ"}
              </span>
            </button>

            <button
              onClick={handleAddHelo}
              className="ff-tool-btn"
              title="Add Helo"
            >
              <img
                src="/icons/helicopter.png"
                alt="Helo"
                className="icon-img"
              />
              <span className="btn-label">Helo</span>
            </button>

            <button
              onClick={handleAddPZMarker}
              className="ff-tool-btn"
              title="PZ/Pickup"
            >
              {pzButtonSvg}
              <span className="btn-label">PZ</span>
            </button>

            <button
              onClick={handleAddSector}
              className="ff-tool-btn"
              title="Sector"
            >
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
          </div>

          <div className="tool-grid">
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
                        handleAddUnit(unit);
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

            <button
              className="ff-tool-btn"
              onClick={() => handleAddGoAround("left")}
            >
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
              onClick={() => handleAddGoAround("right")}
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
            <button onClick={enableExportMode} className={`ff-action-btn blue`}>
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

        {/* Routes */}
        <div className="ff-card">
          <div className="ff-card-header">Routes (.msnx)</div>
          <div className="tool-grid">
            <button
              onClick={toggleRouteSketch}
              className={`ff-tool-btn ${isSketching ? "active" : ""}`}
              title="Sketch a route (click the map to add points)"
              style={{ borderColor: isSketching ? "#64D2FF" : "" }}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke={isSketching ? "#64D2FF" : "currentColor"}
                strokeWidth="2"
                width="24"
                height="24"
              >
                <circle cx="4" cy="20" r="2" />
                <circle cx="12" cy="9" r="2" />
                <circle cx="20" cy="15" r="2" />
                <path d="M5.5 18.5 L10.5 10.5 M13.7 10 L18.3 14" />
              </svg>
              <span
                className="btn-label"
                style={{ color: isSketching ? "#64D2FF" : "" }}
              >
                {isSketching ? "End Route" : "Route"}
              </span>
            </button>

            <button
              onClick={() => msnxInputRef.current?.click()}
              className="ff-tool-btn"
              title="Upload a .msnx mission file"
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                width="24"
                height="24"
              >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
              <span className="btn-label">Upload</span>
            </button>
          </div>
          <input
            ref={msnxInputRef}
            type="file"
            accept=".msnx"
            style={{ display: "none" }}
            onChange={handleMsnxFileChange}
          />
        </div>

        <div style={{ height: "100px" }}></div>
      </div>
      <div className="controls-footer">
        <a
          href="/"
          className="footer-link"
          target="_blank"
          rel="noopener noreferrer"
        >
          <img
            src="/img/ezpz_logo-1.png"
            alt="EZ-PZ Logo"
            className="footer-logo"
          />
        </a>
        <div className="footer-brand-text">
          <span className="footer-version">v{packageJson.version}</span>
        </div>
      </div>
    </div>
  );
};

export default Controls;
