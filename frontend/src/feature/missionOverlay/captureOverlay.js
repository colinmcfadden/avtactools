import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { keepInCapture, SCREEN_ONLY_CLASS } from "../export/captureFilter";
import { IMAGE_PANE, VECTOR_PANE } from "../overlays/KmzOverlayLayer";
import { capturePlan, PIXEL_RATIO, plainBounds } from "./overlayPlan";

/*
 * Drawing the map's planning layers into one transparent PNG at known corners (overlayPlan.js says how large). The map is
 * made the plan's size off to one side, as the LZ card's capture does (feature/export/ExportHandler.jsx), set to the
 * plan's zoom over the area, drawn by html-to-image with the base map left out, and put back as it was, whatever happens.
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

/**
 * Draws `bounds` at `detail` into a transparent PNG. Returns { blob, bounds, width, height }: the image, the corners it was
 * drawn between (the map's own, a margin wider than `bounds`), and its size in pixels.
 */
export const captureOverlay = async (map, bounds, detail, { settleMs = 1200 } = {}) => {
  const plan = capturePlan(bounds, detail, map.getMaxZoom());
  const container = map.getContainer();
  const saved = {
    style: container.getAttribute("style"),
    center: map.getCenter(),
    zoom: map.getZoom(),
    retina: L.Browser.retina,
  };
  // The area's Mercator middle, which its plain average of latitudes is not.
  const nw = map.project([bounds.north, bounds.west], plan.zoom);
  const se = map.project([bounds.south, bounds.east], plan.zoom);
  const middle = map.unproject(nw.add(se).divideBy(2), plan.zoom);
  let showPaths = null;
  try {
    // Leaflet's canvas reads this at every redraw: the lines are drawn at two pixels a CSS pixel on any screen, as
    // html-to-image draws everything else, rather than at one and enlarged.
    L.Browser.retina = true;
    // Important, because index.css holds every Leaflet container at 100% with !important, which a plain inline width
    // loses to: the map stayed the screen's size and only the middle of the area was drawn.
    const styles = {
      position: "absolute",
      top: "0",
      left: "0",
      width: `${plan.cssWidth}px`,
      height: `${plan.cssHeight}px`,
      "z-index": "-1",
      background: "transparent",
    };
    Object.entries(styles).forEach(([name, value]) => container.style.setProperty(name, value, "important"));
    container.classList.add("hide-ui-for-export");
    showPaths = hideScreenOnlyPaths(map);
    map.invalidateSize({ pan: false });
    map.setView(middle, plan.zoom, { animate: false });
    await settle(settleMs);

    const width = container.clientWidth;
    const height = container.clientHeight;
    const topLeft = map.containerPointToLatLng([0, 0]);
    const bottomRight = map.containerPointToLatLng([width, height]);
    // A map that did not take the plan's size or zoom would be drawn over less than the area, at corners right for what
    // was drawn: a file that looks fine and leaves the mission's edges out.
    if (!L.latLngBounds(topLeft, bottomRight).contains(L.latLngBounds([bounds.south, bounds.west], [bounds.north, bounds.east]))) {
      throw new Error("The map could not be made the overlay's size.");
    }
    const canvas = await htmlToImage.toCanvas(container, {
      pixelRatio: PIXEL_RATIO,
      width,
      height,
      skipAutoScale: true,
      skipFonts: true,
      filter: keepInOverlay,
    });
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob((made) => (made ? resolve(made) : reject(new Error("The image could not be made."))), "image/png");
    });
    return {
      blob,
      bounds: { north: topLeft.lat, south: bottomRight.lat, west: topLeft.lng, east: bottomRight.lng },
      width: canvas.width,
      height: canvas.height,
    };
  } finally {
    L.Browser.retina = saved.retina;
    showPaths?.();
    container.classList.remove("hide-ui-for-export");
    if (saved.style === null) container.removeAttribute("style");
    else container.setAttribute("style", saved.style);
    map.invalidateSize({ pan: false });
    map.setView(saved.center, saved.zoom, { animate: false });
  }
};
