import React, { useMemo } from "react";
import { Marker, Polyline, Tooltip, Popup, useMap } from "react-leaflet";
import L from "leaflet";

/**
 * Icons are pure functions of (designation, colour), so a real mission's
 * hundreds of points only ever need a handful of distinct ones. Cache them:
 * handing Leaflet a new divIcon identity per render makes it replace every
 * marker's DOM element, which is what made large imported routes stutter.
 */
const iconCache = new Map();

// Snap radius, in screen pixels, for dragging a route point onto a local point.
const SNAP_PX = 18;

/** Nearest local point within SNAP_PX of a dropped location, or null. */
const snapToLocalPoint = (map, lat, lng, snapPoints) => {
  if (!map || !snapPoints || snapPoints.length === 0) return null;
  const drop = map.latLngToContainerPoint([lat, lng]);
  let best = null;
  let bestDist = SNAP_PX;
  for (const p of snapPoints) {
    if (p.lat == null || p.lon == null) continue;
    const d = drop.distanceTo(map.latLngToContainerPoint([p.lat, p.lon]));
    if (d <= bestDist) {
      bestDist = d;
      best = p;
    }
  }
  return best;
};

const ROLE_LABELS = {
  start: "START",
  release: "RP",
  target: "TARGET",
  waypoint: "POINT",
};

const PT_TYPE_LABELS = {
  turn: "Checkpoint",
  ip: "RP / IP",
  target: "LZ/PZ",
};

