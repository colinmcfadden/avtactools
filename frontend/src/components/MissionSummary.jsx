import React, { useEffect, useState, useMemo } from "react";
import { getPolygonArea } from "../utils/Helpers";

const API_BASE_URL = process.env.REACT_APP_API_URL;

const MissionSummary = ({ detectedLZ, terrainData, targetLocation, mapData, setActiveNotams, winds, loadingWeather }) => {
  // 1. CALCULATE AREA & CAPACITY
  const stats = useMemo(() => {
    if (!detectedLZ) return { area: 0, heloCount: 0 };
    const areaSqM = getPolygonArea(detectedLZ);
    
    // UH-60 Capacity logic
    const separationMeters = 60; // Minimum center-to-center distance
  
    // Each helicopter effectively requires a 60x60 meter box to guarantee 
    // no other helicopter can encroach on its 60m radius.
    const spotSize = separationMeters * separationMeters; // 3600 sq meters per helo

    // Note: For highly irregular polygon shapes, dividing total area by spot size 
    // is an approximation. A 10m wide, 400m long strip has 4000 sq meters but holds 0 helos. 
    // However, for standard open fields, this math is the aviation standard.
    const heloCount = Math.floor(areaSqM / spotSize);
    
    return { area: Math.round(areaSqM), heloCount: Math.max(0, heloCount) };
  }, [detectedLZ]);

  // 2. SLOPES
  const slopeStatus = useMemo(() => {
    if (!terrainData || terrainData.length === 0)
      return { className: "status-safe", label: "NO DATA", max: 0 };

    const maxSlope = Math.max(...terrainData.map((c) => c.slope));

    if (maxSlope > 13)
      return { className: "status-danger", label: "NO GO", max: maxSlope };
    if (maxSlope > 10)
      return { className: "status-warning", label: "CAUTION", max: maxSlope };
    
    return { className: "status-safe", label: "LANDING", max: maxSlope };
  }, [terrainData]);

  if (!detectedLZ) return null;

  return (
    <div className="mission-grid">
      
      {/* --- ROW 1: CAPACITY & AREA (Span 3 each) --- */}
      <div className="ms-tile span-2">
        <div className="ms-label">Capacity</div>
        <div className="ms-value highlight">{stats.heloCount}</div>
      </div>
      
      <div className="ms-tile span-2">
        <div className="ms-label">Area (m²)</div>
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
            <span>MAX SLOPE</span>
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
