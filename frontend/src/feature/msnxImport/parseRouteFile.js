import { childNamed, childrenNamed, childText, decodeXml, parseHostileXml, readPosition, strictNumber } from "../../utils/xmlFiles.js";

/*
 * Reads a Garmin flight plan (.fpl) or a GPX file into sketched routes, the reverse of foreflight.js
 * and in the saved shape of useRouteSketch. A file here comes from whoever sent it, so it is bounded
 * and read as hostile (utils/xmlFiles.js), and a position that cannot be read is left out and counted,
 * never put somewhere.
 *
 * Neither format records what AMPS calls a point, so how a point is typed is a rule, not a reading:
 *   - A point with a name is a named (AMPS) point; a point with none only shapes the line. A name of
 *     exactly `WP<its place in the route>` counts as none, since that is what foreflight.js writes
 *     for a shaping point.
 *   - The first and last points are always named points, whatever the file says: a route needs two to
 *     be a route.
 *   - A named point's type comes from its name: TGT, LZ or PZ is a target, SP, RP or IP an IP point,
 *     anything else a turn point. No attack profile is laid over a file from elsewhere: it is not a
 *     sketch, and its first and last points may not be targets.
 *
 * Elevations are not read for routes. A GPX's `<ele>` may be the aircraft's altitude (a track's
 * always is) rather than the ground's, and the route's ground is fetched as for any route.
 */

export const LIMITS = {
  fileBytes: 8 * 1024 * 1024,
  routes: 100,
  routePoints: 2000,      // a planned route; more than this is not one
  trackPoints: 500,       // what a recorded track is thinned to
  fileWaypoints: 50000,
};

// Before Douglas-Peucker, whose worst case is quadratic and which a hostile track can arrange.
const TRACK_PREFILTER = 5000;
const MAX_NAME = 100;

const generateId = (prefix) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const TOO_LARGE = "This file is too large to be a route (over 8 MB).";
export const NO_ROUTE = "No route of at least two points was found in this file.";

const plural = (n, one) => `${n.toLocaleString("en-US")} ${one}${n === 1 ? "" : "s"}`;

/** What AMPS would call a point, from its name (see the top of this file). */
export const pointTypeFromName = (name) => {
  const bare = String(name ?? "").trim().replace(/^\.+/, "").toUpperCase();
  if (/^(TGT|LZ|PZ)(?![A-Z])/.test(bare)) return "target";
  if (/^(SP|RP|IP)(?![A-Z])/.test(bare)) return "ip";
  return "turn";
};

const cleanName = (text) => String(text ?? "").trim().slice(0, MAX_NAME);

/** `raw`: `[{ lat, lon, name }]`, name "" where the file gives none. */
const toRoutePoints = (raw, newPointId) =>
  raw.map((point, index) => {
    const edge = index === 0 || index === raw.length - 1;
    const named = point.name !== "";
    const isNamedPoint = named || edge;
    const name = named ? point.name : edge ? `WP${index + 1}` : "";
    return {
      id: newPointId(),
      lat: point.lat,
      lon: point.lon,
      ele: null,
      role: index === 0 ? "start" : "waypoint",
      kind: isNamedPoint ? "amps" : "shaping",
      ptType: isNamedPoint ? pointTypeFromName(name) : null,
      name,
    };
  });

const toRoute = (name, raw, newPointId) => ({
  id: generateId("sketch"),
  name: cleanName(name).toUpperCase(),
  elevations: {},
  points: toRoutePoints(raw, newPointId),
});

/** foreflight.js's own name for a point it had no name for. */
const isGeneratedName = (name, position) => name === `WP${position}`;

// ---- Garmin flight plan ------------------------------------------------------------------------

/**
 * Whether `identifier` is what foreflight.js would have made from this comment: its uppercase letters
 * and digits, ten at most, perhaps with a number put on the end to keep it unique. Then the comment
 * is the point's own name. In a flight plan from elsewhere a comment describes the waypoint ("ATLANTA
 * HARTSFIELD" on KATL), and the identifier is its name.
 */
const commentIsTheName = (identifier, comment) => {
  const base = comment.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10);
  // A name with nothing an identifier can hold ("---") is written as WP<n>.
  if (!base) return /^WP\d+$/.test(identifier);
  if (identifier === base) return true;
  for (let digits = 1; digits < identifier.length; digits += 1) {
    const suffix = identifier.slice(-digits);
    if (!/^\d+$/.test(suffix)) break;
    if (identifier.slice(0, -digits) === base.slice(0, 10 - digits)) return true;
  }
  return false;
};

const waypointKey = (identifier, type, country) => `${identifier}\u0000${type}\u0000${country}`;

