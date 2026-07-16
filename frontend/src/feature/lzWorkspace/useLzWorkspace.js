import { useCallback, useMemo, useReducer } from "react";

/**
 * A saved LZ/PZ diagram is deliberately versioned independently from the API
 * record that stores it.  The backend can continue treating `lz_data` as JSON
 * while the client evolves this shape with explicit migrations.
 */
export const LZ_WORKSPACE_SCHEMA_VERSION = 2;

export const LZ_DIAGRAM_STATUS = Object.freeze({
  DRAFT: "draft",
  TARGETED: "targeted",
  ANALYZED: "analyzed",
});

export const LZ_GRAPHIC_COLLECTIONS = Object.freeze([
  "doghouses",
  "helicopters",
  "pzMarkers",
  "sectorsOfFire",
  "goArounds",
  "units",
  "measurements",
]);

export const LZ_WORKSPACE_ACTIONS = Object.freeze({
  CREATE_DIAGRAM: "lzWorkspace/createDiagram",
  IMPORT_DIAGRAM: "lzWorkspace/importDiagram",
  HYDRATE_WORKSPACE: "lzWorkspace/hydrateWorkspace",
  SET_ACTIVE_DIAGRAM: "lzWorkspace/setActiveDiagram",
  REMOVE_DIAGRAM: "lzWorkspace/removeDiagram",
  SET_ANALYSIS_DRAFT: "lzWorkspace/setAnalysisDraft",
  SET_RUNTIME_TERRAIN_DATA: "lzWorkspace/setRuntimeTerrainData",
  COMPLETE_ANALYSIS: "lzWorkspace/completeAnalysis",
  RESET_ANALYSIS: "lzWorkspace/resetAnalysis",
  SET_GRAPHICS: "lzWorkspace/setGraphics",
  SET_GRAPHIC_COLLECTION: "lzWorkspace/setGraphicCollection",
  UPSERT_GRAPHIC: "lzWorkspace/upsertGraphic",
  PATCH_GRAPHIC: "lzWorkspace/patchGraphic",
  REMOVE_GRAPHIC: "lzWorkspace/removeGraphic",
  SET_FLIGHT_DATA: "lzWorkspace/setFlightData",
  SET_VIEW: "lzWorkspace/setView",
  SET_DIAGRAM_NAME: "lzWorkspace/setDiagramName",
  MARK_DIRTY: "lzWorkspace/markDirty",
  MARK_SAVED: "lzWorkspace/markSaved",
  CLEAR_SAVED: "lzWorkspace/clearSaved",
});

let localIdCounter = 0;

const hasOwn = (object, key) =>
  Object.prototype.hasOwnProperty.call(object ?? {}, key);

const isObject = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const asArray = (value) => (Array.isArray(value) ? value : []);

const cloneArray = (value) =>
  asArray(value).map((item) => (isObject(item) ? { ...item } : item));

const cloneObject = (value) => (isObject(value) ? { ...value } : {});

const shallowEqual = (left, right) => {
  const leftObject = left ?? {};
  const rightObject = right ?? {};
  const leftKeys = Object.keys(leftObject);
  const rightKeys = Object.keys(rightObject);

  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every(
      (key) =>
        Object.prototype.hasOwnProperty.call(rightObject, key) &&
        Object.is(leftObject[key], rightObject[key]),
    )
  );
};

const nowIso = () => new Date().toISOString();

/**
 * Generates a client-side id without adding a package dependency.  Callers may
 * pass a known id when hydrating a saved diagram or coordinating an async
 * terrain request.
 */
export const createLzDiagramId = () => {
  if (typeof window !== "undefined" && window.crypto?.randomUUID) {
    return window.crypto.randomUUID();
  }

  localIdCounter += 1;
  return `lz-${Date.now().toString(36)}-${localIdCounter}`;
};

/**
 * Normalizes both the existing `[lat, lon]` App state and object-style inputs.
 */
