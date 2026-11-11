import { act, fireEvent, render, screen } from "@testing-library/react";
import fs from "fs";
import L from "leaflet";
import path from "path";
import React from "react";
import MapZoom from "./MapZoom";

let container;
let map;
beforeEach(() => {
  container = document.createElement("div");
  document.body.appendChild(container);
  map = L.map(container, { zoomControl: false, minZoom: 3, maxZoom: 18, zoomAnimation: false }).setView([34.5, -84.2], 11);
});
afterEach(() => {
  map.remove();
  container.remove();
});

describe("the map's zoom buttons", () => {
  it("draws nothing until the map is made", () => {
    const { container: host } = render(<MapZoom map={null} />);
    expect(host).toBeEmptyDOMElement();
  });

  it("zooms in and out a step, each button named for what it does", () => {
    render(<MapZoom map={map} />);
    expect(screen.getByRole("group", { name: "Zoom" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(map.getZoom()).toBe(12);
    fireEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    fireEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    expect(map.getZoom()).toBe(10);
  });

  it("turns a button off at the end of the map's levels, however the map got there", () => {
    render(<MapZoom map={map} />);
    expect(screen.getByRole("button", { name: "Zoom in" })).toBeEnabled();
    act(() => map.setZoom(18));
    expect(screen.getByRole("button", { name: "Zoom in" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Zoom out" })).toBeEnabled();
    act(() => map.setZoom(3));
    expect(screen.getByRole("button", { name: "Zoom out" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Zoom in" })).toBeEnabled();
  });
});

// jsdom applies no stylesheet, so the sizes are read from it.
describe("the zoom buttons' size", () => {
  const css = fs.readFileSync(path.join(__dirname, "MapZoom.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const size = (block) => Number(block.match(/\.map-zoom__button \{[^}]*?width: (\d+)px/)?.[1]);

  it("is larger than Leaflet's 30 px on a wide window, and a finger's on a phone or a touch screen", () => {
    const [wide] = css.split("@media");
    expect(size(wide)).toBeGreaterThanOrEqual(40);
    const touch = css.split("@media").find((block) => block.trim().startsWith("(max-width: 850px), (pointer: coarse)"));
    expect(size(touch)).toBeGreaterThanOrEqual(44);
  });
});