const parseFlightPlan = (doc, { newPointId, fileName }) => {
  const root = doc.documentElement;
  const byKey = new Map();
  const byIdentifier = new Map();
  let skipped = 0;
  const table = childrenNamed(childNamed(root, "waypoint-table"), "waypoint");
  if (table.length > LIMITS.routePoints * LIMITS.routes) throw new Error("This flight plan holds too many waypoints to be read.");
  const listed = [];
  table.forEach((waypoint) => {
    const identifier = childText(waypoint, "identifier");
    const position = readPosition(childText(waypoint, "lat"), childText(waypoint, "lon"));
    if (!identifier || !position) {
      skipped += 1;
      return;
    }
    const entry = { identifier, comment: childText(waypoint, "comment"), lat: position[0], lon: position[1] };
    byKey.set(waypointKey(identifier, childText(waypoint, "type"), childText(waypoint, "country-code")), entry);
    if (!byIdentifier.has(identifier)) byIdentifier.set(identifier, entry);
    listed.push(entry);
  });

  const route = childNamed(root, "route");
  const routePoints = childrenNamed(route, "route-point");
  if (routePoints.length > LIMITS.routePoints) throw new Error(`This route has more than ${plural(LIMITS.routePoints, "point")}.`);

  // A flight plan may be only its table: then the table's order is the route.
  const entries = routePoints.length === 0
    ? listed.slice(0, LIMITS.routePoints)
    : routePoints.flatMap((point) => {
        const identifier = childText(point, "waypoint-identifier");
        const found =
          byKey.get(waypointKey(identifier, childText(point, "waypoint-type"), childText(point, "waypoint-country-code"))) ??
          byIdentifier.get(identifier);
        if (!found) skipped += 1;
        return found ? [found] : [];
      });
  if (entries.length < 2) throw new Error(NO_ROUTE);

  const raw = entries.map((entry, index) => {
    const position = index + 1;
    const comment = entry.comment;
    const name = comment && commentIsTheName(entry.identifier, comment)
      ? comment
      : !comment && isGeneratedName(entry.identifier, position)
        ? ""
        : entry.identifier;
    return { lat: entry.lat, lon: entry.lon, name: cleanName(name) };
  });
  const routeName = childText(route, "route-name") || fileName;
  return { routes: [toRoute(routeName, raw, newPointId)], points: [], skipped };
};

// ---- GPX ---------------------------------------------------------------------------------------

const readGpxPoints = (elements) => {
  let skipped = 0;
  const points = elements.flatMap((element) => {
    const position = readPosition(element.getAttribute("lat"), element.getAttribute("lon"));
    if (!position) {
      skipped += 1;
      return [];
    }
    return [{ lat: position[0], lon: position[1], element }];
  });
  return { points, skipped };
};

/** The indexes Douglas-Peucker keeps, without recursing: a track is as long as its author made it. */
const simplify = (xy, tolerance) => {
  const keep = new Uint8Array(xy.length);
  keep[0] = 1;
  keep[xy.length - 1] = 1;
  const stack = [[0, xy.length - 1]];
  while (stack.length) {
    const [first, last] = stack.pop();
    const [ax, ay] = xy[first];
    const dx = xy[last][0] - ax;
    const dy = xy[last][1] - ay;
    const lengthSq = dx * dx + dy * dy;
    let farthest = -1;
    let farthestSq = tolerance * tolerance;
    for (let i = first + 1; i < last; i += 1) {
      const px = xy[i][0] - ax;
      const py = xy[i][1] - ay;
      const t = lengthSq === 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / lengthSq));
      const ex = px - t * dx;
      const ey = py - t * dy;
      const distanceSq = ex * ex + ey * ey;
      if (distanceSq > farthestSq) {
        farthestSq = distanceSq;
        farthest = i;
      }
    }
    if (farthest > 0) {
      keep[farthest] = 1;
      stack.push([first, farthest], [farthest, last]);
    }
  }
  return xy.flatMap((_, index) => (keep[index] ? [index] : []));
};

/** A recorded track as a route's worth of points: the same line, within as few metres as it takes. */
export const thinTrack = (points, limit = LIMITS.trackPoints) => {
  if (points.length <= limit) return points;
  const step = Math.max(1, Math.ceil(points.length / TRACK_PREFILTER));
  const coarse = step === 1 ? points : points.filter((_, index) => index % step === 0 || index === points.length - 1);
  const midLat = (coarse[0].lat * Math.PI) / 180;
  const metresPerDegree = 111320;
  const xy = coarse.map((p) => [p.lon * metresPerDegree * Math.cos(midLat), p.lat * metresPerDegree]);
  for (let tolerance = 5; ; tolerance *= 1.5) {
    const kept = simplify(xy, tolerance);
    if (kept.length <= limit) return kept.map((index) => coarse[index]);
  }
};

const waypointOf = (point, newPointId) => {
  const elevationMetres = strictNumber(childText(point.element, "ele"));
  return {
    id: newPointId(),
    name: cleanName(childText(point.element, "name")),
    description: childText(point.element, "desc", "cmt").slice(0, 1000),
    group: cleanName(childText(point.element, "type")) || "Default",
    icon: "",
    // GPX elevations are metres; a local point's are feet.
    elevationFt: elevationMetres === null ? null : Math.round(elevationMetres * 3.28084),
    lat: point.lat,
    lon: point.lon,
  };
};

