import L from "leaflet";
import React, { useMemo } from "react";
import { Marker, useMapEvents } from "react-leaflet";
import { colorFor, shortName } from "../../ui/Avatar";
import "./packs.css";

/*
 * Who else in the open pack is pointing where (screen PackLive): a flag in their colour at their
 * pointer, named. Only people who have this pack open are here, because only they send a pointer.
 * The flags take no clicks. This person's own pointer is reported through `onPoint` as they move it
 * over the map (the pack client sends it at most ten times a second), and null when it leaves.
 */

const escape = (text) =>
  String(text ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]);

const icons = new Map();
const flagFor = (person) => {
  const label = shortName(person.name);
  const color = colorFor(person.user_id ?? person.name);
  const key = `${label}|${color}`;
  if (!icons.has(key)) {
    icons.set(
      key,
      L.divIcon({
        className: "packs-cursor",
        iconSize: [0, 0],
        html: `<div class="packs-cursor__flag"><span class="packs-cursor__pin" style="background:${color}"></span><span class="packs-cursor__label" style="background:${color}">${escape(label)}</span></div>`,
      }),
    );
  }
  return icons.get(key);
};

const Pointer = ({ onPoint }) => {
  useMapEvents({
    mousemove: (event) => onPoint(event.latlng),
    mouseout: () => onPoint(null),
  });
  return null;
};

const PresenceLayer = ({ people = [], onPoint }) => {
  const placed = useMemo(
    () => people.filter((p) => Array.isArray(p.focus?.at) && p.focus.at.length === 2 && p.focus.at.every(Number.isFinite)),
    [people],
  );
  return (
    <>
      {onPoint && <Pointer onPoint={onPoint} />}
      {placed.map((person) => (
        <Marker
          key={person.session ?? person.user_id}
          position={person.focus.at}
          icon={flagFor(person)}
          interactive={false}
          keyboard={false}
          zIndexOffset={2000}
        />
      ))}
    </>
  );
};

export default PresenceLayer;
