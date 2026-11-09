import { act, render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import L from "leaflet";
import Backstop from "./Backstop";

const drawn = vi.hoisted(() => ({ markers: [], map: null }));

vi.mock("react-leaflet", () => ({
  Marker: (props) => {
    drawn.markers.push(props);
    return null;
  },
  Polyline: () => null,
  useMap: () => drawn.map,
  useMapEvent: () => {},
}));

drawn.map = {
  getZoom: () => 15,
  project: (place, zoom) => L.CRS.EPSG3857.latLngToPoint(L.latLng(place), zoom),
  unproject: (point, zoom) => L.CRS.EPSG3857.pointToLatLng(point, zoom),
};

const TARGET = [34.78, -84.08];
const BACKSTOP = {
  id: "b1",
  points: [
    { lat: 34.8, lon: -84.09 },
    { lat: 34.8, lon: -84.07 },
  ],
};

const latest = (zIndexOffset) => drawn.markers.filter((marker) => marker.zIndexOffset === zIndexOffset).at(-1);
const grip = () => latest(-1000);
const tip = () => latest(1000);
const at = (lat, lng) => ({ target: { getLatLng: () => L.latLng(lat, lng) } });

describe("dragging a backstop's arrow", () => {
  beforeEach(() => {
    drawn.markers.length = 0;
  });

  it("keeps the grip's icon through its own drag, or Leaflet would end the drag with no dragend", () => {
    const onChange = vi.fn();
    render(<Backstop data={BACKSTOP} target={TARGET} onChange={onChange} />);

    const icon = grip().icon;
    const { lat, lng } = L.latLng(grip().position);
    act(() => grip().eventHandlers.dragstart(at(lat, lng)));
    // Far enough north that the same arrow is a few pixels longer on the screen.
    for (const north of [0.1, 0.3, 0.5]) {
      act(() => grip().eventHandlers.drag(at(lat + north, lng)));
      expect(grip().icon).toBe(icon);
    }
    act(() => grip().eventHandlers.dragend());

    expect(onChange).toHaveBeenCalledTimes(1);
    const [id, { arrow }] = onChange.mock.calls[0];
    expect(id).toBe("b1");
    expect(arrow.tail.lat).toBeCloseTo(34.8 + 0.5, 6);
    expect(arrow.tip.lat).toBeCloseTo(TARGET[0] + 0.5, 6);
  });

  it("takes the arrow's shape again once the drag is over", () => {
    const onChange = vi.fn();
    const { rerender } = render(<Backstop data={BACKSTOP} target={TARGET} onChange={onChange} />);

    const icon = grip().icon;
    const { lat, lng } = L.latLng(grip().position);
    act(() => grip().eventHandlers.dragstart(at(lat, lng)));
    act(() => grip().eventHandlers.drag(at(lat + 0.5, lng)));
    act(() => grip().eventHandlers.dragend());
    rerender(<Backstop data={{ ...BACKSTOP, ...onChange.mock.calls[0][1] }} target={TARGET} onChange={onChange} />);

    expect(grip().icon).not.toBe(icon);
  });

  it("moves only the tip when the tip is dragged, whatever the grip does meanwhile", () => {
    const onChange = vi.fn();
    render(<Backstop data={BACKSTOP} target={TARGET} onChange={onChange} />);

    const icon = tip().icon;
    act(() => tip().eventHandlers.dragstart(at(TARGET[0], TARGET[1])));
    act(() => tip().eventHandlers.drag(at(34.77, -84.06)));
    expect(tip().icon).toBe(icon);
    act(() => tip().eventHandlers.dragend());

    const [, { arrow }] = onChange.mock.calls[0];
    expect(arrow.tail.lat).toBeCloseTo(34.8, 6);
    expect(arrow.tip).toEqual({ lat: 34.77, lon: -84.06 });
  });
});
