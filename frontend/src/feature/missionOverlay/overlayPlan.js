/*
 * A mission overlay (docs/USER_GUIDE.md, *Mission overlay*): the map's planning layers drawn in square pieces at one zoom,
 * which the server puts together into an MBTiles or GeoTIFF for ForeFlight, with USGS's topo map under them if asked
 * (backend/overlay_image.py). This is the arithmetic of it, apart from the map: at what zoom the area is drawn, where it
 * lies in that zoom's pixels, how many pieces that is, and what it comes to on the ground.
 *
 * Everything here is in device pixels: the map is drawn at PIXEL_RATIO device pixels a CSS pixel, so a device zoom is the
 * map's CSS zoom plus one, and Leaflet's global pixels at it are twice its CSS ones. The server takes the area in those.
 */

// Lines and labels drawn as on a retina screen: crisp at the overlay's own resolution.
export const PIXEL_RATIO = 2;
// Device pixels a side of each piece: 8 tiles, and well under any phone's largest canvas (iOS stops near 16.7 million).
export const PIECE = 2048;
export const PIECE_CSS = PIECE / PIXEL_RATIO;
export const TILE = 256;
// CSS pixels round the area, so a symbol or label on its edge is not cut through.
export const MARGIN = 48;
// Past this a pixel is under 30 cm and shows nothing more of what is planned.
export const MAX_ZOOM = 19;
// USGS's topo map has no tiles past level 16, about 2 m a pixel in the lower 48 (backend/usgs_topo.py).
export const USGS_MAX_ZOOM = 16;
// ForeFlight's limit for a GeoTIFF, and the server's.
export const GEOTIFF_SIDE = 10_000;
// The most tiles at an MBTiles file's finest zoom, the server's: about 100 million pixels, as a GeoTIFF's most.
export const MBTILES_TILES = 1_600;
// What the pieces may come to, under the server's 90 MB for a request (overlay_image_routes.MAX_UPLOAD_BYTES).
export const MAX_UPLOAD_BYTES = 88 * 1024 * 1024;
const MARGIN_PX = MARGIN * PIXEL_RATIO;
const EARTH_CIRCUMFERENCE_M = 40075016.686;
const EARTH_RADIUS_M = 6371008.8;
const MAX_LAT = 85.0511287798;

export const FORMATS = {
  mbtiles: { extension: "mbtiles", title: "MBTiles" },
  geotiff: { extension: "tif", title: "GeoTIFF" },
};

export const BASES = {
  none: { title: "None" },
  "usgs-topo": { title: "USGS topo" },
};

const clampLat = (lat) => Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));

/** Leaflet's projection at zoom 0. */
export const project0 = (lat, lon) => {
  const sin = Math.sin((clampLat(lat) * Math.PI) / 180);
  return {
    x: ((lon + 180) / 360) * 256,
    y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * 256,
  };
};

/** Bounds as { north, south, east, west }, from Leaflet's LatLngBounds (or anything with the same getters). */
export const plainBounds = (bounds) => ({
  north: bounds.getNorth(),
  south: bounds.getSouth(),
  east: bounds.getEast(),
  west: bounds.getWest(),
});

/** On a tenth, without a float's last digit (15.3 is never 15.299999). */
const tenth = (zoom) => Math.round(zoom * 10) / 10;

/**
 * Where `bounds` and its margin lie at a device zoom, in that zoom's global pixels: an MBTiles area on whole tiles, a
 * GeoTIFF's on even pixels (so each piece starts on a whole CSS pixel), both within 85° of the equator.
 */
export const areaAt = (bounds, zoom, format) => {
  const scale = 2 ** zoom;
  const world = TILE * scale;
  const nw = project0(bounds.north, bounds.west);
  const se = project0(bounds.south, bounds.east);
  const step = format === "mbtiles" ? TILE : 2;
  const down = (value) => Math.floor(value / step) * step;
  const up = (value) => Math.ceil(value / step) * step;
  const left = down(nw.x * scale - MARGIN_PX);
  const right = Math.max(left + step, up(se.x * scale + MARGIN_PX));
  const top = Math.max(0, down(nw.y * scale - MARGIN_PX));
  const bottom = Math.max(top + step, Math.min(down(world), up(se.y * scale + MARGIN_PX)));
  return { zoom, left, top, width: right - left, height: bottom - top };
};

