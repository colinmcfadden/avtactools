import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { keepInCapture, SCREEN_ONLY_CLASS } from "../export/captureFilter";
import { IMAGE_PANE, VECTOR_PANE } from "../overlays/KmzOverlayLayer";
import { OVERLAY_CAPTURE_CLASS, PIECE, PIECE_CSS, PIXEL_RATIO, plainBounds, TILE } from "./overlayPlan";

/*
 * Drawing the map's planning layers into transparent square pieces of an overlay (overlayPlan.js says where). The map is
 * made one piece's size off to one side, as the LZ card's capture does (feature/export/captureCard.js), set at the plan's
 * zoom with each piece's corner exactly on the map's, drawn by html-to-image with the base map left out, and put back as
 * it was, whatever happens. The base map's tile layers are taken off while it draws: they are not in the overlay, and
 * fetching their tiles for every piece would only cost time (and Mapbox's tiles).
 */

// Classes of what the overlay leaves out besides the LZ card's: the base map's tiles, popups, other people's pointers in a
// pack, and the LZ card's frame handles. (The MGRS grid, FLOT and backstop handles are SCREEN_ONLY_CLASS already.)
const LEFT_OUT = ["leaflet-tile-pane", "leaflet-popup-pane", "packs-cursor", "move-handle", "resize-handle-se"];

/** html-to-image's filter for the overlay. */
export const keepInOverlay = (node) => keepInCapture(node) && !LEFT_OUT.some((name) => node.classList?.contains(name));

const iconClass = (layer) => layer.options?.icon?.options?.className ?? "";

/** A layer that is the screen's alone, never the mission's. A path says so with `screenOnly` (the LZ card's frame). */
export const isScreenOnly = (map, layer) => {
  if (layer instanceof L.GridLayer || layer instanceof L.Popup || layer instanceof L.Tooltip) return true;
  if (layer.options?.screenOnly) return true;
  const pane = layer.options?.pane ? map.getPane(layer.options.pane) : null;
  if (pane?.classList?.contains(SCREEN_ONLY_CLASS)) return true;
  const icon = iconClass(layer);
  return icon.includes(SCREEN_ONLY_CLASS) || LEFT_OUT.some((name) => icon.includes(name));
};

const boundsOf = (layer) => {
  try {
    if (typeof layer.getBounds === "function") {
      const bounds = layer.getBounds();
      return bounds?.isValid?.() ? bounds : null;
    }
    if (typeof layer.getLatLng === "function") {
      const at = layer.getLatLng();
      return at ? L.latLngBounds(at, at) : null;
    }
  } catch {
    // A layer that cannot say where it is (a circle not yet drawn) adds nothing.
  }
  return null;
};

/**
 * Where everything planned on the map lies, as { north, south, east, west }, or null when nothing is. Imported overlays
 * (KMZ, KML) count only when nothing else is drawn: one can cover a whole country, and the mission is what was planned.
 */
export const missionBounds = (map) => {
  let planned = null;
  let imported = null;
  map.eachLayer((layer) => {
    if (isScreenOnly(map, layer)) return;
    const bounds = boundsOf(layer);
    if (!bounds) return;
    const fromImport = layer.options?.pane === IMAGE_PANE || layer.options?.pane === VECTOR_PANE;
    const copy = L.latLngBounds(bounds.getSouthWest(), bounds.getNorthEast());
    if (fromImport) imported = imported ? imported.extend(copy) : copy;
    else planned = planned ? planned.extend(copy) : copy;
  });
  const found = planned ?? imported;
  return found ? plainBounds(found) : null;
};

/** The map as it is on the screen. */
export const shownBounds = (map) => plainBounds(map.getBounds());

// Device pixels round a layer's place that a piece is drawn for: a marker's icon (a doghouse, a label, a symbol) reaches
// well past its point, a line's width and arrows less far. Too much costs a piece drawn for nothing; too little, a piece
// left out with something in it.
const MARKER_REACH = 256 * PIXEL_RATIO;
const PATH_REACH = 64 * PIXEL_RATIO;

const flatLatLngs = (latLngs, out = []) => {
  for (const item of latLngs ?? []) {
    if (Array.isArray(item)) flatLatLngs(item, out);
    else if (item && Number.isFinite(item.lat) && Number.isFinite(item.lng)) out.push(item);
  }
  return out;
};

const ringsOf = (latLngs) => {
  if (!Array.isArray(latLngs) || latLngs.length === 0) return [];
  return Array.isArray(latLngs[0]) ? latLngs.flatMap(ringsOf) : [latLngs];
};

/**
 * The pieces of a plan with something drawn in them, as a Set of "column,row": what is planned on the map, each layer by
 * where it reaches (a line along its length, a filled shape or an image over its bounds, a marker round its point). A
 * layer that cannot say where it is counts everywhere, so nothing is left out. A piece not drawn is sent as nothing: the
 * server takes it as transparent (backend/overlay_image.py).
 */
