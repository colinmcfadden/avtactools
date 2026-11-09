import L from "leaflet";
import React from "react";
import { Marker } from "react-leaflet";

const SIZE = 14;
const icons = new Map();

// Heavier than a finished route's shaping dot, which all but vanishes on the VFR chart: solid, with a white ring and a dark edge that
// hold over imagery, a pale topo map and the chart's yellows alike.
const draftIcon = (color) => {
  if (!icons.has(color)) {
    icons.set(
      color,
      L.divIcon({
        className: "msnx-point-icon",
        html: `<div style="box-sizing:border-box;width:${SIZE}px;height:${SIZE}px;border-radius:50%;background:${color};border:2px solid white;box-shadow:0 0 0 1.5px rgba(0,0,0,0.85);"></div>`,
        iconSize: [SIZE, SIZE],
        iconAnchor: [SIZE / 2, SIZE / 2],
      }),
    );
  }
  return icons.get(color);
};

/** Where a route being sketched needs a plain dot: a designated point has a marker of its own. */
export const plainDraftPositions = (draftPoints) =>
  draftPoints.filter((p) => !p.designation).map((p) => [p.lat, p.lon]);

/**
 * A dot at each point of a route or boundary still being drawn. A line needs two points, so without these the first
 * click showed nothing and crews could not tell the tool had started. Not interactive, so a click on a dot still
 * reaches the map and places the next point.
 */
export default function DraftPointMarkers({ positions, color }) {
  return positions.map((position, i) => (
    <Marker key={i} position={position} icon={draftIcon(color)} interactive={false} keyboard={false} />
  ));
}
