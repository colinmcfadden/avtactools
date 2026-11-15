/*
 * A mission overlay (docs/USER_GUIDE.md, *Mission overlay*): the map's planning layers drawn into one transparent image at
 * known corners, which the server makes into an MBTiles or GeoTIFF for ForeFlight (backend/overlay_image.py). This is the
 * arithmetic of it, apart from the map: at what zoom the area is drawn, how large, and what that comes to on the ground.
 *
 * The map is Leaflet's Web Mercator on 256-pixel tiles, so an area's size in CSS pixels at a zoom is its size at zoom 0
 * times 2^zoom, and the image (drawn at PIXEL_RATIO device pixels a CSS pixel) is linear in Mercator between its corners.
 */

/** The longest side of the image, in device pixels, for each detail. Standard fits a phone's canvas; High wants a desktop's. */
export const DETAIL = {
  standard: { px: 3200, title: "Standard" },
  high: { px: 6400, title: "High" },
};
// Lines and labels drawn as on a retina screen: crisp at the image's own resolution.
export const PIXEL_RATIO = 2;
// CSS pixels round the area, so a symbol or label on its edge is not cut through.
export const MARGIN = 48;
// Past this a ground pixel is under half a metre: the image grows and shows nothing more.
export const MAX_CAPTURE_ZOOM = 18;
const ZOOM_SNAP = 0.1; // the map's zoomSnap; Leaflet rounds a zoom to it, so it is floored to it here first
const EARTH_CIRCUMFERENCE_M = 40075016.686;
const EARTH_RADIUS_M = 6371008.8;
const MAX_LAT = 85.0511287798;

const clampLat = (lat) => Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));

/** Leaflet's projection at zoom 0, in CSS pixels. */
export const project0 = (lat, lon) => {
  const sin = Math.sin((clampLat(lat) * Math.PI) / 180);
  return {
    x: ((lon + 180) / 360) * 256,
    y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * 256,
  };
};

/** Bounds as the server takes them, from Leaflet's LatLngBounds (or anything with the same getters). */
export const plainBounds = (bounds) => ({
  north: bounds.getNorth(),
  south: bounds.getSouth(),
  east: bounds.getEast(),
  west: bounds.getWest(),
});

/** An area's size at zoom 0, in CSS pixels. */
const size0 = (bounds) => {
  const nw = project0(bounds.north, bounds.west);
  const se = project0(bounds.south, bounds.east);
  return { width: Math.max(0, se.x - nw.x), height: Math.max(0, se.y - nw.y) };
};

/**
 * How the area is drawn for a detail: the zoom (floored to the map's snap, at most `maxZoom`), the map's size in CSS
 * pixels with the margins, the image's in device pixels, and the ground a pixel covers at the area's middle.
 */
export const capturePlan = (bounds, detail = "standard", maxZoom = MAX_CAPTURE_ZOOM) => {
  const longest = (DETAIL[detail] ?? DETAIL.standard).px / PIXEL_RATIO - 2 * MARGIN;
  const { width, height } = size0(bounds);
  const top = Math.min(MAX_CAPTURE_ZOOM, maxZoom);
  const fit = Math.max(width, height) > 0 ? Math.log2(longest / Math.max(width, height)) : top;
  // Floored on a tenth, with room for a float's last digit (15.3 / 0.1 is 152.99999…).
  const zoom = Math.min(top, Math.floor(fit / ZOOM_SNAP + 1e-6) * ZOOM_SNAP);
  const scale = 2 ** zoom;
  const cssWidth = Math.ceil(width * scale) + 2 * MARGIN;
  const cssHeight = Math.ceil(height * scale) + 2 * MARGIN;
  const middle = (bounds.north + bounds.south) / 2;
  const metresPerPixel = (EARTH_CIRCUMFERENCE_M * Math.cos((middle * Math.PI) / 180)) / (256 * scale) / PIXEL_RATIO;
  return {
    zoom: Math.round(zoom * 10) / 10,
    cssWidth,
    cssHeight,
    width: cssWidth * PIXEL_RATIO,
    height: cssHeight * PIXEL_RATIO,
    metresPerPixel,
  };
};

const haversine = (lat1, lon1, lat2, lon2) => {
  const rad = Math.PI / 180;
  const a =
    Math.sin(((lat2 - lat1) * rad) / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(((lon2 - lon1) * rad) / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
};

/** The area's width (across its middle) and height on the ground, in kilometres. */
export const areaSize = (bounds) => {
  const middle = (bounds.north + bounds.south) / 2;
  return {
    widthKm: haversine(middle, bounds.west, middle, bounds.east) / 1000,
    heightKm: haversine(bounds.south, bounds.west, bounds.north, bounds.west) / 1000,
  };
};

const km = (value) => (value >= 10 ? String(Math.round(value)) : value >= 1 ? value.toFixed(1) : value.toFixed(2));

/** "24 × 18 km" */
export const describeArea = (bounds) => {
  const { widthKm, heightKm } = areaSize(bounds);
  return `${km(widthKm)} × ${km(heightKm)} km`;
};

/** "about 0.6 m a pixel", "about 12 m a pixel" */
export const describeResolution = (metresPerPixel) =>
  `about ${metresPerPixel >= 10 ? Math.round(metresPerPixel) : metresPerPixel.toFixed(1)} m a pixel`;

/** "3,200 × 2,410" */
export const describePixels = (plan) => `${plan.width.toLocaleString("en-US")} × ${plan.height.toLocaleString("en-US")}`;

/**
 * What ForeFlight lists the layer as, and the file's name, as the server makes it (overlay_image_routes._layer_name):
 * letters, digits, spaces and `_ . ( ) -`, at most 60 characters, never empty.
 */
export const layerName = (raw) => {
  const name = String(raw ?? "")
    .replace(/[^\p{L}\p{N}_ .()-]+/gu, " ")
    .replace(/\s+/g, " ")
    .replace(/^[ .]+|[ .]+$/g, "");
  const cut = [...name].slice(0, 60).join("").replace(/[ .]+$/g, "");
  return cut || "MISSION OVERLAY";
};

export const FORMATS = {
  mbtiles: { extension: "mbtiles", title: "MBTiles" },
  geotiff: { extension: "tif", title: "GeoTIFF" },
};

/** The file's name for a layer name and format. */
export const overlayFileName = (name, format) => `${layerName(name)}.${(FORMATS[format] ?? FORMATS.mbtiles).extension}`;
