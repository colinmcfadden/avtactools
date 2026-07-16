import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { mapObjectControlMarkup } from "../../utils/mapObjectControls";

const GoAroundMarker = ({ data, updateGoAround, deleteGoAround }) => {
  const map = useMap();
  const markerRef = useRef(null);
  
  const rotationRef = useRef(data.rotation || 0);
  const dataRef = useRef(data);
  const updateRef = useRef(updateGoAround);
  const deleteRef = useRef(deleteGoAround);

  useEffect(() => {
    dataRef.current = data;
    updateRef.current = updateGoAround;
    deleteRef.current = deleteGoAround;
  }, [data, updateGoAround, deleteGoAround]);

  const arrowColor = "#FFC107";
  const stripePattern = `
        <defs>
            <pattern id="diagonalHatch" patternUnits="userSpaceOnUse" width="8" height="8" patternTransform="rotate(0)">
                <path d="M0,8 L8,8" style="stroke:black; stroke-width:2" />
            </pattern>
        </defs>`;

  const rightArrowPath = "M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z";
  const leftArrowPath = "M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z";

  const getHtml = (ga, rotation) => {
    const isRight = ga.direction === "right";
    const path = isRight ? rightArrowPath : leftArrowPath;

    return `
      <div class="drag-lifter doghouse-interactive-wrapper" style="position: relative; width: 100%; height: 100%; pointer-events: none;">
        
        <div class="ga-interaction-group" style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); width: 160px; height: 120px; pointer-events: auto; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.01); border-radius: 8px;">
            
            <div class="map-object-controls dh-controls" style="position: absolute; left: -12px; right: -12px; top: 0; bottom: 0; display: flex; justify-content: space-between; align-items: center; z-index: 10; pointer-events: none;">
                ${mapObjectControlMarkup({ type: "rotate", title: "Drag to rotate go-around", className: "dh-btn dh-rotate" })}
                ${mapObjectControlMarkup({ type: "move", title: "Drag to move go-around", className: "dh-btn dh-move" })}
            </div>

            <div class="ga-body-wrapper" style="
                transform: rotate(${rotation}deg); 
                transform-origin: center center; 
                width: 100px; height: 100px; 
                display: flex; flex-direction: column; align-items: center; justify-content: center;
                pointer-events: auto; z-index: 20; position: relative; cursor: grab;
            ">
                <div style="
                    background: ${arrowColor}; 
                    border: 2px solid black; 
                    font-weight: bold; font-family: sans-serif; font-size: 14px;
                    padding: 2px 8px; margin-bottom: -15px; z-index: 10;
                    box-shadow: 2px 2px 0px rgba(0,0,0,0.5);
                    pointer-events: none;
                ">GA</div>
                
                <svg class="ga-sprite" width="100px" height="100px" viewBox="0 0 100 100" style="overflow: visible; pointer-events: none;">
                    ${stripePattern}
                    <path d="${path}" fill="${arrowColor}" stroke="black" stroke-width="2" />
                    <path d="${path}" fill="url(#diagonalHatch)" stroke="transparent" />
                </svg>
            </div>
        </div>
      </div>`;
  };

  // Helper to safely extract screen coordinates across all devices
  const getEventPoint = (e) => {
    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
  };

  const attachListeners = (markerInst) => {
    const element = markerInst.getElement();
    if (!element) return;

    const wrapper = element.querySelector('.doghouse-interactive-wrapper');
    const interactionGroup = element.querySelector('.ga-interaction-group');
    const bodyWrapper = element.querySelector('.ga-body-wrapper');
    const moveBtn = element.querySelector('.dh-move');

    if (interactionGroup) {
        L.DomEvent.on(interactionGroup, 'mouseleave', () => wrapper.classList.remove('show-controls'));
    }

    // --- 1. Fix Mobile Tap to Reveal Icons ---
    markerInst.off('click'); 
    markerInst.on('click', (e) => {
        const isBtn = e.originalEvent.target.closest('.dh-btn');
        if (!isBtn && wrapper) {
            wrapper.classList.toggle('show-controls');
        }
    });

    // --- Rotate Logic ---
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

        if (markerInst._icon) L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');

        const onRotateDrag = (moveEvent) => {
          if (moveEvent.cancelable) moveEvent.preventDefault(); // Stop mobile browser scrolling
          const { x, y } = getEventPoint(moveEvent);
          if (x === undefined || y === undefined) return;

          const mouseLatLng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
          const center = markerInst.getLatLng();
          
          // Math fix: Convert coordinates to radians for perfect rotation tracking
          const lat1 = center.lat * Math.PI / 180;
          const lon1 = center.lng * Math.PI / 180;
          const lat2 = mouseLatLng.lat * Math.PI / 180;
          const lon2 = mouseLatLng.lng * Math.PI / 180;

          const yVal = Math.sin(lon2 - lon1) * Math.cos(lat2);
          const xVal = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1);
          const newAngle = ((Math.atan2(yVal, xVal) * 180) / Math.PI + 360) % 360;

          rotationRef.current = newAngle;

          const body = element.querySelector('.ga-body-wrapper');
          if (body) body.style.transform = `rotate(${newAngle}deg)`;
        };

        const onRotateEnd = () => {
          isRotating = false;
          rotateBtn.classList.remove('active-rotate');
          map.dragging.enable();

          if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');

          document.removeEventListener("mousemove", onRotateDrag);
          document.removeEventListener("touchmove", onRotateDrag);
          document.removeEventListener("mouseup", onRotateEnd);
          document.removeEventListener("touchend", onRotateEnd);

          updateRef.current(dataRef.current.id, { rotation: rotationRef.current });
        };

        // Attach to Document
        document.addEventListener("mousemove", onRotateDrag, { passive: false });
        document.addEventListener("touchmove", onRotateDrag, { passive: false });
        document.addEventListener("mouseup", onRotateEnd);
        document.addEventListener("touchend", onRotateEnd);
      };

      L.DomEvent.on(rotateBtn, 'mousedown touchstart', startRotate);
    }

    // --- SMART TOUCH COMBINED LOGIC (Drag, Tap, Long-Press) ---
    if (bodyWrapper) {
      L.DomEvent.disableClickPropagation(bodyWrapper);

      let isMoving = false;
      let dragThresholdMet = false;
      let startX = 0, startY = 0;

      const startInteraction = (e) => {
        L.DomEvent.stop(e); 
        if (isMoving) return; 

        const { x, y } = getEventPoint(e);
        startX = x || 0;
        startY = y || 0;
        dragThresholdMet = false;

        map.dragging.disable();

        const onDrag = (moveEvent) => {
          if (!dragThresholdMet) {
            const currentPoint = getEventPoint(moveEvent);
            const dx = Math.abs((currentPoint.x || 0) - startX);
            const dy = Math.abs((currentPoint.y || 0) - startY);
            
            if (dx > 5 || dy > 5) { 
              dragThresholdMet = true;
              isMoving = true;
              bodyWrapper.style.cursor = 'grabbing';
              
              if (markerInst._icon) L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');
            }
          }

          if (isMoving) {
            if (moveEvent.cancelable) moveEvent.preventDefault();
            const { x, y } = getEventPoint(moveEvent);
            if (x === undefined || y === undefined) return;
            
            const latlng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
            markerInst.setLatLng(latlng);
          }
        };

        const onDragEnd = () => {
          map.dragging.enable();
          bodyWrapper.style.cursor = 'grab';

          document.removeEventListener("mousemove", onDrag);
          document.removeEventListener("touchmove", onDrag);
          document.removeEventListener("mouseup", onDragEnd);
          document.removeEventListener("touchend", onDragEnd);

          if (isMoving) {
            isMoving = false;
            if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
            const pos = markerInst.getLatLng();
            updateRef.current(dataRef.current.id, { lat: pos.lat, lon: pos.lng });
          } else {
            wrapper.classList.toggle('show-controls');
          }
        };

        // Attach to Document
        document.addEventListener("mousemove", onDrag, { passive: false });
        document.addEventListener("touchmove", onDrag, { passive: false });
        document.addEventListener("mouseup", onDragEnd);
        document.addEventListener("touchend", onDragEnd);
      };

      if (moveBtn) {
        L.DomEvent.disableClickPropagation(moveBtn);
        L.DomEvent.on(moveBtn, 'mousedown touchstart', startInteraction);
      }

      L.DomEvent.on(bodyWrapper, 'click', (e) => {
        L.DomEvent.stop(e);
        wrapper.classList.toggle('show-controls');
      });

      L.DomEvent.on(bodyWrapper, 'contextmenu', (e) => {
        L.DomEvent.preventDefault(e); 
        L.DomEvent.stopPropagation(e);
        if (window.confirm("Delete Go Around?")) deleteRef.current(dataRef.current.id);
      });
    }
  };

  useEffect(() => {
    const marker = L.marker([data.lat, data.lon], {
      icon: L.divIcon({
        className: "ga-container",
        html: getHtml(data, rotationRef.current),
        iconSize: [160, 120], 
        iconAnchor: [80, 60],
      }),
      draggable: false, 
      zIndexOffset: 1000,
    }).addTo(map);

    markerRef.current = marker;

    attachListeners(marker);
    setTimeout(() => attachListeners(marker), 100);

    return () => marker.remove();
  }, [map]);

  useEffect(() => {
    if (!markerRef.current) return;
    rotationRef.current = data.rotation || 0;

    markerRef.current.setIcon(
      L.divIcon({
        className: "ga-container",
        html: getHtml(data, rotationRef.current),
        iconSize: [160, 120],
        iconAnchor: [80, 60],
      })
    );
    
    markerRef.current.setLatLng([data.lat, data.lon]);
    setTimeout(() => attachListeners(markerRef.current), 50);
  }, [data.lat, data.lon, data.rotation, map]);

  return null;
};

export default GoAroundMarker;
