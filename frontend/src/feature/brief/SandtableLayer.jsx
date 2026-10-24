import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { SCREEN_ONLY_CLASS } from "../export/captureFilter";
import { FIGURE_COLOURS, fitInsets, roleColour } from "./sandtableSteps";

/*
 * The live sandtable on the map (docs/MISSION_BRIEF.md §2.8): one step's figure drawn from the brief's geometry, as the
 * PDF draws it (sandtableSteps.js says what; this only draws), and the map flown to the step's area, clear of the script card
 * and the constants box. Nothing here takes a click: the presenter can still pan and zoom to point at something, and the
 * next step flies the map again. The map goes back to where it was when the brief ends.
 */

const SHAPES_PANE = "sandtableShapePane";
const MARKS_PANE = "sandtableMarkPane";
const LABELS_PANE = "sandtableLabelPane";
// Above the base map, the MGRS grid (350, 360) and Leaflet's overlay pane (400); the labels under its tooltips (650).
const PANES = [[SHAPES_PANE, 450], [MARKS_PANE, 620], [LABELS_PANE, 640]];

const paneOf = (map, name, zIndex) => {
  const pane = map.getPane(name) || map.createPane(name);
  pane.style.zIndex = String(zIndex);
  pane.style.pointerEvents = "none";
  pane.classList.add(SCREEN_ONLY_CLASS);
  pane.setAttribute("aria-hidden", "true");
  return pane;
};

const finite = (v) => typeof v === "number" && Number.isFinite(v);
const at = (lat, lon) => (finite(lat) && finite(lon) ? [lat, lon] : null);

/** A label: words from the plan, set as text so nothing in them is read as markup. */
const labelIcon = (text, { colour = "#ffffff", size = "normal" } = {}) => {
  const element = document.createElement("span");
  element.className = `sandtable-map-label sandtable-map-label--${size}`;
  element.style.color = colour;
  element.textContent = text;
  return L.divIcon({ className: "sandtable-map-label__anchor", html: element, iconSize: [0, 0] });
};

const SVG_NS = "http://www.w3.org/2000/svg";
const svg = (tag, attributes) => {
  const element = document.createElementNS(SVG_NS, tag);
  Object.entries(attributes).forEach(([k, v]) => element.setAttribute(k, String(v)));
  return element;
};

/**
 * An arrow along a heading (true; the map is north up), `length` px long. `from: "tail"` puts its tail on the point (an
 * aircraft's heading), `from: "tip"` its tip (the landing heading, arriving at the LZ/PZ).
 */
const arrowIcon = (heading, { length, colour, from }) => {
  const width = 22;
  const box = svg("svg", { width, height: length, viewBox: `0 0 ${width} ${length}`, class: "sandtable-map-arrow" });
  box.appendChild(svg("line", { x1: width / 2, y1: length, x2: width / 2, y2: 9, stroke: FIGURE_COLOURS.casing, "stroke-width": 7, "stroke-linecap": "round" }));
  box.appendChild(svg("line", { x1: width / 2, y1: length, x2: width / 2, y2: 9, stroke: colour, "stroke-width": 3.5, "stroke-linecap": "round" }));
  box.appendChild(svg("polygon", { points: `${width / 2},0 ${width - 2},14 2,14`, fill: colour, stroke: FIGURE_COLOURS.casing, "stroke-width": 1.5 }));
  // Drawn pointing north, then turned about the end that sits on the point.
  const originY = from === "tip" ? 0 : length;
  box.style.transformOrigin = `${width / 2}px ${originY}px`;
  box.style.transform = `rotate(${heading}deg)`;
  const holder = document.createElement("span");
  holder.appendChild(box);
  return L.divIcon({ className: "sandtable-map-arrow__anchor", html: holder, iconSize: [width, length], iconAnchor: [width / 2, originY] });
};

const squareIcon = (colour, close) => {
  const element = document.createElement("span");
  element.className = `sandtable-map-stop${close ? " sandtable-map-stop--close" : ""}`;
  element.style.borderColor = colour;
  const size = close ? 18 : 14;
  return L.divIcon({ className: "sandtable-map-stop__anchor", html: element, iconSize: [size, size] });
};

