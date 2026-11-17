import { ROUTE_COLORS } from "./colorPalette";

/*
 * What can be done to the routes of a set beyond editing one route: joining two into one, and the
 * colours a route can be given. Pure functions, as sketchOps.js's are: a route in, a route out.
 *
 * Moving a route to another set is not here: which set a route is filed under is the sketch's own
 * business (`setId`, routeSketchReducer.js's `move`), never part of the route as it is saved.
 */

/** The colours a route can be given, with what to call each: the eight new routes take in turn, then four more. */
export const ROUTE_COLOR_CHOICES = [
  { color: ROUTE_COLORS[0], name: "Red" },
  { color: ROUTE_COLORS[1], name: "Blue" },
  { color: ROUTE_COLORS[2], name: "Green" },
  { color: ROUTE_COLORS[3], name: "Yellow" },
  { color: ROUTE_COLORS[4], name: "Purple" },
  { color: ROUTE_COLORS[5], name: "Orange" },
  { color: ROUTE_COLORS[6], name: "Cyan" },
  { color: ROUTE_COLORS[7], name: "Pink" },
  { color: "#FFFFFF", name: "White" },
  { color: "#00C7BE", name: "Teal" },
  { color: "#5E5CE6", name: "Indigo" },
  { color: "#AC8E68", name: "Tan" },
];

const sameColor = (a, b) => String(a ?? "").toLowerCase() === String(b ?? "").toLowerCase();

/** What a route's colour is called, or null for one that is none of the choices (an older save's). */
export const routeColorName = (color) => ROUTE_COLOR_CHOICES.find((choice) => sameColor(choice.color, color))?.name ?? null;

/** Whether `color` is one a route can be given here. */
export const isRouteColorChoice = (color) => routeColorName(color) !== null;

/** The route in another colour (the same route when it has that colour already, or `color` is not a choice). */
export const withRouteColor = (route, color) => {
  const choice = ROUTE_COLOR_CHOICES.find((c) => sameColor(c.color, color));
  if (!choice || sameColor(route.color, choice.color)) return route;
  return { ...route, color: choice.color };
};

// About a metre: two points put on the same local point, or one snapped onto the other, not two clicks near each other.
const SAME_PLACE_DEG = 1e-5;

const samePlace = (a, b) =>
  Boolean(a && b) && Math.abs(a.lat - b.lat) <= SAME_PLACE_DEG && Math.abs(a.lon - b.lon) <= SAME_PLACE_DEG;

const VALUES = ["altitude", "airspeed", "wind"];
const sameValue = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

/** Whether the second route begins where the first ends, so the two share that point once joined. */
export const routesMeet = (first, second) => samePlace(first?.points?.[first.points.length - 1], second?.points?.[0]);

/**
 * Two routes as one: `second`'s points after `first`'s, in the order each was drawn. The one route is
 * `first` grown longer, so it keeps its id, name, colour, the set it is in and whether it is shown,
 * and `second` is no more (the caller takes it out of its set).
 *
 * - Where `second` begins on `first`'s last point (the same place to about a metre), that place is
 *   one point of the route, `first`'s: a leg of no length is not flown. Anywhere else both are kept,
 *   and the line runs from the one to the other.
 * - Every point keeps its type and name: what is a target or an IP in the middle of the longer route
 *   is the planner's to say, not a rule's.
 * - The plan is `first`'s (date, temperature, fuel flow, and the altitude, speed and wind a point with
 *   none of its own flies). So that `second`'s legs are flown as they were planned, each of its named
 *   points that took `second`'s own altitude, speed or wind takes it as its own where `first`'s
 *   differs. One point holds the clock: `first`'s if it has one, else `second`'s.
 * - A point of `second` whose id `first` already has (the same route twice) gets a new one from
 *   `newPointId`, its plan values and ground elevation with it.
 */
export const combineRoutes = (first, second, newPointId = () => crypto.randomUUID()) => {
  const firstPoints = first.points ?? [];
  const secondPoints = second.points ?? [];
  const meet = routesMeet(first, second);
  const taken = new Set(firstPoints.map((point) => point.id));
  // Each point that comes over, with the id it had in `second` (its plan values and ground are filed under that).
  const added = secondPoints.slice(meet ? 1 : 0).map((point) => {
    const id = taken.has(point.id) ? newPointId() : point.id;
    taken.add(id);
    return { was: point.id, point: { ...point, id, role: firstPoints.length > 0 && point.role === "start" ? "waypoint" : point.role } };
  });

  const planA = first.plan ?? {};
  const planB = second.plan ?? {};
  const perPoint = { ...(planA.perPoint ?? {}) };
  const firstHasClock = Object.values(perPoint).some((entry) => entry?.clock);
  added.forEach(({ was, point }, index) => {
    if (point.kind !== "amps") return;
    const own = { ...(planB.perPoint?.[was] ?? {}) };
    // The values this point flew by in `second`, where `first`'s would now be others. (The point
    // `second` started at had no leg arriving; joined on, the leg to it is flown by `first`'s.)
    if (meet || index > 0) {
      VALUES.forEach((name) => {
        if (own[name] === undefined && planB[name] !== undefined && !sameValue(planB[name], planA[name])) own[name] = planB[name];
      });
    }
    if (firstHasClock) delete own.clock;
    if (Object.keys(own).length > 0) perPoint[point.id] = own;
  });
  // The clock `second` kept at the point the two share goes to that point, which is `first`'s.
  const sharedClock = meet ? planB.perPoint?.[secondPoints[0].id]?.clock : undefined;
  if (sharedClock && !firstHasClock) {
    const shared = firstPoints[firstPoints.length - 1].id;
    perPoint[shared] = { ...perPoint[shared], clock: sharedClock };
  }

  const elevations = { ...(first.elevations ?? {}) };
  added.forEach(({ was, point }) => {
    const feet = second.elevations?.[was];
    if (feet !== undefined && elevations[point.id] === undefined) elevations[point.id] = feet;
  });

  return {
    ...first,
    points: [...firstPoints, ...added.map(({ point }) => point)],
    plan: { ...planA, perPoint },
    elevations,
  };
};

/** Why two routes cannot be joined, in words, or null when they can. */
export const whyNotCombined = (first, second) => {
  if (!first || !second) return "That route is no longer here.";
  if (first.id === second.id) return "A route cannot be joined to itself.";
  return null;
};
