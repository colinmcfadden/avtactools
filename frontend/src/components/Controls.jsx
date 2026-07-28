import React, { useEffect, useRef, useState } from "react";
import MissionSummary from "./MissionSummary";
import AircraftPicker from "../feature/aircraft/AircraftPicker";
import "../feature/aircraft/aircraft.css";
import { UNIT_TYPES } from "../feature/unit/UnitIcons";
import { symbolDataUri } from "../feature/symbols/milsym";
import packageJson from "../../package.json";

const Controls = ({
  aircraftProfiles,
  activeAircraftProfile,
  onSelectAircraft,
  onManageAircraft,
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
  onOpenUnitBuilder,
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
  performTerrainAnalysis,
  setActiveNotams,
  winds,
  loadingWeather,
  // Diagram lifecycle controls. They intentionally default to the legacy
  // behavior so Controls can still be used outside the diagram workspace.
  canAnalyze: canAnalyzeProp,
  canDrawBoundary: canDrawBoundaryProp,
  canUseDiagramTools: canUseDiagramToolsProp,
  canSaveDiagram: canSaveDiagramProp,
  diagramStatus,
  diagramReadinessText,
  readinessText,
  // When the desktop panel is dragged narrow it renders as an icon rail: labels
  // and detail cards hide, tools stack vertically, MGRS input stays usable.
  compact = false,
  // Per-user feature entitlements ({key: bool}); a missing key means enabled.
  features = {},
}) => {
  const can = (key) => features[key] !== false;
  const [showUnitMenu, setShowUnitMenu] = useState(false);
  const msnxInputRef = useRef(null);

  // Collapsed rail: the MGRS input is too narrow to use inline, so a search
  // icon pops out a floating (fixed-position, escaping the panel's clip) input.
  const [showMgrsPopout, setShowMgrsPopout] = useState(false);
  const [mgrsPopoutPos, setMgrsPopoutPos] = useState({ top: 64, left: 84 });
  const mgrsBtnRef = useRef(null);
  const mgrsPopoutRef = useRef(null);

  const toggleMgrsPopout = () => {
    setShowMgrsPopout((open) => {
      if (!open) {
        const r = mgrsBtnRef.current?.getBoundingClientRect();
        if (r) setMgrsPopoutPos({ top: r.top, left: r.right + 8 });
      }
      return !open;
    });
  };
  const submitMgrs = () => {
    handleSearch();
    setShowMgrsPopout(false);
  };

  const hasLifecycleProps =
    canAnalyzeProp !== undefined ||
    canDrawBoundaryProp !== undefined ||
    canUseDiagramToolsProp !== undefined ||
    canSaveDiagramProp !== undefined ||
    Boolean(diagramStatus) ||
    Boolean(diagramReadinessText) ||
    Boolean(readinessText);
  const legacyCanAnalyze = Boolean(mapData && targetLocation);
  const canAnalyze = canAnalyzeProp ?? legacyCanAnalyze;
  const canDrawBoundary = canDrawBoundaryProp ?? true;
  const canUseDiagramTools = canUseDiagramToolsProp ?? true;
  const canSaveDiagram = canSaveDiagramProp ?? canUseDiagramTools;
  const canExport = canUseDiagramTools && canSaveDiagram;
  const readinessMessage =
    diagramReadinessText ??
    readinessText ??
    (hasLifecycleProps && !targetLocation
      ? "Set a target on the map to begin an LZ/PZ diagram."
      : hasLifecycleProps && !canDrawBoundary
        ? "Set a target on the map before drawing an LZ boundary."
      : hasLifecycleProps && !canUseDiagramTools
        ? "Analyze the LZ to unlock planning tools."
        : null);
  const analysisEnabled = Boolean(targetLocation) && canAnalyze;
  const showAnalyzeAction = hasLifecycleProps || legacyCanAnalyze;
  const diagramStatusLabel =
    typeof diagramStatus === "string" && diagramStatus.length > 0
      ? diagramStatus.replace(/[-_]/g, " ").toUpperCase()
      : "PLAN";

  useEffect(() => {
    if (!canUseDiagramTools) {
      setShowUnitMenu(false);
    }
  }, [canUseDiagramTools]);

  // Expanding the panel dismisses the MGRS popout (the inline input returns).
  useEffect(() => {
    if (!compact) setShowMgrsPopout(false);
  }, [compact]);

  // Dismiss the popout on an outside click/tap or Escape.
  useEffect(() => {
    if (!showMgrsPopout) return;
    const onDown = (e) => {
      if (
        mgrsPopoutRef.current?.contains(e.target) ||
        mgrsBtnRef.current?.contains(e.target)
      ) {
        return;
      }
      setShowMgrsPopout(false);
    };
    const onKey = (e) => e.key === "Escape" && setShowMgrsPopout(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("touchstart", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("touchstart", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [showMgrsPopout]);

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

  const maxSlope = terrainData?.stats?.maxDeg ?? 0;

  // Simple handler to trigger the MapView logic
  const onDownloadClick = () => {
    if (!canExport || isExporting) return;
    setIsExporting(true);
  };

  const handleAddHelo = () => {
    if (!canUseDiagramTools) return;
    addHelo();
    closeMobileMenu?.();
  };
  const handleAddPZMarker = () => {
    if (!canUseDiagramTools) return;
    addPZMarker();
    closeMobileMenu?.();
  };
  const handleAddSector = () => {
    if (!canUseDiagramTools) return;
    addSector();
    closeMobileMenu?.();
  };
  const handleAddGoAround = (dir) => {
    if (!canUseDiagramTools) return;
    addGoAround(dir);
    closeMobileMenu?.();
  };
  const handleAddUnit = (unit) => {
    if (!canUseDiagramTools) return;
    addUnit(unit);
    setShowUnitMenu(false);
    closeMobileMenu?.();
  };
  const handleEnableExportMode = () => {
    if (!canExport) return;
    enableExportMode();
    closeMobileMenu?.();
  };
  const handleToggleDrawingMode = () => {
    if (!canDrawBoundary) return;
    toggleDrawingMode();
  };

  return (
    <div className={`ff-panel ${compact ? "compact" : ""}`}>
      {/* Search Header. Expanded: inline MGRS input. Collapsed: a search icon
          that pops the input out over the map so it stays typeable. */}
      {compact ? (
        <div className="search-section-rail">
          <button
            ref={mgrsBtnRef}
            type="button"
            onClick={toggleMgrsPopout}
            className={`ff-tool-btn mgrs-rail-btn ${showMgrsPopout ? "active" : ""}`}
            title="MGRS Target"
            aria-label="Enter MGRS target"
          >
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="7" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
          </button>
          {showMgrsPopout && (
            <div
              ref={mgrsPopoutRef}
              className="mgrs-popout"
              style={{ top: mgrsPopoutPos.top, left: mgrsPopoutPos.left }}
            >
              <label className="ff-label">MGRS Target</label>
              <div className="ff-input-group">
                <input
                  className="ff-input"
                  autoFocus
                  value={gridInput}
                  onChange={(e) => setGridInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") submitMgrs();
                  }}
                />
                <button onClick={submitMgrs} className="ff-btn primary">
                  GO
                </button>
              </div>
            </div>
          )}
        </div>
      ) : (
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
      )}

      <div className="ff-scroll-content">
        {/* Mission aircraft — drives icons, separation, capacity, and plan defaults */}
        <div className="ff-card">
          <div className="ff-card-header">Aircraft</div>
          <AircraftPicker
            profiles={aircraftProfiles}
            activeProfile={activeAircraftProfile}
            onSelect={onSelectAircraft}
            onManage={onManageAircraft}
            canManage={can("aircraft_profiles")}
          />
        </div>

        {/* Terrain & Mission Data Tile */}
        {can("lz_pz_tools") && (
        <div className="ff-card ff-card-analysis">
          <div className="ff-card-header">LZ/PZ Analysis</div>
          <div className="mission-data-grid">
            <MissionSummary
              aircraftProfile={activeAircraftProfile}
              detectedLZ={detectedLZ}
              terrainData={terrainData}
              targetLocation={targetLocation}
              mapData={mapData}
              setActiveNotams={setActiveNotams}
              winds={winds}
              loadingWeather={loadingWeather}
            />
          </div>

          {showAnalyzeAction && (
            <div className="analysis-action-row">
              <button
                onClick={() => {
                  if (!analysisEnabled) return;
                  performTerrainAnalysis(targetLocation[0], targetLocation[1]);
                }}
                disabled={!analysisEnabled}
                title={
                  !analysisEnabled
                    ? readinessMessage || "Set a target on the map before analyzing the LZ."
                    : "Analyze the active LZ/PZ diagram"
                }
                className={`ff-action-btn ff-btn primary ${!analysisEnabled ? "disabled" : ""}`}
              >
                Analyze the LZ
              </button>
            </div>
          )}

          {readinessMessage && (
            <div
              className="ff-readiness-text"
              role="status"
              style={{
                color: "#9fb0c8",
                fontSize: "0.76rem",
                lineHeight: 1.35,
                marginTop: "0.65rem",
              }}
            >
              {readinessMessage}
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
          {terrainData?.stats && (
            <div className="ff-stat-block">
              <div className="stat-row">
                <span>Max / P95 slope</span>
                <span
                  style={{
                    color: maxSlope >= 15 ? "#ef4444" : maxSlope > 10 ? "#f59e0b" : "#22c55e",
                    fontWeight: "bold",
                  }}
                >
                  {maxSlope.toFixed(1)}° / {terrainData.stats.p95Deg.toFixed(1)}°
                </span>
              </div>
              <div className="stat-row">
                <span>Terrain source</span>
                <span>{terrainData.source} · {terrainData.resolutionM} m</span>
              </div>
            </div>
          )}
        </div>
        )}

        {/* Tools Grid */}
        {can("lz_pz_tools") && (
        <div className="ff-card ff-card-tools">
          <div className="ff-card-header">LZ/PZ Tools</div>
          <div className="tool-grid">
            <button
              onClick={handleToggleDrawingMode}
              disabled={!canDrawBoundary}
              className={`ff-tool-btn ${isDrawingLZ ? "active" : ""}`}
              title={
                canDrawBoundary
                  ? "Draw Custom LZ"
                  : readinessMessage || "Set a target on the map before drawing an LZ boundary."
              }
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
              disabled={!canUseDiagramTools}
              className="ff-tool-btn"
              title={canUseDiagramTools ? "Add Helo" : readinessMessage}
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
              disabled={!canUseDiagramTools}
              className="ff-tool-btn"
              title={canUseDiagramTools ? "PZ/Pickup" : readinessMessage}
            >
              {pzButtonSvg}
              <span className="btn-label">PZ</span>
            </button>

            <button
              onClick={handleAddSector}
              disabled={!canUseDiagramTools}
              className="ff-tool-btn"
              title={canUseDiagramTools ? "Sector" : readinessMessage}
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
                onClick={() => {
                  if (!canUseDiagramTools) return;
                  setShowUnitMenu(!showUnitMenu);
                }}
                disabled={!canUseDiagramTools}
                className={`ff-tool-btn ${showUnitMenu ? "active" : ""}`}
                title={canUseDiagramTools ? "Add Unit" : readinessMessage}
              >
                <span style={{ fontSize: "20px" }}>+</span>
                <span className="btn-label">Unit</span>
              </button>

              {showUnitMenu && (
                <div className="ff-dropdown">
                  <div
                    onClick={() => {
                      if (!canUseDiagramTools) return;
                      onOpenUnitBuilder?.();
                      setShowUnitMenu(false);
                    }}
                    className="dropdown-item"
                    style={{ fontWeight: 600, color: "var(--ff-accent, #00b5e2)" }}
                  >
                    <span>Build MIL-STD symbol…</span>
                  </div>
                  {UNIT_TYPES.map((unit) => (
                    <div
                      key={unit.id}
                      onClick={() => {
                        if (!canUseDiagramTools) return;
                        handleAddUnit(unit);
                        setShowUnitMenu(false);
                      }}
                      className="dropdown-item"
                    >
                      <img src={symbolDataUri(unit.sidc, { size: 22 })} alt={unit.label} />
                      <span>{unit.label}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <button
              className="ff-tool-btn"
              onClick={() => handleAddGoAround("left")}
              disabled={!canUseDiagramTools}
              title={canUseDiagramTools ? "Add left go-around" : readinessMessage}
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
              disabled={!canUseDiagramTools}
              title={canUseDiagramTools ? "Add right go-around" : readinessMessage}
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
        )}

        {/* Export Controls */}
        {can("exports") && (
        <div className="ff-card ff-card-export">
          <div className="ff-card-header">Export</div>
          <div className="export-controls">
            <button
              onClick={handleEnableExportMode}
              disabled={!canExport}
              title={canExport ? "Set the capture area" : readinessMessage}
              className={`ff-action-btn blue ${!canExport ? "disabled" : ""}`}
            >
              Set Capture Area
            </button>

            <button
              onClick={onDownloadClick}
              disabled={!canExport || !exportBox || isExporting}
              title={
                !canExport
                  ? readinessMessage
                  : !exportBox
                    ? "Set a capture area before exporting."
                    : "Export LZ card"
              }
              className={`ff-action-btn green ${!canExport || !exportBox || isExporting ? "disabled" : ""}`}
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
        )}

        {/* Routes */}
        {(can("routes") || can("msnx_import")) && (
        <div className="ff-card ff-card-routes">
          <div className="ff-card-header">Routes (.msnx)</div>
          <div className="tool-grid">
            {can("routes") && (
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
            )}

            {can("msnx_import") && (
            <button
              onClick={() => msnxInputRef.current?.click()}
              className="ff-tool-btn"
              title="Import a .msnx mission file"
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
              <span className="btn-label">Import MSNX</span>
            </button>
            )}
          </div>
          <input
            ref={msnxInputRef}
            type="file"
            accept=".msnx"
            style={{ display: "none" }}
            onChange={handleMsnxFileChange}
          />
        </div>
        )}

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
