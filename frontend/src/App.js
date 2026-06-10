import React, { useState, useEffect } from "react";
import MapView from "./components/MapView";
import Controls from "./components/Controls";
import "./App.css";
import { getDistanceMeters, convertToLatLongString, isPointInPolygon } from "./utils/Helpers";
import ExportModal from './components/ExportModal';
import MobileQuickAccess from "./components/MobileQuickAccess";
import MobileGridInput from "./components/MobileGridInput";
import axios from "axios";

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
  const [proximityAlerts, setProximityAlerts] = useState([]);
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);
  const [gridInput, setGridInput] = useState("16S GD 6338 3202");
  const [isDrawingLZ, setIsDrawingLZ] = useState(false);
  const [drawingPoints, setDrawingPoints] = useState([]);
  const [customLZ, setCustomLZ] = useState(null);
  const [contextMenu, setContextMenu] = useState(null); // { x, y, type, data }
  const [clickedGrid, setClickedGrid] = useState("Loading...");
  const [mapStyle, setMapStyle] = useState("satellite");
  const [activeNotams, setActiveNotams] = useState("");

  const handleMapRightClick = async (lat, lon, x, y) => {
  setContextMenu({ x, y, type: 'map', lat, lon });
  setClickedGrid("Calculating...");
  try {
    const res = await axios.post(`${API_BASE_URL}/convert-to-mgrs`, { lat, lon });
    setClickedGrid(res.data.mgrs);
  } catch (err) {
    setClickedGrid(convertToLatLongString(lat, lon)); // Fallback to Lat/Lon if backend fails
  }
};

const handleSetAsTarget = () => {
    if (clickedGrid === "Calculating...") return;

    const newTarget = [contextMenu.lat, contextMenu.lon];

    // 1. Update the UI Text
    setGridInput(clickedGrid); 
    setMapData(prev => ({...prev, mgrs: clickedGrid}));
    
    // 2. Always clear the "Official" data when a target moves
    // (So the user is forced to hit Analyze for the new center point)
    setDetectedLZ(null);
    setTerrainData(null);

    // 3. SMART CLEAR: Only destroy the custom drawn LZ if the new target is OUTSIDE of it
    if (customLZ && !isPointInPolygon(newTarget, customLZ)) {
      setCustomLZ(null);
      setDrawingPoints([]);
    }
    
    // 4. Set the new target (This triggers your Doghouses)
    setTargetLocation(newTarget);
    
    // 5. Close the menu
    setContextMenu(null); 
  };

