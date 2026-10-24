import { render } from "@testing-library/react";
import fs from "fs";
import L from "leaflet";
import path from "path";
import React from "react";
import SlopeLayer, {
  CONTOUR_LABEL_PANE,
  CONTOUR_LABEL_Z_INDEX,
  CONTOUR_PANE,
  CONTOUR_Z_INDEX,
  SLOPE_PANE,
  SLOPE_Z_INDEX,
  attachSlope,
} from "./SlopeLayer";
import { GRID_Z_INDEX, LABEL_Z_INDEX } from "../mgrsGrid/MgrsGridLayer";

// react-leaflet is an ES module Jest does not transform: useMap hands the layer a real Leaflet map.
jest.mock("react-leaflet", () => {
  const holder = { map: null };
  return { __holder: holder, useMap: () => holder.map };
});
const { __holder: holder } = jest.requireMock("react-leaflet");

let container;
let map;
beforeEach(() => {
  container = document.createElement("div");
  Object.defineProperty(container, "clientWidth", { configurable: true, value: 1200 });
  Object.defineProperty(container, "clientHeight", { configurable: true, value: 800 });
  document.body.appendChild(container);
  map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView([34.78, -84.08], 16);
  holder.map = map;
});
afterEach(() => {
  map.remove();
  container.remove();
});

const PNG = "data:image/png;base64,AA==";
const line = (elevationFt, { inside = true, major = false, label = null } = {}) => ({
  elevationFt, major, inside, label,
  points: [[34.779, -84.081], [34.781, -84.081]],
});
const ANALYSIS = {
  overlay: PNG,
  bounds: [[34.77, -84.09], [34.79, -84.07]],
  contours: {
    intervalFt: 5,
    lines: [
      line(1520, { inside: false }),
      line(1520, { label: [34.78, -84.081] }),
      line(1525, { major: true, label: [34.78, -84.0805] }),
      line(1525, { inside: false, major: true }),
    ],
  },
};

const paths = (pane) => [...(map.getPane(pane)?.querySelectorAll("path") || [])];
const classes = (pane, part) => paths(pane).map((p) => p.getAttribute("class")).filter((c) => c.includes(`slope-contour__${part} `));
const labels = () => [...(map.getPane(CONTOUR_LABEL_PANE)?.querySelectorAll(".slope-contour-label") || [])].map((l) => l.textContent);
const images = () => [...(map.getPane(SLOPE_PANE)?.querySelectorAll("img") || [])];

const leafletZ = (selector) => {
  const css = fs.readFileSync(path.join(require.resolve("leaflet"), "..", "leaflet.css"), "utf8");
  return Number(css.match(new RegExp(`\\.${selector}\\s*\\{[^}]*z-index:\\s*(\\d+)`))[1]);
};

