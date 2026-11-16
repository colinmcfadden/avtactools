import L from "leaflet";
import * as htmlToImage from "html-to-image";
import { SCREEN_ONLY_CLASS } from "../export/captureFilter";
import { IMAGE_PANE, VECTOR_PANE } from "../overlays/KmzOverlayLayer";
import { captureOverlay, isScreenOnly, keepInOverlay, missionBounds, piecesWithContent } from "./captureOverlay";
import { areaAt, PIECE, PIECE_CSS, PIXEL_RATIO } from "./overlayPlan";

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

describe("the pieces with something in them", () => {
  const AREA = { north: 34.8, south: 34.5, east: -84.3, west: -84.6 };
  let map;
  beforeEach(() => {
    map = makeMap();
  });
  afterEach(() => map.remove());

  // The whole area at device zoom 17, about 2 m a CSS pixel: some 14 pieces across and 17 down.
  const plan = (() => {
    const area = areaAt(AREA, 17, "mbtiles");
    const columns = Math.ceil(area.width / PIECE);
    const rows = Math.ceil(area.height / PIECE);
    return { ...area, columns, rows, pieces: columns * rows };
  })();
  const pieceAt = (lat, lon) => {
    const at = map.project([lat, lon], plan.zoom);
    return `${Math.floor((at.x - plan.left) / PIECE)},${Math.floor((at.y - plan.top) / PIECE)}`;
  };

  it("is the pieces a line passes through, not the box round it", () => {
    L.tileLayer("https://tiles.invalid/{z}/{x}/{y}.png").addTo(map);
    L.polyline([[34.5, -84.5], [34.7, -84.3]]).addTo(map);
    const found = piecesWithContent(map, plan);
    expect(plan.pieces).toBeGreaterThan(150);
    expect(found.has(pieceAt(34.5, -84.5))).toBe(true);
    expect(found.has(pieceAt(34.7, -84.3))).toBe(true);
    expect(found.has(pieceAt(34.6, -84.4))).toBe(true);
    // The line's other corners are well away from it.
    expect(found.has(pieceAt(34.5, -84.3))).toBe(false);
    expect(found.has(pieceAt(34.7, -84.5))).toBe(false);
    expect(found.size).toBeLessThan(plan.pieces / 3);
  });

  it("is the pieces round a marker, the whole of a filled shape, and none for what the screen alone shows", () => {
    L.marker([34.65, -84.45]).addTo(map);
    L.rectangle([[34.52, -84.58], [34.54, -84.56]]).addTo(map);
    L.marker([34.55, -84.35], { icon: L.divIcon({ className: "packs-cursor" }) }).addTo(map);
    L.rectangle([[34.7, -84.35], [34.78, -84.31]], { screenOnly: true }).addTo(map);
    const found = piecesWithContent(map, plan);
    expect(found.has(pieceAt(34.65, -84.45))).toBe(true);
    expect(found.has(pieceAt(34.53, -84.57))).toBe(true);
    expect(found.has(pieceAt(34.55, -84.35))).toBe(false);
    expect(found.has(pieceAt(34.74, -84.33))).toBe(false);
    expect(found.size).toBeLessThan(20);
  });

  it("is every piece where a layer cannot say where it is", () => {
    const Mystery = L.Layer.extend({ onAdd() {}, onRemove() {} });
    new Mystery().addTo(map);
    expect(piecesWithContent(map, plan).size).toBe(plan.pieces);
  });

  it("is nothing where nothing is planned", () => {
    L.tileLayer("https://tiles.invalid/{z}/{x}/{y}.png").addTo(map);
    expect(piecesWithContent(map, plan).size).toBe(0);
  });
});

