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
import { Rectangle } from "react-leaflet";
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

  const attachListeners = (markerInst) => {
    const element = markerInst.getElement();
    if (!element) return;

    const wrapper = element.querySelector('.doghouse-interactive-wrapper');
    const interactionGroup = element.querySelector('.ga-interaction-group');
    const bodyWrapper = element.querySelector('.ga-body-wrapper');

    if (interactionGroup) {
        L.DomEvent.on(interactionGroup, 'mouseleave', () => wrapper.classList.remove('show-controls'));
    }

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

        const onMove = (moveEvent) => {
          const center = markerInst.getLatLng();
          const mouse = moveEvent.latlng;
          
          const y = Math.sin(mouse.lng - center.lng) * Math.cos(mouse.lat);
          const x = Math.cos(center.lat) * Math.sin(mouse.lat) - Math.sin(center.lat) * Math.cos(mouse.lat) * Math.cos(mouse.lng - center.lng);
          const newAngle = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;

          rotationRef.current = newAngle;

          const body = element.querySelector('.ga-body-wrapper');
          if (body) body.style.transform = `rotate(${newAngle}deg)`;
        };

        // FIX: Attach to window to guarantee it catches the release
        const onEnd = () => {
          isRotating = false;
          rotateBtn.classList.remove('active-rotate');
          map.dragging.enable();

          if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');

          map.off("mousemove touchmove", onMove);
          window.removeEventListener("mouseup", onEnd);
          window.removeEventListener("touchend", onEnd);

          updateRef.current(dataRef.current.id, { rotation: rotationRef.current });
        };

        map.on("mousemove touchmove", onMove);
        window.addEventListener("mouseup", onEnd);
        window.addEventListener("touchend", onEnd);
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

        const touch = e.touches ? e.touches[0] : (e.originalEvent && e.originalEvent.touches ? e.originalEvent.touches[0] : e);
        startX = touch.clientX || 0;
        startY = touch.clientY || 0;
        dragThresholdMet = false;

        pressTimer = setTimeout(() => {
          if (!dragThresholdMet) {
            if (window.confirm("Delete Go Around?")) {
              deleteRef.current(dataRef.current.id);
            }
          }
        }, 750); 

        map.dragging.disable();

        const onMove = (moveEvent) => {
          if (!dragThresholdMet) {
            const currentTouch = moveEvent.originalEvent && moveEvent.originalEvent.touches ? moveEvent.originalEvent.touches[0] : moveEvent.originalEvent;
            const dx = Math.abs((currentTouch.clientX || 0) - startX);
            const dy = Math.abs((currentTouch.clientY || 0) - startY);
            
            if (dx > 5 || dy > 5) { 
              dragThresholdMet = true;
              clearTimeout(pressTimer); 
              isMoving = true;
              bodyWrapper.style.cursor = 'grabbing';
              
              if (markerInst._icon) L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');
            }
          }

          if (isMoving) {
            markerInst.setLatLng(moveEvent.latlng);
          }
        };

        // FIX: Attach to window to guarantee it catches the release
        const onEnd = () => {
          clearTimeout(pressTimer);
          map.dragging.enable();
          bodyWrapper.style.cursor = 'grab';

          map.off("mousemove touchmove", onMove);
          window.removeEventListener("mouseup", onEnd);
          window.removeEventListener("touchend", onEnd);

          if (isMoving) {
            isMoving = false;
            if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
            const pos = markerInst.getLatLng();
            updateRef.current(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
          } else {
            wrapper.classList.toggle('show-controls');
          }
        };

        map.on("mousemove touchmove", onMove);
        window.addEventListener("mouseup", onEnd);
        window.addEventListener("touchend", onEnd);
      };

      L.DomEvent.on(bodyWrapper, 'mousedown touchstart', startInteraction);

      L.DomEvent.on(bodyWrapper, 'contextmenu', (e) => {
        L.DomEvent.preventDefault(e); // Stop native long-press menus from doubling up
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

      // --- NEW: SAVE ORIGINAL STYLES ---
      // We need to memorize the exact mobile layout so we can restore it later
      const origWidth = mapContainer.style.width;
      const origHeight = mapContainer.style.height;
      const origPosition = mapContainer.style.position;
      const origTop = mapContainer.style.top;
      const origLeft = mapContainer.style.left;
      const origZIndex = mapContainer.style.zIndex;

      try {
        // --- NEW: FORCE DESKTOP DIMENSIONS ---
        // Blow the map up to a desktop size behind the loading screen
        mapContainer.style.width = '1200px';
        mapContainer.style.height = '1000px';
        mapContainer.style.position = 'absolute';
        mapContainer.style.top = '0';
        mapContainer.style.left = '0';
        mapContainer.style.zIndex = '-1'; 

        // Force Leaflet to recognize the new size and frame the export box perfectly
        map.invalidateSize();
        map.fitBounds(exportBox, { animate: false });

        // 2. Wait for tiles to settle and load from MapBox
        // Increased slightly to 1500ms to ensure all new outer tiles finish downloading
        await new Promise((r) => setTimeout(r, 1500));
        setExportProgress(30);
        
        // 3. Hide UI Elements via CSS Class
        mapContainer.classList.add('hide-ui-for-export');

        // 4. Capture the full 1200x1000 canvas
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

        // 5. Calculate Crop Coordinates
        // Because we just ran fitBounds(), the exportBox is perfectly inside our 1200x1000 container
        const p1 = map.latLngToContainerPoint(exportBox[0]); 
        const p2 = map.latLngToContainerPoint(exportBox[1]);
        
        const rawX = Math.min(p1.x, p2.x);
        const rawY = Math.min(p1.y, p2.y);
        const rawW = Math.abs(p1.x - p2.x);
        const rawH = Math.abs(p1.y - p2.y);

        // Scale coordinates to match the 2x pixelRatio we used above
        const scale = 2;
        const x = rawX * scale;
        const y = rawY * scale;
        const w = rawW * scale;
        const h = rawH * scale;

        // 6. Create Crop Canvas
        const cropCanvas = document.createElement('canvas');
        cropCanvas.width = w;
        cropCanvas.height = h;
        const ctx = cropCanvas.getContext('2d');

        // Draw only the Red Box area onto the new canvas
        ctx.drawImage(fullCanvas, x, y, w, h, 0, 0, w, h);

        cropCanvas.toBlob((blob) => {
            setExportProgress(50);
            
            // Clean up UI
            mapContainer.classList.remove('hide-ui-for-export');
            
            setTimeout(() => {
                processingRef.current = false;
                // PASS THE BLOB UP TO APP.JS
                onExportComplete(blob); 
            }, 500);

        }, 'image/jpeg', 1.0);

        setExportProgress(60);

      } catch (err) {
        console.error("Export Failed:", err);
        alert("Export failed: " + err.message);
      } finally {
        // --- NEW: CLEANUP & RESTORE MOBILE LAYOUT ---
        mapContainer.classList.remove('hide-ui-for-export');
        
        mapContainer.style.width = origWidth || '100%';
        mapContainer.style.height = origHeight || '100%';
        mapContainer.style.position = origPosition || 'relative';
        mapContainer.style.top = origTop || '';
        mapContainer.style.left = origLeft || '';
        mapContainer.style.zIndex = origZIndex || '';

        // Tell Leaflet we shrunk back to mobile size
        map.invalidateSize();
        // Snap the user's view back to the target area so they aren't lost
        map.fitBounds(exportBox, { animate: false });
        
        setTimeout(() => {
          processingRef.current = false;
          // Only trigger onExportComplete(null) if it actually failed and we didn't pass a blob
          if (isExporting) onExportComplete(null);
        }, 500);
      }
    };

    runExport();
    
  }, [isExporting, exportBox, map]); 

  return null;
};

const MapView = ({
  targetLocation,
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
  setExportBox
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
        url="https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{z}/{x}/{y}@2x?access_token=pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw"
        attribution='&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>'
        tileSize={512}
        zoomOffset={-1}
        maxZoom={20}
        crossOrigin="anonymous"
      />

      {/* Logic to Zoom when target changes */}
      <MapUpdater center={targetLocation} />

      {/* 1. The Grid Location (Green Star) */}
      {targetLocation && (
        <Marker position={targetLocation} icon={starIcon}>
          <Popup>Target Grid Location</Popup>
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