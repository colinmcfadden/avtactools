import { act, render } from "@testing-library/react";
import React from "react";
import { MapContainer } from "react-leaflet";
import { OVERLAY_CAPTURE_CLASS } from "../missionOverlay/overlayPlan";
import RouteDoghouseLayer from "./RouteDoghouseLayer";
import { FULL_LEG_PX, MIN_EXPORT_SCALE, MIN_SCALE, routeDoghouses } from "./routeDoghouses";

// One straight leg due south, SP to the target: its one doghouse.
const ROUTE = {
  id: "r1",
  name: "ROUTE 1",
  color: "#ff0000",
  points: [
    { id: "sp", lat: 34.6, lon: -84.4, kind: "amps", ptType: "ip", name: ".SP" },
    { id: "tgt", lat: 34.5, lon: -84.4, kind: "amps", ptType: "target", name: ".TGT" },
  ],
};
const [DOGHOUSE] = routeDoghouses([ROUTE]);
// The zoom at which the leg holds its doghouse whole.
const FULL = Math.log2(FULL_LEG_PX / DOGHOUSE.lengthPx0);

// Real Leaflet, with the map's own zoom steps of a tenth, so what is set is what the marker's element is given.
const mount = (zoom, shown = true) => {
  let map = null;
  const view = render(
    <MapContainer center={[34.55, -84.4]} zoom={zoom} zoomSnap={0.1} zoomAnimation={false} fadeAnimation={false} ref={(m) => { map = m; }}>
      <RouteDoghouseLayer routes={[ROUTE]} shown={shown} />
    </MapContainer>,
  );
  return { ...view, map: () => map };
};
const turns = (container) => [...container.querySelectorAll(".leaflet-marker-pane .route-doghouse__turn")];
const scaleOf = (turn) => {
  const found = /scale\(([\d.]+)\)/.exec(turn.style.transform);
  return found ? Number(found[1]) : 1;
};

describe("RouteDoghouseLayer", () => {
  it("draws a leg's doghouse whole where its leg holds it, turned to the leg", () => {
    const { container } = mount(Math.ceil(FULL * 10) / 10 + 1);
    const [turn] = turns(container);
    expect(turn.style.transform).toContain(`rotate(${DOGHOUSE.rotation}deg)`);
    expect(scaleOf(turn)).toBe(1);
  });

  it("draws it smaller, never puts it away, as the map zooms out", () => {
    const { container, map } = mount(Math.round((FULL - 1) * 10) / 10);
    const [turn] = turns(container);
    expect(turn.isConnected).toBe(true);
    expect(scaleOf(turn)).toBeLessThan(1);
    expect(scaleOf(turn)).toBeGreaterThan(MIN_SCALE);
    act(() => map().setZoom(Math.round(FULL - 6), { animate: false }));
    const [far] = turns(container);
    expect(far.closest(".leaflet-marker-icon").style.display).not.toBe("none");
    expect(scaleOf(far)).toBe(MIN_SCALE);
    expect(far.style.transform).toContain(`rotate(${DOGHOUSE.rotation}deg)`);
  });

  it("keeps a larger floor while a mission overlay is drawn, and the screen's once it is done", () => {
    const { container, map } = mount(Math.round(FULL - 6));
    const element = map().getContainer();
    // As captureOverlay does: the class first, then each piece's view set afresh.
    act(() => {
      element.classList.add(OVERLAY_CAPTURE_CLASS);
      map().setView(map().getCenter(), map().getZoom(), { animate: false, reset: true });
    });
    expect(scaleOf(turns(container)[0])).toBe(MIN_EXPORT_SCALE);
    act(() => {
      element.classList.remove(OVERLAY_CAPTURE_CLASS);
      map().setView(map().getCenter(), map().getZoom(), { animate: false, reset: true });
    });
    expect(scaleOf(turns(container)[0])).toBe(MIN_SCALE);
  });

  it("draws none when they are turned off", () => {
    const { container } = mount(Math.round(FULL + 1), false);
    expect(turns(container)).toHaveLength(0);
  });
});
