import { useEffect, useRef } from "react";
import { useMap, useMapEvents } from "react-leaflet";

// Longer than Leaflet's own wait for a second tap (200 ms), so a double tap, which zooms, is never a tap.
export const TAP_MS = 250;

/**
 * A single tap on the map, told to the phone's frame (usePhoneFrame): `before` is asked how things stand as the
 * finger comes down, and `onTap` is given that once the tap is known to be one. A pan is no tap (Leaflet sends no
 * click for it), nor is either tap of a double tap, nor one that starts a zoom; a tap on a marker is the marker's.
 */
const MapTap = ({ before, onTap }) => {
  const map = useMap();
  const stood = useRef(null);
  const waiting = useRef(null);

  const forget = () => {
    clearTimeout(waiting.current);
    waiting.current = null;
  };

  useEffect(() => {
    const container = map.getContainer();
    // In the capture phase: before anything the press closes has heard of it.
    const down = () => {
      stood.current = before();
    };
    container.addEventListener("pointerdown", down, true);
    return () => {
      container.removeEventListener("pointerdown", down, true);
      forget();
    };
  }, [map, before]);

  useMapEvents({
    click: () => {
      if (waiting.current) {
        forget();
        return;
      }
      const state = stood.current;
      stood.current = null;
      waiting.current = setTimeout(() => {
        waiting.current = null;
        onTap(state);
      }, TAP_MS);
    },
    dblclick: forget,
    zoomstart: forget,
  });

  return null;
};

export default MapTap;