describe("drawing a mission overlay", () => {
  const AREA = { north: 34.8, south: 34.5, east: -84.3, west: -84.6 };
  let element;
  let map;
  let tiles;
  let lzCard;

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
    tiles = L.tileLayer("https://tiles.invalid/{z}/{x}/{y}.png").addTo(map);
    L.polyline([[34.5, -84.5], [34.7, -84.3]]).addTo(map);
    lzCard = L.rectangle([[34.55, -84.45], [34.65, -84.35]], { screenOnly: true, opacity: 0.8, fillOpacity: 0.2 }).addTo(map);
    return element.getAttribute("style");
  };

  // A plan of whole tiles at device zoom 14: two pieces across and three down.
  const planAt = (zoom) => {
    const area = areaAt(AREA, zoom, "mbtiles");
    const columns = Math.ceil(area.width / PIECE);
    const rows = Math.ceil(area.height / PIECE);
    return { ...area, format: "mbtiles", base: "none", columns, rows, pieces: columns * rows };
  };

  const drawnPiece = () => ({ width: PIECE, height: PIECE, toBlob: (done) => done(new Blob(["png"], { type: "image/png" })) });

  // Leaflet snaps a zoom to zoomSnap only where the browser has 3D transforms (every one the app runs in); jsdom has none.
  const any3d = L.Browser.any3d;
  const retina = L.Browser.retina;
  beforeEach(() => {
    htmlToImage.toCanvas.mockReset();
    L.Browser.any3d = true;
  });
  afterEach(() => {
    map.remove();
    L.Browser.any3d = any3d;
    L.Browser.retina = retina;
  });

  it("draws each piece with its corner on the plan's, the base map taken off, and puts the map back as it was", async () => {
    const style = makeSizedMap();
    const plan = planAt(14);
    expect(plan).toMatchObject({ columns: 2, rows: 3 });
    const drawn = [];
    htmlToImage.toCanvas.mockImplementation(async (node, options) => {
      drawn.push({
        size: [node.clientWidth, node.clientHeight],
        zoom: map.getZoom(),
        corner: map.containerPointToLayerPoint([0, 0]).add(map.getPixelOrigin()),
        tiles: map.hasLayer(tiles),
        retina: L.Browser.retina,
        lzCard: lzCard.options.opacity,
        options,
      });
      return drawnPiece();
    });
    const told = [];

    const pieces = await captureOverlay(map, plan, { firstSettleMs: 0, settleMs: 0, onPiece: (done, total) => told.push([done, total]) });

    expect(pieces.map(({ column, row }) => [column, row])).toEqual([[0, 0], [1, 0], [0, 1], [1, 1], [0, 2], [1, 2]]);
    pieces.forEach((piece) => expect(piece.blob).toBeInstanceOf(Blob));
    drawn.forEach((at, index) => {
      const { column, row } = pieces[index];
      expect(at.size).toEqual([PIECE_CSS, PIECE_CSS]);
      expect(at.zoom).toBeCloseTo(13, 9);
      expect(at.corner.x).toBeCloseTo((plan.left + column * PIECE) / PIXEL_RATIO, 6);
      expect(at.corner.y).toBeCloseTo((plan.top + row * PIECE) / PIXEL_RATIO, 6);
      expect(at.tiles).toBe(false);
      expect(at.retina).toBe(true);
      expect(at.lzCard).toBe(0);
      expect(at.options).toMatchObject({ pixelRatio: PIXEL_RATIO, width: PIECE_CSS, height: PIECE_CSS, filter: keepInOverlay });
    });
    expect(told).toEqual([[0, 6], [1, 6], [2, 6], [3, 6], [4, 6], [5, 6], [6, 6]]);

    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
    expect(map.getZoom()).toBe(12);
    expect(map.getCenter().lat).toBeCloseTo(34.6, 6);
    expect(map.getCenter().lng).toBeCloseTo(-84.4, 6);
    expect(map.hasLayer(tiles)).toBe(true);
    expect(lzCard.options).toMatchObject({ opacity: 0.8, fillOpacity: 0.2 });
    expect(L.Browser.retina).toBe(retina);
  });

  it("refuses to draw a map that kept the screen's size, and puts it back as it was", async () => {
    // Set without !important, the size loses to index.css and the piece would be drawn somewhere else than it belongs.
    const style = makeSizedMap({ takes: false });
    await expect(captureOverlay(map, planAt(14), { firstSettleMs: 0, settleMs: 0 })).rejects.toThrow("could not be set to a piece");
    expect(htmlToImage.toCanvas).not.toHaveBeenCalled();
    expect(element.getAttribute("style")).toBe(style);
    expect(element.classList.contains("hide-ui-for-export")).toBe(false);
    expect(map.hasLayer(tiles)).toBe(true);
    expect(map.getZoom()).toBe(12);
  });

  it("draws only the pieces asked for, hands each on as it is drawn, and stops when asked", async () => {
    const style = makeSizedMap();
    const plan = planAt(14);
    htmlToImage.toCanvas.mockImplementation(async () => drawnPiece());
    const handed = [];
    const kept = await captureOverlay(map, plan, {
      firstSettleMs: 0,
      settleMs: 0,
      pieces: new Set(["1,2", "0,1"]),
      onDrawn: async ({ column, row, blob }) => handed.push([column, row, blob instanceof Blob]),
    });
    expect(kept).toEqual([]);
    expect(handed).toEqual([[0, 1, true], [1, 2, true]]);

    const controller = new AbortController();
    const stopped = captureOverlay(map, plan, {
      firstSettleMs: 0,
      settleMs: 0,
      signal: controller.signal,
      onDrawn: async () => controller.abort(),
    });
    await expect(stopped).rejects.toMatchObject({ name: "AbortError" });
    expect(htmlToImage.toCanvas).toHaveBeenCalledTimes(3);
    expect(element.getAttribute("style")).toBe(style);
    expect(map.hasLayer(tiles)).toBe(true);
    expect(map.getZoom()).toBe(12);
  });

  it("refuses a piece drawn at the wrong size", async () => {
    makeSizedMap();
    htmlToImage.toCanvas.mockResolvedValue({ width: PIECE_CSS, height: PIECE_CSS, toBlob: (done) => done(new Blob(["png"])) });
    await expect(captureOverlay(map, planAt(14), { firstSettleMs: 0, settleMs: 0 })).rejects.toThrow("wrong size");
    expect(map.hasLayer(tiles)).toBe(true);
  });
});
