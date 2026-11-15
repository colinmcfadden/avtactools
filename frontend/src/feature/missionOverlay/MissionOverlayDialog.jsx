import React, { useId, useMemo, useState } from "react";
import api from "../auth/api";
import Dialog from "../ui/Dialog";
import { BusyButton, ProgressBar } from "../ui/Spinner";
import { blobError, downloadBlob } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, shownBounds } from "./captureOverlay";
import {
  describeArea,
  describePixels,
  describeResolution,
  describeScale,
  FORMATS,
  GEOTIFF_SIDE,
  layerName,
  MAX_UPLOAD_BYTES,
  overlayFileName,
  overlayPlans,
  USGS_MAX_ZOOM,
} from "./overlayPlan";

/*
 * Mission overlay (docs/USER_GUIDE.md): everything planned on the map as one layer for ForeFlight, in place of exporting
 * threats, routes and LZ/PZs one by one. The area is the whole mission or the map as shown; the base map is none (the
 * overlay lies over ForeFlight's own charts) or USGS's topo map, fetched by the server at its own resolution; the detail
 * is as fine as the file allows, or a level lighter; the file is MBTiles (any ForeFlight subscription) or a GeoTIFF
 * (Military Flight Bag, and other apps). The map is drawn in pieces, so a phone draws as much detail as a computer.
 */

const Choice = ({ name, checked, onChange, title, text, disabled }) => (
  <label className={`ui-choice${checked ? " ui-choice--selected" : ""}`} style={disabled ? { opacity: 0.55, cursor: "default" } : undefined}>
    <input type="radio" name={name} checked={checked} onChange={onChange} disabled={disabled} />
    <span style={{ minWidth: 0 }}>
      <span className="ui-choice__title">{title}</span>
      <span className="ui-choice__text">{text}</span>
    </span>
  </label>
);

const Group = ({ id, label, children, style }) => (
  <div className="ui-field" style={{ marginTop: 14, ...style }}>
    <div className="ui-field__label" id={id}>{label}</div>
    <div role="radiogroup" aria-labelledby={id} style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: 8 }}>
      {children}
    </div>
  </div>
);

const megabytes = (bytes) => `${Math.ceil(bytes / (1024 * 1024))} MB`;

/** "About 1:20,000 · 2.0 m a pixel · 9,800 × 7,400 pixels" */
const describePlan = (plan) =>
  `About ${describeScale(plan.metresPerPixel)} · ${describeResolution(plan.metresPerPixel).replace(/^about /, "")} · ${describePixels(plan)} pixels`;

