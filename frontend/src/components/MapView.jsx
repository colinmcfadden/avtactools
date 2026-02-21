import React, { useEffect, useRef } from "react";
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
  const handleRef = useRef(null);

  // SVG CONFIGURATION
  const arrowColor = "#FFC107"; // Amber/Orange
  const stripePattern = `
        <defs>
            <pattern id="diagonalHatch" patternUnits="userSpaceOnUse" width="8" height="8" patternTransform="rotate(0)">
                <path d="M0,8 L8,8" style="stroke:black; stroke-width:2" />
            </pattern>
        </defs>`;

  // SVG PATHS
  const rightArrowPath =
    "M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z";
  // Mirror the right arrow for left
  const leftArrowPath =
    "M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z";

  const getHtml = (ga, rotation) => {
    const isRight = ga.direction === "right";
    const path = isRight ? rightArrowPath : leftArrowPath;

    return `
        <div style="
            transform: rotate(${rotation}deg); 
            transform-origin: center center; 
            width: 100px; height: 100px; 
            display: flex; flex-direction: column; align-items: center; justify-content: center;
            pointer-events: auto; cursor: grab;
        ">
            <div style="
                background: ${arrowColor}; 
                border: 2px solid black; 
                font-weight: bold; font-family: sans-serif; font-size: 14px;
                padding: 2px 8px; margin-bottom: -15px; z-index: 10;
                box-shadow: 2px 2px 0px rgba(0,0,0,0.5);
            ">GA</div>
            
            <svg width="100px" height="100px" viewBox="0 0 100 100" style="overflow: visible;">
                ${stripePattern}
                <path d="${path}" fill="${arrowColor}" stroke="black" stroke-width="2" />
                <path d="${path}" fill="url(#diagonalHatch)" stroke="transparent" />
            </svg>
        </div>`;
  };

  // --- REUSE ROTATION LOGIC (Simplified from Doghouse) ---
  const calculateHandlePos = (lat, lon, rot) => {
    // Place handle "North" relative to the center
    const offset = 0.0004;
    return [lat + offset, lon];
  };

  useEffect(() => {
    let currentRotation = data.rotation || 0;

    // 1. Create Main Marker
    const marker = L.marker([data.lat, data.lon], {
      icon: L.divIcon({
        className: "ga-container",
        html: getHtml(data, currentRotation),
        iconSize: [100, 100],
        iconAnchor: [50, 50],
      }),
      draggable: true,
      zIndexOffset: 1000,
    }).addTo(map);

    // 2. Create Rotation Handle
    const handle = L.marker(
      calculateHandlePos(data.lat, data.lon, currentRotation),
      {
        icon: L.divIcon({
          className: "rotate-handle",
          html: `<div style="background: white; border: 2px solid #0056b3; width: 12px; height: 12px; border-radius: 50%; cursor: grab;"></div>`,
          iconSize: [12, 12],
          iconAnchor: [6, 6],
        }),
        draggable: true,
        zIndexOffset: 1100,
        opacity: 0,
      },
    ).addTo(map);

    // --- EVENTS ---

    // Drag House -> Move Handle
    marker.on("drag", (e) => {
      const pos = e.target.getLatLng();
      handle.setLatLng(calculateHandlePos(pos.lat, pos.lng, currentRotation));
    });
    marker.on("dragend", (e) => {
      const pos = e.target.getLatLng();
      updateGoAround(data.id, { lat: pos.lat, lon: pos.lng });
    });

    // Rotate Handle
    handle.on("drag", (e) => {
      handle.setOpacity(1);
      const center = marker.getLatLng();
      const mouse = e.target.getLatLng();

      // Calculate Bearing
      const y = Math.sin(mouse.lng - center.lng) * Math.cos(mouse.lat);
      const x =
        Math.cos(center.lat) * Math.sin(mouse.lat) -
        Math.sin(center.lat) *
          Math.cos(mouse.lat) *
          Math.cos(mouse.lng - center.lng);
      const newAngle = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;

      currentRotation = newAngle;

      marker.setIcon(
        L.divIcon({
          className: "ga-container",
          html: getHtml(data, currentRotation),
          iconSize: [100, 100],
          iconAnchor: [50, 50],
        }),
      );

      // Reset Handle to Top (Compass Rose style)
      handle.setLatLng(calculateHandlePos(center.lat, center.lng, 0));
    });

    handle.on("dragend", () => {
      updateGoAround(data.id, { rotation: currentRotation });
    });

    // Hover Visibility
    const show = () => handle.setOpacity(1);
    const hide = () =>
      setTimeout(() => {
        if (!handle.dragging?._draggable?._dragging) handle.setOpacity(0);
      }, 100);
    marker.on("mouseover", show);
    marker.on("mouseout", hide);
    handle.on("mouseover", show);
    handle.on("mouseout", hide);

    // Right Click Delete
    marker.on("contextmenu", (e) => {
      L.DomEvent.stopPropagation(e);
      if (window.confirm("Delete Go Around?")) deleteGoAround(data.id);
    });

    return () => {
      marker.remove();
      handle.remove();
    };
  }, [map, data.id]);

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

      try {
        // 2. Wait for tiles to settle
        await new Promise((r) => setTimeout(r, 1000));
        setExportProgress(30);

        const mapContainer = map.getContainer();
        
        // 3. Hide UI Elements via CSS Class
        // We add a class to the container to hide controls/panels during the snapshot
        mapContainer.classList.add('hide-ui-for-export');

        const fullCanvas = await htmlToImage.toCanvas(mapContainer, {
           quality: 1.0,
           pixelRatio: 2, 
           skipAutoScale: true,
           // Double safety filter
           filter: (node) => {
             return !node.classList?.contains('leaflet-control-container') && 
                    !node.classList?.contains('ff-panel');
           }
        });
        setExportProgress(40);

        // 5. Calculate Crop Coordinates
        // These points are relative to the map container's Top-Left (0,0)
        // Since we captured the map container, these match perfectly.
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
        // source(x,y,w,h) -> dest(0,0,w,h)
        ctx.drawImage(fullCanvas, x, y, w, h, 0, 0, w, h);

        cropCanvas.toBlob((blob) => {
            setExportProgress(50);
            
            // Clean up UI
            const mapContainer = map.getContainer();
            mapContainer.classList.remove('hide-ui-for-export');
            
            setTimeout(() => {
                processingRef.current = false;
                // PASS THE BLOB UP TO APP.JS
                onExportComplete(blob); 
            }, 500);

        }, 'image/jpeg', 1.0);

        setExportProgress(60);

        // 7. Download
        //const link = document.createElement("a");
        //link.download = `LZ_Diagram_${Date.now()}.jpg`;
        //link.href = cropCanvas.toDataURL('image/jpeg', 1.0);
        //document.body.appendChild(link);
        //link.click();
        //document.body.removeChild(link);

        //setExportProgress(100);

      } catch (err) {
        console.error("Export Failed:", err);
        alert("Export failed: " + err.message);
      } finally {
        // Cleanup: Show UI again
        const mapContainer = map.getContainer();
        mapContainer.classList.remove('hide-ui-for-export');
        
        setTimeout(() => {
          processingRef.current = false;
          onExportComplete(null);
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