const triangleIcon = () => {
  const box = svg("svg", { width: 26, height: 24, viewBox: "0 0 26 24" });
  box.appendChild(svg("polygon", { points: "13,2 24,22 2,22", fill: FIGURE_COLOURS.casing, stroke: FIGURE_COLOURS.rally, "stroke-width": 3, "stroke-linejoin": "round" }));
  const holder = document.createElement("span");
  holder.appendChild(box);
  return L.divIcon({ className: "sandtable-map-rally", html: holder, iconSize: [26, 24], iconAnchor: [13, 16] });
};

/**
 * Draws one step (a `stepView`) from the brief's geometry; returns what takes it away again. Every layer is in the
 * sandtable's own panes, takes no events and is left out of an LZ card's capture.
 */
export const attachSandtable = (map, view, geometry) => {
  if (!view) return () => {};
  PANES.forEach(([name, z]) => paneOf(map, name, z));
  // The class, not L.svg(): that answers null where the browser reports no SVG (jsdom), and the tests draw here too.
  const renderer = new L.SVG({ pane: SHAPES_PANE });
  const group = L.layerGroup().addTo(map);
  const shape = (layer) => layer.addTo(group);
  const mark = (latlng, icon, zIndexOffset = 0) =>
    L.marker(latlng, { icon, pane: MARKS_PANE, interactive: false, keyboard: false, zIndexOffset }).addTo(group);
  const label = (latlng, text, options) =>
    L.marker(latlng, { icon: labelIcon(text, options), pane: LABELS_PANE, interactive: false, keyboard: false }).addTo(group);
  const common = { renderer, interactive: false, bubblingMouseEvents: false };

  const routes = geometry?.routes ?? {};
  const stops = geometry?.stops ?? {};
  // The lit route last, so it is drawn over the others.
  const ordered = [...view.routes.filter((r) => !r.lit), ...view.routes.filter((r) => r.lit)];
  ordered.forEach(({ ref, lit, dimmed, labelled }) => {
    const route = routes[ref];
    if (!route) return;
    const colour = lit ? FIGURE_COLOURS.focus : (typeof route.color === "string" && route.color) || FIGURE_COLOURS.route;
    const opacity = dimmed ? 0.55 : 1;
    const weight = lit ? 6 : 4;
    const line = (route.line ?? []).map((p) => (Array.isArray(p) ? at(p[0], p[1]) : null)).filter(Boolean);
    if (line.length >= 2) {
      shape(L.polyline(line, { ...common, color: FIGURE_COLOURS.casing, weight: weight + 4, opacity: 0.6 * opacity, lineJoin: "round" }));
      shape(L.polyline(line, { ...common, color: colour, weight, opacity, lineJoin: "round", lineCap: "round" }));
    }
    (route.points ?? []).forEach((p) => {
      const place = at(p?.lat, p?.lon);
      if (!place) return;
      shape(L.circleMarker(place, { ...common, radius: lit ? 6 : 5, color: colour, weight: 2.5, opacity, fillColor: FIGURE_COLOURS.casing, fillOpacity: opacity }));
      if (labelled && p.name) label(place, p.name, { size: "small" });
    });
  });

  view.stops.forEach(({ ref, close }) => {
    const stop = stops[ref];
    const place = stop && at(stop.lat, stop.lon);
    if (!place) return;
    const colour = roleColour(stop.role);
    if (close) {
      const boundary = (stop.boundary ?? []).map((p) => (Array.isArray(p) ? at(p[0], p[1]) : null)).filter(Boolean);
      if (boundary.length >= 3) {
        shape(L.polygon(boundary, { ...common, color: colour, weight: 3, fillColor: colour, fillOpacity: 0.16 }));
      }
      (stop.aircraft ?? []).forEach((craft) => {
        const spot = at(craft?.lat, craft?.lon);
        if (!spot) return;
        shape(L.circleMarker(spot, { ...common, radius: 7, color: FIGURE_COLOURS.aircraft, weight: 2.5, fillColor: FIGURE_COLOURS.casing, fillOpacity: 1 }));
        mark(spot, arrowIcon(finite(craft.heading) ? craft.heading : 0, { length: 30, colour: FIGURE_COLOURS.aircraft, from: "tail" }));
      });
      if (finite(stop.landingHeading)) {
        mark(place, arrowIcon(stop.landingHeading, { length: 96, colour: "#ffffff", from: "tip" }), -100);
      }
    }
    mark(place, squareIcon(colour, close), 100);
    if (stop.name) label(place, stop.name, { size: close ? "large" : "normal" });
  });

  (geometry?.threats ?? []).forEach((threat) => {
    const place = at(threat?.lat, threat?.lon);
    if (!place) return;
    shape(L.circleMarker(place, { ...common, radius: 9, color: FIGURE_COLOURS.threat, weight: 3, fillColor: FIGURE_COLOURS.threat, fillOpacity: 0.35 }));
    label(place, threat.name || "Threat", { colour: FIGURE_COLOURS.threatLabel, size: "small" });
  });

  if (view.rally) {
    (geometry?.rally ?? []).forEach((rally) => {
      const place = at(rally?.lat, rally?.lon);
      if (!place) return;
      mark(place, triangleIcon(), 200);
      label(place, `${rally.name} (${rally.label})`, { colour: FIGURE_COLOURS.rally });
    });
  }

  return () => {
    group.remove();
    renderer.remove();
  };
};

