import L from "leaflet";
import {
  capturePlan,
  describeArea,
  describePixels,
  describeResolution,
  layerName,
  MARGIN,
  MAX_CAPTURE_ZOOM,
  overlayFileName,
  PIXEL_RATIO,
  project0,
} from "./overlayPlan";

// The Ellijay test LZ's country: about 18 km by 22 km.
const AREA = { north: 34.75, south: 34.55, east: -84.35, west: -84.55 };

describe("the overlay's arithmetic", () => {
  it("projects as Leaflet's map does", () => {
    for (const [lat, lon] of [[34.6, -84.4], [0, 0], [-33.9, 151.2], [84.9, 179.9]]) {
      const ours = project0(lat, lon);
      const leaflet = L.CRS.EPSG3857.latLngToPoint(L.latLng(lat, lon), 0);
      expect(ours.x).toBeCloseTo(leaflet.x, 9);
      expect(ours.y).toBeCloseTo(leaflet.y, 9);
    }
  });

  it("draws an area as large as the detail allows, its longest side filling it with the margins", () => {
    for (const [detail, px] of [["standard", 3200], ["high", 6400]]) {
      const plan = capturePlan(AREA, detail);
      expect(Math.max(plan.width, plan.height)).toBeLessThanOrEqual(px + PIXEL_RATIO);
      // A tenth of a zoom more would not fit: the zoom is the largest on the map's snap.
      const scale = 2 ** (plan.zoom + 0.1);
      const nw = project0(AREA.north, AREA.west);
      const se = project0(AREA.south, AREA.east);
      expect(Math.max(se.x - nw.x, se.y - nw.y) * scale + 2 * MARGIN).toBeGreaterThan(px / PIXEL_RATIO);
      expect(Number.isInteger(Math.round(plan.zoom * 10))).toBe(true);
      expect(plan.width).toBe(plan.cssWidth * PIXEL_RATIO);
    }
    const standard = capturePlan(AREA, "standard");
    const high = capturePlan(AREA, "high");
    // Twice the pixels, less the same margins: a level deeper, or a tenth more.
    expect(high.zoom - standard.zoom).toBeGreaterThanOrEqual(1 - 1e-9);
    expect(high.zoom - standard.zoom).toBeLessThanOrEqual(1.1 + 1e-9);
    expect(high.metresPerPixel).toBeLessThanOrEqual(standard.metresPerPixel / 2 + 1e-9);
  });

  it("a small area stops at the deepest zoom worth drawing, and a point is its margins alone", () => {
    const lz = { north: 34.6005, south: 34.5995, east: -84.4, west: -84.401 };
    expect(capturePlan(lz, "high").zoom).toBe(MAX_CAPTURE_ZOOM);
    expect(capturePlan(lz, "standard").zoom).toBe(MAX_CAPTURE_ZOOM);
    const point = capturePlan({ north: 34.6, south: 34.6, east: -84.4, west: -84.4 }, "standard");
    expect(point).toMatchObject({ zoom: MAX_CAPTURE_ZOOM, cssWidth: 2 * MARGIN, cssHeight: 2 * MARGIN });
    // A base map that goes no deeper keeps the zoom to it.
    expect(capturePlan(lz, "standard", 12).zoom).toBe(12);
  });

  it("says what a pixel covers on the ground, and how large the area is", () => {
    const plan = capturePlan(AREA, "standard");
    // 18 km across about 3,100 pixels: near 6 m a pixel.
    expect(plan.metresPerPixel).toBeGreaterThan(5);
    expect(plan.metresPerPixel).toBeLessThan(8);
    expect(describeArea(AREA)).toBe("18 × 22 km");
    expect(describeResolution(0.62)).toBe("about 0.6 m a pixel");
    expect(describeResolution(12.4)).toBe("about 12 m a pixel");
    expect(describePixels({ width: 3200, height: 2410 })).toBe("3,200 × 2,410");
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
