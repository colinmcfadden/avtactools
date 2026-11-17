import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { keepInCapture } from "./captureFilter";

/*
 * The LZ card's picture. The map is made CARD_MAP's size off to one side, centred on the capture area at the zoom the
 * person is looking at, drawn by html-to-image at two pixels a CSS pixel, and the capture area cut out of that; the map is
 * then put back as it was, whatever happens. The mission overlay is drawn the same way (missionOverlay/captureOverlay.js).
 */

/** The map's size while the card is drawn, in CSS pixels: larger than a phone's map, so the capture area is on it. */
export const CARD_MAP = { width: 1200, height: 1000 };
export const PIXEL_RATIO = 2;

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Where the capture area is on the map, in CSS pixels from its top left corner. */
const areaOnMap = (map, exportBox) => {
  const a = map.latLngToContainerPoint(exportBox[0]);
  const b = map.latLngToContainerPoint(exportBox[1]);
  return {
    x: Math.min(a.x, b.x),
    y: Math.min(a.y, b.y),
    width: Math.abs(a.x - b.x),
    height: Math.abs(a.y - b.y),
  };
};

// Leaflet places a corner a fraction of a pixel either way.
const SLACK = 0.5;

/**
 * Draws the capture area (`exportBox`, two [lat, lon] corners) as the card's JPEG. `onProgress` hears 30 once the map has
 * settled at the card's size and 40 once it is drawn. Refuses, in words, rather than draw a card with part of it missing.
 */
export const captureCard = async (map, exportBox, { settleMs = 1500, onProgress = () => {} } = {}) => {
  const container = map.getContainer();
  const saved = { style: container.getAttribute("style"), center: map.getCenter(), zoom: map.getZoom() };
  try {
    // Important, because index.css holds every Leaflet container at 100% with !important, which a plain inline size
    // loses to: the map stayed the screen's size, and on a phone most of the card came out black.
    const styles = {
      position: "absolute",
      top: "0",
      left: "0",
      width: `${CARD_MAP.width}px`,
      height: `${CARD_MAP.height}px`,
      "z-index": "-1",
    };
    Object.entries(styles).forEach(([name, value]) => container.style.setProperty(name, value, "important"));
    map.invalidateSize({ pan: false });
    // The zoom the person chose, so the card is drawn at the detail they saw.
    map.setView(L.latLngBounds(exportBox).getCenter(), saved.zoom, { animate: false });
    await settle(settleMs);
    onProgress(30);

    const width = container.clientWidth;
    const height = container.clientHeight;
    if (width !== CARD_MAP.width || height !== CARD_MAP.height) {
      throw new Error("The map could not be made the card's size.");
    }
    const area = areaOnMap(map, exportBox);
    if (area.x < -SLACK || area.y < -SLACK || area.x + area.width > width + SLACK || area.y + area.height > height + SLACK) {
      throw new Error("The capture area is too large to draw at this zoom. Zoom out and export again.");
    }

    container.classList.add("hide-ui-for-export");
    const full = await htmlToImage.toCanvas(container, {
      quality: 1.0,
      pixelRatio: PIXEL_RATIO,
      width,
      height,
      skipAutoScale: true,
      skipFonts: true,
      // Controls, panels and screen-only layers (the MGRS grid) stay off the card.
      filter: keepInCapture,
    });
    onProgress(40);

    const x = area.x * PIXEL_RATIO;
    const y = area.y * PIXEL_RATIO;
    const w = area.width * PIXEL_RATIO;
    const h = area.height * PIXEL_RATIO;
    const crop = document.createElement("canvas");
    crop.width = w;
    crop.height = h;
    crop.getContext("2d").drawImage(full, x, y, w, h, 0, 0, w, h);
    return await new Promise((resolve, reject) => {
      crop.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("The image could not be made."))), "image/jpeg", 1.0);
    });
  } finally {
    container.classList.remove("hide-ui-for-export");
    if (saved.style === null) container.removeAttribute("style");
    else container.setAttribute("style", saved.style);
    map.invalidateSize({ pan: false });
    map.setView(saved.center, saved.zoom, { animate: false });
  }
};
