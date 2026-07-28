import React, { useState, useEffect, useCallback, useRef } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { convertToLatLongString } from "./utils/Helpers";
import ExportModal from "./feature/export/ExportModal";
import MobileQuickAccess from "./components/MobileQuickAccess";
import MobileGridInput from "./components/MobileGridInput";
import SidebarCollapseToggle from "./components/SidebarCollapseToggle";
import { useIsMobile } from "./feature/auth/useIsMobile";
import api from "./feature/auth/api";
import {
  createDefaultDoghouses,
  useDoghouses,
} from "./feature/doghouses/useDoghouses";
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
import { useAircraftProfiles } from "./feature/aircraft/useAircraftProfiles";
import AircraftProfileModal from "./feature/aircraft/AircraftProfileModal";
import { matchProfileToAircraft } from "./feature/aircraft/aircraftProfiles";
import { parseCoordinate } from "./utils/coordParse";
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
import UnitBuilder from "./feature/unit/UnitBuilder";
import { useLzWorkspace } from "./feature/lzWorkspace/useLzWorkspace";
import ActiveLzWindow from "./feature/lzWorkspace/ActiveLzWindow";
import LzDiagramRemoveDialog from "./feature/lzWorkspace/LzDiagramRemoveDialog";

const resolveStateUpdate = (nextValue, currentValue) =>
  typeof nextValue === "function" ? nextValue(currentValue) : nextValue;

const getSavedMapId = (result) => result?.id ?? result?.data?.id ?? null;

// The desktop control panel toggles between its full width and a narrow icon
// rail. The rail is wide enough for the tool icons; the MGRS Target input pops
// out on demand while collapsed so it's still typeable.
const SIDEBAR_RAIL = 76;
const SIDEBAR_DEFAULT = 320;
const SIDEBAR_COLLAPSED_KEY = "avtac.sidebarCollapsed";

const EMPTY_LZ_GRAPHICS = Object.freeze({
  doghouses: Object.freeze([]),
  helicopters: Object.freeze([]),
  pzMarkers: Object.freeze([]),
  sectorsOfFire: Object.freeze([]),
  goArounds: Object.freeze([]),
  units: Object.freeze([]),
  measurements: Object.freeze([]),
  exportBox: null,
});

