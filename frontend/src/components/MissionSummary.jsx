import React, { useMemo } from "react";
import { getPolygonArea } from "../utils/Helpers";
import {
  calculateUH60Capacity,
  UH60_MIN_CENTER_SPACING_METERS,
  UH60_ROTOR_TIP_CLEARANCE_METERS,
} from "../utils/helicopterCapacity";

const MissionSummary = ({ detectedLZ, terrainData, targetLocation, mapData, setActiveNotams, winds, loadingWeather }) => {
  // 1. CALCULATE AREA & CAPACITY
  const stats = useMemo(() => {
    if (!detectedLZ) return { area: 0, heloCount: 0 };
    
    // 1. Get total area in SQUARE FEET 
    const areaSqFt = getPolygonArea(detectedLZ);
    
    // A 60 m rotor-tip-to-rotor-tip buffer requires 76.357 m between UH-60
    // centers. Capacity is intentionally a conservative square-grid estimate.
    const heloCount = calculateUH60Capacity(areaSqFt);
    
    return { 
        area: Math.round(areaSqFt), 
        heloCount: Math.max(0, heloCount) 
    };
  }, [detectedLZ]);

  // 2. SLOPES
  const slopeStatus = useMemo(() => {
    if (!terrainData?.stats)
      return { className: "status-safe", label: "NO DATA", max: 0 };

    const maxSlope = terrainData.stats.maxDeg;
    const directional = terrainData.directional;

    if (directional) {
      const exceedsUh60Limit =
        directional.noseHighMaxDeg >= 6 ||
        directional.noseLowMaxDeg >= 15 ||
        directional.crossSlopeMaxDeg >= 15;
      if (exceedsUh60Limit)
        return { className: "status-danger", label: "LIMIT EXCEEDED", max: maxSlope };
    }

    // A general slope magnitude is useful context, but the UH-60 limits are
    // directional. Require a landing heading before treating it as a limit call.
    if (!directional && maxSlope >= 15)
      return { className: "status-warning", label: "HEADING REQUIRED", max: maxSlope };
    if (maxSlope > 10)
      return { className: "status-warning", label: "CAUTION", max: maxSlope };
    
    return { className: "status-safe", label: "LANDING", max: maxSlope };
  }, [terrainData]);

  if (!detectedLZ) return null;

  return (
    <div className="mission-grid">
      
      {/* --- ROW 1: CAPACITY & AREA (Span 3 each) --- */}
      <div className="ms-tile span-2">
        <div
          className="ms-label"
          title={`${UH60_ROTOR_TIP_CLEARANCE_METERS} m rotor-tip clearance / ${UH60_MIN_CENTER_SPACING_METERS.toFixed(1)} m center spacing`}
        >
          Capacity
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
