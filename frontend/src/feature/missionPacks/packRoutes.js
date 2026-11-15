import { flotsFromData, savedFlots } from "../flot/flots";
import { phaseLineLabel, phaseLinesFromData, savedPhaseLines } from "../phaseLines/phaseLines";
import { restoreSketchRoute } from "../msnxImport/useRouteSketch";
import { sameData } from "./packDiff";

/*
 * A set of sketched routes as a mission pack item. Its data is what the library saves for sketched
 * routes ({ version: 1, routes }, with `flots` and `phaseLines` beside them when it has any), less what is each
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
export const routeSetData = (routes, flots = [], phaseLines = []) =>
  JSON.parse(
    JSON.stringify({
      version: 1,
      ...(flots?.length ? { flots: savedFlots(flots) } : {}),
      ...(phaseLines?.length ? { phaseLines: savedPhaseLines(phaseLines) } : {}),
      routes: (routes ?? []).map(withoutOwn),
    }),
  );

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

/** The phase lines of a pack item. */
export const phaseLinesFromItem = (item) => phaseLinesFromData(item?.data);

// -- What a change was, in words, for the pack's history -----------------------------------

const byId = (list) => new Map((Array.isArray(list) ? list : []).filter((e) => e && e.id !== undefined).map((e) => [e.id, e]));

const pointName = (point) => point?.name || "a point";

// A phase line is told as it is drawn (PL RED); one whose name is not words is "a phase line".
const named = (line) => (typeof line?.name === "string" && line.name.trim() ? phaseLineLabel(line) : "a phase line");

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
  const linesBefore = byId(before?.phaseLines);
  const linesAfter = byId(after?.phaseLines);
  linesAfter.forEach((line, id) => {
    const old = linesBefore.get(id);
    if (!old) phrases.push(`added ${named(line)} to ${set}`);
    else if (old.name !== line.name) phrases.push(`renamed ${named(old)} to ${named(line)} on ${set}`);
    else if (!sameData(old.points, line.points)) phrases.push(`moved ${named(line)} on ${set}`);
  });
  linesBefore.forEach((line, id) => {
    if (!linesAfter.has(id)) phrases.push(`removed ${named(line)} from ${set}`);
  });
  const what = phrases[0] ?? `edited ${set}`;
  return `${actor || "Someone"} ${what}.`.slice(0, 300);
};
