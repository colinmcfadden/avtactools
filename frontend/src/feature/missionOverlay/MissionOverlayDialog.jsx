import React, { useEffect, useId, useMemo, useRef, useState } from "react";
import api from "../auth/api";
import Dialog from "../ui/Dialog";
import { BusyButton, ProgressBar } from "../ui/Spinner";
import { blobError, downloadUrl } from "../../utils/fileDownload";
import { captureOverlay, missionBounds, piecesWithContent, shownBounds } from "./captureOverlay";
import {
  BATCH_BYTES,
  describeArea,
  describeBytes,
  describeMinutes,
  describePixels,
  describeResolution,
  describeScale,
  estimateOverlay,
  FORMATS,
  GEOTIFF_SIDE,
  layerName,
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
 *
 * The server makes it in the background (backend/overlay_jobs.py): a whole operation on the USGS map at its own level 16 is
 * tens of thousands of tiles, ten minutes and more, where a request may wait 100 s. So a job is made, the pieces with
 * something in them are drawn and sent a batch at a time (the rest are see-through, or the USGS map alone), the job is
 * started and asked how it stands every POLL_MS until its file is made, which is downloaded from a link of its own. A
 * stop, or the dialog closing, stops the job on the server too.
 */

const POLL_MS = 2000;

const stopped = () => new DOMException("The overlay was stopped.", "AbortError");

const pause = (ms, signal) =>
  new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(stopped());
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        reject(stopped());
      },
      { once: true },
    );
  });

/** What the server said, else `fallback`; the app's own words where no answer came, or the job was lost. */
const jobError = async (err, fallback) => {
  if (err?.response?.status === 404) return "The server lost the overlay (it may have restarted). Try again.";
  return blobError(err, fallback);
};

/** "Fetching the USGS map: tile 1,200 of 41,500" and the like, for how the job stands on the server. */
const describeJob = (job, format) => {
  if (!job) return "Starting…";
  const count = (value) => Number(value ?? 0).toLocaleString("en-US");
  if (job.state === "receiving") return "Sending…";
  if (job.state === "queued") {
    return job.ahead > 0 ? `Waiting for ${job.ahead} other overlay${job.ahead === 1 ? "" : "s"} to be made…` : "Waiting to be made…";
  }
  if (job.stage === "fetching") return `Fetching the USGS map: tile ${count(job.done)} of ${count(job.total)}`;
  if (job.stage === "writing") {
    return format === "mbtiles"
      ? `Writing tile ${count(job.done)} of ${count(job.total)}`
      : `Writing the file: row ${count(job.done)} of ${count(job.total)}`;
  }
  return "Making the file…";
};

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

/** "About 1:20,000 · 2.0 m a pixel · 9,800 × 7,400 pixels" */
const describePlan = (plan) =>
  `About ${describeScale(plan.metresPerPixel)} · ${describeResolution(plan.metresPerPixel).replace(/^about /, "")} · ${describePixels(plan)} pixels`;