const parseGpx = (doc, { newPointId, fileName }) => {
  const root = doc.documentElement;
  let skipped = 0;
  const routes = [];
  const defaultName = fileName;

  const rtes = childrenNamed(root, "rte");
  const tracks = childrenNamed(root, "trk");
  if (rtes.length + tracks.length > LIMITS.routes) throw new Error(`This file holds more than ${plural(LIMITS.routes, "route")}.`);

  rtes.forEach((rte, index) => {
    const elements = childrenNamed(rte, "rtept");
    if (elements.length > LIMITS.routePoints) throw new Error(`A route in this file has more than ${plural(LIMITS.routePoints, "point")}.`);
    const read = readGpxPoints(elements);
    skipped += read.skipped;
    if (read.points.length < 2) {
      if (read.points.length === 1) skipped += 1;
      return;
    }
    const raw = read.points.map((point, at) => {
      const name = childText(point.element, "name");
      return { lat: point.lat, lon: point.lon, name: isGeneratedName(name, at + 1) ? "" : cleanName(name) };
    });
    routes.push(toRoute(childText(rte, "name") || `${defaultName}${rtes.length > 1 ? ` ${index + 1}` : ""}`, raw, newPointId));
  });

  let thinned = 0;
  tracks.forEach((track, trackIndex) => {
    const segments = childrenNamed(track, "trkseg");
    const trackName = childText(track, "name") || `${defaultName}${tracks.length > 1 ? ` ${trackIndex + 1}` : ""}`;
    segments.forEach((segment, segmentIndex) => {
      const read = readGpxPoints(childrenNamed(segment, "trkpt"));
      skipped += read.skipped;
      if (read.points.length < 2) return;
      const kept = thinTrack(read.points);
      if (kept.length < read.points.length) thinned += 1;
      // A track's points are fixes, not places: any name on one (a timestamp, usually) is not a waypoint's.
      const raw = kept.map((point) => ({ lat: point.lat, lon: point.lon, name: "" }));
      routes.push(toRoute(segments.length > 1 ? `${trackName} ${segmentIndex + 1}` : trackName, raw, newPointId));
    });
  });
  if (routes.length > LIMITS.routes) throw new Error(`This file holds more than ${plural(LIMITS.routes, "route")}.`);

  const wptElements = childrenNamed(root, "wpt");
  if (wptElements.length > LIMITS.fileWaypoints) throw new Error(`This file holds more than ${plural(LIMITS.fileWaypoints, "waypoint")}.`);

  if (routes.length > 0) return { routes, points: [], skipped, thinned, waypointsLeftOut: wptElements.length };

  const read = readGpxPoints(wptElements);
  const points = read.points.map((point) => waypointOf(point, newPointId));
  if (points.length === 0) throw new Error(NO_ROUTE);
  return { routes: [], points, skipped: skipped + read.skipped, thinned: 0, waypointsLeftOut: 0 };
};

// ---- The file ----------------------------------------------------------------------------------

const baseName = (name) => String(name ?? "").replace(/\.(fpl|gpx|xml)$/i, "").trim() || "ROUTE";

const summaryOf = ({ routes, points, skipped, thinned = 0, waypointsLeftOut = 0 }) => {
  const parts = routes.length > 0
    ? [plural(routes.length, "route"), plural(routes.reduce((sum, route) => sum + route.points.length, 0), "point")]
    : [plural(points.length, "point")];
  if (thinned > 0) parts.push(`${plural(thinned, "track")} simplified`);
  if (skipped > 0) parts.push(`${skipped.toLocaleString("en-US")} unreadable left out`);
  if (waypointsLeftOut > 0) parts.push(`${plural(waypointsLeftOut, "loose waypoint")} left out`);
  return parts.join(" · ");
};

/**
 * What is in a flight plan's or a GPX's text:
 * `{ format, name, routes: [sketched route], points: [local point], skipped, thinned, waypointsLeftOut, summary }`.
 * `routes` is empty where a GPX holds only waypoints, which are local points (`points`).
 * Throws an Error in words for anything else.
 */
export const parseRouteText = (text, { fileName = "", newPointId = () => crypto.randomUUID() } = {}) => {
  const doc = parseHostileXml(text);
  const root = doc.documentElement.localName;
  const name = baseName(fileName);
  let result;
  let format;
  if (root === "flight-plan") {
    format = "fpl";
    result = parseFlightPlan(doc, { newPointId, fileName: name });
  } else if (root === "gpx") {
    format = "gpx";
    result = parseGpx(doc, { newPointId, fileName: name });
  } else {
    throw new Error("This is not a Garmin flight plan or a GPX file.");
  }
  // One route names the set; several are named by the file they came in.
  const setName = result.routes.length === 1 ? result.routes[0].name : name.toUpperCase();
  return { thinned: 0, waypointsLeftOut: 0, ...result, format, name: setName || name, summary: summaryOf(result) };
};

export const parseRouteFile = async (file, options = {}) => {
  if (file.size > LIMITS.fileBytes) throw new Error(TOO_LARGE);
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes.byteLength > LIMITS.fileBytes) throw new Error(TOO_LARGE);
  return parseRouteText(decodeXml(bytes), { ...options, fileName: file.name });
};
