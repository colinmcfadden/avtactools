import { useState, useEffect } from 'react';
import { getDistanceMeters } from '../../utils/Helpers';

export const useHelicopters = (targetLocation) => {
  const [helicopters, setHelicopters] = useState([]);
  const [proximityAlerts, setProximityAlerts] = useState([]);

  const addHelo = () => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }

    let finalLat = targetLocation[0];
    let finalLon = targetLocation[1];
    let isClear = false;
    let attempts = 0;
    const offsetStep = 0.0001; 

    // Auto-spacing logic
    while (!isClear && attempts < 50) {
      isClear = true;
      for (let helo of helicopters) {
        const dist = getDistanceMeters(finalLat, finalLon, helo.lat, helo.lon);
        if (dist < 60) {
          isClear = false;
          finalLon += offsetStep; 
          break; 
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
    
    setHelicopters((prev) => [...prev, newHelo]);
  };

  const updateHelicopter = (id, newProps) => {
    setHelicopters((prev) =>
      prev.map((helo) => (helo.id === id ? { ...helo, ...newProps } : helo))
    );
  };

  const deleteHelicopter = (id) => {
    setHelicopters((prev) => prev.filter((helo) => helo.id !== id));
  };

  // --- PROXIMITY ALERTS ---
  // Moved here because it relies entirely on the helicopters array
  useEffect(() => {
    const alerts = [];
    const minDistance = 59; // 60 meters

    for (let i = 0; i < helicopters.length; i++) {
      for (let j = i + 1; j < helicopters.length; j++) {
        const dist = getDistanceMeters(helicopters[i].lat, helicopters[i].lon, helicopters[j].lat, helicopters[j].lon);
        
        if (dist < minDistance) {
          alerts.push({
            id: `${helicopters[i].id}-${helicopters[j].id}`,
            message: `Separation Alert: Helicopters are only ${Math.round(dist)}m apart (Min: 60m).`
          });
        }
      }
    }
    
    setProximityAlerts(alerts);
  }, [helicopters]);

  return {
    helicopters,
    setHelicopters,
    addHelo,
    updateHelicopter,
    deleteHelicopter,
    proximityAlerts
  };
};