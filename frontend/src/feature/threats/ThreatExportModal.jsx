import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Draggable from "react-draggable";
import QRCode from "qrcode";
import api from "../auth/api";
import { threatToPayload } from "./threatModel";
import "../export/ExportModal.css";

/**
 * Export dialog for threats — offers a KMZ download (ForeFlight / ATAK / Aero
 * App), a .ths download (AMPS), or a short-lived QR download link for loading
 * the KMZ into an EFB app on an iPad or phone.
 */
const ThreatExportModal = ({ threats, onDownload, onDownloadThs, onClose }) => {
  const nodeRef = useRef(null);
  const canvasRef = useRef(null);
  const qrRequestRef = useRef(0);
  const [downloading, setDownloading] = useState(false);
  const [downloadingThs, setDownloadingThs] = useState(false);
  const [qrLoading, setQrLoading] = useState(true);
  const [qrLink, setQrLink] = useState(null);
  const [qrError, setQrError] = useState(null);
  const [qrExpired, setQrExpired] = useState(false);

  const fileName = "threats.kmz";
  const qrPayload = useMemo(() => ({
    fileName,
    threats: threats.map(threatToPayload),
  }), [threats]);

  const requestQrLink = useCallback(async () => {
    const requestId = ++qrRequestRef.current;
    setQrLoading(true);
    setQrLink(null);
    setQrError(null);
    setQrExpired(false);
    try {
      const response = await api.post("/threats-kmz-link", qrPayload);
      if (requestId !== qrRequestRef.current) return;
      if (typeof response.data?.downloadPath !== "string") {
        throw new Error("The server did not return a download link");
      }

      const apiBase = new URL(api.defaults.baseURL || "/", window.location.origin);
      const downloadUrl = new URL(response.data.downloadPath, apiBase.origin).toString();
      const expiresInSeconds = Number(response.data.expiresInSeconds) || 600;
      setQrLink({
        url: downloadUrl,
        expiresAt: Date.now() + expiresInSeconds * 1000,
        expiresInSeconds,
        maxDownloads: Number(response.data.maxDownloads) || 1,
      });
    } catch (err) {
      if (requestId !== qrRequestRef.current) return;
      setQrError(
        err.response?.data?.error ||
          "Couldn't create a secure QR download link. Please try again.",
      );
    } finally {
      if (requestId === qrRequestRef.current) setQrLoading(false);
    }
  }, [qrPayload]);

  useEffect(() => {
    requestQrLink();
    return () => {
      qrRequestRef.current += 1;
    };
  }, [requestQrLink]);

  useEffect(() => {
    if (!canvasRef.current || !qrLink || qrExpired) return;
    QRCode.toCanvas(canvasRef.current, qrLink.url, {
      width: 320,
      margin: 2,
      errorCorrectionLevel: "M",
    })
      .catch((err) => {
        setQrError("Couldn't render the QR code. Please generate a new link.");
        console.error("Threat QR generation failed:", err);
      });
  }, [qrLink, qrExpired]);

  useEffect(() => {
    if (!qrLink) return undefined;
    const remainingMs = qrLink.expiresAt - Date.now();
    if (remainingMs <= 0) {
      setQrExpired(true);
      return undefined;
    }
    const timer = window.setTimeout(() => setQrExpired(true), remainingMs);
    return () => window.clearTimeout(timer);
  }, [qrLink]);

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

  const handleDownloadThs = async () => {
    setDownloadingThs(true);
    try {
      await onDownloadThs();
    } catch (err) {
      alert("Error exporting .ths: " + err.message);
    } finally {
      setDownloadingThs(false);
    }
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel" style={{ width: "min(380px, 94vw)" }}>
        <div className="modal-header">
          <h3>Export Threats</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          <p style={{ fontSize: "0.8rem", opacity: 0.75, marginTop: 0 }}>
            Export {threats.length} threat{threats.length === 1 ? "" : "s"} — KMZ for
            ForeFlight / ATAK / Aero App, or .ths for AMPS.
          </p>

          <div className="form-divider">Download</div>
          <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
            <button className="export-btn" onClick={handleDownload} disabled={downloading}>
              {downloading ? "Building KMZ…" : "Download .kmz (ForeFlight / ATAK / Aero App)"}
            </button>
            {onDownloadThs && (
              <button
                className="export-btn"
                onClick={handleDownloadThs}
                disabled={downloadingThs}
                style={{ background: "#00b5e2" }}
              >
                {downloadingThs ? "Building .ths…" : "Download .ths (AMPS)"}
              </button>
            )}
          </div>
          <p style={{ fontSize: "0.7rem", opacity: 0.6 }}>
            Open the file on the device and share it to the target app.
          </p>

          <div className="form-divider">QR code — KMZ (scan on the device)</div>
          <div style={{ display: "flex", justifyContent: "center", padding: "6px 0" }}>
            {qrLoading ? (
              <p style={{ maxWidth: "320px", fontSize: "0.8rem", opacity: 0.75 }}>
                Creating a secure, short-lived download link…
              </p>
            ) : qrExpired ? (
              <div style={{ maxWidth: "320px", textAlign: "center" }}>
                <p style={{ color: "#f59e0b", fontSize: "0.8rem" }}>
                  This QR download link has expired.
                </p>
                <button className="export-btn" onClick={requestQrLink}>
                  Generate a new QR code
                </button>
              </div>
            ) : qrError ? (
              <div style={{ maxWidth: "320px", textAlign: "center" }}>
                <p style={{ color: "#f59e0b", fontSize: "0.8rem" }}>{qrError}</p>
                <button className="export-btn" onClick={requestQrLink}>
                  Try again
                </button>
              </div>
            ) : (
              <canvas ref={canvasRef} style={{ borderRadius: "8px", background: "white" }} />
            )}
          </div>
          {qrLink && !qrLoading && !qrError && !qrExpired && (
            <p style={{ fontSize: "0.7rem", opacity: 0.6 }}>
              Scan with the device camera — it opens a browser download of the .kmz to
              share into your EFB app. The link expires in {Math.round(qrLink.expiresInSeconds / 60)}
              {" "}minutes and permits up to {qrLink.maxDownloads} downloads.
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