const smallDotIcon = (color) => {
  const size = 10;
  return L.divIcon({
    className: "msnx-point-icon",
    html: `<div style="width:${size}px;height:${size}px;border-radius:50%;background:${color};border:1px solid white;opacity:0.85;box-shadow:0 0 2px rgba(0,0,0,0.6);"></div>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
};

const shapeIcon = (shape, color) => {
  const size = 20;
  let shapeHtml;

  if (shape === "circle") {
    shapeHtml = `<div style="width:${size}px;height:${size}px;border-radius:50%;background:${color};border:2px solid white;box-shadow:0 0 3px rgba(0,0,0,0.6);"></div>`;
  } else if (shape === "triangle") {
    shapeHtml = `<div style="width:0;height:0;border-left:${size / 2}px solid transparent;border-right:${size / 2}px solid transparent;border-bottom:${size}px solid ${color};filter:drop-shadow(0 0 2px rgba(0,0,0,0.6));"></div>`;
  } else if (shape === "square") {
    shapeHtml = `<div style="width:${size - 4}px;height:${size - 4}px;background:${color};border:2px solid white;box-shadow:0 0 3px rgba(0,0,0,0.6);"></div>`;
  } else {
    // diamond
    shapeHtml = `<div style="width:${size - 4}px;height:${size - 4}px;background:${color};border:2px solid white;transform:rotate(45deg);box-shadow:0 0 3px rgba(0,0,0,0.6);"></div>`;
  }

  return L.divIcon({
    className: "msnx-point-icon",
    html: shapeHtml,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
};

const createIcon = (point, color) => {
  // Sketched points carry a designation; mirror AMPS iconography:
  // Turn = circle, IP/RP = square, Target = triangle, shaping = small dot.
  if (point.kind) {
    if (point.kind === "shaping") return smallDotIcon(color);
    if (point.ptType === "ip") return shapeIcon("square", color);
    if (point.ptType === "target") return shapeIcon("triangle", color);
    return shapeIcon("circle", color);
  }

  // Imported points keep the original name-heuristic roles.
  if (point.role === "waypoint") return smallDotIcon(color);
  if (point.role === "start") return shapeIcon("circle", color);
  if (point.role === "release") return shapeIcon("triangle", color);
  return shapeIcon("diamond", color);
};

export const buildIcon = (point, color) => {
  const key = `${point.kind ?? ""}|${point.ptType ?? ""}|${point.role ?? ""}|${color}`;
  let icon = iconCache.get(key);
  if (!icon) {
    icon = createIcon(point, color);
    iconCache.set(key, icon);
  }
  return icon;
};

const pointDescription = (point) => {
  if (point.kind === "amps") return PT_TYPE_LABELS[point.ptType] || "AMPS point";
  if (point.kind === "shaping") return "Shaping point (serpentine)";
  return ROLE_LABELS[point.role];
};

const MsnxRouteLayer = ({
  routes,
  onUpdatePosition,
  onInsertPoint,
  onPointContextMenu,
  snapPoints,
}) => {
  const map = useMap();
  if (!routes || routes.length === 0) return null;

  return (
    <>
      {routes.map((route) => {
        if (route.visible === false) return null;
        const positions = route.points.map((p) => [p.lat, p.lon]);

        return (
          <React.Fragment key={route.id}>
            <Polyline
              positions={positions}
              pathOptions={{ color: route.color, weight: 3 }}
              eventHandlers={{
                contextmenu: (e) => {
                  L.DomEvent.stop(e);
                  e.originalEvent.preventDefault();
                  onInsertPoint(
                    route.id,
                    e.latlng.lat,
                    e.latlng.lng,
                    e.originalEvent.clientX,
                    e.originalEvent.clientY,
                  );
                },
              }}
            />
            {route.points.map((point, pointIndex) => {
              const showLabel = point.kind ? point.kind === "amps" : point.role !== "waypoint";
              const label =
                point.name.replace(/^\./, "") || ROLE_LABELS[point.role] || "POINT";
              const eventHandlers = {
                dragend: (e) => {
                  const marker = e.target;
                  let { lat, lng } = marker.getLatLng();
                  // Magnetize onto a nearby local point so the route line snaps
                  // to its exact coordinates and adopts its charted elevation.
                  const snap = snapToLocalPoint(map, lat, lng, snapPoints);
                  let chartElevationFt;
                  if (snap) {
                    lat = snap.lat;
                    lng = snap.lon;
                    marker.setLatLng([lat, lng]);
                    if (typeof snap.elevationFt === "number") chartElevationFt = snap.elevationFt;
                  }
                  onUpdatePosition(route.id, point.id, lat, lng, chartElevationFt);
                },
              };
              if (onPointContextMenu) {
                eventHandlers.contextmenu = (e) => {
                  L.DomEvent.stop(e);
                  e.originalEvent.preventDefault();
                  onPointContextMenu(
                    route.id,
                    point.id,
                    e.originalEvent.clientX,
                    e.originalEvent.clientY,
                  );
                };
              }
              return (
                <Marker
                  key={point.uiId ?? point.id ?? `${route.id}-point-${pointIndex}`}
                  position={[point.lat, point.lon]}
                  icon={buildIcon(point, route.color)}
                  // Some AMPS routes emit their serpentine track as bare GPX
                  // rtepts with no point definition behind them. There is
                  // nothing in points.xml to write a new position back to, so a
                  // drag would only move the marker until the next re-import —
                  // don't offer it. Points that do have an AMPS id (including
                  // real CalcPtSerpentine shaping points) stay draggable.
                  draggable={Boolean(point.id)}
                  eventHandlers={eventHandlers}
                >
                  {showLabel && (
                    <Tooltip permanent direction="top" offset={[0, -12]}>
                      {label}
                    </Tooltip>
                  )}
                  <Popup>
                    <strong>{label}</strong>
                    <br />
                    Route: {route.name}
                    <br />
                    {pointDescription(point)}
                    {point.ele != null && (
                      <>
                        <br />
                        Elevation: {Math.round(point.ele)} m
                      </>
                    )}
                  </Popup>
                </Marker>
              );
            })}
          </React.Fragment>
        );
      })}
    </>
  );
};

/**
 * Memoized: a real imported mission renders a marker per route point (531 in a
 * 4-route AMPS file), so re-rendering this subtree on unrelated App state
 * changes — a context menu opening, a weather refresh — meant Leaflet
 * re-seating every one of those markers. All props are stable identities
 * (routes/snapPoints from state, handlers via useCallback), so the default
 * shallow compare keeps the layer still until the routes themselves change.
 */
export default React.memo(MsnxRouteLayer);
