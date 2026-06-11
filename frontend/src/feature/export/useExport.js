import { useState } from 'react';

export const useExport = (targetLocation) => {
  const [exportBox, setExportBox] = useState(null);
  const [isExporting, setIsExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState(0);
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const [capturedMapBlob, setCapturedMapBlob] = useState(null);
  const [exportSuccess, setExportSuccess] = useState(false);

  // Map Capture Box Logic
  const enableExportMode = () => {
    if (!targetLocation) {
      alert("Please find a location first.");
      return;
    }

    const centerLat = parseFloat(targetLocation[0]);
    const centerLon = parseFloat(targetLocation[1]);

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
    setExportBox(newBounds);
  };

  const deleteExportBox = () => {
    setExportBox(null);
  };

  // Handling the Screenshot Blob
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

  // Final API Call
  const handleFinalExport = (formData) => {
    if (!capturedMapBlob) return;

    setExportProgress(70);

    // Download the Image
    const imgUrl = URL.createObjectURL(capturedMapBlob);
    const link = document.createElement('a');
    link.href = imgUrl;
    link.download = `LZ_${formData.lz_name}_Map.jpg`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    // Generate Excel
    const apiPayload = new FormData();
    Object.keys(formData).forEach(key => {
      apiPayload.append(key, formData[key]);
    });
    apiPayload.append("map_image", capturedMapBlob, "map_capture.jpg");

    fetch(`${process.env.REACT_APP_API_URL}/generate-excel`, {
      method: "POST",
      body: apiPayload
    })
    .then(response => response.blob())
    .then(blob => {
      // Download the Excel File
      const excelUrl = URL.createObjectURL(blob);
      const excelLink = document.createElement('a');
      excelLink.href = excelUrl;
      excelLink.download = `LZ_${formData.lz_name}_Card.xlsx`;
      document.body.appendChild(excelLink);
      excelLink.click();
      
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
    .catch(err => {
        console.error("Excel generation failed:", err);
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
    handleFinalExport
  };
};