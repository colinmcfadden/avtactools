import { render } from "@testing-library/react";
import L from "leaflet";
import React from "react";
import { SCREEN_ONLY_CLASS } from "../export/captureFilter";
import SandtableLayer, { attachSandtable } from "./SandtableLayer";
import { FIGURE_COLOURS, stepView } from "./sandtableSteps";

// react-leaflet is an ES module Jest does not transform: useMap hands the layer a real Leaflet map.
jest.mock("react-leaflet", () => {
  const holder = { map: null };
  return { __holder: holder, useMap: () => holder.map };
});
const { __holder: holder } = jest.requireMock("react-leaflet");

const MODEL = require("../../../../contracts/fixtures/brief/compile.json").cases[0].expected;
const indexOf = (id) => MODEL.steps.findIndex((s) => s.id === id);
const viewOf = (id) => stepView(MODEL, indexOf(id));
const presentation = (view, extra = {}) => ({ view, geometry: MODEL.geometry, drawKey: JSON.stringify(view), ...extra });

let container;
let map;
beforeEach(() => {
  container = document.createElement("div");
  // jsdom lays nothing out; give the map a size so it has a view to fit things in.
  Object.defineProperty(container, "clientWidth", { configurable: true, value: 1200 });
  Object.defineProperty(container, "clientHeight", { configurable: true, value: 800 });
  document.body.appendChild(container);
  map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView([30, -80], 6);
  holder.map = map;
});
afterEach(() => {
  map.remove();
  container.remove();
});

const pane = (name) => map.getPane(name);
const labels = () => [...(pane("sandtableLabelPane")?.querySelectorAll(".sandtable-map-label") || [])].map((l) => l.textContent);
const strokes = () => [...(pane("sandtableShapePane")?.querySelectorAll("path") || [])].map((p) => p.getAttribute("stroke"));

describe("the sandtable's figure on the map", () => {
  it("lights the route in focus, names its points, and leaves the others dimmed and unnamed", () => {
    const view = viewOf(MODEL.steps.find((s) => s.figure?.kind === "route").id);
    const lit = MODEL.geometry.routes[view.focus];
    const other = Object.entries(MODEL.geometry.routes).find(([ref]) => ref !== view.focus)[1];
    const detach = attachSandtable(map, view, MODEL.geometry);

    expect(strokes()).toContain(FIGURE_COLOURS.focus);
    lit.points.forEach((p) => expect(labels()).toContain(p.name));
    other.points.filter((p) => !lit.points.some((q) => q.name === p.name)).forEach((p) => expect(labels()).not.toContain(p.name));
    const dimmed = [...pane("sandtableShapePane").querySelectorAll("path")].filter((p) => p.getAttribute("stroke") === other.color);
    expect(dimmed.length).toBeGreaterThan(0);
    dimmed.forEach((p) => expect(Number(p.getAttribute("stroke-opacity"))).toBeCloseTo(0.55));

    detach();
    expect(labels()).toEqual([]);
    expect(strokes()).toEqual([]);
  });

  it("lights the route the flight leaves on at the departure", () => {
    const detach = attachSandtable(map, viewOf("4.b"), MODEL.geometry);
    expect(strokes()).toContain(FIGURE_COLOURS.focus);
    detach();
  });

  it("shows the LZ/PZ in focus with its boundary, aircraft and landing heading", () => {
    const hawk = MODEL.geometry.stops["lz:lz-hawk"];
    const detach = attachSandtable(map, viewOf("4.e@lz:lz-hawk"), MODEL.geometry);
    expect(strokes()).toContain(FIGURE_COLOURS.roles.LZ);
    const arrows = pane("sandtableMarkPane").querySelectorAll(".sandtable-map-arrow");
    expect(arrows).toHaveLength(hawk.aircraft.length + (Number.isFinite(hawk.landingHeading) ? 1 : 0));
    expect(labels()).toContain(hawk.name);
    expect(pane("sandtableLabelPane").querySelector(".sandtable-map-label--large").textContent).toBe(hawk.name);
    detach();
  });

  it("puts the rally points on the contingencies step only", () => {
    const rallyStep = MODEL.steps.find((s) => s.figure?.kind === "rally");
    const named = MODEL.geometry.rally.map((r) => `${r.name} (${r.label})`);
    let detach = attachSandtable(map, stepView(MODEL, MODEL.steps.indexOf(rallyStep)), MODEL.geometry);
    expect(labels()).toEqual(expect.arrayContaining(named));
    expect(pane("sandtableMarkPane").querySelectorAll(".sandtable-map-rally")).toHaveLength(named.length);
    detach();
    detach = attachSandtable(map, viewOf("1"), MODEL.geometry);
    named.forEach((n) => expect(labels()).not.toContain(n));
    detach();
  });

  it("draws the threats on every step", () => {
    const detach = attachSandtable(map, viewOf("1"), MODEL.geometry);
    MODEL.geometry.threats.forEach((t) => expect(labels()).toContain(t.name));
    expect(strokes()).toContain(FIGURE_COLOURS.threat);
    detach();
  });

  it("sets the plan's words as text, never as markup", () => {
    const geometry = {
      ...MODEL.geometry,
      stops: { "lz:x": { name: '<img src="x" onerror="window.__hit = 1">', role: "LZ", lat: 34.5, lon: -84.1, boundary: null, aircraft: [] } },
    };
    const view = { stepId: "x", kind: "overview", focus: null, bounds: null, routes: [], stops: [{ ref: "lz:x", close: false }], rally: false };
    const detach = attachSandtable(map, view, geometry);
    expect(labels()).toContain('<img src="x" onerror="window.__hit = 1">');
    expect(pane("sandtableLabelPane").querySelector("img")).toBeNull();
    detach();
  });

  it("takes no clicks and stays off an LZ card's capture", () => {
    const detach = attachSandtable(map, viewOf("4.e@lz:lz-hawk"), MODEL.geometry);
    ["sandtableShapePane", "sandtableMarkPane", "sandtableLabelPane"].forEach((name) => {
      expect(pane(name).style.pointerEvents).toBe("none");
      expect(pane(name).classList.contains(SCREEN_ONLY_CLASS)).toBe(true);
    });
    expect(container.querySelectorAll(".leaflet-interactive")).toHaveLength(0);
    detach();
  });
});

