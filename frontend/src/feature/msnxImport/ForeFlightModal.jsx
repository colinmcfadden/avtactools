import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import QRCode from "qrcode";
import "../export/ExportModal.css";
import { buildForeFlightRouteString, buildForeFlightUrl, downloadFpl } from "./foreflight";

const ForeFlightModal = ({ route, onClose }) => {
  const nodeRef = useRef(null);
  const canvasRef = useRef(null);
  const [qrError, setQrError] = useState(null);
  const [copied, setCopied] = useState(false);

  const routeString = route ? buildForeFlightRouteString(route) : "";
  const url = route ? buildForeFlightUrl(route) : "";

  useEffect(() => {
    if (!route || !canvasRef.current) return;
    setQrError(null);
    QRCode.toCanvas(canvasRef.current, url, {
      width: 340,
      margin: 2,
      errorCorrectionLevel: "M",
    }).catch((err) => {
      // Overflows QR capacity for very long routes (many shaping points).
      setQrError(
        "This route has too many points to fit in a QR code — use the .fpl download or the route text below instead.",
      );
      console.error("QR generation failed:", err);
    });
  }, [route, url]);

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
          <p style={{ fontSize: "0.8rem", opacity: 0.75, marginTop: 0 }}>
            Scan with the iPad camera — it will offer to open the route in
            ForeFlight.
          </p>

          <div style={{ display: "flex", justifyContent: "center", padding: "8px 0" }}>
            {qrError ? (
              <p style={{ color: "#f59e0b", maxWidth: "340px" }}>{qrError}</p>
            ) : (
              <canvas
                ref={canvasRef}
                style={{ borderRadius: "8px", background: "white" }}
              />
            )}
          </div>

          <div className="form-divider">Route text (paste into ForeFlight search)</div>
          <div
            style={{
              display: "flex",
              gap: "8px",
              alignItems: "flex-start",
            }}
          >
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

          <div className="form-divider">File</div>
          <button className="export-btn" onClick={() => downloadFpl(route)}>
            Download .fpl (Garmin flight plan)
          </button>
          <p style={{ fontSize: "0.7rem", opacity: 0.6 }}>
            Open the .fpl on the iPad (Files, Drive, or email attachment) and
            share it to ForeFlight to import the route with named waypoints.
          </p>
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

