import React, { useRef, useMemo } from "react";
import { Marker } from "react-leaflet";
import L from "leaflet";

const UnitMarker = ({ data, updateUnitPosition, deleteUnit }) => {
  const markerRef = useRef(null);

  // 1. Create the Icon (Memoized to prevent flickering)
  const unitIcon = useMemo(
    () =>
      L.icon({
        iconUrl: data.path,
        iconSize: [50, 30],
        iconAnchor: [25, 15],
        popupAnchor: [0, -15],
        // Add a shadowUrl if you want, or leave null
        shadowUrl: null,
      }),
    [data.path],
  );

  // 2. MEMOIZE EVENT HANDLERS (Crucial for Dragging)
  // If we don't do this, the handlers get recreated on every render,
  // causing the marker to lose its "drag" state and snap back.
  const eventHandlers = useMemo(
    () => ({
      dragend() {
        const marker = markerRef.current;
        if (marker) {
          const { lat, lng } = marker.getLatLng();
          // Call the update function from App.js
          updateUnitPosition(data.id, lat, lng);
        }
      },
      contextmenu(e) {
        L.DomEvent.stopPropagation(e); // Prevent map context menu
        if (window.confirm("Delete this unit?")) {
          deleteUnit(data.id);
        }
      },
    }),
    [data.id, updateUnitPosition, deleteUnit], // Dependencies
  );

  return (
    <Marker
      ref={markerRef}
      position={[data.lat, data.lon]}
      icon={unitIcon}
      draggable={true}
      eventHandlers={eventHandlers}
    />
  );
};

export default UnitMarker;
