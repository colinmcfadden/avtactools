import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { convertToLatLongString } from "./utils/Helpers";
import { toMgrs } from "./utils/mgrs";
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
import { useSavedMaps } from "./feature/savedMaps/useSavedMaps";
import { useMsnxImport } from "./feature/msnxImport/useMsnxImport";
import { useAircraftProfiles } from "./feature/aircraft/useAircraftProfiles";
import AircraftProfileModal from "./feature/aircraft/AircraftProfileModal";
import { matchProfileToAircraft } from "./feature/aircraft/aircraftProfiles";
import { parseCoordinate } from "./utils/coordParse";
import { useRouteSketch } from "./feature/msnxImport/useRouteSketch";
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
import Lz3DWindow from "./feature/viewer3d/Lz3DWindow";
import { releaseAbandonedBuilds } from "./feature/viewer3d/useLidarTileset";
import { useBuildOnSave } from "./feature/viewer3d/useBuildOnSave";
import TopBar from "./feature/shell/TopBar";
import Dock, { useDock } from "./feature/shell/Dock";
import LzPanel, { diagramTitle } from "./feature/shell/LzPanel";
import RoutesDockPanel from "./feature/shell/RoutesDockPanel";
import ThreatsDockPanel from "./feature/shell/ThreatsDockPanel";
import LibraryDialog from "./feature/library/LibraryDialog";
import useLzSaves from "./feature/saveDialog/useLzSaves";
import useRouteSaves, { SKETCHES } from "./feature/saveDialog/useRouteSaves";
import NameDialog from "./feature/ui/NameDialog";
import { useToast } from "./feature/ui/Toast";
import useFilePicker from "./feature/imports/useFilePicker";
import useImports from "./feature/imports/useImports";
import ImportsPanel from "./feature/imports/ImportsPanel";
import { setSummary } from "./feature/saveDialog/useRouteSaves";
import { ConfirmDialog } from "./feature/ui/Dialog";
import { PanelHead } from "./feature/shell/Dock";
import usePackWorkspace from "./feature/missionPacks/ui/usePackWorkspace";
import PackPanel from "./feature/missionPacks/ui/PackPanel";
import WorkspaceSwitcher from "./feature/missionPacks/ui/WorkspaceSwitcher";
import { FinishedBanner } from "./feature/missionPacks/ui/PackDialogs";
import { lzDiagramFromItem, packDiagramId } from "./feature/missionPacks/packLz";
import { packLocalRef } from "./feature/missionPacks/packRef";

