import { act, render } from "@testing-library/react";
import fs from "fs";
import L from "leaflet";
import path from "path";
import React from "react";
import { SCREEN_ONLY_CLASS } from "../export/captureFilter";
import MgrsGridLayer, {
  GRID_PANE,
  GRID_Z_INDEX,
  LABEL_PANE,
  LABEL_Z_INDEX,
  attachMgrsGrid,
} from "./MgrsGridLayer";

// react-leaflet is an ES module Jest does not transform: useMap hands the layer a real Leaflet map.
jest.mock("react-leaflet", () => {
  const holder = { map: null };
  return { __holder: holder, useMap: () => holder.map };
});
const { __holder: holder } = jest.requireMock("react-leaflet");

let container;
let map;
beforeEach(() => {
  jest.useFakeTimers();
  container = document.createElement("div");
  // jsdom lays nothing out; give the map a size so it has a view to draw over.
  Object.defineProperty(container, "clientWidth", { configurable: true, value: 1200 });
  Object.defineProperty(container, "clientHeight", { configurable: true, value: 800 });
  document.body.appendChild(container);
  map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView([34.78, -84.08], 14);
  holder.map = map;
});
afterEach(() => {
  map.remove();
  container.remove();
  jest.useRealTimers();
});

const pane = (name) => map.getPane(name);
const drawnPaths = () => [...(pane(GRID_PANE)?.querySelectorAll("path") || [])];
const labelTexts = () => [...(pane(LABEL_PANE)?.querySelectorAll(".mgrs-grid-label") || [])]
  .map((label) => label.textContent);
// Lines drawn: a path that is more than Leaflet's empty "M0 0".
const pathsWithLines = () => drawnPaths().filter((p) => (p.getAttribute("d") || "").includes("L"));
const settle = () => act(() => {
  jest.advanceTimersByTime(50);
});

// Leaflet's own pane stacking, read from its stylesheet as the browser would.
const leafletZ = (selector) => {
  const css = fs.readFileSync(path.join(require.resolve("leaflet"), "..", "leaflet.css"), "utf8");
  const rule = css.match(new RegExp(`\\.${selector}\\s*\\{[^}]*z-index:\\s*(\\d+)`));
  return Number(rule[1]);
};

describe("the MGRS grid layer", () => {
  it("draws above the base tiles and below the planning graphics, in its own panes", () => {
    const detach = attachMgrsGrid(map);
    expect(leafletZ("leaflet-tile-pane")).toBe(200);
    expect(leafletZ("leaflet-overlay-pane")).toBe(400);
    expect(GRID_Z_INDEX).toBeGreaterThan(200);
    expect(LABEL_Z_INDEX).toBeGreaterThan(GRID_Z_INDEX);
    expect(LABEL_Z_INDEX).toBeLessThan(400);
    expect(pane(GRID_PANE).style.zIndex).toBe(String(GRID_Z_INDEX));
    expect(pane(LABEL_PANE).style.zIndex).toBe(String(LABEL_Z_INDEX));
    detach();
  });

  it("never takes a click, drag or hover, and is marked as screen only", () => {
    const detach = attachMgrsGrid(map);
    [GRID_PANE, LABEL_PANE].forEach((name) => {
      expect(pane(name).style.pointerEvents).toBe("none");
      expect(pane(name).classList.contains(SCREEN_ONLY_CLASS)).toBe(true);
      expect(pane(name).getAttribute("aria-hidden")).toBe("true");
    });
    drawnPaths().forEach((p) => expect(p.classList.contains("leaflet-interactive")).toBe(false));
    const labels = [...pane(LABEL_PANE).querySelectorAll(".mgrs-grid-label")];
    expect(labels.length).toBeGreaterThan(0);
    labels.forEach((label) => {
      expect(label.classList.contains("leaflet-interactive")).toBe(false);
      expect(label.hasAttribute("tabindex")).toBe(false);
    });
    detach();
  });

  it("draws the lines and labels for the view: halos under lines, zone boundaries on top", () => {
    const detach = attachMgrsGrid(map);
    const classes = drawnPaths().map((p) => p.getAttribute("class").split(" ").find((c) => c.includes("--")));
    expect(classes).toEqual([
      "mgrs-grid__halo--minor", "mgrs-grid__halo--major", "mgrs-grid__halo--zone",
      "mgrs-grid__line--minor", "mgrs-grid__line--major", "mgrs-grid__line--zone",
    ]);
    // 1 km lines over north Georgia at zoom 14, labelled in principal digits.
    expect(pathsWithLines().map((p) => p.getAttribute("class"))).toEqual(
      expect.arrayContaining([expect.stringContaining("mgrs-grid__line--minor")]),
    );
    expect(labelTexts()).toEqual(expect.arrayContaining(["66", "16S GD"]));
    detach();
  });

  it("draws again once the view settles somewhere else", () => {
    const detach = attachMgrsGrid(map);
    expect(labelTexts()).toContain("16S GD");
    act(() => {
      map.setView([-33.87, 151.21], 14);
    });
    settle();
    expect(labelTexts()).toContain("56H LH");
    expect(labelTexts()).not.toContain("16S GD");
    detach();
  });

  it("leaves nothing behind, and stops listening, when taken off", () => {
    // Leaflet keeps a map's listeners by event type; the grid's must all go with it.
    const listeners = () => ["moveend", "zoomend", "resize"].map((type) => (map._events?.[type] || []).length);
    const before = listeners();
    const detach = attachMgrsGrid(map);
    expect(pathsWithLines().length).toBeGreaterThan(0);
    expect(listeners()).not.toEqual(before);
    detach();
    expect(listeners()).toEqual(before);
    expect(pane(GRID_PANE).querySelector("svg")).toBeNull();
    expect(pane(GRID_PANE).childElementCount).toBe(0);
    expect(pane(LABEL_PANE).childElementCount).toBe(0);
    expect(drawnPaths()).toHaveLength(0);
    expect(labelTexts()).toHaveLength(0);
    act(() => {
      map.setView([-33.87, 151.21], 14);
    });
    settle();
    expect(drawnPaths()).toHaveLength(0);
    expect(labelTexts()).toHaveLength(0);
  });

  it("can be put back after being taken off", () => {
    attachMgrsGrid(map)();
    const detach = attachMgrsGrid(map);
    expect(pathsWithLines().length).toBeGreaterThan(0);
    expect(labelTexts()).toContain("16S GD");
    detach();
  });

  it("is drawn while the component is mounted, and gone when it is unmounted", () => {
    const { unmount } = render(<MgrsGridLayer />);
    expect(pathsWithLines().length).toBeGreaterThan(0);
    expect(labelTexts().length).toBeGreaterThan(0);
    unmount();
    expect(drawnPaths()).toHaveLength(0);
    expect(labelTexts()).toHaveLength(0);
  });
});
