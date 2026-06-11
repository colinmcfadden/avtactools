import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import L from "leaflet";
import * as htmlToImage from 'html-to-image';

const ExportHandler = ({ isExporting, exportBox, setExportProgress, onExportComplete }) => {
  const map = useMap();
  const processingRef = useRef(false);

  useEffect(() => {
    const runExport = async () => {
      // 1. Guard Clauses
      if (!isExporting || !exportBox || processingRef.current) return;
      
      processingRef.current = true;
      setExportProgress(10);

      const mapContainer = map.getContainer();

      // --- 1. SAVE ORIGINAL STYLES & EXACT VIEW ---
      const origWidth = mapContainer.style.width;
      const origHeight = mapContainer.style.height;
      const origPosition = mapContainer.style.position;
      const origTop = mapContainer.style.top;
      const origLeft = mapContainer.style.left;
      const origZIndex = mapContainer.style.zIndex;

      // Lock in the user's current zoom and map center
      const origZoom = map.getZoom();
      const origCenter = map.getCenter();
      
      // Calculate the exact center of the red export box
      const exportBoxCenter = L.latLngBounds(exportBox).getCenter();

      try {
        // --- 2. FORCE DESKTOP DIMENSIONS ---
        mapContainer.style.width = '1200px';
        mapContainer.style.height = '1000px';
        mapContainer.style.position = 'absolute';
        mapContainer.style.top = '0';
        mapContainer.style.left = '0';
        mapContainer.style.zIndex = '-1'; 

        // Tell Leaflet the map got bigger
        map.invalidateSize();
        
        // --- THE FIX: Center on the box, but FORCE the original zoom level ---
        map.setView(exportBoxCenter, origZoom, { animate: false });

        // Wait for tiles to settle and load from MapBox
        await new Promise((r) => setTimeout(r, 1500));
        setExportProgress(30);
        
        // Hide UI Elements via CSS Class
        mapContainer.classList.add('hide-ui-for-export');

        // Capture the full 1200x1000 canvas
        const fullCanvas = await htmlToImage.toCanvas(mapContainer, {
           quality: 1.0,
           pixelRatio: 2, 
           skipAutoScale: true,
           filter: (node) => {
             return !node.classList?.contains('leaflet-control-container') && 
                    !node.classList?.contains('ff-panel');
           }
        });
        setExportProgress(40);

        // Calculate Crop Coordinates using the perfectly locked zoom
        const p1 = map.latLngToContainerPoint(exportBox[0]); 
        const p2 = map.latLngToContainerPoint(exportBox[1]);
        
        const rawX = Math.min(p1.x, p2.x);
        const rawY = Math.min(p1.y, p2.y);
        const rawW = Math.abs(p1.x - p2.x);
        const rawH = Math.abs(p1.y - p2.y);

        const scale = 2;
        const x = rawX * scale;
        const y = rawY * scale;
        const w = rawW * scale;
        const h = rawH * scale;

        const cropCanvas = document.createElement('canvas');
        cropCanvas.width = w;
        cropCanvas.height = h;
        const ctx = cropCanvas.getContext('2d');

        ctx.drawImage(fullCanvas, x, y, w, h, 0, 0, w, h);

        // --- FIX: Wrap toBlob in a Promise to prevent the finally block from firing early ---
        const finalBlob = await new Promise((resolve) => {
            cropCanvas.toBlob((blob) => resolve(blob), 'image/jpeg', 1.0);
        });

        setExportProgress(60);
        
        // Pass the blob up safely!
        onExportComplete(finalBlob);

      } catch (err) {
        console.error("Export Failed:", err);
        alert("Export failed: " + err.message);
        onExportComplete(null);
      } finally {
        // --- 3. CLEANUP & RESTORE MOBILE LAYOUT ---
        mapContainer.classList.remove('hide-ui-for-export');
        
        mapContainer.style.width = origWidth || '100%';
        mapContainer.style.height = origHeight || '100%';
        mapContainer.style.position = origPosition || 'relative';
        mapContainer.style.top = origTop || '';
        mapContainer.style.left = origLeft || '';
        mapContainer.style.zIndex = origZIndex || '';

        // Tell Leaflet we shrunk back to mobile size
        map.invalidateSize();
        
        // --- THE FIX: Snap the user's view exactly back to where they were looking ---
        map.setView(origCenter, origZoom, { animate: false });
        
        // Slight delay to ensure processing lock clears cleanly
        setTimeout(() => {
          processingRef.current = false;
        }, 500);
      }
    };

    runExport();
    
  }, [isExporting, exportBox, map, onExportComplete, setExportProgress]); 

  return null;
};

export default ExportHandler;