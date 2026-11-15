import L from "leaflet";
import {
  areaAt,
  describeArea,
  describePixels,
  describeResolution,
  describeScale,
  GEOTIFF_SIDE,
  layerName,
  MARGIN,
  MAX_ZOOM,
  MBTILES_TILES,
  overlayFileName,
  overlayPlans,
  PIECE,
  PIXEL_RATIO,
  project0,
  TILE,
  USGS_MAX_ZOOM,
} from "./overlayPlan";

// The Ellijay test LZ's country: about 18 km by 22 km.
const AREA = { north: 34.75, south: 34.55, east: -84.35, west: -84.55 };
// An LZ: a hundred metres or so.
const LZ = { north: 34.6005, south: 34.5995, east: -84.4, west: -84.401 };

const tiles = (area) => (area.width / TILE) * (area.height / TILE);

describe("the overlay's arithmetic", () => {
  it("projects as Leaflet's map does", () => {
    for (const [lat, lon] of [[34.6, -84.4], [0, 0], [-33.9, 151.2], [84.9, 179.9]]) {
      const ours = project0(lat, lon);
      const leaflet = L.CRS.EPSG3857.latLngToPoint(L.latLng(lat, lon), 0);
      expect(ours.x).toBeCloseTo(leaflet.x, 9);
      expect(ours.y).toBeCloseTo(leaflet.y, 9);
    }
  });

  it("lays an area and its margin on whole tiles for MBTiles, on even pixels for a GeoTIFF", () => {
    for (const [format, step, zoom] of [["mbtiles", TILE, 15], ["geotiff", 2, 15.3]]) {
      const area = areaAt(AREA, zoom, format);
      for (const value of [area.left, area.top, area.width, area.height]) expect(value % step).toBe(0);
      const nw = project0(AREA.north, AREA.west);
      const se = project0(AREA.south, AREA.east);
      const scale = 2 ** zoom;
      const margin = MARGIN * PIXEL_RATIO;
      expect(area.left).toBeLessThanOrEqual(nw.x * scale - margin);
      expect(area.top).toBeLessThanOrEqual(nw.y * scale - margin);
      expect(area.left + area.width).toBeGreaterThanOrEqual(se.x * scale + margin);
      expect(area.top + area.height).toBeGreaterThanOrEqual(se.y * scale + margin);
      // No more than a step over.
      expect(area.left).toBeGreaterThan(nw.x * scale - margin - step);
    }
    // Never past the top or bottom of the world.
    const pole = areaAt({ north: 85, south: 84.9, east: 10, west: 0 }, 4, "geotiff");
    expect(pole.top).toBe(0);
  });

  it("draws a GeoTIFF as fine as its 10,000 pixels a side hold, on a tenth of a zoom", () => {
    const { finest, lighter, limitedBy } = overlayPlans(AREA, { format: "geotiff" });
    expect(limitedBy).toBe("size");
    expect(Math.max(finest.width, finest.height)).toBeLessThanOrEqual(GEOTIFF_SIDE);
    const finer = areaAt(AREA, Math.round((finest.zoom + 0.1) * 10) / 10, "geotiff");
    expect(Math.max(finer.width, finer.height)).toBeGreaterThan(GEOTIFF_SIDE);
    expect(Math.round(finest.zoom * 10)).toBeCloseTo(finest.zoom * 10, 9);
    // 22 km into about 10,000 pixels: near 2 m a pixel, and a level lighter is twice that.
    expect(finest.metresPerPixel).toBeGreaterThan(1.5);
    expect(finest.metresPerPixel).toBeLessThan(2.5);
    expect(lighter.zoom).toBeCloseTo(finest.zoom - 1, 9);
    expect(lighter.metresPerPixel).toBeCloseTo(finest.metresPerPixel * 2, 6);
    expect(finest).toMatchObject({ columns: Math.ceil(finest.width / PIECE), rows: Math.ceil(finest.height / PIECE) });
    expect(finest.pieces).toBe(finest.columns * finest.rows);
  });

  it("draws MBTiles at the finest whole zoom whose tiles the server takes", () => {
    const { finest, limitedBy } = overlayPlans(AREA, { format: "mbtiles" });
    expect(limitedBy).toBe("size");
    expect(Number.isInteger(finest.zoom)).toBe(true);
    expect(tiles(finest)).toBeLessThanOrEqual(MBTILES_TILES);
    expect(tiles(areaAt(AREA, finest.zoom + 1, "mbtiles"))).toBeGreaterThan(MBTILES_TILES);
    // A long, thin area spends its tiles along its length: finer than a square of its length would be.
    const corridor = { north: 34.601, south: 34.6, east: -84.0, west: -84.6 };
    const long = overlayPlans(corridor, { format: "mbtiles" }).finest;
    expect(tiles(long)).toBeLessThanOrEqual(MBTILES_TILES);
    expect(long.width / TILE).toBeGreaterThan(Math.sqrt(MBTILES_TILES));
  });

  it("goes no finer than the USGS map's own tiles under it, and says so", () => {
    for (const format of ["mbtiles", "geotiff"]) {
      const topo = overlayPlans(LZ, { format, base: "usgs-topo" });
      expect(topo.finest.zoom).toBe(USGS_MAX_ZOOM);
      expect(topo.limitedBy).toBe("base");
      const none = overlayPlans(LZ, { format, base: "none" });
      expect(none.finest.zoom).toBe(MAX_ZOOM);
      expect(none.limitedBy).toBe("zoom");
    }
    // A large area is held back by the file before the map; one both hold at level 16 is the map's.
    expect(overlayPlans(AREA, { format: "geotiff", base: "usgs-topo" }).limitedBy).toBe("size");
    const both = { north: 34.66, south: 34.58, east: -84.36, west: -84.48 };
    expect(overlayPlans(both, { format: "mbtiles", base: "none" }).finest.zoom).toBe(USGS_MAX_ZOOM);
    expect(overlayPlans(both, { format: "mbtiles", base: "usgs-topo" }).limitedBy).toBe("base");
    // Below the USGS map's most, what holds it is the map on the screen.
    expect(overlayPlans(LZ, { format: "mbtiles", base: "usgs-topo", mapMaxZoom: 12 }).limitedBy).toBe("zoom");
  });

  it("goes no finer than the map does", () => {
    // The map's own most is a CSS zoom: a device zoom one more.
    expect(overlayPlans(LZ, { format: "geotiff", mapMaxZoom: 12 }).finest.zoom).toBe(13);
    expect(overlayPlans(LZ, { format: "mbtiles", mapMaxZoom: 12 }).finest.zoom).toBe(13);
  });

  it("says what a pixel comes to as a chart's scale, and how large the area is", () => {
    expect(describeScale(2)).toBe("1:20,000");
    expect(describeScale(1.96)).toBe("1:20,000");
    expect(describeScale(4.1)).toBe("1:41,000");
    expect(describeScale(0.24)).toBe("1:2,400");
    expect(describeScale(39)).toBe("1:390,000");
    expect(describeArea(AREA)).toBe("18 × 22 km");
    expect(describeResolution(0.62)).toBe("about 0.6 m a pixel");
    expect(describeResolution(12.4)).toBe("about 12 m a pixel");
    expect(describePixels({ width: 9800, height: 7410 })).toBe("9,800 × 7,410");
  });

  it("names the layer as the server does", () => {
    expect(layerName("  ../../etc/passwd  ")).toBe("etc passwd");
    expect(layerName("")).toBe("MISSION OVERLAY");
    expect(layerName("A".repeat(90))).toBe("A".repeat(60));
    expect(layerName("OP DK (2) — night")).toBe("OP DK (2) night");
    expect(layerName("LZ ÉCLAIR")).toBe("LZ ÉCLAIR");
    expect(overlayFileName("OP DK / night", "geotiff")).toBe("OP DK night.tif");
    expect(overlayFileName("OP DK", "mbtiles")).toBe("OP DK.mbtiles");
  });
});
