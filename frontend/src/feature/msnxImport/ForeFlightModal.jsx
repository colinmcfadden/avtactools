import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import Draggable from "react-draggable";
import QRCode from "qrcode";
import "../export/ExportModal.css";
import api from "../auth/api";
import {
  buildForeFlightRouteString,
  buildForeFlightUrl,
  buildGpxXml,
  buildFplXml,
  buildSetGpxXml,
  downloadFpl,
  downloadGpx,
  downloadSetGpx,
  exportableRoutes,
} from "./foreflight";

const QR_OPTS = { width: 320, margin: 2, errorCorrectionLevel: "M" };

// A download link lasts 30 minutes on the server; one made in this window is used again for less.
const SHARE_REUSE_MS = 20 * 60 * 1000;

const WHOLE = -1;

const plural = (n, one) => `${n} ${one}${n === 1 ? "" : "s"}`;

const routeLinkFailed = (unauth) =>
  unauth
    ? "This route has too many points for a direct QR code. Sign in to generate a scannable download link — or use the file downloads below."
    : "This route has too many points for a direct QR code. Use the file downloads below to import it into ForeFlight.";

const setLinkFailed = (status) => {
  if (status === 401) return "A whole set's QR code is a link to its file. Sign in to make one, or use Download GPX below: it is the same file.";
  if (status === 413) return "This set is too large for a download link. Use Download GPX below.";
  return "A whole set's QR code is a link to its file, and the link could not be made. Use Download GPX below: it is the same file.";
};

/**
 * The export screen of a route, or of a whole route set: pass `route`, or `set` as `{ name, routes }`.
 *
 * A set goes as ONE GPX file with every route in it (foreflight.js, buildSetGpxXml), which is the one
 * of these formats that holds more than one. What holds one route by its format (ForeFlight's own
 * link, so the direct QR code; a Garmin .fpl; the route text) is offered for one route: the route
 * asked for, a set's only route, or the one chosen at the top of a set's screen. So a whole set's QR
 * code is always the download link, to a page with its GPX.
 */
