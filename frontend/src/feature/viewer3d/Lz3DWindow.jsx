import React, { useEffect, useRef, useState } from "react";
import Draggable from "react-draggable";
import Viewer3D from "./Viewer3D";
import { useLidarTileset } from "./useLidarTileset";
import { probeTerrain } from "./terrainProvider";
import "../export/ExportModal.css";
import "./viewer3d.css";

/**
 * The 3D point cloud for one LZ/PZ, in a draggable window over the map.
 *
 * Point clouds are generated ahead of time, so much of this component is about
 * the case where one does not exist yet. That is a normal state — the crew has
 * picked a point nobody has built tiles for — and it should read as "not built"
 * rather than as a failure.
 */
const Lz3DWindow = ({ label, lat, lon, radiusM, onClose }) => {
  const nodeRef = useRef(null);
  const { state, url, contextUrl, target, error, refresh } =
    useLidarTileset({ lat, lon, radiusM });

  // Reported rather than assumed: without terrain the ground sits on the
  // ellipsoid far below the points, which looks like a broken render.
  const [hasTerrain, setHasTerrain] = useState(null);
  useEffect(() => {
    let cancelled = false;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return undefined;
    probeTerrain(lat, lon)
      .then((available) => { if (!cancelled) setHasTerrain(available); })
      .catch(() => { if (!cancelled) setHasTerrain(false); });
    return () => { cancelled = true; };
  }, [lat, lon]);

  // The command that builds this exact spot, including the cd — the script
  // path is relative to the repo root, so the command alone fails from
  // anywhere else, and "run this" without saying where is not an instruction.
  const buildCommand = target
    ? `cd C:\\_dev\\avtactools; python tools/build_lz.py ` +
      `--lat ${target.lat.toFixed(6)} --lon ${target.lon.toFixed(6)} ` +
      `--radius ${Math.round(target.radius_m)}`
    : null;

  const [copied, setCopied] = useState(false);
  const copyCommand = () => {
    if (!buildCommand) return;
    navigator.clipboard?.writeText(buildCommand)
      .then(() => setCopied(true))
      .catch(() => setCopied(false));
  };

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div
        ref={nodeRef}
        className="export-modal-container glass-panel lz3d-window"
        role="dialog"
        aria-label={`3D view of ${label || "landing point"}`}
      >
        <div className="modal-header">
          <h3>3D — {label || "Landing point"}</h3>
          <button className="close-btn" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <div className="lz3d-window__body">
          {state === "available" && (
            <Viewer3D tilesetUrl={url} contextUrl={contextUrl} />
          )}

          {state === "available" && hasTerrain === false && (
            <div className="lz3d-window__notice">
              No terrain here — the ground will sit far below the points. Start
              the backend with <code>TERRAIN_DATA_DIR</code> set, or this target
              is outside the mounted DEMs.
            </div>
          )}

          {state === "resolving" && (
            <div className="viewer3d__status">
              <strong>Looking up</strong>
              Checking for a point cloud at this location…
            </div>
          )}

          {state === "missing" && (
            <div className="viewer3d__status lz3d-window__missing">
              <strong>Not generated</strong>
              No point cloud covers this target yet. Open a new PowerShell
              window — leave the app running — and paste this:
              {buildCommand && (
                <code className="lz3d-window__command">{buildCommand}</code>
              )}
              <span className="lz3d-window__hint">
                One to two minutes, and Docker must be running. Nothing needs
                restarting afterwards — just press Check again.
              </span>
              <div className="lz3d-window__actions">
                <button type="button" className="lz3d-window__retry"
                        onClick={copyCommand} disabled={!buildCommand}>
                  {copied ? "Copied" : "Copy command"}
                </button>
                <button type="button" className="lz3d-window__retry" onClick={refresh}>
                  Check again
                </button>
              </div>
            </div>
          )}

          {state === "error" && (
            <div className="viewer3d__status viewer3d__error" role="alert">
              <strong>Lookup failed</strong>
              {error}
              <button type="button" className="lz3d-window__retry" onClick={refresh}>
                Retry
              </button>
            </div>
          )}
        </div>
      </div>
    </Draggable>
  );
};

export default Lz3DWindow;
