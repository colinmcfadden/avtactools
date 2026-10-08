import { act, render } from "@testing-library/react";
import * as htmlToImage from "html-to-image";
import L from "leaflet";
import React from "react";
import { GRID_PANE, LABEL_PANE, attachMgrsGrid } from "../mgrsGrid/MgrsGridLayer";
import ExportHandler from "./ExportHandler";
import { SCREEN_ONLY_CLASS, keepInCapture } from "./captureFilter";

// react-leaflet is an ES module Jest does not transform: useMap hands out a real Leaflet map.
jest.mock("react-leaflet", () => {
  const holder = { map: null };
  return { __holder: holder, useMap: () => holder.map };
});
jest.mock("html-to-image", () => ({ toCanvas: jest.fn() }));
const { __holder: holder } = jest.requireMock("react-leaflet");

const element = (className) => {
  const node = document.createElement("div");
  if (className) node.className = className;
  return node;
};

describe("what the LZ card leaves out of the map", () => {
  it("is the controls, the panels and anything marked as screen only", () => {
    expect(keepInCapture(element("leaflet-control-container"))).toBe(false);
    expect(keepInCapture(element("ff-panel open"))).toBe(false);
    expect(keepInCapture(element(`leaflet-pane ${SCREEN_ONLY_CLASS}`))).toBe(false);
  });

  it("keeps the map itself, and text", () => {
    expect(keepInCapture(element("leaflet-pane leaflet-tile-pane"))).toBe(true);
    expect(keepInCapture(element("leaflet-pane leaflet-overlay-pane"))).toBe(true);
    expect(keepInCapture(element())).toBe(true);
    expect(keepInCapture(document.createTextNode("16S GD"))).toBe(true);
  });
});

describe("the LZ card export with the MGRS grid on", () => {
  let container;
  let map;
  beforeEach(() => {
    jest.useFakeTimers();
    container = document.createElement("div");
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
    jest.restoreAllMocks();
  });

  it("captures the map without the grid's lines or labels", async () => {
    const detach = attachMgrsGrid(map);
    expect(map.getPane(GRID_PANE).querySelector("path")).not.toBeNull();
    expect(map.getPane(LABEL_PANE).querySelector(".mgrs-grid-label")).not.toBeNull();

    // Stop the export at the capture: what it was asked to leave out is all this test needs.
    htmlToImage.toCanvas.mockRejectedValue(new Error("stopped by the test"));
    jest.spyOn(console, "error").mockImplementation(() => {});
    jest.spyOn(window, "alert").mockImplementation(() => {});
    const onExportComplete = jest.fn();
    render(
      <ExportHandler
        isExporting
        exportBox={[[34.775, -84.085], [34.785, -84.075]]}
        setExportProgress={() => {}}
        onExportComplete={onExportComplete}
      />,
    );
    await act(async () => {
      jest.advanceTimersByTime(1500);
    });
    expect(onExportComplete).toHaveBeenCalledWith(null);

    expect(htmlToImage.toCanvas).toHaveBeenCalledTimes(1);
    const [captured, { filter }] = htmlToImage.toCanvas.mock.calls[0];
    expect(captured).toBe(map.getContainer());
    expect(filter(map.getPane(GRID_PANE))).toBe(false);
    expect(filter(map.getPane(LABEL_PANE))).toBe(false);
    expect(filter(map.getPane("tilePane"))).toBe(true);
    expect(filter(map.getPane("overlayPane"))).toBe(true);
    expect(filter(map.getPane("markerPane"))).toBe(true);
    detach();
  });
});
