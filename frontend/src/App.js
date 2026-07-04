import React, { useState, useEffect } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { convertToLatLongString, isPointInPolygon } from "./utils/Helpers";
import ExportModal from "./feature/export/ExportModal";
import MobileQuickAccess from "./components/MobileQuickAccess";
import MobileGridInput from "./components/MobileGridInput";
import axios from "axios";
import { useDoghouses } from "./feature/doghouses/useDoghouses";
import { useHelicopters } from "./feature/helicopters/useHelicopters";
import { useSectorsOfFire } from "./feature/sectorsOfFire/useSectorsOfFire";
import { useGoAround } from "./feature/goAround/useGoAround";
import { useWeather } from "./feature/weather/useWeather";
import { useUnit } from "./feature/unit/useUnit";
import { usePzMarker } from "./feature/pzMarker/usePzMarker";
import { useTerrain } from "./feature/terrain/useTerrain";
import { useExport } from "./feature/export/useExport";
import { useAuth } from "./feature/auth/AuthContext";
import UserMenu from "./feature/auth/UserMenu";
import { useSavedMaps } from "./feature/savedMaps/useSavedMaps";
import HistoryModal from "./feature/savedMaps/HistoryModal";

function App() {
  const [targetLocation, setTargetLocation] = useState(null);
  const [detectedLZ, setDetectedLZ] = useState(null);
  const [gridElevation, setGridElevation] = useState("");
  const [latLong, setLatLong] = useState("");
  const [customLZ, setCustomLZ] = useState(null);
  const [contextMenu, setContextMenu] = useState(null);
  const [showHeatmap, setShowHeatmap] = useState(false);
  const [loading, setLoading] = useState(false);
  const [showLZOutline, setShowLZOutline] = useState(true);
  const [mapData, setMapData] = useState([]);
  const [flightData, setFlightData] = useState({});
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [gridInput, setGridInput] = useState("16S GC 28864 55349");
  const [isDrawingLZ, setIsDrawingLZ] = useState(false);
  const [drawingPoints, setDrawingPoints] = useState([]);
  const [clickedGrid, setClickedGrid] = useState("Loading...");
  const [mapStyle, setMapStyle] = useState("satellite");

  const { goAround, setGoAround, addGoAround, updateGoAround, deleteGoAround } =
    useGoAround(targetLocation);
  const {
    sectorsOfFire,
    setSectors,
    addSectorOfFire,
    updateSectorOfFirePoint,
    moveSectorOfFire,
    deleteSectorOfFire,
  } = useSectorsOfFire(targetLocation);
  const { units, setUnits, addUnit, updateUnitPosition, deleteUnit } =
    useUnit(targetLocation);
  const { pzMarker, setPzMarkers, addPZMarker, updatePZMarker, deletePZMarker } =
    usePzMarker(targetLocation);
  const { winds, activeNotams, setActiveNotams, loadingWeather, fetchWeather } =
    useWeather();
  const { doghouses, setDoghouses, updateDoghouse } = useDoghouses(
    targetLocation,
    setFlightData,
  );

  // Restoring a saved doghouse layout must happen AFTER useDoghouses' own
  // targetLocation-triggered regeneration effect, or the regeneration clobbers
  // it. Effects run in hook-registration order within this component, so this
  // effect (declared after useDoghouses) always runs after that one.
  const [pendingDoghouseRestore, setPendingDoghouseRestore] = useState(null);
  useEffect(() => {
    if (pendingDoghouseRestore) {
      setDoghouses(pendingDoghouseRestore);
      setPendingDoghouseRestore(null);
    }
  }, [pendingDoghouseRestore, setDoghouses]);
  const {
    helicopters,
    setHelicopters,
    addHelo,
    updateHelicopter,
    deleteHelicopter,
    proximityAlerts,
  } = useHelicopters(targetLocation);
  const {
    exportBox,
    setExportBox,
    isExporting,
    setIsExporting,
    exportProgress,
    setExportProgress,
    isExportModalOpen,
    setIsExportModalOpen,
    exportSuccess,
    enableExportMode,
    updateExportBox,
    deleteExportBox,
    handleExportComplete,
    handleFinalExport,
  } = useExport(targetLocation);
  const {
    performTerrainAnalysis,
    terrainData,
    setTerrainData,
    elevation,
    slope,
  } = useTerrain(
    targetLocation,
    setLoading,
    customLZ,
    setContextMenu,
    setLatLong,
    gridElevation,
    setGridElevation,
    detectedLZ,
    setDetectedLZ,
    setCustomLZ,
    fetchWeather,
  );

  const { user } = useAuth();
  const { history, isLoadingHistory, fetchHistory, saveMap, loadMap, updateMap, deleteMap } =
    useSavedMaps();
  const [isHistoryModalOpen, setIsHistoryModalOpen] = useState(false);

  const handleOpenHistory = () => {
    if (!user) {
      alert("Please sign in with Google to save and load maps.");
      return;
    }
    setIsHistoryModalOpen(true);
  };

  const serializeMapState = () => ({
    targetLocation,
    gridInput,
    mapData,
    mapStyle,
    showLZOutline,
    showHeatmap,
    customLZ,
    detectedLZ,
    terrainData,
    gridElevation,
    latLong,
    flightData,
    helicopters,
    pzMarker,
    sectorsOfFire,
    goAround,
    units,
    doghouses,
  });

  const applyMapState = (snapshot) => {
    if (!snapshot) return;
    setTargetLocation(snapshot.targetLocation ?? null);
    setGridInput(snapshot.gridInput ?? "16S GC 28864 55349");
    setMapData(snapshot.mapData ?? []);
    setMapStyle(snapshot.mapStyle ?? "satellite");
    setShowLZOutline(snapshot.showLZOutline ?? true);
    setShowHeatmap(snapshot.showHeatmap ?? false);
    setCustomLZ(snapshot.customLZ ?? null);
    setDetectedLZ(snapshot.detectedLZ ?? null);
    setTerrainData(snapshot.terrainData ?? null);
    setGridElevation(snapshot.gridElevation ?? "");
    setLatLong(snapshot.latLong ?? "");
    setFlightData(snapshot.flightData ?? {});
    setHelicopters(snapshot.helicopters ?? []);
    setPzMarkers(snapshot.pzMarker ?? []);
    setSectors(snapshot.sectorsOfFire ?? []);
    setGoAround(snapshot.goAround ?? []);
    setUnits(snapshot.units ?? []);
    setPendingDoghouseRestore(snapshot.doghouses ?? []);
  };

  const handleMapRightClick = async (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "map", lat, lon });
    setClickedGrid("Calculating...");
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-to-mgrs`, {
        lat,
        lon,
      });
      setClickedGrid(res.data.mgrs);
    } catch (err) {
      setClickedGrid(convertToLatLongString(lat, lon)); // Fallback to Lat/Lon if backend fails
    }
  };

  const handleSetAsTarget = () => {
    if (clickedGrid === "Calculating...") return;

    const newTarget = [contextMenu.lat, contextMenu.lon];

    // 1. Update the UI Text
    setGridInput(clickedGrid);
    setMapData((prev) => ({ ...prev, mgrs: clickedGrid }));

    // 2. Always clear the "Official" data when a target moves
    // (So the user is forced to hit Analyze for the new center point)
    setDetectedLZ(null);
    setTerrainData(null);

    // 3. SMART CLEAR: Only destroy the custom drawn LZ if the new target is OUTSIDE of it
    if (customLZ && !isPointInPolygon(newTarget, customLZ)) {
      setCustomLZ(null);
      setDrawingPoints([]);
    }

    // 4. Set the new target (This triggers your Doghouses)
    setTargetLocation(newTarget);

    // 5. Close the menu
    setContextMenu(null);
  };

  // 2. LZ Right-Click Handler
  const handleLZRightClick = async (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "lz", lat, lon });
    setClickedGrid("Calculating...");
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-to-mgrs`, {
        lat,
        lon,
      });
      setClickedGrid(res.data.mgrs);
    } catch (err) {
      setClickedGrid(convertToLatLongString(lat, lon));
    }
  };

  // 3. Drawing Controls
  const toggleDrawingMode = () => {
    if (isDrawingLZ) {
      // Finish drawing
      if (drawingPoints.length > 2) {
        setCustomLZ(drawingPoints);
      }
      setIsDrawingLZ(false);
      setDrawingPoints([]);
    } else {
      // Start drawing
      setCustomLZ(null);
      setDrawingPoints([]);
      setIsDrawingLZ(true);
    }
  };

  const handleSearch = async () => {
    setLoading(true);
    setMapData((prev) => ({ ...prev, mgrs: gridInput }));
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-grid`, {
        grid: gridInput,
      });
      const { lat, lon } = res.data;
      setTargetLocation([lat, lon]);
    } catch (err) {
      alert("Error finding grid: " + err.message);
    } finally {
      setLoading(false);
      setIsMobileMenuOpen(false); // Closes menu if they searched from the sidebar
    }
  };

  const API_BASE_URL = process.env.REACT_APP_API_URL;

  return (
    <div className="app-container">
      <button
        className="mobile-hamburger-btn"
        onClick={() => setIsMobileMenuOpen(true)}
      >
        ☰
      </button>
      <div className={`sidebar ${isMobileMenuOpen ? "mobile-open" : ""}`}>
        <div className="sidebar-header">
          <UserMenu />
          <button
            className="close-menu-btn mobile-only"
            onClick={() => setIsMobileMenuOpen(false)}
          >
            ✕
          </button>
        </div>
        <Controls
          onOpenHistory={handleOpenHistory}
          setTargetLocation={setTargetLocation}
          setDetectedLZ={setDetectedLZ}
          setAssets={setHelicopters}
          addHelo={addHelo}
          setShowHeatmap={setShowHeatmap}
          terrainData={terrainData}
          addGoAround={addGoAround}
          targetLocation={targetLocation}
          detectedLZ={detectedLZ}
          addPZMarker={addPZMarker}
          addUnit={addUnit}
          setLoading={setLoading}
          showLZOutline={showLZOutline}
          setShowLZOutline={setShowLZOutline}
          addSector={addSectorOfFire}
          exportBox={exportBox}
          enableExportMode={enableExportMode}
          setIsExporting={setIsExporting}
          setExportProgress={setExportProgress}
          isExporting={isExporting}
          exportProgress={exportProgress}
          setMapData={setMapData}
          setLatLong={setLatLong}
          setGridElevation={setGridElevation}
          mapData={{
            elevation: gridElevation,
          }}
          isMobileMenuOpen={isMobileMenuOpen}
          closeMobileMenu={() => setIsMobileMenuOpen(false)}
          gridInput={gridInput}
          setGridInput={setGridInput}
          handleSearch={handleSearch}
          isDrawingLZ={isDrawingLZ}
          toggleDrawingMode={toggleDrawingMode}
          mapStyle={mapStyle}
          setMapStyle={setMapStyle}
          performTerrainAnalysis={performTerrainAnalysis}
          setActiveNotams={setActiveNotams}
          winds={winds}
          loadingWeather={loadingWeather}
        />
      </div>
      <div className="map-area">
        <MobileGridInput
          gridInput={gridInput}
          setGridInput={setGridInput}
          handleSearch={handleSearch}
        />
        <MobileQuickAccess
          addHelo={addHelo}
          addPZMarker={addPZMarker}
          addSector={addSectorOfFire}
          addUnit={addUnit}
          addGoAround={addGoAround}
          enableExportMode={enableExportMode}
          onDownloadClick={() => setIsExporting(true)}
          exportBox={exportBox}
          isExporting={isExporting}
          exportProgress={exportProgress}
        />
        <MapView
          targetLocation={targetLocation}
          mapData={mapData}
          detectedLZ={detectedLZ}
          assets={helicopters}
          updateAsset={updateHelicopter}
          deleteAsset={deleteHelicopter}
          showHeatmap={showHeatmap}
          terrainData={terrainData}
          doghouses={doghouses}
          updateDoghouse={updateDoghouse}
          goArounds={goAround}
          updateGoAround={updateGoAround}
          deleteGoAround={deleteGoAround}
          updatePZMarker={updatePZMarker}
          deletePZMarker={deletePZMarker}
          pzMarkers={pzMarker}
          units={units}
          updateUnitPosition={updateUnitPosition}
          showLZOutline={showLZOutline}
          deleteUnit={deleteUnit}
          sectors={sectorsOfFire}
          updateSectorPoint={updateSectorOfFirePoint}
          moveSector={moveSectorOfFire}
          deleteSector={deleteSectorOfFire}
          exportBox={exportBox}
          updateExportBox={updateExportBox}
          deleteExportBox={deleteExportBox}
          isExporting={isExporting}
          onExportComplete={handleExportComplete}
          setExportProgress={setExportProgress}
          setExportBox={setExportBox}
          setIsExporting={setIsExporting}
          isDrawingLZ={isDrawingLZ}
          drawingPoints={drawingPoints}
          setDrawingPoints={setDrawingPoints}
          customLZ={customLZ}
          handleMapRightClick={handleMapRightClick}
          handleLZRightClick={handleLZRightClick}
          setContextMenu={setContextMenu}
          mapStyle={mapStyle}
        />

        <div className="alert-queue">
          {proximityAlerts.map((alert) => (
            <div key={alert.id} className="proximity-alert">
              ⚠️ {alert.message}
            </div>
          ))}
        </div>
      </div>

      {/* GLOBAL CONTEXT MENU */}
      {contextMenu && (
        <div
          style={{
            position: "fixed",
            left: contextMenu.x,
            top: contextMenu.y,
            zIndex: 99999,
            background: "#1e293b",
            border: "1px solid #334155",
            color: "white",
            padding: "8px",
            borderRadius: "8px",
            boxShadow: "0 4px 6px rgba(0,0,0,0.5)",
            minWidth: "150px",
          }}
        >
          {contextMenu.type === "map" ? (
            <div
              style={{ display: "flex", flexDirection: "column", gap: "8px" }}
            >
              <div
                style={{
                  padding: "4px",
                  fontSize: "14px",
                  fontWeight: "bold",
                  textAlign: "center",
                }}
              >
                {clickedGrid}
              </div>
              <button
                onClick={handleSetAsTarget}
                disabled={clickedGrid === "Calculating..."}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#10b981",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                  opacity: clickedGrid === "Calculating..." ? 0.5 : 1,
                }}
              >
                Set as Target
              </button>
            </div>
          ) : (
            // --- THE NEW LZ CONTEXT MENU ---
            <div
              style={{ display: "flex", flexDirection: "column", gap: "8px" }}
            >
              <div
                style={{
                  padding: "4px",
                  fontSize: "14px",
                  fontWeight: "bold",
                  textAlign: "center",
                }}
              >
                {clickedGrid}
              </div>

              <button
                onClick={handleSetAsTarget}
                disabled={clickedGrid === "Calculating..."}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#10b981",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                  opacity: clickedGrid === "Calculating..." ? 0.5 : 1,
                }}
              >
                Set as Target
              </button>

              <hr style={{ borderColor: "#334155", margin: "2px 0" }} />

              <button
                onClick={performTerrainAnalysis}
                // The magic logic: Disabled if no target is set, OR if the target is outside the LZ
                disabled={
                  !targetLocation || !isPointInPolygon(targetLocation, customLZ)
                }
                title={
                  !targetLocation || !isPointInPolygon(targetLocation, customLZ)
                    ? "You must set a target inside the LZ first"
                    : ""
                }
                style={{
                  width: "100%",
                  padding: "8px",
                  background: "#3b82f6",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor:
                    !targetLocation ||
                    !isPointInPolygon(targetLocation, customLZ)
                      ? "not-allowed"
                      : "pointer",
                  opacity:
                    !targetLocation ||
                    !isPointInPolygon(targetLocation, customLZ)
                      ? 0.4
                      : 1,
                }}
              >
                Analyze LZ
              </button>

              <button 
                onClick={() => {
                  setCustomLZ(null);       
                  setDrawingPoints([]);    
                  setContextMenu(null);    
                }}
                style={{
                  width: '100%', padding: '4px', opacity: 1, 
                  color: 'red', border: 'none', borderRadius: '4px', cursor: 'pointer'
                }}
              >
                ✕ Delete LZ
              </button>
            </div>
          )}
        </div>
      )}

      <ExportModal
        isOpen={isExportModalOpen}
        onClose={() => {
          setIsExportModalOpen(false);
          setIsExporting(false);
          setExportProgress(0);
        }}
        onExport={handleFinalExport}
        mapData={{
          mgrs: mapData.mgrs,
          elevation: gridElevation,
          latLong: latLong,
        }}
        flightData={flightData}
        proximityAlerts={proximityAlerts}
        activeNotams={activeNotams}
      />

      <HistoryModal
        isOpen={isHistoryModalOpen}
        onClose={() => setIsHistoryModalOpen(false)}
        history={history}
        isLoadingHistory={isLoadingHistory}
        fetchHistory={fetchHistory}
        saveMap={saveMap}
        loadMap={loadMap}
        updateMap={updateMap}
        deleteMap={deleteMap}
        buildSnapshot={serializeMapState}
        applySnapshot={applyMapState}
      />

      {exportSuccess && (
        <div className="success-toast">
          <span>✅ LZ/PZ Card successfully exported.</span>
        </div>
      )}

      {loading && (
        <div className="loading-overlay">
          <div className="loader-container">
            <div className="spinner"></div>
            <div className="loading-text">
              Performing terrain and LZ/PZ analysis...
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
