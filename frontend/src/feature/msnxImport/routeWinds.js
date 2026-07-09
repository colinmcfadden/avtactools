import api from "../auth/api";
import { computeRoutePlan, defaultRoutePlan, planPoints } from "./routeCalc";

/**
 * Shared forecast-wind logic for both sketched and imported routes. Fetches
 * each point's wind from the nearest station (METAR now / TAF for future
 * times) and merges the results into a plan as per-point "to" wind overrides.
 */

/**
 * Fetches per-point winds for a route. Each point's target time is its computed
 * clock time (when a TOT is set) or the plan date at midday, so future points
 * pull from the TAF. Returns { winds, amps } or { error }.
 */
export async function fetchForecastWinds(route) {
  const plan = { ...defaultRoutePlan(), ...route.plan };
  const amps = planPoints(route);
  if (amps.length === 0) return { error: "Route has no points." };

  const result = computeRoutePlan(route, plan, route.elevations || {});
  const midday = plan.date ? new Date(`${plan.date}T12:00:00`) : null;
  const points = amps.map((p, i) => {
    const clock = result.points[i]?.clockTime;
    let time = null;
    if (clock) time = clock.toISOString();
    else if (midday && !Number.isNaN(midday.getTime())) time = midday.toISOString();
    return { id: p.id, lat: p.lat, lon: p.lon, time };
  });

  try {
    const res = await api.post("/route-winds", { points });
    return { winds: res.data?.winds || {}, amps };
  } catch (err) {
    return { error: err.response?.data?.error || err.message };
  }
}

/** Returns a new plan with the fetched winds merged in as per-point overrides. */
export function mergeWindsIntoPlan(plan, amps, winds) {
  const base = { ...defaultRoutePlan(), ...plan };
  const perPoint = { ...base.perPoint };
  let firstTemp = null;
  for (const pt of amps) {
    const w = winds[pt.id];
    if (!w) continue;
    perPoint[pt.id] = {
      ...perPoint[pt.id],
      wind: { dirTrue: w.dirTrue, speedKts: w.speedKts },
    };
    if (firstTemp === null && typeof w.tempC === "number") firstTemp = w.tempC;
  }
  const next = { ...base, perPoint };
  if (firstTemp !== null) next.tempC = firstTemp;
  return next;
}
