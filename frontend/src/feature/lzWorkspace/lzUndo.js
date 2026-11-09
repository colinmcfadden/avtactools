import { flightDataFromDoghouses } from "../doghouses/doghouseFields";
import { EMPTY_HISTORY, patchHistory, recordStep, redoStep, undoStep } from "../undo/history";

/*
 * Undo and redo for the LZ/PZ workspace: one history per diagram, in `state.history`, kept beside the
 * reducer the fixtures hold (contracts/fixtures/workspace/ops.json), which knows nothing of it.
 *
 * A version is what a person changes of a diagram: its status, flight data, analysis and graphics.
 * Never the slope raster (redrawn from the boundary), the capture area, the base map or the other view
 * choices. An action carrying `step` ({ seq, key, label }) is something a person did. Any other that
 * changes a version (an analysis, the default doghouses, someone else's change) is laid over every
 * version instead, so undo never takes it back.
 */

export const LZ_UNDO_ACTIONS = Object.freeze({
  UNDO: "lzWorkspace/undo",
  REDO: "lzWorkspace/redo",
});

const NOUNS = {
  helicopters: "helicopter",
  doghouses: "doghouse",
  pzMarkers: "PZ marker",
  sectorsOfFire: "sector of fire",
  goArounds: "go-around",
  units: "unit",
  measurements: "LZ measurement line",
};

const POSITION_KEYS = ["lat", "lon", "lng", "position", "points", "center", "start", "end", "tip", "anchor", "corner", "lengthTip", "widthTip"];
const TURN_KEYS = ["heading", "rotation", "direction", "bearing"];

export const lzVersionOf = (diagram) => {
  const { terrainData: _raster, ...analysis } = diagram.analysis;
  const { exportBox: _capture, ...graphics } = diagram.graphics;
  return { status: diagram.status, flightData: diagram.flightData, analysis, graphics };
};

const json = (value) => JSON.stringify(value);
const same = (a, b) => a === b || json(a) === json(b);

// Section by section, because a version shares most of its sections with the one before it.
const sameSections = (a, b) => {
  if (a === b) return true;
  return [...new Set([...Object.keys(a), ...Object.keys(b)])].every((key) => same(a[key], b[key]));
};

export const sameLzVersion = (a, b) =>
  a === b ||
  (a.status === b.status &&
    same(a.flightData, b.flightData) &&
    sameSections(a.analysis, b.analysis) &&
    sameSections(a.graphics, b.graphics));

// The slope raster is drawn for `detectedLZ`. Keeping that object where it has not changed keeps the raster
// and spares useTerrain, which measures again whenever the object is another.
const restored = (diagram, version) => {
  const sameDetected = same(diagram.analysis.detectedLZ, version.analysis.detectedLZ);
  return {
    ...diagram,
    status: version.status,
    flightData: version.flightData,
    analysis: {
      ...version.analysis,
      detectedLZ: sameDetected ? diagram.analysis.detectedLZ : version.analysis.detectedLZ,
      terrainData: sameDetected ? diagram.analysis.terrainData : null,
    },
    graphics: { ...version.graphics, exportBox: diagram.graphics.exportBox },
    dirty: true,
    updatedAt: new Date().toISOString(),
  };
};

const changedList = (before, after) => {
  const was = new Map(before.filter((e) => e?.id !== undefined).map((e) => [e.id, e]));
  const now = new Map(after.filter((e) => e?.id !== undefined).map((e) => [e.id, e]));
  if ([...now.keys()].some((id) => !was.has(id))) return "Add";
  if ([...was.keys()].some((id) => !now.has(id))) return "Delete";
  const keys = new Set();
  now.forEach((element, id) => {
    const old = was.get(id);
    Object.keys({ ...old, ...element }).forEach((key) => {
      if (!same(old[key], element[key])) keys.add(key);
    });
  });
  const list = [...keys];
  if (list.some((key) => POSITION_KEYS.includes(key))) return "Move";
  if (list.some((key) => TURN_KEYS.includes(key))) return "Turn";
  return "Edit";
};

/** What a step did, in words, for the toast and the buttons' titles. */
export const describeLzStep = (before, after) => {
  const collection = Object.keys(NOUNS).find((name) => !same(before.graphics[name], after.graphics[name]));
  if (collection) {
    const lists = [before.graphics[collection] ?? [], after.graphics[collection] ?? []];
    return `${changedList(lists[0], lists[1])} ${NOUNS[collection]}`;
  }
  if (!same(before.analysis.customLZ, after.analysis.customLZ) || before.status !== after.status) {
    return after.analysis.customLZ ? "Draw boundary" : "Delete boundary";
  }
  return "Change";
};

