import React, { useState, useEffect } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { calculateBearing } from "./utils/Bearing";
import ExportModal from './components/ExportModal';

function App() {
  const [targetLocation, setTargetLocation] = useState(null); // {lat, lon}
  const [detectedLZ, setDetectedLZ] = useState(null);
  const [assets, setAssets] = useState([]);
  const [terrainData, setTerrainData] = useState(null);
  const [showHeatmap, setShowHeatmap] = useState(false);
  const [doghouses, setDoghouses] = useState([]);
  const [goAround, setGoAround] = useState([]);
  const [pzMarkers, setPzMarkers] = useState([]);
  const [units, setUnits] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showLZOutline, setShowLZOutline] = useState(true);
  const [sectors, setSectors] = useState([]);
  const [exportBox, setExportBox] = useState(null); 
  const [isExporting, setIsExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState(0);
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);
  const [capturedMapBlob, setCapturedMapBlob] = useState(null);
  const [gridElevation, setGridElevation] = useState("");
  const [latLong, setLatLong] = useState("");
  const [mapData, setMapData] = useState([]);
  const [flightData, setFlightData] = useState({});

  const API_BASE_URL = process.env.REACT_APP_API_URL;

  const enableExportMode = () => {
    if (!targetLocation) {
      alert("Please find a location first.");
      return;
    }

    const centerLat = parseFloat(targetLocation[0]);
    const centerLon = parseFloat(targetLocation[1]);

    // 1. Define the Height (Lat) first
    // 0.0025 degrees is roughly 275 meters tall
    const dLat = 0.0025; 

    // 2. Calculate Aspect Ratio from your Excel Template
    const aspectRatio = 663 / 555;

    // 3. Calculate Width (Lon) correcting for Latitude
    // We divide by cos(lat) because longitude lines shrink as you move north.
    // This ensures the box LOOKS correct on the screen.
    const latRadians = centerLat * (Math.PI / 180);
    const dLng = (dLat * aspectRatio) / Math.cos(latRadians);

    const redBounds = [
      [centerLat - dLat, centerLon - dLng], // SouthWest
      [centerLat + dLat, centerLon + dLng], // NorthEast
    ];

    setExportBox(redBounds);
};

  const updateExportBox = (id, newBounds) => {
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

  // Trigger terrain analysis automatically
  useEffect(() => {
    if (detectedLZ && detectedLZ.length > 0) {
      fetchTerrainAnalysis(detectedLZ);
    }
  }, [detectedLZ]);

  // --- ASSET MANAGEMENT FUNCTIONS ---
  const addSector = () => {
    if (!targetLocation) return;
    const centerLat = targetLocation[0];
    const centerLon = targetLocation[1];
    const offset = 0.001;
    const newSector = {
      id: `sec-${Date.now()}`,
      points: [
        { lat: centerLat + offset, lng: centerLon },
        { lat: centerLat - offset, lng: centerLon + offset },
        { lat: centerLat - offset, lng: centerLon - offset },
      ],
    };
    setSectors((prev) => [...prev, newSector]);
  };

  const updateSectorPoint = (id, pointIndex, newLat, newLng) => {
    setSectors((prev) =>
      prev.map((sec) => {
        if (sec.id !== id) return sec;
        const newPoints = [...sec.points];
        newPoints[pointIndex] = { lat: newLat, lng: newLng };
        return { ...sec, points: newPoints };
      }),
    );
  };

  const moveSector = (id, dLat, dLon) => {
    setSectors((prev) =>
      prev.map((sec) => {
        if (sec.id !== id) return sec;
        const newPoints = sec.points.map((p) => ({
          lat: p.lat + dLat,
          lng: p.lng + dLon,
        }));
        return { ...sec, points: newPoints };
      }),
    );
  };

  const deleteSector = (id) => {
    setSectors((prev) => prev.filter((s) => s.id !== id));
  };

  const updateAsset = (id, newProps) => {
    setAssets((prevAssets) =>
      prevAssets.map((asset) =>
        asset.id === id ? { ...asset, ...newProps } : asset,
      ),
    );
  };

  const addUnit = (unitConfig) => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }
    const offset = Math.random() * 0.001;
    const newUnit = {
      id: `unit-${Date.now()}`,
      type: unitConfig.id,
      path: unitConfig.path,
      lat: targetLocation[0] + offset,
      lon: targetLocation[1] + offset,
    };
    setUnits((prev) => [...prev, newUnit]);
  };

  const updateUnitPosition = (id, newLat, newLon) => {
    setUnits((prev) =>
      prev.map((u) => (u.id === id ? { ...u, lat: newLat, lon: newLon } : u)),
    );
  };

  const deleteUnit = (id) => {
    setUnits((prev) => prev.filter((u) => u.id !== id));
  };

  const addPZMarker = () => {
    if (!targetLocation) {
      alert("Please search for a location first.");
      return;
    }
    const startLat = parseFloat(targetLocation[0]);
    const startLon = parseFloat(targetLocation[1]);
    const newPZ = {
      id: `pz-${Date.now()}`,
      lat: startLat,
      lon: startLon,
      tipLat: startLat,
      tipLon: startLon - 0.002,
    };
    setPzMarkers((prev) => [...prev, newPZ]);
  };

  const updatePZMarker = (id, newProps) => {
    setPzMarkers((prev) =>
      prev.map((pz) => (pz.id === id ? { ...pz, ...newProps } : pz)),
    );
  };

  const deletePZMarker = (id) => {
    setPzMarkers((prev) => prev.filter((pz) => pz.id !== id));
  };

  const addGoAround = (direction) => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }
    const offset = 0.001;
    const newGA = {
      id: `ga-${Date.now()}`,
      lat:
        direction === "N"
          ? targetLocation[0] + offset
          : targetLocation[0] - offset,
      lon: targetLocation[1],
      direction: direction,
      rotation: 0,
    };
    setGoAround((prev) => [...prev, newGA]);
  };

  const updateGoAround = (id, newProps) => {
    setGoAround((prev) =>
      prev.map((ga) => (ga.id === id ? { ...ga, ...newProps } : ga)),
    );
  };

  const deleteGoAround = (id) => {
    setGoAround((prev) => prev.filter((ga) => ga.id !== id));
  };

  const fetchTerrainAnalysis = async (polygon) => {
    try {
      const response = await fetch(
        `${API_BASE_URL}/terrain-analysis`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ polygon }),
        },
      );
      const data = await response.json();
      if (data.status === "success") {
        setTerrainData(data.heatmap);
      }
    } catch (err) {
      console.error("Terrain API Error:", err);
    }
  };

  const deleteAsset = (id) => {
    setAssets((prevAssets) => prevAssets.filter((asset) => asset.id !== id));
  };

  const addHelo = () => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }
    const newHelo = {
      id: Date.now(),
      lat: targetLocation[0],
      lon: targetLocation[1],
      rotation: 0,
      type: "helo",
    };
    setAssets((prev) => [...prev, newHelo]);
  };

  const generateDoghouses = (center) => {
    const offset = 0.003;
    const houses = [
      {
        id: "dh1",
        lat: center.lat,
        lon: center.lng - offset,
        id_val: "[SP1]",
        heading: "000°",
        time: "01+57",
        dist: "3.13km",
        airspd: "60 KIAS",
      },
      {
        id: "dh2",
        lat: center.lat - offset / 2,
        lon: center.lng - offset,
        id_val: "[RP1]",
        heading: "000°",
        time: "02+10",
        dist: "5.20km",
        airspd: "40 KIAS",
      },
    ];
    setDoghouses(houses);
  };

  const updateDoghouse = (id, changes) => {
    setDoghouses((prev) => {
      return prev.map((dh) => {
        // Simple, independent update: Only update the doghouse that was touched
        if (dh.id === id) {
           return { ...dh, ...changes };
        }
        return dh;
      });
    });
  };

