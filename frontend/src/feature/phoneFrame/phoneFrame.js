/*
 * The phone's frame (850 px wide and below): what the map's tool rail offers at each step of an LZ/PZ, how much of
 * it fits, and which tap on the map puts the frame away. Pure, so the rules are held by tests and not by a screen.
 *
 * The order is the order the app is used in (the owner, 2026-11-11): the route tool and Set Target; then, with a
 * target, Analyze and Draw LZ; then, analysed, the planning tools. The gate itself is the app's own (planning tools
 * are off until an LZ/PZ is analysed, the route tool never is); the rail only leaves out what cannot be used yet.
 * AGENTS.md §17 has the Android app's answers to the same questions (the LZ tool rail, the phone's frame hidden).
 */

export const DRAWING_ALREADY = "Finish or cancel what you are drawing on the map first.";
export const FINISH_BOUNDARY_FIRST = "Finish the LZ/PZ boundary first.";

export const STAGE = Object.freeze({
  NONE: "none",
  TARGETED: "targeted",
  DRAWING: "drawing",
  ANALYZED: "analyzed",
});

/** How far along the active LZ/PZ is, as the rail sees it. */
export const railStage = ({ hasTarget = false, analyzed = false, drawingBoundary = false } = {}) => {
  if (!hasTarget) return STAGE.NONE;
  if (drawingBoundary) return STAGE.DRAWING;
  return analyzed ? STAGE.ANALYZED : STAGE.TARGETED;
};

/**
 * The rail's tools, in order. Each is { key, label, hint, enabled, pressed?, wide?, tone? }:
 * `label` is its name (and its words where it is `wide`), `hint` what it does or why it is off, `pressed` a mode
 * that is on, `wide` a step said in words (the next thing to do), `tone` "primary" for that next step and
 * "drawing" for the boundary being drawn.
 *
 * Nothing at all where nothing may be changed (a finished Mission Pack, or a viewer of one), as on Android: the
 * pack's banner and panels say why.
 */
export const railTools = ({
  features = {},
  hasTarget = false,
  analyzed = false,
  editable = true,
  drawingBoundary = false,
  sketchingRoute = false,
  drawingBackstop = false,
  drawingFlot = false,
} = {}) => {
  if (!editable) return [];
  const can = (key) => features[key] !== false;
  const stage = railStage({ hasTarget, analyzed, drawingBoundary });
  const drawing = drawingBoundary || sketchingRoute || drawingBackstop || drawingFlot;
  // Once analysed the steps are behind the person: they stay, as icons, beside the tools they opened.
  const wide = stage !== STAGE.ANALYZED;
  const tools = [];

  if (can("routes")) {
    const free = sketchingRoute || !drawing;
    tools.push({
      key: "route",
      label: sketchingRoute ? "End route" : "Sketch route",
      hint: sketchingRoute ? "End the route" : free ? "Sketch a route on the map" : DRAWING_ALREADY,
      enabled: free,
      pressed: sketchingRoute,
    });
  }
  if (!can("lz_pz_tools")) return tools;

  tools.push({
    key: "target",
    label: "Set Target",
    hint: drawing ? DRAWING_ALREADY : hasTarget ? "Start a new LZ/PZ at the crosshair" : "Set the target at the crosshair",
    enabled: !drawing,
    wide,
    ...(stage === STAGE.NONE ? { tone: "primary" } : {}),
  });
  if (stage === STAGE.NONE) return tools;

  tools.push({
    key: "analyze",
    label: analyzed ? "Re-analyze" : "Analyze",
    hint: drawingBoundary ? FINISH_BOUNDARY_FIRST : analyzed ? "Analyze the active LZ/PZ again" : "Analyze the active LZ/PZ",
    enabled: !drawingBoundary,
    wide,
    ...(stage === STAGE.TARGETED ? { tone: "primary" } : {}),
  });
  const otherDrawing = drawing && !drawingBoundary;
  tools.push({
    key: "draw",
    label: drawingBoundary ? "Finish LZ" : "Draw LZ",
    hint: otherDrawing
      ? DRAWING_ALREADY
      : drawingBoundary
        ? "Tap the map for each corner, then finish"
        : "Draw the LZ/PZ boundary by hand",
    enabled: !otherDrawing,
    pressed: drawingBoundary,
    wide,
    ...(drawingBoundary ? { tone: "drawing" } : {}),
  });
  if (stage !== STAGE.ANALYZED) return tools;

  const backstopFree = drawingBackstop || !drawing;
  tools.push(
    { key: "helo", label: "Add helicopter", hint: "Add a helicopter", enabled: true },
    { key: "pz", label: "Add PZ marker", hint: "Add a PZ marker", enabled: true },
    { key: "sector", label: "Add sector of fire", hint: "Add a sector of fire", enabled: true },
    {
      key: "backstop",
      label: drawingBackstop ? "Finish backstop" : "Draw a backstop",
      hint: backstopFree ? "Draw a backstop" : DRAWING_ALREADY,
      enabled: backstopFree,
      pressed: drawingBackstop,
    },
    { key: "unit", label: "Add unit", hint: "Add a unit", enabled: true },
    { key: "goAround", label: "Add go-around", hint: "Add a go-around", enabled: true },
  );
  return tools;
};

