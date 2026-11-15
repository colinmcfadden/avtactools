import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import QRCode from "qrcode";
import api from "../auth/api";
import Dialog from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
import { blobError } from "../../utils/fileDownload";
import { exportBody, exportGroups, threatCount } from "./threatExport";

/**
 * Exporting threats for another app: one file of every open set's threats (each set a folder in it) or of one
 * set, as a KMZ for ForeFlight, ATAK and Aero App (or the same unzipped as KML), a .ths for AMPS, or a short-lived
 * link to the KMZ as a QR code, to scan on the iPad or phone that will open it.
 *
 * `sets` are the open sets as the Threats panel shows them; `initialSetId` the one asked for, or null for all.
 * `onDownload({ setId, format })` and `onDownloadThs({ setId })` make and save the file.
 */
const ThreatExportModal = ({ sets, initialSetId = null, onDownload, onDownloadThs, onClose }) => {
  const groupsAll = useMemo(() => exportGroups(sets), [sets]);
  const [setId, setSetId] = useState(initialSetId);
  // A set emptied or closed while the dialog is open: the file is of everything that is left.
  const scope = setId && groupsAll.some((set) => set.id === setId) ? setId : null;
  const groups = useMemo(() => exportGroups(sets, scope), [sets, scope]);
  const count = threatCount(groups);
  const [format, setFormat] = useState("kmz");
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const scopeId = useId();

  const canvasRef = useRef(null);
  const qrRequestRef = useRef(0);
  const [qrLoading, setQrLoading] = useState(true);
  const [qrLink, setQrLink] = useState(null);
  const [qrError, setQrError] = useState(null);
  const [qrExpired, setQrExpired] = useState(false);

  // What the QR code's file holds, as text, so a set's threat moved under the dialog asks for a new link and
  // a pointer moving (a new array of the same threats) does not.
  const qrBody = useMemo(() => JSON.stringify(exportBody(groups, "threats.kmz", "kmz")), [groups]);

  const requestQrLink = useCallback(async () => {
    const requestId = ++qrRequestRef.current;
    setQrLoading(true);
    setQrLink(null);
    setQrError(null);
    setQrExpired(false);
    try {
      const response = await api.post("/threats-kmz-link", JSON.parse(qrBody));
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
      setQrError(err.response?.data?.error || "The QR code's download link could not be made. Try again.");
    } finally {
      if (requestId === qrRequestRef.current) setQrLoading(false);
    }
  }, [qrBody]);

  useEffect(() => {
    if (count === 0) return undefined;
    requestQrLink();
    return () => {
      qrRequestRef.current += 1;
    };
  }, [requestQrLink, count]);

  useEffect(() => {
    if (!canvasRef.current || !qrLink || qrExpired) return;
    QRCode.toCanvas(canvasRef.current, qrLink.url, { width: 240, margin: 2, errorCorrectionLevel: "M" }).catch(() => {
      setQrError("The QR code could not be drawn. Make a new one.");
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

  const download = async (kind) => {
    setBusy(kind);
    setError(null);
    try {
      if (kind === "ths") await onDownloadThs({ setId: scope });
      else await onDownload({ setId: scope, format });
    } catch (err) {
      setError(await blobError(err, kind === "ths" ? "The .ths could not be made. Try again." : `The .${format} could not be made. Try again.`));
    } finally {
      setBusy(null);
    }
  };

  const setsWord = (n) => `${n} ${n === 1 ? "set" : "sets"}`;
  const threatsWord = (n) => `${n} ${n === 1 ? "threat" : "threats"}`;
  const only = groupsAll.length === 1 ? groupsAll[0] : null;

  return (
    <Dialog
      title="Export threats"
      subtitle={
        count === 0
          ? "No threats are open."
          : groups.length > 1
            ? `${threatsWord(count)} in ${setsWord(groups.length)}, each set a folder in the file.`
            : `${threatsWord(count)} from ${groups[0].name}.`
      }
      icon="send"
      width="wide"
      onClose={onClose}
      note="Threat positions go to the server once, to make the file. Nothing is kept."
      footer={<button type="button" className="ui-btn" onClick={onClose}>Close</button>}
    >
      {count === 0 ? (
        <p style={{ margin: 0 }}>Add or import threats first.</p>
      ) : (
        <>
          {groupsAll.length > 1 ? (
            <div className="ui-field">
              <label htmlFor={scopeId} className="ui-field__label">Send</label>
              <select id={scopeId} className="ui-input" value={scope ?? ""} onChange={(event) => setSetId(event.target.value || null)}>
                <option value="">All {threatsWord(threatCount(groupsAll))} in {setsWord(groupsAll.length)}, as one file</option>
                {groupsAll.map((set) => (
                  <option key={set.id} value={set.id}>
                    {set.name} only · {threatsWord(set.threats.length)}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div className="ui-field">
              <div className="ui-field__label">Send</div>
              <div style={{ fontSize: 13 }}>
                <b>{only.name}</b> · {threatsWord(only.threats.length)}
              </div>
            </div>
          )}

          <div className="ui-field" style={{ marginTop: 14 }}>
            <div className="ui-field__label" id={`${scopeId}-format`}>For ForeFlight, ATAK or Aero App</div>
            <div role="radiogroup" aria-labelledby={`${scopeId}-format`} style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8 }}>
              <label className={`ui-choice${format === "kmz" ? " ui-choice--selected" : ""}`}>
                <input type="radio" name="threat-format" checked={format === "kmz"} onChange={() => setFormat("kmz")} />
                <span>
                  <span className="ui-choice__title">KMZ</span>
                  <span className="ui-choice__text">Zipped. What ForeFlight, ATAK and Aero App expect.</span>
                </span>
              </label>
              <label className={`ui-choice${format === "kml" ? " ui-choice--selected" : ""}`}>
                <input type="radio" name="threat-format" checked={format === "kml"} onChange={() => setFormat("kml")} />
                <span>
                  <span className="ui-choice__title">KML</span>
                  <span className="ui-choice__text">The same file, unzipped, for an app that takes only KML.</span>
                </span>
              </label>
            </div>
          </div>

          <div style={{ display: "flex", gap: 8, marginTop: 14, flexWrap: "wrap" }}>
            <BusyButton className="ui-btn ui-btn--primary ui-btn--grow" icon="download" busy={busy === "kmz"} disabled={Boolean(busy)} onClick={() => download("kmz")}>
              <span>Download .{format}</span>
            </BusyButton>
            {onDownloadThs && (
              <BusyButton className="ui-btn ui-btn--grow" icon="upload" busy={busy === "ths"} disabled={Boolean(busy)} onClick={() => download("ths")}>
                <span>Download .ths for AMPS</span>
              </BusyButton>
            )}
          </div>
          {error && <div role="alert" className="ui-field__hint ui-field__hint--error">{error}</div>}
          <div className="ui-field__hint">Open the file on the device and share it to the app. AMPS keeps no folders: the .ths is one list.</div>

          <div className="ui-label" style={{ marginTop: 18, marginBottom: 8 }}>QR code · the KMZ, to scan on the device</div>
          <div style={{ display: "flex", gap: 14, alignItems: "center", flexWrap: "wrap" }}>
            <div style={{ width: 240, minHeight: 120, display: "flex", alignItems: "center", justifyContent: "center" }}>
              {qrLoading ? (
                <span style={{ color: "var(--ff-text-muted)" }}>Making a short-lived download link…</span>
              ) : qrExpired || qrError ? (
                <div style={{ textAlign: "center" }}>
                  <p role={qrError ? "alert" : undefined} style={{ color: "#f0c56f", margin: "0 0 8px" }}>
                    {qrError || "This QR code's link has expired."}
                  </p>
                  <button type="button" className="ui-btn" onClick={requestQrLink}>
                    {qrError ? "Try again" : "Make a new QR code"}
                  </button>
                </div>
              ) : (
                <canvas ref={canvasRef} aria-label="QR code of a link to the KMZ" style={{ borderRadius: 8, background: "white" }} />
              )}
            </div>
            {qrLink && !qrLoading && !qrError && !qrExpired && (
              <p style={{ flex: "1 1 180px", margin: 0, color: "var(--ff-text-muted)", lineHeight: 1.45 }}>
                Scan it with the device's camera: the browser downloads the .kmz, to share into the app. The link lasts{" "}
                {Math.round(qrLink.expiresInSeconds / 60)} minutes and works {qrLink.maxDownloads === 1 ? "once" : `${qrLink.maxDownloads} times`}.
              </p>
            )}
          </div>
        </>
      )}
    </Dialog>
  );
};

export default ThreatExportModal;
