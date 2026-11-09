import { useEffect, useRef, useState } from "react";
import { Marker, Polyline, useMap, useMapEvent } from "react-leaflet";
import L from "leaflet";
import { createMapObjectHandleIcon } from "../../utils/mapObjectControls";
import { calculateBearing } from "../../utils/Helpers";
import { backstopAnchor, backstopPoints, backstopReading, moveBackstopPoints, rotateBackstopPoints } from "./backstops";
import "./backstops.css";

// Drawn with dashes cased in white, so the line reads over imagery as well as over a chart.
const DASHES = "10 7";
const CASING = { color: "#ffffff", weight: 5, opacity: 0.85, dashArray: DASHES, lineCap: "butt" };
const LINE = { color: "#000000", weight: 3, dashArray: DASHES, lineCap: "butt" };
const SHAFT_CASING = { color: "#ffffff", weight: 5, opacity: 0.85 };
const SHAFT = { color: "#000000", weight: 3 };

// How far short of the target the arrow stops (it would otherwise hide under the target's star), and the label beyond the line.
const TARGET_GAP_PX = 16;
const LABEL_OFFSET_PX = 24;

const handleIcon = (type, title) => createMapObjectHandleIcon({ type, title, size: 28, className: "map-screen-only" });
const MOVE_ICON = handleIcon("move", "Drag to move backstop");
const ROTATE_ICON = handleIcon("rotate", "Drag to rotate backstop");

const headIcon = (angle) =>
  L.divIcon({
    className: "backstop-head-anchor",
    iconSize: [20, 20],
    iconAnchor: [20, 10],
    html: `<svg class="backstop-head" width="20" height="20" viewBox="0 0 20 20" style="transform: rotate(${angle}deg)"><polygon points="20,10 2,2 6,10 2,18" /></svg>`,
  });

const labelIcon = (text, dx, dy) =>
  L.divIcon({
    className: "backstop-label-anchor",
    iconSize: [0, 0],
    html: `<div class="backstop-label" style="transform: translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))">${text}</div>`,
  });

/** The arrow on the screen at this zoom: where it stops short of the target, which way it points, and the way back from it. */
const arrowOnScreen = (map, zoom, from, target) => {
  const start = map.project([from.lat, from.lon], zoom);
  const end = map.project(target, zoom);
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  const length = Math.hypot(dx, dy);
  if (length === 0) return null;
  const stop = Math.min(TARGET_GAP_PX, length / 2);
  const ux = dx / length;
  const uy = dy / length;
  return { tip: map.unproject(L.point(end.x - ux * stop, end.y - uy * stop), zoom), angle: (Math.atan2(dy, dx) * 180) / Math.PI, ux, uy };
};

/**
 * One backstop on the map. Without `onChange` (a read-only Mission Pack) it is only shown, and with `passive` (something else is being
 * drawn) it also leaves every click to the map. What is shown while a handle is dragged is local, and goes to the diagram when it is let go.
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
  const points = live ?? saved;
  const reading = backstopReading(points, target);
  if (saved.length < 2 || !reading) return null;

  const arrow = arrowOnScreen(map, zoom, reading.anchor, target);
  const positions = points.map((p) => [p.lat, p.lon]);
  const anchorAt = backstopAnchor(saved);
  const lastSaved = saved[saved.length - 1];
  const last = points[points.length - 1];
  // A handle being dragged follows the finger by itself; only the other one is told where to go.
  const rotateAt = drag.current?.mode === "rotate" ? lastSaved : last;

  const finishDrag = () => {
    const moved = drag.current?.latest;
    drag.current = null;
    setLive(null);
    if (moved && onChange) onChange(data.id, { points: moved });
  };
  const showing = (value) => () => {
    if (!drag.current) setActive(value);
  };
  const hover = { mouseover: showing(true), mouseout: showing(false) };

  const moveHandlers = {
    ...hover,
    dragstart: (e) => {
      drag.current = { mode: "move", from: e.target.getLatLng(), points: saved };
    },
    drag: (e) => {
      const { from, points: start } = drag.current ?? {};
      if (!from) return;
      const at = e.target.getLatLng();
      drag.current.latest = moveBackstopPoints(start, at.lat - from.lat, at.lng - from.lng);
      setLive(drag.current.latest);
    },
    dragend: finishDrag,
  };
  const rotateHandlers = {
    ...hover,
    dragstart: () => {
      drag.current = {
        mode: "rotate",
        about: anchorAt,
        from: calculateBearing(anchorAt.lat, anchorAt.lon, lastSaved.lat, lastSaved.lon),
        points: saved,
      };
    },
    drag: (e) => {
      const { about, from, points: start } = drag.current ?? {};
      if (!about) return;
      const at = e.target.getLatLng();
      const turned = calculateBearing(about.lat, about.lon, at.lat, at.lng) - from;
      drag.current.latest = rotateBackstopPoints(start, about, turned);
      setLive(drag.current.latest);
    },
    dragend: finishDrag,
  };

  return (
    <>
      <Polyline positions={positions} pathOptions={CASING} interactive={false} />
      <Polyline positions={positions} pathOptions={LINE} interactive={false} />
      {arrow && (
        <>
          <Polyline positions={[[reading.anchor.lat, reading.anchor.lon], arrow.tip]} pathOptions={SHAFT_CASING} interactive={false} />
          <Polyline positions={[[reading.anchor.lat, reading.anchor.lon], arrow.tip]} pathOptions={SHAFT} interactive={false} />
          <Marker position={arrow.tip} icon={headIcon(arrow.angle)} interactive={false} keyboard={false} />
          <Marker
            position={[reading.anchor.lat, reading.anchor.lon]}
            icon={labelIcon(reading.text, -arrow.ux * LABEL_OFFSET_PX, -arrow.uy * LABEL_OFFSET_PX)}
            interactive={false}
            keyboard={false}
          />
        </>
      )}
      {editable && (
        <>
          {/* What a finger or the mouse finds: a line this thin would be nearly impossible to hit. */}
          <Polyline
            positions={positions}
            pathOptions={{ opacity: 0, weight: 24 }}
            eventHandlers={{
              ...hover,
              click: (e) => {
                L.DomEvent.stopPropagation(e);
                setActive((value) => !value);
              },
              contextmenu: (e) => {
                if (!onContextMenu) return;
                L.DomEvent.stop(e);
                e.originalEvent.preventDefault();
                onContextMenu(data.id, e.originalEvent.clientX, e.originalEvent.clientY);
              },
            }}
          />
          <Marker position={[anchorAt.lat, anchorAt.lon]} icon={MOVE_ICON} draggable eventHandlers={moveHandlers} opacity={active ? 1 : 0} zIndexOffset={1000} />
          <Marker position={[rotateAt.lat, rotateAt.lon]} icon={ROTATE_ICON} draggable eventHandlers={rotateHandlers} opacity={active ? 1 : 0} zIndexOffset={1000} />
        </>
      )}
    </>
  );
};

export default Backstop;
