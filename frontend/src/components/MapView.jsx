import React, { useEffect, useRef, useState } from "react";
import {
  MapContainer,
  TileLayer,
  Marker,
  Popup,
  Polygon,
  useMap,
  Tooltip,
} from "react-leaflet";
import LZDimensions from "./LZDimensions";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { useMapEvents, Polyline, Rectangle } from "react-leaflet";
import PZMarker from "./PZMarker";
import UnitMarker from "./UnitMarker";
import SectorMarker from "./SectorMarker";
import ExportBox from "./ExportBox";
import Doghouse from "./Doghouse"; 
import Helicopter from "./Helicopter";
import * as htmlToImage from 'html-to-image';

// Fix for default Leaflet marker icons in React
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: require("leaflet/dist/images/marker-icon-2x.png"),
  iconUrl: require("leaflet/dist/images/marker-icon.png"),
  shadowUrl: require("leaflet/dist/images/marker-shadow.png"),
});

// Custom Icons
const starIcon = new L.Icon({
  iconUrl: "https://upload.wikimedia.org/wikipedia/commons/2/29/Gold_Star.svg", // Placeholder for Green Star
  iconSize: [15, 15],
});

// Helper to auto-zoom to new location
function MapUpdater({ center }) {
  const map = useMap();
  useEffect(() => {
    if (center) {
      map.setView(center, 17);
    }
  }, [center, map]);
  return null;
}

function MapInteractionHandler({ 
  isDrawingLZ, setDrawingPoints, handleMapRightClick, setContextMenu 
}) {
  useMapEvents({
    contextmenu: (e) => {
      if (isDrawingLZ) return; // Don't interrupt drawing
      // Stop the default browser right-click menu
      e.originalEvent.preventDefault(); 
      handleMapRightClick(e.latlng.lat, e.latlng.lng, e.originalEvent.clientX, e.originalEvent.clientY);
    },
    click: (e) => {
      if (isDrawingLZ) {
        setDrawingPoints(prev => [...prev, [e.latlng.lat, e.latlng.lng]]);
      } else {
        setContextMenu(null); // Click anywhere else to close the menu
      }
    }
  });
  return null;
}

