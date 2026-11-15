import { useEffect, useRef, useState } from "react";
import { Marker, Polyline } from "react-leaflet";
import L from "leaflet";
import { linePoints } from "./setLines";

/**
 * What changing a set's line (a FLOT, a phase line) on the map keeps: the point being dragged, which is local and goes to the set
 * when it is let go (`onChange(id, { points })`), and whether its handles show. `points` is the line as it is drawn meanwhile.
 */
export const useLineEditing = ({ data, onChange, editable }) => {
  const [live, setLive] = useState(null);
  const [active, setActive] = useState(false);
  const drag = useRef(null);

  // A pack that turns read-only mid-drag takes the handles away, and their dragend with them.
  useEffect(() => {
    if (!editable) {
      drag.current = null;
      setLive(null);
    }
  }, [editable]);

  const saved = linePoints(data);
  const showing = (value) => () => {
    if (!drag.current) setActive(value);
  };
  const hover = { mouseover: showing(true), mouseout: showing(false) };

  const handlesFor = (index) => ({
    ...hover,
    dragstart: () => {
      drag.current = { latest: null };
    },
    drag: (e) => {
      if (!drag.current) return;
      const at = e.target.getLatLng();
      drag.current.latest = saved.map((point, i) => (i === index ? { lat: at.lat, lon: at.lng } : point));
      setLive(drag.current.latest);
    },
    dragend: () => {
      const moved = drag.current?.latest;
      drag.current = null;
      setLive(null);
      if (moved && onChange) onChange(data.id, { points: moved });
    },
  });

  return { saved, points: live ?? saved, active, hover, handlesFor, toggle: () => setActive((value) => !value) };
};

/**
 * The handles of a line being changed (`editing`: useLineEditing's): an unseen wide line over it, which is what a finger or the
 * mouse finds (a line this thin would be nearly impossible to hit), and a vertex to drag at each of its points.
 *
 * The line's menu opens from a vertex as it does from the line between two: a vertex lies over the line, so a right-click
 * (a long press) on one never reaches the line under it, and the ends, where the pointer goes first, are vertices.
 */
const LineHandles = ({ id, editing, icon, onContextMenu }) => {
  const openMenu = (e) => {
    if (!onContextMenu) return;
    L.DomEvent.stop(e);
    e.originalEvent.preventDefault();
    onContextMenu(id, e.originalEvent.clientX, e.originalEvent.clientY);
  };
  return (
    <>
      <Polyline
        positions={editing.points.map((p) => [p.lat, p.lon])}
        pathOptions={{ opacity: 0, weight: 26 }}
        eventHandlers={{
          ...editing.hover,
          click: (e) => {
            L.DomEvent.stopPropagation(e);
            editing.toggle();
          },
          contextmenu: openMenu,
        }}
      />
      {editing.saved.map((point, i) => (
        <Marker
          // eslint-disable-next-line react/no-array-index-key
          key={i}
          position={[point.lat, point.lon]}
          icon={icon}
          draggable
          eventHandlers={{ ...editing.handlesFor(i), contextmenu: openMenu }}
          opacity={editing.active ? 1 : 0}
          zIndexOffset={1000}
        />
      ))}
    </>
  );
};

export default LineHandles;
