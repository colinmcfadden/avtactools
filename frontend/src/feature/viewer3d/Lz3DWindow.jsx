import React, { useRef } from "react";
import Draggable from "react-draggable";
import Viewer3D from "./Viewer3D";
import { useLidarTileset } from "./useLidarTileset";
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
  const { state, url, key, error, refresh } = useLidarTileset({ lat, lon, radiusM });

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
          {state === "available" && <Viewer3D tilesetUrl={url} />}

          {state === "resolving" && (
            <div className="viewer3d__status">
              <strong>Looking up</strong>
              Checking for a point cloud at this location…
            </div>
          )}

          {state === "missing" && (
            <div className="viewer3d__status">
              <strong>Not generated</strong>
              No point cloud has been built for this location yet.
              <code className="lz3d-window__key">{key}</code>
              <button type="button" className="lz3d-window__retry" onClick={refresh}>
                Check again
              </button>
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
