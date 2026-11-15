import React, { useId, useMemo, useState } from "react";
import api from "../auth/api";
import Dialog from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
import { blobError, downloadBlob } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, shownBounds } from "./captureOverlay";
import { capturePlan, describeArea, describePixels, describeResolution, DETAIL, FORMATS, layerName, overlayFileName } from "./overlayPlan";

/*
 * Mission overlay (docs/USER_GUIDE.md): everything planned on the map, as one image ForeFlight lays over its own charts, in
 * place of exporting threats, routes and LZ/PZs one by one. The area is the whole mission or the map as shown; the detail
 * decides how sharp it is; the file is MBTiles (any ForeFlight subscription) or a GeoTIFF (Military Flight Bag, and other
 * apps). The base map and the MGRS grid are left out, so it sits on whichever chart the crew has under it.
 */

// A phone's or tablet's browser cannot hold a canvas the size of High's (iOS stops at about 16.7 million pixels).
const coarsePointer = () => typeof window !== "undefined" && window.matchMedia?.("(pointer: coarse)").matches === true;

const Choice = ({ name, checked, onChange, title, text, disabled }) => (
  <label className={`ui-choice${checked ? " ui-choice--selected" : ""}`} style={disabled ? { opacity: 0.55, cursor: "default" } : undefined}>
    <input type="radio" name={name} checked={checked} onChange={onChange} disabled={disabled} />
    <span style={{ minWidth: 0 }}>
      <span className="ui-choice__title">{title}</span>
      <span className="ui-choice__text">{text}</span>
    </span>
  </label>
);

const MissionOverlayDialog = ({ map, defaultName = "MISSION OVERLAY", onClose, notify }) => {
  const ids = useId();
  // Read once as it opens: the dialog covers the map, so nothing moves under it.
  const areas = useMemo(() => ({ mission: missionBounds(map), shown: shownBounds(map) }), [map]);
  const [area, setArea] = useState(areas.mission ? "mission" : "shown");
  const highOff = useMemo(coarsePointer, []);
  const [detail, setDetail] = useState("standard");
  const [format, setFormat] = useState("mbtiles");
  const [name, setName] = useState(defaultName);
  const [stage, setStage] = useState(null);
  const [error, setError] = useState(null);

  const bounds = areas[area];
  const maxZoom = map.getMaxZoom();
  const plans = useMemo(
    () => ({ standard: capturePlan(bounds, "standard", maxZoom), high: capturePlan(bounds, "high", maxZoom) }),
    [bounds, maxZoom],
  );
  // At the deepest zoom both details are one image: High would only be larger.
  const sameDetail = plans.standard.zoom === plans.high.zoom;
  const chosen = sameDetail ? "standard" : detail;

  const make = async () => {
    setError(null);
    setStage("drawing");
    let shot;
    try {
      shot = await captureOverlay(map, bounds, chosen);
    } catch {
      setStage(null);
      setError(
        chosen === "high"
          ? "This browser could not draw an image that large. Choose Standard detail and try again."
          : "The map could not be drawn into an image. Try again.",
      );
      return;
    }
    setStage("making");
    try {
      const form = new FormData();
      form.append("image", shot.blob, "overlay.png");
      for (const key of ["north", "south", "east", "west"]) form.append(key, String(shot.bounds[key]));
      form.append("format", format);
      form.append("name", layerName(name));
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
  return (
    <Dialog
      title="Mission overlay"
      subtitle="One image of what is planned on the map, for ForeFlight to lay over its own charts."
      icon="image"
      width="xwide"
      onClose={busy ? undefined : onClose}
      dismissible={!busy}
      note="The image goes to the server once, to make the file. Nothing is kept."
      footer={
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" className="ui-btn" onClick={onClose} disabled={busy}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary" icon="download" busy={busy} onClick={make}>
            <span>{stage === "drawing" ? "Drawing the map…" : stage === "making" ? "Making the file…" : `Download .${FORMATS[format].extension}`}</span>
          </BusyButton>
        </div>
      }
    >
      <div className="ui-field">
        <div className="ui-field__label" id={`${ids}-area`}>Area</div>
        <div role="radiogroup" aria-labelledby={`${ids}-area`} style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: 8 }}>
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
        </div>
      </div>

      <div className="ui-field" style={{ marginTop: 14 }}>
        <div className="ui-field__label" id={`${ids}-detail`}>Detail</div>
        <div role="radiogroup" aria-labelledby={`${ids}-detail`} style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: 8 }}>
          {Object.entries(DETAIL).map(([key, entry]) => {
            const off = key === "high" && (highOff || sameDetail);
            return (
              <Choice
                key={key}
                name={`${ids}-detail`}
                checked={chosen === key}
                onChange={() => setDetail(key)}
                disabled={off || busy}
                title={entry.title}
                text={
                  key === "high" && highOff
                    ? "On a computer: too large for a phone's or tablet's browser to draw."
                    : key === "high" && sameDetail
                      ? "The area is small enough that Standard is already as sharp as it gets."
                      : `${describePixels(plans[key])} pixels, ${describeResolution(plans[key].metresPerPixel)}`
                }
              />
            );
          })}
        </div>
      </div>

      <div className="ui-field" style={{ marginTop: 14 }}>
        <div className="ui-field__label" id={`${ids}-format`}>File</div>
        <div role="radiogroup" aria-labelledby={`${ids}-format`} style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: 8 }}>
          <Choice
            name={`${ids}-format`}
            checked={format === "mbtiles"}
            onChange={() => setFormat("mbtiles")}
            disabled={busy}
            title="MBTiles"
            text="ForeFlight's map layer file. Works on every subscription; AirDrop it or open it from Files."
          />
          <Choice
            name={`${ids}-format`}
            checked={format === "geotiff"}
            onChange={() => setFormat("geotiff")}
            disabled={busy}
            title="GeoTIFF"
            text="One image on a map grid. ForeFlight takes it with Military Flight Bag (Files or email: AirDrop puts it in Photos); ATAK and GIS apps open it too."
          />
        </div>
      </div>

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
        In it: everything drawn on the map but the base map and the MGRS grid. Hide what should not go in first. It is a picture:
        nothing in it can be selected or changed in ForeFlight, and labels grow and shrink as ForeFlight zooms.
      </div>
      {error && <div role="alert" className="ui-field__hint ui-field__hint--error">{error}</div>}
    </Dialog>
  );
};

export default MissionOverlayDialog;
