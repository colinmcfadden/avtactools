import { EMPTY_HISTORY, patchHistory, recordStep, redoStep, undoStep } from "../undo/history";

/*
 * Routes and, per set, what undo can take back: sketched routes filed by set, an imported mission's by
 * its file (`makeRouteReducer` is told which). A version is one set's routes. Whether a route is shown
 * is each person's view, never part of a version: an undo keeps it as it is now.
 *
 * `edit` is something a person did. `quiet` is fetched data (winds, ground), laid over every version so
 * undo never takes it back. `place` is a whole set put in from elsewhere (a mission pack's version).
 * `barrier` is a change no step can take back (a point spliced into a mission's file): what could be
 * undone in that set goes with it.
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

/** A reducer for routes filed into sets by `keyOf`. */
export const makeRouteReducer = (keyOf) => {
  const routesOfSet = (routes, key) => routes.filter((route) => keyOf(route) === key);

  /** `next` in place of the routes of set `key`, where the set sits in the list. */
  const placeRoutes = (routes, key, next) => {
    const at = routes.findIndex((route) => keyOf(route) === key);
    const others = routes.filter((route) => keyOf(route) !== key);
    if (at < 0) return [...others, ...next];
    const before = routes.slice(0, at).filter((route) => keyOf(route) !== key).length;
    return [...others.slice(0, before), ...next, ...others.slice(before)];
  };

  // A version put back keeps whichever routes are hidden now.
  const restoredRoutes = (routes, key, version) => {
    const visibility = new Map(routesOfSet(routes, key).map((route) => [route.id, route.visible]));
    return placeRoutes(
      routes,
      key,
      version.map((route) => (visibility.has(route.id) ? { ...route, visible: visibility.get(route.id) } : route)),
    );
  };

  return (state, action) => {
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
        const moved = (isUndo ? undoStep : redoStep)(historyOf(state, action.setKey), action.seq);
        if (!moved) return state;
        const version = isUndo ? moved.step.before : moved.step.after;
        return withHistory({ ...state, routes: restoredRoutes(state.routes, action.setKey, version) }, action.setKey, moved.history);
      }

      default:
        return state;
    }
  };
};

export const routeSketchReducer = makeRouteReducer(setKeyOf);
