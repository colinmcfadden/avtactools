import React from "react";
import { Marker, Polyline, Tooltip, Popup } from "react-leaflet";
import L from "leaflet";

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

const buildIcon = (point, color) => {
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

const pointDescription = (point) => {
  if (point.kind === "amps") return PT_TYPE_LABELS[point.ptType] || "AMPS point";
  if (point.kind === "shaping") return "Shaping point (serpentine)";
  return ROLE_LABELS[point.role];
};

const MsnxRouteLayer = ({ routes, onUpdatePosition, onInsertPoint, onPointContextMenu }) => {
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
            {route.points.map((point) => {
              const showLabel = point.kind ? point.kind === "amps" : point.role !== "waypoint";
              const label =
                point.name.replace(/^\./, "") || ROLE_LABELS[point.role] || "POINT";
              const eventHandlers = {
                dragend: (e) => {
                  const { lat, lng } = e.target.getLatLng();
                  onUpdatePosition(route.id, point.id, lat, lng);
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
                  key={point.id}
                  position={[point.lat, point.lon]}
                  icon={buildIcon(point, route.color)}
                  draggable
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

export default MsnxRouteLayer;
