import { render } from "@testing-library/react";
import React from "react";
import { MapContainer } from "react-leaflet";
import DraftPointMarkers, { plainDraftPositions } from "./DraftPointMarkers";

// Real Leaflet, so what is held is what lands in the map's DOM.
const onMap = (positions) => (
  <MapContainer center={[34.5, -84.2]} zoom={16}>
    <DraftPointMarkers positions={positions} color="#FFC107" />
  </MapContainer>
);
const dots = (container) => [...container.querySelectorAll(".leaflet-marker-pane .msnx-point-icon")];

describe("DraftPointMarkers", () => {
  it("shows the first point at once, and each later point the same way", () => {
    const { container, rerender } = render(onMap([[34.5, -84.2]]));
    expect(dots(container)).toHaveLength(1);
    expect(dots(container)[0].innerHTML).toContain("#FFC107");

    rerender(onMap([[34.5, -84.2], [34.51, -84.21]]));
    expect(dots(container)).toHaveLength(2);
    expect(dots(container)[1].innerHTML).toBe(dots(container)[0].innerHTML);
  });

  it("draws nothing with no points", () => {
    const { container } = render(onMap([]));
    expect(dots(container)).toHaveLength(0);
  });

  it("leaves clicks to the map, so a point can be placed on or beside a dot", () => {
    const { container } = render(onMap([[34.5, -84.2]]));
    const [dot] = dots(container);
    expect(dot.classList.contains("leaflet-interactive")).toBe(false);
    expect(dot.hasAttribute("tabindex")).toBe(false);
  });
});

describe("plainDraftPositions", () => {
  it("gives every undesignated point, in order", () => {
    expect(plainDraftPositions([{ lat: 1, lon: 2, designation: null }, { lat: 3, lon: 4 }])).toEqual([[1, 2], [3, 4]]);
  });

  it("leaves out a designated point, which has its own marker", () => {
    const designated = { lat: 3, lon: 4, designation: { ptType: "ip", name: ".RP" } };
    expect(plainDraftPositions([{ lat: 1, lon: 2, designation: null }, designated])).toEqual([[1, 2]]);
  });
});
