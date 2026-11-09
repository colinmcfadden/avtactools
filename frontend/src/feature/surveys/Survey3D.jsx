import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import Dialog, { ConfirmDialog } from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
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

/** A capture as the editor lists it: "Looking 090° true · 27 OCT 2026 · LiDAR GA_Statewide_B3_2018" (north is 360, as on the page). */
export const captionWords = (capture) => {
  const parts = [];
  if (Number.isFinite(capture.heading_deg)) parts.push(`Looking ${String(Math.round(capture.heading_deg) % 360 || 360).padStart(3, "0")}° true`);
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

/** Why a capture was not kept, in words: the server's, where it came that far; the frame's own otherwise, never "no connection". */
export const captureFailure = (error) => {
  if (error?.isAxiosError || error?.response || error?.request) return surveyFailure(error, "The 3D view could not be kept.");
  return typeof error?.message === "string" && error.message.startsWith("The 3D view")
    ? error.message
    : "The 3D view could not be captured: close it and open it again.";
};

/** The 3D window for a capture: the LZ's point cloud, opened down the best approach, and Capture. */
export const CaptureDialog = ({ target, approach, survey, onKeep, onClose }) => {
  const { state, url, contextUrl, frame, streamed, error, refresh } = useLidarTileset({ lat: target?.lat, lon: target?.lon, stream: true });
  const view = useMemo(() => approachView(approach), [approach]);
  const scene = useRef(null);
  const urlRef = useRef(url);
  urlRef.current = url;
  const [readyFor, setReadyFor] = useState(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState(null);
  const working = useRef(null);
  // Stable: the viewer is built again whenever it changes. Ready for the tileset it was built on, so a viewer gone (the target moved,
  // the point cloud lost) leaves Capture off until the next one is.
  const onReady = useCallback((parts) => {
    scene.current = parts;
    setReadyFor(urlRef.current);
  }, []);
  const ready = state === "available" && readyFor === url;

  // Closing the window (Cancel, Escape, the target gone) gives up a capture under way: its frame is not kept, nor its upload finished.
  useEffect(() => () => working.current?.abort(), []);

  const capture = async () => {
    const parts = scene.current;
    if (!parts) return;
    const controller = new AbortController();
    working.current = controller;
    setBusy(true);
    setProblem(null);
    try {
      const { viewer, tileset, Cesium } = parts;
      // From the LZ's middle where the server says where it is; a streamed survey's own sphere is a whole state, too far to say.
      const framed = frame && [frame.lat, frame.lon, frame.height_m, frame.radius_m].every(Number.isFinite);
      const centre = framed || !streamed ? lzSphere(Cesium, tileset, frame).center : null;
      const { blob, pose } = await captureFrame(viewer, { readPose: () => poseOf(Cesium, viewer.camera, centre) });
      if (controller.signal.aborted) return;
      // The survey the window shows is the one the snapshot's obstacles were read from only where both are streamed for the place.
      await onKeep(blob, { ...pose, survey: streamed ? survey ?? null : null }, { signal: controller.signal });
    } catch (err) {
      if (controller.signal.aborted) return;
      setProblem(captureFailure(err));
      setBusy(false);
    } finally {
      if (working.current === controller) working.current = null;
    }
  };

  return (
    <Dialog
      title="3D view for the page"
      subtitle={view ? `Opens looking down the best approach, ${String(view.headingDeg).padStart(3, "0")}°` : "The survey has no best approach: it opens as the 3D view does"}
      icon="box"
      width="xwide"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onClose}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary ui-btn--38" icon="image" onClick={capture} busy={busy} disabled={!ready}>
            {busy ? "Keeping…" : "Capture"}
          </BusyButton>
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

  const keep = async (blob, pose, options) => {
    await onCapture(blob, pose, options);
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
