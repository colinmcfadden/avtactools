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
import MsnxRouteLayer, {
  buildIcon as buildSketchPointIcon,
} from "../feature/msnxImport/MsnxRouteLayer";
import LocalPointsLayer from "../feature/localPoints/LocalPointsLayer";
import ThreatLayer from "../feature/threats/ThreatLayer";
import { getMapStyle } from "../feature/mapStyles/mapStyles";

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
  onDraftPointContextMenu,
  onMapMove,
}) {
  const map = useMapEvents({
    moveend: () => {
      if (onMapMove) {
        const c = map.getCenter();
        onMapMove([c.lat, c.lng]);
      }
    },
    contextmenu: (e) => {
      if (isDrawingLZ) return; // Don't interrupt drawing
      if (isSketchingRoute) {
        // Right-click while drawing a route: designate a point (target / IP /
        // checkpoint) at this location instead of the normal map menu.
        e.originalEvent.preventDefault();
        onDraftPointContextMenu(
          e.latlng.lat,
          e.latlng.lng,
          e.originalEvent.clientX,
          e.originalEvent.clientY,
        );
        return;
      }
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
  onDraftPointContextMenu,
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
  localPointSets,
  onAddLocalPointToRoute,
  threats,
  onThreatMove,
  onThreatEdit,
  onMapMove,
}) => {
  // Default Center (somewhere neutral)
  const defaultCenter = [34.0522, -118.2437];
  const activeMapStyle = getMapStyle(mapStyle);
  // Visible local points that a dragged route point can snap onto.
  const localSnapPoints = (localPointSets || [])
    .filter((set) => set.visible !== false)
    .flatMap((set) => set.points);
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
      {/* Base Layer - driven by the mapStyles registry */}
      <TileLayer
        key={activeMapStyle.id} // Forces Leaflet to refresh when the style changes
        url={activeMapStyle.url}
        crossOrigin="anonymous"
        {...activeMapStyle.options}
      />

      <MapInteractionHandler
        isDrawingLZ={isDrawingLZ}
        setDrawingPoints={setDrawingPoints}
        handleMapRightClick={handleMapRightClick}
        setContextMenu={setContextMenu}
        isSketchingRoute={isSketchingRoute}
        addDraftPoint={addDraftPoint}
        onDraftPointContextMenu={onDraftPointContextMenu}
        onMapMove={onMapMove}
      />

      {/* In-progress route sketch */}
      {isSketchingRoute && draftPoints.length > 0 && (
        <>
          <Polyline
            positions={draftPoints.map((p) => [p.lat, p.lon])}
            pathOptions={{ color: "#64D2FF", weight: 3, dashArray: "5, 5" }}
          />
          {draftPoints.map(
            (p, i) =>
              p.designation && (
                <Marker
                  key={`draft-${i}`}
                  position={[p.lat, p.lon]}
                  icon={buildSketchPointIcon(
                    { kind: "amps", ptType: p.designation.ptType },
                    "#64D2FF",
                  )}
                >
                  <Tooltip permanent direction="top" offset={[0, -12]}>
                    {(p.designation.name || "").replace(/^\./, "")}
                  </Tooltip>
                </Marker>
              ),
          )}
        </>
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
        snapPoints={localSnapPoints}
      />

      <MsnxRouteLayer
        routes={sketchedRoutes}
        onUpdatePosition={onUpdateSketchPointPosition}
        onInsertPoint={onInsertMsnxPoint}
        onPointContextMenu={onSketchPointContextMenu}
        snapPoints={localSnapPoints}
      />

      <LocalPointsLayer pointSets={localPointSets} onAddToRoute={onAddLocalPointToRoute} />

      <ThreatLayer threats={threats} onMove={onThreatMove} onEdit={onThreatEdit} />

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
