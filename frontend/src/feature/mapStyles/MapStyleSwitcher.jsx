import React, { useEffect, useRef, useState } from "react";
import { MAP_STYLES, getMapStyle, previewTileUrl } from "./mapStyles";
import "./MapStyleSwitcher.css";

/**
 * Google-Maps-style base map picker, floating over the bottom-left of the
 * map. Collapsed it shows the active style's thumbnail; clicking it fans out
 * one tile per entry in MAP_STYLES.
 */
const MapStyleSwitcher = ({ mapStyle, setMapStyle }) => {
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
        className="map-style-toggle"
        onClick={() => setOpen((o) => !o)}
        title="Change map type"
      >
        <img src={previewTileUrl(active)} alt="" draggable={false} />
        <span>Layers</span>
      </button>

      {open && (
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
      )}
    </div>
  );
};

export default MapStyleSwitcher;
