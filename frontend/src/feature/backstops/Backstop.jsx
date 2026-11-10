import { useEffect, useMemo, useRef, useState } from "react";
import { Marker, Polyline, useMap, useMapEvent } from "react-leaflet";
import L from "leaflet";
import { createMapObjectHandleIcon, mapObjectControlMarkup } from "../../utils/mapObjectControls";
import { aimBackstopArrow, backstopArrow, backstopPoints, backstopReading, moveBackstopArrow } from "./backstops";
import { BACKSTOP_CASING, BACKSTOP_LINE, BACKSTOP_RED } from "./backstopStyle";
import "./backstops.css";

const SHAFT_CASING = { color: "#ffffff", weight: 5 };
const SHAFT = { color: BACKSTOP_RED, weight: 3 };

// How far short of the target an arrow on it stops (it would otherwise hide under the target's star), how far back from its point the shaft
// ends (inside the head, so the shaft's width never shows past the head's narrow point), and the label beyond the arrow's tail.
const TARGET_GAP_PX = 16;
const SHAFT_STOP_PX = 10;
const LABEL_OFFSET_PX = 24;

const TIP_ICON = createMapObjectHandleIcon({ type: "rotate", title: "Drag to change the arrow's length and heading", size: 28, className: "map-screen-only" });

const headIcon = (angle) =>
  L.divIcon({
    className: "backstop-head-anchor",
    iconSize: [20, 20],
    iconAnchor: [20, 10],
    html: `<svg class="backstop-head" width="20" height="20" viewBox="0 0 20 20" style="transform: rotate(${angle}deg)"><polygon points="20,10 2,2 6,10 2,18" fill="${BACKSTOP_RED}" /></svg>`,
  });

const labelIcon = (text, dx, dy) =>
  L.divIcon({
    className: "backstop-label-anchor",
    iconSize: [0, 0],
    html: `<div class="backstop-label" style="transform: translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))">${text}</div>`,
  });

/** What a finger or the mouse finds along the arrow (a line this thin would be nearly impossible to hit), and the move handle at its middle. */
const gripIcon = (length, angle) =>
  L.divIcon({
    className: "map-object-handle-icon map-screen-only",
    iconSize: [0, 0],
    html: `<div class="backstop-grip" style="width: ${length}px; transform: translate(-50%, -50%) rotate(${angle}deg)"></div>
      <div class="backstop-grip-handle">${mapObjectControlMarkup({ type: "move", title: "Drag to move the arrow", size: 28 })}</div>`,
  });

/** The arrow on the screen at this zoom: how far it is drawn, where its parts go, which way it points, and the way back from it. */
const arrowOnScreen = (map, zoom, { tail, tip }, target) => {
  const start = map.project([tail.lat, tail.lon], zoom);
  const end = map.project([tip.lat, tip.lon], zoom);
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const length = Math.hypot(dx, dy);
  if (length === 0) return null;
  const ux = dx / length;
  const uy = dy / length;
  const along = (px) => map.unproject(L.point(start.x + ux * px, start.y + uy * px), zoom);
  // Less of a gap the further the tip is from the target, so an arrow does not jump as it is picked up from there.
  const gap = Math.min(Math.max(0, TARGET_GAP_PX - end.distanceTo(map.project(target, zoom))), length / 2);
  const drawn = length - gap;
  return {
    head: along(drawn),
    shaftEnd: along(Math.max(0, drawn - SHAFT_STOP_PX)),
    middle: along(drawn / 2),
    length: drawn,
    angle: (Math.atan2(dy, dx) * 180) / Math.PI,
    ux,
    uy,
  };
};

const placeOfMarker = (marker) => {
  const { lat, lng } = marker.getLatLng();
  return { lat, lon: lng };
};

/**
 * One backstop on the map. Without `onChange` (a read-only Mission Pack) it is only shown, and with `passive` (something else is being
 * drawn) it also leaves every click to the map. What is shown while the arrow is dragged is local, and goes to the diagram when it is let go.
 */
