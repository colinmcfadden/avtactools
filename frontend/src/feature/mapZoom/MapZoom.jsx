import React, { useEffect, useState } from "react";
import Icon from "../ui/Icon";
import "./MapZoom.css";

/*
 * The map's zoom buttons, in the app's own style and a size a finger finds (the owner, 2026-11-11), in place of
 * Leaflet's stock control. Beside the map, not in it: a press is never a click on the map (a corner of a boundary
 * being drawn), and the LZ card capture, which takes the map's own element, never has them.
 *
 * `map` is the Leaflet map once it is made (MapView's `onMap`). A step is the map's own zoomDelta, as the stock
 * buttons' was, and a button is off at the end of the base map's levels.
 */
const MapZoom = ({ map }) => {
  const [zoom, setZoom] = useState(null);

  useEffect(() => {
    if (!map) return undefined;
    const read = () => setZoom(map.getZoom());
    read();
    map.on("zoomend zoomlevelschange", read);
    return () => map.off("zoomend zoomlevelschange", read);
  }, [map]);

  if (!map) return null;
  const atMost = zoom !== null && zoom >= map.getMaxZoom();
  const atLeast = zoom !== null && zoom <= map.getMinZoom();

  return (
    <div className="map-zoom" role="group" aria-label="Zoom">
      <button type="button" className="map-zoom__button" aria-label="Zoom in" title="Zoom in" disabled={atMost} onClick={() => map.zoomIn()}>
        <Icon name="plus" size={20} strokeWidth={2.2} />
      </button>
      <button type="button" className="map-zoom__button" aria-label="Zoom out" title="Zoom out" disabled={atLeast} onClick={() => map.zoomOut()}>
        <Icon name="minus" size={20} strokeWidth={2.2} />
      </button>
    </div>
  );
};

export default MapZoom;