export const normalizeLzTarget = (target, mgrs = "") => {
  if (!target) return null;

  const lat = Array.isArray(target)
    ? target[0]
    : target.lat ?? target.latitude;
  const lon = Array.isArray(target)
    ? target[1]
    : target.lon ?? target.lng ?? target.longitude;

  const parsedLat = Number(lat);
  const parsedLon = Number(lon);
  if (!Number.isFinite(parsedLat) || !Number.isFinite(parsedLon)) {
    return null;
  }

  return {
    lat: parsedLat,
    lon: parsedLon,
    mgrs:
      typeof mgrs === "string" && mgrs.trim()
        ? mgrs
        : typeof target.mgrs === "string"
          ? target.mgrs
          : "",
  };
};

export const createEmptyLzGraphics = () => ({
  doghouses: [],
  helicopters: [],
  pzMarkers: [],
  sectorsOfFire: [],
  goArounds: [],
  units: [],
  measurements: [],
  exportBox: null,
});

export const createEmptyLzAnalysis = () => ({
  customLZ: null,
  detectedLZ: null,
  // Raster data can be large (and may contain base64 pixels). It is runtime
  // state only; serializeDiagram intentionally strips it for saved records.
  terrainData: null,
  results: null,
  gridElevation: "",
  latLong: "",
});

export const createDefaultLzView = () => ({
  mapStyle: "satellite",
  showLZOutline: true,
  showHeatmap: false,
});

const normalizeGraphics = (source = {}) => {
  const graphics = isObject(source.graphics) ? source.graphics : source;

  return {
    doghouses: cloneArray(graphics.doghouses),
    helicopters: cloneArray(graphics.helicopters),
    // `pzMarker`, `sectorsOfFire`, and `goAround` are the field names in the
    // current flat App snapshot. New diagrams use the pluralized names.
    pzMarkers: cloneArray(graphics.pzMarkers ?? graphics.pzMarker),
    sectorsOfFire: cloneArray(graphics.sectorsOfFire),
    goArounds: cloneArray(graphics.goArounds ?? graphics.goAround),
    units: cloneArray(graphics.units),
    measurements: cloneArray(graphics.measurements),
    exportBox: graphics.exportBox ?? null,
  };
};

const normalizeAnalysis = (source = {}) => {
  const analysis = isObject(source.analysis) ? source.analysis : source;
  const defaults = createEmptyLzAnalysis();

  return {
    ...defaults,
    customLZ: analysis.customLZ ?? defaults.customLZ,
    detectedLZ: analysis.detectedLZ ?? defaults.detectedLZ,
    terrainData: analysis.terrainData ?? defaults.terrainData,
    results: analysis.results ?? analysis.analysisResults ?? defaults.results,
    gridElevation: analysis.gridElevation ?? defaults.gridElevation,
    latLong: analysis.latLong ?? defaults.latLong,
  };
};

const normalizeView = (source = {}) => {
  const view = isObject(source.view) ? source.view : source;
  const defaults = createDefaultLzView();

  return {
    mapStyle: view.mapStyle ?? defaults.mapStyle,
    showLZOutline: view.showLZOutline ?? defaults.showLZOutline,
    showHeatmap: view.showHeatmap ?? defaults.showHeatmap,
  };
};

const normalizeMapData = (value) => (isObject(value) ? { ...value } : {});

const hasAnalysisResult = (analysis) =>
  Boolean(analysis.detectedLZ || analysis.results);

const deriveDiagramStatus = (requestedStatus, target, analysis) => {
  if (!target) return LZ_DIAGRAM_STATUS.DRAFT;

  if (
    requestedStatus === LZ_DIAGRAM_STATUS.ANALYZED ||
    hasAnalysisResult(analysis)
  ) {
    return LZ_DIAGRAM_STATUS.ANALYZED;
  }

  return LZ_DIAGRAM_STATUS.TARGETED;
};

/**
 * Produces the current version of one per-LZ/PZ diagram from either a current
 * diagram object or a legacy flat snapshot. This function is intentionally
 * side-effect free so it can also be used for API migrations.
 */
