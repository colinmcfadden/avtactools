import { render } from "@testing-library/react";
import React from "react";
import VfrChartLayers from "./VfrChartLayers";

const layers = vi.hoisted(() => []);

vi.mock("react-leaflet", async (importOriginal) => ({
  ...(await importOriginal()),
  TileLayer: (props) => {
    layers.push(props);
    return null;
  },
}));

const ARCGIS = /tiles\.arcgis\.com/;
const LIVE = {
  build: "2026-10-29.1",
  tiles: "https://api.test/charts/vfr/2026-10-29.1/{z}/{x}/{y}.webp",
  tileSize: 512,
  minZoom: 5,
  maxZoom: 14,
  bounds: [-125, 24, -66, 50],
  fallback: [[-158.9, 18.6, -154.7, 22.6], [-180, 51, -128, 72]],
};

const drawn = (charts) => {
  layers.length = 0;
  render(<VfrChartLayers charts={{ known: true, coverage: null, tileFailed: () => {}, ...charts }} />);
  return [...layers];
};

describe("the VFR map's layers", () => {
  it("are none until the server has said, so ArcGIS is not asked for CONUS on the way to the charts", () => {
    expect(drawn({ known: false, live: null })).toEqual([]);
  });

  it("with no live build are today's service, everywhere", () => {
    const [layer, ...rest] = drawn({ live: null });
    expect(rest).toEqual([]);
    expect(layer.url).toMatch(ARCGIS);
    expect(layer.bounds).toBeUndefined();
    expect(layer.maxNativeZoom).toBe(12);
    expect(layer.crossOrigin).toBe("anonymous");
  });

  it("with a live build are the charts within its bounds, and ArcGIS only in its own rectangles", () => {
    const found = drawn({ live: LIVE });
    expect(found).toHaveLength(3);
    const [alaska, hawaii, chart] = [found[1], found[0], found[2]];
    expect(hawaii.url).toMatch(ARCGIS);
    expect(hawaii.bounds).toEqual([[18.6, -158.9], [22.6, -154.7]]);
    expect(alaska.url).toMatch(ARCGIS);
    expect(alaska.bounds).toEqual([[51, -180], [72, -128]]);
    expect(chart.url).toBe(LIVE.tiles);
    expect(chart).toMatchObject({ tileSize: 512, zoomOffset: -1, minNativeZoom: 6, maxNativeZoom: 15, maxZoom: 20, bounds: [[24, -125], [50, -66]] });
  });

  it("draws the charts over ArcGIS's", () => {
    const found = drawn({ live: LIVE });
    expect(found[2].zIndex).toBeGreaterThan(found[0].zIndex);
  });

  it("fetches every tile for the LZ card's canvas, the charts' and ArcGIS's alike", () => {
    drawn({ live: LIVE }).forEach((layer) => expect(layer.crossOrigin).toBe("anonymous"));
  });

  it("asks for the config again only for a chart tile that failed", () => {
    const tileFailed = vi.fn();
    const found = drawn({ live: LIVE, tileFailed });
    expect(found[2].eventHandlers.tileerror).toBe(tileFailed);
    expect(found[0].eventHandlers).toBeUndefined();
    expect(found[1].eventHandlers).toBeUndefined();
    expect(drawn({ live: null, tileFailed })[0].eventHandlers).toBeUndefined();
  });

  it("is made anew for a new build, whose bounds and levels a layer cannot take on later", () => {
    const { rerender } = render(<VfrChartLayers charts={{ known: true, live: LIVE, tileFailed: () => {} }} />);
    layers.length = 0;
    const next = { ...LIVE, build: "2026-12-24.1", tiles: "https://api.test/charts/vfr/2026-12-24.1/{z}/{x}/{y}.webp" };
    rerender(<VfrChartLayers charts={{ known: true, live: next, tileFailed: () => {} }} />);
    expect(layers.map((layer) => layer.url)).toContain(next.tiles);
  });
});
