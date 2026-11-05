import React, { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { copyText } from "../../utils/clipboard";
import { cleanLabel, gridLink, shareText } from "../../utils/gridShare";
import { formatLatLong } from "../../utils/targetReading";
import "./ShareGridDialog.css";

// The QR code carries the whole text, not only the link, so a phone with no signal still reads the grid.
const QR_OPTS = { width: 240, margin: 2, errorCorrectionLevel: "M" };

/** Whether this browser has the system's share sheet (Safari's reaches AirDrop; Firefox on the desktop has none). */
const canShare = () => typeof navigator !== "undefined" && typeof navigator.share === "function";

/**
 * Sends a grid to someone (docs/GRID_SHARING.md, options 1 to 3): the grid, its lat/long and its label as text,
 * through the system's share sheet where the browser has one, or copied; its link alone; and a QR code of the
 * same text for another phone's camera, which needs no signal. `place` is `{ mgrs, lat, lon, label? }`.
 */
const ShareGridDialog = ({ place, onClose }) => {
  const { show: toast } = useToast();
  const canvasRef = useRef(null);
  const [qrFailed, setQrFailed] = useState(false);
  const label = cleanLabel(place.label);
  const text = shareText({ ...place, label });
  const link = gridLink(place.mgrs, label);

  useEffect(() => {
    if (!canvasRef.current) return;
    QRCode.toCanvas(canvasRef.current, text, QR_OPTS).catch(() => setQrFailed(true));
  }, [text]);

  const copy = async (what, words) => {
    if (await copyText(what)) toast({ message: words, duration: 2000 });
    else toast({ tone: "error", message: "It could not be copied." });
  };

  const share = async () => {
    try {
      await navigator.share({ title: label || place.mgrs, text });
    } catch (err) {
      // Closing the sheet is not a failure.
      if (err?.name !== "AbortError") toast({ tone: "error", message: "The grid could not be shared. Copy it instead." });
    }
  };

  return (
    <Dialog
      title="Share grid"
      subtitle={label || undefined}
      icon="send"
      onClose={onClose}
      note="Unclassified only."
      footer={
        <div className="grid-share__actions">
          <button type="button" className="ui-btn" onClick={() => copy(link, "Link copied")}>
            <Icon name="link" size={14} /> Copy link
          </button>
          <button type="button" className={`ui-btn${canShare() ? "" : " ui-btn--primary"}`} onClick={() => copy(text, "Copied")}>
            <Icon name="copy" size={14} /> Copy
          </button>
          {canShare() && (
            <button type="button" className="ui-btn ui-btn--primary" onClick={share}>
              <Icon name="send" size={14} /> Share…
            </button>
          )}
        </div>
      }
    >
      <div className="grid-share">
        <div className="grid-share__grid">{place.mgrs}</div>
        <div className="grid-share__latlong">{formatLatLong(place.lat, place.lon)}</div>
        {qrFailed ? (
          <p className="grid-share__note">The QR code could not be drawn.</p>
        ) : (
          <canvas
            ref={canvasRef}
            className="grid-share__qr"
            role="img"
            aria-label={`QR code of ${label ? `${label}, ` : ""}${place.mgrs}`}
          />
        )}
        <p className="grid-share__note">Another phone's camera reads the code, with no signal needed.</p>
      </div>
    </Dialog>
  );
};

export default ShareGridDialog;