/** The tools as the rail's rows: a step said in words has a row to itself, icons go two to a row. */
export const railRows = (tools) => {
  const rows = [];
  let open = null;
  tools.forEach((tool) => {
    if (tool.wide) {
      open = null;
      rows.push([tool]);
    } else if (open) {
      open.push(tool);
      open = null;
    } else {
      open = [tool];
      rows.push(open);
    }
  });
  return rows;
};

// The rail's measures in CSS pixels; phoneFrame.css reads them as custom properties, so there is one of each.
// `size` is a button (a finger's reach, and a glove's), `top` the room kept under the search bar, `foot` the room
// the row under the rail takes (its offset from the map's bottom, the hide and show button, a gap).
export const RAIL = Object.freeze({ size: 48, gap: 6, top: 8, foot: 84 });

/** How many of the rail's rows a map `mapHeight` tall has room for; all of them where it cannot be measured. */
export const railCapacity = (mapHeight) => {
  if (!Number.isFinite(mapHeight)) return Infinity;
  return Math.max(0, Math.floor((mapHeight - RAIL.top - RAIL.foot + RAIL.gap) / (RAIL.size + RAIL.gap)));
};

/**
 * The rows that are drawn where only `capacity` fit, and the tools that go behind a More button instead (which
 * takes one of the places). A row whose mode is on is kept first, then the latest steps, which are the ones in
 * use: what gives way is what the person is done with. Where not even one row fits, nothing is offered.
 */
export const railFit = (rows, capacity) => {
  if (rows.length <= capacity) return { rows, more: [] };
  if (capacity < 1) return { rows: [], more: [] };
  const on = (row) => (row.some((tool) => tool.pressed) ? 1 : 0);
  const kept = new Set(
    rows
      .map((row, index) => ({ row, index }))
      .sort((a, b) => on(b.row) - on(a.row) || b.index - a.index)
      .slice(0, capacity - 1)
      .map(({ index }) => index),
  );
  return {
    rows: rows.filter((_, index) => kept.has(index)),
    more: rows.filter((_, index) => !kept.has(index)).flat(),
  };
};

/**
 * The LZ card's two buttons, in their order too: Capture once there is something to export (an analysed LZ/PZ,
 * which a read-only pack may still export), Export once a capture area is set.
 */
export const cardTools = ({ features = {}, canExport = false, hasCaptureArea = false } = {}) => {
  const offered = features.exports !== false && canExport;
  return { capture: offered, export: offered && hasCaptureArea };
};

/** Undo and redo stand on the map only while there is something to take back or put back. */
export const undoShown = (undo) => Boolean(undo && (undo.canUndo || undo.canRedo));

/**
 * What a tap on the map does to the phone's frame, from how things stood as the finger came down:
 *   "none"   it is not the frame's: no phone, a brief presented, something being drawn with taps (the tap is a
 *            point), the search open (it folds itself as it loses the focus, and the tap does nothing else), or
 *            a tap that put something away (a menu on the map, a popup, the Layers list, a tool's choices);
 *   "menu"   it closes the open menu, and nothing else;
 *   "frame"  it hides the frame, or shows it again.
 */
export const frameTap = ({
  phone = false,
  presenting = false,
  drawing = false,
  searchOpen = false,
  menuOpen = false,
  dismissed = false,
} = {}) => {
  if (!phone || presenting || drawing || searchOpen) return "none";
  if (menuOpen) return "menu";
  return dismissed ? "none" : "frame";
};

/** Whether the frame is put away: only on a phone, and never while something is drawn or a brief presented. */
export const frameHidden = ({ phone = false, hidden = false, drawing = false, presenting = false } = {}) =>
  phone && hidden && !drawing && !presenting;

// What a tap on the map puts away before it is anything else (frameTap's `dismissed`): the app's menus (portaled
// to the body: a tool's choices and More among them), the map's own menu, a Leaflet popup and the Layers list.
export const DISMISSED_BY_A_TAP = ".ui-menu, .ctx-menu, .leaflet-popup, .map-style-panel";