export const normalizeLzDiagram = (source = {}, options = {}) => {
  const input = isObject(source) ? source : {};
  const sourceTarget = input.target ?? input.targetLocation;
  const targetMgrs =
    input.target?.mgrs ?? input.mapData?.mgrs ?? input.gridInput ?? "";
  const target = normalizeLzTarget(sourceTarget, targetMgrs);
  const analysis = normalizeAnalysis(input);
  const createdAt =
    options.createdAt ?? input.createdAt ?? input.created_at ?? nowIso();
  const updatedAt =
    options.updatedAt ?? input.updatedAt ?? input.updated_at ?? createdAt;
  const id = options.id ?? input.id ?? createLzDiagramId();

  return {
    schemaVersion: LZ_WORKSPACE_SCHEMA_VERSION,
    id: String(id),
    savedId: options.savedId ?? input.savedId ?? null,
    name: options.name ?? input.name ?? "",
    dirty: options.dirty ?? Boolean(input.dirty),
    createdAt,
    updatedAt,
    status: deriveDiagramStatus(input.status, target, analysis),
    target,
    mapData: normalizeMapData(input.mapData),
    flightData: cloneObject(input.flightData),
    analysis,
    graphics: normalizeGraphics(input),
    view: normalizeView(input),
  };
};

/**
 * Starts a blank, target-bound diagram. Graphics are deliberately empty: the
 * caller should create defaults (such as doghouses) only after analysis has
 * succeeded for this particular diagram.
 */
export const createLzDiagramFromTarget = ({
  target,
  mgrs = "",
  id,
  name = "",
  savedId = null,
  mapData = {},
  view,
  createdAt,
} = {}) => {
  const normalizedTarget = normalizeLzTarget(target, mgrs);
  if (!normalizedTarget) return null;

  const timestamp = createdAt ?? nowIso();
  return normalizeLzDiagram(
    {
      id: id ?? createLzDiagramId(),
      savedId,
      name,
      dirty: true,
      createdAt: timestamp,
      updatedAt: timestamp,
      target: normalizedTarget,
      mapData: {
        ...normalizeMapData(mapData),
        mgrs: normalizedTarget.mgrs || mapData?.mgrs || "",
      },
      view,
      analysis: createEmptyLzAnalysis(),
      graphics: createEmptyLzGraphics(),
      status: LZ_DIAGRAM_STATUS.TARGETED,
    },
    { id: id ?? undefined, createdAt: timestamp, updatedAt: timestamp },
  );
};

/**
 * Converts the current App.js save shape into exactly one versioned diagram.
 * It accepts the API record as well, so callers may provide `id`/`name` from
 * the SavedLZ listing without changing the legacy payload itself.
 */
export const normalizeLegacyLzSnapshot = (snapshot = {}, options = {}) => {
  const wrapper = isObject(snapshot) ? snapshot : {};
  const isApiRecord = isObject(wrapper.lz_data);
  const record = isApiRecord ? wrapper.lz_data : wrapper;
  return normalizeLzDiagram(record, {
    id: options.id ?? wrapper.clientId ?? undefined,
    savedId:
      options.savedId ?? (isApiRecord ? wrapper.id : wrapper.savedId ?? null),
    name: options.name ?? wrapper.name ?? "",
    dirty: options.dirty ?? false,
    createdAt:
      options.createdAt ?? (isApiRecord ? wrapper.created_at : undefined),
    updatedAt:
      options.updatedAt ?? (isApiRecord ? wrapper.updated_at : undefined),
  });
};

export const getActiveLzDiagram = (workspace) => {
  if (!workspace?.activeDiagramId) return null;
  return workspace.diagramsById?.[workspace.activeDiagramId] ?? null;
};

export const canAnalyzeLzDiagram = (diagram) => Boolean(diagram?.target);

export const canEditLzDiagramGraphics = (diagram) =>
  Boolean(
    diagram?.target && diagram.status === LZ_DIAGRAM_STATUS.ANALYZED,
  );

const uniqueDiagramId = (diagramsById, requestedId) => {
  if (!diagramsById[requestedId]) return requestedId;

  let suffix = 2;
  let candidate = `${requestedId}-${suffix}`;
  while (diagramsById[candidate]) {
    suffix += 1;
    candidate = `${requestedId}-${suffix}`;
  }
  return candidate;
};

/**
 * Accepts an empty value, a workspace, an array of diagrams, a single new
 * diagram, or a legacy snapshot.  It gives the UI one canonical session shape.
 */