const resolveStateUpdate = (nextValue, currentValue) =>
  typeof nextValue === "function" ? nextValue(currentValue) : nextValue;

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
    markDirty,
    clearSaved,
    removeDiagram,
    importLegacySnapshot,
  } = lzWorkspace;
  const workspaceRef = useRef(lzWorkspace.workspace);
  const { show: toast } = useToast();
  const dock = useDock("lz");

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
  // 3D point cloud for the active LZ/PZ. Opens over the map rather than
  // replacing it, so the 2D diagram stays available alongside.
  const [is3DOpen, setIs3DOpen] = useState(false);
  // A refresh cannot tell the build service it is leaving, so the reloaded
  // page does: point-cloud builds the previous load was waiting on are
  // released rather than left to run ahead of everything opened next.
  useEffect(() => {
    releaseAbandonedBuilds();
  }, []);
  // Saved LZs get their point cloud built in the background, ready for 3D.
  useBuildOnSave(diagrams);
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

  const { user, logout } = useAuth();
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
    // Default off (entitlements.DEFAULT_OFF): only an explicit "on" shows Mission Packs.
    mission_packs: Boolean(uf && uf.mission_packs === true),
  };
  const { history, isLoadingHistory, historyError, fetchHistory, saveMap, loadMap, updateMap, deleteMap } =
    useSavedMaps();
  // The Library dialog: null when closed, else the tab it opens on.
  const [libraryTab, setLibraryTab] = useState(null);
  const openLibrary = useCallback((tab = "lz") => setLibraryTab(tab), []);
  // One name asked for at a time (a route being finished, a route point being named).
  const [nameAsk, setNameAsk] = useState(null);

  const lzSaves = useLzSaves({
    getDiagram: (id) => workspaceRef.current?.diagramsById?.[id],
    diagramTitle: (diagram) => diagramTitle(diagram, workspaceRef.current?.diagramOrder?.indexOf(diagram.id) ?? 0),
    markSaved,
    markDirty,
    removeDiagram,
    library: { history, fetchHistory, saveMap, updateMap },
    signedIn: Boolean(user),
    onOpenLibrary: () => openLibrary("lz"),
  });

  const {
    importedRoutes,
    importMsnxFile,
    updatePointPosition,
    insertPoint,
    removeRoute,
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

  const importMission = async (file) => {
    const result = await importMsnxFile(file);
    // Follow the airframe the mission was actually planned for, so icons,
    // separation, and plan defaults match the file rather than whatever was
    // selected before. Unrecognised airframes leave the selection alone.
    const matched = matchProfileToAircraft(aircraftProfiles, result?.aircraft);
    if (matched) selectProfile(matched.slug);
    return result;
  };

  const handleImportMsnx = async (file) => {
    try {
      await importMission(file);
      dock.show("routes");
    } catch (err) {
      toast({ tone: "error", message: `${file.name} could not be imported: ${err.message}` });
    }
  };

  const [foreFlightRoute, setForeFlightRoute] = useState(null);

  const {
    isSketching,
    draftPoints,
    sketchedRoutes,
    sketchSets,
    startSketch,
    cancelSketch,
    addDraftPoint,
    finishSketch,
    designateSketchPoint,
    updateSketchPointPosition,
    insertSketchPoint,
    appendSketchPoint,
    loadSketchRoutes,
    openSavedRoutes,
    replaceRouteSet,
    removeRouteSet,
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
    savedRoutesError,
    fetchSavedRoutes,
    saveSketch,
    saveMission,
    updateSketch,
    updateMission,
    loadSavedRoute,
    loadSavedRouteFile,
    deleteSavedRoute,
  } = useSavedRoutes();

  // The route sets in this session: the sketched routes, each saved set opened beside them, then each
  // imported mission file.
  const routeSets = useMemo(() => {
    const sets = sketchSets.map(({ setId, routes }) => ({ key: setId ?? SKETCHES, kind: "sketch", routes }));
    const byFile = new Map();
    importedRoutes.forEach((route) => {
      if (!byFile.has(route.fileId)) {
        const set = { key: route.fileId, kind: "mission", fileName: route.fileName, routes: [] };
        byFile.set(route.fileId, set);
        sets.push(set);
      }
      byFile.get(route.fileId).routes.push(route);
    });
    return sets;
  }, [importedRoutes, sketchSets]);

  const routeSaves = useRouteSaves({
    sets: routeSets,
    library: { savedRoutes, fetchSavedRoutes, saveSketch, updateSketch, saveMission, updateMission, serializeFile },
    signedIn: Boolean(user),
    onOpenLibrary: () => openLibrary("routes"),
  });

  // --- Mission Packs: one open at a time, in place of the Library (docs/MENU_REDESIGN.md §8) ----------
  const packs = usePackWorkspace({
    enabled: feat.mission_packs,
    user,
    toast,
    dock,
    lz: {
      workspace: lzWorkspace.workspace,
      importDiagram: lzWorkspace.importDiagram,
      applyRemoteDiagram: lzWorkspace.applyRemoteDiagram,
      removeDiagram,
      setActiveDiagram,
      hydrateWorkspace: lzWorkspace.hydrateWorkspace,
    },
    sketch: { sketchedRoutes, loadSketchRoutes, replaceRouteSet, removeRouteSet },
    points: { pointSets: localPoints.pointSets, setPointSets: localPoints.setPointSets },
    library: {
      history,
      savedRoutes,
      savedPointSets: localPoints.savedPointSets,
      refreshAll: () => {
        fetchHistory();
        fetchSavedRoutes();
        localPoints.fetchSavedPointSets();
      },
    },
    onOpenLibrary: () => openLibrary("lz"),
  });
  const inPack = Boolean(packs.open);
  const packReadOnly = inPack && packs.readOnly;
  const editable = !packReadOnly;
  // The pack's route set new routes go into (the last one chosen, else the first).
  const [packRouteTarget, setPackRouteTarget] = useState(null);
  const packRouteSetFor = () =>
    packs.packRoutes.open.includes(packRouteTarget) ? packRouteTarget : packs.packRoutes.open[0] ?? null;

  const handleLoadSavedRoute = async (entry) => {
    if (entry.kind === "mission") {
      const file = await loadSavedRouteFile(entry.id, entry.file_name);
      const { fileId } = await importMsnxFile(file);
      routeSaves.adopt(fileId, entry);
    } else {
      const record = await loadSavedRoute(entry.id);
      const routes = record.route_data?.routes;
      if (!routes?.length) throw new Error("This save contains no routes.");
      routeSaves.adopt(openSavedRoutes(routes) ?? SKETCHES, entry);
    }
  };

  const handleDeleteSavedRoute = async (id) => {
    await deleteSavedRoute(id);
    routeSaves.recordChanged(id, { deleted: true });
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
    // Cancelling the name keeps the sketch going.
    setNameAsk({
      title: "Name the route",
      subtitle: `${draftPoints.length} points. You can rename it later.`,
      icon: "route",
      initialName: defaultName,
      confirmLabel: "Finish route",
      upperCase: true,
      onConfirm: (name) => {
        const label = name || defaultName;
        if (inPack) {
          const target = packRouteSetFor() ?? packs.packRoutes.createItem();
          if (target) {
            setPackRouteTarget(target);
            finishSketch(label, packs.packRoutes.setIdOf(target));
          }
        } else {
          finishSketch(label);
        }
        setNameAsk(null);
      },
    });
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
      toast({ tone: "warn", message: `The mission exported, but the threats (.ths) could not be: ${err.message}` });
    }
  };

  /** Export .msnx for one route set, with the threats beside it as a .ths when asked. */
  const exportRouteSet = async (set, withThreats) => {
    try {
      if (set.kind === "mission") await exportFile(set.key);
      else await exportSketches(set.routes);
    } catch (err) {
      toast({ tone: "error", message: `The mission file could not be made: ${err.message}` });
      return;
    }
    if (withThreats) {
      await maybeExportThreats(
        set.kind === "mission" ? set.fileName : set.routes.map((r) => r.name).join("_") || "mission",
      );
    }
  };

  // A route set being closed with unsaved changes asks first.
  const [closingSet, setClosingSet] = useState(null);
  const dropRouteSet = (set) => {
    if (set.kind === "mission") set.routes.forEach((route) => removeRoute(route.id));
    else set.routes.forEach((route) => removeSketchRoute(route.id));
  };
  const closeRouteSet = (set) => {
    if (routeSaves.stateOf(set.key).dirty) setClosingSet(set);
    else dropRouteSet(set);
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
      toast({
        tone: "warn",
        message: "Start a route first (Sketch a route), then use + on a local point to snap the line to it.",
        action: { label: "Open Routes", onClick: () => dock.show("routes") },
      });
    }
  };

  const POINT_NAMES = { target: ".LZ", ip: ".RP", turn: ".CP" };
  const POINT_KINDS = { target: "LZ/PZ (target)", ip: "RP or IP", turn: "checkpoint" };

  /** Asks for a route point's name, then `apply(name)`. */
  const askPointName = (ptType, initialName, apply) => {
    setContextMenu(null);
    setNameAsk({
      title: "Name the point",
      subtitle: `A ${POINT_KINDS[ptType] ?? "route point"}. AMPS shows this name.`,
      icon: "mapPin",
      initialName,
      confirmLabel: "OK",
      upperCase: true,
      onConfirm: (name) => {
        apply(name || initialName);
        setNameAsk(null);
      },
    });
  };

  const handleAddDesignatedDraftPoint = (ptType) => {
    const { lat, lon } = contextMenu;
    askPointName(ptType, POINT_NAMES[ptType] || ".CP", (name) => addDraftPoint(lat, lon, { ptType, name }));
  };

  const findSketchPoint = (routeId, pointId) =>
    sketchedRoutes
      .find((r) => r.id === routeId)
      ?.points.find((p) => p.id === pointId);

  const handleDesignatePoint = (kind, ptType) => {
    const { routeId, pointId } = contextMenu;
    if (kind === "amps") {
      const current = findSketchPoint(routeId, pointId);
      askPointName(ptType, current?.name || POINT_NAMES[ptType] || ".CP", (name) =>
        designateSketchPoint(routeId, pointId, { kind: "amps", ptType, name }),
      );
      return;
    }
    designateSketchPoint(routeId, pointId, { kind: "shaping" });
    setContextMenu(null);
  };

  const handleRenameSketchPoint = () => {
    const { routeId, pointId } = contextMenu;
    const current = findSketchPoint(routeId, pointId);
    const ptType = current?.ptType ?? "turn";
    askPointName(ptType, current?.name || ".CP", (name) =>
      designateSketchPoint(routeId, pointId, { kind: "amps", ptType, name }),
    );
  };

  const handleInsertPointConfirm = () => {
    try {
      if (contextMenu.routeId.startsWith("sketch-")) {
        insertSketchPoint(contextMenu.routeId, contextMenu.lat, contextMenu.lon);
      } else {
        insertPoint(contextMenu.routeId, contextMenu.lat, contextMenu.lon);
      }
    } catch (err) {
      toast({ tone: "error", message: `The point could not be added: ${err.message}` });
    }
    setContextMenu(null);
  };

  const startDiagramAtTarget = useCallback(
    (target, mgrs) => {
      const normalizedMgrs = (mgrs || "").trim();
      if (packs.open && packs.readOnly) {
        toast({ tone: "warn", message: "This pack is read-only, so nothing new can be added to it." });
        return null;
      }
      const diagramId = packs.open
        ? packs.packLz.createItem(target, { mgrs: normalizedMgrs })
        : startDiagram(target, {
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
    [gridInput, startDiagram, packs, toast],
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

  // The grid of a clicked point, worked out in the browser (utils/mgrs gives
  // the backend's exact answer), so the menu opens with it already filled in.
  // Lat/long only at the poles, which MGRS covers with a different projection.
  const gridAt = (lat, lon) => toMgrs(lat, lon) ?? convertToLatLongString(lat, lon);

  const handleMapRightClick = (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "map", lat, lon });
    setClickedGrid(gridAt(lat, lon));
  };

  const handleSetAsTarget = () => {
    if (!contextMenu) return;
    startDiagramAtTarget([contextMenu.lat, contextMenu.lon], clickedGrid);
  };

  // 2. LZ Right-Click Handler
  const handleLZRightClick = (lat, lon, x, y) => {
    setContextMenu({ x, y, type: "lz", lat, lon });
    setClickedGrid(gridAt(lat, lon));
  };

  // 3. Drawing Controls
  const toggleDrawingMode = () => {
    if (!hasActiveTarget) {
      toast({ tone: "warn", message: "Set a target on the map before drawing an LZ/PZ boundary." });
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
        const mgrs = toMgrs(lat, lon);
        if (!mgrs) throw new Error("That position is beyond the MGRS grid (84°N / 80°S).");
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
      toast({ tone: "error", message: `That grid could not be found: ${detail}` });
    } finally {
      setLoading(false);
      setIsMobileMenuOpen(false); // Closes menu if they searched from the sidebar
    }
  };

  // --- The redesign's frame: top bar, dock, Library and imports (docs/MENU_REDESIGN.md) --------

  const imports = useImports({
    pack: packs.packForPanels,
    importers: {
      msnx: importMission,
      lps: (file, options) => localPoints.importLpsFile(file, options),
      ths: async (file) => {
        const count = await importThsFile(file);
        if (!count) throw new Error("No threats found in this file.");
        return count;
      },
    },
    destinations: {
      library: {
        msnx: (result, item) => routeSaves.queueSave(result.fileId, item.name),
        lps: (set) => localPoints.savePointSet(set),
      },
      pack: {
        lps: async (set) => {
          localPoints.removePointSet(set.id);
          if (!packs.packPoints.createItem(set.name, set.points)) throw new Error("the pack did not take the points");
        },
      },
    },
    onDone: (kinds) => {
      if (inPack && kinds.includes("msnx")) {
        toast({ tone: "info", message: "The mission file is in your Library session; packs hold sketched routes only. Switch to Library to see it." });
      }
      if (kinds.length === 1 && kinds[0] === "ths") dock.show("threats");
      else if (kinds.includes("msnx")) dock.show("routes");
      else if (kinds.length > 0) dock.show("imports");
    },
  });
  const pickers = {
    msnx: useFilePicker({ accept: ".msnx", onFiles: imports.begin }),
    lps: useFilePicker({ accept: ".lps,.LPS", multiple: true, onFiles: imports.begin }),
    ths: useFilePicker({ accept: ".ths,.THS", multiple: true, onFiles: imports.begin }),
    any: useFilePicker({ accept: ".msnx,.lps,.LPS,.ths,.THS", multiple: true, onFiles: imports.begin }),
  };

  // The Library's lists, loaded once signed in: the LZ/PZ panel offers the recent LZ/PZs, and the
  // Save dialogs check a new name against what is already saved.
  const signedInId = user?.id ?? null;
  useEffect(() => {
    if (signedInId == null || !feat.cloud_save) return;
    fetchHistory();
    fetchSavedRoutes();
    localPoints.fetchSavedPointSets();
    // Once per sign-in.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedInId]);

  const openSavedLz = async (entry) => {
    const snapshot = await loadMap(entry.id);
    const loadedId = applyMapState(snapshot, entry);
    if (loadedId) lzSaves.setSavedAt((prev) => ({ ...prev, [loadedId]: entry.updated_at }));
    dock.show("lz");
  };
  const sessionLzIds = new Set(diagrams.map((d) => d.savedId).filter((id) => id != null));
  const sessionRouteIds = new Set(routeSets.map((set) => routeSaves.stateOf(set.key).link?.id).filter((id) => id != null));
  const sessionPointIds = new Set(localPoints.pointSets.map((set) => set.savedId).filter((id) => id != null));
  const librarySources = {
    lz: {
      entries: history,
      loading: isLoadingHistory,
      error: historyError,
      openIds: sessionLzIds,
      refresh: fetchHistory,
      onOpen: openSavedLz,
      onDelete: async (entry) => {
        await deleteMap(entry.id);
        const open = Object.values(workspaceRef.current?.diagramsById ?? {}).find((d) => String(d.savedId) === String(entry.id));
        if (open) clearSaved(open.id);
      },
      onRenamed: (entry, name) => {
        const open = Object.values(workspaceRef.current?.diagramsById ?? {}).find((d) => String(d.savedId) === String(entry.id));
        if (!open) return;
        const wasDirty = open.dirty;
        lzWorkspace.setDiagramName(name, open.id);
        if (!wasDirty) markDirty(false, open.id);
      },
    },
    routes: {
      entries: savedRoutes,
      loading: isLoadingSaved,
      error: savedRoutesError,
      openIds: sessionRouteIds,
      refresh: fetchSavedRoutes,
      onOpen: async (entry) => {
        await handleLoadSavedRoute(entry);
        dock.show("routes");
      },
      onDelete: (entry) => handleDeleteSavedRoute(entry.id),
      onRenamed: (entry, name) => routeSaves.recordChanged(entry.id, { name }),
    },
    points: {
      entries: localPoints.savedPointSets,
      loading: localPoints.isLoadingSavedSets,
      error: localPoints.savedSetsError,
      openIds: sessionPointIds,
      refresh: localPoints.fetchSavedPointSets,
      onOpen: async (entry) => {
        await localPoints.loadSavedPointSet(entry);
        dock.show("imports");
      },
      onDelete: (entry) => localPoints.deleteSavedPointSet(entry.id),
      onRenamed: (entry, name) =>
        localPoints.setPointSets((prev) => prev.map((set) => (set.savedId === entry.id ? { ...set, name } : set))),
    },
  };
  const recentLz = history.filter((entry) => !sessionLzIds.has(entry.id)).slice(0, 3);
  // In a pack, opening something from the Library adds a copy of it to the pack.
  const PACK_KIND = { lz: "lz", routes: "route", points: "pointset" };
  const libraryView = inPack
    ? Object.fromEntries(
        Object.entries(librarySources).map(([tab, source]) => [
          tab,
          { ...source, openIds: new Set(), onOpen: (entry) => packs.copyIn(PACK_KIND[tab], entry) },
        ]),
      )
    : librarySources;

  // What is shown while a pack is open: its own route sets and point sets; the Library's stay loaded.
  const ofOpenPack = (localId) => Boolean(localId) && packLocalRef(localId)?.pack === packs.open;
  const shownSketches = inPack ? sketchedRoutes.filter((r) => ofOpenPack(r.setId)) : sketchedRoutes.filter((r) => !r.setId || !packLocalRef(r.setId));
  const shownImported = inPack ? [] : importedRoutes;
  const shownPointSets = (inPack ? localPoints.pointSets.filter((set) => ofOpenPack(set.id)) : localPoints.pointSets.filter((set) => !packLocalRef(set.id)))
    .map((set) => (packLocalRef(set.id) ? { ...set, packItemId: packLocalRef(set.id).item } : set));

  // The LZ/PZ panel in a pack lists every LZ/PZ in it; one not open here is drawn from the pack's copy.
  const packItemsByUuid = new Map(packs.items.map((item) => [item.uuid, item]));
  const lzCards = inPack
    ? packs.items
        .filter((item) => item.kind === "lz")
        .map((item) => lzWorkspace.workspace.diagramsById[packDiagramId(packs.open, item.uuid)] ?? { ...lzDiagramFromItem(packs.open, item), name: item.name })
    : diagrams;
  const packItemOf = (localId) => {
    const ref = packLocalRef(localId);
    return ref && ref.pack === packs.open ? packItemsByUuid.get(ref.item) ?? null : null;
  };
  const selectCard = (id) => {
    if (lzWorkspace.workspace.diagramsById[id]) handleSelectDiagram(id);
    else if (packItemOf(id)) packs.packLz.openItem(packLocalRef(id).item);
  };

  // A pack's route sets, as the Routes panel shows them.
  const packRouteSets = inPack
    ? packs.packRoutes.open.map((uuid) => {
        const item = packItemsByUuid.get(uuid);
        const setId = packs.packRoutes.setIdOf(uuid);
        return {
          key: uuid,
          kind: "sketch",
          item,
          routes: sketchedRoutes.filter((r) => r.setId === setId),
          pack: {
            finished: packs.readOnly,
            target: packRouteSetFor() === uuid,
            editedBy: item?.updated_by ?? null,
            editedAt: item?.updated_at ?? null,
          },
        };
      })
    : null;
  const routePanelSets = packRouteSets ?? routeSets;
  const routePanelState = inPack
    ? (key) => ({ name: packItemsByUuid.get(key)?.name ?? "ROUTES", link: null, dirty: false, savedAt: null, saving: false })
    : routeSaves.stateOf;

  // Who else has the pack open, once each.
  const packPeople = [];
  packs.others.forEach((person) => {
    if (!packPeople.some((p) => p.id === person.user_id)) packPeople.push({ id: person.user_id, name: person.name });
  });

  const lzUnsaved = diagrams.some((d) => d.status === "analyzed" && (d.savedId == null || d.dirty));
  const routesUnsaved = routeSets.some((set) => routeSaves.stateOf(set.key).dirty);
  const importCount = shownPointSets.length + (inPack ? 0 : routeSets.filter((set) => set.kind === "mission").length) + imports.threatFiles.length;
  const railItems = [
    ...(inPack
      ? [{ key: "pack", label: "Pack", icon: "layers", iconColor: "var(--pack)", dot: packs.newCount > 0 ? "pack" : null, dotLabel: "Changed since you looked" }]
      : []),
    { key: "lz", label: "LZ/PZ", icon: "hexagon", dot: !inPack && lzUnsaved ? "warn" : null, dotLabel: "Unsaved changes" },
    { key: "routes", label: "Routes", icon: "route", dot: !inPack && routesUnsaved ? "warn" : null, dotLabel: "Unsaved changes", hidden: !feat.routes },
    { key: "threats", label: "Threats", icon: "diamond", count: threats.length, hidden: !feat.threats },
    { key: "imports", label: "Imports", icon: "download", count: importCount },
  ];

  const sketchedPlan = {
    updateRoutePlan,
    updatePointPlanOverride,
    setPointClock: setSketchPointClock,
    updatePointName: updateSketchPointName,
    refreshRouteElevations,
    applyForecastWinds,
  };
  const importedPlan = {
    updateRoutePlan: updateImportedRoutePlan,
    updatePointPlanOverride: updateImportedPointOverride,
    setPointClock: setImportedPointClock,
    updatePointName: updateImportedPointName,
    refreshRouteElevations: refreshImportedElevations,
    applyForecastWinds: applyImportedForecastWinds,
  };
  const localPointNames = shownPointSets.flatMap((set) =>
    set.points.map((p) => ({ name: p.name, lat: p.lat, lon: p.lon, elevationFt: p.elevationFt })),
  );
  // A read-only pack (finished, or a viewer) hands the map no edit handlers, so its layers offer no
  // drag, delete or edit at all, rather than one the pack refuses and silently puts back.
  const mapEdit = (handler) => (editable ? handler : undefined);

  // Ctrl/⌘ S saves what the dock is showing: the route set with unsaved changes when Routes is open,
  // else the active LZ/PZ. The browser's own "save page" never appears.
  useEffect(() => {
    const onKey = (event) => {
      if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.altKey || event.key.toLowerCase() !== "s") return;
      event.preventDefault();
      if (document.querySelector('[role="dialog"]')) return;
      if (inPack) {
        toast({ tone: "pack", message: "Everything in a pack is saved as you make it." });
        return;
      }
      if (dock.panel === "routes" && routeSets.length > 0) {
        const set = routeSets.find((candidate) => routeSaves.stateOf(candidate.key).dirty) ?? routeSets[0];
        routeSaves.save(set.key);
        return;
      }
      if (activeDiagramId) lzSaves.save(activeDiagramId);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const closingState = closingSet ? routeSaves.stateOf(closingSet.key) : null;

  // The pack panel has nothing to show outside a pack.
  useEffect(() => {
    if (!inPack && dock.panel === "pack") dock.show("lz");
  }, [inPack, dock]);

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
          canAnalyze={canAnalyze && editable}
          canDrawBoundary={hasActiveTarget && editable}
          canUseDiagramTools={canEditGraphics && editable}
          canSaveDiagram={canEditGraphics && editable}
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
      <div className="map-area" {...imports.dragProps}>
        <TopBar
          user={user}
          isAdmin={Boolean(user?.is_admin)}
          onSignOut={logout}
          onOpenLibrary={() => openLibrary("lz")}
          onImport={(kind) => pickers[kind]?.open()}
          canImport={{ msnx: feat.msnx_import && !inPack, ths: feat.threats }}
          pack={packs.topPack}
          packStatus={packs.topStatus ?? "live"}
          people={packPeople}
          renderSwitcher={
            feat.mission_packs
              ? ({ open, close, anchorRef }) => (
                  <WorkspaceSwitcher
                    open={open}
                    onClose={close}
                    anchorRef={anchorRef}
                    onOpen={packs.home.refresh}
                    current={packs.open}
                    here={packPeople.length}
                    packs={packs.home.packs}
                    invites={packs.home.invites}
                    teams={packs.home.teams}
                    library={{
                      lz: history.length,
                      routes: savedRoutes.length,
                      points: localPoints.savedPointSets.length,
                      unsaved: inPack ? packs.parkedUnsaved : diagrams.filter((d) => d.status === "analyzed" && (d.savedId == null || d.dirty)).length,
                    }}
                    onPick={packs.openPack}
                    onNewPack={packs.newPack}
                    onManageTeams={packs.manageTeams}
                    onAccept={(invite) => packs.answerInvite(invite, true)}
                    onDecline={(invite) => packs.answerInvite(invite, false)}
                  />
                )
              : undefined
          }
          onOpenMenu={isMobile ? () => setIsMobileMenuOpen(true) : undefined}
        />
        <div
          className={`shell-map${dock.collapsed ? " shell-map--dock-collapsed" : ""}${inPack && packs.meta?.status === "finished" ? " shell-map--banner" : ""}`}
        >
        <UnitBadge />
        {inPack && packs.meta?.status === "finished" && (
          <FinishedBanner
            pack={packs.meta}
            isOwner={packs.role === "owner"}
            onSaveCopy={packItemOf(activeDiagramId) ? () => packs.actions.saveCopy(packItemOf(activeDiagramId)) : undefined}
            onDuplicate={packs.actions.duplicate}
            onReopen={packs.actions.reopen}
          />
        )}
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
          canAnalyze={canAnalyze && editable}
          canUseDiagramTools={canEditGraphics && editable}
          canSaveDiagram={canEditGraphics && editable}
          diagramStatus={diagramStatus}
          diagramReadinessText={diagramReadinessText}
        />
        <MapView
          importedRoutes={shownImported}
          onUpdateMsnxPointPosition={mapEdit(updatePointPosition)}
          onInsertMsnxPoint={mapEdit(handleInsertPointContextMenu)}
          sketchedRoutes={shownSketches}
          onUpdateSketchPointPosition={mapEdit(updateSketchPointPosition)}
          onSketchPointContextMenu={mapEdit(handleSketchPointContextMenu)}
          isSketchingRoute={isSketching}
          addDraftPoint={addDraftPoint}
          onDraftPointContextMenu={handleDraftPointContextMenu}
          draftPoints={draftPoints}
          activeDiagramId={activeDiagramId}
          targetLocation={targetLocation}
          mapData={mapData}
          detectedLZ={detectedLZ}
          assets={helicopters}
          updateAsset={mapEdit(updateHelicopter)}
          deleteAsset={mapEdit(deleteHelicopter)}
          aircraftProfiles={aircraftProfiles}
          activeAircraftProfile={activeProfile}
          showHeatmap={showHeatmap}
          terrainData={terrainData}
          doghouses={doghouses}
          updateDoghouse={mapEdit(updateDoghouse)}
          goArounds={goAround}
          updateGoAround={mapEdit(updateGoAround)}
          deleteGoAround={mapEdit(deleteGoAround)}
          updatePZMarker={mapEdit(updatePZMarker)}
          deletePZMarker={mapEdit(deletePZMarker)}
          pzMarkers={pzMarker}
          units={units}
          onEditUnit={mapEdit(setEditingUnit)}
          updateUnitPosition={mapEdit(updateUnitPosition)}
          showLZOutline={showLZOutline}
          deleteUnit={mapEdit(deleteUnit)}
          sectors={sectorsOfFire}
          updateSectorPoint={mapEdit(updateSectorOfFirePoint)}
          moveSector={mapEdit(moveSectorOfFire)}
          deleteSector={mapEdit(deleteSectorOfFire)}
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
          // The drawn LZ's own menu deletes or re-analyzes it; read-only, the map's menu opens there instead.
          handleLZRightClick={editable ? handleLZRightClick : handleMapRightClick}
          setContextMenu={setContextMenu}
          mapStyle={mapStyle}
          localPointSets={shownPointSets}
          onAddLocalPointToRoute={mapEdit(handleAddLocalPointToRoute)}
          threats={threats}
          onThreatMove={moveThreat}
          onThreatEdit={beginEditThreat}
          onMapMove={setMapCenter}
          presence={inPack ? packs.others : undefined}
          onPoint={inPack ? packs.pointAt : undefined}
        />

        <div className="alert-queue">
          {proximityAlerts.map((alert) => (
            <div key={alert.id} className="proximity-alert">
              ⚠️ {alert.message}
            </div>
          ))}
        </div>
        {imports.overlay}
        </div>

        <Dock items={railItems} dock={dock}>
          {dock.panel === "pack" && inPack && packs.meta && (
            <PackPanel
              pack={packs.meta}
              members={packs.session?.members ?? []}
              items={packs.items}
              openIds={packs.openIds}
              people={packs.others}
              me={user?.id}
              newSince={packs.seen.newSince}
              readOnly={packs.readOnly}
              actions={packs.actions}
              tab={packs.panelTab}
              setTab={packs.setPanelTab}
              dropped={packs.dropped}
              onKeepDropped={packs.keepDropped}
              status={packs.status}
              onCollapse={() => dock.setCollapsed(true)}
            />
          )}
          {dock.panel === "pack" && inPack && !packs.meta && (
            <>
              <PanelHead title={packs.topPack?.name ?? "Pack"} subtitle={packs.status === "error" ? "Cannot reach the pack" : "Opening…"} onCollapse={() => dock.setCollapsed(true)} />
              <div className="shell-dock__body">
                <div className="shell-empty">
                  {packs.status === "error" ? "The pack could not be opened. Check the connection; it is tried again by itself." : "Opening the pack…"}
                </div>
              </div>
            </>
          )}
          {dock.panel === "lz" && (
            <LzPanel
              diagrams={lzCards}
              activeDiagramId={activeDiagramId}
              savedAt={lzSaves.savedAt}
              savingId={lzSaves.savingId}
              recent={feat.cloud_save && !inPack ? recentLz : []}
              pack={inPack ? packs.packForPanels : null}
              itemInfo={inPack ? packs.itemInfo : undefined}
              presence={inPack ? packs.presenceOn : undefined}
              onSelect={selectCard}
              onSave={(id, options) => (inPack ? packItemOf(id) && packs.actions.saveCopy(packItemOf(id)) : lzSaves.save(id, options))}
              onSaveAs={(id) => (inPack ? packItemOf(id) && packs.actions.saveCopy(packItemOf(id)) : lzSaves.saveAs(id))}
              onRename={(id, name) => lzWorkspace.setDiagramName(name, id)}
              onClose={(id) => (inPack ? packItemOf(id) && packs.actions.close(packItemOf(id)) : lzSaves.close(id))}
              onAddToPack={
                feat.mission_packs && !inPack
                  ? (id) => {
                      const diagram = lzWorkspace.workspace.diagramsById[id];
                      const entry = history.find((e) => e.id === diagram?.savedId) ?? { id: diagram?.savedId, name: diagram?.name };
                      if (entry.id != null) packs.addToPack("lz", entry);
                    }
                  : undefined
              }
              onView3D={(id) => {
                // The 3D window always shows the active LZ, so a card's 3D button makes its LZ active first.
                if (id && id !== activeDiagramId) handleSelectDiagram(id);
                setIs3DOpen(true);
              }}
              onOpenRecent={(entry) =>
                openSavedLz(entry).catch((err) => toast({ tone: "error", message: `“${entry.name}” could not be opened: ${err.message}` }))
              }
              onBrowseAll={() => openLibrary("lz")}
              onCollapse={() => dock.setCollapsed(true)}
            />
          )}
          {dock.panel === "routes" && (
            <RoutesDockPanel
              sets={routePanelSets}
              stateOf={routePanelState}
              pack={inPack ? packs.packForPanels : null}
              threatCount={feat.threats ? threats.length : 0}
              actions={{
                save: (set) => routeSaves.save(set.key),
                saveAs: (set) => (set.pack ? set.item && packs.actions.saveCopy(set.item) : routeSaves.saveAs(set.key)),
                rename: (set, name) => (set.pack ? packs.packRoutes.renameSet(set.key, name) : routeSaves.rename(set.key, name)),
                close: (set) => (set.pack ? set.item && packs.actions.close(set.item) : closeRouteSet(set)),
                sketchInto: inPack
                  ? (set) => {
                      setPackRouteTarget(set.key);
                      if (!isSketching) startSketch();
                    }
                  : undefined,
                addToPack:
                  feat.mission_packs && !inPack ? (set, link) => link && packs.addToPack("route", { id: link.id, name: link.name }) : undefined,
                export: (set, { withThreats }) => exportRouteSet(set, withThreats),
                share: setForeFlightRoute,
                toggleVisibility: (set, id) => (set.kind === "mission" ? toggleRouteVisibility(id) : toggleSketchVisibility(id)),
                removeRoute: (set, id) => (set.kind === "mission" ? removeRoute(id) : removeSketchRoute(id)),
              }}
              plan={(set) => (set.kind === "mission" ? importedPlan : sketchedPlan)}
              localPointNames={localPointNames}
              sketch={{
                active: isSketching,
                name: `ROUTE ${sketchedRoutes.length + 1}`,
                points: draftPoints.length,
                enabled: feat.routes && editable,
                onStart: startSketch,
                onCancel: cancelSketch,
                onFinish: toggleRouteSketch,
              }}
              onImportMsnx={pickers.msnx.open}
              canImportMsnx={feat.msnx_import}
              onCollapse={() => dock.setCollapsed(true)}
            />
          )}
          {dock.panel === "threats" && (
            <ThreatsDockPanel
              threats={threats}
              onAdd={() => beginAddThreat(mapCenter[0], mapCenter[1])}
              onImport={pickers.ths.open}
              onEdit={beginEditThreat}
              onRemove={removeThreat}
              onRemoveAll={() => threats.forEach((threat) => removeThreat(threat.id))}
              onToggleVisibility={toggleThreatVisibility}
              onExportThs={() =>
                Promise.resolve(exportThsFile("threats")).catch((err) => toast({ tone: "error", message: `The .ths could not be made: ${err.message}` }))
              }
              onExportKmz={() => setShowThreatExport(true)}
              onCollapse={() => dock.setCollapsed(true)}
            />
          )}
          {dock.panel === "imports" && (
            <ImportsPanel
              pointSets={shownPointSets}
              pack={inPack ? packs.packForPanels : null}
              missions={(inPack ? [] : routeSets)
                .filter((set) => set.kind === "mission")
                .map((set) => ({ key: set.key, fileName: set.fileName, summary: setSummary(set.routes), saved: Boolean(routeSaves.stateOf(set.key).link) }))}
              threatFiles={imports.threatFiles}
              onImport={pickers.any.open}
              onTogglePoints={localPoints.togglePointSetVisibility}
              onSavePoints={
                feat.cloud_save
                  ? (set) =>
                      localPoints
                        .savePointSet(set)
                        .then(() => toast({ message: `Saved “${set.name}” to your Library`, action: { label: "Open Library", onClick: () => openLibrary("points") } }))
                        .catch((err) => toast({ tone: "error", message: `“${set.name}” could not be saved: ${err.message}` }))
                  : undefined
              }
              onRemovePoints={(id) => {
                const item = packItemOf(id);
                if (item) packs.actions.close(item);
                else localPoints.removePointSet(id);
              }}
              onOpenRoutes={() => dock.show("routes")}
              onOpenThreats={() => dock.show("threats")}
              onCollapse={() => dock.setCollapsed(true)}
            />
          )}
        </Dock>
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

      {is3DOpen && activeDiagram?.target && (
        <Lz3DWindow
          label={
            `${activeDiagram.flightData?.lz_label || "LZ"} ` +
            `${activeDiagram.flightData?.lz_name || activeDiagram.name || ""}`.trim()
          }
          lat={activeDiagram.target.lat}
          lon={activeDiagram.target.lon}
          saved={activeDiagram.savedId != null}
          importedRoutes={shownImported}
          sketchedRoutes={shownSketches}
          onClose={() => setIs3DOpen(false)}
        />
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

      {packs.dialogs}
      {lzSaves.dialogs}
      {routeSaves.dialogs}
      {imports.dialog}
      {nameAsk && <NameDialog {...nameAsk} onCancel={() => setNameAsk(null)} />}
      {closingSet && closingState && (
        <ConfirmDialog
          title={`Save changes to ${closingState.name}?`}
          text="You have unsaved changes. If you close these routes now they are lost."
          icon="alertTriangle"
          iconTone="warn"
          confirmLabel="Save"
          confirmIcon="save"
          secondary={{
            label: "Don’t save",
            tone: "danger-soft",
            onClick: () => {
              dropRouteSet(closingSet);
              setClosingSet(null);
            },
          }}
          onCancel={() => setClosingSet(null)}
          onConfirm={() => {
            const set = closingSet;
            setClosingSet(null);
            routeSaves.save(set.key, { then: () => dropRouteSet(set) });
          }}
        />
      )}
      {libraryTab && (
        <LibraryDialog
          initialTab={libraryTab}
          sources={libraryView}
          onClose={() => setLibraryTab(null)}
          onAddToPack={feat.mission_packs && !inPack ? (tab, entry) => packs.addToPack(PACK_KIND[tab], entry) : undefined}
          openLabel={inPack ? "Add to pack" : "Open"}
          openingLabel={inPack ? "Adding" : "Opening"}
          subtitle={inPack ? `Everything you have saved. Adding one puts a copy of it in ${packs.meta?.name ?? "the pack"}.` : undefined}
        />
      )}
      {pickers.msnx.element}
      {pickers.lps.element}
      {pickers.ths.element}
      {pickers.any.element}

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