describe("the sandtable's map", () => {
  const containsBounds = ([[s, w], [n, e]]) => map.getBounds().contains(L.latLngBounds([s, w], [n, e]));

  it("flies to each step's area, clear of what lies over the map", () => {
    const spy = jest.spyOn(map, "flyToBounds");
    const measure = jest.fn(() => ({ top: 20, right: 30, bottom: 300, left: 40 }));
    const hawk = viewOf("4.e@lz:lz-hawk");
    render(<SandtableLayer presentation={presentation(hawk, { measure })} />);
    expect(spy).toHaveBeenCalledWith(hawk.bounds, expect.objectContaining({ paddingTopLeft: [40, 20], paddingBottomRight: [30, 300] }));
    expect(containsBounds(hawk.bounds)).toBe(true);
  });

  it("flies once a step, not at every change to the plan under it, and again on the next step", () => {
    const spy = jest.spyOn(map, "flyToBounds");
    const hawk = viewOf("4.e@lz:lz-hawk");
    const { rerender } = render(<SandtableLayer presentation={presentation(hawk)} />);
    const moved = { ...hawk, bounds: [[hawk.bounds[0][0] + 0.01, hawk.bounds[0][1]], hawk.bounds[1]] };
    rerender(<SandtableLayer presentation={presentation(moved)} />);
    expect(spy).toHaveBeenCalledTimes(1);
    rerender(<SandtableLayer presentation={presentation(viewOf("1"))} />);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("leaves the map where it is for a step with nowhere to show, and flies back to the one before", () => {
    const spy = jest.spyOn(map, "flyToBounds");
    const hawk = viewOf("4.e@lz:lz-hawk");
    const { rerender } = render(<SandtableLayer presentation={presentation(hawk)} />);
    const nowhere = { ...hawk, stepId: "nowhere", bounds: null };
    rerender(<SandtableLayer presentation={presentation(nowhere)} />);
    expect(spy).toHaveBeenCalledTimes(1);
    rerender(<SandtableLayer presentation={presentation(hawk)} />);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("fits without flying for someone who asked for less motion", () => {
    const original = window.matchMedia;
    window.matchMedia = (query) => ({ matches: query.includes("reduce"), media: query, addEventListener() {}, removeEventListener() {} });
    try {
      const fly = jest.spyOn(map, "flyToBounds");
      const fit = jest.spyOn(map, "fitBounds");
      render(<SandtableLayer presentation={presentation(viewOf("1"))} />);
      expect(fly).not.toHaveBeenCalled();
      expect(fit).toHaveBeenCalledWith(viewOf("1").bounds, expect.objectContaining({ animate: false }));
    } finally {
      window.matchMedia = original;
    }
  });

  it("still flies to the first step when it is set up twice, as React does in development", () => {
    const before = { center: map.getCenter(), zoom: map.getZoom() };
    const hawk = viewOf("4.e@lz:lz-hawk");
    const { unmount } = render(<React.StrictMode><SandtableLayer presentation={presentation(hawk)} /></React.StrictMode>);
    expect(containsBounds(hawk.bounds)).toBe(true);
    expect(map.getZoom()).not.toBe(before.zoom);
    unmount();
    expect(map.getZoom()).toBe(before.zoom);
  });

  it("gives the map back where it was, its own keys untouched", () => {
    const before = { center: map.getCenter(), zoom: map.getZoom() };
    const { unmount } = render(<SandtableLayer presentation={presentation(viewOf("4.e@lz:lz-hawk"))} />);
    expect(map.keyboard.enabled()).toBe(true);
    expect(map.getZoom()).not.toBe(before.zoom);
    unmount();
    expect(map.keyboard.enabled()).toBe(true);
    expect(map.getZoom()).toBe(before.zoom);
    expect(map.getCenter().lat).toBeCloseTo(before.center.lat, 6);
    expect(map.getCenter().lng).toBeCloseTo(before.center.lng, 6);
    expect(labels()).toEqual([]);
  });

  it("ends quietly when the whole map goes first (a sign-out in the middle of a brief)", () => {
    const { unmount } = render(<SandtableLayer presentation={presentation(viewOf("1"))} />);
    map.remove();
    expect(() => unmount()).not.toThrow();
    // afterEach removes the map again: give it a fresh one to remove.
    map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView([30, -80], 6);
  });
});