export const createInitialLzWorkspace = (source = {}) => {
  const rawDiagrams = Array.isArray(source)
    ? source
    : Array.isArray(source?.diagrams)
      ? source.diagrams
      : Array.isArray(source?.diagramOrder) && isObject(source?.diagramsById)
        ? source.diagramOrder.map((id) => source.diagramsById[id])
        : source && (source.targetLocation || source.target || source.graphics)
          ? [source]
          : [];

  const diagramsById = {};
  const diagramOrder = [];

  rawDiagrams.filter(Boolean).forEach((rawDiagram) => {
    const normalized = normalizeLzDiagram(rawDiagram, { dirty: Boolean(rawDiagram.dirty) });
    const id = uniqueDiagramId(diagramsById, normalized.id);
    const diagram = id === normalized.id ? normalized : { ...normalized, id };
    diagramsById[id] = diagram;
    diagramOrder.push(id);
  });

  const requestedActiveId = source?.activeDiagramId;
  const activeDiagramId = diagramsById[requestedActiveId]
    ? requestedActiveId
    : diagramOrder[diagramOrder.length - 1] ?? null;

  return {
    schemaVersion: LZ_WORKSPACE_SCHEMA_VERSION,
    diagramsById,
    diagramOrder,
    activeDiagramId,
  };
};

/**
 * Saved payloads should contain one diagram only. Terrain raster data is not
 * persisted because it is regenerated from the analysis boundary on load.
 */
export const serializeLzDiagram = (diagram, { includeTerrainData = false } = {}) => {
  if (!diagram) return null;

  const normalized = normalizeLzDiagram(diagram, {
    id: diagram.id,
    dirty: false,
    createdAt: diagram.createdAt,
    updatedAt: diagram.updatedAt,
  });

  return {
    ...normalized,
    dirty: false,
    analysis: {
      ...normalized.analysis,
      terrainData: includeTerrainData ? normalized.analysis.terrainData : null,
    },
  };
};

const updateDiagram = (state, diagramId, updater, { markDirty = true } = {}) => {
  const current = state.diagramsById[diagramId];
  if (!current) return state;

  const next = updater(current);
  if (!next || next === current) return state;

  const updated = {
    ...next,
    updatedAt: nowIso(),
    dirty: markDirty ? true : next.dirty,
  };

  return {
    ...state,
    diagramsById: {
      ...state.diagramsById,
      [diagramId]: updated,
    },
  };
};

const updateGraphics = (diagram, updater) => ({
  ...diagram,
  graphics: updater(diagram.graphics),
});

const isGraphicCollection = (collection) =>
  LZ_GRAPHIC_COLLECTIONS.includes(collection);

const mergeGraphics = (graphics, patch) => {
  const normalizedPatch = normalizeGraphics({ graphics: patch });
  const next = { ...graphics };

  LZ_GRAPHIC_COLLECTIONS.forEach((collection) => {
    if (hasOwn(patch, collection)) {
      next[collection] = normalizedPatch[collection];
    }
  });
  if (hasOwn(patch, "exportBox")) next.exportBox = normalizedPatch.exportBox;

  return next;
};

