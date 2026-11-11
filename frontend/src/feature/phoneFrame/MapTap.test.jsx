import { act, fireEvent, render } from "@testing-library/react";
import L from "leaflet";
import React from "react";
import MapTap, { TAP_MS } from "./MapTap";

// react-leaflet's hooks stood in for with a real Leaflet map, whose own events are fired as Leaflet would.
vi.mock("react-leaflet", async () => {
  const ReactMock = await import("react");
  const holder = { map: null };
  return {
    __holder: holder,
    useMap: () => holder.map,
    useMapEvents: (handlers) => {
      ReactMock.useEffect(() => {
        holder.map.on(handlers);
        return () => holder.map.off(handlers);
      });
      return holder.map;
    },
  };
});

const { __holder: holder } = await import("react-leaflet");

let container;
beforeEach(() => {
  vi.useFakeTimers();
  container = document.createElement("div");
  document.body.appendChild(container);
  // Leaflet's own double-click zoom reads the browser's event, which a fired one has none of.
  holder.map = L.map(container, { doubleClickZoom: false }).setView([34.5, -84.2], 16);
});
afterEach(() => {
  holder.map.remove();
  container.remove();
  vi.useRealTimers();
});

const setup = (stood = { phone: true }) => {
  const before = vi.fn(() => stood);
  const onTap = vi.fn();
  render(<MapTap before={before} onTap={onTap} />);
  return { before, onTap };
};
const click = () => act(() => holder.map.fire("click"));
const wait = (ms = TAP_MS) => act(() => vi.advanceTimersByTime(ms));

describe("a tap on the map, told to the phone's frame", () => {
  it("is told once it is known to be a single tap, with how things stood as the finger came down", () => {
    const stood = { phone: true, searchOpen: true };
    const { before, onTap } = setup(stood);
    fireEvent.pointerDown(container);
    expect(before).toHaveBeenCalledTimes(1);
    click();
    expect(onTap).not.toHaveBeenCalled();
    wait();
    expect(onTap).toHaveBeenCalledTimes(1);
    expect(onTap).toHaveBeenCalledWith(stood);
  });

  it("is not told of either tap of a double tap, which zooms", () => {
    const { onTap } = setup();
    fireEvent.pointerDown(container);
    click();
    wait(120);
    fireEvent.pointerDown(container);
    click();
    act(() => holder.map.fire("dblclick"));
    wait(1000);
    expect(onTap).not.toHaveBeenCalled();
  });

  it("is not told of a tap that began a zoom", () => {
    const { onTap } = setup();
    fireEvent.pointerDown(container);
    click();
    act(() => holder.map.fire("zoomstart"));
    wait(1000);
    expect(onTap).not.toHaveBeenCalled();
  });

  it("tells each of two taps apart in time", () => {
    const { onTap } = setup();
    fireEvent.pointerDown(container);
    click();
    wait();
    fireEvent.pointerDown(container);
    click();
    wait();
    expect(onTap).toHaveBeenCalledTimes(2);
  });

  it("tells nothing once it is gone", () => {
    const before = vi.fn(() => ({}));
    const onTap = vi.fn();
    const { unmount } = render(<MapTap before={before} onTap={onTap} />);
    fireEvent.pointerDown(container);
    click();
    unmount();
    wait(1000);
    expect(onTap).not.toHaveBeenCalled();
    fireEvent.pointerDown(container);
    expect(before).toHaveBeenCalledTimes(1);
  });
});
