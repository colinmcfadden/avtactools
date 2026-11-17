import { useMemo, useState } from "react";
import { Marker, Polyline, useMap, useMapEvent } from "react-leaflet";
import L from "leaflet";
import { createMapObjectHandleIcon } from "../../utils/mapObjectControls";
import LineHandles, { useLineEditing } from "../setLines/LineHandles";
import { flotLabelSpot, scallopRuns } from "./flots";
import "./flot.css";

const FLOT_COLOR = "#2f8fff";
const CASING = { color: "#06121f", weight: 5, opacity: 0.85, lineCap: "round", lineJoin: "round" };
const LINE = { color: FLOT_COLOR, weight: 2.5, lineCap: "round", lineJoin: "round" };

// How far past the view scallops are worked out, so a pan shows them before it ends and they are worked out again.
const VIEW_MARGIN_PX = 500;
const LABEL_OFFSET_PX = 15;

const HANDLE_ICON = createMapObjectHandleIcon({ type: "move", title: "Drag to move this point of the FLOT", size: 26, className: "map-screen-only" });

const labelIcon = (dx, dy) =>
  L.divIcon({
    className: "flot-label-anchor",
    iconSize: [0, 0],
    html: `<div class="flot-label" style="transform: translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))">FLOT</div>`,
  });

/**
 * One FLOT on the map. Without `onChange` (a read-only Mission Pack) it is only shown, and with `passive` (something else is being drawn)
 * it also leaves every click to the map. A point being dragged is local, and goes to the set when it is let go.
 */
const Flot = ({ data, onChange, onContextMenu, passive = false }) => {
  const map = useMap();
  // Every zoom ends with a moveend too.
  const [view, setView] = useState(0);
  useMapEvent("moveend", () => setView((n) => n + 1));

  const editable = Boolean(onChange) && !passive;
  const editing = useLineEditing({ data, onChange, editable });
  const { saved, points } = editing;
  const flipped = Boolean(data.flipped);

  const drawn = useMemo(() => {
    const zoom = map.getZoom();
    const pixels = points.map((p) => map.project([p.lat, p.lon], zoom));
    const box = map.getPixelBounds();
    const within = {
      minX: box.min.x - VIEW_MARGIN_PX,
      minY: box.min.y - VIEW_MARGIN_PX,
      maxX: box.max.x + VIEW_MARGIN_PX,
      maxY: box.max.y + VIEW_MARGIN_PX,
    };
    const runs = scallopRuns(pixels, { flipped, within }).map((run) => run.map((pixel) => map.unproject(L.point(pixel.x, pixel.y), zoom)));
    const spot = flotLabelSpot(pixels, flipped);
    return { runs, label: spot && { at: map.unproject(L.point(spot.x, spot.y), zoom), dx: spot.awayX * LABEL_OFFSET_PX, dy: spot.awayY * LABEL_OFFSET_PX } };
    // `view` is what says the map moved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, view, points, flipped]);

  const icon = useMemo(() => (drawn.label ? labelIcon(drawn.label.dx, drawn.label.dy) : null), [drawn.label?.dx, drawn.label?.dy]); // eslint-disable-line react-hooks/exhaustive-deps

  if (saved.length < 2) return null;

  return (
    <>
      {drawn.runs.length > 0 && (
        <>
          <Polyline positions={drawn.runs} pathOptions={CASING} interactive={false} />
          <Polyline positions={drawn.runs} pathOptions={LINE} interactive={false} />
        </>
      )}
      {drawn.label && icon && <Marker position={drawn.label.at} icon={icon} interactive={false} keyboard={false} />}
      {editable && <LineHandles id={data.id} editing={editing} icon={HANDLE_ICON} onContextMenu={onContextMenu} />}
    </>
  );
};

export default Flot;