const ForeFlightModal = ({ route = null, set = null, onClose }) => {
  const nodeRef = useRef(null);
  const canvasRef = useRef(null);
  const reqRef = useRef(0);
  const linksRef = useRef(new WeakMap());
  const titleId = useId();
  const pickerId = useId();
  const [copied, setCopied] = useState(false);
  // loading | deeplink | share | error
  const [qrStatus, setQrStatus] = useState("loading");
  const [statusMsg, setStatusMsg] = useState("");
  // Which of a set's routes the screen is showing; kept with the set it was chosen in, so another set starts whole.
  const [pick, setPick] = useState({ set: null, index: WHOLE });

  const group = route ? null : set;
  const routes = useMemo(() => (group ? exportableRoutes(group.routes) : []), [group]);
  const leftOut = useMemo(() => (group ? (group.routes ?? []).filter((r) => !routes.includes(r)) : []), [group, routes]);
  const pickedIndex = group && pick.set === group && pick.index < routes.length ? pick.index : WHOLE;
  // The one route the single-route parts act on, if there is one.
  const single = route ?? (routes.length === 1 ? routes[0] : pickedIndex === WHOLE ? null : routes[pickedIndex]);
  // The whole set is what the GPX button and the QR code send while no route of it is chosen.
  const whole = Boolean(group) && pickedIndex === WHOLE;
  const nothing = Boolean(group) && routes.length === 0;

  const routeString = single ? buildForeFlightRouteString(single) : "";

  // A short-lived server link to the files, and its QR code. For a route this is the fallback when
  // the route is too large for the deep link; for a whole set it is the only QR there is. Scanning
  // it opens a page to download the file and import it into ForeFlight.
  const drawShareQr = useCallback(async (subject, payload, reqId, failed) => {
    try {
      const made = linksRef.current.get(subject);
      let shareUrl = made && Date.now() - made.at < SHARE_REUSE_MS ? made.url : null;
      if (!shareUrl) {
        const res = await api.post("/route-share", payload());
        const sharePath = res.data?.sharePath;
        if (typeof sharePath !== "string") throw new Error("No share link returned");
        const apiBase = new URL(api.defaults.baseURL || "/", window.location.origin);
        shareUrl = new URL(sharePath, apiBase.origin).toString();
        linksRef.current.set(subject, { url: shareUrl, at: Date.now() });
      }
      if (reqId !== reqRef.current) return;
      await QRCode.toCanvas(canvasRef.current, shareUrl, QR_OPTS);
      if (reqId !== reqRef.current) return;
      setQrStatus("share");
      setStatusMsg("");
    } catch (err) {
      if (reqId !== reqRef.current) return;
      setQrStatus("error");
      setStatusMsg(failed(err?.response?.status));
    }
  }, []);

  useEffect(() => {
    if ((!single && !group) || !canvasRef.current) return undefined;
    const reqId = ++reqRef.current;
    setStatusMsg("");
    if (nothing) {
      setQrStatus("error");
      setStatusMsg("No route of this set has two points yet, so there is nothing to send.");
    } else if (single) {
      setQrStatus("loading");
      // First choice: the on-the-spot deep link that opens ForeFlight directly.
      QRCode.toCanvas(canvasRef.current, buildForeFlightUrl(single), QR_OPTS)
        .then(() => {
          if (reqId === reqRef.current) setQrStatus("deeplink");
        })
        .catch(() => {
          if (reqId !== reqRef.current) return;
          drawShareQr(
            single,
            () => ({ name: single.name, gpx: buildGpxXml(single), fpl: buildFplXml(single) }),
            reqId,
            (status) => routeLinkFailed(status === 401),
          );
        });
    } else {
      setQrStatus("loading");
      // No flight plan for a set: a .fpl holds one route, and the page offers what it is given.
      drawShareQr(group, () => ({ name: group.name, gpx: buildSetGpxXml(routes) }), reqId, setLinkFailed);
    }
    return () => {
      reqRef.current += 1;
    };
  }, [single, group, routes, nothing, drawShareQr]);

  if (!route && !set) return null;

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(routeString);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      window.prompt("Copy the route string:", routeString);
    }
  };

  const caption =
    qrStatus === "deeplink"
      ? "Scan with the iPad camera — it will offer to open the route directly in ForeFlight."
      : qrStatus === "share"
        ? single
          ? "This route is large, so the QR opens a page to download the route file (GPX or .fpl), then share it into ForeFlight. Link expires in 30 min."
          : `Scan with the iPad camera: it opens a page to download all ${plural(routes.length, "route")} as one GPX file, then share that file into ForeFlight. Link expires in 30 min.`
        : qrStatus === "loading"
          ? "Generating QR code…"
          : "";

  const name = route ? route.name : set.name;

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel" role="dialog" aria-labelledby={titleId}>
        <div className="modal-header">
          <h3 id={titleId}>Send to ForeFlight — {name}</h3>
          <button className="close-btn" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          {group && routes.length > 1 && (
            <div className="input-group" style={{ marginBottom: "12px" }}>
              <label htmlFor={pickerId}>Send</label>
              <select
                id={pickerId}
                value={String(pickedIndex)}
                onChange={(event) => setPick({ set: group, index: Number(event.target.value) })}
              >
                <option value={String(WHOLE)}>All {routes.length} routes, as one GPX file</option>
                {routes.map((r, index) => (
                  <option key={`${index}-${r.id ?? r.name}`} value={String(index)}>
                    {r.name} only
                  </option>
                ))}
              </select>
            </div>
          )}
          {leftOut.length > 0 && !nothing && (
            <p role="note" style={{ fontSize: "0.75rem", color: "#d5a03f", margin: "0 0 10px" }}>
              Left out: {leftOut.map((r) => r.name).join(", ")}. A route needs two points to be sent.
            </p>
          )}

          <p style={{ fontSize: "0.8rem", opacity: 0.75, marginTop: 0, minHeight: "2.4em" }}>
            {caption}
          </p>

          <div style={{ display: "flex", justifyContent: "center", padding: "8px 0" }}>
            {qrStatus === "error" && (
              <p role="alert" style={{ color: "#d5a03f", maxWidth: "320px", textAlign: "center" }}>
                {statusMsg}
              </p>
            )}
            <canvas
              ref={canvasRef}
              style={{
                borderRadius: "8px",
                background: "white",
                display: qrStatus === "error" ? "none" : "block",
              }}
            />
          </div>

          <div className="form-divider">{whole && !single ? "Route set file (import into ForeFlight)" : "Route files (import into ForeFlight)"}</div>
          <div style={{ display: "flex", gap: "8px" }}>
            <button
              className="export-btn"
              style={{ flex: 1 }}
              disabled={nothing}
              onClick={() => (whole ? downloadSetGpx({ name: group.name, routes }) : downloadGpx(single))}
            >
              {whole && !single && !nothing ? `Download GPX · ${plural(routes.length, "route")}` : "Download GPX"}
            </button>
            {single && (
              <button
                className="cancel-btn"
                style={{ flex: 1 }}
                onClick={() => downloadFpl(single)}
              >
                Download .fpl
              </button>
            )}
          </div>
          {single ? (
            <p style={{ fontSize: "0.7rem", opacity: 0.6, lineHeight: 1.5 }}>
              On the iPad: open the file (Files app), then <b>Share → ForeFlight</b>.
              The route appears under <b>Flights</b> and its points under{" "}
              <b>User Waypoints</b>. GPX is the most reliable; .fpl is Garmin
              flight-plan format.
            </p>
          ) : (
            !nothing && (
              <p style={{ fontSize: "0.7rem", opacity: 0.6, lineHeight: 1.5 }}>
                All {routes.length} routes are in the one GPX file, hidden on the map or not, in the
                order listed and with every point of each. On the iPad: open the file (Files app),
                then <b>Share → ForeFlight</b>. A Garmin .fpl, the route text and the direct QR code
                hold one route each: choose a route under <b>Send</b> for those, and if an app shows
                only one route of this file, send the others that way.
              </p>
            )
          )}

          {single && (
            <>
              <div className="form-divider">Route text (paste into ForeFlight search)</div>
              <div style={{ display: "flex", gap: "8px", alignItems: "flex-start" }}>
                <code
                  style={{
                    flex: 1,
                    fontSize: "0.7rem",
                    background: "rgba(0,0,0,0.3)",
                    padding: "8px",
                    borderRadius: "6px",
                    wordBreak: "break-all",
                    maxHeight: "80px",
                    overflowY: "auto",
                  }}
                >
                  {routeString}
                </code>
                <button className="cancel-btn" onClick={handleCopy}>
                  {copied ? "Copied!" : "Copy"}
                </button>
              </div>
            </>
          )}
        </div>

        <div className="modal-footer">
          <button className="cancel-btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </Draggable>
  );
};

export default ForeFlightModal;
