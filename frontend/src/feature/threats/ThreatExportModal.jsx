import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import QRCode from "qrcode";
import { threatToPayload } from "./threatModel";
import "../export/ExportModal.css";

const API_BASE_URL = process.env.REACT_APP_API_URL;

// base64url encode a UTF-8 string (QR-friendly, URL-safe).
const base64Url = (str) => {
  const bytes = new TextEncoder().encode(str);
  let bin = "";
  bytes.forEach((b) => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

// QR codes top out around ~2.9 KB; keep a margin so the code stays scannable.
const QR_URL_LIMIT = 2200;

/**
 * Export dialog for threats — offers a direct KMZ download or a QR code that
 * points at a device-side download of the same KMZ (for loading into
 * ForeFlight / ATAK / Aero App on an iPad or phone).
 */
const ThreatExportModal = ({ threats, onDownload, onClose }) => {
  const nodeRef = useRef(null);
  const canvasRef = useRef(null);
  const [downloading, setDownloading] = useState(false);
  const [qrError, setQrError] = useState(null);

  const fileName = "threats.kmz";
  const payload = JSON.stringify({
    fileName,
    threats: threats.map(threatToPayload),
  });
  const qrUrl = `${API_BASE_URL}/threats-kmz?data=${base64Url(payload)}`;
  const tooBig = qrUrl.length > QR_URL_LIMIT;

  useEffect(() => {
    if (!canvasRef.current || tooBig) return;
    setQrError(null);
    QRCode.toCanvas(canvasRef.current, qrUrl, { width: 320, margin: 2, errorCorrectionLevel: "M" })
      .catch((err) => {
        setQrError("Couldn't build a QR code for this many threats — use Download instead.");
        console.error("Threat QR generation failed:", err);
      });
  }, [qrUrl, tooBig]);

  const handleDownload = async () => {
    setDownloading(true);
    try {
      await onDownload();
    } catch (err) {
      alert("Error exporting KMZ: " + err.message);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel" style={{ width: "380px" }}>
        <div className="modal-header">
          <h3>Export Threats</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          <p style={{ fontSize: "0.8rem", opacity: 0.75, marginTop: 0 }}>
            KMZ overlay ({threats.length} threat{threats.length === 1 ? "" : "s"}) for
            ForeFlight, ATAK, or Aero App.
          </p>

          <div className="form-divider">Download</div>
          <button className="export-btn" onClick={handleDownload} disabled={downloading}>
            {downloading ? "Building KMZ…" : "Download .kmz"}
          </button>
          <p style={{ fontSize: "0.7rem", opacity: 0.6 }}>
            Open the file on the device and share it to ForeFlight / ATAK / Aero App.
          </p>

          <div className="form-divider">QR code (scan on the device)</div>
          <div style={{ display: "flex", justifyContent: "center", padding: "6px 0" }}>
            {tooBig ? (
              <p style={{ color: "#f59e0b", maxWidth: "320px", fontSize: "0.8rem" }}>
                Too many threats to fit in a QR code — use the Download button instead.
              </p>
            ) : qrError ? (
              <p style={{ color: "#f59e0b", maxWidth: "320px", fontSize: "0.8rem" }}>{qrError}</p>
            ) : (
              <canvas ref={canvasRef} style={{ borderRadius: "8px", background: "white" }} />
            )}
          </div>
          {!tooBig && !qrError && (
            <p style={{ fontSize: "0.7rem", opacity: 0.6 }}>
              Scan with the device camera — it opens a browser download of the .kmz to
              share into your EFB app.
            </p>
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

export default ThreatExportModal;
