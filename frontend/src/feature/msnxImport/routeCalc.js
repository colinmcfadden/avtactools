import { UH60L_PROFILE } from "./uh60lProfile";
import api from "../auth/api";

/**
 * Route planning math: leg distances/courses along the sketched geometry,
 * IAS→TAS conversion, wind-triangle ground speeds, TOT-anchored clock times,
 * and fuel estimates. Pure and synchronous — elevations (for AGL altitudes
 * and density altitude) are fetched separately and passed in.
 */

const EARTH_RADIUS_NM = 3440.065;
export const M_TO_FT = 3.28084;
const KTS_TO_MPS = 0.514444;

const toRad = (deg) => (deg * Math.PI) / 180;
const toDeg = (rad) => (rad * 180) / Math.PI;

export const distanceNm = (lat1, lon1, lat2, lon2) => {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_NM * Math.asin(Math.sqrt(a));
};

export const trueCourseDeg = (lat1, lon1, lat2, lon2) => {
  const dLon = toRad(lon2 - lon1);
  const y = Math.sin(dLon) * Math.cos(toRad(lat2));
  const x =
    Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(dLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
};

/** ISA temperature (°C) at a pressure altitude in feet. */
const isaTempC = (altFt) => 15 - 1.98 * (altFt / 1000);

/**
 * IAS→TAS with the standard planning approximation: TAS grows ~2% per
 * 1,000 ft of density altitude. DA from pressure altitude and OAT.
 */
export const iasToTas = (iasKts, pressAltFt, oatC) => {
  const da = pressAltFt + 118.8 * (oatC - isaTempC(pressAltFt));
  return iasKts * (1 + 0.02 * Math.max(0, da) / 1000);
};

/**
 * Wind-triangle solve. Wind direction is where the wind blows FROM (°true),
 * aviation convention. Returns null when the wind is too strong for this TAS
 * to hold the course.
 */
export const solveWindTriangle = (tasKts, courseDeg, windFromDeg, windKts) => {
  if (windKts <= 0) return { gsKts: tasKts, windCorrectionDeg: 0, headwindKts: 0 };
  const relRad = toRad(windFromDeg - courseDeg);
  const headwind = windKts * Math.cos(relRad);
  const crosswind = windKts * Math.sin(relRad);
  if (Math.abs(crosswind) > tasKts) return null;
  const wcaRad = Math.asin(crosswind / tasKts);
  const gs = tasKts * Math.cos(wcaRad) - headwind;
  if (gs <= 0) return null;
  return {
    gsKts: gs,
    windCorrectionDeg: toDeg(wcaRad),
    headwindKts: headwind,
  };
};

export const defaultRoutePlan = () => ({
  aircraft: UH60L_PROFILE.name,
  // Route-level defaults; each point can override its own "to" values.
  airspeed: {
    value: UH60L_PROFILE.defaultAirspeedKts,
    type: UH60L_PROFILE.defaultAirspeedType,
  },
  altitude: {
    value: UH60L_PROFILE.defaultAltitudeFt,
    ref: UH60L_PROFILE.defaultAltitudeRef,
  },
  wind: { dirTrue: 0, speedKts: 0 },
  tempC: 15,
  fuelFlowLbHr: UH60L_PROFILE.defaultFuelFlowLbHr,
  // Date (YYYY-MM-DD) that all clock times fall on; defaults to today.
  date: "",
  // Per-AMPS-point "to" values (apply to the leg arriving at the point) plus
  // an optional clock anchor:
  //   { [pointId]: { altitude:{value,ref}, airspeed:{value,type},
  //                  wind:{dirTrue,speedKts}, clock:"HH:MM:SS" } }
  // Exactly one point may carry a clock at a time — it anchors every other
  // point's clock time via the rolling elapsed times.
  perPoint: {},
});

/**
 * Backfills plan defaults on routes restored from older saves and migrates
 * the pre-inline `tot` anchor (single TOT field) into a per-point clock.
 */
export const ensureRoutePlan = (route) => {
  const plan = { ...defaultRoutePlan(), ...(route.plan || {}) };
  if (route.plan?.tot?.time && !hasAnyClock(plan)) {
    const { pointId, time, date } = route.plan.tot;
    if (date) plan.date = date;
    const anchorId = pointId || planPoints(route)[0]?.id;
    if (anchorId) {
      plan.perPoint = {
        ...plan.perPoint,
        [anchorId]: { ...plan.perPoint[anchorId], clock: time },
      };
    }
  }
  delete plan.tot;
  return { ...route, plan };
};

const hasAnyClock = (plan) =>
  Object.values(plan.perPoint || {}).some((o) => o?.clock);

/** Parses a "HH:MM" / "HH:MM:SS" clock on a YYYY-MM-DD date into a Date. */
const parseClockToDate = (dateStr, timeStr) => {
  if (!timeStr) return null;
  const m = timeStr.trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!m) return null;
  const [, hh, mm, ss] = m;
  const base = dateStr ? new Date(`${dateStr}T00:00:00`) : new Date();
  if (Number.isNaN(base.getTime())) return null;
  base.setHours(Number(hh), Number(mm), Number(ss || 0), 0);
  return base;
};