const Backstop = ({ data, target, onChange, onContextMenu, passive = false }) => {
  const map = useMap();
  const [zoom, setZoom] = useState(() => map.getZoom());
  useMapEvent("zoomend", () => setZoom(map.getZoom()));

  const [live, setLive] = useState(null);
  const [active, setActive] = useState(false);
  const drag = useRef(null);
  const editable = Boolean(onChange) && !passive;

  // A pack that turns read-only mid-drag takes the handles away, and their dragend with them.
  useEffect(() => {
    if (!editable) {
      drag.current = null;
      setLive(null);
    }
  }, [editable]);

  const saved = backstopPoints(data);
  const arrow = backstopArrow(live ? { ...data, arrow: live.arrow } : data, target);
  const onScreen = arrow ? arrowOnScreen(map, zoom, arrow, target) : null;
  // Made again only when the arrow's shape changes, since it is what the mouse is over, and never during its own drag: a marker given a new
  // icon is given a new drag handler too, and the drag in hand stops with no dragend (the same arrow is a few pixels longer further north).
  const gripLength = live?.grip?.length ?? Math.round(onScreen?.length ?? 0);
  const gripAngle = live?.grip?.angle ?? Math.round(onScreen?.angle ?? 0);
  const grip = useMemo(() => gripIcon(gripLength, gripAngle), [gripLength, gripAngle]);

  if (saved.length < 2 || !arrow) return null;

  const reading = backstopReading(arrow);
  const positions = saved.map((p) => [p.lat, p.lon]);
  const shaft = onScreen ? [[arrow.tail.lat, arrow.tail.lon], onScreen.shaftEnd] : [];
  // React-leaflet puts a marker back at its position on every render, so the one being dragged is told where the finger is.
  const handleAt = (mode, place) => (live?.mode === mode ? live.at : place);

  const follow = (at, moved) => {
    drag.current.latest = moved;
    setLive({ mode: drag.current.mode, at: [at.lat, at.lon], arrow: moved, grip: drag.current.grip });
  };
  const finishDrag = () => {
    const { latest, start } = drag.current ?? {};
    drag.current = null;
    setLive(null);
    // A tip picked up from the target and put back on it has changed nothing.
    if (latest && onChange && (latest.tip || !start.onTarget)) onChange(data.id, { arrow: latest });
  };
  const showing = (value) => () => {
    if (!drag.current) setActive(value);
  };
  const hover = { mouseover: showing(true), mouseout: showing(false) };
  const openMenu = (e) => {
    if (!onContextMenu) return;
    L.DomEvent.stop(e);
    e.originalEvent.preventDefault();
    onContextMenu(data.id, e.originalEvent.clientX, e.originalEvent.clientY);
  };

  const gripHandlers = {
    ...hover,
    contextmenu: openMenu,
    click: () => setActive((value) => !value),
    dragstart: (e) => {
      drag.current = { mode: "move", from: placeOfMarker(e.target), start: arrow, grip: { length: gripLength, angle: gripAngle } };
    },
    drag: (e) => {
      if (!drag.current) return;
      const { from, start } = drag.current;
      const at = placeOfMarker(e.target);
      follow(at, moveBackstopArrow(start, at.lat - from.lat, at.lon - from.lon));
    },
    dragend: finishDrag,
  };
  const tipHandlers = {
    ...hover,
    contextmenu: openMenu,
    dragstart: () => {
      drag.current = { mode: "tip", start: arrow };
    },
    drag: (e) => {
      if (!drag.current) return;
      const at = placeOfMarker(e.target);
      const finger = map.project([at.lat, at.lon], zoom);
      const aimed = aimBackstopArrow(drag.current.start, at, target, (place) => map.project([place.lat, place.lon], zoom).distanceTo(finger));
      if (aimed) follow(at, aimed);
    },
    dragend: finishDrag,
  };

  return (
    <>
      <Polyline positions={positions} pathOptions={BACKSTOP_CASING} interactive={false} />
      <Polyline positions={positions} pathOptions={BACKSTOP_LINE} interactive={false} />
      {onScreen && (
        <>
          <Polyline positions={shaft} pathOptions={SHAFT_CASING} interactive={false} />
          <Polyline positions={shaft} pathOptions={SHAFT} interactive={false} />
          <Marker position={onScreen.head} icon={headIcon(onScreen.angle)} interactive={false} keyboard={false} />
          <Marker
            position={[arrow.tail.lat, arrow.tail.lon]}
            icon={labelIcon(reading.text, -onScreen.ux * LABEL_OFFSET_PX, -onScreen.uy * LABEL_OFFSET_PX)}
            interactive={false}
            keyboard={false}
          />
        </>
      )}
      {/* The line only answers a right-click: it stays where it was sketched. */}
      {editable && <Polyline positions={positions} pathOptions={{ opacity: 0, weight: 24 }} eventHandlers={{ contextmenu: openMenu }} />}
      {editable && onScreen && (
        <>
          {/* Under everything else in the marker pane: the arrow ends among the LZ/PZ's graphics, which must stay in reach. */}
          <Marker position={handleAt("move", onScreen.middle)} icon={grip} draggable eventHandlers={gripHandlers} opacity={active ? 1 : 0} zIndexOffset={-1000} />
          <Marker position={handleAt("tip", onScreen.head)} icon={TIP_ICON} draggable eventHandlers={tipHandlers} opacity={active ? 1 : 0} zIndexOffset={1000} />
        </>
      )}
    </>
  );
};

export default Backstop;
