import { act, render, screen } from "@testing-library/react";
import { RouteLine, routeLineStyles } from "./MsnxRouteLayer";

const lines = [];

vi.mock("react-leaflet", () => ({
  Polyline: (props) => {
    lines.push(props);
    return props.children ?? null;
  },
  Marker: () => null,
  Popup: () => null,
  Tooltip: ({ children }) => <div>{children}</div>,
  useMap: () => null,
}));

const ROUTE = { id: "r1", name: "ROUTE 1", color: "#ff0000" };
const POSITIONS = [[34, -84], [34.1, -84.1]];

const drawn = () => {
  const [halo, line, hit] = lines.slice(-3);
  return { halo, line, hit };
};

const rightClick = () => ({
  latlng: { lat: 34.05, lng: -84.05 },
  originalEvent: { clientX: 10, clientY: 20, preventDefault: () => {}, stopPropagation: () => {} },
});

describe("a route's line on the map", () => {
  beforeEach(() => {
    lines.length = 0;
  });

  it("is plain until the mouse is on it, and thick and haloed while it is", () => {
    render(<RouteLine route={ROUTE} positions={POSITIONS} onInsertPoint={() => {}} />);
    expect(drawn().line.pathOptions).toEqual(routeLineStyles(ROUTE.color, false).line);
    expect(drawn().halo.pathOptions.opacity).toBe(0);
    act(() => drawn().hit.eventHandlers.mouseover());
    expect(drawn().line.pathOptions.weight).toBeGreaterThan(routeLineStyles(ROUTE.color, false).line.weight);
    expect(drawn().halo.pathOptions.opacity).toBeGreaterThan(0);
    act(() => drawn().hit.eventHandlers.mouseout());
    expect(drawn().line.pathOptions).toEqual(routeLineStyles(ROUTE.color, false).line);
  });

  it("leaves the mouse to a wide unseen line, so it is easy to find", () => {
    render(<RouteLine route={ROUTE} positions={POSITIONS} onInsertPoint={() => {}} />);
    const { halo, line, hit } = drawn();
    expect([halo.interactive, line.interactive]).toEqual([false, false]);
    expect(hit.weight).toBeGreaterThan(line.pathOptions.weight * 3);
    expect(hit.opacity).toBe(0);
  });

  it("says which route it is and what a right-click does", () => {
    render(<RouteLine route={ROUTE} positions={POSITIONS} onInsertPoint={() => {}} />);
    expect(screen.getByText("ROUTE 1")).toBeTruthy();
    expect(screen.getByText(/to add a point/)).toBeTruthy();
  });

  it("offers no point to add where none can be (a read-only pack), and still shows the route", () => {
    render(<RouteLine route={ROUTE} positions={POSITIONS} />);
    expect(screen.getByText("ROUTE 1")).toBeTruthy();
    expect(screen.queryByText(/to add a point/)).toBeNull();
  });

  it("asks to add a point where it was right-clicked", () => {
    const onInsertPoint = vi.fn();
    render(<RouteLine route={ROUTE} positions={POSITIONS} onInsertPoint={onInsertPoint} />);
    drawn().hit.eventHandlers.contextmenu(rightClick());
    expect(onInsertPoint).toHaveBeenCalledWith("r1", 34.05, -84.05, 10, 20);
  });

  it("keeps out of the way while something is being drawn: no highlight, and the map has the right-click", () => {
    render(<RouteLine route={ROUTE} positions={POSITIONS} onInsertPoint={() => {}} drawing />);
    expect(drawn().hit.eventHandlers).toEqual({});
    expect(screen.queryByText("ROUTE 1")).toBeNull();
  });
});
