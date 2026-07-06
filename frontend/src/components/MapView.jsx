import React, { useEffect } from "react";
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
import PZMarker from "../feature/pzMarker/PZMarker";
import UnitMarker from "../feature/unit/UnitMarker";
import SectorMarker from "../feature/sectorsOfFire/SectorMarker";
import ExportBox from "../feature/export/ExportBox";
import Doghouse from "../feature/doghouses/Doghouse";
import Helicopter from "../feature/helicopters/Helicopter";
import GoAroundMarker from "../feature/goAround/GoAround";
import ExportHandler from "../feature/export/ExportHandler";
import MsnxRouteLayer from "../feature/msnxImport/MsnxRouteLayer";

// Fix for default Leaflet marker icons in React
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: require("leaflet/dist/images/marker-icon-2x.png"),
  iconUrl: require("leaflet/dist/images/marker-icon.png"),
  shadowUrl: require("leaflet/dist/images/marker-shadow.png"),
});

// Custom Icons
const starIcon = new L.Icon({
  iconUrl: "/icons/Gold_Star.svg",
  iconSize: [15, 15],
});

// auto-zoom to new location
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
  isDrawingLZ,
  setDrawingPoints,
  handleMapRightClick,
  setContextMenu,
  isSketchingRoute,
  addDraftPoint,
}) {
  useMapEvents({
    contextmenu: (e) => {
      if (isDrawingLZ || isSketchingRoute) return; // Don't interrupt drawing
      // Stop the default browser right-click menu
      e.originalEvent.preventDefault();
      handleMapRightClick(
        e.latlng.lat,
        e.latlng.lng,
        e.originalEvent.clientX,
        e.originalEvent.clientY,
      );
    },
    click: (e) => {
      if (isDrawingLZ) {
        setDrawingPoints((prev) => [...prev, [e.latlng.lat, e.latlng.lng]]);
      } else if (isSketchingRoute) {
        addDraftPoint(e.latlng.lat, e.latlng.lng);
      } else {
        setContextMenu(null); // Click anywhere else to close the menu
      }
    },
  });
  return null;
}

const MapView = ({
  importedRoutes,
  onUpdateMsnxPointPosition,
  onInsertMsnxPoint,
  sketchedRoutes,
  onUpdateSketchPointPosition,
  onSketchPointContextMenu,
  isSketchingRoute,
  addDraftPoint,
  draftPoints,
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
  mapStyle,
}) => {
  // Default Center (somewhere neutral)
  const defaultCenter = [34.0522, -118.2437];
  // Helper to determine color based on slope degree
  const getSlopeColor = (deg) => {
    if (deg < 3) return "green"; // Very Flat / Ideal
    if (deg < 6) return "blue"; // Flat / Safe
    if (deg < 10) return "yellow"; // Moderate
    if (deg < 13) return "orange"; // steep
    return "red"; // Approaching limits`
  };

  return (
    <MapContainer
      id="map-to-export"
      center={defaultCenter}
      zoom={11}
      style={{ height: "100%", width: "100%" }}
      preserveDrawingBuffer={true}
      preferCanvas={true}
      zoomSnap={0.1}
      zoomDelta={0.1}
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
        isSketchingRoute={isSketchingRoute}
        addDraftPoint={addDraftPoint}
      />

      {/* In-progress route sketch */}
      {isSketchingRoute && draftPoints.length > 0 && (
        <Polyline
          positions={draftPoints.map((p) => [p.lat, p.lon])}
          pathOptions={{ color: "#64D2FF", weight: 3, dashArray: "5, 5" }}
        />
      )}

      {/* Render the Active Drawing Line */}
      {isDrawingLZ && drawingPoints.length > 0 && (
        <Polyline
          positions={drawingPoints}
          pathOptions={{ color: "#FFC107", weight: 3, dashArray: "5, 5" }}
        />
      )}

      {/* Render the Completed Custom Polygon */}
      {customLZ && (
        <Polygon
          positions={customLZ}
          pathOptions={{
            color: "#FFC107",
            weight: 3,
            fillColor: "#FFC107",
            fillOpacity: 0.2,
          }}
          eventHandlers={{
            contextmenu: (e) => {
              L.DomEvent.stop(e); // Prevent the map from seeing this right-click
              e.originalEvent.preventDefault();

              handleLZRightClick(
                e.latlng.lat,
                e.latlng.lng,
                e.originalEvent.clientX,
                e.originalEvent.clientY,
              );
            },
          }}
        />
      )}

      {/* Logic to Zoom when target changes */}
      <MapUpdater center={targetLocation} />

      <MsnxRouteLayer
        routes={importedRoutes}
        onUpdatePosition={onUpdateMsnxPointPosition}
        onInsertPoint={onInsertMsnxPoint}
      />

      <MsnxRouteLayer
        routes={sketchedRoutes}
        onUpdatePosition={onUpdateSketchPointPosition}
        onInsertPoint={onInsertMsnxPoint}
        onPointContextMenu={onSketchPointContextMenu}
      />

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
            color: "#0056b3", 
            weight: 3, 
            fillColor: "#0056b3", 
            fillOpacity: 0.1,
            dashArray: "5, 5",
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
          aspectRatio={663 / 555} // Freeform or fixed, up to you
          onUpdate={updateExportBox}
          onDelete={() => {
            setExportBox(null); // Removes the box from the map
            setIsExporting(false); // Kills the UI progress bar overlay
            setExportProgress(0); // Resets progress
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
