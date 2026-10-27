import React, { useCallback, useId, useMemo, useRef, useState } from "react";
import Dialog, { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import Viewer3D from "../viewer3d/Viewer3D";
import { useLidarTileset } from "../viewer3d/useLidarTileset";
import { lzSphere } from "../viewer3d/framing";
import { Thumb } from "./SurveyPhotos";
import { CAPTURE_HEIGHT, CAPTURE_WIDTH, approachView, captureFrame, poseOf } from "./capture3d";
import { surveyFailure } from "./surveyApi";

/*
 * A survey's 3D view (docs/LZ_SURVEYS.md §5, step L3): the one picture of the LZ in 3D its page prints. The editor draws it, as the
 * server has no GPU: *Open 3D* opens the 3D window on the LZ looking down the best approach, the admin turns it as they want it, and
 * Capture keeps one frame at the page's size with the camera that took it and the LiDAR survey it shows. A new capture takes the place
 * of the last, which the server keeps (a published page may show it).
 */

/** The LiDAR survey a capture shows, in words: its name, and its year where the name does not say it. */
export const surveyWords = (survey) =>
  survey?.name
    ? `${survey.name}${survey.year && !String(survey.name).includes(String(survey.year)) ? ` (${survey.year})` : ""}`
    : null;

const captionWords = (capture) => {
  const parts = [];
  if (Number.isFinite(capture.heading_deg)) parts.push(`Looking ${String(Math.round(capture.heading_deg) % 360).padStart(3, "0")}° true`);
  if (capture.taken_on) parts.push(capture.taken_on);
  const survey = surveyWords(capture.pose?.survey);
  if (survey) parts.push(`LiDAR ${survey}`);
  return parts.join(" · ") || "3D view";
};

const LOOKING = {
  idle: "Set the LZ's target to see it in 3D.",
  resolving: "Looking for the LiDAR here…",
  building: "Building the point cloud: several minutes the first time. Keep this window open.",
  missing: "This server cannot build the point cloud here.",
};

/** The 3D window for a capture: the LZ's point cloud, opened down the best approach, and Capture. */
export const CaptureDialog = ({ target, approach, survey, onKeep, onClose }) => {
  const { state, url, contextUrl, frame, error, refresh } = useLidarTileset({ lat: target?.lat, lon: target?.lon, stream: true });
  const view = useMemo(() => approachView(approach), [approach]);
  const scene = useRef(null);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(null);
  // Stable: the viewer is built again whenever it changes.
  const onReady = useCallback((parts) => {
    scene.current = parts;
    setReady(true);
  }, []);

  const capture = async () => {
    const parts = scene.current;
    if (!parts) return;
    setBusy(true);
    setProblem(null);
    try {
      const blob = await captureFrame(parts.viewer);
      const centre = lzSphere(parts.Cesium, parts.tileset, frame).center;
      await onKeep(blob, { ...poseOf(parts.Cesium, parts.viewer.camera, centre), survey: survey ?? null });
    } catch (err) {
      setProblem(err?.name === "SecurityError"
        ? "The browser would not let the view be read (its imagery came without leave to). Close the window and try again."
        : surveyFailure(err, "The 3D view could not be kept."));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="3D view for the page"
      subtitle={view ? `Opens looking down the best approach, ${String(view.headingDeg).padStart(3, "0")}°` : "The survey has no best approach: it opens as the 3D view does"}
      icon="box"
      width="xwide"
      onClose={busy ? undefined : onClose}
      dismissible={!busy}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={capture} disabled={!ready || busy}>
            <Icon name="image" size={14} />
            {busy ? "Keeping…" : "Capture"}
          </button>
        </>
      )}
    >
      <p className="survey-muted">
        Turn the view to show the LZ as a crew would see it, then Capture: the page prints this frame, {CAPTURE_WIDTH} × {CAPTURE_HEIGHT}.
      </p>
      <div className="survey-capture">
        {state === "available" && (
          <Viewer3D tilesetUrl={url} contextUrl={contextUrl} frame={frame} view={view} capturable onReady={onReady} />
        )}
        {state !== "available" && state !== "error" && (
          <div className="viewer3d__status" role="status"><strong>3D</strong>{LOOKING[state] ?? LOOKING.resolving}</div>
        )}
        {state === "error" && (
          <div className="viewer3d__status viewer3d__error" role="alert">
            <strong>The 3D view could not be opened</strong>
            {error}
            <button type="button" className="ui-btn ui-btn--30" onClick={refresh}>Try again</button>
          </div>
        )}
      </div>
      {problem && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{problem}</div>
        </div>
      )}
    </Dialog>
  );
};

const Survey3D = ({ assets = [], target, approach, survey, readOnly = false, onCapture, onRemove }) => {
  const headingId = useId();
  const { show } = useToast();
  const [open, setOpen] = useState(false);
  const [removing, setRemoving] = useState(false);
  const capture = assets.filter((a) => a.kind === "view_3d").reduce((newest, a) => (!newest || a.id > newest.id ? a : newest), null);

  const keep = async (blob, pose) => {
    await onCapture(blob, pose);
    setOpen(false);
    show({ message: "3D view kept: the page prints it." });
  };

  return (
    <section className="survey-panel" aria-labelledby={headingId}>
      <div className="survey-panel__head">
        <h3 id={headingId} className="survey-panel__title">3D view</h3>
        {!readOnly && (
          <button type="button" className="ui-btn ui-btn--34" disabled={!target} onClick={() => setOpen(true)}>
            <Icon name="box" size={14} />
            {capture ? "Capture again" : "Open 3D"}
          </button>
        )}
      </div>
      <p className="survey-muted">
        The page prints one picture of the LZ in 3D: the LiDAR&apos;s points over the ground, looking down the best approach or as you turn
        it.
      </p>
      {capture ? (
        <figure className="survey-capture__kept">
          <Thumb url={capture.url} alt="The 3D view the page prints" />
          <figcaption className="survey-muted">{captionWords(capture)}</figcaption>
          {!readOnly && (
            <button type="button" className="ui-btn ui-btn--28 ui-btn--ghost" onClick={() => setRemoving(true)}>
              <Icon name="trash" size={13} />
              Take off the survey
            </button>
          )}
        </figure>
      ) : (
        <p className="survey-muted">{target ? "No 3D view captured yet." : LOOKING.idle}</p>
      )}
      {open && target && (
        <CaptureDialog target={target} approach={approach} survey={survey} onKeep={keep} onClose={() => setOpen(false)} />
      )}
      {removing && capture && (
        <ConfirmDialog
          title="Take the 3D view off the survey?"
          text="It stays on the server, so a page already published still shows it."
          confirmLabel="Take it off"
          confirmTone="danger"
          onCancel={() => setRemoving(false)}
          onConfirm={async () => {
            try {
              await onRemove(capture.id);
            } catch (err) {
              show({ message: surveyFailure(err, "The 3D view could not be taken off."), tone: "error" });
            }
            setRemoving(false);
          }}
        />
      )}
    </section>
  );
};

export default Survey3D;
