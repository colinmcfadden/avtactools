import React, { useEffect, useRef } from "react";
import L from "leaflet";
import { useMap } from "react-leaflet";
import { calculateBearing } from '../../utils/Helpers';

const Doghouse = ({ data, updateDoghouse }) => {
  const map = useMap();
  const markerRef = useRef(null);
  const rotationRef = useRef(parseInt(data.heading) || 0);

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

    const rotateIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="width: 20px; height: 20px;"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>`;
    const moveIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width: 24px; height: 24px;"><polyline points="5 9 2 12 5 15"/><polyline points="9 5 12 2 15 5"/><polyline points="19 9 22 12 19 15"/><polyline points="9 19 12 22 15 19"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="12" y1="2" x2="12" y2="22"/></svg>`;

    return `
      <div class="drag-lifter doghouse-interactive-wrapper" style="position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; pointer-events: none;">
        
        <div class="dh-controls" style="position: absolute; width: 140px; display: flex; justify-content: space-between; align-items: center; z-index: 10;">
            <div class="dh-btn dh-rotate" title="Drag to Rotate" style="pointer-events: auto;">
                ${rotateIcon}
            </div>
            <div class="dh-btn dh-move" title="Drag to Move" style="pointer-events: auto;">
                ${moveIcon}
            </div>
        </div>

        <div class="doghouse-wrapper" style="pointer-events: auto; width: 60px; transform: rotate(${rotation}deg); transform-origin: center center; position: absolute; z-index: 20;">
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
        </div>
      </div>`;
  };

  // Helper to extract screen coordinates reliably from either mouse or touch
  const getEventPoint = (e) => {
    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
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

        const val = (span.innerText || "").trim();
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
              iconSize: [160, 120],
              iconAnchor: [80, 60],
            })
          );

          updateRef.current(currentData.id, { heading: `${newDeg.toString().padStart(3, "0")}°` });
          setTimeout(() => attachListeners(markerInst), 50);
        } else {
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

          updateRef.current(dataRef.current.id, {
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

          const pos = markerInst.getLatLng();
          updateRef.current(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
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
        html: getHtml(data, rotationRef.current),
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

    const incomingHeading = parseInt(data.heading) || 0;
    rotationRef.current = incomingHeading;

    markerRef.current.setIcon(
      L.divIcon({
        className: "doghouse-container",
        html: getHtml(data, incomingHeading),
        iconSize: [160, 120],
        iconAnchor: [80, 60],
      })
    );
    
    markerRef.current.setLatLng([data.lat, data.lon]);
    setTimeout(() => attachListeners(markerRef.current), 50);
  }, [data.lat, data.lon, data.heading, data.time, data.dist, data.airspeed, data.id_val]);

  return null;
};

export default Doghouse;