useEffect(() => {
    const dh2 = doghouses.find(d => d.id === 'dh2');
    const dh1 = doghouses.find(d => d.id === 'dh1');

    if (dh2 || dh1) {
      setFlightData(prev => ({
        ...prev,
        landing_hdg: dh2 ? dh2.heading : prev.landing_hdg, 
        takeoff_hdg: dh1 ? dh1.heading : prev.takeoff_hdg
      }));
    }
  }, [doghouses]);


  const handleFinalExport = (formData) => {
    if (!capturedMapBlob) return;

    setExportProgress(70);

    // A. Download the Image (The user gets the JPG immediately)
    const imgUrl = URL.createObjectURL(capturedMapBlob);
    const link = document.createElement('a');
    link.href = imgUrl;
    link.download = `LZ_${formData.lz_name}_Map.jpg`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    // B. Generate Excel (Send blob + form data to backend)
    const apiPayload = new FormData();
    
    // Append all text fields
    Object.keys(formData).forEach(key => {
      apiPayload.append(key, formData[key]);
    });

    // Append the image blob
    apiPayload.append("map_image", capturedMapBlob, "map_capture.jpg");

    // Call Backend
    fetch(`${process.env.REACT_APP_API_URL}/generate-excel`, {
      method: "POST",
      body: apiPayload
    })
    .then(response => response.blob())
    .then(blob => {
      // C. Download the Excel File
      const excelUrl = URL.createObjectURL(blob);
      const excelLink = document.createElement('a');
      excelLink.href = excelUrl;
      excelLink.download = `LZ_${formData.lz_name}_Card.xlsx`;
      document.body.appendChild(excelLink);
      excelLink.click();
      
      setExportProgress(100);
        
        // Give a tiny delay so the user sees the bar hit 100%
        setTimeout(() => {
            setIsExporting(false);
            setExportProgress(0);
            setIsExportModalOpen(false);
        }, 500);
    })
    .catch(err => console.error("Excel generation failed:", err));
  };

  useEffect(() => {
    const handleEdit = (e) => {
      const { id, field } = e.detail;
      const newVal = window.prompt(`Enter new value for ${field}:`);
      if (newVal !== null) {
        updateDoghouse(id, { [field]: newVal });
      }
    };
    window.addEventListener("edit-dh", handleEdit);
    return () => window.removeEventListener("edit-dh", handleEdit);
  }, []);

  useEffect(() => {
    if (targetLocation) {
      generateDoghouses({ lat: targetLocation[0], lng: targetLocation[1] });
    }
  }, [targetLocation]);

  return (
    <div className="app-container">
      <div className="sidebar">
        <h2 className="tactical-header">
          LZ Card Caddy <span className="version-tag">1.0.0-alpha.1</span>
        </h2>
        <Controls
          setTargetLocation={setTargetLocation}
          setDetectedLZ={setDetectedLZ}
          setAssets={setAssets}
          addHelo={addHelo}
          setShowHeatmap={setShowHeatmap}
          terrainData={terrainData}
          addGoAround={addGoAround}
          targetLocation={targetLocation}
          detectedLZ={detectedLZ}
          addPZMarker={addPZMarker}
          addUnit={addUnit}
          setLoading={setLoading}
          showLZOutline={showLZOutline}
          setShowLZOutline={setShowLZOutline}
          addSector={addSector}
          exportBox={exportBox}
          enableExportMode={enableExportMode}
          setIsExporting={setIsExporting}
          setExportProgress={setExportProgress}
          isExporting={isExporting}
          exportProgress={exportProgress}
          setMapData={setMapData}
          setLatLong={setLatLong}
          setGridElevation={setGridElevation}
          mapData={{
          elevation: gridElevation
        }}
        />
      </div>
      <div className="map-area">
        <MapView
          targetLocation={targetLocation}
          detectedLZ={detectedLZ}
          assets={assets}
          updateAsset={updateAsset}
          deleteAsset={deleteAsset}
          showHeatmap={showHeatmap}
          terrainData={terrainData}
          doghouses={doghouses}
          updateDoghouse={updateDoghouse}
          goArounds={goAround}
          updateGoAround={updateGoAround}
          deleteGoAround={deleteGoAround}
          updatePZMarker={updatePZMarker}
          deletePZMarker={deletePZMarker}
          pzMarkers={pzMarkers}
          units={units}
          updateUnitPosition={updateUnitPosition}
          showLZOutline={showLZOutline}
          deleteUnit={deleteUnit}
          sectors={sectors}
          updateSectorPoint={updateSectorPoint}
          moveSector={moveSector}
          deleteSector={deleteSector}
          exportBox={exportBox}
          updateExportBox={updateExportBox}
          deleteExportBox={deleteExportBox}
          isExporting={isExporting}
          onExportComplete={handleExportComplete}
          setExportProgress={setExportProgress}
          setExportBox={setExportBox}
          setIsExporting={setIsExporting}
        />
      </div>

      <ExportModal 
        isOpen={isExportModalOpen}
        onClose={() => {
          setIsExportModalOpen(false);
          setIsExporting(false);
          setExportProgress(0);
        }}
        onExport={handleFinalExport}
        mapData={{
          mgrs: mapData.mgrs,
          elevation: gridElevation,
          latLong: latLong
        }}
        flightData={flightData}
     />

      {loading && (
        <div className="loading-overlay">
          <div className="loader-container">
            <div className="spinner"></div>
            <div className="loading-text">
              Performing terrain and LZ/PZ analysis...
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
