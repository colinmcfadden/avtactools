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
  const safeName = route.name.replace(/[^\w-]+/g, "_") || "route";
  downloadBlob(new Blob([xml], { type: "application/xml" }), `${safeName}.fpl`);
};

/**
 * GPX 1.1 route — the most widely-recognized ForeFlight import for a route of
 * user waypoints. A single <rte> with named <rtept>s imports as one route
 * (rather than scattering loose user waypoints).
 */
export const buildGpxXml = (route) => {
  const points = foreflightPoints(route);
  const ids = buildIdentifiers(points);

  const rtepts = points
    .map(
      (p, i) => `    <rtept lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}">
      <name>${xmlEscape(ids[i])}</name>
    </rtept>`,
    )
    .join("\n");

  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AV Tac Tools" xmlns="http://www.topografix.com/GPX/1/1">
  <rte>
    <name>${xmlEscape(route.name.toUpperCase().slice(0, 25))}</name>
${rtepts}
  </rte>
</gpx>
`;
};

export const downloadGpx = (route) => {
  const xml = buildGpxXml(route);
  const safeName = route.name.replace(/[^\w-]+/g, "_") || "route";
  downloadBlob(new Blob([xml], { type: "application/gpx+xml" }), `${safeName}.gpx`);
};
