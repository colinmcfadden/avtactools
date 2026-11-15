import { render, screen } from "@testing-library/react";
import L from "leaflet";
import React from "react";
import PhaseLine from "./PhaseLine";

/*
 * react-leaflet is stood in for, as in readOnlyMapLayers.test.jsx: useMap gives a real Leaflet map (the labels are
 * placed from its projection), and the declarative pieces render plain divs carrying what the layer gave them.
 */
vi.mock("react-leaflet", () => {
  const ReactMock = require("react");
  const holder = { map: null, icons: [] };
  const Piece = (testid) => (props) => {
    if (testid === "marker" && props.icon?.options?.html instanceof HTMLElement) holder.icons.push(props.icon.options.html);
    return ReactMock.createElement("div", {
      "data-testid": testid,
      "data-draggable": String(Boolean(props.draggable)),
      "data-weight": String(props.pathOptions?.weight ?? ""),
      "data-opacity": String(props.pathOptions?.opacity ?? ""),
      "data-interactive": String(props.interactive !== false),
    });
  };
  return { __holder: holder, useMap: () => holder.map, Marker: Piece("marker"), Polyline: Piece("polyline") };
});

const { __holder: holder } = await import("react-leaflet");

let container;
beforeEach(() => {
  container = document.createElement("div");
  document.body.appendChild(container);
  holder.map = L.map(container).setView([34.5, -84.2], 12);
  holder.icons = [];
});
afterEach(() => {
  holder.map.remove();
  container.remove();
});

// Drawn west to east.
const RED = { id: "pl-1", name: "RED", points: [{ lat: 34.5, lon: -84.3 }, { lat: 34.5, lon: -84.1 }] };
const labels = () => [...new Set(holder.icons)];

describe("a phase line on the map", () => {
  it("is a solid line on its casing with PL and its name beyond each end", () => {
    render(<PhaseLine data={RED} />);
    const lines = screen.getAllByTestId("polyline");
    expect(lines.map((line) => line.dataset.weight)).toEqual(["8", "3.5"]);
    expect(lines.every((line) => line.dataset.interactive === "false")).toBe(true);
    const [west, east] = labels().slice(-2);
    expect([west.textContent, east.textContent]).toEqual(["PL RED", "PL RED"]);
    // The west end's label lies wholly west of it, the east end's wholly east: the line is never under either.
    expect(west.style.transform).toMatch(/^translate\(calc\(-50% \+ -50\.00% \+ -5\.00px\)/);
    expect(east.style.transform).toMatch(/^translate\(calc\(-50% \+ 50\.00% \+ 5\.00px\)/);
  });

  it("writes a name as text, never as markup", () => {
    render(<PhaseLine data={{ ...RED, name: '<img src=x onerror="alert(1)">' }} />);
    const label = labels().at(-1);
    expect(label.children).toHaveLength(0);
    expect(label.textContent).toBe('PL <img src=x onerror="alert(1)">');
  });

  it("has no handles where it may not be changed, nor while something else is drawn", () => {
    const { rerender } = render(<PhaseLine data={RED} />);
    const draggable = () => screen.getAllByTestId("marker").filter((marker) => marker.dataset.draggable === "true");
    expect(draggable()).toHaveLength(0);
    rerender(<PhaseLine data={RED} onChange={() => {}} />);
    expect(draggable()).toHaveLength(2);
    // The unseen line a finger finds.
    expect(screen.getAllByTestId("polyline").filter((line) => line.dataset.weight === "26" && line.dataset.opacity === "0")).toHaveLength(1);
    rerender(<PhaseLine data={RED} onChange={() => {}} passive />);
    expect(draggable()).toHaveLength(0);
  });

  it("draws nothing of a line with fewer than two places", () => {
    const { container: drawn } = render(<PhaseLine data={{ id: "pl-2", name: "RED", points: [{ lat: 34.5, lon: -84.3 }] }} />);
    expect(drawn).toBeEmptyDOMElement();
  });
});
