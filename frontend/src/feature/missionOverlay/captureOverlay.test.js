import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { SCREEN_ONLY_CLASS } from "../export/captureFilter";
import { IMAGE_PANE, VECTOR_PANE } from "../overlays/KmzOverlayLayer";
import { captureOverlay, isScreenOnly, keepInOverlay, missionBounds } from "./captureOverlay";
import { capturePlan } from "./overlayPlan";

vi.mock("html-to-image", () => ({ toCanvas: vi.fn() }));

const makeMap = () => {
  const element = document.createElement("div");
  Object.defineProperty(element, "clientWidth", { value: 800 });
  Object.defineProperty(element, "clientHeight", { value: 600 });
  document.body.appendChild(element);
  const map = L.map(element, { preferCanvas: false }).setView([34.6, -84.4], 12);
  return map;
};

const node = (...classes) => {
  const element = document.createElement("div");
  element.classList.add(...classes);
  return element;
};

describe("what goes in a mission overlay", () => {
  let map;
  beforeEach(() => {
    map = makeMap();
  });
  afterEach(() => map.remove());

  it("spans what is planned, leaving out the base map, handles, the grid and other people's pointers", () => {
    L.tileLayer("https://tiles.invalid/{z}/{x}/{y}.png").addTo(map);
    L.polyline([[34.5, -84.5], [34.7, -84.3]]).addTo(map);
    L.marker([34.8, -84.6]).addTo(map);
    // The screen's alone: far away, so a mistake would show in the bounds.
    L.marker([40, -100], { icon: L.divIcon({ className: "packs-cursor" }) }).addTo(map);
    L.marker([40, -100], { icon: L.divIcon({ className: `map-object-handle-icon ${SCREEN_ONLY_CLASS}` }) }).addTo(map);
    L.rectangle([[30, -90], [31, -89]], { screenOnly: true }).addTo(map);
    const grid = map.createPane("gridPane");
    grid.classList.add(SCREEN_ONLY_CLASS);
    L.polyline([[0, 0], [1, 1]], { pane: "gridPane" }).addTo(map);
    L.popup().setLatLng([10, 10]).setContent("x").openOn(map);

    expect(missionBounds(map)).toEqual({ north: 34.8, south: 34.5, east: -84.3, west: -84.6 });
  });

  it("counts an imported overlay only when nothing is planned", () => {
    map.createPane(IMAGE_PANE);
    map.createPane(VECTOR_PANE);
    L.polyline([[20, -110], [45, -70]], { pane: VECTOR_PANE }).addTo(map);
    expect(missionBounds(map)).toEqual({ north: 45, south: 20, east: -70, west: -110 });
    L.marker([34.6, -84.4]).addTo(map);
    expect(missionBounds(map)).toEqual({ north: 34.6, south: 34.6, east: -84.4, west: -84.4 });
  });

  it("is nothing when nothing is drawn", () => {
    L.tileLayer("https://tiles.invalid/{z}/{x}/{y}.png").addTo(map);
    expect(missionBounds(map)).toBeNull();
  });

  it("tells a screen-only layer apart", () => {
    expect(isScreenOnly(map, L.tileLayer("x"))).toBe(true);
    expect(isScreenOnly(map, L.rectangle([[0, 0], [1, 1]], { screenOnly: true }))).toBe(true);
    expect(isScreenOnly(map, L.marker([0, 0]))).toBe(false);
  });

  it("leaves the base map, popups, pointers and the LZ card's handles out of the image", () => {
    for (const name of ["leaflet-tile-pane", "leaflet-popup-pane", "packs-cursor", "move-handle", "resize-handle-se", SCREEN_ONLY_CLASS, "leaflet-control-container"]) {
      expect(keepInOverlay(node(name))).toBe(false);
    }
    for (const name of ["leaflet-overlay-pane", "leaflet-marker-pane", "leaflet-tooltip-pane", "map-slope"]) {
      expect(keepInOverlay(node(name))).toBe(true);
    }
  });
});

describe("drawing a mission overlay", () => {
  const AREA = { north: 34.8, south: 34.5, east: -84.3, west: -84.6 };
  let element;
  let map;

  // As index.css has it: every Leaflet container is held at the screen's size with !important, which only an inline
  // value that is important itself overrides. `takes` false is a page where nothing can.
  const makeSizedMap = ({ takes = true } = {}) => {
    element = document.createElement("div");
    element.setAttribute("style", "height: 100%;");
    const side = (name, screen) => () =>
      takes && element.style.getPropertyPriority(name) === "important" ? parseFloat(element.style.getPropertyValue(name)) : screen;
    Object.defineProperty(element, "clientWidth", { get: side("width", 800) });
    Object.defineProperty(element, "clientHeight", { get: side("height", 600) });
    document.body.appendChild(element);
    map = L.map(element, { zoomSnap: 0.1 }).setView([34.6, -84.4], 12);
    L.polyline([[34.5, -84.5], [34.7, -84.3]]).addTo(map);
    return element.getAttribute("style");
  };

  // Leaflet snaps a zoom to zoomSnap only where the browser has 3D transforms (every one the app runs in); jsdom has none.
  const any3d = L.Browser.any3d;
  beforeEach(() => {
    htmlToImage.toCanvas.mockReset();
    L.Browser.any3d = true;
  });
  afterEach(() => {
    map.remove();
    L.Browser.any3d = any3d;
  });

  it("draws the map at the plan's size and zoom, and puts it back as it was", async () => {
    const style = makeSizedMap();
    const plan = capturePlan(AREA, "standard", map.getMaxZoom());
    let drawnAt = null;
    htmlToImage.toCanvas.mockImplementation(async (node, options) => {
      drawnAt = { width: node.clientWidth, height: node.clientHeight, zoom: map.getZoom(), options };
      return { width: options.width * 2, height: options.height * 2, toBlob: (done) => done(new Blob(["png"])) };
    });

    const shot = await captureOverlay(map, AREA, "standard", { settleMs: 0 });

    expect(drawnAt).toMatchObject({ width: plan.cssWidth, height: plan.cssHeight, zoom: plan.zoom });
    expect(drawnAt.options).toMatchObject({ width: plan.cssWidth, height: plan.cssHeight, pixelRatio: 2 });
    expect(shot).toMatchObject({ width: plan.width, height: plan.height });
    // The corners drawn hold the whole area, its margin round it.
    expect(shot.bounds.north).toBeGreaterThan(AREA.north);
    expect(shot.bounds.south).toBeLessThan(AREA.south);
    expect(shot.bounds.east).toBeGreaterThan(AREA.east);
    expect(shot.bounds.west).toBeLessThan(AREA.west);

    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
    expect(map.getZoom()).toBe(12);
    expect(L.Browser.retina).toBe(false);
  });

  it("refuses to draw a map that kept the screen's size, and puts it back as it was", async () => {
    // Before the fix the size was set without !important and lost to index.css: only the area's middle was drawn.
    const style = makeSizedMap({ takes: false });
    await expect(captureOverlay(map, AREA, "standard", { settleMs: 0 })).rejects.toThrow("overlay's size");
    expect(htmlToImage.toCanvas).not.toHaveBeenCalled();
    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
  });
});