/** Whether an area is within the format's limit. */
const fits = (area, format) =>
  format === "mbtiles"
    ? (area.width / TILE) * (area.height / TILE) <= MBTILES_TILES
    : Math.max(area.width, area.height) <= GEOTIFF_SIDE;

/** The finest device zoom the format takes the area at, at most `cap`: a whole one for MBTiles, a tenth for a GeoTIFF. */
const finestZoom = (bounds, format, cap) => {
  if (format === "mbtiles") {
    // A tile count, which a long thin area can spend along one side: every whole zoom down from the cap.
    let zoom = Math.floor(cap);
    while (zoom > 1 && !fits(areaAt(bounds, zoom, format), format)) zoom -= 1;
    return Math.max(1, zoom);
  }
  // A longest side: from the zoom it would fill the limit at, a step above for the rounding, down until it fits.
  let zoom = tenth(Math.floor(cap * 10 + 1e-6) / 10);
  const nw = project0(bounds.north, bounds.west);
  const se = project0(bounds.south, bounds.east);
  const longest = Math.max(se.x - nw.x, se.y - nw.y);
  if (longest > 0) zoom = Math.min(zoom, tenth(Math.floor(Math.log2((GEOTIFF_SIDE - 2 * MARGIN_PX) / longest) * 10) / 10 + 0.2));
  while (zoom > 1 && !fits(areaAt(bounds, zoom, format), format)) zoom = tenth(zoom - 0.1);
  return Math.max(1, zoom);
};

/** The ground a pixel covers at the area's middle, in metres. */
const metresPerPixel = (bounds, zoom) =>
  (EARTH_CIRCUMFERENCE_M * Math.cos((((bounds.north + bounds.south) / 2) * Math.PI) / 180)) / (TILE * 2 ** zoom);

/**
 * The details an overlay of `bounds` can be made at, finest first: `finest`, as fine as the format, the base map and the
 * map allow, and `lighter`, a level coarser (a quarter of the pixels). Each is a plan: the device zoom, the area at it,
 * its pieces, and what a pixel covers. `limitedBy` says what held the finest back: "base" (the USGS map goes no finer),
 * "zoom" (finer would show nothing more) or "size" (the format's limit).
 */
export const overlayPlans = (bounds, { format = "mbtiles", base = "none", mapMaxZoom = Infinity } = {}) => {
  // The map's CSS zoom is the device zoom less one, and the map goes no deeper than its own most.
  const ceiling = Math.min(MAX_ZOOM, Number.isFinite(mapMaxZoom) ? mapMaxZoom + 1 : MAX_ZOOM);
  const cap = base === "usgs-topo" ? Math.min(USGS_MAX_ZOOM, ceiling) : ceiling;
  const finest = finestZoom(bounds, format, cap);
  // At the USGS map's own most, that is what holds it (whether the file would also allow no finer or not).
  const limitedBy = finest < cap ? "size" : base === "usgs-topo" && cap === USGS_MAX_ZOOM ? "base" : "zoom";
  const plan = (zoom) => {
    const area = areaAt(bounds, zoom, format);
    const columns = Math.ceil(area.width / PIECE);
    const rows = Math.ceil(area.height / PIECE);
    return { ...area, format, base, columns, rows, pieces: columns * rows, metresPerPixel: metresPerPixel(bounds, zoom) };
  };
  const lighterZoom = tenth(finest - 1);
  return { finest: plan(finest), lighter: lighterZoom >= 1 ? plan(lighterZoom) : null, limitedBy };
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
export const describeResolution = (metres) => `about ${metres >= 10 ? Math.round(metres) : metres.toFixed(1)} m a pixel`;

/**
 * The scale a pixel comes to on an iPad's screen (about a tenth of a millimetre a pixel), as a chart's, to two figures:
 * "1:20,000" for 2 m a pixel. ForeFlight shows it larger or smaller as it zooms; this is where it is at its sharpest.
 */
export const describeScale = (metres) => {
  const denominator = metres * 10_000;
  const figures = 10 ** Math.max(0, Math.floor(Math.log10(denominator)) - 1);
  return `1:${(Math.round(denominator / figures) * figures).toLocaleString("en-US")}`;
};

/** "9,800 × 7,400" */
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

/** The file's name for a layer name and format. */
export const overlayFileName = (name, format) => `${layerName(name)}.${(FORMATS[format] ?? FORMATS.mbtiles).extension}`;
