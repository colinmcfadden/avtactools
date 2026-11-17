import { EMPTY_HISTORY, patchHistory, recordStep, redoStep, topRedo, topUndo, undoStep } from "../undo/history";

/*
 * Routes and, per set, what undo can take back: sketched routes filed by set, an imported mission's by
 * its file (`makeRouteReducer` is told which). A version is one set's routes. Whether a route is shown
 * is each person's view, never part of a version: an undo keeps it as it is now.
 *
 * `edit` is something a person did. `quiet` is fetched data (winds, ground), laid over every version so
 * undo never takes it back. `place` is a whole set put in from elsewhere (a mission pack's version).
 * `barrier` is a change no step can take back (a point spliced into a mission's file): what could be
 * undone in that set goes with it. `move` files a route under another set: one thing done to two
 * sets, so each set's history gets its half of the step, linked, and undo and redo take both halves
 * at once. A half is never taken alone, which would leave the route in both sets or in neither: when
 * one set's half is lost (its history dropped, the set closed), the other set's half goes too, with
 * what is older than it there, since those versions are of the set as it was before the route moved.
 */

export const SKETCHES = "sketches";

export const setKeyOf = (route) => route.setId ?? SKETCHES;

const plain = ({ visible: _visible, ...route }) => route;

export const sameRoutes = (a, b) =>
  a === b ||
  (a.length === b.length &&
    a.every((route, index) => route === b[index] || JSON.stringify(plain(route)) === JSON.stringify(plain(b[index]))));

const historyOf = (state, key) => state.history[key] ?? EMPTY_HISTORY;
const withHistory = (state, key, history) => ({ ...state, history: { ...state.history, [key]: history } });
const withoutHistory = (state, key) => {
  if (!state.history[key]) return state;
  const { [key]: _gone, ...history } = state.history;
  return { ...state, history };
};

export const INITIAL_SKETCH_STATE = Object.freeze({ routes: [], history: Object.freeze({}) });

const lastIndexWhere = (list, test) => {
  for (let i = list.length - 1; i >= 0; i -= 1) if (test(list[i])) return i;
  return -1;
};

/**
 * The histories with no half of a move whose other half is gone. In `undo` a half without its other
 * goes with everything under it (older); in `redo`, with everything that would be made again after it.
 */
export const withoutOrphans = (state) => {
  let history = state.history;
  for (;;) {
    const keys = Object.keys(history);
    const halves = (stack) => {
      const count = new Map();
      keys.forEach((key) => history[key][stack].forEach((step) => step.link !== undefined && count.set(step.link, (count.get(step.link) ?? 0) + 1)));
      return count;
    };
    const undoHalves = halves("undo");
    const redoHalves = halves("redo");
    let changed = false;
    const next = {};
    keys.forEach((key) => {
      const { undo, redo } = history[key];
      const lostUndo = lastIndexWhere(undo, (step) => step.link !== undefined && undoHalves.get(step.link) < 2);
      const lostRedo = lastIndexWhere(redo, (step) => step.link !== undefined && redoHalves.get(step.link) < 2);
      if (lostUndo < 0 && lostRedo < 0) {
        next[key] = history[key];
        return;
      }
      changed = true;
      next[key] = { undo: lostUndo < 0 ? undo : undo.slice(lostUndo + 1), redo: lostRedo < 0 ? redo : redo.slice(lostRedo + 1) };
    });
    if (!changed) return history === state.history ? state : { ...state, history };
    history = next;
  }
};

/**
 * A reducer for routes filed into sets by `keyOf`. `fileUnder(route, key)` is the route as the set
 * `key` keeps it, for sets a route can move between.
 */
