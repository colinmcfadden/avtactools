import L from "leaflet";
import React, { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMap, useMapEvents } from "react-leaflet";
import { chartWords, nextExpiry } from "./vfrCharts";
import "./VfrAttribution.css";

// setTimeout's longest wait; a cycle is 56 days, so one run out further off is simply looked at again after this.
const LONGEST_WAIT_MS = 2 ** 31 - 1;

const placeOf = (map) => {
  const bounds = map.getBounds();
  const center = map.getCenter();
  return {
    view: { west: bounds.getWest(), south: bounds.getSouth(), east: bounds.getEast(), north: bounds.getNorth() },
    center: { lat: center.lat, lon: center.lng },
    zoom: map.getZoom(),
    now: Date.now(),
  };
};

/**
 * What the VFR map says of its charts (docs/VFR_CHARTS.md §4.5), in a Leaflet control of its own just above the map's attribution:
 * so it sits where attributions do on every screen, and like them is left off an LZ card.
 */
export default function VfrAttribution({ charts }) {
  const { known, live, coverage, finer } = charts;
  const map = useMap();
  const [host, setHost] = useState(null);
  const [place, setPlace] = useState(() => placeOf(map));
  const update = () => setPlace(placeOf(map));

  useMapEvents({ moveend: update, zoomend: update, resize: update });

  useEffect(() => {
    const control = new L.Control({ position: "bottomright" });
    control.onAdd = () => L.DomUtil.create("div", "vfr-attribution");
    control.addTo(map);
    setHost(control.getContainer());
    return () => control.remove();
  }, [map]);

  // A chart turns red at its expiry instant, whether or not anyone moves the map then.
  useEffect(() => {
    const at = nextExpiry(live, coverage, place.now);
    if (at == null) return undefined;
    const timer = setTimeout(update, Math.min(at - Date.now() + 1, LONGEST_WAIT_MS));
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, coverage, place.now]);

  if (!host || !known) return null;
  const { lines, enlarged } = chartWords({ live, coverage, finer, ...place });
  return createPortal(
    <>
      {enlarged && <div className="vfr-attribution__enlarged">{enlarged}</div>}
      {lines.map(({ text, expired }) => (
        <div key={text} className={expired ? "vfr-attribution__expired" : undefined}>
          {text}
        </div>
      ))}
    </>,
    host,
  );
}
