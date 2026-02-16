import React, { useEffect, useRef, useMemo } from "react";
import { Rectangle, Marker } from "react-leaflet";
import L from "leaflet";

const resizeIcon = L.divIcon({
  className: "export-handle resize-handle",
  html: `<div style="background: white; border: 2px solid #ef4444; width: 12px; height: 12px; cursor: nwse-resize; box-shadow: 0 1px 3px rgba(0,0,0,0.3);"></div>`,
  iconSize: [12, 12],
  iconAnchor: [6, 6],
});

const moveIcon = L.divIcon({
  className: "export-handle move-handle",
  html: `<div style="background: white; border: 2px solid #0056b3; width: 24px; height: 24px; border-radius: 50%; display: flex; align-items: center; justify-content: center; cursor: move; box-shadow: 0 2px 4px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" width="16" height="16" fill="#0056b3"><path d="M12 2L15 5h-2v14h2l-3 3-3-3h2V5H9l3-3zM5 12l3-3v2h8V9l3 3-3 3v-2H8v2L5 12z"/></svg></div>`,
  iconSize: [24, 24],
  iconAnchor: [12, 12],
});

const ExportBox = ({ id, bounds, onUpdate }) => {
  const rectRef = useRef(null);

  // We use useMemo to calculate the initial positions so we don't recalculate on unrelated renders
  const { south, west, north, east, center } = useMemo(() => {
    const b = L.latLngBounds(bounds);
    return {
      south: b.getSouth(),
      west: b.getWest(),
      north: b.getNorth(),
      east: b.getEast(),
      center: b.getCenter(),
    };
  }, [bounds]);

  // --- 1. RESIZE HANDLER (Direct DOM Update) ---
  const onResizeDrag = (e, anchorLat, anchorLng) => {
    // This runs 60fps. We CANNOT call setState here.
    const marker = e.target;
    const newLat = marker.getLatLng().lat;
    const newLng = marker.getLatLng().lng;

    // Directly update the Rectangle layer on the map
    if (rectRef.current) {
      rectRef.current.setBounds([
        [anchorLat, anchorLng],
        [newLat, newLng]
      ]);
    }
  };

  const onResizeEnd = (e, anchorLat, anchorLng) => {
    // Only update React state when the user LETS GO
    const marker = e.target;
    onUpdate(id, [
      [anchorLat, anchorLng],
      [marker.getLatLng().lat, marker.getLatLng().lng]
    ]);
  };

  // --- 2. MOVE HANDLER (Direct DOM Update) ---
  const onMoveDrag = (e) => {
    const marker = e.target;
    const newCenter = marker.getLatLng();
    
    const latOffset = (north - south) / 2;
    const lngOffset = (east - west) / 2;

    const newBounds = [
      [newCenter.lat - latOffset, newCenter.lng - lngOffset],
      [newCenter.lat + latOffset, newCenter.lng + lngOffset]
    ];

    // Update the blue rectangle instantly
    if (rectRef.current) {
      rectRef.current.setBounds(newBounds);
    }
  };

  const onMoveEnd = (e) => {
    const newCenter = e.target.getLatLng();
    const latOffset = (north - south) / 2;
    const lngOffset = (east - west) / 2;

    onUpdate(id, [
      [newCenter.lat - latOffset, newCenter.lng - lngOffset],
      [newCenter.lat + latOffset, newCenter.lng + lngOffset]
    ]);
  };

  return (
    <>
      <Rectangle
        ref={rectRef}
        bounds={bounds}
        pathOptions={{ color: "#ef4444", weight: 2, fillColor: "#ef4444", fillOpacity: 0.1, dashArray: "5, 5" }}
      />

      {/* Center Move Handle */}
      <Marker position={center} icon={moveIcon} draggable={true}
        eventHandlers={{ drag: onMoveDrag, dragend: onMoveEnd }} />

      {/* Corner Resize Handles */}
      <Marker position={[north, west]} icon={resizeIcon} draggable={true}
        eventHandlers={{ drag: (e) => onResizeDrag(e, south, east), dragend: (e) => onResizeEnd(e, south, east) }} />
      <Marker position={[north, east]} icon={resizeIcon} draggable={true}
        eventHandlers={{ drag: (e) => onResizeDrag(e, south, west), dragend: (e) => onResizeEnd(e, south, west) }} />
      <Marker position={[south, east]} icon={resizeIcon} draggable={true}
        eventHandlers={{ drag: (e) => onResizeDrag(e, north, west), dragend: (e) => onResizeEnd(e, north, west) }} />
      <Marker position={[south, west]} icon={resizeIcon} draggable={true}
        eventHandlers={{ drag: (e) => onResizeDrag(e, north, east), dragend: (e) => onResizeEnd(e, north, east) }} />
    </>
  );
};

export default ExportBox;