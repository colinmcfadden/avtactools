import React from "react";
import { Polyline, Marker } from "react-leaflet";
import L from "leaflet";
import { getDistanceMeters } from "../utils/Helpers";

const ProximityLines = ({ assets, activeDrag }) => {
  // If we aren't dragging anything, render nothing
  if (!activeDrag) return null;

  // Find all other helicopters
  const otherHelos = assets.filter(
    (a) => a.type === "helo" && a.id !== activeDrag.id
  );

  return otherHelos.map((helo) => {
    const dist = getDistanceMeters(activeDrag.lat, activeDrag.lon, helo.lat, helo.lon);
    const isViolation = dist < 60;

    // Calculate the midpoint to place the label
    const midLat = (activeDrag.lat + helo.lat) / 2;
    const midLon = (activeDrag.lon + helo.lon) / 2;

    // Create a custom badge for the distance
    const labelIcon = L.divIcon({
      className: "distance-badge-icon",
      html: `<div style="
        background: ${isViolation ? "#dc2626" : "#374151"}; 
        color: white; 
        padding: 2px 6px; 
        border-radius: 10px; 
        font-size: 10px; 
        font-weight: bold; 
        border: 1px solid rgba(255,255,255,0.5); 
        white-space: nowrap; 
        transform: translate(-50%, -50%); /* Centers the badge perfectly */
        box-shadow: 0 2px 4px rgba(0,0,0,0.5);
      ">${Math.round(dist)}m</div>`,
      iconSize: [0, 0], 
    });

    return (
      <React.Fragment key={`line-${helo.id}`}>
        <Polyline
          positions={[
            [activeDrag.lat, activeDrag.lon],
            [helo.lat, helo.lon],
          ]}
          pathOptions={{
            color: isViolation ? "#dc2626" : "#9ca3af",
            dashArray: "6, 6",
            weight: 2,
            opacity: 0.8,
          }}
          interactive={false} // Prevents the lines from blocking map clicks
        />
        <Marker position={[midLat, midLon]} icon={labelIcon} interactive={false} />
      </React.Fragment>
    );
  });
};

export default ProximityLines;