describe("the slope map", () => {
  it("lies above the MGRS grid and below everything planned on the map, contours over the slope colours", () => {
    attachSlope(map, ANALYSIS);
    expect(GRID_Z_INDEX).toBeLessThan(SLOPE_Z_INDEX);
    expect(LABEL_Z_INDEX).toBeLessThan(SLOPE_Z_INDEX);
    expect(SLOPE_Z_INDEX).toBeLessThan(CONTOUR_Z_INDEX);
    expect(CONTOUR_Z_INDEX).toBeLessThan(CONTOUR_LABEL_Z_INDEX);
    expect(CONTOUR_LABEL_Z_INDEX).toBeLessThan(leafletZ("leaflet-overlay-pane"));      // the LZ boundary and routes are over it
    [SLOPE_PANE, CONTOUR_PANE, CONTOUR_LABEL_PANE].forEach((name) => {
      expect(map.getPane(name).style.pointerEvents).toBe("none");
      expect(map.getPane(name).getAttribute("aria-hidden")).toBe("true");
    });
  });

  it("draws the raster and every contour piece, a halo under each line, the ring outside faded and every fifth heavier", () => {
    attachSlope(map, ANALYSIS);
    expect(images().map((img) => img.getAttribute("src"))).toEqual([PNG]);
    const halos = classes(CONTOUR_PANE, "halo");
    const lines = classes(CONTOUR_PANE, "line");
    expect(halos).toHaveLength(4);
    expect(lines).toHaveLength(4);
    // Every halo is drawn before every line, so none covers one.
    const order = paths(CONTOUR_PANE).map((p) => (p.getAttribute("class").includes("slope-contour__halo ") ? "halo" : "line"));
    expect(order).toEqual(["halo", "halo", "halo", "halo", "line", "line", "line", "line"]);
    expect(lines.filter((c) => c.includes("--outside"))).toHaveLength(2);
    expect(lines.filter((c) => c.includes("--major"))).toHaveLength(2);
  });

  it("labels each height once, where the server says", () => {
    attachSlope(map, ANALYSIS);
    expect(labels()).toEqual(["1520", "1525"]);
  });

  it("draws an older server's analysis, with no contours, as the raster alone", () => {
    attachSlope(map, { overlay: PNG, bounds: ANALYSIS.bounds });
    expect(images()).toHaveLength(1);
    expect(paths(CONTOUR_PANE)).toHaveLength(0);
    expect(labels()).toEqual([]);
  });

  it("passes over a piece it cannot draw", () => {
    attachSlope(map, { ...ANALYSIS, contours: { intervalFt: 5, lines: [null, { elevationFt: 1, points: [[34.78, -84.08]] }, line(1530)] } });
    expect(classes(CONTOUR_PANE, "line")).toHaveLength(1);
  });

  it("draws the raster at 0.55 when nobody says otherwise", () => {
    attachSlope(map, ANALYSIS);
    expect(images()[0].style.opacity).toBe("0.55");
  });

  it("draws the raster at its opacity, and sets a new one on the raster drawn, with nothing drawn again", () => {
    const { rerender } = render(<SlopeLayer terrainData={ANALYSIS} shown opacity={0.3} />);
    const [img] = images();
    const contours = paths(CONTOUR_PANE);
    expect(img.style.opacity).toBe("0.3");
    rerender(<SlopeLayer terrainData={ANALYSIS} shown opacity={0.8} />);
    expect(images()).toHaveLength(1);
    expect(images()[0]).toBe(img);
    expect(img.style.opacity).toBe("0.8");
    expect(paths(CONTOUR_PANE)).toHaveLength(contours.length);
    paths(CONTOUR_PANE).forEach((p, i) => expect(p).toBe(contours[i]));               // the very same lines: the contours keep theirs
  });

  it("draws a slope shown again, or for a new analysis, at the opacity it has now", () => {
    const { rerender } = render(<SlopeLayer terrainData={ANALYSIS} shown={false} opacity={0.3} />);
    expect(images()).toHaveLength(0);
    rerender(<SlopeLayer terrainData={ANALYSIS} shown opacity={0.9} />);
    expect(images()[0].style.opacity).toBe("0.9");
    rerender(<SlopeLayer terrainData={{ ...ANALYSIS }} shown opacity={0.2} />);
    expect(images()).toHaveLength(1);
    expect(images()[0].style.opacity).toBe("0.2");
  });

  it("is taken away when hidden, and drawn for the analysis it has now", () => {
    const { rerender } = render(<SlopeLayer terrainData={ANALYSIS} shown />);
    expect(classes(CONTOUR_PANE, "line")).toHaveLength(4);
    rerender(<SlopeLayer terrainData={ANALYSIS} shown={false} />);
    expect(paths(CONTOUR_PANE)).toHaveLength(0);
    expect(images()).toHaveLength(0);
    expect(labels()).toEqual([]);
    rerender(<SlopeLayer terrainData={{ ...ANALYSIS, contours: { intervalFt: 5, lines: [line(1600, { label: [34.78, -84.08] })] } }} shown />);
    expect(classes(CONTOUR_PANE, "line")).toHaveLength(1);
    expect(labels()).toEqual(["1600"]);
  });
});