function App() {
  const [contextMenu, setContextMenu] = useState(null);
  const [loading, setLoading] = useState(false);
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [gridInput, setGridInput] = useState("16S GC 28864 55349");
  const [isDrawingLZ, setIsDrawingLZ] = useState(false);
  const [drawingPoints, setDrawingPoints] = useState([]);
  const [clickedGrid, setClickedGrid] = useState("Loading...");
  const [mapStyle, setMapStyle] = useState("satellite");

  // Resizable/collapsible desktop control panel. Width persists across sessions;
  // below the compact threshold the panel renders as an icon rail. On mobile the
  // sidebar is a full-screen overlay, so width/compact don't apply there.
  const isMobile = useIsMobile();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1",
  );
  useEffect(() => {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed ? "1" : "0");
  }, [sidebarCollapsed]);
  const sidebarCompact = !isMobile && sidebarCollapsed;
  const sidebarWidth = sidebarCollapsed ? SIDEBAR_RAIL : SIDEBAR_DEFAULT;

  const lzWorkspace = useLzWorkspace();
  const {
    activeDiagram,
    activeDiagramId,
    diagrams,
    hasActiveTarget,
    canAnalyze,
    canEditGraphics,
    startDiagram,
    setActiveDiagram,
    setAnalysisDraft,
    setRuntimeTerrainData,
    completeAnalysis,
    resetAnalysis,
    setGraphicCollection,
    setGraphics,
    setFlightData: setDiagramFlightData,
    setView,
    markSaved,
    clearSaved,
    removeDiagram,
    serializeActiveDiagram,
    importLegacySnapshot,
  } = lzWorkspace;
  const workspaceRef = useRef(lzWorkspace.workspace);
  const pendingSaveDiagramIdRef = useRef(null);

  useEffect(() => {
    workspaceRef.current = lzWorkspace.workspace;
  }, [lzWorkspace.workspace]);

  const targetLocation = activeDiagram?.target
    ? [activeDiagram.target.lat, activeDiagram.target.lon]
    : null;
  const detectedLZ = activeDiagram?.analysis?.detectedLZ ?? null;
  const customLZ = activeDiagram?.analysis?.customLZ ?? null;
  const terrainData = activeDiagram?.analysis?.terrainData ?? null;
  const gridElevation = activeDiagram?.analysis?.gridElevation ?? "";
  const latLong = activeDiagram?.analysis?.latLong ?? "";
  const flightData = activeDiagram?.flightData ?? {};
  const mapData = activeDiagram?.mapData ?? { mgrs: gridInput };
  const showHeatmap = activeDiagram?.view?.showHeatmap ?? false;
  const showLZOutline = activeDiagram?.view?.showLZOutline ?? true;
  const diagramStatus = activeDiagram?.status ?? "no_target";
  const diagramReadinessText = !hasActiveTarget
    ? "Set a target on the map to initialize an LZ/PZ diagram."
    : !canEditGraphics
      ? "Target set. Analyze the LZ to unlock planning graphics, export, and save." : "";

  const updateActiveAnalysis = useCallback(
    (key, nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram?.target) return;
      setAnalysisDraft(
        {
          [key]: resolveStateUpdate(nextValue, diagram.analysis?.[key]),
        },
        diagram.id,
      );
    },
    [setAnalysisDraft],
  );

  const setCustomLZ = useCallback(
    (nextValue) => updateActiveAnalysis("customLZ", nextValue),
    [updateActiveAnalysis],
  );
  const setDetectedLZ = useCallback(
    (nextValue) => updateActiveAnalysis("detectedLZ", nextValue),
    [updateActiveAnalysis],
  );
  const setGridElevation = useCallback(
    (nextValue) => updateActiveAnalysis("gridElevation", nextValue),
    [updateActiveAnalysis],
  );
  const setLatLong = useCallback(
    (nextValue) => updateActiveAnalysis("latLong", nextValue),
    [updateActiveAnalysis],
  );
  const setFlightData = useCallback(
    (nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram) return;
      setDiagramFlightData(
        resolveStateUpdate(nextValue, diagram.flightData ?? {}),
        diagram.id,
      );
    },
    [setDiagramFlightData],
  );

  const setActiveGraphicCollection = useCallback(
    (collection, nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram) return;
      setGraphicCollection(
        collection,
        resolveStateUpdate(nextValue, diagram.graphics?.[collection] ?? []),
        diagram.id,
      );
    },
    [setGraphicCollection],
  );

  const setDoghouses = useCallback(
    (nextValue) => setActiveGraphicCollection("doghouses", nextValue),
    [setActiveGraphicCollection],
  );
  const setHelicopters = useCallback(
    (nextValue) => setActiveGraphicCollection("helicopters", nextValue),
    [setActiveGraphicCollection],
  );
  const setPzMarkers = useCallback(
    (nextValue) => setActiveGraphicCollection("pzMarkers", nextValue),
    [setActiveGraphicCollection],
  );
  const setSectors = useCallback(
    (nextValue) => setActiveGraphicCollection("sectorsOfFire", nextValue),
    [setActiveGraphicCollection],
  );
  const setGoAround = useCallback(
    (nextValue) => setActiveGraphicCollection("goArounds", nextValue),
    [setActiveGraphicCollection],
  );
  const setUnits = useCallback(
    (nextValue) => setActiveGraphicCollection("units", nextValue),
    [setActiveGraphicCollection],
  );
  const setExportBox = useCallback(
    (nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram) return;
      setGraphics(
        {
          exportBox: resolveStateUpdate(
            nextValue,
            diagram.graphics?.exportBox ?? null,
          ),
        },
        diagram.id,
      );
    },
    [setGraphics],
  );
  const setShowHeatmap = useCallback(
    (nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram) return;
      setView(
        {
          showHeatmap: resolveStateUpdate(
            nextValue,
            diagram.view?.showHeatmap ?? false,
          ),
        },
        diagram.id,
      );
    },
    [setView],
  );
  const setShowLZOutline = useCallback(
    (nextValue) => {
      const diagram = workspaceRef.current?.diagramsById?.[
        workspaceRef.current?.activeDiagramId
      ];
      if (!diagram) return;
      setView(
        {
          showLZOutline: resolveStateUpdate(
            nextValue,
            diagram.view?.showLZOutline ?? true,
          ),
        },
        diagram.id,
      );
    },
    [setView],
  );

  const activeGraphics = activeDiagram?.graphics ?? EMPTY_LZ_GRAPHICS;
  const { goAround, addGoAround, updateGoAround, deleteGoAround } =
    useGoAround(targetLocation, {
      goAround: activeGraphics.goArounds ?? [],
      setGoAround,
    });
  const {
    sectorsOfFire,
    addSectorOfFire,
    updateSectorOfFirePoint,
    moveSectorOfFire,
    deleteSectorOfFire,
  } = useSectorsOfFire(targetLocation, {
    sectorsOfFire: activeGraphics.sectorsOfFire ?? [],
    setSectors,
  });
  const { units, addUnit, updateUnit, updateUnitPosition, deleteUnit } =
    useUnit(targetLocation, {
      units: activeGraphics.units ?? [],
      setUnits,
    });
  const [editingUnit, setEditingUnit] = useState(null);
  const { pzMarker, addPZMarker, updatePZMarker, deletePZMarker } =
    usePzMarker(targetLocation, {
      pzMarker: activeGraphics.pzMarkers ?? [],
      setPzMarkers,
    });
  const { winds, activeNotams, setActiveNotams, loadingWeather, fetchWeather } =
    useWeather();
  // Aircraft profiles drive map icons, separation alerts, LZ capacity, and
  // route-planning defaults. Declared before the consumers below.
  const {
    profiles: aircraftProfiles,
    masterProfiles,
    customProfiles,
    activeProfile,
    selectProfile,
    createProfile,
    updateProfile,
    deleteProfile,
  } = useAircraftProfiles();
  const [isAircraftModalOpen, setIsAircraftModalOpen] = useState(false);
  const { doghouses, updateDoghouse } = useDoghouses(targetLocation, setFlightData, {
    doghouses: activeGraphics.doghouses ?? [],
    setDoghouses,
  });
  const {
    helicopters,
    addHelo,
    updateHelicopter,
    deleteHelicopter,
    proximityAlerts,
  } = useHelicopters(targetLocation, {
    helicopters: activeGraphics.helicopters ?? [],
    setHelicopters,
    profiles: aircraftProfiles,
    activeProfile,
  });
  const {
    exportBox,
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
  } = useExport(targetLocation, {
    exportBox: activeGraphics.exportBox ?? null,
    setExportBox,
  });

  const handleTerrainData = useCallback(
    (nextTerrainData, diagramId) => {
      const diagram = workspaceRef.current?.diagramsById?.[diagramId];
      if (!diagram?.target) return;
      setRuntimeTerrainData(nextTerrainData, diagramId);
    },
    [setRuntimeTerrainData],
  );

  const handleAnalysisComplete = useCallback(
    (analysis, diagramId) => {
      const diagram = workspaceRef.current?.diagramsById?.[diagramId];
      if (!diagram?.target) return;

      completeAnalysis(analysis, diagramId);

      // Defaults belong to this diagram alone and are created once, after
      // analysis has established the LZ/PZ. They are never regenerated when a
      // later target starts a separate diagram.
      if (!diagram.graphics?.doghouses?.length) {
        setGraphicCollection(
          "doghouses",
          createDefaultDoghouses(
            [diagram.target.lat, diagram.target.lon],
            diagramId,
          ),
          diagramId,
        );
      }
    },
    [completeAnalysis, setGraphicCollection],
  );
  const {
    performTerrainAnalysis,
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
    {
      analysisDiagramId: activeDiagramId,
      terrainData,
      onAnalysisComplete: handleAnalysisComplete,
      onTerrainData: handleTerrainData,
    },
  );

  const { user } = useAuth();
  // Per-user feature entitlements from /auth/me. A missing map or key defaults
  // to enabled, so nothing is hidden while loading or for unrestricted users.
  const uf = user?.features || null;
  const feat = {
    lz_pz_tools: !uf || uf.lz_pz_tools !== false,
    routes: !uf || uf.routes !== false,
    msnx_import: !uf || uf.msnx_import !== false,
    threats: !uf || uf.threats !== false,
    cloud_save: !uf || uf.cloud_save !== false,
    exports: !uf || uf.exports !== false,
    aircraft_profiles: !uf || uf.aircraft_profiles !== false,
  };
  const { history, isLoadingHistory, fetchHistory, saveMap, loadMap, updateMap, deleteMap } =
    useSavedMaps();
  const [isHistoryModalOpen, setIsHistoryModalOpen] = useState(false);
  const [isLayerSaveInProgress, setIsLayerSaveInProgress] = useState(false);
  const [removeConfirmationDiagramId, setRemoveConfirmationDiagramId] = useState(null);
  const [pendingRemovalDiagramId, setPendingRemovalDiagramId] = useState(null);

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
      const result = await importMsnxFile(file);
      // Follow the airframe the mission was actually planned for, so icons,
      // separation, and plan defaults match the file rather than whatever was
      // selected before. Unrecognised airframes leave the selection alone.
      const matched = matchProfileToAircraft(aircraftProfiles, result?.aircraft);
      if (matched) selectProfile(matched.slug);
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
  } = useRouteSketch({ aircraftProfile: activeProfile });

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
  const [showUnitBuilder, setShowUnitBuilder] = useState(false);

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

  // Stable identities: these are handed to the memoized route layers, which
  // render a marker per route point (hundreds, for a real AMPS mission).
  const handleInsertPointContextMenu = useCallback((routeId, lat, lon, x, y) => {
    setContextMenu({ x, y, type: "route-line", routeId, lat, lon });
  }, []);

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

  const handleSketchPointContextMenu = useCallback((routeId, pointId, x, y) => {
    setContextMenu({ x, y, type: "sketch-point", routeId, pointId });
  }, []);

  // Right-click while drawing a route: add a designated point right there.
  const handleDraftPointContextMenu = (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "draft-point", lat, lon });
  };

  // "+" on a local point's popup: snap the active route's line to that point.
  // While drawing, it extends the draft; otherwise it appends to the most
  // recent sketched route.
  const handleAddLocalPointToRoute = (localPoint) => {
    const name = (localPoint.name || "POINT").toUpperCase();
    // Carry the local point's charted elevation so the route uses it instead of
    // the DEM at that point.
    const chartElevationFt =
      typeof localPoint.elevationFt === "number" ? localPoint.elevationFt : undefined;
    if (isSketching) {
      addDraftPoint(localPoint.lat, localPoint.lon, { ptType: "turn", name, chartElevationFt });
    } else if (sketchedRoutes.length > 0) {
      const target = sketchedRoutes[sketchedRoutes.length - 1];
      appendSketchPoint(target.id, localPoint.lat, localPoint.lon, {
        name,
        ptType: "turn",
        chartElevationFt,
      });
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

  const updateSavedActiveDiagram = useCallback(
    async ({ removeAfterSave = false } = {}) => {
      const diagramId = activeDiagramId;
      const diagram = workspaceRef.current?.diagramsById?.[diagramId];
      if (!diagram?.savedId) return false;

      setIsLayerSaveInProgress(true);
      try {
        const result = await updateMap(diagram.savedId, serializeActiveDiagram());
        await fetchHistory();
        markSaved(
          {
            savedId: getSavedMapId(result) ?? diagram.savedId,
            name: diagram.name,
          },
          diagram.id,
        );
        if (removeAfterSave) removeDiagram(diagram.id);
        return true;
      } catch (err) {
        alert("Error saving LZ/PZ diagram: " + err.message);
        return false;
      } finally {
        setIsLayerSaveInProgress(false);
      }
    },
    [
      activeDiagramId,
      fetchHistory,
      markSaved,
      removeDiagram,
      serializeActiveDiagram,
      updateMap,
    ],
  );

  const handleLayerSave = useCallback(() => {
    if (!activeDiagram) return;
    if (!user) {
      alert("Saving and loading maps and routes is only available to signed-in users.");
      return;
    }
    if (!activeDiagram.savedId) {
      setIsHistoryModalOpen(true);
      return;
    }
    if (!activeDiagram.dirty) return;
    void updateSavedActiveDiagram();
  }, [activeDiagram, updateSavedActiveDiagram, user]);

  const handleLayerRemove = useCallback(() => {
    if (!activeDiagram) return;

    if (!activeDiagram.savedId || activeDiagram.dirty) {
      setRemoveConfirmationDiagramId(activeDiagram.id);
      return;
    }

    removeDiagram(activeDiagram.id);
  }, [activeDiagram, removeDiagram]);

  const pendingRemovalDiagram = diagrams.find(
    (diagram) => diagram.id === removeConfirmationDiagramId,
  );

  const handleDiscardDiagram = useCallback(() => {
    if (!removeConfirmationDiagramId) return;
    removeDiagram(removeConfirmationDiagramId);
    setRemoveConfirmationDiagramId(null);
  }, [removeConfirmationDiagramId, removeDiagram]);

  const handleSaveFirstAndRemove = useCallback(() => {
    const diagram = workspaceRef.current?.diagramsById?.[removeConfirmationDiagramId];
    if (!diagram) {
      setRemoveConfirmationDiagramId(null);
      return;
    }
    if (!user) {
      alert("Saving and loading maps and routes is only available to signed-in users.");
      return;
    }

    setRemoveConfirmationDiagramId(null);
    if (diagram.savedId) {
      void updateSavedActiveDiagram({ removeAfterSave: true });
      return;
    }

    setPendingRemovalDiagramId(diagram.id);
    setIsHistoryModalOpen(true);
  }, [removeConfirmationDiagramId, updateSavedActiveDiagram, user]);

  const startDiagramAtTarget = useCallback(
    (target, mgrs) => {
      const normalizedMgrs = (mgrs || "").trim();
      const diagramId = startDiagram(target, {
        mgrs: normalizedMgrs,
        mapData: { mgrs: normalizedMgrs },
      });
      if (!diagramId) return null;

      // A new target deliberately starts a new active diagram. Existing work
      // remains in the workspace, untouched, until the user switches back or
      // saves it. Nothing is copied or regenerated from the previous target.
      setGridInput(normalizedMgrs || gridInput);
      setDrawingPoints([]);
      setIsDrawingLZ(false);
      setEditingUnit(null);
      setContextMenu(null);
      return diagramId;
    },
    [gridInput, startDiagram],
  );

  const handleSelectDiagram = useCallback(
    (diagramId) => {
      if (!diagramId) return;
      const nextDiagram = workspaceRef.current?.diagramsById?.[diagramId];
      setActiveDiagram(diagramId);
      if (nextDiagram?.target?.mgrs) setGridInput(nextDiagram.target.mgrs);
      setDrawingPoints([]);
      setIsDrawingLZ(false);
      setEditingUnit(null);
      setContextMenu(null);
    },
    [setActiveDiagram],
  );

  const serializeMapState = () => {
    pendingSaveDiagramIdRef.current = activeDiagramId;
    return serializeActiveDiagram();
  };

  const applyMapState = useCallback(
    (snapshot, historyEntry) => {
      if (!snapshot) return;

      const savedId = historyEntry?.id ?? snapshot?.savedId ?? null;
      const name = historyEntry?.name ?? snapshot?.name ?? "";
      const loadedId = importLegacySnapshot(snapshot, { savedId, name });
      const loadedMgrs =
        snapshot?.target?.mgrs ??
        snapshot?.mapData?.mgrs ??
        snapshot?.gridInput ??
        "";
      if (loadedMgrs) setGridInput(loadedMgrs);
      if (snapshot?.mapStyle) setMapStyle(snapshot.mapStyle);
      setDrawingPoints([]);
      setIsDrawingLZ(false);
      setEditingUnit(null);
      setContextMenu(null);
      return loadedId;
    },
    [importLegacySnapshot],
  );

  const handleMapRightClick = async (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "map", lat, lon });
    setClickedGrid("Calculating...");
    try {
      const res = await api.post("/convert-to-mgrs", {
        lat,
        lon,
      });
      setClickedGrid(res.data.mgrs);
    } catch (err) {
      setClickedGrid(convertToLatLongString(lat, lon)); // Fallback to Lat/Lon if backend fails
    }
  };

  const handleSetAsTarget = () => {
    if (clickedGrid === "Calculating..." || !contextMenu) return;
    startDiagramAtTarget([contextMenu.lat, contextMenu.lon], clickedGrid);
  };

  // 2. LZ Right-Click Handler
  const handleLZRightClick = async (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "lz", lat, lon });
    setClickedGrid("Calculating...");
    try {
      const res = await api.post("/convert-to-mgrs", {
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
    if (!hasActiveTarget) {
      alert("Set a target on the map before drawing an LZ/PZ boundary.");
      return;
    }

    if (isDrawingLZ) {
      // Finish drawing
      if (drawingPoints.length > 2) {
        setCustomLZ(drawingPoints);
      }
      setIsDrawingLZ(false);
      setDrawingPoints([]);
    } else {
      // Start drawing
      // Changing an already analyzed boundary makes the existing analysis
      // stale. Keep its graphics, but require analysis again before edits.
      if (activeDiagram?.status === "analyzed") {
        resetAnalysis(activeDiagram.id);
      }
      setCustomLZ(null);
      setDrawingPoints([]);
      setIsDrawingLZ(true);
    }
  };

  const handleSearch = async () => {
    setLoading(true);
    try {
      // The target field takes a grid or a pasted lat/long in any common
      // notation. A coordinate already gives us the position, so it only needs
      // converting to MGRS for display; a grid needs the conversion the other
      // way. parseCoordinate refuses anything that is a valid grid, so an MGRS
      // entry can never be diverted down the coordinate path.
      const coordinate = parseCoordinate(gridInput);
      if (coordinate) {
        const { lat, lon } = coordinate;
        const res = await api.post("/convert-to-mgrs", { lat, lon });
        const mgrs = res.data?.mgrs;
        if (!mgrs) throw new Error("The server didn't return a grid for that position.");
        // startDiagramAtTarget rewrites the field with the grid, so the user
        // sees their coordinate resolve into the MGRS the rest of the app uses.
        startDiagramAtTarget([lat, lon], mgrs);
        return;
      }

      const res = await api.post("/convert-grid", {
        grid: gridInput,
      });
      const { lat, lon } = res.data;
      startDiagramAtTarget([lat, lon], gridInput);
    } catch (err) {
      const detail = err.response?.data?.message || err.response?.data?.error || err.message;
      alert("Error finding grid: " + detail);
    } finally {
      setLoading(false);
      setIsMobileMenuOpen(false); // Closes menu if they searched from the sidebar
    }
  };

  return (
    <div className="app-container">
      <button
        className="mobile-hamburger-btn"
        onClick={() => setIsMobileMenuOpen(true)}
      >
        ☰
      </button>
      <div
        className={`sidebar ${isMobileMenuOpen ? "mobile-open" : ""} ${
          sidebarCompact ? "sidebar-compact" : ""
        }`}
      >
        <div className="sidebar-header">
          {/* On mobile the account + save controls live here (hidden on
              desktop, where they float over the map instead). */}
          <div className="mobile-account-row">
            {feat.cloud_save && (
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
            )}
            <UserMenu variant="mobile" />
          </div>
          <button
            className="close-menu-btn mobile-only"
            onClick={() => setIsMobileMenuOpen(false)}
          >
            ✕
          </button>
        </div>
        <Controls
          features={feat}
          aircraftProfiles={aircraftProfiles}
          activeAircraftProfile={activeProfile}
          onSelectAircraft={selectProfile}
          onManageAircraft={() => setIsAircraftModalOpen(true)}
          onImportMsnx={handleImportMsnx}
          isSketching={isSketching}
          toggleRouteSketch={toggleRouteSketch}
          addHelo={addHelo}
          setShowHeatmap={setShowHeatmap}
          terrainData={terrainData}
          addGoAround={addGoAround}
          targetLocation={targetLocation}
          detectedLZ={detectedLZ}
          addPZMarker={addPZMarker}
          addUnit={addUnit}
          onOpenUnitBuilder={() => setShowUnitBuilder(true)}
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
          setLatLong={setLatLong}
          setGridElevation={setGridElevation}
          mapData={{
            ...mapData,
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
          canAnalyze={canAnalyze}
          canDrawBoundary={hasActiveTarget}
          canUseDiagramTools={canEditGraphics}
          canSaveDiagram={canEditGraphics}
          diagramStatus={diagramStatus}
          diagramReadinessText={diagramReadinessText}
          compact={sidebarCompact}
        />
      </div>
      {!isMobile && (
        <SidebarCollapseToggle
          collapsed={sidebarCollapsed}
          onToggle={() => setSidebarCollapsed((v) => !v)}
          width={sidebarWidth}
        />
      )}
      <div className="map-area">
        <UnitBadge />
        <div className="floating-topright">
          {feat.cloud_save && (
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
          )}
          <UserMenu variant="desktop" />
        </div>
        <RoutesPanel
          features={feat}
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
            set.points.map((p) => ({
              name: p.name,
              lat: p.lat,
              lon: p.lon,
              elevationFt: p.elevationFt,
            })),
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
          features={feat}
          addHelo={addHelo}
          addPZMarker={addPZMarker}
          addSector={addSectorOfFire}
          addUnit={addUnit}
          onOpenUnitBuilder={() => setShowUnitBuilder(true)}
          addGoAround={addGoAround}
          enableExportMode={enableExportMode}
          onDownloadClick={() => setIsExporting(true)}
          exportBox={exportBox}
          isExporting={isExporting}
          exportProgress={exportProgress}
          isSketching={isSketching}
          toggleRouteSketch={toggleRouteSketch}
          canAnalyze={canAnalyze}
          canUseDiagramTools={canEditGraphics}
          canSaveDiagram={canEditGraphics}
          diagramStatus={diagramStatus}
          diagramReadinessText={diagramReadinessText}
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
          activeDiagramId={activeDiagramId}
          targetLocation={targetLocation}
          mapData={mapData}
          detectedLZ={detectedLZ}
          assets={helicopters}
          updateAsset={updateHelicopter}
          deleteAsset={deleteHelicopter}
          aircraftProfiles={aircraftProfiles}
          activeAircraftProfile={activeProfile}
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
          onEditUnit={setEditingUnit}
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

        <ActiveLzWindow
          diagrams={diagrams}
          activeDiagramId={activeDiagramId}
          onSelect={handleSelectDiagram}
          onSave={handleLayerSave}
          onRemove={handleLayerRemove}
          canSaveActive={canEditGraphics}
          isSaving={isLayerSaveInProgress}
          initialPosition={{ x: 16, y: 86 }}
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
          className="ctx-menu"
          style={{
            position: "fixed",
            // Clamp to the viewport so the menu never spills off a screen edge
            // (especially on phones, where a tap near the right/bottom would
            // otherwise push it out of view).
            left: Math.max(8, Math.min(contextMenu.x, window.innerWidth - 178)),
            top: Math.max(8, Math.min(contextMenu.y, window.innerHeight - 340)),
            zIndex: 99999,
          }}
        >
          {contextMenu.type === "map" ? (
            <>
              <div className="ctx-title">{clickedGrid}</div>
              <button
                className="ctx-btn ctx-btn--success"
                onClick={handleSetAsTarget}
                disabled={clickedGrid === "Calculating..."}
              >
                Set as Target
              </button>
              <button
                className="ctx-btn ctx-btn--danger"
                onClick={handleAddThreatHere}
              >
                Add Threat Here
              </button>
            </>
          ) : contextMenu.type === "lz" ? (
            // --- THE LZ CONTEXT MENU ---
            <>
              <div className="ctx-title">{clickedGrid}</div>
              <button
                className="ctx-btn ctx-btn--success"
                onClick={handleSetAsTarget}
                disabled={clickedGrid === "Calculating..."}
              >
                Set as Target
              </button>

              <hr className="ctx-divider" />

              <button
                className="ctx-btn ctx-btn--primary"
                onClick={performTerrainAnalysis}
                disabled={!canAnalyze}
                title={
                  !canAnalyze
                    ? "Set a target on the map before analyzing the LZ/PZ."
                    : ""
                }
              >
                Analyze LZ
              </button>

              <button
                className="ctx-btn ctx-btn--danger"
                onClick={() => {
                  setCustomLZ(null);
                  setDrawingPoints([]);
                  setContextMenu(null);
                }}
              >
                ✕ Delete LZ
              </button>
            </>
          ) : contextMenu.type === "draft-point" ? (
            // --- DRAW-MODE POINT DESIGNATION MENU ---
            <>
              <div className="ctx-label">Add point here as:</div>
              {[
                { label: "● Checkpoint (Turn)", ptType: "turn" },
                { label: "■ RP / IP", ptType: "ip" },
                { label: "▲ LZ / PZ (Target)", ptType: "target" },
              ].map((opt) => (
                <button
                  key={opt.label}
                  className="ctx-btn ctx-btn--primary"
                  onClick={() => handleAddDesignatedDraftPoint(opt.ptType)}
                >
                  {opt.label}
                </button>
              ))}
              <button
                className="ctx-btn"
                onClick={() => {
                  addDraftPoint(contextMenu.lat, contextMenu.lon);
                  setContextMenu(null);
                }}
              >
                · Shaping point
              </button>
              <button
                className="ctx-btn ctx-btn--ghost"
                onClick={() => setContextMenu(null)}
              >
                Cancel
              </button>
            </>
          ) : contextMenu.type === "route-line" ? (
            // --- ROUTE LINE CONTEXT MENU ---
            <>
              <button
                className="ctx-btn ctx-btn--success"
                onClick={handleInsertPointConfirm}
              >
                Insert Point Here
              </button>
              <button
                className="ctx-btn ctx-btn--ghost"
                onClick={() => setContextMenu(null)}
              >
                Cancel
              </button>
            </>
          ) : (
            // --- SKETCHED POINT DESIGNATION MENU ---
            <>
              <div className="ctx-label">Designate point as:</div>
              {[
                { label: "● Checkpoint (Turn)", kind: "amps", ptType: "turn" },
                { label: "■ RP / IP", kind: "amps", ptType: "ip" },
                { label: "▲ LZ / PZ (Target)", kind: "amps", ptType: "target" },
                { label: "· Shaping point", kind: "shaping", ptType: null },
              ].map((opt) => (
                <button
                  key={opt.label}
                  className={
                    opt.kind === "amps" ? "ctx-btn ctx-btn--primary" : "ctx-btn"
                  }
                  onClick={() => handleDesignatePoint(opt.kind, opt.ptType)}
                >
                  {opt.label}
                </button>
              ))}
              <button
                className="ctx-btn ctx-btn--success"
                onClick={handleRenameSketchPoint}
              >
                Rename
              </button>
              <button
                className="ctx-btn ctx-btn--ghost"
                onClick={() => setContextMenu(null)}
              >
                Cancel
              </button>
            </>
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

      {showUnitBuilder && (
        <UnitBuilder onSubmit={addUnit} onClose={() => setShowUnitBuilder(false)} />
      )}

      {isAircraftModalOpen && (
        <AircraftProfileModal
          masterProfiles={masterProfiles}
          customProfiles={customProfiles}
          onCreate={createProfile}
          onUpdate={updateProfile}
          onDelete={deleteProfile}
          onClose={() => setIsAircraftModalOpen(false)}
        />
      )}

      {editingUnit && (
        <UnitBuilder
          initial={editingUnit}
          onSubmit={(data) => updateUnit(editingUnit.id, data)}
          onDelete={() => deleteUnit(editingUnit.id)}
          onClose={() => setEditingUnit(null)}
        />
      )}

      <LzDiagramRemoveDialog
        diagram={pendingRemovalDiagram}
        isSaving={isLayerSaveInProgress}
        onCancel={() => setRemoveConfirmationDiagramId(null)}
        onDiscard={handleDiscardDiagram}
        onSaveFirst={handleSaveFirstAndRemove}
      />

      <HistoryModal
        isOpen={isHistoryModalOpen}
        onClose={() => {
          setIsHistoryModalOpen(false);
          setPendingRemovalDiagramId(null);
        }}
        history={history}
        isLoadingHistory={isLoadingHistory}
        fetchHistory={fetchHistory}
        saveMap={saveMap}
        loadMap={loadMap}
        updateMap={updateMap}
        deleteMap={deleteMap}
        buildSnapshot={serializeMapState}
        applySnapshot={applyMapState}
        activeMapId={activeDiagram?.savedId ?? null}
        activeName={activeDiagram?.name ?? ""}
        canSave={canEditGraphics}
        saveDisabledReason={
          !hasActiveTarget
            ? "Set a target on the map and analyze the LZ/PZ before saving."
            : "Analyze the active LZ/PZ before saving it."
        }
        onSaved={({ id, name }) => {
          const diagramId = pendingSaveDiagramIdRef.current ?? activeDiagramId;
          const diagram = workspaceRef.current?.diagramsById?.[diagramId];
          if (!diagram) return;
          markSaved(
            {
              ...(id != null ? { savedId: id } : {}),
              name,
            },
            diagram.id,
          );
          if (pendingRemovalDiagramId === diagram.id) {
            removeDiagram(diagram.id);
            setPendingRemovalDiagramId(null);
          }
          pendingSaveDiagramIdRef.current = null;
        }}
        onLoaded={applyMapState}
        onDeleted={({ id }) => {
          const deletedDiagram = Object.values(
            workspaceRef.current?.diagramsById ?? {},
          ).find((diagram) => String(diagram.savedId) === String(id));
          if (deletedDiagram) clearSaved(deletedDiagram.id);
        }}
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
