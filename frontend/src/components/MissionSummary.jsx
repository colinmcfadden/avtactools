import React, { useMemo } from "react";
import { getPolygonArea } from "../utils/Helpers";
import {
  FALLBACK_PROFILE,
  capacityForArea,
  centerSpacingM,
  tipClearanceM,
} from "../feature/aircraft/aircraftProfiles";

/**
 * Area (square feet, rounded) and how many aircraft fit in a landing zone. Capacity is a conservative square-grid estimate using the
 * selected aircraft's own spacing, so a Chinook LZ holds fewer than a Black Hawk one. Pure, and exported so the native apps can be held
 * to it (contracts/fixtures/planning/summary.json).
 */
export const lzAreaAndCapacity = (detectedLZ, profile = FALLBACK_PROFILE) => {
  if (!detectedLZ) return { area: 0, heloCount: 0 };

  const areaSqFt = getPolygonArea(detectedLZ);
  const heloCount = capacityForArea(areaSqFt, profile);

  return {
    area: Math.round(areaSqFt),
    heloCount: Math.max(0, heloCount),
  };
};

/**
 * The call the slope tile makes. The UH-60 limits are directional, so a limit is only called with a landing heading; a general slope
 * magnitude is context, and 15 degrees or more without a heading asks for one.
 */
export const slopeStatusFor = (terrainData) => {
  if (!terrainData?.stats) return { className: "status-safe", label: "NO DATA", max: 0 };

  const maxSlope = terrainData.stats.maxDeg;
  const directional = terrainData.directional;

  if (directional) {
    const exceedsUh60Limit =
      directional.noseHighMaxDeg >= 6 ||
      directional.noseLowMaxDeg >= 15 ||
      directional.crossSlopeMaxDeg >= 15;
    if (exceedsUh60Limit) return { className: "status-danger", label: "LIMIT EXCEEDED", max: maxSlope };
  }

  if (!directional && maxSlope >= 15) return { className: "status-warning", label: "HEADING REQUIRED", max: maxSlope };
  if (maxSlope > 10) return { className: "status-warning", label: "CAUTION", max: maxSlope };

  return { className: "status-safe", label: "LANDING", max: maxSlope };
};

const MissionSummary = ({ detectedLZ, terrainData, targetLocation, mapData, setActiveNotams, winds, loadingWeather, aircraftProfile }) => {
  const profile = aircraftProfile || FALLBACK_PROFILE;

  // 1. CALCULATE AREA & CAPACITY
  const stats = useMemo(() => lzAreaAndCapacity(detectedLZ, profile), [detectedLZ, profile]);

  // 2. SLOPES
  const slopeStatus = useMemo(() => slopeStatusFor(terrainData), [terrainData]);

  if (!detectedLZ) return null;

  return (
    <div className="mission-grid">
      
      {/* --- ROW 1: CAPACITY & AREA (Span 3 each) --- */}
      <div className="ms-tile span-2">
        <div
          className="ms-label"
          title={`${profile.designation}: ${tipClearanceM(profile).toFixed(0)} m rotor-tip clearance / ${centerSpacingM(profile).toFixed(1)} m center spacing`}
        >
          Capacity · {profile.designation}
        </div>
        <div className="ms-value highlight">{stats.heloCount}</div>
      </div>
      
      <div className="ms-tile span-2">
        <div className="ms-label">Area (ft²)</div>
        <div className="ms-value">{stats.area.toLocaleString()}</div>
      </div>

      <div className="ms-tile span-2">
        <div className="ms-label">Elevation</div>
            <div className="ms-value-row">
                <span className="ms-value">{mapData.elevation}'</span>
                <span className="ms-unit">MSL</span>
            </div>
      </div>

      {/* --- ROW 2: SLOPE ALERT (Span 6 / Full) --- */}
      <div className={`ms-tile span-6 ${slopeStatus.className} slope-alert-tile`}>
        <div className="slope-header">
            <span>MAX TERRAIN SLOPE</span>
        </div>
        <div className="slope-main-text">{slopeStatus.max.toFixed(1)}°</div>
      </div>

      {/* --- ROW 3: WEATHER TRIO (Span 2 each) --- */}
      
      {/* Wind */}
      <div className="ms-tile span-2">
        <div className="ms-label">Wind</div>
        {loadingWeather ? <span className="ms-loading">--</span> : (
            <div className="ms-value-row">
                <span 
                    style={{ transform: `rotate(${winds.dir}deg)`, display: 'inline-block' }}
                    className="wind-arrow"
                >⬇</span>
                <span className="ms-value">{winds.speed}</span>
                <span className="ms-unit">kts</span>
            </div>
        )}
      </div>

      {/* Temp */}
      <div className="ms-tile span-2">
        <div className="ms-label">Temp</div>
        {loadingWeather ? <span className="ms-loading">--</span> : (
            <div className="ms-value-row">
                <span className="ms-value">{winds.temp}</span>
                <span className="ms-unit">°C</span>
            </div>
        )}
      </div>

      {/* Altimeter */}
      <div className="ms-tile span-2">
        <div className="ms-label">Altimeter</div>
        {loadingWeather ? <span className="ms-loading">--</span> : (
            <>
                <div className="ms-value-row">
                    <span className="ms-value" style={{color: "#00b5e2"}}>{winds.pressure}</span>
                    <span className="ms-unit">Hg</span>
                </div>
                <div className="station-id">{winds.station}</div>
            </>
        )}
      </div>
    </div>
  );
};

export default MissionSummary;
