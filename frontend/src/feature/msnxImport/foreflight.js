import { downloadBlob } from "./mutateMsnx.js";

/**
 * Points to hand ForeFlight: ALL route points, including the serpentine/shaping
 * geometry, so the exported path matches what's actually flown — not just the
 * designated turn/IP/target waypoints.
 *
 * Including every point means a dense route can exceed the QR code's capacity
 * for the direct deep link; when it does, the modal falls back to a hosted
 * download link (GPX/FPL), which carries the full point set with no size limit.
 */
export const foreflightPoints = (route) =>
  route.points;

/** Decimal lat/lon tokens, space-separated — ForeFlight route search format. */
export const buildForeFlightRouteString = (route) =>
  foreflightPoints(route)
    .map((p) => `${p.lat.toFixed(6)}/${p.lon.toFixed(6)}`)
    .join(" ");

/** ForeFlight's documented URL scheme: opens the route in the Maps view. */
export const buildForeFlightUrl = (route) =>
  "foreflightmobile://maps/search?q=" +
  encodeURIComponent(buildForeFlightRouteString(route));

const xmlEscape = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const safeFileName = (name, fallback) => String(name ?? "").replace(/[^\w-]+/g, "_") || fallback;

/**
 * Waypoint identifiers: uppercase alphanumerics, max 10 chars (Garmin FPL
 * convention), deduplicated with numeric suffixes.
 */
const buildIdentifiers = (points) => {
  const used = new Set();
  return points.map((p, i) => {
    let base = (p.name || `WP${i + 1}`)
      .toUpperCase()
      .replace(/[^A-Z0-9]/g, "")
      .slice(0, 10);
    if (!base) base = `WP${i + 1}`;
    let id = base;
    let n = 2;
    while (used.has(id)) {
      const suffix = String(n++);
      id = base.slice(0, 10 - suffix.length) + suffix;
    }
    used.add(id);
    return id;
  });
};

/** Garmin FPL v1 XML — ForeFlight's standard flight-plan import format. */
export const buildFplXml = (route) => {
  const points = foreflightPoints(route);
  const ids = buildIdentifiers(points);

  const waypoints = points
    .map(
      (p, i) => `    <waypoint>
      <identifier>${ids[i]}</identifier>
      <type>USER WAYPOINT</type>
      <country-code></country-code>
      <lat>${p.lat.toFixed(6)}</lat>
      <lon>${p.lon.toFixed(6)}</lon>
      <comment>${xmlEscape(p.name || "")}</comment>
    </waypoint>`,
    )
    .join("\n");

  const routePoints = ids
    .map(
      (id) => `    <route-point>
      <waypoint-identifier>${id}</waypoint-identifier>
      <waypoint-type>USER WAYPOINT</waypoint-type>
      <waypoint-country-code></waypoint-country-code>
    </route-point>`,
    )
    .join("\n");

  return `<?xml version="1.0" encoding="utf-8"?>
<flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
  <created>${new Date().toISOString()}</created>
  <waypoint-table>
${waypoints}
  </waypoint-table>
  <route>
    <route-name>${xmlEscape(route.name.toUpperCase().slice(0, 25))}</route-name>
    <flight-plan-index>1</flight-plan-index>
${routePoints}
  </route>
</flight-plan>
`;
};

export const downloadFpl = (route) => {
  const xml = buildFplXml(route);
  downloadBlob(new Blob([xml], { type: "application/xml" }), `${safeFileName(route.name, "route")}.fpl`);
};

/** One route as a GPX `<rte>`, every point of it a named `<rtept>`. */
const gpxRoute = (route) => {
  const points = foreflightPoints(route);
  const ids = buildIdentifiers(points);

  const rtepts = points
    .map(
      (p, i) => `    <rtept lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}">
      <name>${xmlEscape(ids[i])}</name>
    </rtept>`,
    )
    .join("\n");

  return `  <rte>
    <name>${xmlEscape(route.name.toUpperCase().slice(0, 25))}</name>
${rtepts}
  </rte>`;
};

const gpxDocument = (rtes) => `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AV Tac Tools" xmlns="http://www.topografix.com/GPX/1/1">
${rtes.join("\n")}
</gpx>
`;

/**
 * GPX 1.1 route — the most widely-recognized ForeFlight import for a route of
 * user waypoints. A single <rte> with named <rtept>s imports as one route
 * (rather than scattering loose user waypoints).
 */
export const buildGpxXml = (route) => gpxDocument([gpxRoute(route)]);

export const downloadGpx = (route) => {
  const xml = buildGpxXml(route);
  downloadBlob(new Blob([xml], { type: "application/gpx+xml" }), `${safeFileName(route.name, "route")}.gpx`);
};

// ---- A set of routes ---------------------------------------------------------------------------

/**
 * The routes of a set that a file can hold, in the set's order: a route is a line, so one of fewer
 * than two points is left out. Whether a route is hidden on the map does not come into it.
 */
export const exportableRoutes = (routes) =>
  (routes ?? []).filter((route) => (route?.points?.length ?? 0) >= 2);

/**
 * A set of routes as ONE GPX 1.1 file: a `<rte>` for each of its routes, in the order given, each
 * exactly what buildGpxXml writes for that route alone (so a set of one route is that route's file,
 * byte for byte). Identifiers are numbered within a route, never across the file: the same name in
 * two routes stays the same name in both, which is what comes back when the file is opened again.
 *
 * GPX is the one of these formats that holds more than one route: Garmin's flight plan schema has
 * one `<route>` in a file and ForeFlight's URL is one route, so a set has neither, and those are
 * made a route at a time. A FLOT is not a route and is in no export.
 */
export const buildSetGpxXml = (routes) => gpxDocument(exportableRoutes(routes).map(gpxRoute));

/** `set`: `{ name, routes }`. The file is named for the set. */
export const downloadSetGpx = (set) => {
  const xml = buildSetGpxXml(set.routes);
  downloadBlob(new Blob([xml], { type: "application/gpx+xml" }), `${safeFileName(set.name, "routes")}.gpx`);
};