const MissionOverlayDialog = ({ map, defaultName = "MISSION OVERLAY", mapStyle, onClose, notify }) => {
  const ids = useId();
  // Read once as it opens: the dialog covers the map, so nothing moves under it.
  const areas = useMemo(() => ({ mission: missionBounds(map), shown: shownBounds(map) }), [map]);
  const [area, setArea] = useState(areas.mission ? "mission" : "shown");
  const [base, setBase] = useState(mapStyle === "usgs-topo" ? "usgs-topo" : "none");
  const [detail, setDetail] = useState("finest");
  const [format, setFormat] = useState("mbtiles");
  const [name, setName] = useState(defaultName);
  const [stage, setStage] = useState(null);
  const [drawn, setDrawn] = useState({ done: 0, total: 0 });
  const [error, setError] = useState(null);

  const bounds = areas[area];
  const mapMaxZoom = map.getMaxZoom();
  const plans = useMemo(() => overlayPlans(bounds, { format, base, mapMaxZoom }), [bounds, format, base, mapMaxZoom]);
  const chosen = detail === "lighter" && plans.lighter ? "lighter" : "finest";
  const plan = plans[chosen];

  const make = async () => {
    setError(null);
    setStage("drawing");
    setDrawn({ done: 0, total: plan.pieces });
    let pieces;
    try {
      pieces = await captureOverlay(map, plan, { onPiece: (done, total) => setDrawn({ done, total }) });
    } catch {
      setStage(null);
      setError("The map could not be drawn into the overlay. Try again.");
      return;
    }
    const size = pieces.reduce((sum, piece) => sum + piece.blob.size, 0);
    if (size > MAX_UPLOAD_BYTES) {
      setStage(null);
      setError(`What is drawn comes to ${megabytes(size)}, more than can be sent. Choose less detail or a smaller area.`);
      return;
    }
    setStage("making");
    try {
      const form = new FormData();
      form.append("format", format);
      form.append("base", base);
      form.append("name", layerName(name));
      for (const key of ["zoom", "left", "top", "width", "height"]) form.append(key, String(plan[key]));
      for (const piece of pieces) form.append(`piece-${piece.column}-${piece.row}`, piece.blob, `piece-${piece.column}-${piece.row}.png`);
      const answer = await api.post("/overlay-image", form, { responseType: "blob" });
      const fileName = overlayFileName(name, format);
      downloadBlob(answer.data, fileName);
      notify?.({ tone: "ok", message: `${fileName} is in your downloads. Open it with ForeFlight from Files${format === "mbtiles" ? " or AirDrop" : ""}.` });
      onClose();
    } catch (err) {
      setError(await blobError(err, "The overlay could not be made. Try again."));
    } finally {
      setStage(null);
    }
  };

  const busy = stage !== null;
  const working =
    stage === "drawing"
      ? `Drawing piece ${Math.min(drawn.done + 1, drawn.total)} of ${drawn.total}…`
      : stage === "making"
        ? base === "usgs-topo"
          ? "Fetching the USGS map and making the file…"
          : "Making the file…"
        : null;
  const finestText =
    plans.limitedBy === "base"
      ? `${describePlan(plans.finest)}. The USGS topo map has no more detail than this.`
      : plans.limitedBy === "size"
        ? `${describePlan(plans.finest)}. As much as ${format === "geotiff" ? `a GeoTIFF of ${GEOTIFF_SIDE.toLocaleString("en-US")} pixels a side` : "an MBTiles file"} holds for this area.`
        : describePlan(plans.finest);
  return (
    <Dialog
      title="Mission overlay"
      subtitle="What is planned on the map as one layer for ForeFlight, over its own charts or on the USGS topo map."
      icon="image"
      width="xwide"
      onClose={busy ? undefined : onClose}
      dismissible={!busy}
      note="What is drawn goes to the server once, to make the file. Nothing is kept."
      footer={
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" className="ui-btn" onClick={onClose} disabled={busy}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary" icon="download" busy={busy} onClick={make}>
            <span>{working ?? `Download .${FORMATS[format].extension}`}</span>
          </BusyButton>
        </div>
      }
    >
      <Group id={`${ids}-area`} label="Area" style={{ marginTop: 0 }}>
        <Choice
          name={`${ids}-area`}
          checked={area === "mission"}
          onChange={() => setArea("mission")}
          disabled={!areas.mission || busy}
          title="The whole mission"
          text={areas.mission ? `Everything planned on the map · ${describeArea(areas.mission)}` : "Nothing is planned on the map yet."}
        />
        <Choice
          name={`${ids}-area`}
          checked={area === "shown"}
          onChange={() => setArea("shown")}
          disabled={busy}
          title="The map as shown"
          text={`What the screen shows now · ${describeArea(areas.shown)}`}
        />
      </Group>

      <Group id={`${ids}-base`} label="Base map">
        <Choice
          name={`${ids}-base`}
          checked={base === "none"}
          onChange={() => setBase("none")}
          disabled={busy}
          title="None"
          text="Only what is planned, see-through, over whichever chart ForeFlight shows under it."
        />
        <Choice
          name={`${ids}-base`}
          checked={base === "usgs-topo"}
          onChange={() => setBase("usgs-topo")}
          disabled={busy}
          title="USGS topo"
          text={`The USGS topographic map under it, down to about ${describeScale(2)} (level ${USGS_MAX_ZOOM}). United States only; ForeFlight's chart does not show through.`}
        />
      </Group>

      <Group id={`${ids}-detail`} label="Detail">
        <Choice
          name={`${ids}-detail`}
          checked={chosen === "finest"}
          onChange={() => setDetail("finest")}
          disabled={busy}
          title="Finest"
          text={finestText}
        />
        <Choice
          name={`${ids}-detail`}
          checked={chosen === "lighter"}
          onChange={() => setDetail("lighter")}
          disabled={!plans.lighter || busy}
          title="Lighter"
          text={plans.lighter ? `${describePlan(plans.lighter)}. A quarter the size, quicker to make and send.` : "The area is as coarse as it can be drawn."}
        />
      </Group>

      <Group id={`${ids}-format`} label="File">
        <Choice
          name={`${ids}-format`}
          checked={format === "mbtiles"}
          onChange={() => setFormat("mbtiles")}
          disabled={busy}
          title="MBTiles"
          text="ForeFlight's map layer file, sharp at every zoom. Works on every subscription; AirDrop it or open it from Files."
        />
        <Choice
          name={`${ids}-format`}
          checked={format === "geotiff"}
          onChange={() => setFormat("geotiff")}
          disabled={busy}
          title="GeoTIFF"
          text={`One image in latitude and longitude, at most ${GEOTIFF_SIDE.toLocaleString("en-US")} pixels a side. ForeFlight takes it with Military Flight Bag (Files or email: AirDrop puts it in Photos); ATAK and GIS apps open it too.`}
        />
      </Group>

      <div className="ui-field" style={{ marginTop: 14 }}>
        <label htmlFor={`${ids}-name`} className="ui-field__label">Layer name</label>
        <input
          id={`${ids}-name`}
          type="text"
          className="ui-input"
          value={name}
          maxLength={60}
          disabled={busy}
          onChange={(event) => setName(event.target.value)}
        />
        <div className="ui-field__hint">What ForeFlight lists it as in Map Layers, and the file's name.</div>
      </div>

      <div className="ui-field__hint" style={{ marginTop: 14 }}>
        In it: everything drawn on the map but the base map on the screen and the MGRS grid. Hide what should not go in first.
        It is a picture: nothing in it can be selected or changed in ForeFlight, and labels grow and shrink as ForeFlight zooms.
      </div>
      {stage === "drawing" && drawn.total > 1 && (
        <div style={{ marginTop: 12 }}>
          <ProgressBar done={drawn.done} total={drawn.total} label="Pieces of the overlay drawn" />
        </div>
      )}
      {error && <div role="alert" className="ui-field__hint ui-field__hint--error">{error}</div>}
    </Dialog>
  );
};

export default MissionOverlayDialog;
