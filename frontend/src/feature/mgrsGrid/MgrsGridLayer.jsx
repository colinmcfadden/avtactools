import { useEffect } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { mgrsGrid } from "./mgrsGrid";
import "./mgrsGrid.css";

/**
 * The MGRS grid over the map, on whatever base map is chosen (mgrsGrid.js says what to draw).
 *
 * It lives in two panes of its own, above the base tiles (200) and below everything planned on
 * the map (overlays 400, markers 600), so a helicopter or a route is never under a grid line.
 * Neither pane takes a click, drag or hover: the map and its graphics get them all. The grid is
 * worked out again once the view settles, never during a pan, and covers a quarter of the view
 * round it so a pan does not show bare map before then. Both panes carry SCREEN_ONLY_CLASS,
 * which the LZ card export leaves out: the grid is for the screen, never for a card.
 */

export const GRID_PANE = "mgrsGridPane";
export const LABEL_PANE = "mgrsGridLabelPane";
export const SCREEN_ONLY_CLASS = "map-screen-only";
export const GRID_Z_INDEX = 350;
export const LABEL_Z_INDEX = 360;

const PAD = 0.25;
const EARTH_CIRCUMFERENCE = 40075016.686;     // Web Mercator's, metres
// Halos first, then the lines on top, zone boundaries last of each: no halo covers a line.
const KINDS = ["minor", "major", "zone"];
const WEIGHTS = { minor: 1, major: 1.75, zone: 2.5 }; // drawn widths are set in mgrsGrid.css

// Map overlays the labels keep clear of: Leaflet's own controls, and what the app floats over
// the map beside it (the unit badge, the base map picker, a finished pack's banner, the phone
// layout's search and buttons).
const OVERLAYS_INSIDE = ".leaflet-control";
const OVERLAYS_BESIDE = [
  ".unit-badge",
  ".map-style-switcher",
  ".packs-banner",
  ".mobile-top-search-container",
  ".mobile-quick-access-container",
].join(", ");

const avoidBoxes = (map) => {
  const container = map.getContainer();
  const origin = container.getBoundingClientRect();
  const elements = [
    ...container.querySelectorAll(OVERLAYS_INSIDE),
    ...(container.parentElement ? container.parentElement.querySelectorAll(OVERLAYS_BESIDE) : []),
  ];
  return elements
    .map((element) => element.getBoundingClientRect())
    .filter((rect) => rect.width > 0 && rect.height > 0)
    .map((rect) => [
      rect.left - origin.left - 4,
      rect.top - origin.top - 4,
      rect.right - origin.left + 4,
      rect.bottom - origin.top + 4,
    ]);
};

// Leaflet has no way to remove a pane, so one made earlier is used again; empty, it shows nothing.
// Hidden from screen readers: it is a picture of what the cursor readout says in words.
const paneFor = (map, name, zIndex) => {
  const pane = map.getPane(name) || map.createPane(name);
  pane.style.zIndex = String(zIndex);
  pane.style.pointerEvents = "none";
  pane.classList.add(SCREEN_ONLY_CLASS, "map-mgrs-grid");
  pane.setAttribute("aria-hidden", "true");
  return pane;
};

const labelIcon = ({ kind, text }) => {
  const pill = document.createElement("span");
  pill.textContent = text;
  return L.divIcon({ className: `mgrs-grid-label mgrs-grid-label--${kind}`, html: pill, iconSize: [0, 0] });
};

/** The view's ground scale at its centre, as Leaflet's Web Mercator gives it. */
const metresPerPixel = (map) => {
  const { lat } = map.getCenter();
  return (EARTH_CIRCUMFERENCE * Math.cos((lat * Math.PI) / 180)) / map.options.crs.scale(map.getZoom());
};

/** Draws the grid on a Leaflet map until the returned function is called. */
export const attachMgrsGrid = (map) => {
  paneFor(map, GRID_PANE, GRID_Z_INDEX);
  paneFor(map, LABEL_PANE, LABEL_Z_INDEX);

  const renderer = new L.SVG({ pane: GRID_PANE, padding: PAD });
  const path = (kind, role) => L.polyline([], {
    renderer,
    interactive: false,
    className: `mgrs-grid__${role} mgrs-grid__${role}--${kind}`,
    weight: WEIGHTS[kind],
    // Leaflet would otherwise simplify by a whole pixel, undoing mgrsGrid's quarter pixel.
    smoothFactor: 0.2,
  });
  const halos = Object.fromEntries(KINDS.map((kind) => [kind, path(kind, "halo")]));
  const lines = Object.fromEntries(KINDS.map((kind) => [kind, path(kind, "line")]));
  const paths = L.layerGroup([...KINDS.map((k) => halos[k]), ...KINDS.map((k) => lines[k])]).addTo(map);
  const labels = L.layerGroup().addTo(map);

  let frame = null;
  const draw = () => {
    frame = null;
    const size = map.getSize();
    const bounds = map.getBounds();
    const grid = size.x > 0 && size.y > 0
      ? mgrsGrid(
        { south: bounds.getSouth(), west: bounds.getWest(), north: bounds.getNorth(), east: bounds.getEast() },
        metresPerPixel(map),
        { pad: PAD, avoid: avoidBoxes(map) },
      )
      : { lines: [], labels: [] };
    KINDS.forEach((kind) => {
      const latlngs = grid.lines.filter((line) => line.kind === kind).map((line) => line.points);
      halos[kind].setLatLngs(latlngs);
      lines[kind].setLatLngs(latlngs);
    });
    labels.clearLayers();
    grid.labels.forEach((label) => {
      labels.addLayer(L.marker([label.lat, label.lon], {
        icon: labelIcon(label),
        pane: LABEL_PANE,
        interactive: false,
        keyboard: false,
      }));
    });
  };
  // Once per frame however many events arrive together (a zoom ends with zoomend and moveend).
  const schedule = () => {
    if (frame === null) frame = window.requestAnimationFrame(draw);
  };

  map.on("moveend zoomend resize", schedule);
  draw();

  return () => {
    map.off("moveend zoomend resize", schedule);
    if (frame !== null) window.cancelAnimationFrame(frame);
    frame = null;
    paths.remove();
    labels.remove();
    renderer.remove();
  };
};

/** The grid as a child of MapContainer: drawn while mounted, gone when unmounted. */
const MgrsGridLayer = () => {
  const map = useMap();
  useEffect(() => attachMgrsGrid(map), [map]);
  return null;
};

export default MgrsGridLayer;
