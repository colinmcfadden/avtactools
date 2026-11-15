import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import { captureCard } from "./captureCard";

/** Draws the LZ card's picture once an export is asked for (captureCard.js says how), and hands the JPEG up. */
const ExportHandler = ({ isExporting, exportBox, setExportProgress, onExportComplete }) => {
  const map = useMap();
  const processingRef = useRef(false);

  useEffect(() => {
    const runExport = async () => {
      if (!isExporting || !exportBox || processingRef.current) return;

      processingRef.current = true;
      setExportProgress(10);
      try {
        const blob = await captureCard(map, exportBox, { onProgress: setExportProgress });
        setExportProgress(60);
        onExportComplete(blob);
      } catch (err) {
        console.error("Export Failed:", err);
        alert("Export failed: " + err.message);
        onExportComplete(null);
      } finally {
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
