import { useState, useEffect } from "react";
import axios from "axios";
import { convertToLatLongString } from "../../utils/Helpers";

export const useTerrain = (
  targetLocation,
  setLoading,
  customLZ,
  setContextMenu,
  setLatLong,
  gridElevation,
  setGridElevation,
  detectedLZ,
  setDetectedLZ,
  setCustomLZ,
  fetchWeather,
) => {
  const [elevation, setElevation] = useState(null);
  const [slope, setSlope] = useState(null);
  const [terrainData, setTerrainData] = useState([]);

  const API_BASE_URL = process.env.REACT_APP_API_URL;

  const performTerrainAnalysis = async () => {
    if (!targetLocation && !customLZ) return;

    setLoading(true);
    setContextMenu(null); // Instantly hide the right-click menu

    try {
      // Find our center point to check elevation
      const centerLat = targetLocation ? targetLocation[0] : customLZ[0][0];
      const centerLon = targetLocation ? targetLocation[1] : customLZ[0][1];

      fetchWeather(centerLat, centerLon);

      // 1. Fetch Elevation from the backend
      const analysis = await axios.post(`${API_BASE_URL}/analyze-field`, {
        lat: centerLat,
        lon: centerLon,
      });

      // 2. Set Lat/Long String and Elevation for the UI
      setLatLong(convertToLatLongString(centerLat, centerLon));
      if (analysis.data.elevation) {
        setGridElevation(analysis.data.elevation);
      }

      // 3. Pipe the correct Polygon into the UI
      if (customLZ && customLZ.length > 2) {
        // --- CUSTOM DRAWN LZ ---
        // Setting this triggers your useEffect, which calculates Area, Capacity, AND the Heatmap
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

  const fetchTerrainAnalysis = async (polygon) => {
    try {
      const response = await fetch(`${API_BASE_URL}/terrain-analysis`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ polygon }),
      });
      const data = await response.json();
      if (data.status === "success") {
        setTerrainData(data.heatmap);
      }
    } catch (err) {
      console.error("Terrain API Error:", err);
    }
  };

  useEffect(() => {
    if (detectedLZ && detectedLZ.length > 0) {
      fetchTerrainAnalysis(detectedLZ);
    }
  }, [detectedLZ]);

  return {
    performTerrainAnalysis,
    elevation,
    slope,
    terrainData,
    setTerrainData,
  };
};
