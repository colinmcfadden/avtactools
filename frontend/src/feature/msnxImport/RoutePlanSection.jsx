import React, { useId, useMemo, useState } from "react";
import {
  computeRoutePlan,
  defaultRoutePlan,
  formatClock,
  formatDuration,
} from "./routeCalc";
import { AIRSPEED_TYPES, FALLBACK_PROFILE } from "../aircraft/aircraftProfiles";
import { indexLocalPointsByName, matchLocalPointName } from "../localPoints/localPointMatch";
import Icon from "../ui/Icon";
import "../shell/shell.css";

const num = (value, fallback = 0) => {
  const parsed = parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const spdShort = { ground: "GS", indicated: "KIAS", true: "KTAS" };

/** A route point's symbol, as the map draws it: target a triangle, RP/IP a square, a turn a dot. */
const PointGlyph = ({ type, color }) => (
  <span className="plan__glyph" aria-hidden="true">
    <svg width="12" height="12" viewBox="0 0 12 12">
      {type === "target" ? (
        <path d="M6 1 11 11H1Z" fill={color} />
      ) : type === "ip" ? (
        <rect x="1.5" y="1.5" width="9" height="9" fill={color} />
      ) : (
        <circle cx="6" cy="6" r="4.5" fill={color} />
      )}
    </svg>
  </span>
);

const Field = ({ label, width, htmlFor, children }) => (
  <div className="plan__field" style={{ width }}>
    {htmlFor ? (
      <label className="plan__label" htmlFor={htmlFor}>{label}</label>
    ) : (
      <span className="plan__label" aria-hidden="true">{label}</span>
    )}
    {children}
  </div>
);

/**
 * Inline per-point planning editor for a route. Each route point row carries the speed / altitude /
 * wind flown TO that point (the arriving leg), a clock to set the TOT anchor, and an editable name
 * with autocomplete from loaded local points. Rolling ("stopwatch") elapsed times and anchored clock
 * times recompute live. Backed by the UH-60L profile from the vidx.
 */
const RoutePlanSection = ({
  route,
  localPointNames = [],
  readOnly = false,
  updateRoutePlan,
  updatePointPlanOverride,
  setPointClock,
  updatePointName,
  refreshRouteElevations,
  applyForecastWinds,
}) => {
  const plan = useMemo(() => ({ ...defaultRoutePlan(), ...route.plan }), [route.plan]);
  const result = useMemo(
    () => computeRoutePlan(route, plan, route.elevations || {}),
    [route, plan],
  );

  const [fetchingElev, setFetchingElev] = useState(false);
  const [elevStatus, setElevStatus] = useState("");
  const [fetchingWinds, setFetchingWinds] = useState(false);
  const [windStatus, setWindStatus] = useState(null);
  // The point whose clock is being typed, and what is typed: the clock is set on Enter or Set.
  const [clockEdit, setClockEdit] = useState(null);
  const id = useId();
  const datalistId = `${id}-points`;

  const localByName = useMemo(() => indexLocalPointsByName(localPointNames), [localPointNames]);

  const hasElevations = Object.keys(route.elevations || {}).length > 0;

  const handleFetchElevations = async () => {
    setFetchingElev(true);
    setElevStatus("");
    try {
      const fetched = await refreshRouteElevations(route.id);
      if (!fetched || Object.keys(fetched).length === 0) {
        setElevStatus("Could not reach the elevation service. Try again in a moment.");
      }
    } finally {
      setFetchingElev(false);
    }
  };

  const handleNameChange = (pointId, raw) => {
    const { name, coords, chartElevationFt } = matchLocalPointName(localByName, raw);
    updatePointName(route.id, pointId, name, coords, chartElevationFt);
  };

  const commitClock = (event) => {
    event?.preventDefault();
    if (!clockEdit) return;
    setPointClock(route.id, clockEdit.pointId, clockEdit.value.trim());
    setClockEdit(null);
  };

  const effAlt = (pointId) => plan.perPoint?.[pointId]?.altitude || plan.altitude;
  const effSpd = (pointId) => plan.perPoint?.[pointId]?.airspeed || plan.airspeed;
  const effWind = (pointId) => plan.perPoint?.[pointId]?.wind || plan.wind;

  const handleFetchWinds = async () => {
    setFetchingWinds(true);
    setWindStatus(null);
    try {
      const res = await applyForecastWinds(route.id);
      if (res?.error) {
        setWindStatus({ warn: true, text: `Could not fetch winds: ${res.error}` });
        return;
      }
      const entries = Object.values(res?.winds || {});
      if (entries.length === 0) {
        setWindStatus({ warn: true, text: "No reporting stations found near this route." });
        return;
      }
      // Summarize which sources fed the legs (e.g. "2 METAR, 1 TAF").
      const bySource = entries.reduce((acc, w) => {
        acc[w.source] = (acc[w.source] || 0) + 1;
        return acc;
      }, {});
      setWindStatus({
        text:
          `Winds for ${entries.length} points · ` +
          Object.entries(bySource)
            .map(([source, n]) => `${n} ${source}`)
            .join(", "),
      });
    } finally {
      setFetchingWinds(false);
    }
  };

  return (
    <div className="plan">
      <datalist id={datalistId}>
        {localPointNames.map((lp, i) => (
          <option key={`${lp.name}-${i}`} value={lp.name} />
        ))}
      </datalist>

      <fieldset disabled={readOnly} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
        <div className="plan__fields">
          <Field label="Date" width={118} htmlFor={`${id}-date`}>
            <input
              id={`${id}-date`}
              className="plan__input plan__input--text"
              type="date"
              value={plan.date || ""}
              onChange={(e) => updateRoutePlan(route.id, { date: e.target.value })}
            />
          </Field>
          <Field label="Temp °C" width={50} htmlFor={`${id}-temp`}>
            <input
              id={`${id}-temp`}
              className="plan__input"
              type="number"
              value={plan.tempC}
              onChange={(e) => updateRoutePlan(route.id, { tempC: num(e.target.value, 15) })}
            />
          </Field>
          <Field label="Fuel lb/hr" width={64} htmlFor={`${id}-fuel`}>
            <input
              id={`${id}-fuel`}
              className="plan__input"
              type="number"
              min="0"
              value={plan.fuelFlowLbHr}
              onChange={(e) => updateRoutePlan(route.id, { fuelFlowLbHr: num(e.target.value) })}
            />
          </Field>
        </div>

        <div className="plan__points">
          {result.points.map((rp, i) => {
            const isFirst = i === 0;
            const altOver = effAlt(rp.id);
            const spdOver = effSpd(rp.id);
            const windOver = effWind(rp.id);
            const rowId = `${id}-${i}`;
            const editingClock = clockEdit?.pointId === rp.id;
            const pointName = rp.name || (isFirst ? "start point" : `point ${i + 1}`);
            return (
              <div key={rp.uiId ?? rp.id ?? `${route.id}-plan-point-${i}`} className="plan__point">
                <div className="plan__point-head">
                  <PointGlyph type={rp.ptType} color={route.color || "#e5533d"} />
                  <input
                    className="plan__input"
                    style={{ flex: 1, minWidth: 0, fontSize: 12.5 }}
                    list={datalistId}
                    value={rp.name || ""}
                    placeholder={isFirst ? ".SP" : `.CP${i}`}
                    aria-label={`Name of ${pointName}`}
                    onChange={(e) => handleNameChange(rp.id, e.target.value)}
                    title="Type a name. A matching local point is offered, and choosing it moves the point there."
                  />
                  <button
                    type="button"
                    className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square"
                    aria-label={rp.hasClock ? `Change the clock time at ${pointName}` : `Set a clock time at ${pointName}`}
                    aria-expanded={editingClock}
                    style={rp.hasClock ? { color: "#92c9ea" } : undefined}
                    onClick={() =>
                      setClockEdit(editingClock ? null : { pointId: rp.id, value: plan.perPoint?.[rp.id]?.clock || "" })
                    }
                  >
                    <Icon name="clock" size={15} />
                  </button>
                  <span className={`plan__time${rp.hasClock ? " plan__time--set" : ""}`} title="Clock time at this point">
                    {rp.clockTime ? formatClock(rp.clockTime) : "--:--:--"}
                  </span>
                </div>

                {editingClock && (
                  <form className="plan__clock" onSubmit={commitClock}>
                    <input
                      className="plan__input"
                      // A clock is typed, not picked: AMPS times run past midnight, and the browsers'
                      // time pickers disagree about seconds.
                      placeholder="HH:MM:SS"
                      aria-label={`Clock time at ${pointName}, HH:MM:SS`}
                      value={clockEdit.value}
                      // eslint-disable-next-line jsx-a11y/no-autofocus
                      autoFocus
                      onChange={(e) => setClockEdit({ pointId: rp.id, value: e.target.value })}
                      onKeyDown={(e) => {
                        if (e.key === "Escape") {
                          e.stopPropagation();
                          setClockEdit(null);
                        }
                      }}
                    />
                    <button type="submit" className="ui-btn ui-btn--primary ui-btn--28">Set</button>
                    {rp.hasClock && (
                      <button
                        type="button"
                        className="ui-btn ui-btn--ghost ui-btn--28"
                        onClick={() => {
                          setPointClock(route.id, rp.id, "");
                          setClockEdit(null);
                        }}
                      >
                        Clear
                      </button>
                    )}
                  </form>
                )}

                <div className="plan__values">
                  <Field label={isFirst ? "Start alt" : "Alt to"} width={40} htmlFor={`${rowId}-alt`}>
                    <input
                      id={`${rowId}-alt`}
                      className="plan__input"
                      type="number"
                      value={altOver.value}
                      onChange={(e) =>
                        updatePointPlanOverride(route.id, rp.id, {
                          altitude: { ref: altOver.ref, value: num(e.target.value) },
                        })
                      }
                    />
                  </Field>
                  <Field label="" width={48}>
                    <select
                      className="plan__input plan__select"
                      aria-label={`Altitude reference at ${pointName}`}
                      value={altOver.ref}
                      onChange={(e) =>
                        updatePointPlanOverride(route.id, rp.id, {
                          altitude: { value: altOver.value, ref: e.target.value },
                        })
                      }
                    >
                      <option value="agl">AGL</option>
                      <option value="msl">MSL</option>
                    </select>
                  </Field>

                  {!isFirst && (
                    <>
                      <Field label="Speed to" width={38} htmlFor={`${rowId}-spd`}>
                        <input
                          id={`${rowId}-spd`}
                          className="plan__input"
                          type="number"
                          min="0"
                          value={spdOver.value}
                          onChange={(e) =>
                            updatePointPlanOverride(route.id, rp.id, {
                              airspeed: { type: spdOver.type, value: num(e.target.value) },
                            })
                          }
                        />
                      </Field>
                      <Field label="" width={52}>
                        <select
                          className="plan__input plan__select"
                          aria-label={`Speed type at ${pointName}`}
                          value={spdOver.type}
                          onChange={(e) =>
                            updatePointPlanOverride(route.id, rp.id, {
                              airspeed: { value: spdOver.value, type: e.target.value },
                            })
                          }
                        >
                          {AIRSPEED_TYPES.map((t) => (
                            <option key={t.value} value={t.value}>
                              {spdShort[t.value]}
                            </option>
                          ))}
                        </select>
                      </Field>
                      <Field label="Wind °T / KT" width={32} htmlFor={`${rowId}-wdir`}>
                        <input
                          id={`${rowId}-wdir`}
                          className="plan__input"
                          aria-label={`Wind direction at ${pointName}, degrees true`}
                          type="number"
                          min="0"
                          max="360"
                          value={windOver.dirTrue}
                          onChange={(e) =>
                            updatePointPlanOverride(route.id, rp.id, {
                              wind: { speedKts: windOver.speedKts, dirTrue: num(e.target.value) },
                            })
                          }
                        />
                      </Field>
                      {/* One label over the wind's two boxes, as over altitude and speed: at 11 px a "KT"
                          label of its own over this 28 px box ran into WIND °T, so the shared label
                          carries both units (a crew must see what the number is in). */}
                      <Field label="" width={28}>
                        <input
                          className="plan__input"
                          type="number"
                          min="0"
                          aria-label={`Wind speed at ${pointName}, knots`}
                          value={windOver.speedKts}
                          onChange={(e) =>
                            updatePointPlanOverride(route.id, rp.id, {
                              wind: { dirTrue: windOver.dirTrue, speedKts: num(e.target.value) },
                            })
                          }
                        />
                      </Field>
                    </>
                  )}
                </div>

                <div className="plan__computed">
                  <span>
                    {isFirst
                      ? "START"
                      : `${rp.legDistNm != null ? rp.legDistNm.toFixed(1) : "--"} nm · ${
                          rp.legCourseTrueDeg != null
                            ? String(Math.round(rp.legCourseTrueDeg)).padStart(3, "0")
                            : "---"
                        }°T · ${rp.legGsKts != null ? Math.round(rp.legGsKts) : "--"} kt${
                          rp.mslFt != null ? ` · ${Math.round(rp.mslFt)}' MSL` : ""
                        }`}
                  </span>
                  <span>{formatDuration(rp.elapsedSec)}</span>
                </div>
              </div>
            );
          })}
        </div>

        {result.totals && (
          <div className="plan__totals">
            <span>Total {result.totals.distNm.toFixed(1)} nm</span>
            <span>{formatDuration(result.totals.timeSec)}</span>
            <span>{result.totals.fuelLb != null ? `${Math.round(result.totals.fuelLb)} lb` : "-- lb"}</span>
          </div>
        )}

        <div className="plan__tools">
          <button
            type="button"
            className="ui-btn ui-btn--34 ui-btn--grow"
            disabled={fetchingWinds}
            title="Fetch each point's wind from the nearest station: METAR now, TAF for future times and dates"
            onClick={handleFetchWinds}
          >
            <Icon name="wind" size={14} />
            <span>{fetchingWinds ? "Fetching" : "Get winds"}</span>
          </button>
          <button
            type="button"
            className="ui-btn ui-btn--34 ui-btn--grow"
            onClick={handleFetchElevations}
            disabled={fetchingElev}
            title={`${hasElevations ? "Fetch the ground elevations again. " : ""}Ground elevations enable AGL↔MSL altitudes and density-altitude TAS`}
          >
            <Icon name="mapPin" size={14} />
            <span>{fetchingElev ? "Fetching" : "Get elevations"}</span>
          </button>
        </div>
        {windStatus && (
          <div className={windStatus.warn ? "plan__warning" : "plan__status"} role="status">
            {windStatus.warn && <Icon name="alertTriangle" size={13} />}
            <span>{windStatus.text}</span>
          </div>
        )}
        {elevStatus && (
          <div className="plan__warning" role="status">
            <Icon name="alertTriangle" size={13} />
            <span>{elevStatus}</span>
          </div>
        )}
      </fieldset>

      <div className="plan__note">
        {plan.aircraft || FALLBACK_PROFILE.name} · speeds, altitudes and winds are “to” each point and export to AMPS
      </div>

      {result.warnings.map((warning) => (
        <div key={warning} className="plan__warning">
          <Icon name="alertTriangle" size={13} />
          <span>{warning}</span>
        </div>
      ))}
    </div>
  );
};

export default RoutePlanSection;
