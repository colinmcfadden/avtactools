import { flotsFromData, savedFlots } from "../flot/flots";
import { restoreSketchRoute } from "../msnxImport/useRouteSketch";
import { sameData } from "./packDiff";

/*
 * A set of sketched routes as a mission pack item. Its data is what the library saves for sketched
 * routes ({ version: 1, routes }, with `flots` beside them when it has any), less what is each
 * person's own: which routes they have hidden, and the set the sketch files them under here
 * (`setId`: packLocalId, the pack and the item).
 */

const OWN_ROUTE_FIELDS = ["visible", "setId"];

const withoutOwn = (route) => {
  const copy = { ...route };
  OWN_ROUTE_FIELDS.forEach((key) => delete copy[key]);
  return copy;
};

/** A set of routes, as the sketch keeps them, as its pack item has them. */
export const routeSetData = (routes, flots = []) =>
  JSON.parse(JSON.stringify({ version: 1, ...(flots?.length ? { flots: savedFlots(flots) } : {}), routes: (routes ?? []).map(withoutOwn) }));

/** The shared part of a route item's raw data. */
export const sharedRouteData = (data) => {
  const copy = JSON.parse(JSON.stringify(data ?? {}));
  if (Array.isArray(copy.routes)) copy.routes = copy.routes.map((route) => (route && typeof route === "object" ? withoutOwn(route) : route));
  return copy;
};

/** The routes of a pack item as the sketch keeps them, in the set `setId`. */
export const routesFromItem = (item, setId) =>
  (Array.isArray(item?.data?.routes) ? item.data.routes : [])
    .filter((route) => route && typeof route === "object" && route.id !== undefined)
    .map((route) => restoreSketchRoute(route, setId));

/** The FLOTs of a pack item. */
export const flotsFromItem = (item) => flotsFromData(item?.data);

// -- What a change was, in words, for the pack's history -----------------------------------

const byId = (list) => new Map((Array.isArray(list) ? list : []).filter((e) => e && e.id !== undefined).map((e) => [e.id, e]));

const pointName = (point) => point?.name || "a point";

/**
 * One sentence for the pack's history about a change to a route set ("Sam B. moved .RP on RED 1.").
 * `before` and `after` are the set's shared data; `name` is the set's.
 */
export const describeRouteChange = (before, after, { name, actor }) => {
  const set = name || "the routes";
  const phrases = [];
  const was = byId(before?.routes);
  const now = byId(after?.routes);
  // A route that is gone, with its points now another route's, was not removed: it was joined onto
  // that route (routeSetOps.combineRoutes), all of its points or all but the first, where the two met.
  const taken = new Map(); // the route that grew -> { route: the one joined onto it, points: the ids that came over }
  const joined = new Set(); // the routes joined onto another
  was.forEach((gone, goneId) => {
    if (now.has(goneId)) return;
    const goneIds = [...byId(gone.points).keys()];
    if (goneIds.length === 0) return;
    for (const [id, route] of now) {
      const old = was.get(id);
      if (!old || taken.has(id)) continue;
      const oldPoints = byId(old.points);
      const newPoints = byId(route.points);
      const carried = goneIds.filter((pointId) => newPoints.has(pointId) && !oldPoints.has(pointId));
      if (carried.length > 0 && carried.length >= goneIds.length - 1) {
        taken.set(id, { route: gone, points: new Set(carried) });
        joined.add(goneId);
        break;
      }
    }
  });
  now.forEach((route, id) => {
    const label = route.name || "a route";
    const old = was.get(id);
    if (!old) {
      phrases.push(`added the route ${label} to ${set}`);
      return;
    }
    const onto = taken.get(id);
    if (onto) phrases.push(`combined ${onto.route.name ? `the route ${onto.route.name}` : "a route"} into ${label}`);
    if (old.name !== route.name) phrases.push(old.name ? `renamed the route ${old.name} to ${label}` : `named a route ${label}`);
    if (old.color !== route.color) phrases.push(`changed the colour of ${label}`);
    const oldPoints = byId(old.points);
    const newPoints = byId(route.points);
    newPoints.forEach((point, pointId) => {
      const previous = oldPoints.get(pointId);
      if (!previous) {
        if (!onto?.points.has(pointId)) phrases.push(`added ${pointName(point)} to ${label}`);
      } else if (previous.lat !== point.lat || previous.lon !== point.lon) phrases.push(`moved ${pointName(point)} on ${label}`);
      else if (!sameData(previous, point)) phrases.push(`changed ${pointName(point)} on ${label}`);
    });
    oldPoints.forEach((point, pointId) => {
      if (!newPoints.has(pointId)) phrases.push(`removed ${pointName(point)} from ${label}`);
    });
    if (!sameData(old.plan, route.plan)) phrases.push(`changed the plan of ${label}`);
    if (!sameData(old.elevations, route.elevations)) phrases.push(`updated the ground elevations of ${label}`);
  });
  was.forEach((route, id) => {
    if (!now.has(id) && !joined.has(id)) phrases.push(route.name ? `removed the route ${route.name} from ${set}` : `removed a route from ${set}`);
  });
  const flotsBefore = byId(before?.flots);
  const flotsAfter = byId(after?.flots);
  flotsAfter.forEach((flot, id) => {
    const old = flotsBefore.get(id);
    if (!old) phrases.push(`added a FLOT to ${set}`);
    else if (Boolean(old.flipped) !== Boolean(flot.flipped)) phrases.push(`flipped a FLOT on ${set}`);
    else if (!sameData(old.points, flot.points)) phrases.push(`moved a FLOT on ${set}`);
  });
  flotsBefore.forEach((_flot, id) => {
    if (!flotsAfter.has(id)) phrases.push(`removed a FLOT from ${set}`);
  });
  const what = phrases[0] ?? `edited ${set}`;
  return `${actor || "Someone"} ${what}.`.slice(0, 300);
};