export const piecesWithContent = (map, plan) => {
  const found = new Set();
  const world = TILE * 2 ** plan.zoom;
  const every = () => {
    for (let row = 0; row < plan.rows; row += 1) for (let column = 0; column < plan.columns; column += 1) found.add(`${column},${row}`);
  };
  // A box in the plan's global device pixels, and its copies a world east and west (an area across the antimeridian).
  const box = (x0, y0, x1, y1) => {
    for (const shift of [-world, 0, world]) {
      const c0 = Math.max(0, Math.floor((x0 + shift - plan.left) / PIECE));
      const c1 = Math.min(plan.columns - 1, Math.floor((x1 + shift - plan.left) / PIECE));
      const r0 = Math.max(0, Math.floor((y0 - plan.top) / PIECE));
      const r1 = Math.min(plan.rows - 1, Math.floor((y1 - plan.top) / PIECE));
      for (let row = r0; row <= r1; row += 1) for (let column = c0; column <= c1; column += 1) found.add(`${column},${row}`);
    }
  };
  const at = (latLng) => map.project(latLng, plan.zoom);
  const around = (point, reach) => box(point.x - reach, point.y - reach, point.x + reach, point.y + reach);
  const along = (latLngs, closed) => {
    const points = latLngs.map(at);
    if (closed && points.length > 2) points.push(points[0]);
    if (points.length === 1) around(points[0], PATH_REACH);
    for (let k = 1; k < points.length; k += 1) {
      const a = points[k - 1];
      const b = points[k];
      // Steps of a quarter piece: every piece the line passes through, and none its bounding box only spans.
      const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / (PIECE / 4)));
      for (let step = 0; step <= steps; step += 1) {
        around({ x: a.x + ((b.x - a.x) * step) / steps, y: a.y + ((b.y - a.y) * step) / steps }, PATH_REACH);
      }
    }
  };
  const over = (bounds, reach) => {
    const nw = at(bounds.getNorthWest());
    const se = at(bounds.getSouthEast());
    box(Math.min(nw.x, se.x) - reach, nw.y - reach, Math.max(nw.x, se.x) + reach, se.y + reach);
  };

  map.eachLayer((layer) => {
    if (found.size === plan.columns * plan.rows) return;
    // Groups hold layers the map lists too, and a renderer is where paths are drawn, not a path.
    if (layer instanceof L.LayerGroup || layer instanceof L.Renderer) return;
    if (isScreenOnly(map, layer)) return;
    try {
      if (layer instanceof L.Circle) {
        if (layer.options.fill) over(layer.getBounds(), PATH_REACH);
        else {
          const centre = at(layer.getLatLng());
          const edge = at(L.latLng(layer.getBounds().getNorth(), layer.getLatLng().lng));
          const radius = Math.abs(centre.y - edge.y);
          const ring = Array.from({ length: 65 }, (_, k) => {
            const angle = (k / 64) * 2 * Math.PI;
            return map.unproject([centre.x + radius * Math.cos(angle), centre.y + radius * Math.sin(angle)], plan.zoom);
          });
          along(ring, false);
        }
      } else if (layer instanceof L.CircleMarker) {
        around(at(layer.getLatLng()), (layer.getRadius() + (layer.options.weight ?? 0)) * PIXEL_RATIO + PATH_REACH);
      } else if (layer instanceof L.Polygon) {
        if (layer.options.fill) over(layer.getBounds(), PATH_REACH);
        else ringsOf(layer.getLatLngs()).forEach((ring) => along(flatLatLngs(ring), true));
      } else if (layer instanceof L.Polyline) {
        ringsOf(layer.getLatLngs()).forEach((line) => along(flatLatLngs(line), false));
      } else if (layer instanceof L.Marker) {
        around(at(layer.getLatLng()), MARKER_REACH);
      } else if (typeof layer.getBounds === "function") {
        over(layer.getBounds(), PATH_REACH);
      } else if (typeof layer.getLatLng === "function") {
        around(at(layer.getLatLng()), MARKER_REACH);
      } else {
        every();
      }
    } catch {
      // One that cannot say where it is (not yet drawn) is drawn wherever it may be.
      every();
    }
  });
  return found;
};

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Paths marked `screenOnly` (drawn on the map's one canvas, so no class can leave them out) hidden until put back. */
const hideScreenOnlyPaths = (map) => {
  const hidden = [];
  map.eachLayer((layer) => {
    if (layer.options?.screenOnly && typeof layer.setStyle === "function") {
      hidden.push([layer, { opacity: layer.options.opacity, fillOpacity: layer.options.fillOpacity }]);
      layer.setStyle({ opacity: 0, fillOpacity: 0 });
    }
  });
  return () => hidden.forEach(([layer, style]) => layer.setStyle(style));
};

const pngOf = (canvas) =>
  new Promise((resolve, reject) => {
    canvas.toBlob((made) => (made ? resolve(made) : reject(new Error("A piece of the overlay could not be made."))), "image/png");
  });

/**
 * Draws a plan's pieces (overlayPlans) row by row: all of them, or those in `pieces` (a Set of "column,row",
 * piecesWithContent). Returns [{ column, row, blob }], each blob a PNG of PIECE pixels a side whose top left corner is the
 * plan's (left + column × PIECE, top + row × PIECE); or, given `onDrawn`, hands each to it as it is drawn (and waits for
 * it: a large overlay is sent as it is drawn, not held) and returns []. `onPiece(done, total)` is told as each piece is
 * begun. `signal` stops it between pieces (an AbortError). The first piece waits `firstSettleMs` for what the new zoom
 * redraws; the rest, which only move, `settleMs`.
 */
export const captureOverlay = async (
  map,
  plan,
  { pieces: wanted, onPiece, onDrawn, signal, firstSettleMs = 1200, settleMs = 400 } = {},
) => {
  const zoom = Math.round((plan.zoom - Math.log2(PIXEL_RATIO)) * 10) / 10;
  const container = map.getContainer();
  const saved = {
    style: container.getAttribute("style"),
    center: map.getCenter(),
    zoom: map.getZoom(),
    retina: L.Browser.retina,
  };
  const tileLayers = [];
  map.eachLayer((layer) => {
    if (layer instanceof L.GridLayer) tileLayers.push(layer);
  });
  let showPaths = null;
  try {
    // Leaflet's canvas reads this at every redraw: the lines are drawn at two pixels a CSS pixel on any screen, as
    // html-to-image draws everything else, rather than at one and enlarged.
    L.Browser.retina = true;
    // Important, because index.css holds every Leaflet container at 100% with !important, which a plain inline width
    // loses to.
    const styles = {
      position: "absolute",
      top: "0",
      left: "0",
      width: `${PIECE_CSS}px`,
      height: `${PIECE_CSS}px`,
      "z-index": "-1",
      background: "transparent",
    };
    Object.entries(styles).forEach(([name, value]) => container.style.setProperty(name, value, "important"));
    container.classList.add("hide-ui-for-export", OVERLAY_CAPTURE_CLASS);
    showPaths = hideScreenOnlyPaths(map);
    tileLayers.forEach((layer) => map.removeLayer(layer));
    map.invalidateSize({ pan: false });

    const order = [];
    for (let row = 0; row < plan.rows; row += 1) {
      for (let column = 0; column < plan.columns; column += 1) {
        if (!wanted || wanted.has(`${column},${row}`)) order.push([column, row]);
      }
    }
    const total = order.length;
    const pieces = [];
    let drawn = 0;
    for (const [column, row] of order) {
      if (signal?.aborted) throw new DOMException("The overlay was stopped.", "AbortError");
      onPiece?.(drawn, total);
      // The piece's corner in the map's own (CSS) pixels at its zoom: a whole number, which the map's corner is set on.
      const left = (plan.left + column * PIECE) / PIXEL_RATIO;
      const top = (plan.top + row * PIECE) / PIXEL_RATIO;
      // `reset` so the map is set afresh: a move within the screen would otherwise pan, keeping its old pixel origin.
      map.setView(map.unproject([left + PIECE_CSS / 2, top + PIECE_CSS / 2], zoom), zoom, { animate: false, reset: true });
      await settle(drawn === 0 ? firstSettleMs : settleMs);
      // A map that did not take the piece's size, zoom or corner would be drawn somewhere else than the server puts it.
      const size = map.getSize();
      const corner = map.containerPointToLayerPoint([0, 0]).add(map.getPixelOrigin());
      if (
        size.x !== PIECE_CSS ||
        size.y !== PIECE_CSS ||
        Math.abs(map.getZoom() - zoom) > 1e-6 ||
        Math.abs(corner.x - left) > 0.01 ||
        Math.abs(corner.y - top) > 0.01
      ) {
        throw new Error("The map could not be set to a piece of the overlay.");
      }
      const canvas = await htmlToImage.toCanvas(container, {
        pixelRatio: PIXEL_RATIO,
        width: PIECE_CSS,
        height: PIECE_CSS,
        skipAutoScale: true,
        skipFonts: true,
        filter: keepInOverlay,
      });
      if (canvas.width !== PIECE || canvas.height !== PIECE) throw new Error("A piece of the overlay was drawn at the wrong size.");
      const piece = { column, row, blob: await pngOf(canvas) };
      drawn += 1;
      if (onDrawn) await onDrawn(piece);
      else pieces.push(piece);
    }
    onPiece?.(total, total);
    return pieces;
  } finally {
    L.Browser.retina = saved.retina;
    try {
      showPaths?.();
      container.classList.remove("hide-ui-for-export", OVERLAY_CAPTURE_CLASS);
      if (saved.style === null) container.removeAttribute("style");
      else container.setAttribute("style", saved.style);
      map.invalidateSize({ pan: false });
      map.setView(saved.center, saved.zoom, { animate: false, reset: true });
    } finally {
      // Back once the map is where it was, so they fetch the screen's tiles and not a piece's.
      tileLayers.forEach((layer) => {
        if (!map.hasLayer(layer)) map.addLayer(layer);
      });
    }
  }
};
