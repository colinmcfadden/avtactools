import { useEffect, useMemo } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { doghouseBoxHtml } from "../doghouses/doghouseBox";
import { fitsAt, routeDoghouses } from "./routeDoghouses";
import "./routeDoghouses.css";

const iconOf = (doghouse) =>
  L.divIcon({
    className: "route-doghouse",
    iconSize: [0, 0],
    iconAnchor: [0, 0],
    // Turned to the leg, then moved off the line in its own frame: to the right of the way it is flown, clear of it.
    html: `<div class="route-doghouse__turn" style="transform: rotate(${doghouse.rotation}deg)">${doghouseBoxHtml(doghouse.box, {
      className: "route-doghouse__box",
      style: `transform: translate(calc(-50% + ${doghouse.offset.x}px), calc(-50% + ${doghouse.offset.y}px));`,
      roofStyle: "white-space: nowrap; overflow: hidden; text-overflow: clip;",
    })}</div>`,
  });

/**
 * The legs' doghouses over the routes shown. Drawn as markers that take no click (the route's line and points under them
 * keep theirs), and shown or put away as the zoom changes, in the same call, so a mission overlay that sets the view and
 * draws at once finds them as that zoom has them.
 */
const RouteDoghouseLayer = ({ routes, shown = true }) => {
  const map = useMap();
  const doghouses = useMemo(() => (shown ? routeDoghouses(routes) : []), [routes, shown]);

  useEffect(() => {
    if (doghouses.length === 0) return undefined;
    const placed = doghouses.map((doghouse) => ({
      doghouse,
      marker: L.marker([doghouse.lat, doghouse.lon], { icon: iconOf(doghouse), interactive: false, keyboard: false }),
    }));
    const group = L.layerGroup(placed.map(({ marker }) => marker)).addTo(map);
    const fit = (zoom) => {
      for (const { doghouse, marker } of placed) {
        const element = marker.getElement();
        if (element) element.style.display = fitsAt(doghouse, zoom) ? "" : "none";
      }
    };
    const now = () => fit(map.getZoom());
    const animating = (event) => fit(event.zoom);
    now();
    map.on("zoom zoomend viewreset", now);
    map.on("zoomanim", animating);
    return () => {
      map.off("zoom zoomend viewreset", now);
      map.off("zoomanim", animating);
      group.remove();
    };
  }, [map, doghouses]);

  return null;
};

export default RouteDoghouseLayer;
