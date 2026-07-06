const MAPBOX_TOKEN =
  "pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw";

const MAPBOX_ATTRIBUTION =
  '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>';

const mapboxTiles = (styleId) =>
  `https://api.mapbox.com/styles/v1/mapbox/${styleId}/tiles/512/{z}/{x}/{y}@2x?access_token=${MAPBOX_TOKEN}`;

/**
 * Registry of available base maps. To add a map, append an entry here — the
 * switcher control and the map's TileLayer both render from this list. Any
 * tile server works as long as `url` is a Leaflet {z}/{x}/{y} template; the
 * tileSize/zoomOffset pair below is specific to Mapbox's 512px tiles (use
 * 256 / 0 for standard OSM-style servers).
 */
export const MAP_STYLES = [
  {
    id: "satellite",
    label: "Satellite",
    url: mapboxTiles("satellite-v9"),
    attribution: MAPBOX_ATTRIBUTION,
    tileSize: 512,
    zoomOffset: -1,
    maxZoom: 20,
  },
  {
    id: "topo",
    label: "Topo",
    url: mapboxTiles("outdoors-v12"),
    attribution: MAPBOX_ATTRIBUTION,
    tileSize: 512,
    zoomOffset: -1,
    maxZoom: 20,
  },
];

/** Falls back to the first style so stale saved ids can't blank the map. */
export const getMapStyle = (id) =>
  MAP_STYLES.find((style) => style.id === id) || MAP_STYLES[0];

/**
 * A fixed tile over the Colorado Rockies used as the switcher thumbnail —
 * mountainous terrain makes the styles visually distinct at a glance.
 */
export const previewTileUrl = (style) =>
  style.url.replace("{z}", "6").replace("{x}", "13").replace("{y}", "24");
