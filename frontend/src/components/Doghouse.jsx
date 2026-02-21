import React, { useEffect, useRef, useState } from "react";
import L from "leaflet";
import {
  useMap,
} from "react-leaflet";
import { calculateBearing, calculateDoghouseHandlePos } from '../utils/Helpers';

const Doghouse = ({ data, updateDoghouse }) => {
  const map = useMap();
  const markerRef = useRef(null);
  const handleRef = useRef(null);
  const rotationRef = useRef(parseInt(data.heading) || 0);

  // Refs for state sync (prevents stale data in event listeners)
  const dataRef = useRef(data);
  const updateRef = useRef(updateDoghouse);

  useEffect(() => {
    dataRef.current = data;
    updateRef.current = updateDoghouse;
  }, [data, updateDoghouse]);

  // --- HTML GENERATOR ---
  const getHtml = (dh, rotation) => {
    const time = (dh.time || "00+00").split("+");
    const airspeed = dh.airspeed ? dh.airspeed.split(" ")[0] : "90";

    return `
        <div class="doghouse-wrapper" style="pointer-events: auto; cursor: grab; width: 60px; transform: rotate(${rotation}deg); transform-origin: center center;">
            <div style="width: 0; height: 0; border-left: 30px solid transparent; border-right: 30px solid transparent; border-bottom: 20px solid black; position: relative;">
                <div class="dh-input" data-type="id" style="position: absolute; top: 2px; left: -30px; width: 60px; text-align: center; font-weight: bold; font-size: 10px; color: white; cursor: text;">${dh.id_val}</div>
            </div>
            <div style="background: white; border: 2px solid black; width: 60px; display: flex; flex-direction: column; font-family: monospace; font-weight: bold; font-size: 12px; color: black;">
                
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="heading" style="cursor: text; min-width: 20px; text-align: right; padding: 2px 0;">${Math.round(rotation).toString().padStart(3, "0")}</span>
                    <span style="pointer-events: none;">°</span>
                </div>

                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="time-m" style="cursor: text; min-width: 15px; text-align: right; padding: 2px 0;">${time[0] || "00"}</span>
                    <span style="pointer-events: none;">+</span>
                    <span class="dh-input" data-type="time-s" style="cursor: text; min-width: 15px; text-align: left; padding: 2px 0;">${time[1] || "00"}</span>
                </div>

                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="dist" style="cursor: text; min-width: 20px; text-align: right; padding: 2px 0;">${parseFloat(dh.dist) || 0}</span>
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> km</span>
                </div>

                <div style="display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="airspeed" style="cursor: text; min-width: 20px; text-align: right; padding: 2px 0;">${parseInt(airspeed) || 90}</span>
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> kts</span>
                </div>
            </div>
        </div>`;
  };

  const attachListeners = (markerInst, handleInst) => {
    const element = markerInst.getElement();
    if (!element) return;
    const inputs = element.querySelectorAll(".dh-input");

    inputs.forEach((span) => {
      L.DomEvent.disableClickPropagation(span);
      span.addEventListener("mousedown", (e) => {
        e.stopPropagation();
        map.dragging.disable();
      });
      span.addEventListener("dblclick", (e) => e.stopPropagation());

      span.onclick = (e) => {
        e.stopPropagation();
        map.dragging.disable();
        span.contentEditable = "true";
        span.focus();
        span.style.backgroundColor = "#e6f7ff";
        document.execCommand("selectAll", false, null);
      };

      span.onblur = () => {
        span.contentEditable = "false";
        span.style.backgroundColor = "transparent";
        map.dragging.enable();

        const val = span.innerText.trim();
        const type = span.getAttribute("data-type");
        const currentData = dataRef.current; 

        if (type === "heading") {
          let newDeg = parseInt(val) || 0;
          newDeg = (newDeg + 360) % 360; 
          rotationRef.current = newDeg; 

          markerInst.setIcon(
            L.divIcon({
              className: "doghouse-container",
              html: getHtml({ ...currentData, heading: val }, newDeg),
              iconSize: [60, 100],
              iconAnchor: [30, 50],
            }),
          );

          handleInst.setLatLng(calculateDoghouseHandlePos(currentData.lat, currentData.lon, newDeg));
          updateRef.current(currentData.id, { heading: `${newDeg.toString().padStart(3, "0")}°` });
          setTimeout(() => attachListeners(markerInst, handleInst), 50);

        } else {
          // Reverted back to simple independent field updates
          let updates = {};
          if (type === "id") updates.id_val = val;
          else if (type === "dist") updates.dist = `${val}km`;
          else if (type === "airspeed") updates.airspeed = `${val} kts`;
          else if (type.startsWith("time")) {
            const row = span.parentElement;
            const m = row.querySelector('[data-type="time-m"]').innerText;
            const s = row.querySelector('[data-type="time-s"]').innerText;
            updates.time = `${m}+${s}`;
          }
          
          updateRef.current(currentData.id, updates);
        }
      };

      span.onkeydown = (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          span.blur();
        }
      };
    });
  };

  // --- 1. SETUP EFFECT ---
  useEffect(() => {
    const marker = L.marker([data.lat, data.lon], {
      icon: L.divIcon({
        className: "doghouse-container",
        html: getHtml(data, rotationRef.current),
        iconSize: [60, 100],
        iconAnchor: [30, 50],
      }),
      draggable: true,
      zIndexOffset: 2000,
    }).addTo(map);

    const handle = L.marker(calculateDoghouseHandlePos(data.lat, data.lon), {
      icon: L.divIcon({
        className: "rotate-handle",
        html: `<div style="background: white; border: 2px solid #0056b3; width: 12px; height: 12px; border-radius: 50%; cursor: grab;"></div>`,
        iconSize: [12, 12],
        iconAnchor: [30, 30],
      }),
      draggable: true,
      zIndexOffset: 2100,
      opacity: 0,
    }).addTo(map);

    markerRef.current = marker;
    handleRef.current = handle;

    attachListeners(marker, handle);
    setTimeout(() => attachListeners(marker, handle), 100);

    const show = () => handle.setOpacity(1);
    const hide = () => {
      setTimeout(() => {
        if (!handle.dragging?._draggable?._dragging) handle.setOpacity(0);
      }, 200);
    };

    marker.on("mouseover", show);
    marker.on("mouseout", hide);
    handle.on("mouseover", show);
    handle.on("mouseout", hide);

    marker.on("drag", (e) => {
      const pos = e.target.getLatLng();
      handle.setLatLng(calculateDoghouseHandlePos(pos.lat, pos.lng, rotationRef.current));
    });

    marker.on("dragend", (e) => {
      const pos = e.target.getLatLng();
      updateRef.current(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
      setTimeout(() => attachListeners(marker, handle), 50);
    });

    handle.on("drag", (e) => {
      handle.setOpacity(1);
      const center = marker.getLatLng();
      const mouse = e.target.getLatLng();
      const newAngle = calculateBearing(
        center.lat * (Math.PI / 180), center.lng * (Math.PI / 180),
        mouse.lat * (Math.PI / 180), mouse.lng * (Math.PI / 180)
      );
      rotationRef.current = newAngle;

      marker.setIcon(
        L.divIcon({
          className: "doghouse-container",
          html: getHtml(dataRef.current, newAngle),
          iconSize: [60, 100],
          iconAnchor: [30, 50],
        })
      );

      handle.setLatLng(calculateDoghouseHandlePos(center.lat, center.lng, newAngle));
    });

    handle.on("dragend", () => {
      updateRef.current(dataRef.current.id, {
        heading: `${Math.round(rotationRef.current).toString().padStart(3, "0")}°`,
      });
      setTimeout(() => attachListeners(marker, handle), 50);
    });

    return () => {
      marker.remove();
      handle.remove();
    };
  }, [map]); // Runs only on Mount

  // --- 2. SYNC EFFECT (Keeps DOM matching React State if props change) ---
  useEffect(() => {
    if (!markerRef.current || !handleRef.current) return;

    const incomingHeading = parseInt(data.heading) || 0;
    rotationRef.current = incomingHeading;

    markerRef.current.setIcon(
      L.divIcon({
        className: "doghouse-container",
        html: getHtml(data, incomingHeading),
        iconSize: [60, 100],
        iconAnchor: [30, 50],
      })
    );
    
    markerRef.current.setLatLng([data.lat, data.lon]);
    handleRef.current.setLatLng(calculateDoghouseHandlePos(data.lat, data.lon, incomingHeading));

    setTimeout(() => attachListeners(markerRef.current, handleRef.current), 50);
  }, [data.lat, data.lon, data.heading, data.time, data.dist, data.airspeed, data.id_val]);

  return null;
};

export default Doghouse;