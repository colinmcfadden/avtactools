import React from "react";
import { Marker, Circle, ImageOverlay, Tooltip, Popup } from "react-leaflet";
import L from "leaflet";
import { NMI_TO_M, RADAR_TYPES } from "./threatModel";
import { symbolDivIcon } from "../symbols/milsym";

// Fallback glyph (red diamond) when a threat has no renderable MIL-STD symbol.
const fallbackIcon = (color) =>
  L.divIcon({
    className: "threat-icon",
    html: `<div style="width:16px;height:16px;background:${color};border:2px solid white;transform:rotate(45deg);box-shadow:0 0 4px rgba(0,0,0,0.8);"></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  });

// The MIL-STD-2525 symbol from the threat's SIDC, falling back to the diamond.
// The name is shown by the marker's tooltip, so it isn't repeated as a label.
const threatIcon = (threat) =>
  symbolDivIcon(threat.milstdId, { size: 32 }) || fallbackIcon(threat.color);

const ringColor = (type) => (type === RADAR_TYPES.engagement ? "#ef4444" : "#fbbf24");

/**
 * Renders all visible threats: terrain-mask image overlays (detection/
 * engagement), range rings, and a draggable marker. Masks come pre-rendered
 * as PNG data URLs from the backend viewshed endpoint.
 */
const ThreatLayer = ({ threats, onMove, onEdit }) => {
  if (!threats || threats.length === 0) return null;

  return (
    <>
      {threats.map((threat) => {
        if (threat.visible === false) return null;
        const maskByType = new Map(
          (threat.mask?.radars || []).map((r) => [r.type, r.png]),
        );

        return (
          <React.Fragment key={threat.id}>
            {/* Terrain masks (drawn under rings/marker) */}
            {threat.mask?.bounds &&
              threat.radars.map((radar) => {
                const png = maskByType.get(radar.type);
                if (!png || radar.showMask === false) return null;
                return (
                  <ImageOverlay
                    key={`mask-${threat.id}-${radar.type}`}
                    url={png}
                    bounds={threat.mask.bounds}
                    opacity={1}
                  />
                );
              })}

            {/* Range rings */}
            {threat.radars.map((radar) =>
              radar.showRangeRings === false ? null : (
                <Circle
                  key={`ring-${threat.id}-${radar.type}`}
                  center={[threat.lat, threat.lon]}
                  radius={radar.rangeNmi * NMI_TO_M}
                  pathOptions={{
                    color: ringColor(radar.type),
                    weight: 1.5,
                    fill: false,
                    dashArray: radar.type === RADAR_TYPES.engagement ? undefined : "6 6",
                  }}
                />
              ),
            )}

            <Marker
              position={[threat.lat, threat.lon]}
              icon={threatIcon(threat)}
              draggable
              eventHandlers={{
                dragend: (e) => {
                  const { lat, lng } = e.target.getLatLng();
                  onMove(threat.id, lat, lng);
                },
              }}
            >
              <Tooltip permanent direction="top" offset={[0, -10]}>
                {threat.name}
              </Tooltip>
              <Popup>
                <strong>{threat.name}</strong>
                <br />
                {threat.milstdId}
                <br />
                {threat.radars.map((r) => (
                  <span key={r.type}>
                    {r.type === RADAR_TYPES.engagement ? "Engagement" : "Detection"}:{" "}
                    {r.rangeNmi} nmi, {r.antennaHeightFt} ft {r.aglNotMsl ? "AGL" : "MSL"}
                    <br />
                  </span>
                ))}
                {threat.maskError && (
                  <span style={{ color: "#b91c1c" }}>Mask error: {threat.maskError}</span>
                )}
                <br />
                <button onClick={() => onEdit(threat.id)}>Edit threat</button>
              </Popup>
            </Marker>
          </React.Fragment>
        );
      })}
    </>
  );
};

export default ThreatLayer;
