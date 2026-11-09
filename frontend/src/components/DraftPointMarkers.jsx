import React from "react";
import { Marker } from "react-leaflet";
import { buildIcon } from "../feature/msnxImport/MsnxRouteLayer";

const DOT = { kind: "shaping" };

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
    <Marker key={i} position={position} icon={buildIcon(DOT, color)} interactive={false} keyboard={false} />
  ));
}
