import { render } from "@testing-library/react";
import L from "leaflet";
import React from "react";
import StartAtLocation from "./StartAtLocation";
import { LOCATED_ZOOM, resetStartAtLocation } from "./startLocation";

// react-leaflet is an ES module: useMap hands the component a real Leaflet map.
vi.mock("react-leaflet", () => {
  const holder = { map: null };
  return { __holder: holder, useMap: () => holder.map };
});
const { __holder: holder } = await import("react-leaflet");

const flush = async () => {
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
};

let container;
let asks;
beforeEach(() => {
  resetStartAtLocation();
  window.localStorage.clear();
  container = document.createElement("div");
  Object.defineProperty(container, "clientWidth", { configurable: true, value: 1200 });
  Object.defineProperty(container, "clientHeight", { configurable: true, value: 800 });
  document.body.appendChild(container);
  holder.map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView([34.0522, -118.2437], 11);
  asks = [];
  Object.defineProperty(navigator, "geolocation", {
    configurable: true,
    value: { getCurrentPosition: (success) => asks.push(success) },
  });
});
afterEach(() => {
  holder.map.remove();
  container.remove();
  delete navigator.geolocation;
});

describe("StartAtLocation", () => {
  it("asks the browser once and centres the map on the answer", async () => {
    const { rerender } = render(<StartAtLocation claimed={false} />);
    rerender(<StartAtLocation claimed={false} />);
    await flush();
    expect(asks).toHaveLength(1);
    asks[0]({ coords: { latitude: 34.78, longitude: -84.08 } });
    await flush();
    expect(holder.map.getZoom()).toBe(LOCATED_ZOOM);
    expect(holder.map.getCenter().lat).toBeCloseTo(34.78, 9);
  });

  it("does not ask while the app has a view of its own", async () => {
    render(<StartAtLocation claimed />);
    await flush();
    expect(asks).toHaveLength(0);
  });

  it("drops the answer once the app claims the view", async () => {
    const { rerender } = render(<StartAtLocation claimed={false} />);
    await flush();
    rerender(<StartAtLocation claimed />);
    asks[0]({ coords: { latitude: 34.78, longitude: -84.08 } });
    await flush();
    expect(holder.map.getZoom()).toBe(11);
  });
});
