import React, { useState, useEffect } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { calculateBearing } from "./utils/Bearing";

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

  // EXPORT STATE
  const [exportBox, setExportBox] = useState(null); // Just the Red Box bounds now
  const [isExporting, setIsExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState(0);

  const enableExportMode = () => {
    if (!targetLocation) {
      alert("Please find a location first.");
      return;
    }

    // --- THE FIX: Parse coordinates as floats first ---
    // This ensures we are doing math, not string concatenation.
    const centerLat = parseFloat(targetLocation[0]);
    const centerLon = parseFloat(targetLocation[1]);

    // Roughly calculate offsets for default sizes
    const dLat = 0.002;
    const dLng = 0.0025;

    // Red Box (Use the parsed numbers)
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

  const handleExportComplete = () => {
    setIsExporting(false);
    setExportProgress(0);
    // Optional: Keep the box on screen or remove it. Currently keeping it.
    // setExportBox(null);
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
        "http://127.0.0.1:5000/api/terrain-analysis",
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
      },
      {
        id: "dh2",
        lat: center.lat - offset / 2,
        lon: center.lng - offset,
        id_val: "[RP1]",
        heading: "000°",
        time: "02+10",
        dist: "5.20km",
      },
    ];
    setDoghouses(houses);
  };

  const updateDoghouse = (id, newPos) => {
    setDoghouses((prev) => {
      const currentHouse = prev.find((dh) => dh.id === id);
      if (!currentHouse) return prev;

      // 1. Calculate the new bearing based on the mouse/drag position
      // We calculate from the doghouse center to the new cursor position
      const newBearing = calculateBearing(
        currentHouse.lat,
        currentHouse.lon,
        newPos.lat,
        newPos.lng,
      );

      return prev.map((dh) => {
        // 2. MASTER SYNC: If updating dh2, apply bearing to ALL
        if (id === "dh2") {
          return {
            ...dh,
            // Only update position for the house actually being dragged
            lat: dh.id === id ? newPos.lat : dh.lat,
            lon: dh.id === id ? newPos.lng : dh.lon,
            // Update heading for EVERYONE
            heading: `${newBearing.toString().padStart(3, "0")}°`,
          };
        }

        // 3. INDEPENDENT UPDATE: For dh1 or others
        if (dh.id === id) {
          return {
            ...dh,
            lat: newPos.lat,
            lon: newPos.lng,
            heading: `${newBearing.toString().padStart(3, "0")}°`,
          };
        }

        return dh;
      });
    });
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
        />
      </div>
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
