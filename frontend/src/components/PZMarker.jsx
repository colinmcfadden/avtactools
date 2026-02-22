import React, { useEffect, useRef, useState } from "react";
import { useMap, useMapEvents } from "react-leaflet";
import L from "leaflet";

const PZMarker = ({ data, updatePZMarker, deletePZMarker }) => {
  const map = useMap();
  const anchorMarkerRef = useRef(null);
  const tipMarkerRef = useRef(null);
  const latestData = useRef(data);
  const [svgPath, setSvgPath] = useState("");

  useEffect(() => {
    latestData.current = data;
  }, [data]);

  // --- HELPER: ENCODE SVG TO DATA URI ---
  const svgToDataUri = (innerContent) => {
    const svg = `
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="-1000 -1000 2000 2000" width="2000" height="2000">
                ${innerContent}
            </svg>
        `.trim();
    return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  };

  // --- HELPER: SVG STRING GENERATOR ---
  const generateSvgString = (L_dist, angle) => {
    const headLen = 12;
    const headWidth = 8;
    const shaftWidth = 6;
    const start = 9;

    const path = `
            M ${start},${-shaftWidth / 2} 
            L ${L_dist - headLen},${-shaftWidth / 2} 
            L ${L_dist - headLen},${-headWidth} 
            L ${L_dist},0 
            L ${L_dist - headLen},${headWidth} 
            L ${L_dist - headLen},${shaftWidth / 2} 
            L ${start},${shaftWidth / 2} 
            Z
        `;

    const soldierPath =
      "M2,14 L2,8 L4,6 L4,4 A2,2 0 1,0 0,4 L0,6 L2,8 L2,14 L0,18 L2,18 L3,15 L4,18 L6,18 L4,14 Z";
    const soldierCount = Math.max(0, Math.floor((L_dist - 25) / 10));
    let sHtml = "";

    for (let i = 0; i < soldierCount; i++) {
      const xPos = 15 + i * 10;
      sHtml += `<g transform="translate(${xPos}, -10) scale(0.4)" fill="black">${soldierPath}</g>`;
    }

    const rotationTransform = `rotate(${angle} 0 0)`;

    return `<g transform="${rotationTransform}"><path d="${path}" fill="rgba(0, 0, 255, 0.3)" stroke="black" stroke-width="1"/>${sHtml}</g>`;
  };

  // --- GEOMETRY CALCULATOR ---
  const updateVisuals = () => {
    const d = latestData.current;
    const anchorLat = d.lat;
    const anchorLon = d.lon;
    const tipLat = d.tipLat !== undefined ? d.tipLat : anchorLat;
    const tipLon = d.tipLon !== undefined ? d.tipLon : anchorLon - 0.001;

    if (anchorLat == null || anchorLon == null) return;

    const anchorPt = map.latLngToContainerPoint([anchorLat, anchorLon]);
    const tipPt = map.latLngToContainerPoint([tipLat, tipLon]);

    const dx = tipPt.x - anchorPt.x;
    const dy = tipPt.y - anchorPt.y;

    const distance = Math.sqrt(dx * dx + dy * dy);
    const angle = Math.atan2(dy, dx) * (180 / Math.PI);

    setSvgPath(generateSvgString(distance, angle));
  };

  // --- HTML GENERATOR ---
  const getAnchorHtml = () => {
    const arrowImageSrc = svgToDataUri(svgPath);

    return `
        <div class="drag-lifter" style="position: relative; overflow: visible; width: 0; height: 0;">
            <img src="${arrowImageSrc}" style="position: absolute; top: -1000px; left: -1000px; width: 2000px; height: 2000px; pointer-events: none; display: block;" />
            
            <div style="position: absolute; top: -22px; left: -22px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; cursor: move; pointer-events: auto;">
                <svg width="24" height="24" viewBox="0 0 24 24" style="overflow: visible; pointer-events: none;">
                    <defs>
                        <filter id="redGlow" x="-50%" y="-50%" width="300%" height="300%">
                             <feDropShadow dx="0" dy="0" stdDeviation="5" flood-color="red" flood-opacity="1.0"/>
                        </filter>
                    </defs>
                    <circle cx="12" cy="12" r="8" fill="none" stroke="red" stroke-width="2" filter="url(#redGlow)"/>
                </svg>
            </div>
        </div>`;
  };

  // --- ZOOM/VIEW HANDLERS ---
  useMapEvents({ zoom: updateVisuals, viewreset: updateVisuals });

  // --- INITIALIZATION & MARKER SETUP ---
  useEffect(() => {
    updateVisuals();
    const d = latestData.current;
    const safeTipLat = d.tipLat ?? d.lat;
    const safeTipLon = d.tipLon ?? d.lon - 0.001;

    // 1. ANCHOR MARKER
    const anchor = L.marker([d.lat, d.lon], {
      icon: L.divIcon({
        className: "pz-container",
        html: getAnchorHtml(),
        iconSize: [0, 0],
        iconAnchor: [0, 0],
      }),
      draggable: true,
      zIndexOffset: 3000,
    }).addTo(map);

    // 2. TIP MARKER
    const tip = L.marker([safeTipLat, safeTipLon], {
      icon: L.divIcon({
        className: "pz-tip-wrapper",
        html: `
            <div class="drag-lifter" style="width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; cursor: crosshair;">
                <div style="background: white; border: 2px solid #0056b3; width: 16px; height: 16px; border-radius: 50%; box-shadow: 0 0 4px rgba(0,0,0,0.5);"></div>
            </div>
        `,
        iconSize: [44, 44],
        iconAnchor: [22, 22],
      }),
      draggable: true,
      zIndexOffset: 3100,
      opacity: 0,
    }).addTo(map);

    anchorMarkerRef.current = anchor;
    tipMarkerRef.current = tip;

    // --- HOVER LOGIC ---
    let isHovering = false;
    const showHandle = () => {
      isHovering = true;
      tip.setOpacity(1);
    };
    const hideHandle = () => {
      isHovering = false;
      setTimeout(() => {
        if (!isHovering && !tip.dragging?._draggable?._dragging)
          tip.setOpacity(0);
      }, 100);
    };
    anchor.on("mouseover", showHandle);
    anchor.on("mouseout", hideHandle);
    tip.on("mouseover", showHandle);
    tip.on("mouseout", hideHandle);

    // --- ANCHOR DRAG LOGIC ---
    anchor.on("dragstart", (e) => {
        if (e.target._icon) L.DomUtil.addClass(e.target._icon, 'mobile-lifting');
        tip.setOpacity(0); // Hide tip so it doesn't get left behind visually
    });

    anchor.on("drag", (e) => {
      const newAnchor = e.target.getLatLng();
      const currentData = latestData.current;
      const dLat = newAnchor.lat - currentData.lat;
      const dLon = newAnchor.lng - currentData.lon;
      tip.setLatLng([
        (currentData.tipLat || currentData.lat) + dLat,
        (currentData.tipLon || currentData.lon - 0.001) + dLon,
      ]);
    });

    anchor.on("dragend", (e) => {
      if (e.target._icon) L.DomUtil.removeClass(e.target._icon, 'mobile-lifting');
      if (isHovering) tip.setOpacity(1); // Bring tip back
      
      const newAnchor = e.target.getLatLng();
      const currentData = latestData.current;
      const dLat = newAnchor.lat - currentData.lat;
      const dLon = newAnchor.lng - currentData.lon;
      updatePZMarker(data.id, {
        lat: newAnchor.lat,
        lon: newAnchor.lng,
        tipLat: (currentData.tipLat || currentData.lat) + dLat,
        tipLon: (currentData.tipLon || currentData.lon - 0.001) + dLon,
      });
    });

    // --- TIP DRAG LOGIC WITH MAGIC MATH ---
    let isDraggingTip = false;
    const isMobile = window.matchMedia("(max-width: 850px), (pointer: coarse)").matches;

    const redrawTip = (newTipLatLng) => {
      const currentData = latestData.current;
      const anchorPt = map.latLngToContainerPoint([
        currentData.lat,
        currentData.lon,
      ]);
      const tipPt = map.latLngToContainerPoint(newTipLatLng);
      
      // FIX: The Magic Math! Pull the visual arrow tip UP 80px while dragging on mobile
      if (isDraggingTip && isMobile) {
          tipPt.y -= 80; 
      }

      const dx = tipPt.x - anchorPt.x;
      const dy = tipPt.y - anchorPt.y;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const angle = Math.atan2(dy, dx) * (180 / Math.PI);
      setSvgPath(generateSvgString(dist, angle));
    };

    tip.on("dragstart", (e) => {
        isDraggingTip = true;
        tip.setOpacity(1);
        if (e.target._icon) L.DomUtil.addClass(e.target._icon, 'mobile-lifting');
        redrawTip(e.target.getLatLng()); // Instantly snap arrow up
    });
    
    tip.on("drag", (e) => {
      redrawTip(e.target.getLatLng());
    });

    tip.on("dragend", (e) => {
      isDraggingTip = false;
      if (e.target._icon) L.DomUtil.removeClass(e.target._icon, 'mobile-lifting');

      const newTip = e.target.getLatLng();
      updatePZMarker(data.id, { tipLat: newTip.lat, tipLon: newTip.lng });
      
      redrawTip(newTip); // Instantly snap arrow back down
      if (!isHovering) tip.setOpacity(0);
    });

    anchor.on("contextmenu", (e) => {
      L.DomEvent.stopPropagation(e);
      if (window.confirm("Delete this PZ Marker?")) deletePZMarker(data.id);
    });

    return () => {
      anchor.remove();
      tip.remove();
    };
  }, [map, data.id]);

  // Sync Icon with State
  useEffect(() => {
    if (anchorMarkerRef.current) {
      anchorMarkerRef.current.setIcon(
        L.divIcon({
          className: "pz-container",
          html: getAnchorHtml(),
          iconSize: [0, 0],
          iconAnchor: [0, 0],
        }),
      );
    }
  }, [svgPath]);

  return null;
};

export default PZMarker;