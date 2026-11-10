import { useEffect, useRef } from "react";
import { useMap, useMapEvents } from "react-leaflet";
import L from "leaflet";
import { featureAt } from "./overlayHits";
import "./overlays.css";

/**
 * The map overlays that are switched on, under the MGRS grid (350) and everything planned on the map.
 *
 * They live in panes of their own that take no mouse events: under preferCanvas a pane's paths share
 * one canvas, and the topmost canvas takes every mouse event, so in a pane above the planning
 * graphics they would stop those being clicked. What an overlay says about a feature comes from the
 * map's own click, measured against it afterwards (overlayHits.js).
 *
 * Everything shown comes from a file somebody sent, so a name or description reaches the page only
 * as a text node: Leaflet takes a string as HTML, and never gets one here.
 */

export const IMAGE_PANE = "kmzImagePane";
export const VECTOR_PANE = "kmzVectorPane";
export const IMAGE_Z_INDEX = 310;
export const VECTOR_Z_INDEX = 330;

// Past this, a name beside every point would hide the map.
const MAX_LABELLED_POINTS = 200;

const paneFor = (map, name, zIndex) => {
  const pane = map.getPane(name) || map.createPane(name);
  pane.style.zIndex = String(zIndex);
  pane.style.pointerEvents = "none";
  return pane;
};

const textNode = (text, className) => {
  const element = document.createElement("div");
  element.className = className;
  element.textContent = text;
  return element;
};

/** The layers for one overlay, and what to let go of once it is taken off the map. */
export const buildOverlayLayers = (doc) => {
  const layers = [];
  const urls = [];
  doc.images.forEach((image) => {
    const url = URL.createObjectURL(new Blob([image.bytes], { type: image.mime }));
    urls.push(url);
    layers.push(L.imageOverlay(url, image.bounds, { pane: IMAGE_PANE, opacity: image.opacity, interactive: false }));
  });
  const points = doc.features.filter((feature) => feature.kind === "point");
  const labelled = points.length <= MAX_LABELLED_POINTS;
  doc.features.forEach((feature) => {
    const shared = { pane: VECTOR_PANE, interactive: false };
    if (feature.kind === "point") {
      const marker = L.circleMarker(feature.at, { ...shared, radius: 5, color: "#10161d", weight: 1.5, fillColor: feature.color, fillOpacity: feature.opacity, opacity: 1 });
      if (labelled && feature.name) {
        marker.bindTooltip(textNode(feature.name, "kmz-overlay-label__text"), { permanent: true, direction: "right", offset: [6, 0], className: "kmz-overlay-label", interactive: false });
      }
      layers.push(marker);
    } else if (feature.kind === "line") {
      layers.push(L.polyline(feature.path, { ...shared, color: feature.color, opacity: feature.opacity, weight: feature.width }));
    } else {
      layers.push(
        L.polygon(feature.rings, {
          ...shared,
          stroke: feature.outline,
          color: feature.color,
          opacity: feature.opacity,
          weight: feature.width,
          fill: feature.fill,
          fillColor: feature.fillColor,
          fillOpacity: feature.fillOpacity,
        }),
      );
    }
  });
  return { layers, urls };
};

const OverlayDrawing = ({ doc }) => {
  const map = useMap();
  useEffect(() => {
    const { layers, urls } = buildOverlayLayers(doc);
    const group = L.layerGroup(layers).addTo(map);
    return () => {
      group.remove();
      urls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [map, doc]);
  return null;
};

const popupContent = (feature) => {
  const element = document.createElement("div");
  if (feature.name) element.appendChild(textNode(feature.name, "kmz-overlay-popup__name"));
  if (feature.description) element.appendChild(textNode(feature.description, "kmz-overlay-popup__text"));
  return element;
};

const OverlayClicks = ({ overlays }) => {
  const map = useMapEvents({
    click: (event) => {
      const project = (lat, lon) => map.latLngToContainerPoint([lat, lon]);
      const feature = featureAt(overlays, event.containerPoint, project);
      if (!feature || (!feature.name && !feature.description)) return;
      L.popup({ maxWidth: 320, className: "kmz-overlay-popup" }).setLatLng(event.latlng).setContent(popupContent(feature)).openOn(map);
    },
  });
  return null;
};

/**
 * `overlays`: those open, drawn if `visible`. `fit`: { bounds, key }, where to go when the key changes.
 * `passive`: the map's clicks are something else's (drawing, a brief being presented).
 */
const KmzOverlayLayer = ({ overlays = [], fit = null, passive = false }) => {
  const map = useMap();
  paneFor(map, IMAGE_PANE, IMAGE_Z_INDEX);
  paneFor(map, VECTOR_PANE, VECTOR_Z_INDEX);

  const lastFit = useRef(fit?.key ?? null);
  useEffect(() => {
    if (!fit || fit.key === lastFit.current) return;
    lastFit.current = fit.key;
    map.fitBounds(fit.bounds, { padding: [48, 48], maxZoom: 16 });
  }, [map, fit]);

  const shown = overlays.filter((overlay) => overlay.visible);
  return (
    <>
      {shown.map((overlay) => (
        <OverlayDrawing key={overlay.id} doc={overlay.doc} />
      ))}
      {!passive && shown.length > 0 && <OverlayClicks overlays={shown} />}
    </>
  );
};

export default KmzOverlayLayer;
