import React, { useRef, useMemo, useEffect } from "react";
import { Rectangle, Marker, useMap } from "react-leaflet";
import L from "leaflet";

const ExportBox = ({ bounds, onUpdate, aspectRatio }) => {
  const map = useMap();
  
  // Refs to access standard Leaflet objects directly
  const rectRef = useRef(null);
  const resizeRef = useRef(null);
  const moveRef = useRef(null);

  // --- ICONS ---
  const resizeIcon = new L.DivIcon({
    className: "resize-handle-se",
    html: `<div style="width: 16px; height: 16px; background: white; border: 2px solid red; cursor: nwse-resize;"></div>`,
    iconSize: [16, 16],
    iconAnchor: [8, 8],
  });

  const moveIcon = new L.DivIcon({
    className: "move-handle",
    html: `<div style="width: 24px; height: 24px; background: rgba(255,255,255,0.8); border: 2px solid #0056b3; border-radius: 4px; display: flex; align-items: center; justify-content: center; cursor: move;">
            <span style="font-size: 16px; color: #0056b3;">✥</span>
           </div>`,
    iconSize: [24, 24],
    iconAnchor: [12, 12],
  });

  // --- CALCULATIONS ---
  
  // Convert standard bounds [[lat1,lng1], [lat2,lng2]] to organized NSEW
  const getCoords = (b) => {
    return {
      n: Math.max(b[0][0], b[1][0]),
      s: Math.min(b[0][0], b[1][0]),
      e: Math.max(b[0][1], b[1][1]),
      w: Math.min(b[0][1], b[1][1]),
    };
  };

  const getCenter = (c) => [(c.n + c.s) / 2, (c.e + c.w) / 2];

  // --- EVENT HANDLERS ---

  // 1. RESIZE HANDLER (Bottom-Right / South-East)
  const resizeHandlers = useMemo(() => ({
    drag: (e) => {
      // Get the "Fixed" Anchor (North-West corner)
      // We rely on the PROPS for the anchor, assuming it hasn't changed mid-drag
      const current = getCoords(bounds);
      const anchorLat = current.n; 
      const anchorLng = current.w;

      const mouse = e.target.getLatLng();
      
      // Calculate new South-East based on Mouse
      let newSouth = mouse.lat;
      let newEast = mouse.lng;

      if (aspectRatio) {
         // Lock Ratio Logic
         const dLat = anchorLat - newSouth; // Height (Positive)
         const latRad = (anchorLat + newSouth) / 2 * (Math.PI / 180);
         const lngScale = 1 / Math.cos(latRad);
         
         // Width = Height * Ratio
         const dLng = dLat * aspectRatio * lngScale;
         
         newEast = anchorLng + dLng;
      }

      // VISUAL UPDATE ONLY (Bypass React)
      const newBounds = [[anchorLat, anchorLng], [newSouth, newEast]];
      if (rectRef.current) rectRef.current.setBounds(newBounds);
      if (moveRef.current) moveRef.current.setLatLng([(anchorLat + newSouth)/2, (anchorLng + newEast)/2]);
      
      // Keep the resize handle locked to the corner logic if using ratio
      if (aspectRatio && resizeRef.current) {
         resizeRef.current.setLatLng([newSouth, newEast]);
      }
    },
    dragend: (e) => {
      // NOW we update React State
      if (rectRef.current) {
         const b = rectRef.current.getBounds();
         onUpdate("red", [[b.getSouth(), b.getWest()], [b.getNorth(), b.getEast()]]);
      }
    }
  }), [bounds, aspectRatio, onUpdate]);

  // 2. MOVE HANDLER (Center)
  const moveHandlers = useMemo(() => ({
    drag: (e) => {
      const mouse = e.target.getLatLng();
      const current = getCoords(bounds);
      const center = getCenter(current);
      
      // Calculate Delta
      const dLat = mouse.lat - center[0];
      const dLng = mouse.lng - center[1];

      const newBounds = [
        [current.s + dLat, current.w + dLng], 
        [current.n + dLat, current.e + dLng]
      ];

      // Visual Update
      if (rectRef.current) rectRef.current.setBounds(newBounds);
      if (resizeRef.current) resizeRef.current.setLatLng([current.s + dLat, current.e + dLng]);
    },
    dragend: (e) => {
      if (rectRef.current) {
         const b = rectRef.current.getBounds();
         onUpdate("red", [[b.getSouth(), b.getWest()], [b.getNorth(), b.getEast()]]);
      }
    }
  }), [bounds, onUpdate]);

  // --- RENDER ---
  const coords = getCoords(bounds);
  const center = getCenter(coords);

  // We explicitly update positions via Effect to ensure handles snap to 
  // the correct place if 'bounds' changes externally (or after a dragend)
  useEffect(() => {
     if (resizeRef.current) resizeRef.current.setLatLng([coords.s, coords.e]);
     if (moveRef.current) moveRef.current.setLatLng(center);
  }, [bounds]);

  return (
    <>
      <Rectangle 
        ref={rectRef}
        bounds={bounds} 
        pathOptions={{ color: "red", weight: 2, fillOpacity: 0.1 }} 
      />
      
      {/* Center Move Handle */}
      <Marker 
        ref={moveRef}
        position={center} 
        draggable={true} 
        eventHandlers={moveHandlers}
        icon={moveIcon}
        zIndexOffset={1000}
      />

      {/* South-East Resize Handle */}
      <Marker 
        ref={resizeRef}
        position={[coords.s, coords.e]} 
        draggable={true} 
        eventHandlers={resizeHandlers}
        icon={resizeIcon}
        zIndexOffset={1000}
      />
    </>
  );
};

export default ExportBox;