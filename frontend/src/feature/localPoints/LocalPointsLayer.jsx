import React from "react";
import { Marker, Tooltip, Popup } from "react-leaflet";
import L from "leaflet";

// .LPS icon files aren't shipped with the app, so map the common AMPS icon
// families to simple glyph markers: LZ-style icons render as a triangle,
// everything else as a small diamond.
const isLzIcon = (icon) => /lz|pz/i.test(icon || "");

const localPointIcon = (point, color) => {
  const html = isLzIcon(point.icon)
    ? `<div style="width:0;height:0;border-left:7px solid transparent;border-right:7px solid transparent;border-bottom:13px solid ${color};filter:drop-shadow(0 0 2px rgba(0,0,0,0.7));"></div>`
    : `<div style="width:10px;height:10px;background:${color};border:1.5px solid white;transform:rotate(45deg);box-shadow:0 0 2px rgba(0,0,0,0.7);"></div>`;
  return L.divIcon({
    className: "local-point-icon",
    html,
    iconSize: [14, 14],
    iconAnchor: [7, 7],
  });
};

/** Renders every visible local point set. Hidden sets render nothing. */
const LocalPointsLayer = ({ pointSets }) => {
  if (!pointSets || pointSets.length === 0) return null;

  return (
    <>
      {pointSets.map((set) => {
        if (set.visible === false) return null;
        return set.points.map((point) => (
          <Marker
            key={point.id}
            position={[point.lat, point.lon]}
            icon={localPointIcon(point, set.color)}
          >
            <Tooltip direction="top" offset={[0, -8]}>
              {point.name}
            </Tooltip>
            <Popup>
              <strong>{point.name}</strong>
              {point.description && (
                <>
                  <br />
                  {point.description}
                </>
              )}
              <br />
              Set: {set.name}
              {point.elevationFt != null && (
                <>
                  <br />
                  Elevation: {point.elevationFt} ft
                </>
              )}
            </Popup>
          </Marker>
        ));
      })}
    </>
  );
};

export default LocalPointsLayer;
