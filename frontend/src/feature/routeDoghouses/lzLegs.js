/*
 * The legs of the routes shown that fly into and out of an LZ/PZ, and what they do to its own doghouses (the owner, 2026-11-26).
 * A route's doghouse on the leg that arrives at the LZ/PZ's target stands in for the LZ/PZ's landing doghouse, and one on the leg
 * that leaves it for its takeoff doghouse: while one does, the LZ/PZ's own is not drawn, and the heading it would give the flight
 * data is the leg's true course instead, so the slope is judged on the course the route flies in, and the LZ card and the brief
 * say it. Nothing is written into the LZ/PZ: its doghouses keep what they say, and are drawn and followed again as soon as no
 * route reaches it (a route hidden, moved off, or closed).
 *
 * A leg reaches the target where its named point there is within 500 m of it, the brief's rule for a route that reaches a stop
 * (compile.js). Of several, the leg whose end is nearest the target wins (the first of equal ones, routes and legs in order).
 * Only routes shown count, as only they are drawn: a route hidden on the map stands in for nothing.
 */
import { computeRoutePlan, defaultRoutePlan, distanceNm, planPoints } from "../msnxImport/routeCalc";
import { doghouseHeadingText, flightDoghouses } from "../doghouses/doghouseFields";
import { roofName } from "./routeDoghouses";

/** How near the target a route's named point must be for its leg to reach the LZ/PZ, in metres. */
export const LZ_LEG_REACH_M = 500;
const METRES_PER_NM = 1852;

const finite = (value) => typeof value === "number" && Number.isFinite(value);

/** A target as { lat, lon } ({lat, lon} as a diagram keeps it, or [lat, lon]); null when it is not a place. */
const placeOf = (target) => {
  const lat = Array.isArray(target) ? target[0] : target?.lat;
  const lon = Array.isArray(target) ? target[1] : target?.lon;
  return finite(lat) && finite(lon) ? { lat, lon } : null;
};

/** A leg's true course as a heading the flight data keeps: whole degrees, 0 to 359, three digits and a degree sign ("060°"). */
export const legHeadingText = (courseDeg) => doghouseHeadingText((((Math.round(courseDeg) % 360) + 360) % 360));

const legOf = (route, leg, from, to, metres) => ({
  routeId: route.id ?? null,
  routeName: String(route.name ?? "").trim(),
  from: roofName(from.name) || roofName(leg.fromName),
  to: roofName(to.name) || roofName(leg.toName),
  courseDeg: leg.courseTrueDeg,
  heading: legHeadingText(leg.courseTrueDeg),
  metres,
});

/**
 * The leg of [routes] that arrives at [target] (`landing`) and the one that leaves it (`takeoff`), each null when none does:
 * `{ routeId, routeName, from, to, courseDeg, heading, metres }`, `from` and `to` the points' names as the route's doghouses
 * write them, `metres` how far the leg's end at the LZ/PZ is from the target. A leg with no length or no course is no leg.
 */
export const lzLegs = (target, routes = []) => {
  const legs = { landing: null, takeoff: null };
  const at = placeOf(target);
  if (!at) return legs;
  const metresTo = (p) => distanceNm(p.lat, p.lon, at.lat, at.lon) * METRES_PER_NM;
  for (const route of Array.isArray(routes) ? routes : []) {
    if (!route || route.visible === false || !Array.isArray(route.points)) continue;
    if (!route.points.every((p) => finite(p?.lat) && finite(p?.lon))) continue;
    const amps = planPoints(route);
    if (amps.length < 2) continue;
    const plan = computeRoutePlan(route, { ...defaultRoutePlan(), ...route.plan }, route.elevations || {});
    plan.legs.forEach((leg, i) => {
      if (!finite(leg.courseTrueDeg) || !(leg.distNm > 0)) return;
      const from = amps[i];
      const to = amps[i + 1];
      if (!from || !to) return;
      const arriving = metresTo(to);
      if (arriving <= LZ_LEG_REACH_M && (legs.landing === null || arriving < legs.landing.metres)) {
        legs.landing = legOf(route, leg, from, to, arriving);
      }
      const leaving = metresTo(from);
      if (leaving <= LZ_LEG_REACH_M && (legs.takeoff === null || leaving < legs.takeoff.metres)) {
        legs.takeoff = legOf(route, leg, from, to, leaving);
      }
    });
  }
  return legs;
};

/**
 * [flightData] with the headings of the legs that stand in for its doghouses: what the slope is judged on and the LZ card and
 * the brief say. The same object when no leg does.
 */
export const flightDataWithLegs = (flightData, legs) => {
  const data = flightData ?? {};
  if (!legs?.landing && !legs?.takeoff) return data;
  return {
    ...data,
    ...(legs.landing ? { landing_hdg: legs.landing.heading } : {}),
    ...(legs.takeoff ? { takeoff_hdg: legs.takeoff.heading } : {}),
  };
};

/**
 * [doghouses] less those a leg stands in for: the landing doghouse while a leg arrives, the takeoff one while a leg leaves (the
 * doghouses that give the flight data its headings, `flightDoghouses`). The same list when neither is.
 */
export const doghousesBesideLegs = (doghouses, legs) => {
  const list = Array.isArray(doghouses) ? doghouses : [];
  const { landing, takeoff } = flightDoghouses(list);
  const gone = [legs?.landing ? landing : null, legs?.takeoff ? takeoff : null].filter(Boolean);
  return gone.length === 0 ? list : list.filter((dh) => !gone.includes(dh));
};

/** What the slope tile says of a landing heading a leg gives: "From ROUTE 2, RP to TGT". */
export const legWords = (leg) => {
  if (!leg) return null;
  const between = leg.from && leg.to ? `, ${leg.from} to ${leg.to}` : "";
  return `From ${leg.routeName || "the route"}${between}`;
};
