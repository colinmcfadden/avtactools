import React, { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import * as turf from "@turf/turf";

// --- HELPERS ---
const toTurf = (latlng) => turf.point([latlng.lng, latlng.lat]);

const getDistance = (p1, p2) => {
  if (!p1 || !p2) return "0 m";
  // Updated to meters
  const dist = turf.distance(toTurf(p1), toTurf(p2), { units: "meters" });
  return Math.round(dist) + " m";
};

const createVertexIcon = () =>
  L.divIcon({
    className: "vertex-handle",
    html: `<div style="background:white; border:2px solid #FF8C00; width:10px; height:10px; border-radius:50%; cursor:crosshair; box-shadow:0 1px 3px rgba(0,0,0,0.4);"></div>`,
    iconSize: [10, 10],
    iconAnchor: [5, 5],
  });

const createLabelIcon = (text) =>
  L.divIcon({
    className: "label-handle",
    html: `<div style="
        background: #FF8C00; 
        color: white; 
        padding: 1px 3px; 
        border-radius: 3px; 
        font-weight: bold; 
        font-size: 9px; 
        white-space: nowrap; 
        border: 1px solid white; 
        text-shadow: 0px 0px 1px black; 
        font-family: 'Arial Narrow', sans-serif; 
        box-shadow: 0 1px 3px rgba(0,0,0,0.3); 
        text-align: center; 
        display: inline-block; 
        width: auto;
        transform: translate(-50%, -50%);
        pointer-events: none; 
        user-select: none;">${text}</div>`,
    iconSize: [0, 0],
  });

const LZDimensions = ({ detectedLZ }) => {
  const map = useMap();

  const hoverTimers = useRef({});
  const isDraggingRef = useRef(false);
  const isHoveringRef = useRef({ len: false, wid: false });

  const layersRef = useRef({
    group: L.layerGroup(),

    lenLine: L.polyline([], { color: "#FF8C00", weight: 1, dashArray: "5,5" }),
    widLine: L.polyline([], { color: "#FF8C00", weight: 1, dashArray: "5,5" }),

    corner: L.marker([0, 0], {
      icon: createVertexIcon(),
      draggable: true,
      zIndexOffset: 1000,
      opacity: 0,
    }),
    lenTip: L.marker([0, 0], {
      icon: createVertexIcon(),
      draggable: true,
      zIndexOffset: 1000,
      opacity: 0,
    }),
    widTip: L.marker([0, 0], {
      icon: createVertexIcon(),
      draggable: true,
      zIndexOffset: 1000,
      opacity: 0,
    }),

    lenLabel: L.marker([0, 0], {
      icon: createLabelIcon("0 m"),
      draggable: true,
      zIndexOffset: 2000,
    }),
    widLabel: L.marker([0, 0], {
      icon: createLabelIcon("0 m"),
      draggable: true,
      zIndexOffset: 2000,
    }),
  });

  const stateRef = useRef({
    corner: null,
    lenTip: null,
    widTip: null,
    lenT: 0.1,
    widT: 0.1,
  });

  useEffect(() => {
    const layers = layersRef.current;
    layers.group.addTo(map);

    Object.values(layers).forEach((layer) => {
      if (layer !== layers.group) layer.addTo(layers.group);
    });

    return () => {
      layers.group.clearLayers();
      layers.group.remove();
    };
  }, [map]);

  useEffect(() => {
    if (!detectedLZ || detectedLZ.length < 3) return;
    if (stateRef.current.corner) return;

    const lats = detectedLZ.map((p) => p[0]);
    const lngs = detectedLZ.map((p) => p[1]);

    stateRef.current.corner = {
      lat: Math.max(...lats),
      lng: Math.min(...lngs),
    };
    stateRef.current.lenTip = {
      lat: Math.min(...lats),
      lng: Math.min(...lngs),
    };
    stateRef.current.widTip = {
      lat: Math.max(...lats),
      lng: Math.max(...lngs),
    };

    updateVisuals();
    setupEventListeners();
  }, [detectedLZ]);

  const updateVisuals = () => {
    const { corner, lenTip, widTip } = stateRef.current;
    const layers = layersRef.current;

    if (!corner || !lenTip || !widTip) return;

    layers.corner.setLatLng(corner);
    layers.lenTip.setLatLng(lenTip);
    layers.widTip.setLatLng(widTip);

    layers.lenLine.setLatLngs([corner, lenTip]);
    layers.widLine.setLatLngs([corner, widTip]);

    const lenMid = {
      lat: (corner.lat + lenTip.lat) / 2,
      lng: (corner.lng + lenTip.lng) / 2,
    };
    const widMid = {
      lat: (corner.lat + widTip.lat) / 2,
      lng: (corner.lng + widTip.lng) / 2,
    };

    layers.lenLabel.setLatLng(lenMid);
    layers.lenLabel.setIcon(createLabelIcon(getDistance(corner, lenTip)));

    layers.widLabel.setLatLng(widMid);
    layers.widLabel.setIcon(createLabelIcon(getDistance(corner, widTip)));
  };

  const setupEventListeners = () => {
    const layers = layersRef.current;

    const showGroup = (groupName, markers) => {
      isHoveringRef.current[groupName] = true;
      if (hoverTimers.current[groupName]) {
        clearTimeout(hoverTimers.current[groupName]);
        delete hoverTimers.current[groupName];
      }
      markers.forEach((m) => m.setOpacity(1));
    };

    const hideGroup = (groupName, markers) => {
      isHoveringRef.current[groupName] = false;
      hoverTimers.current[groupName] = setTimeout(() => {
        if (!isDraggingRef.current) {
          markers.forEach((m) => m.setOpacity(0));
        }
      }, 200);
    };

    const lenGroupMarkers = [layers.corner, layers.lenTip];
    const widGroupMarkers = [layers.corner, layers.widTip];

    // --- THROTTLED DRAG HANDLER ---
    const onDrag = (marker, callback, affectedGroups = []) => {
      marker.off("dragstart drag dragend");
      
      let rafId = null; // Track animation frames

      marker.on("dragstart", () => {
        isDraggingRef.current = true;
        marker.setOpacity(1);

        if (affectedGroups.includes("len"))
          lenGroupMarkers.forEach((m) => m.setOpacity(1));
        if (affectedGroups.includes("wid"))
          widGroupMarkers.forEach((m) => m.setOpacity(1));
      });

      marker.on("drag", (e) => {
        // 1. Update the underlying data immediately
        callback(e.latlng);

        // 2. Throttle the VISUAL update (the heavy part)
        // This prevents the "jumping" and "lagging" by syncing with the screen refresh
        if (rafId) cancelAnimationFrame(rafId);
        rafId = requestAnimationFrame(() => {
          updateVisuals();
        });
      });

      marker.on("dragend", () => {
        isDraggingRef.current = false;
        if (rafId) cancelAnimationFrame(rafId);

        affectedGroups.forEach((group) => {
          if (!isHoveringRef.current[group]) {
            const markers = group === "len" ? lenGroupMarkers : widGroupMarkers;
            markers.forEach((m) => m.setOpacity(0));
          }
        });
      });
    };

    onDrag(layers.corner, (pos) => { stateRef.current.corner = pos; }, ["len", "wid"]);

    onDrag(layers.lenTip, (pos) => {
        const { corner, lenT, widTip } = stateRef.current;
        const widAxisGeo = turf.lineString([toTurf(corner).geometry.coordinates, toTurf(widTip).geometry.coordinates]);
        const total = turf.length(widAxisGeo);
        if (total === 0) return;
        const sliderPt = turf.along(widAxisGeo, lenT * total).geometry.coordinates;
        stateRef.current.lenTip = { lat: pos.lat - (sliderPt[1] - corner.lat), lng: pos.lng - (sliderPt[0] - corner.lng) };
    }, ["len"]);

    onDrag(layers.widTip, (pos) => {
        const { corner, widT, lenTip } = stateRef.current;
        const lenAxisGeo = turf.lineString([toTurf(corner).geometry.coordinates, toTurf(lenTip).geometry.coordinates]);
        const total = turf.length(lenAxisGeo);
        if (total === 0) return;
        const sliderPt = turf.along(lenAxisGeo, widT * total).geometry.coordinates;
        stateRef.current.widTip = { lat: pos.lat - (sliderPt[1] - corner.lat), lng: pos.lng - (sliderPt[0] - corner.lng) };
    }, ["wid"]);

    onDrag(layers.lenLabel, (pos) => {
        const { corner, widTip } = stateRef.current;
        const widAxisGeo = turf.lineString([toTurf(corner).geometry.coordinates, toTurf(widTip).geometry.coordinates]);
        const snapped = turf.nearestPointOnLine(widAxisGeo, toTurf(pos));
        const total = turf.length(widAxisGeo);
        if (total > 0) stateRef.current.lenT = Math.max(0, Math.min(1, turf.distance(toTurf(corner), snapped) / total));
    }, ["len", "wid"]);

    onDrag(layers.widLabel, (pos) => {
        const { corner, lenTip } = stateRef.current;
        const lenAxisGeo = turf.lineString([toTurf(corner).geometry.coordinates, toTurf(lenTip).geometry.coordinates]);
        const snapped = turf.nearestPointOnLine(lenAxisGeo, toTurf(pos));
        const total = turf.length(lenAxisGeo);
        if (total > 0) stateRef.current.widT = Math.max(0, Math.min(1, turf.distance(toTurf(corner), snapped) / total));
    }, ["len", "wid"]);

    const lenTriggers = [layers.lenLine, layers.corner, layers.lenTip];
    lenTriggers.forEach((layer) => {
      layer.on("mouseover", () => showGroup("len", lenGroupMarkers));
      layer.on("mouseout", () => hideGroup("len", lenGroupMarkers));
    });

    const widTriggers = [layers.widLine, layers.corner, layers.widTip];
    widTriggers.forEach((layer) => {
      layer.on("mouseover", () => showGroup("wid", widGroupMarkers));
      layer.on("mouseout", () => hideGroup("wid", widGroupMarkers));
    });
  };

  return null;
};

export default LZDimensions;