export const lzWorkspaceReducer = (state, action) => {
  switch (action.type) {
    case LZ_WORKSPACE_ACTIONS.HYDRATE_WORKSPACE:
      return createInitialLzWorkspace(action.workspace);

    case LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM:
    case LZ_WORKSPACE_ACTIONS.IMPORT_DIAGRAM: {
      if (!action.diagram) return state;
      const normalized = normalizeLzDiagram(action.diagram, {
        dirty:
          action.dirty ??
          (action.type === LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM),
      });
      const id = uniqueDiagramId(state.diagramsById, normalized.id);
      const diagram = id === normalized.id ? normalized : { ...normalized, id };

      return {
        ...state,
        diagramsById: {
          ...state.diagramsById,
          [id]: diagram,
        },
        diagramOrder: [...state.diagramOrder, id],
        activeDiagramId: action.activate === false ? state.activeDiagramId : id,
      };
    }

    case LZ_WORKSPACE_ACTIONS.SET_ACTIVE_DIAGRAM:
      return state.diagramsById[action.diagramId]
        ? { ...state, activeDiagramId: action.diagramId }
        : state;

    case LZ_WORKSPACE_ACTIONS.REMOVE_DIAGRAM: {
      if (!state.diagramsById[action.diagramId]) return state;

      const remainingDiagrams = { ...state.diagramsById };
      delete remainingDiagrams[action.diagramId];
      const diagramOrder = state.diagramOrder.filter((id) => id !== action.diagramId);
      const activeDiagramId =
        state.activeDiagramId === action.diagramId
          ? diagramOrder[diagramOrder.length - 1] ?? null
          : state.activeDiagramId;

      return {
        ...state,
        diagramsById: remainingDiagrams,
        diagramOrder,
        activeDiagramId,
      };
    }

    case LZ_WORKSPACE_ACTIONS.SET_ANALYSIS_DRAFT:
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!diagram.target) return diagram;
        return {
          ...diagram,
          analysis: {
            ...diagram.analysis,
            ...cloneObject(action.analysis),
          },
        };
      });

    // Terrain raster data is regenerated for the active diagram and is
    // intentionally omitted from saved snapshots. Updating it must not turn a
    // previously saved diagram into an unsaved one when the user switches LZs.
    case LZ_WORKSPACE_ACTIONS.SET_RUNTIME_TERRAIN_DATA:
      return updateDiagram(
        state,
        action.diagramId,
        (diagram) => {
          if (!diagram.target || diagram.analysis.terrainData === action.terrainData) {
            return diagram;
          }

          return {
            ...diagram,
            analysis: {
              ...diagram.analysis,
              terrainData: action.terrainData,
            },
          };
        },
        { markDirty: false },
      );

    case LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS:
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!diagram.target) return diagram;
        return {
          ...diagram,
          status: LZ_DIAGRAM_STATUS.ANALYZED,
          analysis: {
            ...diagram.analysis,
            ...cloneObject(action.analysis),
          },
        };
      });

    case LZ_WORKSPACE_ACTIONS.RESET_ANALYSIS:
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!diagram.target) return diagram;
        return {
          ...diagram,
          status: LZ_DIAGRAM_STATUS.TARGETED,
          analysis: createEmptyLzAnalysis(),
        };
      });

    case LZ_WORKSPACE_ACTIONS.SET_GRAPHICS:
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!canEditLzDiagramGraphics(diagram)) return diagram;
        return updateGraphics(diagram, (graphics) =>
          mergeGraphics(graphics, cloneObject(action.graphics)),
        );
      });

    case LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION:
      if (!isGraphicCollection(action.collection)) return state;
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!canEditLzDiagramGraphics(diagram)) return diagram;
        return updateGraphics(diagram, (graphics) => ({
          ...graphics,
          [action.collection]: cloneArray(action.items),
        }));
      });

    case LZ_WORKSPACE_ACTIONS.UPSERT_GRAPHIC:
      if (!isGraphicCollection(action.collection) || !isObject(action.item)) {
        return state;
      }
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!canEditLzDiagramGraphics(diagram)) return diagram;
        return updateGraphics(diagram, (graphics) => {
          const items = graphics[action.collection];
          const itemId = action.item.id;
          const existingIndex = items.findIndex((item) => item?.id === itemId);
          const nextItems =
            itemId !== undefined && existingIndex >= 0
              ? items.map((item, index) =>
                  index === existingIndex ? { ...item, ...action.item } : item,
                )
              : [...items, { ...action.item }];

          return { ...graphics, [action.collection]: nextItems };
        });
      });

    case LZ_WORKSPACE_ACTIONS.PATCH_GRAPHIC:
      if (!isGraphicCollection(action.collection) || action.id === undefined) {
        return state;
      }
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!canEditLzDiagramGraphics(diagram)) return diagram;
        return updateGraphics(diagram, (graphics) => ({
          ...graphics,
          [action.collection]: graphics[action.collection].map((item) =>
            item?.id === action.id ? { ...item, ...cloneObject(action.patch) } : item,
          ),
        }));
      });

    case LZ_WORKSPACE_ACTIONS.REMOVE_GRAPHIC:
      if (!isGraphicCollection(action.collection) || action.id === undefined) {
        return state;
      }
      return updateDiagram(state, action.diagramId, (diagram) => {
        if (!canEditLzDiagramGraphics(diagram)) return diagram;
        return updateGraphics(diagram, (graphics) => ({
          ...graphics,
          [action.collection]: graphics[action.collection].filter(
            (item) => item?.id !== action.id,
          ),
        }));
      });

    case LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA:
      return updateDiagram(state, action.diagramId, (diagram) => {
        const flightData = {
          ...diagram.flightData,
          ...cloneObject(action.flightData),
        };

        return shallowEqual(flightData, diagram.flightData)
          ? diagram
          : { ...diagram, flightData };
      });

    case LZ_WORKSPACE_ACTIONS.SET_VIEW:
      return updateDiagram(state, action.diagramId, (diagram) => ({
        ...diagram,
        view: {
          ...diagram.view,
          ...cloneObject(action.view),
        },
      }));

    case LZ_WORKSPACE_ACTIONS.SET_DIAGRAM_NAME:
      return updateDiagram(state, action.diagramId, (diagram) => ({
        ...diagram,
        name: action.name ?? "",
      }));

    case LZ_WORKSPACE_ACTIONS.MARK_DIRTY:
      return updateDiagram(
        state,
        action.diagramId,
        (diagram) => ({ ...diagram, dirty: Boolean(action.dirty) }),
        { markDirty: false },
      );

    case LZ_WORKSPACE_ACTIONS.MARK_SAVED:
      return updateDiagram(
        state,
        action.diagramId,
        (diagram) => ({
          ...diagram,
          savedId: action.savedId ?? diagram.savedId,
          name: action.name ?? diagram.name,
          dirty: false,
        }),
        { markDirty: false },
      );

    case LZ_WORKSPACE_ACTIONS.CLEAR_SAVED:
      return updateDiagram(state, action.diagramId, (diagram) => ({
        ...diagram,
        savedId: null,
      }));

    default:
      return state;
  }
};

