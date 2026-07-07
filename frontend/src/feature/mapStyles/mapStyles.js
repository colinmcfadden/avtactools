const MAPBOX_TOKEN =
  "pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw";

const MAPBOX_ATTRIBUTION =
  '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>';

const mapboxTiles = (styleId) =>
  `https://api.mapbox.com/styles/v1/mapbox/${styleId}/tiles/512/{z}/{x}/{y}@2x?access_token=${MAPBOX_TOKEN}`;

// Mapbox serves 512px tiles, which Leaflet renders at the right scale with
// this pair; standard 256px OSM-style servers need neither.
const MAPBOX_TILE_OPTIONS = {
  attribution: MAPBOX_ATTRIBUTION,
  tileSize: 512,
  zoomOffset: -1,
  maxZoom: 20,
};

/**
 * Registry of available base maps. To add a map, append an entry here — the
 * switcher control and the map's TileLayer both render from this list.
 *
 * - `url`: Leaflet tile URL template ({z}/{x}/{y} tokens in any order).
 * - `options`: spread directly onto the <TileLayer>, so any Leaflet
 *   TileLayer option works (attribution, tileSize, native zoom range, ...).
 *   The tile server must allow CORS — tiles are drawn with
 *   crossOrigin="anonymous" so the LZ-card canvas export can read them.
 * - `preview` (optional): {z,x,y} of the switcher-thumbnail tile, for
 *   servers whose zoom range doesn't include the default preview tile.
 */
export const MAP_STYLES = [
  {
    id: "satellite",
    label: "Satellite",
    url: mapboxTiles("satellite-v9"),
    options: MAPBOX_TILE_OPTIONS,
  },
  {
    id: "topo",
    label: "Topo",
    url: mapboxTiles("outdoors-v12"),
    options: MAPBOX_TILE_OPTIONS,
  },
  {
    id: "vfr-sectional",
    label: "VFR",
    url: "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/{z}/{y}/{x}",
    options: {
      attribution: "FAA Aeronautical Information Services",
      // FAA only publishes levels 8-12; Leaflet stretches those tiles when
      // the map is zoomed outside that range instead of going blank.
      minNativeZoom: 8,
      maxNativeZoom: 12,
      maxZoom: 20,
    },
    // Default preview tile is z6, below this service's range; z8 over Denver.
    preview: { z: 8, x: 53, y: 97 },
  },
];

/** Falls back to the first style so stale saved ids can't blank the map. */
export const getMapStyle = (id) =>
  MAP_STYLES.find((style) => style.id === id) || MAP_STYLES[0];

/**
 * The switcher-thumbnail tile. Default is a fixed tile over the Colorado
 * Rockies — mountainous terrain makes the styles visually distinct at a
 * glance; per-style `preview` overrides it.
 */
export const previewTileUrl = (style) => {
  const { z, x, y } = style.preview || { z: 6, x: 13, y: 24 };
  return style.url.replace("{z}", z).replace("{x}", x).replace("{y}", y);
};