/**
 * AMPS-point list for planning: shaping points only shape leg geometry.
 *
 * A point with no AMPS id is excluded as well. Per-point plan overrides are
 * keyed by point id, so an id-less point cannot hold or export one — every such
 * row would read and write the same `perPoint[undefined]` entry and move
 * together. Matches the filter used when reading a plan out of a mission file.
 */
export const planPoints = (route) =>
  route.points.filter((p) => p.kind !== "shaping" && p.id);

/**
 * Computes the full plan for a sketched route.
 *
 * @param route       sketched route ({ points: [...] })
 * @param plan        plan settings (see defaultRoutePlan)
 * @param elevationsFt optional { [pointId]: groundElevationFt } for AGL
 *                     altitudes and density-altitude TAS conversion
 * @returns { points, legs, totals, warnings }
 */
export const computeRoutePlan = (route, plan, elevationsFt = {}) => {
  const warnings = [];
  const amps = planPoints(route);
  if (amps.length < 2) {
    return { points: [], legs: [], totals: null, warnings: ["Route needs at least two route points."] };
  }

  // Point altitudes (per-point override, else route default). A charted
  // elevation (from a snapped local point) is authoritative over the DEM.
  const pointAlts = amps.map((p) => {
    const alt = plan.perPoint?.[p.id]?.altitude || plan.altitude;
    const groundFt = p.chartElevationFt ?? elevationsFt[p.id] ?? null;
    let mslFt = null;
    let aglFt = null;
    if (alt.ref === "msl") {
      mslFt = alt.value;
      if (groundFt != null) aglFt = alt.value - groundFt;
    } else {
      aglFt = alt.value;
      if (groundFt != null) mslFt = groundFt + alt.value;
    }
    return { ref: alt.ref, value: alt.value, mslFt, aglFt, groundFt };
  });

  // Legs between consecutive AMPS points; distance follows the shaping
  // geometry the aircraft actually flies, course is point-to-point. Airspeed
  // and wind are "to" values — they come from the point being flown TO (the
  // leg's arrival point, amps[i + 1]).
  const legs = [];
  for (let i = 0; i < amps.length - 1; i++) {
    const from = amps[i];
    const to = amps[i + 1];
    const fromIdx = route.points.indexOf(from);
    const toIdx = route.points.indexOf(to);

    let dist = 0;
    for (let j = fromIdx; j < toIdx; j++) {
      const a = route.points[j];
      const b = route.points[j + 1];
      dist += distanceNm(a.lat, a.lon, b.lat, b.lon);
    }
    const course = trueCourseDeg(from.lat, from.lon, to.lat, to.lon);

    const arriving = plan.perPoint?.[to.id] || {};
    const airspeed = arriving.airspeed || plan.airspeed;
    const wind = arriving.wind || plan.wind || { dirTrue: 0, speedKts: 0 };
    let gsKts = null;
    let tasKts = null;
    let windCorrectionDeg = 0;

    if (airspeed.type === "ground") {
      gsKts = airspeed.value;
    } else {
      if (airspeed.type === "indicated") {
        // Density altitude from the leg's average planned MSL altitude when
        // known; otherwise convert at the raw altitude value (best effort).
        const altFt =
          ((pointAlts[i].mslFt ?? pointAlts[i].value) +
            (pointAlts[i + 1].mslFt ?? pointAlts[i + 1].value)) /
          2;
        tasKts = iasToTas(airspeed.value, altFt, plan.tempC ?? 15);
      } else {
        tasKts = airspeed.value;
      }
      const solved = solveWindTriangle(
        tasKts,
        course,
        wind.dirTrue ?? 0,
        wind.speedKts ?? 0,
      );
      if (!solved) {
        warnings.push(
          `Leg ${from.name || i + 1} → ${to.name || i + 2}: wind exceeds what ${Math.round(tasKts)} KTAS can correct for.`,
        );
        gsKts = null;
      } else {
        gsKts = solved.gsKts;
        windCorrectionDeg = solved.windCorrectionDeg;
      }
    }

    const timeSec = gsKts && gsKts > 0 ? (dist / gsKts) * 3600 : null;
    const fuelLb =
      timeSec != null ? (timeSec / 3600) * (plan.fuelFlowLbHr ?? 0) : null;

    legs.push({
      fromId: from.id,
      toId: to.id,
      fromName: from.name || `PT${i + 1}`,
      toName: to.name || `PT${i + 2}`,
      distNm: dist,
      courseTrueDeg: course,
      airspeed,
      wind,
      tasKts,
      gsKts,
      windCorrectionDeg,
      timeSec,
      fuelLb,
    });
  }

  // Rolling ("stopwatch") elapsed time from the first point.
  const cumSec = [0];
  let timingBroken = false;
  for (const leg of legs) {
    if (leg.timeSec == null) timingBroken = true;
    cumSec.push(timingBroken ? null : cumSec[cumSec.length - 1] + leg.timeSec);
  }

  // Clock times, anchored at whichever point carries a clock (the TOT).
  let anchorIdx = amps.findIndex((p) => plan.perPoint?.[p.id]?.clock);
  const anchorTime =
    anchorIdx >= 0
      ? parseClockToDate(plan.date, plan.perPoint[amps[anchorIdx].id].clock)
      : null;
  if (!anchorTime) anchorIdx = -1;

  const points = amps.map((p, i) => {
    let clockTime = null;
    if (anchorTime && cumSec[i] != null && cumSec[anchorIdx] != null) {
      clockTime = new Date(
        anchorTime.getTime() + (cumSec[i] - cumSec[anchorIdx]) * 1000,
      );
    }
    // The leg arriving at this point supplies its "to" speed/wind (the first
    // point has none).
    const legTo = i > 0 ? legs[i - 1] : null;
    return {
      id: p.id,
      uiId: p.uiId ?? p.id ?? `route-plan-point-${i}`,
      name: p.name,
      ptType: p.ptType,
      lat: p.lat,
      lon: p.lon,
      ...pointAlts[i],
      airspeed: legTo ? legTo.airspeed : null,
      wind: legTo ? legTo.wind : null,
      legDistNm: legTo ? legTo.distNm : null,
      legCourseTrueDeg: legTo ? legTo.courseTrueDeg : null,
      legGsKts: legTo ? legTo.gsKts : null,
      legTimeSec: legTo ? legTo.timeSec : null,
      legFuelLb: legTo ? legTo.fuelLb : null,
      elapsedSec: cumSec[i],
      clockTime,
      hasClock: Boolean(plan.perPoint?.[p.id]?.clock),
      isTotAnchor: i === anchorIdx,
    };
  });

  const totalTimeSec = timingBroken ? null : cumSec[cumSec.length - 1];
  const totals = {
    distNm: legs.reduce((sum, leg) => sum + leg.distNm, 0),
    timeSec: totalTimeSec,
    fuelLb:
      totalTimeSec != null
        ? (totalTimeSec / 3600) * (plan.fuelFlowLbHr ?? 0)
        : null,
  };

  return { points, legs, totals, warnings };
};

/**
 * Ground elevations (ft) for a route's AMPS points. Fetched through this app's
 * backend (/api/elevations) rather than the public elevation API directly —
 * the browser can't reach that API cross-origin (CORS), which previously left
 * every point without a ground elevation and every exported AMPS point stuck at
 * the mission template's default. Returns { [pointId]: elevationFt }; resolves
 * to {} on failure so planning degrades gracefully instead of blocking.
 */
export const fetchPointElevationsFt = async (route) => {
  const amps = planPoints(route);
  if (amps.length === 0) return {};
  try {
    const res = await api.post("/elevations", {
      points: amps.map((p) => ({ lat: p.lat, lon: p.lon })),
    });
    const data = res.data;
    const feet = data?.elevationsFt || [];
    const map = {};
    amps.forEach((p, i) => {
      if (typeof feet[i] === "number") map[p.id] = feet[i];
    });
    return map;
  } catch {
    return {};
  }
};

export const formatClock = (date) =>
  date
    ? `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}:${String(date.getSeconds()).padStart(2, "0")}`
    : "--:--:--";

export const formatDuration = (sec) => {
  if (sec == null) return "--";
  const s = Math.round(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`
    : `${m}:${String(r).padStart(2, "0")}`;
};