/**
 * Session-oriented API for the LZ/PZ workflow. The reducer keeps diagrams
 * separate even though the current map UI renders only one active diagram.
 */
export const useLzWorkspace = (initialWorkspace) => {
  const [state, dispatch] = useReducer(
    lzWorkspaceReducer,
    initialWorkspace,
    createInitialLzWorkspace,
  );

  const activeDiagram = useMemo(() => getActiveLzDiagram(state), [state]);
  const activeDiagramId = state.activeDiagramId;

  const startDiagram = useCallback((target, options = {}) => {
    const diagram = createLzDiagramFromTarget({ target, ...options });
    if (!diagram) return null;

    dispatch({ type: LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM, diagram });
    return diagram.id;
  }, []);

  const importDiagram = useCallback((diagram, { activate = true } = {}) => {
    if (!diagram) return null;
    const normalized = normalizeLzDiagram(diagram, { dirty: false });
    dispatch({
      type: LZ_WORKSPACE_ACTIONS.IMPORT_DIAGRAM,
      diagram: normalized,
      activate,
      dirty: false,
    });
    return normalized.id;
  }, []);

  const importLegacySnapshot = useCallback(
    (snapshot, options = {}) => {
      const diagram = normalizeLegacyLzSnapshot(snapshot, options);
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.IMPORT_DIAGRAM,
        diagram,
        activate: options.activate ?? true,
        dirty: false,
      });
      return diagram.id;
    },
    [],
  );

  const setActiveDiagram = useCallback((diagramId) => {
    dispatch({ type: LZ_WORKSPACE_ACTIONS.SET_ACTIVE_DIAGRAM, diagramId });
  }, []);

  const removeDiagram = useCallback((diagramId = activeDiagramId) => {
    if (!diagramId) return;
    dispatch({ type: LZ_WORKSPACE_ACTIONS.REMOVE_DIAGRAM, diagramId });
  }, [activeDiagramId]);

  const setAnalysisDraft = useCallback(
    (analysis, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_ANALYSIS_DRAFT,
        diagramId,
        analysis,
      });
    },
    [activeDiagramId],
  );

  const setRuntimeTerrainData = useCallback(
    (terrainData, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_RUNTIME_TERRAIN_DATA,
        diagramId,
        terrainData,
      });
    },
    [activeDiagramId],
  );

  const completeAnalysis = useCallback(
    (analysis, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS,
        diagramId,
        analysis,
      });
    },
    [activeDiagramId],
  );

  const resetAnalysis = useCallback((diagramId = activeDiagramId) => {
    if (!diagramId) return;
    dispatch({ type: LZ_WORKSPACE_ACTIONS.RESET_ANALYSIS, diagramId });
  }, [activeDiagramId]);

  const setGraphics = useCallback(
    (graphics, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_GRAPHICS,
        diagramId,
        graphics,
      });
    },
    [activeDiagramId],
  );

  const setGraphicCollection = useCallback(
    (collection, items, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
        diagramId,
        collection,
        items,
      });
    },
    [activeDiagramId],
  );

  const upsertGraphic = useCallback(
    (collection, item, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.UPSERT_GRAPHIC,
        diagramId,
        collection,
        item,
      });
    },
    [activeDiagramId],
  );

  const patchGraphic = useCallback(
    (collection, id, patch, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.PATCH_GRAPHIC,
        diagramId,
        collection,
        id,
        patch,
      });
    },
    [activeDiagramId],
  );

  const removeGraphic = useCallback(
    (collection, id, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.REMOVE_GRAPHIC,
        diagramId,
        collection,
        id,
      });
    },
    [activeDiagramId],
  );

  const setFlightData = useCallback(
    (flightData, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA,
        diagramId,
        flightData,
      });
    },
    [activeDiagramId],
  );

  const setView = useCallback(
    (view, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_VIEW,
        diagramId,
        view,
      });
    },
    [activeDiagramId],
  );

  const setDiagramName = useCallback(
    (name, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.SET_DIAGRAM_NAME,
        diagramId,
        name,
      });
    },
    [activeDiagramId],
  );

  const markSaved = useCallback(
    ({ savedId, name } = {}, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.MARK_SAVED,
        diagramId,
        savedId,
        name,
      });
    },
    [activeDiagramId],
  );

  const markDirty = useCallback(
    (dirty = true, diagramId = activeDiagramId) => {
      if (!diagramId) return;
      dispatch({
        type: LZ_WORKSPACE_ACTIONS.MARK_DIRTY,
        diagramId,
        dirty,
      });
    },
    [activeDiagramId],
  );

  const clearSaved = useCallback((diagramId = activeDiagramId) => {
    if (!diagramId) return;
    dispatch({ type: LZ_WORKSPACE_ACTIONS.CLEAR_SAVED, diagramId });
  }, [activeDiagramId]);

  const hydrateWorkspace = useCallback((workspace) => {
    dispatch({ type: LZ_WORKSPACE_ACTIONS.HYDRATE_WORKSPACE, workspace });
  }, []);

  const serializeActiveDiagram = useCallback(
    (options) => serializeLzDiagram(getActiveLzDiagram(state), options),
    [state],
  );

  const diagrams = useMemo(
    () => state.diagramOrder.map((id) => state.diagramsById[id]).filter(Boolean),
    [state],
  );

  return {
    state,
    workspace: state,
    diagrams,
    activeDiagram,
    activeDiagramId,
    hasActiveTarget: canAnalyzeLzDiagram(activeDiagram),
    canAnalyze: canAnalyzeLzDiagram(activeDiagram),
    canEditGraphics: canEditLzDiagramGraphics(activeDiagram),
    startDiagram,
    importDiagram,
    importLegacySnapshot,
    setActiveDiagram,
    removeDiagram,
    setAnalysisDraft,
    setRuntimeTerrainData,
    completeAnalysis,
    resetAnalysis,
    setGraphics,
    setGraphicCollection,
    upsertGraphic,
    patchGraphic,
    removeGraphic,
    setFlightData,
    setView,
    setDiagramName,
    markSaved,
    markDirty,
    clearSaved,
    hydrateWorkspace,
    serializeActiveDiagram,
    dispatch,
  };
};

export default useLzWorkspace;
