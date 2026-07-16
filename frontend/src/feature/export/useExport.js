import { useState } from "react";

/**
 * Optional controlled-state shape:
 *   { exportBox: ExportBounds | null, setExportBox: React.Dispatch<React.SetStateAction<ExportBounds | null>> }
 * Capture/modal/progress runtime state intentionally remains internal to this hook.
 */
export const useExport = (targetLocation, options = {}) => {
  const [internalExportBox, setInternalExportBox] = useState(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState(0);
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const [capturedMapBlob, setCapturedMapBlob] = useState(null);
  const [exportSuccess, setExportSuccess] = useState(false);
  const controlledExportBox = options?.exportBox;
  const controlledSetExportBox = options?.setExportBox;
  const isExportBoxControlled =
    controlledExportBox !== undefined &&
    typeof controlledSetExportBox === "function";
  const exportBox = isExportBoxControlled
    ? controlledExportBox
    : internalExportBox;
  const setExportBox = isExportBoxControlled
    ? controlledSetExportBox
    : setInternalExportBox;

  const enableExportMode = () => {
    if (!targetLocation) {
      alert("Please find a location first.");
      return;
    }

    const centerLat = parseFloat(targetLocation[0]);
    const centerLon = parseFloat(targetLocation[1]);
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) return;

    const dLat = 0.0025;
    const aspectRatio = 663 / 555;
    const latRadians = centerLat * (Math.PI / 180);
    const dLng = (dLat * aspectRatio) / Math.cos(latRadians);
    const redBounds = [
      [centerLat - dLat, centerLon - dLng],
      [centerLat + dLat, centerLon + dLng],
    ];

    setExportBox(redBounds);
  };

  const updateExportBox = (id, newBounds) => {
    void id;
    setExportBox(newBounds);
  };

  const deleteExportBox = () => {
    setExportBox(null);
  };

  const handleExportComplete = (blob) => {
    setExportProgress(60);
    if (blob) {
      setCapturedMapBlob(blob);
      setIsExportModalOpen(true);
    } else {
      setIsExporting(true);
      setExportProgress(0);
    }
  };

  const handleFinalExport = (formData) => {
    if (!capturedMapBlob) return;

    setExportProgress(70);

    const imageUrl = URL.createObjectURL(capturedMapBlob);
    const imageLink = document.createElement("a");
    imageLink.href = imageUrl;
    imageLink.download = `LZ_${formData.lz_name}_Map.jpg`;
    document.body.appendChild(imageLink);
    imageLink.click();
    document.body.removeChild(imageLink);

    const apiPayload = new FormData();
    Object.keys(formData).forEach((key) => {
      apiPayload.append(key, formData[key]);
    });
    apiPayload.append("map_image", capturedMapBlob, "map_capture.jpg");

    fetch(`${process.env.REACT_APP_API_URL}/generate-excel`, {
      method: "POST",
      body: apiPayload,
    })
      .then((response) => response.blob())
      .then((blob) => {
        const excelUrl = URL.createObjectURL(blob);
        const excelLink = document.createElement("a");
        excelLink.href = excelUrl;
        excelLink.download = `LZ_${formData.lz_name}_Card.xlsx`;
        document.body.appendChild(excelLink);
        excelLink.click();
        document.body.removeChild(excelLink);

        setExportProgress(100);
        setTimeout(() => {
          setIsExporting(false);
          setExportProgress(0);
          setIsExportModalOpen(false);
          setExportSuccess(true);

          setTimeout(() => {
            setExportSuccess(false);
          }, 4000);
        }, 500);
      })
      .catch((error) => {
        console.error("Excel generation failed:", error);
        alert("Failed to generate Excel card. Please try again.");
      });
  };

  return {
    exportBox,
    setExportBox,
    isExporting,
    setIsExporting,
    exportProgress,
    setExportProgress,
    isExportModalOpen,
    setIsExportModalOpen,
    exportSuccess,
    enableExportMode,
    updateExportBox,
    deleteExportBox,
    handleExportComplete,
    handleFinalExport,
  };
};
