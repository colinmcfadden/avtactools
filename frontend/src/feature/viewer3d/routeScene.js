import { computeRoutePlan, defaultRoutePlan, planPoints } from "../msnxImport/routeCalc";

/**
 * Routes as 3D shapes: where each line, curtain and label goes, in metres
 * above the ellipsoid. Pure, so it can be tested — the 3D picture itself
 * cannot be (see docs/3D_PLANNING_GRAPHICS_PLAN.md).
 *
 * Two steps, because heights come from the server:
 *   prepareRoutes(routes)         → the points to ask the server about
 *   placeRoutes(prepared, heights) → the shapes, once the heights are back
 */

export const FT_TO_M = 0.3048;

// Ground is sampled along each leg, not just at its ends: a straight leg
// between two hilltops crosses a valley, and a curtain whose foot only knew the
// hilltops would hang in mid-air over it. 50 m is several DEM pixels.
export const SAMPLE_STEP_M = 50;

// Under the server's per-request cap (terrain_tiles.MAX_POINTS = 5000). Long
// routes are sampled more coarsely rather than refused.
export const MAX_SAMPLES = 4500;

// Where the planner's ground (Terrarium/SRTM) and the 3D ground (USGS DEMs)
// disagree by more than this, the label says so. A display flag only — not a
// clearance standard.
export const AGL_MISMATCH_FT = 50;

const EARTH_RADIUS_M = 6371008.8;

