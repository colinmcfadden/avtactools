import { useState, useEffect } from 'react';

export const useDoghouses = (targetLocation, setFlightData) => {
  const [doghouses, setDoghouses] = useState([]);

  const generateDoghouses = (center) => {
    const offset = 0.003;
    const houses = [
      { id: "dh1", lat: center.lat, lon: center.lng - offset, id_val: "[SP1]", heading: "000°", time: "01+57", dist: "3.13km", airspeed: "60 KIAS" },
      { id: "dh2", lat: center.lat - offset / 2, lon: center.lng - offset, id_val: "[RP1]", heading: "000°", time: "02+10", dist: "5.20km", airspeed: "40 KIAS" },
    ];
    setDoghouses(houses);
  };

  const updateDoghouse = (id, changes) => {
    setDoghouses((prev) => {
      return prev.map((dh) => {
        if (dh.id === id) {
           return { ...dh, ...changes };
        }
        return dh;
      });
    });
  };

  // 1. EVENT LISTENER: Listens for clicks requesting an edit prompt
  useEffect(() => {
    // Safety check: Only run if setFlightData was actually passed in
    if (!setFlightData) return; 

    const dh2 = doghouses.find(d => d.id === 'dh2');
    const dh1 = doghouses.find(d => d.id === 'dh1');

    if (dh2 || dh1) {
      setFlightData(prev => ({
        ...prev,
        landing_hdg: dh2 ? dh2.heading : prev.landing_hdg, 
        takeoff_hdg: dh1 ? dh1.heading : prev.takeoff_hdg
      }));
    }
  }, [doghouses, setFlightData]);

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

  // 2. AUTO-GENERATOR: Rebuilds doghouses when the target moves
  useEffect(() => {
    if (targetLocation) {
      generateDoghouses({ lat: targetLocation[0], lng: targetLocation[1] });
    }
  }, [targetLocation]);

  return {
    doghouses,
    setDoghouses,
    updateDoghouse
  };
};