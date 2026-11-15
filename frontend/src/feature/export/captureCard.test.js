import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { CARD_MAP, PIXEL_RATIO, captureCard } from "./captureCard";

vi.mock("html-to-image", () => ({ toCanvas: vi.fn() }));

const TARGET = [34.6, -84.4];

// The capture area the app first puts round a target (useExport's enableExportMode): about 663 by 555 m.
const boxAround = ([lat, lon]) => {
  const dLat = 0.0025;
  const dLng = (dLat * (663 / 555)) / Math.cos((lat * Math.PI) / 180);
  return [
    [lat - dLat, lon - dLng],
    [lat + dLat, lon + dLng],
  ];
};
const BOX = boxAround(TARGET);

describe("drawing the LZ card's picture", () => {
  let element;
  let map;
  let drawImage;

  // As index.css has it: every Leaflet container is held at the screen's size with !important (here a phone's map, 375 by
  // 366), which only an inline value that is important itself overrides. `takes` false is a page where nothing can.
  const makeSizedMap = ({ takes = true, zoom = 16.7 } = {}) => {
    element = document.createElement("div");
    element.setAttribute("style", "height: 100%; width: 100%;");
    const side = (name, screen) => () =>
      takes && element.style.getPropertyPriority(name) === "important" ? parseFloat(element.style.getPropertyValue(name)) : screen;
    Object.defineProperty(element, "clientWidth", { get: side("width", 375) });
    Object.defineProperty(element, "clientHeight", { get: side("height", 366) });
    document.body.appendChild(element);
    // MapView's own zoom settings: a tenth of a level, so the person's zoom is seldom a whole one.
    map = L.map(element, { zoomSnap: 0.1, zoomAnimation: false, fadeAnimation: false }).setView(TARGET, zoom);
    return element.getAttribute("style");
  };

  // Leaflet snaps a zoom to zoomSnap only where the browser has 3D transforms (every one the app runs in); jsdom has none.
  const any3d = L.Browser.any3d;
  beforeEach(() => {
    htmlToImage.toCanvas.mockReset();
    L.Browser.any3d = true;
    // jsdom draws nothing: the crop's canvas records what it is asked to draw and hands back a JPEG.
    drawImage = vi.fn();
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ drawImage });
    vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((done) => done(new Blob(["jpeg"], { type: "image/jpeg" })));
  });
  afterEach(() => {
    map.remove();
    element.remove();
    L.Browser.any3d = any3d;
    vi.restoreAllMocks();
  });

  it("draws the map at the card's size and the person's zoom, cuts the capture area out whole, and puts the map back", async () => {
    const style = makeSizedMap();
    const screenMiddle = map.getCenter();
    let drawn = null;
    htmlToImage.toCanvas.mockImplementation(async (node, options) => {
      drawn = {
        width: node.clientWidth,
        height: node.clientHeight,
        zoom: map.getZoom(),
        // Where the capture area's middle was on the map drawn.
        middle: map.latLngToContainerPoint(L.latLngBounds(BOX).getCenter()),
        uiHidden: node.classList.contains("hide-ui-for-export"),
        // Where the capture area was on the map drawn, in the image's pixels.
        area: [0, 1].map((i) => map.latLngToContainerPoint(BOX[i]).multiplyBy(options.pixelRatio)),
        options,
      };
      return { width: options.width * options.pixelRatio, height: options.height * options.pixelRatio };
    });
    const progress = [];

    const blob = await captureCard(map, BOX, { settleMs: 0, onProgress: (step) => progress.push(step) });

    // Before the fix the size lost to index.css and the map was drawn at the phone's 375 by 366.
    expect(drawn).toMatchObject({ width: CARD_MAP.width, height: CARD_MAP.height, zoom: 16.7, uiHidden: true });
    expect(drawn.options).toMatchObject({ width: CARD_MAP.width, height: CARD_MAP.height, pixelRatio: PIXEL_RATIO });
    // Centred on the capture area, to within the fraction of a pixel Leaflet rounds the map's origin to.
    expect(drawn.middle.distanceTo([CARD_MAP.width / 2, CARD_MAP.height / 2])).toBeLessThan(1);

    // The capture area, cut from inside what was drawn (on the phone it reached past it, and those parts came out black).
    expect(drawImage).toHaveBeenCalledTimes(1);
    const [source, sx, sy, sw, sh, dx, dy, dw, dh] = drawImage.mock.calls[0];
    const [corner, opposite] = drawn.area;
    expect(sx).toBeCloseTo(Math.min(corner.x, opposite.x), 6);
    expect(sy).toBeCloseTo(Math.min(corner.y, opposite.y), 6);
    expect(sw).toBeCloseTo(Math.abs(corner.x - opposite.x), 6);
    expect(sh).toBeCloseTo(Math.abs(corner.y - opposite.y), 6);
    expect(sx).toBeGreaterThanOrEqual(0);
    expect(sy).toBeGreaterThanOrEqual(0);
    expect(sx + sw).toBeLessThanOrEqual(source.width);
    expect(sy + sh).toBeLessThanOrEqual(source.height);
    expect([dx, dy, dw, dh]).toEqual([0, 0, sw, sh]);
    expect(blob).toBeInstanceOf(Blob);
    expect(progress).toEqual([30, 40]);

    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
    expect(map.getZoom()).toBe(16.7);
    expect(map.latLngToContainerPoint(screenMiddle).distanceTo([375 / 2, 366 / 2])).toBeLessThan(1);
  });

  it("refuses to draw a map that kept the screen's size, and puts it back as it was", async () => {
    const style = makeSizedMap({ takes: false });
    await expect(captureCard(map, BOX, { settleMs: 0 })).rejects.toThrow("the card's size");
    expect(htmlToImage.toCanvas).not.toHaveBeenCalled();
    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
    expect(map.getZoom()).toBe(16.7);
  });

  it("refuses a capture area larger than the card's map at the person's zoom, rather than draw part of it", async () => {
    // At 18 the first capture area is about 1,350 by 1,130 CSS pixels.
    const style = makeSizedMap({ zoom: 18 });
    await expect(captureCard(map, BOX, { settleMs: 0 })).rejects.toThrow("Zoom out");
    expect(htmlToImage.toCanvas).not.toHaveBeenCalled();
    expect(element.getAttribute("style")).toBe(style);
    expect(map.getZoom()).toBe(18);
  });
});