export const distanceM = (a, b) => {
  const toRad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * toRad;
  const dLon = (b.lon - a.lon) * toRad;
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(a.lat * toRad) * Math.cos(b.lat * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
};

const isFinitePoint = (p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lon);

/**
 * A target at either end of a route is the pickup or the landing zone, where
 * the aircraft is on the ground. Its planned altitude is the leg flown in, not
 * where it ends up, so it is drawn on the ground and the leg becomes the climb
 * out or the descent in. Display only: the plan and the AMPS export keep the
 * planned value. A target mid-route is overflown and keeps its altitude.
 */
const isTouchdown = (point, index, count) =>
  point.ptType === "target" && (index === 0 || index === count - 1);

// The same call the route panel makes (RoutePlanSection.jsx), so the 3D labels
// and the panel can never disagree about a planned altitude.
const planFor = (route) =>
  computeRoutePlan(route, { ...defaultRoutePlan(), ...route.plan }, route.elevations || {});

/**
 * The visible routes, and every point along them the server should return
 * ground and geoid for. `samples` is the request body; everything else indexes
 * into it.
 */
export const prepareRoutes = (routes = []) => {
  const candidates = [];
  for (const route of routes) {
    if (!route || route.visible === false) continue;
    const points = route.points || [];
    if (points.length < 2 || !points.every(isFinitePoint)) continue;

    const plan = planFor(route);
    if (plan.points.length < 2) continue;

    const along = [0];
    for (let i = 1; i < points.length; i++) {
      along.push(along[i - 1] + distanceM(points[i - 1], points[i]));
    }
    candidates.push({ route, points, plan, along });
  }

  const vertexCount = candidates.reduce((sum, c) => sum + c.points.length, 0);
  const totalM = candidates.reduce((sum, c) => sum + c.along[c.along.length - 1], 0);
  const step = Math.max(SAMPLE_STEP_M, totalM / Math.max(1, MAX_SAMPLES - vertexCount));

  const samples = [];
  const prepared = [];
  for (const { route, points, plan, along } of candidates) {
    const first = samples.length;
    const distances = [];
    const vertexSample = [];

    points.forEach((point, i) => {
      if (i > 0) {
        const prev = points[i - 1];
        const legM = along[i] - along[i - 1];
        for (let d = step; d < legM; d += step) {
          const t = d / legM;
          samples.push({ lat: prev.lat + (point.lat - prev.lat) * t,
                         lon: prev.lon + (point.lon - prev.lon) * t });
          distances.push(along[i - 1] + d);
        }
      }
      vertexSample.push(samples.length);
      samples.push({ lat: point.lat, lon: point.lon });
      distances.push(along[i]);
    });

    // AMPS points carry the planned altitudes; shaping points only shape the
    // line, so their heights come from the AMPS points either side.
    const amps = planPoints(route);
    const anchors = amps.map((point, i) => {
      const index = points.indexOf(point);
      return { sample: vertexSample[index], distance: along[index],
               name: point.name, ptType: point.ptType ?? null, plan: plan.points[i],
               touchdown: isTouchdown(point, i, amps.length) };
    });

    prepared.push({ id: route.id ?? route.name, name: route.name, color: route.color,
                    first, count: samples.length - first, distances, anchors });
  }

  return {
    routes: prepared,
    // Rounded to about 0.1 m: finer than any DEM, and it halves the request.
    samples: samples.map((s) => ({ lat: Number(s.lat.toFixed(6)),
                                   lon: Number(s.lon.toFixed(6)) })),
  };
};

const formatFt = (ft) => `${Math.round(ft).toLocaleString("en-US")}'`;

/**
 * Where an AMPS point sits, in metres above the ellipsoid.
 *
 * At its planned MSL — the altitude the AMPS export writes as CmdAlt, so the
 * one that will actually be flown — corrected to the ellipsoid. Only a point
 * with no planned MSL (its AGL could not be converted because the planner had
 * no ground there) falls back to the 3D ground plus its AGL.
 */
const placeAnchor = (anchor, groundM, geoidM) => {
  const { mslFt, aglFt } = anchor.plan;
  if (anchor.touchdown && groundM != null) {
    return { heightM: groundM, basis: "ground" };
  }
  if (mslFt != null) {
    return { heightM: mslFt * FT_TO_M + (geoidM ?? 0), basis: "msl" };
  }
  if (aglFt != null && groundM != null) {
    return { heightM: groundM + aglFt * FT_TO_M, basis: "agl" };
  }
  return { heightM: null, basis: null };
};

const labelFor = ({ heightM, basis, groundM, geoidM, ...anchor }) => {
  const name = String(anchor.name || "").replace(/^\./, "") || "Point";
  const measuredAglFt = groundM != null ? (heightM - groundM) / FT_TO_M : null;
  const lines = [name];

  if (basis === "ground") {
    // The zone's own elevation, back from the ellipsoid to MSL: the number a
    // crew briefs for a landing zone.
    lines.push(`On the ground · ${formatFt((groundM - (geoidM ?? 0)) / FT_TO_M)} MSL`);
    return { text: lines.join("\n"), measuredAglFt: 0, mismatch: false };
  }

  if (basis === "agl") {
    lines.push(`${formatFt(anchor.plan.aglFt)} AGL · no planned MSL`);
    return { text: lines.join("\n"), measuredAglFt, mismatch: false };
  }

  lines.push(`${formatFt(anchor.plan.mslFt)} MSL · `
    + (measuredAglFt != null ? `${formatFt(measuredAglFt)} AGL` : "AGL n/a"));

  // The planner converts AGL with its own ground. Where that ground is off,
  // the route will not fly at the AGL the planner showed — say so.
  const plannedAglFt = anchor.plan.aglFt;
  const mismatch = plannedAglFt != null && measuredAglFt != null
    && Math.abs(measuredAglFt - plannedAglFt) > AGL_MISMATCH_FT;
  if (mismatch) lines.push(`planned ${formatFt(plannedAglFt)} AGL`);

  return { text: lines.join("\n"), measuredAglFt, mismatch };
};

/** Height at a distance along the route, straight between AMPS points. */
const heightAt = (anchors, distance) => {
  if (anchors.length === 0) return null;
  if (distance <= anchors[0].distance) return anchors[0].heightM;
  const last = anchors[anchors.length - 1];
  if (distance >= last.distance) return last.heightM;

  for (let i = 0; i < anchors.length - 1; i++) {
    const a = anchors[i];
    const b = anchors[i + 1];
    if (distance < a.distance || distance > b.distance) continue;
    if (a.heightM == null || b.heightM == null) return null;
    const span = b.distance - a.distance;
    if (span <= 0) return b.heightM;
    return a.heightM + (b.heightM - a.heightM) * ((distance - a.distance) / span);
  }
  return null;
};

/** Consecutive indices for which `keep` holds, as [start, end) runs of 2+. */
const runs = (count, keep) => {
  const out = [];
  let start = null;
  for (let i = 0; i <= count; i++) {
    if (i < count && keep(i)) {
      if (start === null) start = i;
    } else if (start !== null) {
      if (i - start >= 2) out.push([start, i]);
      start = null;
    }
  }
  return out;
};

/**
 * Drawable shapes, given the server's answer for `prepared.samples`.
 *
 * Returns one entry per route: `lines` (runs of [lon, lat, heightM]), `walls`
 * (the same with the ground under each position, only where ground is known),
 * and `markers` for the AMPS points. A height that cannot be known leaves a
 * gap rather than a guess.
 */
export const placeRoutes = (prepared, heights) => {
  const groundAll = heights?.groundM;
  const geoidAll = heights?.geoidM;
  if (!prepared || !Array.isArray(groundAll)
      || groundAll.length !== prepared.samples.length) {
    return [];
  }

  return prepared.routes.map((route) => {
    const sampleAt = (i) => prepared.samples[route.first + i];
    const groundAt = (i) => groundAll[route.first + i] ?? null;

    const anchors = route.anchors.map((anchor) => {
      const groundM = groundAll[anchor.sample] ?? null;
      const geoidM = geoidAll?.[anchor.sample] ?? null;
      const placed = placeAnchor(anchor, groundM, geoidM);
      return { ...anchor, ...placed, groundM, geoidM };
    });

    const heightsM = route.distances.map((d) => heightAt(anchors, d));
    const position = (i) => [sampleAt(i).lon, sampleAt(i).lat, heightsM[i]];

    const lines = runs(route.count, (i) => heightsM[i] != null)
      .map(([start, end]) => Array.from({ length: end - start }, (_u, k) => position(start + k)));

    const walls = runs(route.count, (i) => heightsM[i] != null && groundAt(i) != null)
      .map(([start, end]) => ({
        positions: Array.from({ length: end - start }, (_u, k) => position(start + k)),
        groundM: Array.from({ length: end - start }, (_u, k) => groundAt(start + k)),
      }));

    const markers = anchors
      .filter((anchor) => anchor.heightM != null)
      .map((anchor) => {
        const sample = prepared.samples[anchor.sample];
        const label = labelFor(anchor);
        return {
          lon: sample.lon,
          lat: sample.lat,
          heightM: anchor.heightM,
          groundM: anchor.groundM,
          ptType: anchor.ptType,
          label: label.text,
          measuredAglFt: label.measuredAglFt,
          mismatch: label.mismatch,
        };
      });

    return { id: route.id, name: route.name, color: route.color, lines, walls, markers };
  });
};
