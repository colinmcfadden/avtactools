import { useState, useEffect, useCallback, useRef } from "react";
import { convertToLatLongString } from "../../utils/Helpers";
import api from "../auth/api";

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
  options = {},
) => {
  const elevation = null;
  const slope = null;
  const [localTerrainData, setLocalTerrainData] = useState(null);

  const {
    analysisDiagramId = null,
    terrainData: controlledTerrainData,
    onAnalysisComplete,
    onTerrainData,
  } = options;
  const hasControlledTerrainData = Object.prototype.hasOwnProperty.call(
    options,
    "terrainData",
  );
  const terrainData = hasControlledTerrainData
    ? controlledTerrainData
    : localTerrainData;
  const terrainDataRef = useRef(terrainData);

  useEffect(() => {
    terrainDataRef.current = terrainData;
  }, [terrainData]);

  const setTerrainData = useCallback(
    (nextValue, diagramId = analysisDiagramId) => {
      const value =
        typeof nextValue === "function"
          ? nextValue(terrainDataRef.current)
          : nextValue;

      if (onTerrainData) {
        onTerrainData(value, diagramId);
        return;
      }

      setLocalTerrainData(value);
    },
    [analysisDiagramId, onTerrainData],
  );

  const performTerrainAnalysis = async () => {
    // A target is the required identity of an LZ/PZ diagram. A boundary alone
    // is not enough to start analysis because it cannot safely own the result.
    if (!targetLocation) {
      alert("Set a target on the map before analyzing the LZ/PZ.");
      return null;
    }

    const diagramIdAtStart = analysisDiagramId;

    setLoading(true);
    setContextMenu?.(null); // Instantly hide the right-click menu

    try {
      // Find our center point to check elevation
      const centerLat = targetLocation[0];
      const centerLon = targetLocation[1];

      fetchWeather?.(centerLat, centerLon);

      // 1. Fetch Elevation from the backend
      const analysis = await api.post("/analyze-field", {
        lat: centerLat,
        lon: centerLon,
      });

      const analysisPayload = {
        latLong: convertToLatLongString(centerLat, centerLon),
        gridElevation: analysis.data.elevation ?? "",
        customLZ: null,
        detectedLZ:
          customLZ && customLZ.length > 2
            ? customLZ
            : analysis.data.suggested_lz,
        results: analysis.data,
      };

      if (onAnalysisComplete) {
        onAnalysisComplete(analysisPayload, diagramIdAtStart);
      } else {
        // Backwards-compatible behavior for callers that have not adopted the
        // diagram workspace yet.
        setLatLong(analysisPayload.latLong);
        setGridElevation(analysisPayload.gridElevation);
        setDetectedLZ(analysisPayload.detectedLZ);
        setCustomLZ(null);
      }

      return analysisPayload;
    } catch (err) {
      alert("Error analyzing LZ: " + err.message);
    } finally {
      // Small delay to let the useEffect heatmap math finish before removing the loading screen
      setTimeout(() => setLoading(false), 500);
    }
  };

  const fetchTerrainAnalysis = useCallback(async (polygon, diagramId) => {
    try {
      const response = await api.post("/terrain-analysis", { polygon });
      const data = response.data;
      if (data.status === "success") {
        setTerrainData(data, diagramId);
      } else {
        throw new Error(data.error || "Terrain analysis failed");
      }
    } catch (err) {
      console.error("Terrain API Error:", err);
    }
  }, [setTerrainData]);

  useEffect(() => {
    if (detectedLZ && detectedLZ.length > 0) {
      // Capture the diagram identity at request time. If the user switches to
      // another draft before the request resolves, the result still belongs to
      // the initiating diagram rather than whichever diagram is active later.
      fetchTerrainAnalysis(detectedLZ, analysisDiagramId);
    }
  }, [detectedLZ, analysisDiagramId, fetchTerrainAnalysis]);

  return {
    performTerrainAnalysis,
    elevation,
    slope,
    terrainData,
    setTerrainData,
  };
};