export const makeRouteReducer = (keyOf, fileUnder = (route) => route) => {
  const routesOfSet = (routes, key) => routes.filter((route) => keyOf(route) === key);

  /** `next` in place of the routes of set `key`, where the set sits in the list. */
  const placeRoutes = (routes, key, next) => {
    const at = routes.findIndex((route) => keyOf(route) === key);
    const others = routes.filter((route) => keyOf(route) !== key);
    if (at < 0) return [...others, ...next];
    const before = routes.slice(0, at).filter((route) => keyOf(route) !== key).length;
    return [...others.slice(0, before), ...next, ...others.slice(before)];
  };

  const reduce = (state, action) => {
    switch (action.type) {
      case "edit": {
        const route = action.routeId !== undefined ? state.routes.find((r) => r.id === action.routeId) : null;
        const key = route ? keyOf(route) : action.setKey;
        if (key === undefined) return state;
        const routes = action.update(state.routes);
        const history = historyOf(state, key);
        const top = history.undo[history.undo.length - 1];
        const before = routesOfSet(state.routes, key);
        return withHistory(
          { ...state, routes },
          key,
          recordStep(
            history,
            {
              label: action.step.label,
              before: top && sameRoutes(top.after, before) ? top.after : before,
              after: routesOfSet(routes, key),
              key: action.step.key ?? null,
              seq: action.step.seq,
            },
            sameRoutes,
          ),
        );
      }

      case "quiet": {
        const route = state.routes.find((r) => r.id === action.routeId);
        if (!route) return state;
        const key = keyOf(route);
        const change = (r) => (r.id === action.routeId ? action.change(r) : r);
        return withHistory(
          { ...state, routes: state.routes.map(change) },
          key,
          patchHistory(historyOf(state, key), (version) => version.map(change), sameRoutes),
        );
      }

      case "visible":
        return {
          ...state,
          routes: state.routes.map((route) => (route.id === action.routeId ? { ...route, visible: !route.visible } : route)),
        };

      // New routes opened into a set: what could be undone there was about what was there before.
      case "append": {
        const keys = new Set(action.routes.map(keyOf));
        const history = Object.fromEntries(Object.entries(state.history).filter(([key]) => !keys.has(key)));
        return { routes: [...state.routes, ...action.routes], history };
      }

      case "place": {
        const key = action.setId;
        const hidden = new Set(routesOfSet(state.routes, key).filter((r) => r.visible === false).map((r) => r.id));
        const next = action.routes.map((route) => (hidden.has(route.id) ? { ...route, visible: false } : route));
        const placed = { ...state, routes: placeRoutes(state.routes, key, next) };
        if (action.dropHistory) return withoutHistory(placed, key);
        if (!action.rebase) return placed;
        return withHistory(placed, key, patchHistory(historyOf(state, key), action.rebase, sameRoutes));
      }

      // A route filed under another set (`toKey`'s).
      case "move": {
        // `fromKey` says which, where two sets hold a route of one id (a record copied into a pack twice).
        const route = state.routes.find((r) => r.id === action.routeId && (action.fromKey === undefined || keyOf(r) === action.fromKey));
        if (!route) return state;
        const fromKey = keyOf(route);
        const toKey = action.toKey;
        if (fromKey === toKey) return state;
        const moved = fileUnder(route, toKey);
        // After the routes the set already has, where the set sits in the list; a new set goes last.
        const rest = state.routes.filter((r) => r !== route);
        const last = rest.reduce((at, r, index) => (keyOf(r) === toKey ? index : at), -1);
        const routes = last < 0 ? [...rest, moved] : [...rest.slice(0, last + 1), moved, ...rest.slice(last + 1)];
        const half = (current, key) => {
          const history = historyOf(current, key);
          const top = history.undo[history.undo.length - 1];
          const before = routesOfSet(state.routes, key);
          return withHistory(
            current,
            key,
            recordStep(
              history,
              { label: action.step.label, before: top && sameRoutes(top.after, before) ? top.after : before, after: routesOfSet(routes, key), seq: action.step.seq, link: action.step.seq },
              sameRoutes,
            ),
          );
        };
        return half(half({ ...state, routes }, fromKey), toKey);
      }

      case "removeSet": {
        const routes = state.routes.filter((route) => keyOf(route) !== action.setKey);
        return withoutHistory({ ...state, routes }, action.setKey);
      }

      case "barrier":
        return withoutHistory({ ...state, routes: action.update(state.routes) }, action.setKey);

      case "clear":
        return INITIAL_SKETCH_STATE;

      case "undo":
      case "redo": {
        const isUndo = action.type === "undo";
        const topOf = (key) => (isUndo ? topUndo : topRedo)(historyOf(state, key));
        const step = topOf(action.setKey);
        if (!step) return state;
        // A route moved between two sets is taken back, and moved again, in both at once.
        const keys = step.link === undefined ? [action.setKey] : Object.keys(state.history).filter((key) => topOf(key)?.link === step.link);
        if (step.link !== undefined && keys.length !== 2) return state;
        const live = new Map(keys.map((key) => [key, new Map(routesOfSet(state.routes, key).map((route) => [route.id, route]))]));
        return keys.reduce((current, key) => {
          const moved = (isUndo ? undoStep : redoStep)(historyOf(current, key), action.seq);
          const version = (isUndo ? moved.step.before : moved.step.after).map((route) => {
            // A version put back keeps whichever routes are hidden now.
            const here = live.get(key).get(route.id);
            if (here) return { ...route, visible: here.visible };
            // The route coming back from the other set comes as it is there now: what was done to it since it
            // moved (someone else's change in a pack, fetched winds) is in that set's versions, not this one's.
            const there = keys.map((other) => (other === key ? undefined : live.get(other).get(route.id))).find(Boolean);
            return there ? fileUnder(there, key) : route;
          });
          return withHistory({ ...current, routes: placeRoutes(current.routes, key, version) }, key, moved.history);
        }, state);
      }

      default:
        return state;
    }
  };

  // Which routes are shown changes no history; everything else may have cost a move one of its halves.
  return (state, action) => (action.type === "visible" ? reduce(state, action) : withoutOrphans(reduce(state, action)));
};

/** A sketched route under the set `key`: `setId`, none for this session's own sketches. */
const sketchFiledUnder = ({ setId: _filed, ...route }, key) => (key === SKETCHES ? route : { ...route, setId: key });

export const routeSketchReducer = makeRouteReducer(setKeyOf, sketchFiledUnder);