// 2. LZ Right-Click Handler
const handleLZRightClick = async (lat, lon, x, y) => {
  setContextMenu({ x, y, type: 'lz', lat, lon });
    setClickedGrid("Calculating...");
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-to-mgrs`, { lat, lon });
      setClickedGrid(res.data.mgrs);
    } catch (err) {
      setClickedGrid(convertToLatLongString(lat, lon)); 
    }
};

// 3. Drawing Controls
const toggleDrawingMode = () => {
  if (isDrawingLZ) {
    // Finish drawing
    if (drawingPoints.length > 2) {
      setCustomLZ(drawingPoints);
    }
    setIsDrawingLZ(false);
    setDrawingPoints([]);
  } else {
    // Start drawing
    setCustomLZ(null);
    setDrawingPoints([]);
    setIsDrawingLZ(true);
  }
};

  const handleSearch = async () => {
    setLoading(true);
    setMapData(prev => ({...prev, mgrs: gridInput}));
    try {
      const res = await axios.post(`${API_BASE_URL}/convert-grid`, { grid: gridInput });
      const { lat, lon } = res.data;
      setTargetLocation([lat, lon]);
    } catch (err) {
      alert("Error finding grid: " + err.message);
    } finally {
      setLoading(false);
      setIsMobileMenuOpen(false); // Closes menu if they searched from the sidebar
    }
  };

  const performTerrainAnalysis = async () => {
    if (!targetLocation && !customLZ) return;
    
    setLoading(true);
    setContextMenu(null); // Instantly hide the right-click menu
    
    try {
      // Find our center point to check elevation
      const centerLat = targetLocation ? targetLocation[0] : customLZ[0][0];
      const centerLon = targetLocation ? targetLocation[1] : customLZ[0][1];

      // 1. Fetch Elevation from the backend
      const analysis = await axios.post(`${API_BASE_URL}/analyze-field`, { 
        lat: centerLat, 
        lon: centerLon 
      });

      // 2. Set Lat/Long String and Elevation for the UI
      setLatLong(convertToLatLongString(centerLat, centerLon));
      if (analysis.data.elevation) {
        setGridElevation(analysis.data.elevation); 
      }

      // 3. Pipe the correct Polygon into the UI
      if (customLZ && customLZ.length > 2) {
        // --- CUSTOM DRAWN LZ ---
        // Setting this triggers your useEffect, which calculates Area, Capacity, AND the Heatmap!
        setDetectedLZ(customLZ); 
        setCustomLZ(null); // Clear the custom LZ so it doesn't interfere with future edits
      } else {
        // --- AUTO-DETECT LZ ---
        // Use the shape the backend found for us (This also triggers the useEffect)
        setDetectedLZ(analysis.data.suggested_lz); 
      }

    } catch (err) {
      alert("Error analyzing LZ: " + err.message);
    } finally {
      // Small delay to let the useEffect heatmap math finish before removing the loading screen
      setTimeout(() => setLoading(false), 500); 
    }
  };

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

  // Add this useEffect to watch your helicopters array
useEffect(() => {
  const alerts = [];
  const minDistance = 59; // 60 meters

  // Check every helo against every other helo
  for (let i = 0; i < assets.length; i++) {
    for (let j = i + 1; j < assets.length; j++) {
      const dist = getDistanceMeters(assets[i].lat, assets[i].lon, assets[j].lat, assets[j].lon);
      
      if (dist < minDistance) {
        alerts.push({
          id: `${assets[i].id}-${assets[j].id}`,
          message: `Separation Alert: Helicopters are only ${Math.round(dist)}m apart (Min: 60m).`
        });
      }
    }
  }
  
  setProximityAlerts(alerts);
}, [assets]); // Runs every time a helicopter moves

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

    let finalLat = targetLocation[0];
    let finalLon = targetLocation[1];
    let isClear = false;
    let attempts = 0;

    // Roughly 10 meters in decimal degrees (longitude)
    const offsetStep = 0.0001; 

    // Only check collision against OTHER helicopters
    const existingHelos = assets.filter(a => a.type === "helo");

    // Push it East 10m at a time until it is 60m away from every other helo
    while (!isClear && attempts < 50) {
      isClear = true;
      for (let helo of existingHelos) {
        const dist = getDistanceMeters(finalLat, finalLon, helo.lat, helo.lon);
        if (dist < 60) {
          isClear = false;
          finalLon += offsetStep; // Bump it East
          break; // Start the check over with the new coordinates
        }
      }
      attempts++;
    }

    const newHelo = {
      id: Date.now(),
      lat: finalLat,
      lon: finalLon,
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
      <button 
        className="mobile-hamburger-btn" 
        onClick={() => setIsMobileMenuOpen(true)}
      >
        ☰
      </button>
      <div className={`sidebar ${isMobileMenuOpen ? 'mobile-open' : ''}`}>
        <div className="sidebar-header">
          <button className="close-menu-btn mobile-only" onClick={() => setIsMobileMenuOpen(false)}>✕</button>
        </div>
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
        isMobileMenuOpen={isMobileMenuOpen}
        closeMobileMenu={() => setIsMobileMenuOpen(false)}
        gridInput={gridInput}               
        setGridInput={setGridInput}         
        handleSearch={handleSearch}       
        isDrawingLZ={isDrawingLZ}
        toggleDrawingMode={toggleDrawingMode}  
        mapStyle={mapStyle}
        setMapStyle={setMapStyle}
        performTerrainAnalysis={performTerrainAnalysis}
        setActiveNotams={setActiveNotams}
        />
      </div>
      <div className="map-area">
        <MobileGridInput
          gridInput={gridInput} 
          setGridInput={setGridInput} 
          handleSearch={handleSearch} 
        />
        <MobileQuickAccess 
            addHelo={addHelo}
            addPZMarker={addPZMarker}
            addSector={addSector}
            addUnit={addUnit}
            addGoAround={addGoAround}
            enableExportMode={enableExportMode}
            onDownloadClick={() => setIsExporting(true)}
            exportBox={exportBox}
            isExporting={isExporting}
            exportProgress={exportProgress}
        />
        <MapView
          targetLocation={targetLocation}
          mapData={mapData}
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
          isDrawingLZ={isDrawingLZ}
          drawingPoints={drawingPoints}
          setDrawingPoints={setDrawingPoints}
          customLZ={customLZ}
          handleMapRightClick={handleMapRightClick}
          handleLZRightClick={handleLZRightClick}
          setContextMenu={setContextMenu}
          mapStyle={mapStyle}
        />

        <div className="alert-queue">
          {proximityAlerts.map(alert => (
            <div key={alert.id} className="proximity-alert">
              ⚠️ {alert.message}
            </div>
          ))}
        </div>
      </div>

      {/* GLOBAL CONTEXT MENU */}
{contextMenu && (
  <div 
    style={{
      position: 'fixed', left: contextMenu.x, top: contextMenu.y,
      zIndex: 99999, background: '#1e293b', border: '1px solid #334155',
      color: 'white', padding: '8px', borderRadius: '8px',
      boxShadow: '0 4px 6px rgba(0,0,0,0.5)', minWidth: '150px'
    }}
  >
    {contextMenu.type === 'map' ? (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ padding: '4px', fontSize: '14px', fontWeight: 'bold', textAlign: 'center' }}>
          {clickedGrid}
        </div>
        <button 
          onClick={handleSetAsTarget}
          disabled={clickedGrid === "Calculating..."}
          style={{
            width: '100%', padding: '6px', background: '#10b981', 
            color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer',
            opacity: clickedGrid === "Calculating..." ? 0.5 : 1
          }}
        >
          Set as Target
        </button>
      </div>
    ) : (
      // --- THE NEW LZ CONTEXT MENU ---
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ padding: '4px', fontSize: '14px', fontWeight: 'bold', textAlign: 'center' }}>
          {clickedGrid}
        </div>
        
        <button 
          onClick={handleSetAsTarget}
          disabled={clickedGrid === "Calculating..."}
          style={{
            width: '100%', padding: '6px', background: '#10b981', 
            color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer',
            opacity: clickedGrid === "Calculating..." ? 0.5 : 1
          }}
        >
          Set as Target
        </button>

        <hr style={{ borderColor: '#334155', margin: '2px 0' }} />

        <button 
          onClick={performTerrainAnalysis}
          // The magic logic: Disabled if no target is set, OR if the target is outside the LZ
          disabled={!targetLocation || !isPointInPolygon(targetLocation, customLZ)}
          title={(!targetLocation || !isPointInPolygon(targetLocation, customLZ)) ? "You must set a target inside the LZ first" : ""}
          style={{
            width: '100%', padding: '8px', background: '#3b82f6', 
            color: 'white', border: 'none', borderRadius: '4px', 
            cursor: (!targetLocation || !isPointInPolygon(targetLocation, customLZ)) ? 'not-allowed' : 'pointer',
            opacity: (!targetLocation || !isPointInPolygon(targetLocation, customLZ)) ? 0.4 : 1
          }}
        >
          Analyze LZ
        </button>
      </div>
    )}
  </div>
)}

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
        proximityAlerts={proximityAlerts}
        activeNotams={activeNotams}
     />

     {exportSuccess && (
        <div className="success-toast">
          <span>✅ LZ/PZ Card successfully exported.</span>
        </div>
      )}

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
