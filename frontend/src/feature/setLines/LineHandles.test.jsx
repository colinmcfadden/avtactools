import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import LineHandles, { useLineEditing } from "./LineHandles";

const drawn = vi.hoisted(() => ({ markers: [], lines: [] }));

vi.mock("react-leaflet", () => ({
  Marker: (props) => {
    drawn.markers.push(props);
    return null;
  },
  Polyline: (props) => {
    drawn.lines.push(props);
    return null;
  },
}));

const LINE = {
  id: "pl-1",
  points: [
    { lat: 34.5, lon: -84.3 },
    { lat: 34.5, lon: -84.2 },
    { lat: 34.5, lon: -84.1 },
  ],
};

const Handles = ({ onContextMenu }) => {
  const editing = useLineEditing({ data: LINE, onChange: () => {}, editable: true });
  return <LineHandles id={LINE.id} editing={editing} icon={null} onContextMenu={onContextMenu} />;
};

// A right-click as Leaflet hands it to a layer: its own event, with the browser's inside.
const rightClick = (clientX, clientY) => ({ originalEvent: { clientX, clientY, preventDefault: vi.fn() } });

describe("a line's menu", () => {
  beforeEach(() => {
    drawn.markers.length = 0;
    drawn.lines.length = 0;
  });

  it("opens from the line between two points", () => {
    const onContextMenu = vi.fn();
    render(<Handles onContextMenu={onContextMenu} />);
    const click = rightClick(210, 96);
    drawn.lines.at(-1).eventHandlers.contextmenu(click);
    expect(onContextMenu).toHaveBeenCalledWith("pl-1", 210, 96);
    expect(click.originalEvent.preventDefault).toHaveBeenCalled();
  });

  it("opens from each of its points, which lie over the line", () => {
    const onContextMenu = vi.fn();
    render(<Handles onContextMenu={onContextMenu} />);
    const vertices = drawn.markers.slice(-LINE.points.length);
    expect(vertices).toHaveLength(3);
    vertices.forEach((vertex, i) => {
      const click = rightClick(100 + i, 50);
      vertex.eventHandlers.contextmenu(click);
      expect(onContextMenu).toHaveBeenLastCalledWith("pl-1", 100 + i, 50);
      // The map under the point has a menu of its own, which must not open as well, nor the browser's.
      expect(click.originalEvent._stopped).toBe(true);
      expect(click.originalEvent.preventDefault).toHaveBeenCalled();
    });
    expect(onContextMenu).toHaveBeenCalledTimes(3);
  });

  it("keeps a point's drag as it was", () => {
    render(<Handles onContextMenu={() => {}} />);
    const vertex = drawn.markers.at(-1);
    expect(vertex.draggable).toBe(true);
    expect(Object.keys(vertex.eventHandlers).sort()).toEqual(["contextmenu", "drag", "dragend", "dragstart", "mouseout", "mouseover"]);
  });

  it("does nothing where there is no menu to open", () => {
    render(<Handles />);
    const click = rightClick(1, 2);
    expect(() => drawn.markers.at(-1).eventHandlers.contextmenu(click)).not.toThrow();
    expect(click.originalEvent.preventDefault).not.toHaveBeenCalled();
  });
});
