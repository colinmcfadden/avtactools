/**
 * The live sandtable's steps, as plain data (docs/MISSION_BRIEF.md §2.8): what each step is called and what the map shows
 * for it, read from the compiled brief and nothing else, so the sandtable says what the PDF says. Pure; held to
 * contracts/fixtures/brief/sandtable.json, which Android's sandtable (B9) follows.
 *
 * What is drawn mirrors the PDF's figures (backend/brief/figures.py): a route is lit on a route or departure step when it
 * is the one in focus, the others dimmed on a route step; point names show on the lit route and on overview and rally
 * steps; the LZ/PZ in focus shows its boundary, aircraft and landing heading; threats always; rally points on the rally
 * step. A step with no figure shows the whole plan, unlit, over the brief's overview. A step is never called by a page:
 * where Chromium breaks the printed pages is not known here.
 */

/** The colours a figure draws in, the same as the PDF's (backend/brief/figures.py). */
export const FIGURE_COLOURS = {
  route: "#5aa7d4",
  focus: "#ffd166",
  casing: "#101317",
  aircraft: "#64f338",
  threat: "#e06a62",
  threatLabel: "#ffb3ad",
  rally: "#ffd166",
  roles: { LZ: "#ffd166", PZ: "#6fe3a5", FARP: "#f0b429", HA: "#c9d0d8", LAAGER: "#c9d0d8", FALSE_LZ: "#e0a0ff" },
};

export const roleColour = (role) => FIGURE_COLOURS.roles[role] ?? FIGURE_COLOURS.roles.LZ;

const validBounds = (b) =>
  Array.isArray(b) && b.length === 2 && b.every((c) => Array.isArray(c) && c.length === 2 && c.every((v) => typeof v === "number" && Number.isFinite(v)));

/** Where a step with no figure looks: the brief's first overview figure, else every figure's area together; null for none. */
export const overviewBounds = (model) => {
  const figures = (model?.steps ?? []).map((s) => s.figure).filter((f) => f && validBounds(f.bounds));
  const overview = figures.find((f) => f.kind === "overview");
  if (overview) return overview.bounds;
  if (figures.length === 0) return null;
  let [[s, w], [n, e]] = figures[0].bounds;
  figures.slice(1).forEach(({ bounds: [[s2, w2], [n2, e2]] }) => {
    s = Math.min(s, s2); w = Math.min(w, w2); n = Math.max(n, n2); e = Math.max(e, e2);
  });
  return [[s, w], [n, e]];
};

/**
 * The steps as the sandtable lists them: where each is in the brief ("Step 3 of 18"), its number, the section it belongs
 * to ("Landing sequence"), its title (the route or LZ/PZ it is about, else the section), who briefs it, and the planned
 * time of what it is about (a route's start, an LZ/PZ's arrival), when the plan has one.
 */
export const sandtableSteps = (model) => {
  const steps = model?.steps ?? [];
  const flow = model?.flow ?? [];
  return steps.map((step, i) => {
    const entry = step.ref ? flow.find((f) => f.ref === step.ref && !f.gone) ?? null : null;
    const instance = entry?.name ?? null;
    const suffix = instance ? ` · ${instance}` : "";
    const section = instance && step.title.endsWith(suffix) ? step.title.slice(0, -suffix.length) : step.title;
    return {
      id: step.id,
      position: `Step ${i + 1} of ${steps.length}`,
      number: step.number,
      section,
      title: instance ?? section,
      who: step.who,
      time: entry ? (entry.kind === "route" ? entry.start : entry.arrive) ?? null : null,
    };
  });
};

/**
 * What the map shows for a step: the area to fit, the kind of figure and the item in focus, and for each route and LZ/PZ
 * of the figure how it is drawn. Routes and LZ/PZs are named by ref; their lines, points and words are in the brief's
 * `geometry`. Null for a step that is not there.
 */
