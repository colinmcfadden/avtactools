import React, { useRef, useMemo } from "react";
import { Marker } from "react-leaflet";
import L from "leaflet";
import { symbolParts } from "../symbols/milsym";

const UnitMarker = ({ data, updateUnitPosition, deleteUnit, onEdit }) => {
  const markerRef = useRef(null);

  // 1. Create the Icon (Memoized to prevent flickering). We use divIcon so it
  // can be wrapped in the drag-lifter for the mobile drag-lift effect.
  // A MIL-STD symbol (from the unit builder) renders via milsymbol; older
  // image-based presets fall back to their SVG file.
  const unitIcon = useMemo(() => {
    const parts = data.sidc
      ? symbolParts(data.sidc, {
          size: 32,
          uniqueDesignation: data.uniqueDesignation,
          higherFormation: data.higherFormation,
        })
      : null;

    if (parts) {
      return L.divIcon({
        className: "unit-div-icon",
        html: `<div class="drag-lifter" style="width:${parts.size.width}px;height:${parts.size.height}px;display:flex;align-items:center;justify-content:center;">${parts.svg}</div>`,
        iconSize: [parts.size.width, parts.size.height],
        iconAnchor: [parts.anchor.x, parts.anchor.y],
      });
    }

    return L.divIcon({
      className: "unit-div-icon",
      html: `
            <div class="drag-lifter" style="position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                <img src="${data.path}" style="width: 50px; height: 30px; object-fit: contain; pointer-events: none;" draggable="false" />
            </div>
        `,
      iconSize: [50, 30],
      iconAnchor: [25, 15],
    });
  }, [data.sidc, data.uniqueDesignation, data.higherFormation, data.path]);

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
      click(e) {
        L.DomEvent.stopPropagation(e);
        onEdit?.(data); // open the symbol editor
      },
      contextmenu(e) {
        L.DomEvent.stopPropagation(e); // Prevent map context menu
        if (window.confirm("Delete this unit?")) {
          deleteUnit(data.id);
        }
      },
    }),
    [data, updateUnitPosition, deleteUnit, onEdit], // Dependencies
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