const prefersReducedMotion = () => {
  try {
    return Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);
  } catch {
    return false;
  }
};

/**
 * The sandtable's layer inside the map. `presentation` is the sandtable's: `view` (the step, from stepView), `geometry`
 * (the brief's), and `measure(mapBox)`, the insets that keep the figure clear of what lies over the map.
 */
const SandtableLayer = ({ presentation }) => {
  const map = useMap();
  const { view, geometry, drawKey, measure } = presentation;

  // Flown to each step once (and again only if a step that had no area to show gets one), not at every change to the
  // plan under it: the presenter may have moved the map to point at something.
  const flown = useRef(null);

  // The map goes back to where it was when the brief ends. Taken away and set up again (React does so once in
  // development), it starts again: the view it gives back is the one it found, and the step is flown to again. Leaflet's
  // own arrow keys need nothing here: the sandtable takes every key before the map hears it.
  useEffect(() => {
    const before = { center: map.getCenter(), zoom: map.getZoom() };
    return () => {
      flown.current = null;
      // Gone with the whole map (a sign-out mid-brief): nothing to give back, and Leaflet would throw.
      if (!map.getPane("mapPane")) return;
      map.invalidateSize({ pan: false });
      // Without animation, which also ends a flight still under way.
      map.setView(before.center, before.zoom, { animate: false });
    };
  }, [map]);

  // Drawn again only when what it draws changed, not for every new copy of the same brief.
  const latest = useRef({ view, geometry });
  latest.current = { view, geometry };
  useEffect(() => attachSandtable(map, latest.current.view, latest.current.geometry), [map, drawKey]);

  useEffect(() => {
    if (!view) return;
    const bounds = view.bounds;
    const previous = flown.current;
    if (previous && previous.stepId === view.stepId && (previous.hadBounds || !bounds)) return;
    flown.current = { stepId: view.stepId, hadBounds: Boolean(bounds) };
    if (!bounds) return;
    // The frame around the map changed when the brief began; Leaflet must know its size before it fits anything.
    map.invalidateSize({ pan: false });
    const box = map.getContainer().getBoundingClientRect();
    const insets = measure ? measure(box) : fitInsets(box);
    const options = {
      paddingTopLeft: [insets.left, insets.top],
      paddingBottomRight: [insets.right, insets.bottom],
      maxZoom: 17,
    };
    if (prefersReducedMotion()) map.fitBounds(bounds, { ...options, animate: false });
    else map.flyToBounds(bounds, { ...options, duration: 0.9 });
  }, [map, view, measure]);

  return null;
};

export default SandtableLayer;
