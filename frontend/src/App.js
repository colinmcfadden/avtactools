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
import { useMsnxImport } from "./feature/msnxImport/useMsnxImport";
import { useRouteSketch } from "./feature/msnxImport/useRouteSketch";
import RoutesPanel from "./feature/msnxImport/RoutesPanel";
import ForeFlightModal from "./feature/msnxImport/ForeFlightModal";
import { useSavedRoutes } from "./feature/msnxImport/useSavedRoutes";
import MapStyleSwitcher from "./feature/mapStyles/MapStyleSwitcher";
import UnitBadge from "./components/UnitBadge";
import { useLocalPoints } from "./feature/localPoints/useLocalPoints";
import { useThreats } from "./feature/threats/useThreats";
import ThreatDialog from "./feature/threats/ThreatDialog";
import ThreatExportModal from "./feature/threats/ThreatExportModal";

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

  const {
    importedRoutes,
    importMsnxFile,
    updatePointPosition,
    insertPoint,
    removeRoute,
    clearRoutes,
    exportFile,
    serializeFile,
    toggleRouteVisibility,
    // Imported-route plan handlers (aliased — useRouteSketch exports the same
    // names for sketched routes).
    updateRoutePlan: updateImportedRoutePlan,
    updatePointPlanOverride: updateImportedPointOverride,
    setPointClock: setImportedPointClock,
    updatePointName: updateImportedPointName,
    refreshRouteElevations: refreshImportedElevations,
    applyForecastWinds: applyImportedForecastWinds,
  } = useMsnxImport();

  const handleImportMsnx = async (file) => {
    try {
      await importMsnxFile(file);
    } catch (err) {
      alert("Error importing route: " + err.message);
    }
  };

  const [foreFlightRoute, setForeFlightRoute] = useState(null);

  const {
    isSketching,
    draftPoints,
    sketchedRoutes,
    startSketch,
    cancelSketch,
    addDraftPoint,
    finishSketch,
    designateSketchPoint,
    updateSketchPointPosition,
    insertSketchPoint,
    appendSketchPoint,
    loadSketchRoutes,
    removeSketchRoute,
    toggleSketchVisibility,
    exportSketches,
    updateRoutePlan,
    updatePointPlanOverride,
    setSketchPointClock,
    updateSketchPointName,
    refreshRouteElevations,
    applyForecastWinds,
  } = useRouteSketch();

  const localPoints = useLocalPoints();
  const {
    threats,
    editingThreat,
    beginAddThreat,
    beginEditThreat,
    cancelEdit: cancelThreatEdit,
    saveThreat,
    removeThreat,
    toggleVisibility: toggleThreatVisibility,
    moveThreat,
    importThsFile,
    exportThsFile,
    exportKmzFile,
  } = useThreats();
  const [mapCenter, setMapCenter] = useState([34.0522, -118.2437]);
  const [showThreatExport, setShowThreatExport] = useState(false);

  const {
    savedRoutes,
    isLoadingSaved,
    fetchSavedRoutes,
    saveSketch,
    saveMission,
    updateSketch,
    updateMission,
    loadSavedRoute,
    loadSavedRouteFile,
    deleteSavedRoute,
  } = useSavedRoutes();

  // Links what's on the map to its cloud save, so Save offers to overwrite
  // that entry instead of always creating a new one. Keyed by msnx fileId,
  // plus SKETCHES_KEY for the sketched-routes bundle. Set on save and on
  // load; stale fileId keys are harmless since fileIds are never reused.
  const SKETCHES_KEY = "sketches";
  const [routeSaveLinks, setRouteSaveLinks] = useState({});
  const linkRouteSave = (key, saved) =>
    setRouteSaveLinks((prev) => ({
      ...prev,
      [key]: { id: saved.id, name: saved.name },
    }));

  // Once every sketch is removed, the next save is a different bundle — it
  // shouldn't offer to overwrite the old one.
  useEffect(() => {
    if (sketchedRoutes.length === 0) {
      setRouteSaveLinks((prev) => {
        if (!prev[SKETCHES_KEY]) return prev;
        const { [SKETCHES_KEY]: _dropped, ...rest } = prev;
        return rest;
      });
    }
  }, [sketchedRoutes.length]);

  /** True when the user has a linked save and chose to overwrite it. */
  const confirmOverwrite = (link, what) =>
    Boolean(link) &&
    window.confirm(
      `Overwrite the saved ${what} "${link.name}"?\n(Cancel to save as a new entry instead)`,
    );

  const handleSaveMissionGroup = async (group) => {
    if (!user) {
      alert("Saving routes is only available to signed-in users.");
      return;
    }
    try {
      const link = routeSaveLinks[group.fileId];
      const overwrite = confirmOverwrite(link, "mission");

      let name = null;
      if (!overwrite) {
        const defaultName = group.fileName.replace(/\.msnx$/i, "");
        const input = window.prompt("Save mission as:", defaultName);
        if (input === null) return;
        name = input.trim() || defaultName;
      }

      const serialized = await serializeFile(group.fileId);
      if (!serialized) throw new Error("Mission file data is no longer loaded.");

      if (overwrite) {
        await updateMission(link.id, group.routes, serialized.blob, serialized.fileName);
        alert(`Updated "${link.name}".`);
      } else {
        const saved = await saveMission(name, group.routes, serialized.blob, serialized.fileName);
        linkRouteSave(group.fileId, saved);
        alert("Mission saved.");
      }
    } catch (err) {
      alert("Error saving mission: " + err.message);
    }
  };

  const handleSaveSketches = async () => {
    if (!user) {
      alert("Saving routes is only available to signed-in users.");
      return;
    }
    try {
      const link = routeSaveLinks[SKETCHES_KEY];
      if (confirmOverwrite(link, "routes")) {
        await updateSketch(link.id, sketchedRoutes);
        alert(`Updated "${link.name}".`);
        return;
      }

      const defaultName = "SKETCHED ROUTES";
      const input = window.prompt("Save sketched routes as:", defaultName);
      if (input === null) return;
      const saved = await saveSketch(input.trim() || defaultName, sketchedRoutes);
      linkRouteSave(SKETCHES_KEY, saved);
      alert("Routes saved.");
    } catch (err) {
      alert("Error saving routes: " + err.message);
    }
  };

  const handleLoadSavedRoute = async (entry) => {
    if (entry.kind === "mission") {
      const file = await loadSavedRouteFile(entry.id, entry.file_name);
      const { fileId } = await importMsnxFile(file);
      linkRouteSave(fileId, entry);
    } else {
      const record = await loadSavedRoute(entry.id);
      const routes = record.route_data?.routes;
      if (!routes?.length) throw new Error("This save contains no routes.");
      loadSketchRoutes(routes);
      linkRouteSave(SKETCHES_KEY, entry);
    }
  };

  const handleDeleteSavedRoute = async (id) => {
    await deleteSavedRoute(id);
    setRouteSaveLinks((prev) =>
      Object.fromEntries(Object.entries(prev).filter(([, link]) => link.id !== id)),
    );
  };

  const toggleRouteSketch = () => {
    if (!isSketching) {
      startSketch();
      return;
    }
    if (draftPoints.length < 2) {
      cancelSketch();
      return;
    }
    const defaultName = `ROUTE ${sketchedRoutes.length + 1}`;
    const name = window.prompt("Route name:", defaultName);
    if (name === null) return; // keep sketching
    finishSketch(name.trim().toUpperCase() || defaultName);
  };

  const handleInsertPointContextMenu = (routeId, lat, lon, x, y) => {
    setContextMenu({ x, y, type: "route-line", routeId, lat, lon });
  };

  // Threats export to a companion .ths downloaded alongside the .msnx (AMPS
  // reads the two as a mission + its threat overlay). Threats are never saved.
  const maybeExportThreats = async (baseName) => {
    if (threats.length === 0) return;
    try {
      // Companion file travels with the mission, e.g. "GOAT SUCKER_threats.ths".
      await exportThsFile(`${(baseName || "mission").replace(/\.msnx$/i, "")}_threats`);
    } catch (err) {
      alert("The mission exported, but the threats (.ths) export failed: " + err.message);
    }
  };

  const handleExportSketchesWithThreats = async () => {
    await exportSketches();
    await maybeExportThreats(sketchedRoutes.map((r) => r.name).join("_") || "mission");
  };

  const handleExportMissionFileWithThreats = async (fileId) => {
    await exportFile(fileId);
    const group = importedRoutes.find((r) => r.fileId === fileId);
    await maybeExportThreats((group?.fileName || "mission").replace(/\.msnx$/i, ""));
  };

  const handleAddThreatHere = () => {
    beginAddThreat(contextMenu.lat, contextMenu.lon);
    setContextMenu(null);
  };

  const handleSketchPointContextMenu = (routeId, pointId, x, y) => {
    setContextMenu({ x, y, type: "sketch-point", routeId, pointId });
  };

  // Right-click while drawing a route: add a designated point right there.
  const handleDraftPointContextMenu = (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "draft-point", lat, lon });
  };

  // "+" on a local point's popup: snap the active route's line to that point.
  // While drawing, it extends the draft; otherwise it appends to the most
  // recent sketched route.
  const handleAddLocalPointToRoute = (localPoint) => {
    const name = (localPoint.name || "POINT").toUpperCase();
    if (isSketching) {
      addDraftPoint(localPoint.lat, localPoint.lon, { ptType: "turn", name });
    } else if (sketchedRoutes.length > 0) {
      const target = sketchedRoutes[sketchedRoutes.length - 1];
      appendSketchPoint(target.id, localPoint.lat, localPoint.lon, { name, ptType: "turn" });
    } else {
      alert(
        'Start a route first (Route button), then use "+" on a local point to snap the line to it.',
      );
    }
  };

  const handleAddDesignatedDraftPoint = (ptType) => {
    const { lat, lon } = contextMenu;
    const defaults = { target: ".LZ", ip: ".RP", turn: ".CP" };
    const name = window.prompt("Point name:", defaults[ptType] || ".CP");
    if (name === null) return;
    addDraftPoint(lat, lon, {
      ptType,
      name: name.trim().toUpperCase() || defaults[ptType],
    });
    setContextMenu(null);
  };

  const findSketchPoint = (routeId, pointId) =>
    sketchedRoutes
      .find((r) => r.id === routeId)
      ?.points.find((p) => p.id === pointId);

  const handleDesignatePoint = (kind, ptType) => {
    const { routeId, pointId } = contextMenu;
    if (kind === "amps") {
      const current = findSketchPoint(routeId, pointId);
      const defaultName =
        current?.name || (ptType === "target" ? ".LZ" : ptType === "ip" ? ".RP" : ".CP");
      const name = window.prompt("Point name:", defaultName);
      if (name === null) return;
      designateSketchPoint(routeId, pointId, {
        kind: "amps",
        ptType,
        name: name.trim().toUpperCase() || defaultName,
      });
    } else {
      designateSketchPoint(routeId, pointId, { kind: "shaping" });
    }
    setContextMenu(null);
  };

  const handleRenameSketchPoint = () => {
    const { routeId, pointId } = contextMenu;
    const current = findSketchPoint(routeId, pointId);
    const name = window.prompt("Point name:", current?.name || ".CP");
    if (name === null) return;
    designateSketchPoint(routeId, pointId, {
      kind: "amps",
      ptType: current?.ptType ?? "turn",
      name: name.trim().toUpperCase() || current?.name,
    });
    setContextMenu(null);
  };

  const handleInsertPointConfirm = () => {
    try {
      if (contextMenu.routeId.startsWith("sketch-")) {
        insertSketchPoint(contextMenu.routeId, contextMenu.lat, contextMenu.lon);
      } else {
        insertPoint(contextMenu.routeId, contextMenu.lat, contextMenu.lon);
      }
    } catch (err) {
      alert("Error inserting point: " + err.message);
    }
    setContextMenu(null);
  };

  const handleOpenHistory = () => {
    if (!user) {
      alert("Saving and loading maps and routes is only available to signed-in users.");
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
          {/* On mobile the account + save controls live here (hidden on
              desktop, where they float over the map instead). */}
          <div className="mobile-account-row">
            <button
              className={`floating-save-btn ${user ? "" : "disabled"}`}
              onClick={handleOpenHistory}
              title={
                user
                  ? "Save / load maps & routes"
                  : "Saving and loading is only available to signed-in users"
              }
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                width="22"
                height="22"
              >
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
                <polyline points="17 21 17 13 7 13 7 21" />
                <polyline points="7 3 7 8 15 8" />
              </svg>
            </button>
            <UserMenu />
          </div>
          <button
            className="close-menu-btn mobile-only"
            onClick={() => setIsMobileMenuOpen(false)}
          >
            ✕
          </button>
        </div>
        <Controls
          onImportMsnx={handleImportMsnx}
          isSketching={isSketching}
          toggleRouteSketch={toggleRouteSketch}
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
          performTerrainAnalysis={performTerrainAnalysis}
          setActiveNotams={setActiveNotams}
          winds={winds}
          loadingWeather={loadingWeather}
        />
      </div>
      <div className="map-area">
        <UnitBadge />
        <div className="floating-topright">
          <button
            className={`floating-save-btn ${user ? "" : "disabled"}`}
            onClick={handleOpenHistory}
            title={
              user
                ? "Save / load maps & routes"
                : "Saving and loading maps and routes is only available to signed-in users"
            }
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              width="22"
              height="22"
            >
              <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
              <polyline points="17 21 17 13 7 13 7 21" />
              <polyline points="7 3 7 8 15 8" />
            </svg>
          </button>
          <UserMenu />
        </div>
        <RoutesPanel
          routes={importedRoutes}
          removeRoute={removeRoute}
          clearRoutes={clearRoutes}
          exportFile={handleExportMissionFileWithThreats}
          toggleVisibility={toggleRouteVisibility}
          sketchedRoutes={sketchedRoutes}
          removeSketchRoute={removeSketchRoute}
          toggleSketchVisibility={toggleSketchVisibility}
          exportSketches={handleExportSketchesWithThreats}
          threats={{
            threats,
            onImportThs: importThsFile,
            onAddThreat: () => beginAddThreat(mapCenter[0], mapCenter[1]),
            onEdit: beginEditThreat,
            onRemove: removeThreat,
            onToggleVisibility: toggleThreatVisibility,
            onExportKmz: () => setShowThreatExport(true),
          }}
          onForeFlight={setForeFlightRoute}
          onSaveMissionGroup={handleSaveMissionGroup}
          onSaveSketches={handleSaveSketches}
          localPointNames={localPoints.pointSets.flatMap((set) =>
            set.points.map((p) => ({ name: p.name, lat: p.lat, lon: p.lon })),
          )}
          sketchedPlan={{
            updateRoutePlan,
            updatePointPlanOverride,
            setPointClock: setSketchPointClock,
            updatePointName: updateSketchPointName,
            refreshRouteElevations,
            applyForecastWinds,
          }}
          importedPlan={{
            updateRoutePlan: updateImportedRoutePlan,
            updatePointPlanOverride: updateImportedPointOverride,
            setPointClock: setImportedPointClock,
            updatePointName: updateImportedPointName,
            refreshRouteElevations: refreshImportedElevations,
            applyForecastWinds: applyImportedForecastWinds,
          }}
        />
        <MapStyleSwitcher mapStyle={mapStyle} setMapStyle={setMapStyle} />
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
          isSketching={isSketching}
          toggleRouteSketch={toggleRouteSketch}
        />
        <MapView
          importedRoutes={importedRoutes}
          onUpdateMsnxPointPosition={updatePointPosition}
          onInsertMsnxPoint={handleInsertPointContextMenu}
          sketchedRoutes={sketchedRoutes}
          onUpdateSketchPointPosition={updateSketchPointPosition}
          onSketchPointContextMenu={handleSketchPointContextMenu}
          isSketchingRoute={isSketching}
          addDraftPoint={addDraftPoint}
          onDraftPointContextMenu={handleDraftPointContextMenu}
          draftPoints={draftPoints}
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
          localPointSets={localPoints.pointSets}
          onAddLocalPointToRoute={handleAddLocalPointToRoute}
          threats={threats}
          onThreatMove={moveThreat}
          onThreatEdit={beginEditThreat}
          onMapMove={setMapCenter}
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
            // Clamp to the viewport so the menu never spills off a screen edge
            // (especially on phones, where a tap near the right/bottom would
            // otherwise push it out of view).
            left: Math.max(8, Math.min(contextMenu.x, window.innerWidth - 178)),
            top: Math.max(8, Math.min(contextMenu.y, window.innerHeight - 340)),
            zIndex: 99999,
            background: "#1e293b",
            border: "1px solid #334155",
            color: "white",
            padding: "8px",
            borderRadius: "8px",
            boxShadow: "0 4px 6px rgba(0,0,0,0.5)",
            minWidth: "150px",
            maxWidth: "calc(100vw - 16px)",
            maxHeight: "80vh",
            overflowY: "auto",
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
              <button
                onClick={handleAddThreatHere}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#ef4444",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Add Threat Here
              </button>
            </div>
          ) : contextMenu.type === "lz" ? (
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
          ) : contextMenu.type === "draft-point" ? (
            // --- DRAW-MODE POINT DESIGNATION MENU ---
            <div
              style={{ display: "flex", flexDirection: "column", gap: "6px" }}
            >
              <div
                style={{
                  padding: "2px 4px",
                  fontSize: "12px",
                  color: "#94a3b8",
                  textAlign: "center",
                }}
              >
                Add point here as:
              </div>
              {[
                { label: "● Checkpoint (Turn)", ptType: "turn" },
                { label: "■ RP / IP", ptType: "ip" },
                { label: "▲ LZ / PZ (Target)", ptType: "target" },
              ].map((opt) => (
                <button
                  key={opt.label}
                  onClick={() => handleAddDesignatedDraftPoint(opt.ptType)}
                  style={{
                    width: "100%",
                    padding: "6px",
                    background: "#3b82f6",
                    color: "white",
                    border: "none",
                    borderRadius: "4px",
                    cursor: "pointer",
                    textAlign: "left",
                  }}
                >
                  {opt.label}
                </button>
              ))}
              <button
                onClick={() => {
                  addDraftPoint(contextMenu.lat, contextMenu.lon);
                  setContextMenu(null);
                }}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#475569",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                  textAlign: "left",
                }}
              >
                · Shaping point
              </button>
              <button
                onClick={() => setContextMenu(null)}
                style={{
                  width: "100%",
                  padding: "4px",
                  color: "#9ca3af",
                  background: "none",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Cancel
              </button>
            </div>
          ) : contextMenu.type === "route-line" ? (
            // --- ROUTE LINE CONTEXT MENU ---
            <div
              style={{ display: "flex", flexDirection: "column", gap: "8px" }}
            >
              <button
                onClick={handleInsertPointConfirm}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#10b981",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Insert Point Here
              </button>
              <button
                onClick={() => setContextMenu(null)}
                style={{
                  width: "100%",
                  padding: "4px",
                  opacity: 1,
                  color: "#9ca3af",
                  background: "none",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Cancel
              </button>
            </div>
          ) : (
            // --- SKETCHED POINT DESIGNATION MENU ---
            <div
              style={{ display: "flex", flexDirection: "column", gap: "6px" }}
            >
              <div
                style={{
                  padding: "2px 4px",
                  fontSize: "12px",
                  color: "#94a3b8",
                  textAlign: "center",
                }}
              >
                Designate point as:
              </div>
              {[
                { label: "● Checkpoint (Turn)", kind: "amps", ptType: "turn" },
                { label: "■ RP / IP", kind: "amps", ptType: "ip" },
                { label: "▲ LZ / PZ (Target)", kind: "amps", ptType: "target" },
                { label: "· Shaping point", kind: "shaping", ptType: null },
              ].map((opt) => (
                <button
                  key={opt.label}
                  onClick={() => handleDesignatePoint(opt.kind, opt.ptType)}
                  style={{
                    width: "100%",
                    padding: "6px",
                    background: opt.kind === "amps" ? "#3b82f6" : "#475569",
                    color: "white",
                    border: "none",
                    borderRadius: "4px",
                    cursor: "pointer",
                    textAlign: "left",
                  }}
                >
                  {opt.label}
                </button>
              ))}
              <button
                onClick={handleRenameSketchPoint}
                style={{
                  width: "100%",
                  padding: "6px",
                  background: "#10b981",
                  color: "white",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Rename
              </button>
              <button
                onClick={() => setContextMenu(null)}
                style={{
                  width: "100%",
                  padding: "4px",
                  color: "#9ca3af",
                  background: "none",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                Cancel
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

      <ForeFlightModal
        route={foreFlightRoute}
        onClose={() => setForeFlightRoute(null)}
      />

      {editingThreat && (
        <ThreatDialog
          editing={editingThreat}
          onSave={saveThreat}
          onCancel={cancelThreatEdit}
        />
      )}

      {showThreatExport && (
        <ThreatExportModal
          threats={threats}
          onDownload={() => exportKmzFile("threats")}
          onDownloadThs={() => exportThsFile("threats")}
          onClose={() => setShowThreatExport(false)}
        />
      )}

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
        savedRoutes={savedRoutes}
        isLoadingSaved={isLoadingSaved}
        fetchSavedRoutes={fetchSavedRoutes}
        onLoadRoute={handleLoadSavedRoute}
        onDeleteRoute={handleDeleteSavedRoute}
        localPoints={localPoints}
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
