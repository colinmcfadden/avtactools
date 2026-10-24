import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { SLOPE_OPACITY } from "./slopeOpacity";
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
// the raster's opacity multiplies it. The person sets it (slopeOpacity.js); unset, 0.55 lands the middle band near 36% and the full
// range at roughly 31-40%, light enough to read the imagery through while keeping the steep-ground emphasis.
export const SLOPE_OVERLAY_OPACITY = SLOPE_OPACITY.DEFAULT / 100;

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
 * Draws [terrainData]'s slope raster, at [opacity] (0 to 1, of its own alpha), and contours on [map]; returns what takes them away, which
 * also sets the raster's opacity again (`setOpacity`) with nothing drawn anew. An analysis from a server before contours draws the raster
 * alone. The contours keep their own opacity: the setting is the raster's.
 */
export const attachSlope = (map, terrainData, opacity = SLOPE_OVERLAY_OPACITY) => {
  paneFor(map, SLOPE_PANE, SLOPE_Z_INDEX);
  paneFor(map, CONTOUR_PANE, CONTOUR_Z_INDEX);
  paneFor(map, CONTOUR_LABEL_PANE, CONTOUR_LABEL_Z_INDEX);
  const layers = L.layerGroup().addTo(map);
  const raster = terrainData?.overlay && terrainData?.bounds
    ? L.imageOverlay(terrainData.overlay, terrainData.bounds, { pane: SLOPE_PANE, opacity, interactive: false }).addTo(layers)
    : null;
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
  const detach = () => {
    layers.remove();
    renderer.remove();
  };
  detach.setOpacity = (value) => raster?.setOpacity(value);
  return detach;
};

/**
 * The slope map as a child of MapContainer: drawn while mounted and [shown], for [terrainData], the raster at [opacity] (0 to 1). A new
 * opacity is set on the raster drawn, never a reason to draw the slope map again: a slider being dragged would redraw every contour.
 */
const SlopeLayer = ({ terrainData, shown, opacity = SLOPE_OVERLAY_OPACITY }) => {
  const map = useMap();
  const attached = useRef(null);
  const opacityNow = useRef(opacity);
  // Before the drawing effect, so a slope drawn in the same commit is drawn at the opacity it has now.
  useEffect(() => {
    opacityNow.current = opacity;
    attached.current?.setOpacity(opacity);
  }, [opacity]);
  useEffect(() => {
    if (!shown || !terrainData) return undefined;
    const detach = attachSlope(map, terrainData, opacityNow.current);
    attached.current = detach;
    return () => {
      attached.current = null;
      detach();
    };
  }, [map, terrainData, shown]);
  return null;
};

export default SlopeLayer;
