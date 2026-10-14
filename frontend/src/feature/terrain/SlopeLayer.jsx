import { useEffect } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import "./slopeLayer.css";

/**
 * The slope map over an analysed LZ/PZ: the banded slope raster the server drew, and the contour lines of the ground in and round
 * the LZ (`contours` of /api/terrain-analysis), so the shape of the ground can be read as on a topographic map: what falls away
 * past an edge, where a ridge or a dip runs. The lines inside the boundary are drawn full, the ring round it faded; every fifth is
 * heavier, and each height inside is labelled once.
 *
 * Three panes of its own, above the MGRS grid (350, 360) and below everything planned on the map (overlays 400, markers 600), so the
 * boundary, a route or a helicopter is never under a contour. None takes a click, drag or hover. Unlike the grid, the slope map is
 * part of the LZ card export when it is shown, as the raster always was.
 */

export const SLOPE_PANE = "slopeRasterPane";
export const CONTOUR_PANE = "slopeContourPane";
export const CONTOUR_LABEL_PANE = "slopeContourLabelPane";
export const SLOPE_Z_INDEX = 380;
export const CONTOUR_Z_INDEX = 390;
export const CONTOUR_LABEL_Z_INDEX = 395;

// The slope PNG already carries its own per-band alpha (145-185 of 255, rising with steepness so hazardous ground stands out), and
// this multiplies it. 0.55 lands the middle band near 36% and the full range at roughly 31-40%, light enough to read the imagery
// through while keeping the steep-ground emphasis.
export const SLOPE_OVERLAY_OPACITY = 0.55;

// Leaflet has no way to remove a pane, so one made earlier is used again; empty, it shows nothing. Hidden from screen readers: the
// slope tile says what matters in words.
const paneFor = (map, name, zIndex) => {
  const pane = map.getPane(name) || map.createPane(name);
  pane.style.zIndex = String(zIndex);
  pane.style.pointerEvents = "none";
  pane.classList.add("map-slope");
  pane.setAttribute("aria-hidden", "true");
  return pane;
};

const lineClass = (line, part) =>
  `slope-contour__${part} slope-contour__${part}--${line.major ? "major" : "minor"}${line.inside ? "" : ` slope-contour__${part}--outside`}`;

const labelIcon = (elevationFt) => {
  const pill = document.createElement("span");
  pill.textContent = String(elevationFt);
  return L.divIcon({ className: "slope-contour-label", html: pill, iconSize: [0, 0] });
};

/**
 * Draws [terrainData]'s slope raster and contours on [map]; returns what takes them away. An analysis from a server before contours
 * draws the raster alone.
 */
export const attachSlope = (map, terrainData) => {
  paneFor(map, SLOPE_PANE, SLOPE_Z_INDEX);
  paneFor(map, CONTOUR_PANE, CONTOUR_Z_INDEX);
  paneFor(map, CONTOUR_LABEL_PANE, CONTOUR_LABEL_Z_INDEX);
  const layers = L.layerGroup().addTo(map);
  if (terrainData?.overlay && terrainData?.bounds) {
    L.imageOverlay(terrainData.overlay, terrainData.bounds, {
      pane: SLOPE_PANE, opacity: SLOPE_OVERLAY_OPACITY, interactive: false,
    }).addTo(layers);
  }
  const lines = Array.isArray(terrainData?.contours?.lines) ? terrainData.contours.lines : [];
  const renderer = new L.SVG({ pane: CONTOUR_PANE });
  const drawn = lines.filter((line) => Array.isArray(line?.points) && line.points.length >= 2);
  // Halos first, then the lines on top, so no halo covers a line.
  ["halo", "line"].forEach((part) => drawn.forEach((line) => {
    L.polyline(line.points, { renderer, interactive: false, className: lineClass(line, part), weight: line.major ? 2 : 1 }).addTo(layers);
  }));
  drawn.filter((line) => Array.isArray(line.label) && line.label.length === 2).forEach((line) => {
    L.marker(line.label, { pane: CONTOUR_LABEL_PANE, icon: labelIcon(line.elevationFt), interactive: false, keyboard: false }).addTo(layers);
  });
  return () => {
    layers.remove();
    renderer.remove();
  };
};

/** The slope map as a child of MapContainer: drawn while mounted and [shown], for [terrainData]. */
const SlopeLayer = ({ terrainData, shown }) => {
  const map = useMap();
  useEffect(() => (shown && terrainData ? attachSlope(map, terrainData) : undefined), [map, terrainData, shown]);
  return null;
};

export default SlopeLayer;
