import React, { useMemo } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import SlopeProfile from "../terrain/SlopeProfile";
import { slopeProfileFor } from "../terrain/groundProfile";

/*
 * What EZ/PZ measured of the LZ (the survey's snapshot, docs/LZ_SURVEYS.md §3.3), and the dialog that shows what a new measurement
 * would change before the survey takes it. The slope call is the 95th percentile at the best approach, judged against the UH-60's
 * limits with no wind; the LZ/PZ panel's tile, which judges the steepest cell at the landing doghouse, is not the survey's (§11 R7).
 */

const shown = (value) => (value === null || value === undefined || value === "" ? "—" : String(value));

const SLOPE_ROWS = [
  ["noseUp", "Nose up"],
  ["noseDown", "Nose down"],
  ["cross", "Cross"],
  ["overall", "Any direction"],
];

export const SlopeTable = ({ slope }) => {
  if (!slope) return null;
  return (
    <table className="survey-table survey-table--compact">
      <thead>
        <tr>
          <th scope="col">Slope</th>
          <th scope="col">95th percentile</th>
          <th scope="col">Average</th>
          <th scope="col">Steepest cell</th>
          <th scope="col">Limit</th>
        </tr>
      </thead>
      <tbody>
        {SLOPE_ROWS.map(([key, label]) =>
          slope[key] ? (
            <tr key={key} className={slope.exceeded?.includes(key) ? "survey-table__row--open" : undefined}>
              <th scope="row">{label}</th>
              <td>{slope[key].p95.toFixed(1)}°</td>
              <td>{slope[key].mean.toFixed(1)}°</td>
              <td>{slope[key].max.toFixed(1)}°</td>
              <td>{slope.limits?.[key] !== undefined ? `${slope.limits[key]}°` : "—"}</td>
            </tr>
          ) : null,
        )}
      </tbody>
    </table>
  );
};

const callTone = (slope) => (slope?.call === "exceeded" ? "warn" : slope?.call === "within" ? "ok" : "muted");

/** The best approach a snapshot was measured at: what its slope and profile are for, whatever the field says now. */
export const measuredHeading = (snapshot) => {
  if (snapshot && "approachDeg" in snapshot) return Number.isFinite(snapshot.approachDeg) ? snapshot.approachDeg : null;
  const heading = snapshot?.slope?.headingDeg;
  return Number.isFinite(heading) ? heading : null;
};

const Measurements = ({ snapshot, stale = [], onMeasure, busy, canMeasure, whyNot }) => {
  const heading = measuredHeading(snapshot);
  const profile = useMemo(
    () => (snapshot?.terrain?.profiles && heading !== null ? slopeProfileFor(snapshot.terrain.profiles, heading) : null),
    [snapshot, heading],
  );
  const elevation = snapshot?.elevation;
  const size = snapshot?.size;
  return (
    <section className="survey-panel" aria-labelledby="survey-measured">
      <div className="survey-panel__head">
        <h3 id="survey-measured" className="survey-panel__title">Measured by EZ/PZ</h3>
        <button type="button" className="ui-btn ui-btn--34 ui-btn--primary" disabled={!canMeasure || Boolean(busy)} onClick={onMeasure}>
          <Icon name="refresh" size={14} />
          {busy === "measuring" || busy === "applying" ? "Measuring…" : snapshot ? "Measure again" : "Measure"}
        </button>
      </div>
      {!canMeasure && whyNot && <p className="survey-muted">{whyNot}</p>}
      {!snapshot ? (
        <p className="survey-muted">Not measured yet. Set the target and the boundary on the map, then Measure.</p>
      ) : (
        <>
          {stale.length > 0 && (
            <div className="survey-banner survey-banner--warn" role="status">
              <Icon name="alertTriangle" size={16} />
              <div>
                <strong>Measured before the survey changed: measure it again.</strong> {stale.join(" ")} It is not sent for review or
                published until it is.
              </div>
            </div>
          )}
          <dl className="survey-facts">
            <div><dt>Grid</dt><dd>{shown(snapshot.grid)}</dd></div>
            <div><dt>Lat/long</dt><dd>{shown(snapshot.latLong)}</dd></div>
            <div>
              <dt>Elevation</dt>
              <dd>
                {elevation ? `${elevation.ft} ft MSL` : "—"}
                {elevation && (
                  <div className="survey-muted">
                    {elevation.dem?.length ? elevation.dem.join(", ") : elevation.source} · {elevation.resolutionM} m · {elevation.verticalDatum}
                  </div>
                )}
              </dd>
            </div>
            <div><dt>Size</dt><dd>{size ? `${size.lengthFt.toLocaleString()} × ${size.widthFt.toLocaleString()} ft` : "—"}</dd></div>
            <div><dt>LZ axis</dt><dd>{size ? `${String(Math.round(size.axisDeg)).padStart(3, "0")}° / ${String((Math.round(size.axisDeg) + 180) % 360).padStart(3, "0")}°` : "—"}</dd></div>
            <div><dt>Area</dt><dd>{size ? `${size.areaAcres} acres` : "—"}</dd></div>
            <div>
              <dt>Fit</dt>
              <dd>
                {snapshot.fit ? `${snapshot.fit.count} ${snapshot.fit.aircraft}s by area` : "—"}
                <div className="survey-muted">A note only: Max A/C is the survey's judgement.</div>
              </dd>
            </div>
          </dl>
          {snapshot.slope && (
            <div className={`survey-call survey-state--${callTone(snapshot.slope)}`} role="status">{snapshot.slope.words}</div>
          )}
          <SlopeTable slope={snapshot.slope} />
          {profile && <div className="survey-profile"><SlopeProfile profile={profile} /></div>}
          {snapshot.problems?.length > 0 && (
            <ul className="survey-problems">
              {snapshot.problems.map((problem) => <li key={problem}>{problem}</li>)}
            </ul>
          )}
          <p className="survey-muted">Measured {snapshot.measuredAt?.slice(0, 10)} by {snapshot.measuredBy || "an admin"}.</p>
        </>
      )}
    </section>
  );
};

/** What a new measurement changes, shown before the survey takes it. */
export const MeasurePreview = ({ preview, busy, onApply, onCancel }) => {
  const changes = preview?.changes ?? [];
  return (
    <Dialog
      title="Use these measurements?"
      subtitle={changes.length ? `${changes.length} ${changes.length === 1 ? "value changes" : "values change"}` : "Nothing changes"}
      icon="refresh"
      width="xwide"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Keep the current ones</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={onApply} disabled={busy}>
            {busy ? "Keeping…" : "Use these measurements"}
          </button>
        </>
      )}
    >
      {changes.length === 0 ? (
        <p>The LZ measures as it did. Keeping it records the new date.</p>
      ) : (
        <table className="survey-table survey-table--compact">
          <thead>
            <tr><th scope="col">Value</th><th scope="col">Now</th><th scope="col">Measured</th></tr>
          </thead>
          <tbody>
            {changes.map((change) => (
              <tr key={change.label}>
                <th scope="row">{change.label}</th>
                <td>{shown(change.before)}</td>
                <td>{shown(change.after)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {preview?.snapshot?.problems?.length > 0 && (
        <ul className="survey-problems">
          {preview.snapshot.problems.map((problem) => <li key={problem}>{problem}</li>)}
        </ul>
      )}
    </Dialog>
  );
};

export default Measurements;