const historyOf = (state, diagramId) => state.history?.[diagramId] ?? EMPTY_HISTORY;

const withHistory = (state, diagramId, history) => ({
  ...state,
  history: { ...state.history, [diagramId]: history },
});

const withoutHistory = (state, diagramId) => {
  if (!state.history?.[diagramId]) return state;
  const { [diagramId]: _gone, ...history } = state.history;
  return { ...state, history };
};

// What a change that is nobody's step does to a version, or null if it leaves versions alone.
const quietChange = (action, ACTIONS, diagram) => {
  const { terrainData: _raster, ...analysis } = diagram.analysis;
  switch (action.type) {
    case ACTIONS.COMPLETE_ANALYSIS:
    case ACTIONS.RESET_ANALYSIS:
      return (version) => ({ ...version, status: diagram.status, analysis });
    case ACTIONS.SET_ANALYSIS_DRAFT: {
      const { terrainData: _none, ...fields } = action.analysis ?? {};
      return (version) => ({ ...version, analysis: { ...version.analysis, ...fields } });
    }
    case ACTIONS.SET_GRAPHIC_COLLECTION:
      // The default doghouses an analysis makes: only where a version has none.
      return (version) =>
        version.graphics[action.collection]?.length
          ? version
          : { ...version, graphics: { ...version.graphics, [action.collection]: diagram.graphics[action.collection] } };
    case ACTIONS.APPLY_REMOTE_DIAGRAM:
      return action.rebase ?? null;
    default:
      return null;
  }
};

// A doghouse and the heading it gives are one step: the flight data is settled here, so useDoghouses'
// effect finds nothing left to change and cannot add a step or wipe redo after an undo.
const tidy = (diagram, previous) => {
  if (same(diagram.graphics.doghouses, previous.graphics.doghouses)) return diagram;
  const flightData = flightDataFromDoghouses(diagram.graphics.doghouses, diagram.flightData);
  return same(flightData, diagram.flightData) ? diagram : { ...diagram, flightData };
};

/** `reducer` (the workspace's) with one history per diagram. */
export const withLzUndo = (reducer, ACTIONS) => (state, action) => {
  if (action.type === LZ_UNDO_ACTIONS.UNDO || action.type === LZ_UNDO_ACTIONS.REDO) {
    const diagram = state.diagramsById[action.diagramId];
    if (!diagram) return state;
    const isUndo = action.type === LZ_UNDO_ACTIONS.UNDO;
    const moved = (isUndo ? undoStep : redoStep)(historyOf(state, diagram.id), action.seq);
    if (!moved) return state;
    const next = withHistory(state, diagram.id, moved.history);
    return {
      ...next,
      diagramsById: { ...next.diagramsById, [diagram.id]: restored(diagram, isUndo ? moved.step.before : moved.step.after) },
    };
  }

  const next = reducer(state, action);

  if (action.type === ACTIONS.HYDRATE_WORKSPACE) {
    // Parking a person's workspace for a pack carries its history out and back.
    const merged = { ...state.history, ...action.workspace?.history };
    return {
      ...next,
      history: Object.fromEntries(Object.entries(merged).filter(([id]) => next.diagramsById[id])),
    };
  }
  if (action.type === ACTIONS.REMOVE_DIAGRAM) return withoutHistory(next, action.diagramId);
  if (next === state) return state;

  const previous = state.diagramsById[action.diagramId];
  const diagram = next.diagramsById?.[action.diagramId];
  if (!previous || !diagram) return next;

  if (!action.step) {
    if (action.dropHistory) return withoutHistory(next, action.diagramId);
    const history = historyOf(next, diagram.id);
    const change = quietChange(action, ACTIONS, diagram);
    return change ? withHistory(next, diagram.id, patchHistory(history, change, sameLzVersion)) : next;
  }

  const tidied = tidy(diagram, previous);
  const settled = tidied === diagram ? next : { ...next, diagramsById: { ...next.diagramsById, [diagram.id]: tidied } };
  const before = lzVersionOf(previous);
  const after = lzVersionOf(tidied);
  const history = historyOf(next, diagram.id);
  const top = history.undo[history.undo.length - 1];
  return withHistory(
    settled,
    diagram.id,
    recordStep(
      history,
      {
        label: action.step.label ?? describeLzStep(before, after),
        // One object where the last step ended, so laying a change over the history changes it once.
        before: top && sameLzVersion(top.after, before) ? top.after : before,
        after,
        key: action.step.key ?? null,
        seq: action.step.seq,
      },
      sameLzVersion,
    ),
  );
};
