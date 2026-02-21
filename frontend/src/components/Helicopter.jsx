import React, { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { calculateAngle, calculateHandlePos, getDistanceMeters } from '../utils/Helpers';

const getHeloIcon = (rot, sizePx = 40) => {
  // Normalize rotation for display (0-359)
  const displayRot = Math.round(((rot % 360) + 360) % 360);

  return L.divIcon({
    className: "helo-div-icon",
    html: `
            <div style="position: relative; display: flex; justify-content: center; align-items: center;">
                <div class="heading-readout" style="
                    position: absolute; 
                    top: -15px; 
                    background: rgba(0,0,0,0.3); 
                    color: white; 
                    padding: 2px 5px; 
                    border-radius: 2px; 
                    font-size: 9px; 
                    font-family: monospace;
                    pointer-events: none;
                    white-space: nowrap;
                ">
                    ${displayRot}°
                </div>
                
                <div style="transform: rotate(${rot || 0}deg); display: flex;">
                    <img src="/icons/helicopter.png" style="width: 100%; height: 100%; object-fit: contain;" />
                </div>
            </div>`,
    iconSize: [sizePx, sizePx],
    iconAnchor: [sizePx / 2, sizePx / 2],
  });
};

const rotateHandleIcon = L.divIcon({
  className: "rotate-handle",
  html: `<div style="
        background: white; 
        border: 2px solid #FF8C00; 
        width: 14px; height: 14px; 
        border-radius: 50%; 
        display: flex; 
        align-items: center; 
        justify-content: center; 
        box-shadow: 0 2px 4px rgba(0,0,0,0.3); 
        cursor: grab;
    ">
        <span style="font-size: 10px; color: #FF8C00; font-weight: bold;">↻</span>
    </div>`,
  iconSize: [14, 14],
  iconAnchor: [7, 7],
});



const Helicopter = ({ asset, updateAsset, deleteAsset, allAssets }) => {
  const map = useMap();
  const heloRef = useRef(null);
  const handleRef = useRef(null);
  const linesLayerRef = useRef(null);
  const stateRef = useRef(asset);
  const deleteAssetRef = useRef(deleteAsset);
  const allAssetsRef = useRef(allAssets);

  // Keep refs up to date without triggering re-renders
  useEffect(() => {
    stateRef.current = asset;
    deleteAssetRef.current = deleteAsset;
    allAssetsRef.current = allAssets;
  }, [asset, deleteAsset, allAssets]);

  const calculateSizePx = (lat) => {
    const zoom = map.getZoom();
    const rotorDiameterMeters = 16.357; // 53' 8" to meters
    const metersPerPx = 156543.03392 * Math.cos((lat * Math.PI) / 180) / Math.pow(2, zoom);
    return Math.max(rotorDiameterMeters / metersPerPx, 15);
  };

  // --- 1. SETUP & EVENTS (Runs ONLY ONCE per helicopter) ---
  useEffect(() => {
    // FIX 1: Calculate the size immediately 
    const initialSize = calculateSizePx(asset.lat);

    const helo = L.marker([asset.lat, asset.lon], {
      // FIX 2: Pass initialSize to the icon generator
      icon: getHeloIcon(asset.rotation || 0, initialSize), 
      draggable: true,
      zIndexOffset: 500,
    }).addTo(map);

    const handle = L.marker(
      calculateHandlePos(asset.lat, asset.lon, asset.rotation || 0),
      {
        icon: rotateHandleIcon,
        draggable: true,
        zIndexOffset: 1000,
        opacity: 0,
      }
    ).addTo(map);

    const linesGroup = L.layerGroup().addTo(map);
    linesLayerRef.current = linesGroup;

    heloRef.current = helo;
    handleRef.current = handle;

    const handleZoom = () => {
      const newSize = calculateSizePx(stateRef.current.lat);
      helo.setIcon(getHeloIcon(stateRef.current.rotation, newSize));
    };
    map.on("zoomend", handleZoom);

    helo.on("contextmenu", (e) => {
      L.DomEvent.stopPropagation(e);
      L.DomEvent.preventDefault(e);
      if (window.confirm("Delete this helicopter?")) {
        deleteAssetRef.current(asset.id); // Uses the ref so we don't need it in deps
      }
    });

    // --- DYNAMIC PROXIMITY LINES ---
    const drawLines = (currentLat, currentLon) => {
      linesGroup.clearLayers(); // Erase old lines
      
      const otherHelos = allAssetsRef.current.filter(a => a.type === "helo" && a.id !== asset.id);
      
      otherHelos.forEach(other => {
         const dist = getDistanceMeters(currentLat, currentLon, other.lat, other.lon);
         const isViolation = dist < 59;
         const midLat = (currentLat + other.lat) / 2;
         const midLon = (currentLon + other.lon) / 2;
         
         const line = L.polyline([[currentLat, currentLon], [other.lat, other.lon]], {
            color: isViolation ? "#dc2626" : "#9ca3af",
            dashArray: "6, 6",
            weight: 2,
            opacity: 0.8,
            interactive: false
         });
         
         const badge = L.marker([midLat, midLon], {
            icon: L.divIcon({
              className: "distance-badge-icon",
              html: `<div style="
                background: ${isViolation ? '#dc2626' : '#374151'}; 
                color: white; 
                padding: 2px 6px; 
                border-radius: 10px; 
                font-size: 10px; 
                font-weight: bold; 
                border: 1px solid rgba(255,255,255,0.5); 
                white-space: nowrap; 
                width: max-content;
                transform: translate(-50%, -50%);
                box-shadow: 0 2px 4px rgba(0,0,0,0.5);
              ">${Math.round(dist)}m</div>`,
              iconSize: [0, 0], 
            }),
            interactive: false
         });
         
         linesGroup.addLayer(line);
         linesGroup.addLayer(badge);
      });
    };

    // --- HOVER LOGIC ---
    let isHovering = false;
    let isDragging = false;

    const show = () => { 
      isHovering = true; 
      handle.setOpacity(1); 
      drawLines(stateRef.current.lat, stateRef.current.lon); // Draw lines on hover
    };
    
    const hide = () => {
      isHovering = false;
      setTimeout(() => {
        if (!isHovering && !isDragging && !handle.dragging?._draggable?._dragging) {
          handle.setOpacity(0);
          linesGroup.clearLayers(); // Erase lines when hover ends
        }
      }, 50);
    };

    helo.on("mouseover", show);
    helo.on("mouseout", hide);
    handle.on("mouseover", show);
    handle.on("mouseout", hide);

    // --- DRAG LOGIC ---
    helo.on("dragstart", () => { isDragging = true; });
    handle.on("dragstart", () => { isDragging = true; });

    helo.on("drag", (e) => {
      const pos = e.target.getLatLng();
      stateRef.current.lat = pos.lat;
      stateRef.current.lon = pos.lng;
      handle.setLatLng(calculateHandlePos(pos.lat, pos.lng, stateRef.current.rotation));

      drawLines(pos.lat, pos.lng);
    });

    handle.on("drag", (e) => {
      handle.setOpacity(1);
      const mousePos = e.target.getLatLng();
      const angle = calculateAngle(
        stateRef.current.lat, stateRef.current.lon, mousePos.lat, mousePos.lng
      );
      stateRef.current.rotation = angle;

      const currentSize = calculateSizePx(stateRef.current.lat);
      helo.setIcon(getHeloIcon(angle, currentSize));
      handle.setLatLng(calculateHandlePos(stateRef.current.lat, stateRef.current.lon, angle));

      drawLines(stateRef.current.lat, stateRef.current.lon);
    });

    const saveToReact = () => {
        isDragging = false;
      updateAsset(asset.id, {
        lat: stateRef.current.lat,
        lon: stateRef.current.lon,
        rotation: stateRef.current.rotation,
      });
      hide();
    };

    helo.on("dragend", saveToReact);
    handle.on("dragend", saveToReact);

    return () => {
      map.off("zoomend", handleZoom); 
      helo.remove();
      handle.remove();
      linesGroup.clearLayers(); // Clear lines on cleanup
      linesGroup.remove();
    };
  }, [map, asset.id]);

  // --- 2. SYNC EFFECT (Runs when React updates the asset from outside) ---
  useEffect(() => {
    if (!heloRef.current || !handleRef.current) return;

    // Recalculate scale in case latitude changed significantly
    const currentSize = calculateSizePx(asset.lat);
    
    // Smoothly update the existing markers without destroying them
    heloRef.current.setLatLng([asset.lat, asset.lon]);
    heloRef.current.setIcon(getHeloIcon(asset.rotation || 0, currentSize));
    handleRef.current.setLatLng(calculateHandlePos(asset.lat, asset.lon, asset.rotation || 0));
  }, [asset.lat, asset.lon, asset.rotation, map]);

  return null;
};

export default Helicopter;