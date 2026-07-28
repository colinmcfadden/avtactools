import React, { useId, useMemo, useState } from "react";
import {
  computeRoutePlan,
  defaultRoutePlan,
  planPoints,
  formatClock,
  formatDuration,
} from "./routeCalc";
import { AIRSPEED_TYPES, FALLBACK_PROFILE } from "../aircraft/aircraftProfiles";

const num = (value, fallback = 0) => {
  const parsed = parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const PT_GLYPHS = { turn: "●", ip: "■", target: "▲" };
const spdShort = { ground: "GS", indicated: "KIAS", true: "KTAS" };

const labelStyle = { fontSize: "0.62rem", opacity: 0.6, textTransform: "uppercase" };
const inputStyle = {
  background: "rgba(255,255,255,0.08)",
  border: "1px solid rgba(255,255,255,0.15)",
  borderRadius: "4px",
  color: "inherit",
  padding: "2px 4px",
  fontSize: "0.75rem",
  minWidth: 0,
};

const ClockIcon = ({ active }) => (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke={active ? "#00b5e2" : "currentColor"}
    strokeWidth="2"
  >
    <circle cx="12" cy="12" r="9" />
    <polyline points="12 7 12 12 15 14" />
  </svg>
);

/**
 * Inline per-point planning editor for a sketched route. Each route point row
 * carries the speed / altitude / wind flown TO that point (the arriving leg),
 * a clock icon to set the TOT anchor, and an editable name with autocomplete
 * from loaded local points. Rolling ("stopwatch") elapsed times and anchored
 * clock times recompute live. Backed by the UH-60L profile from the vidx.
 */
const RoutePlanSection = ({
  route,
  localPointNames = [],
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

  const amps = planPoints(route);
  const [fetchingElev, setFetchingElev] = useState(false);
  const [fetchingWinds, setFetchingWinds] = useState(false);
  const [windStatus, setWindStatus] = useState("");
  const datalistId = useId();

  const localByName = useMemo(() => {
    const map = new Map();
    for (const lp of localPointNames) {
      if (lp?.name) map.set(lp.name.toUpperCase(), lp);
    }
    return map;
  }, [localPointNames]);

  const hasElevations = Object.keys(route.elevations || {}).length > 0;

  const handleFetchElevations = async () => {
    setFetchingElev(true);
    try {
      const fetched = await refreshRouteElevations(route.id);
      if (!fetched || Object.keys(fetched).length === 0) {
        alert("Couldn't fetch elevations (elevation service unreachable).");
      }
    } finally {
      setFetchingElev(false);
    }
  };

  const handleNameChange = (pointId, raw) => {
    const name = raw.toUpperCase();
    const match = localByName.get(name.replace(/^\./, ""));
    updatePointName(
      route.id,
      pointId,
      name,
      match ? { lat: match.lat, lon: match.lon } : undefined,
      match && typeof match.elevationFt === "number" ? match.elevationFt : undefined,
    );
  };

  const handleClockClick = (pointId, current) => {
    const entered = window.prompt(
      "Clock time (HH:MM:SS) to hit this point — blank to clear:",
      current || "",
    );
    if (entered === null) return;
    setPointClock(route.id, pointId, entered.trim());
  };

  const effAlt = (id) => plan.perPoint?.[id]?.altitude || plan.altitude;
  const effSpd = (id) => plan.perPoint?.[id]?.airspeed || plan.airspeed;
  const effWind = (id) => plan.perPoint?.[id]?.wind || plan.wind;

  const handleFetchWinds = async () => {
    setFetchingWinds(true);
    setWindStatus("");
    try {
      const res = await applyForecastWinds(route.id);
      if (res?.error) {
        setWindStatus("Wind fetch failed");
        alert("Couldn't fetch winds: " + res.error);
        return;
      }
      const entries = Object.values(res?.winds || {});
      if (entries.length === 0) {
        setWindStatus("No reporting stations found near this route.");
        return;
      }
      // Summarize which sources fed the legs (e.g. "2 METAR, 1 TAF").
      const bySource = entries.reduce((acc, w) => {
        acc[w.source] = (acc[w.source] || 0) + 1;
        return acc;
      }, {});
      setWindStatus(
        `${entries.length} pts · ` +
          Object.entries(bySource)
            .map(([source, n]) => `${n} ${source}`)
            .join(", "),
      );
    } finally {
      setFetchingWinds(false);
    }
  };

  const rowBox = {
    padding: "5px 6px",
    borderRadius: "6px",
    background: "rgba(255,255,255,0.04)",
    display: "flex",
    flexDirection: "column",
    gap: "4px",
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "8px", fontSize: "0.8rem" }}>
      <datalist id={datalistId}>
        {localPointNames.map((lp, i) => (
          <option key={`${lp.name}-${i}`} value={lp.name} />
        ))}
      </datalist>

      {/* --- route-level defaults / weather --- */}
      <div style={{ display: "flex", gap: "6px", alignItems: "flex-end", flexWrap: "wrap" }}>
        <div>
          <div style={labelStyle}>Date</div>
          <input
            style={{ ...inputStyle, width: "108px" }}
            type="date"
            value={plan.date || ""}
            onChange={(e) => updateRoutePlan(route.id, { date: e.target.value })}
          />
        </div>
        <div>
          <div style={labelStyle}>Temp °C</div>
          <input
            style={{ ...inputStyle, width: "44px" }}
            type="number"
            value={plan.tempC}
            onChange={(e) => updateRoutePlan(route.id, { tempC: num(e.target.value, 15) })}
          />
        </div>
        <div>
          <div style={labelStyle}>Fuel lb/hr</div>
          <input
            style={{ ...inputStyle, width: "56px" }}
            type="number"
            min="0"
            value={plan.fuelFlowLbHr}
            onChange={(e) => updateRoutePlan(route.id, { fuelFlowLbHr: num(e.target.value) })}
          />
        </div>
        <button
          className="export-btn"
          style={{ fontSize: "0.65rem", padding: "3px 6px" }}
          disabled={fetchingWinds}
          title="Fetch each point's wind from the nearest station — METAR now, TAF for future times/dates"
          onClick={handleFetchWinds}
        >
          {fetchingWinds ? "Fetching..." : "Winds ⛅"}
        </button>
      </div>
      {windStatus && (
        <div style={{ fontSize: "0.62rem", opacity: 0.7 }}>{windStatus}</div>
      )}

      {/* --- per-point rows --- */}
      {result.points.map((rp, i) => {
        const isFirst = i === 0;
        const altOver = effAlt(rp.id);
        const spdOver = effSpd(rp.id);
        const windOver = effWind(rp.id);
        return (
          <div key={rp.uiId ?? rp.id ?? `${route.id}-plan-point-${i}`} style={rowBox}>
            {/* name + clock */}
            <div style={{ display: "flex", alignItems: "center", gap: "6px" }}>
              <span style={{ flexShrink: 0 }}>{PT_GLYPHS[rp.ptType] || "●"}</span>
              <input
                style={{ ...inputStyle, flex: 1 }}
                list={datalistId}
                value={rp.name || ""}
                placeholder={isFirst ? ".SP" : `.CP${i}`}
                onChange={(e) => handleNameChange(rp.id, e.target.value)}
                title="Type a name; matching local points autocomplete and snap to their location"
              />
              <button
                onClick={() => handleClockClick(rp.id, plan.perPoint?.[rp.id]?.clock)}
                style={{
                  background: "none",
                  border: "none",
                  color: rp.hasClock ? "#00b5e2" : "#9ca3af",
                  cursor: "pointer",
                  padding: "0 2px",
                  display: "flex",
                  flexShrink: 0,
                }}
                title={rp.hasClock ? "TOT anchor — click to edit/clear" : "Set clock time (TOT) here"}
              >
                <ClockIcon active={rp.hasClock} />
              </button>
              <span
                style={{
                  fontSize: "0.72rem",
                  fontVariantNumeric: "tabular-nums",
                  minWidth: "58px",
                  textAlign: "right",
                  color: rp.hasClock ? "#00b5e2" : "inherit",
                  flexShrink: 0,
                }}
                title="Clock time at this point"
              >
                {rp.clockTime ? formatClock(rp.clockTime) : "--:--:--"}
              </span>
            </div>

            {/* editable "to" values */}
            <div style={{ display: "flex", gap: "6px", flexWrap: "wrap", alignItems: "flex-end" }}>
              <div>
                <div style={labelStyle}>{isFirst ? "Start alt" : "Alt to"}</div>
                <div style={{ display: "flex", gap: "2px" }}>
                  <input
                    style={{ ...inputStyle, width: "46px" }}
                    type="number"
                    value={altOver.value}
                    onChange={(e) =>
                      updatePointPlanOverride(route.id, rp.id, {
                        altitude: { ref: altOver.ref, value: num(e.target.value) },
                      })
                    }
                  />
                  <select
                    style={inputStyle}
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
                </div>
              </div>

              {!isFirst && (
                <>
                  <div>
                    <div style={labelStyle}>Speed to</div>
                    <div style={{ display: "flex", gap: "2px" }}>
                      <input
                        style={{ ...inputStyle, width: "42px" }}
                        type="number"
                        min="0"
                        value={spdOver.value}
                        onChange={(e) =>
                          updatePointPlanOverride(route.id, rp.id, {
                            airspeed: { type: spdOver.type, value: num(e.target.value) },
                          })
                        }
                      />
                      <select
                        style={inputStyle}
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
                    </div>
                  </div>
                  <div>
                    <div style={labelStyle}>Wind °T/kt</div>
                    <div style={{ display: "flex", gap: "2px" }}>
                      <input
                        style={{ ...inputStyle, width: "38px" }}
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
                      <input
                        style={{ ...inputStyle, width: "34px" }}
                        type="number"
                        min="0"
                        value={windOver.speedKts}
                        onChange={(e) =>
                          updatePointPlanOverride(route.id, rp.id, {
                            wind: { dirTrue: windOver.dirTrue, speedKts: num(e.target.value) },
                          })
                        }
                      />
                    </div>
                  </div>
                </>
              )}
            </div>

            {/* computed */}
            <div
              style={{
                fontSize: "0.68rem",
                opacity: 0.75,
                display: "flex",
                justifyContent: "space-between",
                gap: "6px",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              <span>
                {isFirst
                  ? "START"
                  : `${rp.legDistNm != null ? rp.legDistNm.toFixed(1) : "--"}nm · ${
                      rp.legCourseTrueDeg != null
                        ? String(Math.round(rp.legCourseTrueDeg)).padStart(3, "0")
                        : "---"
                    }°T · ${rp.legGsKts != null ? Math.round(rp.legGsKts) : "--"}kt
                     · ${rp.mslFt != null ? `${Math.round(rp.mslFt)}' MSL   ` : ""}`
                    }
              </span>
              <span>
                {formatDuration(rp.elapsedSec)}
              </span>
            </div>
          </div>
        );
      })}

      {/* --- totals + tools --- */}
      {result.totals && (
        <div style={{ display: "flex", justifyContent: "space-between", fontSize: "0.72rem", fontWeight: 600 }}>
          <span>Total {result.totals.distNm.toFixed(1)} nm</span>
          <span>{formatDuration(result.totals.timeSec)}</span>
          <span>
            {result.totals.fuelLb != null ? `${Math.round(result.totals.fuelLb)} lb` : "-- lb"}
          </span>
        </div>
      )}

      <div style={{ display: "flex", gap: "6px" }}>
        <button
          className="export-btn"
          style={{ fontSize: "0.7rem", flex: 1 }}
          onClick={handleFetchElevations}
          disabled={fetchingElev}
          title="Ground elevations enable AGL↔MSL altitudes and density-altitude TAS"
        >
          {fetchingElev
            ? "Fetching..."
            : hasElevations
              ? "Refresh elevations"
              : "Fetch elevations"}
        </button>
      </div>

      <div style={{ fontSize: "0.62rem", opacity: 0.5 }}>
        {plan.aircraft || FALLBACK_PROFILE.name} · speeds/altitudes/winds are "to" each point and export to AMPS
      </div>

      {result.warnings.map((warning) => (
        <div key={warning} style={{ color: "#fbbf24", fontSize: "0.68rem" }}>
          ⚠ {warning}
        </div>
      ))}
    </div>
  );
};

export default RoutePlanSection;
