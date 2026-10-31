import React, { useEffect, useRef, useState } from "react";
import { MAP_STYLES, getMapStyle, previewTileUrl } from "./mapStyles";
import "./MapStyleSwitcher.css";

/**
 * Google-Maps-style base map picker, floating over the bottom-left of the
 * map. Collapsed it shows the active style's thumbnail; clicking it fans out
 * one tile per entry in MAP_STYLES.
 *
 * Below the tiles, the MGRS grid's switch (when setShowMgrsGrid is given). The
 * grid goes over any base map, and while it is on the collapsed button says so,
 * so lines on the map are never a mystery.
 */
const MapStyleSwitcher = ({ mapStyle, setMapStyle, showMgrsGrid = false, setShowMgrsGrid }) => {
  const [open, setOpen] = useState(false);
  const containerRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e) => {
      if (!containerRef.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const active = getMapStyle(mapStyle);

  return (
    <div className="map-style-switcher" ref={containerRef}>
      <button
        type="button"
        className="map-style-toggle"
        onClick={() => setOpen((o) => !o)}
        title={setShowMgrsGrid ? "Change map type or show the MGRS grid" : "Change map type"}
        aria-label={showMgrsGrid ? "Layers, MGRS grid on" : "Layers"}
        aria-expanded={open}
      >
        <img src={previewTileUrl(active)} alt="" draggable={false} />
        {showMgrsGrid && (
          <span className="map-style-toggle__badge" aria-hidden="true">MGRS</span>
        )}
        <span>Layers</span>
      </button>

      {open && (
        <div className="map-style-panel">
          <div className="map-style-options">
            {MAP_STYLES.map((style) => (
              <button
                key={style.id}
                className={`map-style-option ${style.id === active.id ? "active" : ""}`}
                onClick={() => {
                  setMapStyle(style.id);
                  setOpen(false);
                }}
              >
                <img src={previewTileUrl(style)} alt="" draggable={false} />
                <span>{style.label}</span>
              </button>
            ))}
          </div>
          {/* Stays open when flipped, so the change can be seen before the next. */}
          {setShowMgrsGrid && (
            <button
              type="button"
              role="switch"
              aria-checked={showMgrsGrid}
              className="map-layer-switch"
              onClick={() => setShowMgrsGrid(!showMgrsGrid)}
            >
              <span className="map-layer-switch__label">MGRS grid</span>
              <span className="map-layer-switch__state" aria-hidden="true">
                {showMgrsGrid ? "On" : "Off"}
              </span>
              <span className="map-layer-switch__track" aria-hidden="true" />
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default MapStyleSwitcher;
