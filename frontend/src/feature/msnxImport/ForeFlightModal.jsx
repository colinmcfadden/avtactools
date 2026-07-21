import React, { useCallback, useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import QRCode from "qrcode";
import "../export/ExportModal.css";
import api from "../auth/api";
import {
  buildForeFlightRouteString,
  buildForeFlightUrl,
  buildGpxXml,
  buildFplXml,
  downloadFpl,
  downloadGpx,
} from "./foreflight";

const QR_OPTS = { width: 320, margin: 2, errorCorrectionLevel: "M" };

const ForeFlightModal = ({ route, onClose }) => {
  const nodeRef = useRef(null);
  const canvasRef = useRef(null);
  const reqRef = useRef(0);
  const [copied, setCopied] = useState(false);
  // loading | deeplink | share | error
  const [qrStatus, setQrStatus] = useState("loading");
  const [statusMsg, setStatusMsg] = useState("");

  const routeString = route ? buildForeFlightRouteString(route) : "";

  // Fallback for routes too large for the deep link: stash the GPX + FPL on a
  // short-lived server link and encode that URL instead. Scanning it opens a
  // page to download the files and import them into ForeFlight.
  const drawShareQr = useCallback(async (r, reqId) => {
    try {
      const res = await api.post("/route-share", {
        name: r.name,
        gpx: buildGpxXml(r),
        fpl: buildFplXml(r),
      });
      if (reqId !== reqRef.current) return;
      const sharePath = res.data?.sharePath;
      if (typeof sharePath !== "string") throw new Error("No share link returned");

      const apiBase = new URL(api.defaults.baseURL || "/", window.location.origin);
      const shareUrl = new URL(sharePath, apiBase.origin).toString();
      await QRCode.toCanvas(canvasRef.current, shareUrl, QR_OPTS);
      if (reqId !== reqRef.current) return;
      setQrStatus("share");
      setStatusMsg("");
    } catch (err) {
      if (reqId !== reqRef.current) return;
      const unauth = err?.response?.status === 401;
      setQrStatus("error");
      setStatusMsg(
        unauth
          ? "This route has too many points for a direct QR code. Sign in to generate a scannable download link — or use the file downloads below."
          : "This route has too many points for a direct QR code. Use the file downloads below to import it into ForeFlight.",
      );
    }
  }, []);

  useEffect(() => {
    if (!route || !canvasRef.current) return;
    const reqId = ++reqRef.current;
    setQrStatus("loading");
    setStatusMsg("");
    // First choice: the on-the-spot deep link that opens ForeFlight directly.
    QRCode.toCanvas(canvasRef.current, buildForeFlightUrl(route), QR_OPTS)
      .then(() => {
        if (reqId === reqRef.current) setQrStatus("deeplink");
      })
      .catch(() => {
        if (reqId === reqRef.current) drawShareQr(route, reqId);
      });
    return () => {
      reqRef.current += 1;
    };
  }, [route, drawShareQr]);

  if (!route) return null;

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
        ? "This route is large, so the QR opens a page to download the route file (GPX or .fpl), then share it into ForeFlight. Link expires in 30 min."
        : qrStatus === "loading"
          ? "Generating QR code…"
          : "";

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        <div className="modal-header">
          <h3>Send to ForeFlight — {route.name}</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          <p style={{ fontSize: "0.8rem", opacity: 0.75, marginTop: 0, minHeight: "2.4em" }}>
            {caption}
          </p>

          <div style={{ display: "flex", justifyContent: "center", padding: "8px 0" }}>
            {qrStatus === "error" && (
              <p style={{ color: "#d5a03f", maxWidth: "320px", textAlign: "center" }}>
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

          <div className="form-divider">Route files (import into ForeFlight)</div>
          <div style={{ display: "flex", gap: "8px" }}>
            <button
              className="export-btn"
              style={{ flex: 1 }}
              onClick={() => downloadGpx(route)}
            >
              Download GPX
            </button>
            <button
              className="cancel-btn"
              style={{ flex: 1 }}
              onClick={() => downloadFpl(route)}
            >
              Download .fpl
            </button>
          </div>
          <p style={{ fontSize: "0.7rem", opacity: 0.6, lineHeight: 1.5 }}>
            On the iPad: open the file (Files app), then <b>Share → ForeFlight</b>.
            The route appears under <b>Flights</b> and its points under{" "}
            <b>User Waypoints</b>. GPX is the most reliable; .fpl is Garmin
            flight-plan format.
          </p>

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
