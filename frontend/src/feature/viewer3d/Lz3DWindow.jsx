import React, { useEffect, useMemo, useRef, useState } from "react";
import Draggable from "react-draggable";
import Viewer3D from "./Viewer3D";
import { useLidarTileset } from "./useLidarTileset";
import { useRouteScene } from "./useRouteScene";
import { probeTerrain } from "./terrainProvider";
import "../export/ExportModal.css";
import "./viewer3d.css";

// Where builds are run, and the downloaded survey to read. Configured because
// they differ per deployment: a workstation checkout on Windows, or the server
// that holds both the LiDAR and the tilesets it serves.
const BUILD_CWD = process.env.REACT_APP_LIDAR_BUILD_CWD || "/opt/avtactools";
const BUILD_COLLECTION = process.env.REACT_APP_LIDAR_COLLECTION || "";

// The build service's stage names, as a crew member would want to read them.
const STAGE_LABELS = {
  waiting: "Queued",
  starting: "Starting",
  "locating survey": "Finding the LiDAR survey",
  "processing points": "Processing points",
  "building tiles": "Building 3D tiles",
  "building context": "Building the surrounding area",
};

const formatElapsed = (seconds) => {
  const s = Math.max(0, Math.round(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

/**
 * Seconds since the build was first seen. Ticks locally so the clock moves
 * smoothly between the service's three-second progress reports.
 */
const useBuildClock = (building, key) => {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!building) return undefined;
    const started = Date.now();
    setElapsed(0);
    const tick = setInterval(() => setElapsed((Date.now() - started) / 1000), 1000);
    return () => clearInterval(tick);
  }, [building, key]);
  return elapsed;
};

/**
 * The 3D point cloud for one LZ/PZ, in a draggable window over the map.
 *
 * Opening it somewhere new starts a build on the server, and the window shows
 * progress until the point cloud arrives. That is the normal first visit to any
 * LZ, so it reads as work in progress rather than as a failure. Only a server
 * without a build service falls back to telling an operator what to run.
 */
const NO_ROUTES = [];

const Lz3DWindow = ({ label, lat, lon, radiusM, saved = false,
                      importedRoutes = NO_ROUTES, sketchedRoutes = NO_ROUTES,
                      onClose }) => {
  const nodeRef = useRef(null);
  // The same route state the 2D map draws, so an edit there shows here.
  const routes = useMemo(() => [...importedRoutes, ...sketchedRoutes],
                         [importedRoutes, sketchedRoutes]);
  const { scene: routeScene } = useRouteScene(routes);
  const { state, url, contextUrl, target, error, refresh, stage, position,
          elapsedS, buildFailed } = useLidarTileset({ lat, lon, radiusM, keep: saved });
  // The service's own count wins once it reports one, so reopening the window
  // mid-build shows the real time rather than restarting at 0:00.
  const elapsed = Math.max(useBuildClock(state === "building", target?.lat),
                           elapsedS || 0);

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

  // The command that builds this exact spot. It has to say where to run, since
  // the script path is relative to the repo root — but where that is depends on
  // the deployment. Builds belong wherever the LiDAR and the tileset store
  // live, which for a self-hosted backend is the server, not a workstation.
  const buildCommand = target
    ? `cd ${BUILD_CWD} && python tools/build_lz.py ` +
      `--lat ${target.lat.toFixed(6)} --lon ${target.lon.toFixed(6)} ` +
      `--radius ${Math.round(target.radius_m)}` +
      (BUILD_COLLECTION ? ` --collection ${BUILD_COLLECTION}` : "")
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
            <Viewer3D tilesetUrl={url} contextUrl={contextUrl} routeScene={routeScene} />
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

          {state === "building" && (
            <div className="viewer3d__status lz3d-window__building" role="status">
              <strong>Building point cloud</strong>
              <span className="lz3d-window__stage">
                {STAGE_LABELS[stage] || stage || "Starting"}
                {position > 0 && ` — ${position} ahead in the queue`}
              </span>
              <div className="lz3d-window__progress" aria-hidden="true">
                <span />
              </div>
              <span className="lz3d-window__elapsed">{formatElapsed(elapsed)}</span>
              <span className="lz3d-window__hint">
                {/* A build runs only while someone waits for it, unless the
                    LZ is saved — see useLidarTileset. */}
                Takes several minutes the first time; opens at once after that.{" "}
                {saved
                  ? "This LZ is saved, so you may close this window and the build finishes in the background."
                  : "Closing this window or refreshing stops the build. Save the LZ to keep it building."}
              </span>
            </div>
          )}

          {state === "missing" && (
            <div className="viewer3d__status lz3d-window__missing">
              <strong>Not generated</strong>
              This server can't build point clouds. Run this on the
              machine that holds the tileset store — leave the app running:
              {buildCommand && (
                <code className="lz3d-window__command">{buildCommand}</code>
              )}
              <span className="lz3d-window__hint">
                One to two minutes, and Docker must be running. Nothing needs
                restarting afterwards — just press Check again.
              </span>
              <span className="lz3d-window__hint">
                To build automatically instead, run the build service and start
                the backend with <code>LIDAR_BUILDER_URL</code> set
                (backend/lidar/SERVER_SETUP.md).
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
              <strong>{buildFailed ? "Build failed" : "Lookup failed"}</strong>
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