const MissionOverlayDialog = ({ map, defaultName = "MISSION OVERLAY", mapStyle, onClose, notify, pollMs = POLL_MS }) => {
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
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  // The run under way: what stops it, and the job it made on the server.
  const run = useRef(null);

  const bounds = areas[area];
  const mapMaxZoom = map.getMaxZoom();
  const plans = useMemo(() => overlayPlans(bounds, { format, base, mapMaxZoom }), [bounds, format, base, mapMaxZoom]);
  const chosen = detail === "lighter" && plans.lighter ? "lighter" : "finest";
  const plan = plans[chosen];
  // The pieces with something in them: only those are drawn and sent.
  const wanted = useMemo(() => piecesWithContent(map, plan), [map, plan]);
  const estimate = estimateOverlay(plan);

  // A job left by a dialog closed part way (the person, or the page) is stopped on the server, not left to time out.
  const letGo = (current) => {
    if (!current) return;
    current.controller.abort();
    if (current.job) api.delete(`/overlay-jobs/${current.job}`).catch(() => {});
  };
  useEffect(() => () => letGo(run.current), []);

  const stop = () => {
    letGo(run.current);
    run.current = null;
    setStage(null);
    setJob(null);
  };

  const make = async () => {
    const controller = new AbortController();
    const { signal } = controller;
    const current = { controller, job: null };
    run.current = current;
    setError(null);
    setJob(null);
    setStage("starting");
    try {
      const order = new FormData();
      order.append("format", format);
      order.append("base", base);
      order.append("name", layerName(name));
      for (const key of ["zoom", "left", "top", "width", "height"]) order.append(key, String(plan[key]));
      const { data: made } = await api.post("/overlay-jobs", order, { signal });
      current.job = made.id;

      setStage("drawing");
      setDrawn({ done: 0, total: wanted.size });
      let batch = new FormData();
      let bytes = 0;
      let count = 0;
      const send = async () => {
        if (count === 0) return;
        const sending = batch;
        batch = new FormData();
        bytes = 0;
        count = 0;
        await api.post(`/overlay-jobs/${made.id}/pieces`, sending, { signal });
      };
      if (wanted.size > 0) {
        await captureOverlay(map, plan, {
          pieces: wanted,
          signal,
          onPiece: (done, total) => setDrawn({ done, total }),
          onDrawn: async (piece) => {
            if (count > 0 && bytes + piece.blob.size > BATCH_BYTES) await send();
            const key = `piece-${piece.column}-${piece.row}`;
            batch.append(key, piece.blob, `${key}.png`);
            bytes += piece.blob.size;
            count += 1;
          },
        });
      }
      await send();

      await api.post(`/overlay-jobs/${made.id}/start`, null, { signal });
      setStage("making");
      let status = null;
      for (;;) {
        await pause(pollMs, signal);
        ({ data: status } = await api.get(`/overlay-jobs/${made.id}`, { signal }));
        setJob(status);
        if (status.state === "done") break;
        if (status.state === "failed" || status.state === "cancelled") {
          current.job = null;
          throw Object.assign(new Error(status.error || "The overlay could not be made. Try again."), { said: true });
        }
      }
      current.job = null;
      const fileName = overlayFileName(name, format);
      downloadUrl(`${api.defaults.baseURL ?? ""}${status.download}`, fileName);
      notify?.({ tone: "ok", message: `${fileName} is in your downloads. Open it with ForeFlight from Files${format === "mbtiles" ? " or AirDrop" : ""}.` });
      run.current = null;
      onClose();
    } catch (err) {
      if (signal.aborted) return; // stopped, or the dialog closed: nothing to say
      letGo(current);
      run.current = null;
      setStage(null);
      setJob(null);
      if (err?.said) setError(err.message);
      else if (err?.isAxiosError || err?.response || err?.request) setError(await jobError(err, "The overlay could not be made. Try again."));
      else setError("The map could not be drawn into the overlay. Try again.");
    }
  };

  const busy = stage !== null;
  const working =
    stage === "starting"
      ? "Starting…"
      : stage === "drawing"
        ? `Drawing piece ${Math.min(drawn.done + 1, drawn.total).toLocaleString("en-US")} of ${drawn.total.toLocaleString("en-US")}…`
        : stage === "making"
          ? describeJob(job, format)
          : null;
  const drawsText =
    wanted.size < plan.pieces
      ? ` Draws ${wanted.size.toLocaleString("en-US")} of its ${plan.pieces.toLocaleString("en-US")} pieces of map, where something is planned.`
      : "";
  const estimateText = estimate
    ? ` About ${plan.tiles.toLocaleString("en-US")} tiles, ${describeBytes(estimate.bytes)}; the server takes ${describeMinutes(estimate.seconds)} to make it.`
    : "";
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
      note="What is drawn goes to the server once, to make the file, which it keeps for 30 minutes to be downloaded."
      footer={
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" className="ui-btn" onClick={busy ? stop : onClose}>{busy ? "Stop" : "Cancel"}</button>
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
      {!busy && (drawsText || estimateText) && (
        <div className="ui-field__hint" style={{ marginTop: 8 }}>{`${drawsText}${estimateText}`.trim()}</div>
      )}
      {stage === "drawing" && drawn.total > 1 && (
        <div style={{ marginTop: 12 }}>
          <ProgressBar done={drawn.done} total={drawn.total} label="Pieces of the overlay drawn" />
        </div>
      )}
      {stage === "making" && (
        <div style={{ marginTop: 12 }}>
          <div className="ui-field__hint" aria-live="polite">{describeJob(job, format)}</div>
          <ProgressBar done={job?.state === "running" ? job.done : 0} total={job?.state === "running" ? job.total : 0} label="The overlay being made" />
        </div>
      )}
      {error && <div role="alert" className="ui-field__hint ui-field__hint--error">{error}</div>}
    </Dialog>
  );
};

export default MissionOverlayDialog;
