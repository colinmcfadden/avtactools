import { useMemo } from "react";
import { Marker, Polyline, useMap } from "react-leaflet";
import L from "leaflet";
import { createMapObjectHandleIcon } from "../../utils/mapObjectControls";
import LineHandles, { useLineEditing } from "../setLines/LineHandles";
import { labelShift, phaseLineEnds, phaseLineLabel } from "./phaseLines";
import { PHASE_LINE_CASING, PHASE_LINE_COLOR } from "./phaseLineStyle";
import "./phaseLines.css";

const CASING = { color: PHASE_LINE_CASING, weight: 8, opacity: 0.92, lineCap: "round", lineJoin: "round" };
const LINE = { color: PHASE_LINE_COLOR, weight: 3.5, lineCap: "round", lineJoin: "round" };

// Which way a line leaves its end is the same on the screen at every zoom, so it is worked out at one where no two points share a pixel.
const DIRECTION_ZOOM = 18;
const LABEL_GAP_PX = 5;

const HANDLE_ICON = createMapObjectHandleIcon({ type: "move", title: "Drag to move this point of the phase line", size: 26, className: "map-screen-only" });

// The label is made as an element, never as markup: its name is typed by a person, and in a Mission Pack by anyone in it.
const labelIcon = (text, end) => {
  const { fx, fy } = labelShift(end);
  const label = document.createElement("div");
  label.className = "phase-line-label";
  label.textContent = text;
  label.style.transform = `translate(calc(-50% + ${(fx * 50).toFixed(2)}% + ${(end.ux * LABEL_GAP_PX).toFixed(2)}px), calc(-50% + ${(fy * 50).toFixed(2)}% + ${(end.uy * LABEL_GAP_PX).toFixed(2)}px))`;
  return L.divIcon({ className: "phase-line-label-anchor", iconSize: [0, 0], html: label });
};

/**
 * One phase line on the map: a solid line with `PL <NAME>` beyond each end. Without `onChange` (a read-only Mission Pack) it is only
 * shown, and with `passive` (something else is being drawn) it also leaves every click to the map. A point being dragged is local,
 * and goes to the set when it is let go.
 */
const PhaseLine = ({ data, onChange, onContextMenu, passive = false }) => {
  const map = useMap();
  const editable = Boolean(onChange) && !passive;
  const editing = useLineEditing({ data, onChange, editable });
  const { saved, points } = editing;
  const text = phaseLineLabel(data);

  const ends = phaseLineEnds(points.map((p) => map.project([p.lat, p.lon], DIRECTION_ZOOM)));
  // An icon made anew is a label made anew: only when its words or the way it faces change.
  const facing = ends.map((end) => `${end.ux.toFixed(3)},${end.uy.toFixed(3)}`).join(";");
  const icons = useMemo(() => ends.map((end) => labelIcon(text, end)), [text, facing]); // eslint-disable-line react-hooks/exhaustive-deps

  if (saved.length < 2) return null;

  const positions = points.map((p) => [p.lat, p.lon]);
  const labelled = icons.length === 2 ? [points[0], points[points.length - 1]] : [];

  return (
    <>
      <Polyline positions={positions} pathOptions={CASING} interactive={false} />
      <Polyline positions={positions} pathOptions={LINE} interactive={false} />
      {labelled.map((point, i) => (
        // eslint-disable-next-line react/no-array-index-key
        <Marker key={i} position={[point.lat, point.lon]} icon={icons[i]} interactive={false} keyboard={false} />
      ))}
      {editable && <LineHandles id={data.id} editing={editing} icon={HANDLE_ICON} onContextMenu={onContextMenu} />}
    </>
  );
};

export default PhaseLine;
