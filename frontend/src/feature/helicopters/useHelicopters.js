import { useState, useEffect } from 'react';
import { getDistanceFeet } from '../../utils/Helpers';

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
        const dist = getDistanceFeet(finalLat, finalLon, helo.lat, helo.lon);
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
  useEffect(() => {
    const alerts = [];
    
    const rotorDiameterFeet = 53.67; // 53 ft 8 in
    const minClearanceFeet = 60;    // Define your minimum safe edge-to-edge distance here

    for (let i = 0; i < helicopters.length; i++) {
      for (let j = i + 1; j < helicopters.length; j++) {
        
        // 1. Get center-to-center distance in feet
        const centerDist = getDistanceFeet(helicopters[i].lat, helicopters[i].lon, helicopters[j].lat, helicopters[j].lon);
        
        // 2. Subtract the radius of BOTH helicopters (which equals one full diameter)
        const edgeToEdgeDist = centerDist - rotorDiameterFeet;
        
        // 3. Check against your minimum clearance
        if (edgeToEdgeDist < minClearanceFeet) {
          
          // Use Math.max to prevent it from saying "-15ft apart" if they crash/overlap
          const displayDist = Math.max(0, Math.round(edgeToEdgeDist)); 
          
          alerts.push({
            id: `${helicopters[i].id}-${helicopters[j].id}`,
            message: `Separation Alert: Rotor edges are only ${displayDist}ft apart (Min: ${minClearanceFeet}ft).`
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