import { restoreSketchRoute } from "../msnxImport/useRouteSketch";
import { sameData } from "./packDiff";

/*
 * A set of sketched routes as a mission pack item. Its data is what the library saves for sketched
 * routes ({ version: 1, routes }), less what is each person's own: which routes they have hidden,
 * and the set the sketch files them under here (`setId`: packLocalId, the pack and the item).
 */

const OWN_ROUTE_FIELDS = ["visible", "setId"];

const withoutOwn = (route) => {
  const copy = { ...route };
  OWN_ROUTE_FIELDS.forEach((key) => delete copy[key]);
  return copy;
};

/** A set of routes, as the sketch keeps them, as its pack item has them. */
export const routeSetData = (routes) => JSON.parse(JSON.stringify({ version: 1, routes: (routes ?? []).map(withoutOwn) }));

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
  now.forEach((route, id) => {
    const label = route.name || "a route";
    const old = was.get(id);
    if (!old) {
      phrases.push(`added the route ${label} to ${set}`);
      return;
    }
    if (old.name !== route.name) phrases.push(old.name ? `renamed the route ${old.name} to ${label}` : `named a route ${label}`);
    const oldPoints = byId(old.points);
    const newPoints = byId(route.points);
    newPoints.forEach((point, pointId) => {
      const previous = oldPoints.get(pointId);
      if (!previous) phrases.push(`added ${pointName(point)} to ${label}`);
      else if (previous.lat !== point.lat || previous.lon !== point.lon) phrases.push(`moved ${pointName(point)} on ${label}`);
      else if (!sameData(previous, point)) phrases.push(`changed ${pointName(point)} on ${label}`);
    });
    oldPoints.forEach((point, pointId) => {
      if (!newPoints.has(pointId)) phrases.push(`removed ${pointName(point)} from ${label}`);
    });
    if (!sameData(old.plan, route.plan)) phrases.push(`changed the plan of ${label}`);
    if (!sameData(old.elevations, route.elevations)) phrases.push(`updated the ground elevations of ${label}`);
  });
  was.forEach((route, id) => {
    if (!now.has(id)) phrases.push(route.name ? `removed the route ${route.name} from ${set}` : `removed a route from ${set}`);
  });
  const what = phrases[0] ?? `edited ${set}`;
  return `${actor || "Someone"} ${what}.`.slice(0, 300);
};