export const stepView = (model, index) => {
  const step = model?.steps?.[index];
  if (!step) return null;
  const figure = step.figure ?? null;
  const geometry = model.geometry ?? {};
  const allRoutes = geometry.routes ?? {};
  const allStops = geometry.stops ?? {};
  const kind = figure?.kind ?? null;
  const focus = figure?.ref ?? null;
  // A figure names what it shows; one that names nothing shows everything, as the PDF's does.
  const routeRefs = figure?.routes?.length ? figure.routes : Object.keys(allRoutes);
  const stopRefs = figure?.stops?.length ? figure.stops : Object.keys(allStops);
  return {
    stepId: step.id,
    kind,
    focus,
    bounds: figure ? (validBounds(figure.bounds) ? figure.bounds : null) : overviewBounds(model),
    routes: routeRefs.filter((ref) => allRoutes[ref]).map((ref) => {
      const lit = (kind === "route" || kind === "departure") && ref === focus;
      return { ref, lit, dimmed: !lit && kind === "route", labelled: lit || kind === "overview" || kind === "rally" };
    }),
    stops: stopRefs.filter((ref) => allStops[ref]).map((ref) => ({ ref, close: kind === "stop" && ref === focus })),
    rally: kind === "rally",
  };
};

/** The index of a step by id; else the same place in the list (a step that went), within it. */
export const indexOfStep = (model, id, fallback = 0) => {
  const steps = model?.steps ?? [];
  if (steps.length === 0) return -1;
  const found = steps.findIndex((s) => s.id === id);
  if (found >= 0) return found;
  return Math.min(Math.max(0, fallback), steps.length - 1);
};

/**
 * How far in from each edge of the map the figure is fitted, in pixels, so the script card and the constants box do not
 * cover it. `map` is the map's box; `covers` are the boxes laid over it ({left, top, right, bottom} on the page). Each box
 * still over the map that is left, the largest first, pushes in from the edge (top, bottom, left or right) that leaves the
 * most map: a card along the bottom pushes the bottom up, and a box in a corner then pushes in from the side. At least
 * `min` px of map is always left each way.
 */
export const fitInsets = (map, covers = [], { margin = 16, min = 160 } = {}) => {
  const insets = { top: margin, right: margin, bottom: margin, left: margin };
  if (!map || !(map.right > map.left) || !(map.bottom > map.top)) return insets;
  const width = map.right - map.left;
  const height = map.bottom - map.top;
  const areaLeft = (i) => Math.max(0, width - i.left - i.right) * Math.max(0, height - i.top - i.bottom);
  const overMap = covers
    .filter((box) => box && box.right > map.left && box.left < map.right && box.bottom > map.top && box.top < map.bottom)
    .sort((a, b) => (b.right - b.left) * (b.bottom - b.top) - (a.right - a.left) * (a.bottom - a.top));
  overMap.forEach((box) => {
    const free = { left: map.left + insets.left, right: map.right - insets.right, top: map.top + insets.top, bottom: map.bottom - insets.bottom };
    if (!(Math.min(box.right, free.right) > Math.max(box.left, free.left)) || !(Math.min(box.bottom, free.bottom) > Math.max(box.top, free.top))) return;
    const ways = [
      ["top", box.bottom - map.top + margin],
      ["bottom", map.bottom - box.top + margin],
      ["left", box.right - map.left + margin],
      ["right", map.right - box.left + margin],
    ].map(([side, inset]) => {
      const next = { ...insets, [side]: Math.max(insets[side], inset) };
      return { next, area: areaLeft(next) };
    });
    Object.assign(insets, ways.reduce((best, way) => (way.area > best.area ? way : best)).next);
  });
  const squeeze = (a, b, size) => {
    const over = insets[a] + insets[b] - Math.max(0, size - min);
    if (over <= 0) return;
    // Give back from the larger side first.
    const [big, small] = insets[a] >= insets[b] ? [a, b] : [b, a];
    const fromBig = Math.min(over, insets[big] - margin);
    insets[big] -= Math.max(0, fromBig);
    const rest = over - Math.max(0, fromBig);
    if (rest > 0) insets[small] = Math.max(0, insets[small] - rest);
  };
  squeeze("top", "bottom", height);
  squeeze("left", "right", width);
  return insets;
};
