import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useMap, useMapEvents } from "react-leaflet";
import { toMgrs } from "../utils/mgrs";
import "./CursorGridReadout.css";

/**
 * The MGRS grid under the cursor, beside the map type picker, updated as the
 * mouse moves.
 *
 * Converted in the browser (utils/mgrs), so a sweep across the map costs no
 * requests — at most one conversion per animation frame, under a microsecond
 * each. Only this component re-renders as the mouse moves, not the map.
 *
 * Rendered into Leaflet's control layer: the LZ card export captures the map
 * element but leaves that layer out, so the readout never lands on a card.
 */
const CursorGridReadout = () => {
  const map = useMap();
  const [grid, setGrid] = useState(null);
  const frame = useRef(null);
  const latest = useRef(null);

  useMapEvents({
    mousemove: (event) => {
      latest.current = event.latlng;
      if (frame.current) return;
      frame.current = requestAnimationFrame(() => {
        frame.current = null;
        const { lat, lng } = latest.current;
        setGrid(toMgrs(lat, lng));
      });
    },
  });

  // mouseleave rather than Leaflet's mouseout, which also fires on passing
  // over a marker and would make the readout flicker.
  useEffect(() => {
    const container = map.getContainer();
    const hide = () => {
      cancelAnimationFrame(frame.current);
      frame.current = null;
      setGrid(null);
    };
    container.addEventListener("mouseleave", hide);
    return () => {
      container.removeEventListener("mouseleave", hide);
      cancelAnimationFrame(frame.current);
    };
  }, [map]);

  const layer = map.getContainer().querySelector(".leaflet-control-container");
  if (!grid || !layer) return null;

  return createPortal(
    <div className="cursor-grid" aria-hidden="true">
      <span className="cursor-grid__label">MGRS</span>
      <span className="cursor-grid__value">{grid}</span>
    </div>,
    layer,
  );
};

export default CursorGridReadout;
