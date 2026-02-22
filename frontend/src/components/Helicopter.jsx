import React, { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import { calculateAngle, getDistanceMeters } from '../utils/Helpers';

const getHeloIcon = (rot, sizePx = 40) => {
  const displayRot = Math.round(((rot % 360) + 360) % 360);
  const rotateIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="width: 14px; height: 14px;"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>`;

  const boxWidth = Math.max(sizePx + 100, 140);

  return L.divIcon({
    className: "helo-div-icon",
    html: `
        <div class="drag-lifter doghouse-interactive-wrapper" style="position: relative; width: 100%; height: 100%; pointer-events: none;">
            
            <div class="helo-interaction-group" style="position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); width: ${boxWidth}px; height: ${sizePx + 40}px; pointer-events: auto; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,0.01); border-radius: 8px;">
                
                <div style="position: absolute; left: 10px; top: 0; bottom: 0; display: flex; align-items: center; z-index: 10; pointer-events: none;">
                    <div class="dh-controls" style="pointer-events: none;">
                        <div class="dh-btn dh-rotate" title="Drag to Rotate" style="pointer-events: auto; width: 34px; height: 34px; min-width: 34px; min-height: 34px;">${rotateIcon}</div>
                    </div>
                </div>

                <div class="helo-body-wrapper" style="position: relative; display: flex; justify-content: center; align-items: center; width: ${sizePx}px; height: ${sizePx}px; z-index: 20; cursor: grab;">
                    <div class="heading-readout" data-type="heading" style="
                        position: absolute; 
                        top: -15px; 
                        background: rgba(0,0,0,0.3); 
                        color: white; 
                        padding: 2px 5px; 
                        border-radius: 2px; 
                        font-size: 9px; 
                        font-family: monospace;
                        pointer-events: none;
                        white-space: nowrap;
                    ">
                        ${displayRot}°
                    </div>
                    
                    <div class="helo-sprite" style="transform: rotate(${rot || 0}deg); display: flex; width: 100%; height: 100%;">
                        <img src="/icons/helicopter.png" style="width: 100%; height: 100%; object-fit: contain; pointer-events: none;" draggable="false" />
                    </div>
                </div>
            </div>
        </div>`,
    iconSize: [boxWidth, boxWidth],
    iconAnchor: [boxWidth / 2, boxWidth / 2],
  });
};

const Helicopter = ({ asset, updateAsset, deleteAsset, allAssets }) => {
  const map = useMap();
  const heloRef = useRef(null);
  const linesLayerRef = useRef(null);
  const stateRef = useRef(asset);
  
  const deleteAssetRef = useRef(deleteAsset);
  const allAssetsRef = useRef(allAssets);
  const updateRef = useRef(updateAsset);

  useEffect(() => {
    stateRef.current = asset;
    deleteAssetRef.current = deleteAsset;
    allAssetsRef.current = allAssets;
    updateRef.current = updateAsset;
  }, [asset, deleteAsset, allAssets, updateAsset]);

  const calculateSizePx = (lat) => {
    const zoom = map.getZoom();
    const rotorDiameterMeters = 16.357; 
    const metersPerPx = 156543.03392 * Math.cos((lat * Math.PI) / 180) / Math.pow(2, zoom);
    return Math.max(rotorDiameterMeters / metersPerPx, 15);
  };

  const drawLines = (currentLat, currentLon) => {
    if (!linesLayerRef.current) return;
    linesLayerRef.current.clearLayers(); 
    
    const otherHelos = allAssetsRef.current.filter(a => a.type === "helo" && a.id !== asset.id);
    
    otherHelos.forEach(other => {
        const dist = getDistanceMeters(currentLat, currentLon, other.lat, other.lon);
        const isViolation = dist < 59;
        const midLat = (currentLat + other.lat) / 2;
        const midLon = (currentLon + other.lon) / 2;
        
        const line = L.polyline([[currentLat, currentLon], [other.lat, other.lon]], {
            color: isViolation ? "#dc2626" : "#9ca3af",
            dashArray: "6, 6",
            weight: 2,
            opacity: 0.8,
            interactive: false
        });
        
        const badge = L.marker([midLat, midLon], {
            icon: L.divIcon({
                className: "distance-badge-icon",
                html: `<div style="
                background: ${isViolation ? '#dc2626' : '#374151'}; 
                color: white; 
                padding: 2px 6px; 
                border-radius: 10px; 
                font-size: 10px; 
                font-weight: bold; 
                border: 1px solid rgba(255,255,255,0.5); 
                white-space: nowrap; 
                width: max-content;
                transform: translate(-50%, -50%);
                box-shadow: 0 2px 4px rgba(0,0,0,0.5);
                ">${Math.round(dist)}m</div>`,
                iconSize: [0, 0], 
            }),
            interactive: false
        });
        
        linesLayerRef.current.addLayer(line);
        linesLayerRef.current.addLayer(badge);
    });
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
    const interactionGroup = element.querySelector('.helo-interaction-group');
    const bodyWrapper = element.querySelector('.helo-body-wrapper');

    if (interactionGroup) {
        L.DomEvent.on(interactionGroup, 'mouseleave', () => wrapper.classList.remove('show-controls'));
    }

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

        if (markerInst._icon) {
            L.DomUtil.addClass(markerInst._icon, 'mobile-lifting');
        }

        const onRotateDrag = (moveEvent) => {
          if (moveEvent.cancelable) moveEvent.preventDefault(); // Stop mobile browser scrolling
          const { x, y } = getEventPoint(moveEvent);
          if (x === undefined || y === undefined) return;

          const mouseLatLng = map.containerPointToLatLng(map.mouseEventToContainerPoint({ clientX: x, clientY: y }));
          const center = markerInst.getLatLng();
          const angle = calculateAngle(
            center.lat, center.lng, mouseLatLng.lat, mouseLatLng.lng
          );
          stateRef.current.rotation = angle;

          const sprite = element.querySelector('.helo-sprite');
          if (sprite) sprite.style.transform = `rotate(${angle}deg)`;
          
          const headingInput = element.querySelector('[data-type="heading"]');
          if (headingInput) headingInput.innerText = `${Math.round(((angle % 360) + 360) % 360)}°`;
        };

        const onRotateEnd = () => {
          isRotating = false;
          rotateBtn.classList.remove('active-rotate');
          map.dragging.enable();

          if (markerInst._icon) {
              L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
          }

          document.removeEventListener("mousemove", onRotateDrag);
          document.removeEventListener("touchmove", onRotateDrag);
          document.removeEventListener("mouseup", onRotateEnd);
          document.removeEventListener("touchend", onRotateEnd);

          updateRef.current(asset.id, {
            rotation: stateRef.current.rotation,
          });
        };

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
      let pressTimer;

      const startInteraction = (e) => {
        L.DomEvent.stop(e); 
        if (isMoving) return; 

        const { x, y } = getEventPoint(e);
        startX = x || 0;
        startY = y || 0;
        dragThresholdMet = false;

        pressTimer = setTimeout(() => {
          if (!dragThresholdMet) {
            if (window.confirm("Delete this helicopter?")) {
              deleteAssetRef.current(asset.id);
            }
          }
        }, 750); 

        map.dragging.disable();

        const onDrag = (moveEvent) => {
          if (!dragThresholdMet) {
            const currentPoint = getEventPoint(moveEvent);
            const dx = Math.abs((currentPoint.x || 0) - startX);
            const dy = Math.abs((currentPoint.y || 0) - startY);
            
            if (dx > 5 || dy > 5) { 
              dragThresholdMet = true;
              clearTimeout(pressTimer); 
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
            drawLines(latlng.lat, latlng.lng);
          }
        };

        const onDragEnd = () => {
          clearTimeout(pressTimer);
          map.dragging.enable();
          bodyWrapper.style.cursor = 'grab';

          document.removeEventListener("mousemove", onDrag);
          document.removeEventListener("touchmove", onDrag);
          document.removeEventListener("mouseup", onDragEnd);
          document.removeEventListener("touchend", onDragEnd);

          if (isMoving) {
            isMoving = false;
            if (markerInst._icon) L.DomUtil.removeClass(markerInst._icon, 'mobile-lifting');
            if (linesLayerRef.current) linesLayerRef.current.clearLayers();
            const pos = markerInst.getLatLng();
            updateRef.current(asset.id, { lat: pos.lat, lon: pos.lng });
          } else {
            wrapper.classList.toggle('show-controls');
          }
        };

        document.addEventListener("mousemove", onDrag, { passive: false });
        document.addEventListener("touchmove", onDrag, { passive: false });
        document.addEventListener("mouseup", onDragEnd);
        document.addEventListener("touchend", onDragEnd);
      };

      L.DomEvent.on(bodyWrapper, 'mousedown touchstart', startInteraction);

      L.DomEvent.on(bodyWrapper, 'contextmenu', (e) => {
        L.DomEvent.stop(e);
        if (window.confirm("Delete this helicopter?")) deleteAssetRef.current(asset.id);
      });
    }
  };

  useEffect(() => {
    const initialSize = calculateSizePx(asset.lat);

    const helo = L.marker([asset.lat, asset.lon], {
      icon: getHeloIcon(asset.rotation || 0, initialSize), 
      draggable: false, 
      zIndexOffset: 500,
    }).addTo(map);

    linesLayerRef.current = L.layerGroup().addTo(map);
    heloRef.current = helo;

    attachListeners(helo);
    setTimeout(() => attachListeners(helo), 100);

    const handleZoom = () => {
      const newSize = calculateSizePx(stateRef.current.lat);
      helo.setIcon(getHeloIcon(stateRef.current.rotation, newSize));
      setTimeout(() => attachListeners(helo), 50);
    };
    map.on("zoomend", handleZoom);

    const interactionGroup = helo.getElement()?.querySelector('.helo-interaction-group');
    if (interactionGroup) {
        L.DomEvent.on(interactionGroup, 'mouseenter', () => drawLines(stateRef.current.lat, stateRef.current.lon));
        L.DomEvent.on(interactionGroup, 'mouseleave', () => {
          setTimeout(() => {
            if (linesLayerRef.current) linesLayerRef.current.clearLayers();
          }, 50);
        });
    }

    return () => {
      map.off("zoomend", handleZoom); 
      helo.remove();
      if (linesLayerRef.current) {
          linesLayerRef.current.clearLayers();
          linesLayerRef.current.remove();
      }
    };
  }, [map, asset.id]);

  useEffect(() => {
    if (!heloRef.current) return;
    const currentSize = calculateSizePx(asset.lat);
    
    heloRef.current.setLatLng([asset.lat, asset.lon]);
    heloRef.current.setIcon(getHeloIcon(asset.rotation || 0, currentSize));
    setTimeout(() => attachListeners(heloRef.current), 50);
  }, [asset.lat, asset.lon, asset.rotation, map]);

  return null;
};

export default Helicopter;