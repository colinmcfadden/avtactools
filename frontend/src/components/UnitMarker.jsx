import React, { useRef, useMemo } from "react";
import { Marker } from "react-leaflet";
import L from "leaflet";

const UnitMarker = ({ data, updateUnitPosition, deleteUnit }) => {
  const markerRef = useRef(null);

  // 1. Create the Icon (Memoized to prevent flickering)
  // We use divIcon instead of icon so we can wrap it in the drag-lifter!
  const unitIcon = useMemo(
    () =>
      L.divIcon({
        className: "unit-div-icon",
        html: `
            <div class="drag-lifter" style="position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                <img src="${data.path}" style="width: 50px; height: 30px; object-fit: contain; pointer-events: none;" draggable="false" />
            </div>
        `,
        iconSize: [50, 30],
        iconAnchor: [25, 15],
      }),
    [data.path],
  );

  // 2. MEMOIZE EVENT HANDLERS
  const eventHandlers = useMemo(
    () => ({
      // Float the icon up when dragging starts
      dragstart(e) {
        const marker = e.target;
        if (marker._icon) {
          L.DomUtil.addClass(marker._icon, 'mobile-lifting');
        }
      },
      // Drop it back down and save when finished
      dragend(e) {
        const marker = e.target;
        if (marker._icon) {
          L.DomUtil.removeClass(marker._icon, 'mobile-lifting');
        }
        
        const { lat, lng } = marker.getLatLng();
        updateUnitPosition(data.id, lat, lng);
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
      draggable={true} // Standard Leaflet dragging works perfectly here!
      eventHandlers={eventHandlers}
      zIndexOffset={600}
    />
  );
};

export default UnitMarker;