import React, { useEffect, useRef } from "react";
import L from "leaflet";
import { useMap } from "react-leaflet";
import { calculateBearing } from '../../utils/Helpers';
import { mapObjectControlMarkup } from '../../utils/mapObjectControls';
import {
  doghouseDisplay,
  doghouseFieldUpdates,
  doghouseHeadingDegrees,
  doghouseHeadingText,
  doghouseRotation,
} from "./doghouseFields";

const Doghouse = ({ data, updateDoghouse }) => {
  const map = useMap();
  const markerRef = useRef(null);
  const rotationRef = useRef(doghouseRotation(data));

  const dataRef = useRef(data);
  const updateRef = useRef(updateDoghouse);

  useEffect(() => {
    dataRef.current = data;
    updateRef.current = updateDoghouse;
  }, [data, updateDoghouse]);
  // No updateDoghouse (a read-only Mission Pack): it is shown, but cannot be moved, turned or typed in.
  const locked = !updateDoghouse;

  // --- HTML GENERATOR ---
  const getHtml = (dh, rotation, locked = false) => {
    const shown = doghouseDisplay(dh, rotation);
    const field = locked ? "default" : "text";

    return `
      <div class="drag-lifter doghouse-interactive-wrapper" style="position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; pointer-events: none;">
        
        ${locked ? "" : `<div class="map-object-controls dh-controls" style="position: absolute; width: 140px; display: flex; justify-content: space-between; align-items: center; z-index: 10;">
            ${mapObjectControlMarkup({ type: "rotate", title: "Drag to rotate doghouse", className: "dh-btn dh-rotate" })}
            ${mapObjectControlMarkup({ type: "move", title: "Drag to move doghouse", className: "dh-btn dh-move" })}
        </div>`}

        <div class="doghouse-wrapper" style="pointer-events: auto; width: 60px; transform: rotate(${rotation}deg); transform-origin: center center; position: absolute; z-index: 20;">
            <div style="width: 0; height: 0; border-left: 30px solid transparent; border-right: 30px solid transparent; border-bottom: 20px solid black; position: relative;">
                <div class="dh-input" data-type="id" style="position: absolute; top: 2px; left: -30px; width: 60px; text-align: center; font-weight: bold; font-size: 10px; color: white; cursor: ${field};">${shown.id}</div>
            </div>
            <div style="background: white; border: 2px solid black; width: 60px; display: flex; flex-direction: column; font-family: monospace; font-weight: bold; font-size: 12px; color: black;">
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="heading" style="cursor: ${field}; min-width: 20px; text-align: right; padding: 2px 0;">${shown.heading}</span>
                    <span style="pointer-events: none;">°</span>
                </div>
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="time-m" style="cursor: ${field}; min-width: 15px; text-align: right; padding: 2px 0;">${shown.minutes}</span>
                    <span style="pointer-events: none;">+</span>
                    <span class="dh-input" data-type="time-s" style="cursor: ${field}; min-width: 15px; text-align: left; padding: 2px 0;">${shown.seconds}</span>
                </div>
                <div style="border-bottom: 1px solid black; display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="dist" style="cursor: ${field}; min-width: 20px; text-align: right; padding: 2px 0;">${shown.distance}</span>
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> km</span>
                </div>
                <div style="display: flex; justify-content: center; align-items: center;">
                    <span class="dh-input" data-type="airspeed" style="cursor: ${field}; min-width: 20px; text-align: right; padding: 2px 0;">${shown.airspeed}</span>
                    <span style="font-size: 10px; margin-left: 1px; pointer-events: none;"> kts</span>
                </div>
            </div>
        </div>
      </div>`;
  };

  // Helper to extract screen coordinates reliably from either mouse or touch
  const getEventPoint = (e) => {
    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
  };

  // A move or turn the pack became read-only during (the owner finished it, or this person's role
  // was lowered) has nothing to take it when the finger lifts: show the doghouse as stored, locked.
  const putBack = (markerInst) => {
    const stored = dataRef.current;
    rotationRef.current = doghouseRotation(stored);
    markerInst.setIcon(
      L.divIcon({
        className: "doghouse-container",
        html: getHtml(stored, rotationRef.current, true),
        iconSize: [160, 120],
        iconAnchor: [80, 60],
      })
    );
    markerInst.setLatLng([stored.lat, stored.lon]);
    setTimeout(() => attachListeners(markerInst), 50);
  };

  const attachListeners = (markerInst) => {
    const element = markerInst.getElement();
    if (!element) return;
    const wrapper = element.querySelector('.doghouse-interactive-wrapper');

    // --- 1. Fix Mobile Tap to Reveal Icons ---
    markerInst.off('click'); 
    markerInst.on('click', (e) => {
        const isInput = e.originalEvent.target.classList.contains('dh-input');
        const isBtn = e.originalEvent.target.closest('.dh-btn');
        // Only toggle if they tapped the main body
        if (!isInput && !isBtn && wrapper) {
            wrapper.classList.toggle('show-controls');
        }
    });

    L.DomEvent.on(wrapper, 'mouseleave', () => {
        wrapper.classList.remove('show-controls');
    });

    // Nothing below may change it without updateDoghouse to take the change.
    if (!updateRef.current) return;

    // --- 2. Text Input Logic ---
    const inputs = element.querySelectorAll(".dh-input");
    inputs.forEach((span) => {
      L.DomEvent.disableClickPropagation(span);
      span.onclick = (e) => {
        L.DomEvent.stopPropagation(e);
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

        // Locked while it was being typed in: the redraw that locked it has already replaced the field.
        const update = updateRef.current;
        if (!update) return;

        const val = (span.innerText || "").trim();
        const type = span.getAttribute("data-type");
        const currentData = dataRef.current; 

        if (type === "heading") {
          const newDeg = doghouseHeadingDegrees(val);
          rotationRef.current = newDeg; 

          markerInst.setIcon(
            L.divIcon({
              className: "doghouse-container",
              html: getHtml({ ...currentData, heading: val }, newDeg),
              iconSize: [160, 120],
              iconAnchor: [80, 60],
            })
          );

          update(currentData.id, { heading: doghouseHeadingText(newDeg) });
          setTimeout(() => attachListeners(markerInst), 50);
        } else {
          let time;
          if (type.startsWith("time")) {
            const row = span.parentElement;
            time = {
              minutes: row.querySelector('[data-type="time-m"]').innerText,
              seconds: row.querySelector('[data-type="time-s"]').innerText,
            };
          }
          const updates = doghouseFieldUpdates(type, val, time);
          update(currentData.id, updates);
        }
      };

      span.onkeydown = (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          span.blur();
        }
      };
    });

    // --- 3. Custom Rotation Logic (Document-Level Tracking) ---
    const rotateBtn = element.querySelector('.dh-rotate');
    if (rotateBtn) {
      L.DomEvent.disableClickPropagation(rotateBtn);

      let isRotating = false;

      const startRotate = (e) => {
        L.DomEvent.stop(e); 
        if (isRotating) return; 
        
        isRotating = true;
        rotateBtn.classList.add('active-rotate');
        map.dragging.disable();

        const onRotateDrag = (moveEvent) => {
          if (moveEvent.cancelable) moveEvent.preventDefault(); // Stop mobile browser scrolling
          const { x, y } = getEventPoint(moveEvent);
          if (x === undefined || y === undefined) return;

          const mouseLatLng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
          const center = markerInst.getLatLng();

          let newAngle = calculateBearing(
            center.lat * (Math.PI / 180), center.lng * (Math.PI / 180),
            mouseLatLng.lat * (Math.PI / 180), mouseLatLng.lng * (Math.PI / 180)
          );
          rotationRef.current = newAngle;

          const body = element.querySelector('.doghouse-wrapper');
          if (body) body.style.transform = `rotate(${newAngle}deg)`;
          
          const headingInput = element.querySelector('[data-type="heading"]');
          if (headingInput) headingInput.innerText = Math.round(newAngle).toString().padStart(3, "0");
        };

        const onRotateEnd = () => {
          isRotating = false;
          rotateBtn.classList.remove('active-rotate');
          map.dragging.enable();

          // Unbind from document
          document.removeEventListener("mousemove", onRotateDrag);
          document.removeEventListener("touchmove", onRotateDrag);
          document.removeEventListener("mouseup", onRotateEnd);
          document.removeEventListener("touchend", onRotateEnd);

          const update = updateRef.current;
          if (!update) {
            putBack(markerInst);
            return;
          }
          update(dataRef.current.id, {
            heading: `${Math.round(rotationRef.current).toString().padStart(3, "0")}°`,
          });
        };

        // Bind to document to guarantee we track the finger even if it leaves the button
        document.addEventListener("mousemove", onRotateDrag, { passive: false });
        document.addEventListener("touchmove", onRotateDrag, { passive: false });
        document.addEventListener("mouseup", onRotateEnd);
        document.addEventListener("touchend", onRotateEnd);
      };

      L.DomEvent.on(rotateBtn, 'mousedown touchstart', startRotate);
    }

    // --- 4. Custom Drag/Move Logic (Document-Level Tracking) ---
    const moveBtn = element.querySelector('.dh-move');
    if (moveBtn) {
      L.DomEvent.disableClickPropagation(moveBtn);

      let isMoving = false;

      const startMove = (e) => {
        L.DomEvent.stop(e); 
        if (isMoving) return; 
        
        isMoving = true;
        moveBtn.classList.add('active-move');
        map.dragging.disable();
        
        if (markerInst._icon) {
            L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');
        }

        const onDrag = (moveEvent) => {
          if (moveEvent.cancelable) moveEvent.preventDefault(); // Stop mobile browser scrolling
          const { x, y } = getEventPoint(moveEvent);
          if (x === undefined || y === undefined) return;

          // Convert raw screen pixel to Map Coordinate
          const latlng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
          markerInst.setLatLng(latlng);
        };

        const onDragEnd = () => {
          isMoving = false;
          moveBtn.classList.remove('active-move');
          map.dragging.enable();
          
          if (markerInst._icon) {
              L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
          }

          // Unbind from document
          document.removeEventListener("mousemove", onDrag);
          document.removeEventListener("touchmove", onDrag);
          document.removeEventListener("mouseup", onDragEnd);
          document.removeEventListener("touchend", onDragEnd);

          const update = updateRef.current;
          if (!update) {
            putBack(markerInst);
            return;
          }
          const pos = markerInst.getLatLng();
          update(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
        };

        document.addEventListener("mousemove", onDrag, { passive: false });
        document.addEventListener("touchmove", onDrag, { passive: false });
        document.addEventListener("mouseup", onDragEnd);
        document.addEventListener("touchend", onDragEnd);
      };

      L.DomEvent.on(moveBtn, 'mousedown touchstart', startMove);
    }
  };

  // --- 1. SETUP EFFECT ---
  useEffect(() => {
    const marker = L.marker([data.lat, data.lon], {
      icon: L.divIcon({
        className: "doghouse-container",
        html: getHtml(data, rotationRef.current, !updateRef.current),
        iconSize: [160, 120], 
        iconAnchor: [80, 60],
      }),
      draggable: false, 
      zIndexOffset: 2000,
    }).addTo(map);

    markerRef.current = marker;

    attachListeners(marker);
    setTimeout(() => attachListeners(marker), 100);

    return () => {
      marker.remove();
    };
  }, [map]); 

  // --- 2. SYNC EFFECT ---
  useEffect(() => {
    if (!markerRef.current) return;

    const incomingHeading = doghouseRotation(data);
    rotationRef.current = incomingHeading;

    markerRef.current.setIcon(
      L.divIcon({
        className: "doghouse-container",
        html: getHtml(data, incomingHeading, locked),
        iconSize: [160, 120],
        iconAnchor: [80, 60],
      })
    );
    
    markerRef.current.setLatLng([data.lat, data.lon]);
    setTimeout(() => attachListeners(markerRef.current), 50);
  }, [data.lat, data.lon, data.heading, data.time, data.dist, data.airspeed, data.id_val, locked]);

  return null;
};

export default Doghouse;