const GoAroundMarker = ({ data, updateGoAround, deleteGoAround }) => {
  const map = useMap();
  const markerRef = useRef(null);
  
  const rotationRef = useRef(data.rotation || 0);
  const dataRef = useRef(data);
  const updateRef = useRef(updateGoAround);
  const deleteRef = useRef(deleteGoAround);

  useEffect(() => {
    dataRef.current = data;
    updateRef.current = updateGoAround;
    deleteRef.current = deleteGoAround;
  }, [data, updateGoAround, deleteGoAround]);

  const arrowColor = "#FFC107";
  const stripePattern = `
        <defs>
            <pattern id="diagonalHatch" patternUnits="userSpaceOnUse" width="8" height="8" patternTransform="rotate(0)">
                <path d="M0,8 L8,8" style="stroke:black; stroke-width:2" />
            </pattern>
        </defs>`;

  const rightArrowPath = "M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z";
  const leftArrowPath = "M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z";

  const getHtml = (ga, rotation) => {
    const isRight = ga.direction === "right";
    const path = isRight ? rightArrowPath : leftArrowPath;

    const rotateIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="width: 14px; height: 14px;"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>`;

    return `
      <div class="drag-lifter doghouse-interactive-wrapper" style="position: relative; width: 100%; height: 100%; pointer-events: none;">
        
        <div class="ga-interaction-group" style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); width: 160px; height: 120px; pointer-events: auto; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.01); border-radius: 8px;">
            
            <div style="position: absolute; left: -15px; top: 0; bottom: 0; display: flex; align-items: center; z-index: 10; pointer-events: none;">
                <div class="dh-controls" style="pointer-events: none;">
                    <div class="dh-btn dh-rotate" title="Drag to Rotate" style="pointer-events: auto; width: 34px; height: 34px; min-width: 34px; min-height: 34px;">${rotateIcon}</div>
                </div>
            </div>

            <div class="ga-body-wrapper" style="
                transform: rotate(${rotation}deg); 
                transform-origin: center center; 
                width: 100px; height: 100px; 
                display: flex; flex-direction: column; align-items: center; justify-content: center;
                pointer-events: auto; z-index: 20; position: relative; cursor: grab;
            ">
                <div style="
                    background: ${arrowColor}; 
                    border: 2px solid black; 
                    font-weight: bold; font-family: sans-serif; font-size: 14px;
                    padding: 2px 8px; margin-bottom: -15px; z-index: 10;
                    box-shadow: 2px 2px 0px rgba(0,0,0,0.5);
                    pointer-events: none;
                ">GA</div>
                
                <svg class="ga-sprite" width="100px" height="100px" viewBox="0 0 100 100" style="overflow: visible; pointer-events: none;">
                    ${stripePattern}
                    <path d="${path}" fill="${arrowColor}" stroke="black" stroke-width="2" />
                    <path d="${path}" fill="url(#diagonalHatch)" stroke="transparent" />
                </svg>
            </div>
        </div>
      </div>`;
  };

  // Helper to safely extract screen coordinates across all devices
  const getEventPoint = (e) => {
    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
  };

  const attachListeners = (markerInst) => {
    const element = markerInst.getElement();
    if (!element) return;

    const wrapper = element.querySelector('.doghouse-interactive-wrapper');
    const interactionGroup = element.querySelector('.ga-interaction-group');
    const bodyWrapper = element.querySelector('.ga-body-wrapper');

    if (interactionGroup) {
        L.DomEvent.on(interactionGroup, 'mouseleave', () => wrapper.classList.remove('show-controls'));
    }

    // --- 1. Fix Mobile Tap to Reveal Icons ---
    markerInst.off('click'); 
    markerInst.on('click', (e) => {
        const isBtn = e.originalEvent.target.closest('.dh-btn');
        if (!isBtn && wrapper) {
            wrapper.classList.toggle('show-controls');
        }
    });

    // --- Rotate Logic ---
    const rotateBtn = element.querySelector('.dh-rotate');
    if (rotateBtn) {
      L.DomEvent.disableClickPropagation(rotateBtn);

      let isRotating = false;

      const startRotate = (e) => {
        L.DomEvent.stop(e); 
        if (isRotating) return; 
        
        isRotating = true;
        rotateBtn.classList.add('active-rotate');
        map.dragging.disable();

        if (markerInst._icon) L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');

        const onRotateDrag = (moveEvent) => {
          if (moveEvent.cancelable) moveEvent.preventDefault(); // Stop mobile browser scrolling
          const { x, y } = getEventPoint(moveEvent);
          if (x === undefined || y === undefined) return;

          const mouseLatLng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
          const center = markerInst.getLatLng();
          
          // Math fix: Convert coordinates to radians for perfect rotation tracking
          const lat1 = center.lat * Math.PI / 180;
          const lon1 = center.lng * Math.PI / 180;
          const lat2 = mouseLatLng.lat * Math.PI / 180;
          const lon2 = mouseLatLng.lng * Math.PI / 180;

          const yVal = Math.sin(lon2 - lon1) * Math.cos(lat2);
          const xVal = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1);
          const newAngle = ((Math.atan2(yVal, xVal) * 180) / Math.PI + 360) % 360;

          rotationRef.current = newAngle;

          const body = element.querySelector('.ga-body-wrapper');
          if (body) body.style.transform = `rotate(${newAngle}deg)`;
        };

        const onRotateEnd = () => {
          isRotating = false;
          rotateBtn.classList.remove('active-rotate');
          map.dragging.enable();

          if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');

          document.removeEventListener("mousemove", onRotateDrag);
          document.removeEventListener("touchmove", onRotateDrag);
          document.removeEventListener("mouseup", onRotateEnd);
          document.removeEventListener("touchend", onRotateEnd);

          updateRef.current(dataRef.current.id, { rotation: rotationRef.current });
        };

        // Attach to Document
        document.addEventListener("mousemove", onRotateDrag, { passive: false });
        document.addEventListener("touchmove", onRotateDrag, { passive: false });
        document.addEventListener("mouseup", onRotateEnd);
        document.addEventListener("touchend", onRotateEnd);
      };

      L.DomEvent.on(rotateBtn, 'mousedown touchstart', startRotate);
    }

    // --- SMART TOUCH COMBINED LOGIC (Drag, Tap, Long-Press) ---
    if (bodyWrapper) {
      L.DomEvent.disableClickPropagation(bodyWrapper);

      let isMoving = false;
      let dragThresholdMet = false;
      let startX = 0, startY = 0;
      let pressTimer;

      const startInteraction = (e) => {
        L.DomEvent.stop(e); 
        if (isMoving) return; 

        const { x, y } = getEventPoint(e);
        startX = x || 0;
        startY = y || 0;
        dragThresholdMet = false;

        pressTimer = setTimeout(() => {
          if (!dragThresholdMet) {
            if (window.confirm("Delete Go Around?")) {
              deleteRef.current(dataRef.current.id);
            }
          }
        }, 750); 

        map.dragging.disable();

        const onDrag = (moveEvent) => {
          if (!dragThresholdMet) {
            const currentPoint = getEventPoint(moveEvent);
            const dx = Math.abs((currentPoint.x || 0) - startX);
            const dy = Math.abs((currentPoint.y || 0) - startY);
            
            if (dx > 5 || dy > 5) { 
              dragThresholdMet = true;
              clearTimeout(pressTimer); 
              isMoving = true;
              bodyWrapper.style.cursor = 'grabbing';
              
              if (markerInst._icon) L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');
            }
          }

          if (isMoving) {
            if (moveEvent.cancelable) moveEvent.preventDefault();
            const { x, y } = getEventPoint(moveEvent);
            if (x === undefined || y === undefined) return;
            
            const latlng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
            markerInst.setLatLng(latlng);
          }
        };

        const onDragEnd = () => {
          clearTimeout(pressTimer);
          map.dragging.enable();
          bodyWrapper.style.cursor = 'grab';

          document.removeEventListener("mousemove", onDrag);
          document.removeEventListener("touchmove", onDrag);
          document.removeEventListener("mouseup", onDragEnd);
          document.removeEventListener("touchend", onDragEnd);

          if (isMoving) {
            isMoving = false;
            if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
            const pos = markerInst.getLatLng();
            updateRef.current(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
          } else {
            wrapper.classList.toggle('show-controls');
          }
        };

        // Attach to Document
        document.addEventListener("mousemove", onDrag, { passive: false });
        document.addEventListener("touchmove", onDrag, { passive: false });
        document.addEventListener("mouseup", onDragEnd);
        document.addEventListener("touchend", onDragEnd);
      };

      L.DomEvent.on(bodyWrapper, 'mousedown touchstart', startInteraction);

      L.DomEvent.on(bodyWrapper, 'contextmenu', (e) => {
        L.DomEvent.preventDefault(e); 
        L.DomEvent.stopPropagation(e);
        if (window.confirm("Delete Go Around?")) deleteRef.current(dataRef.current.id);
      });
    }
  };

  useEffect(() => {
    const marker = L.marker([data.lat, data.lon], {
      icon: L.divIcon({
        className: "ga-container",
        html: getHtml(data, rotationRef.current),
        iconSize: [160, 120], 
        iconAnchor: [80, 60],
      }),
      draggable: false, 
      zIndexOffset: 1000,
    }).addTo(map);

    markerRef.current = marker;

    attachListeners(marker);
    setTimeout(() => attachListeners(marker), 100);

    return () => marker.remove();
  }, [map]);

  useEffect(() => {
    if (!markerRef.current) return;
    rotationRef.current = data.rotation || 0;

    markerRef.current.setIcon(
      L.divIcon({
        className: "ga-container",
        html: getHtml(data, rotationRef.current),
        iconSize: [160, 120],
        iconAnchor: [80, 60],
      })
    );
    
    markerRef.current.setLatLng([data.lat, data.lon]);
    setTimeout(() => attachListeners(markerRef.current), 50);
  }, [data.lat, data.lon, data.rotation, map]);

  return null;
};

const ExportHandler = ({ isExporting, exportBox, setExportProgress, onExportComplete }) => {
  const map = useMap();
  const processingRef = useRef(false);

  useEffect(() => {
    const runExport = async () => {
      // 1. Guard Clauses
      if (!isExporting || !exportBox || processingRef.current) return;
      
      processingRef.current = true;
      setExportProgress(10);

      const mapContainer = map.getContainer();

      // --- 1. SAVE ORIGINAL STYLES & EXACT VIEW ---
      const origWidth = mapContainer.style.width;
      const origHeight = mapContainer.style.height;
      const origPosition = mapContainer.style.position;
      const origTop = mapContainer.style.top;
      const origLeft = mapContainer.style.left;
      const origZIndex = mapContainer.style.zIndex;

      // Lock in the user's current zoom and map center
      const origZoom = map.getZoom();
      const origCenter = map.getCenter();
      
      // Calculate the exact center of the red export box
      const exportBoxCenter = L.latLngBounds(exportBox).getCenter();

      try {
        // --- 2. FORCE DESKTOP DIMENSIONS ---
        mapContainer.style.width = '1200px';
        mapContainer.style.height = '1000px';
        mapContainer.style.position = 'absolute';
        mapContainer.style.top = '0';
        mapContainer.style.left = '0';
        mapContainer.style.zIndex = '-1'; 

        // Tell Leaflet the map got bigger
        map.invalidateSize();
        
        // --- THE FIX: Center on the box, but FORCE the original zoom level ---
        map.setView(exportBoxCenter, origZoom, { animate: false });

        // Wait for tiles to settle and load from MapBox
        await new Promise((r) => setTimeout(r, 1500));
        setExportProgress(30);
        
        // Hide UI Elements via CSS Class
        mapContainer.classList.add('hide-ui-for-export');

        // Capture the full 1200x1000 canvas
        const fullCanvas = await htmlToImage.toCanvas(mapContainer, {
           quality: 1.0,
           pixelRatio: 2, 
           skipAutoScale: true,
           filter: (node) => {
             return !node.classList?.contains('leaflet-control-container') && 
                    !node.classList?.contains('ff-panel');
           }
        });
        setExportProgress(40);

        // Calculate Crop Coordinates using the perfectly locked zoom
        const p1 = map.latLngToContainerPoint(exportBox[0]); 
        const p2 = map.latLngToContainerPoint(exportBox[1]);
        
        const rawX = Math.min(p1.x, p2.x);
        const rawY = Math.min(p1.y, p2.y);
        const rawW = Math.abs(p1.x - p2.x);
        const rawH = Math.abs(p1.y - p2.y);

        const scale = 2;
        const x = rawX * scale;
        const y = rawY * scale;
        const w = rawW * scale;
        const h = rawH * scale;

        const cropCanvas = document.createElement('canvas');
        cropCanvas.width = w;
        cropCanvas.height = h;
        const ctx = cropCanvas.getContext('2d');

        ctx.drawImage(fullCanvas, x, y, w, h, 0, 0, w, h);

        // --- FIX: Wrap toBlob in a Promise to prevent the finally block from firing early ---
        const finalBlob = await new Promise((resolve) => {
            cropCanvas.toBlob((blob) => resolve(blob), 'image/jpeg', 1.0);
        });

        setExportProgress(60);
        
        // Pass the blob up safely!
        onExportComplete(finalBlob);

      } catch (err) {
        console.error("Export Failed:", err);
        alert("Export failed: " + err.message);
        onExportComplete(null);
      } finally {
        // --- 3. CLEANUP & RESTORE MOBILE LAYOUT ---
        mapContainer.classList.remove('hide-ui-for-export');
        
        mapContainer.style.width = origWidth || '100%';
        mapContainer.style.height = origHeight || '100%';
        mapContainer.style.position = origPosition || 'relative';
        mapContainer.style.top = origTop || '';
        mapContainer.style.left = origLeft || '';
        mapContainer.style.zIndex = origZIndex || '';

        // Tell Leaflet we shrunk back to mobile size
        map.invalidateSize();
        
        // --- THE FIX: Snap the user's view exactly back to where they were looking ---
        map.setView(origCenter, origZoom, { animate: false });
        
        // Slight delay to ensure processing lock clears cleanly
        setTimeout(() => {
          processingRef.current = false;
        }, 500);
      }
    };

    runExport();
    
  }, [isExporting, exportBox, map]); 

  return null;
};

const MapView = ({
  targetLocation,
  mapData,
  detectedLZ,
  assets,
  updateAsset,
  deleteAsset,
  terrainData,
  showHeatmap,
  doghouses,
  updateDoghouse,
  goArounds,
  updateGoAround,
  deleteGoAround,
  pzMarkers,
  updatePZMarker,
  deletePZMarker,
  units,
  updateUnitPosition,
  deleteUnit,
  showLZOutline,
  sectors,
  updateSectorPoint,
  moveSector,
  deleteSector,
  exportBox,
  updateExportBox,
  deleteExportBox,
  isExporting,
  onExportComplete,
  setExportProgress,
  setIsExporting,
  setExportBox,
  isDrawingLZ,
  drawingPoints,
  setDrawingPoints,
  customLZ,
  handleMapRightClick,
  handleLZRightClick,
  setContextMenu,
  mapStyle
}) => {
  const [activeDrag, setActiveDrag] = useState(null);

  // Default Center (somewhere neutral)
  const defaultCenter = [34.0522, -118.2437];
  // Helper to determine color based on slope degree
  const getSlopeColor = (deg) => {
    if (deg < 3) return "green"; // Very Flat / Ideal
    if (deg < 6) return "blue"; // Flat / Safe
    if (deg < 10) return "yellow"; // Moderate
    if (deg < 13) return "orange"; // steep
    return "red"; // Approaching limits``
  };

  return (
    <MapContainer
      id="map-to-export"
      center={defaultCenter}
      zoom={11}
      style={{ height: "100%", width: "100%" }}
      preserveDrawingBuffer={true}
      preferCanvas={true}
      zoomSnap={0.10} 
      zoomDelta={0.10} 
      wheelPxPerZoomLevel={120}
      updateWhenZooming={false}
    >

      {/* Base Layer - using MapBox for Satellite Imagery */}
      <TileLayer
        key={mapStyle} // Forces Leaflet to refresh when the style changes
        url={
          mapStyle === "topo"
            ? "https://api.mapbox.com/styles/v1/mapbox/outdoors-v12/tiles/512/{z}/{x}/{y}@2x?access_token=pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw"
            : "https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{z}/{x}/{y}@2x?access_token=pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw"
        }
        attribution='&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>'
        tileSize={512}
        zoomOffset={-1}
        maxZoom={20}
        crossOrigin="anonymous"
      />

      <MapInteractionHandler 
        isDrawingLZ={isDrawingLZ} 
        setDrawingPoints={setDrawingPoints}
        handleMapRightClick={handleMapRightClick}
        setContextMenu={setContextMenu}
      />

      {/* Render the Active Drawing Line */}
      {isDrawingLZ && drawingPoints.length > 0 && (
        <Polyline 
          positions={drawingPoints} 
          pathOptions={{ color: '#FFC107', weight: 3, dashArray: '5, 5' }} 
        />
      )}

      {/* Render the Completed Custom Polygon */}
      {customLZ && (
  <Polygon
    positions={customLZ}
    pathOptions={{ color: "#FFC107", weight: 3, fillColor: "#FFC107", fillOpacity: 0.2 }}
    eventHandlers={{
      contextmenu: (e) => {
        L.DomEvent.stop(e); // Prevent the map from seeing this right-click
        e.originalEvent.preventDefault();
        // 👇 Add e.latlng.lat and e.latlng.lng here!
        handleLZRightClick(e.latlng.lat, e.latlng.lng, e.originalEvent.clientX, e.originalEvent.clientY);
      }
    }}
  />
)}

      {/* Logic to Zoom when target changes */}
      <MapUpdater center={targetLocation} />

      {/* 1. The Grid Location (Green Star) */}
      {targetLocation && (
        <Marker position={targetLocation} icon={starIcon}>
          <Popup>{mapData.mgrs}</Popup>
        </Marker>
      )}

      {/* 2. The Auto-Detected Field (Blue Outline) */}
      {showLZOutline && detectedLZ && (
        <Polygon
          pathOptions={{
            color: "#0056b3", // Dark Blue Line
            weight: 3, // Thicker line for visibility
            fillColor: "#0056b3", // Blue Fill
            fillOpacity: 0.1, // Very transparent fill (so you can see the grass)
            dashArray: "5, 5", // Optional: Makes it a dashed line like a tactical map
          }}
          positions={detectedLZ}
        />
      )}

      {detectedLZ && <LZDimensions detectedLZ={detectedLZ} />}

      {showHeatmap &&
        terrainData &&
        terrainData.map((cell, idx) => (
          <Rectangle
            key={`slope-${idx}`}
            bounds={cell.bounds}
            pathOptions={{
              color: "transparent",
              fillColor: getSlopeColor(cell.slope),
              fillOpacity: 0.5,
              weight: 0,
            }}
          >
            {/* Optional: Hover to see exact degree */}
            <Tooltip sticky>Slope: {cell.slope.toFixed(1)}°</Tooltip>
          </Rectangle>
        ))}

      {doghouses.map((dh) => (
        <Doghouse key={dh.id} data={dh} updateDoghouse={updateDoghouse} />
      ))}

      {goArounds.map((ga) => (
        <GoAroundMarker
          key={ga.id}
          data={ga}
          updateGoAround={updateGoAround}
          deleteGoAround={deleteGoAround}
        />
      ))}

      {pzMarkers.map((pz) => (
        <PZMarker
          key={pz.id}
          data={pz}
          updatePZMarker={updatePZMarker}
          deletePZMarker={deletePZMarker}
        />
      ))}

      {units &&
        units.map((unit) => (
          <UnitMarker
            key={unit.id}
            data={unit}
            updateUnitPosition={updateUnitPosition}
            deleteUnit={deleteUnit}
          />
        ))}

      {/* 3. Drag and Drop Assets */}
      {assets.map((asset, index) => (
        <Helicopter
          key={asset.id}
          asset={asset}
          allAssets={assets}
          updateAsset={updateAsset}
          deleteAsset={deleteAsset}
        />
      ))}

      {sectors.map((sector) => (
        <SectorMarker
          key={sector.id}
          data={sector}
          updateSectorPoint={updateSectorPoint}
          moveSector={moveSector}
          deleteSector={deleteSector}
        />
      ))}

      {/* EXPORT BOXES */}
      {!isExporting && exportBox && (
        <ExportBox
          id="red"
          bounds={exportBox}
          color="red"
          aspectRatio={663/555} // Freeform or fixed, up to you
          onUpdate={updateExportBox}
          onDelete={() => {
              setExportBox(null);      // Removes the box from the map
              setIsExporting(false);   // Kills the UI progress bar overlay
              setExportProgress(0);    // Resets progress
          }}
        />
      )}

      {/* Export Logic */}
      <ExportHandler
        isExporting={isExporting}
        exportBox={exportBox}
        setExportProgress={setExportProgress}
        onExportComplete={onExportComplete}
      />
    </MapContainer>
  );
};

export default MapView;