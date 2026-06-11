import { useState } from 'react';

export const useWeather = () => {
  const [winds, setWinds] = useState({ 
    speed: '--', dir: 0, temp: '--', pressure: '--', 
    station: 'AWAITING ANALYSIS', vis: '--', dew: '--' 
  });
  const [activeNotams, setActiveNotams] = useState("");
  const [loadingWeather, setLoadingWeather] = useState(false);

  const fetchWeather = async (lat, lon) => {
    setLoadingWeather(true);
    setWinds(prev => ({ ...prev, station: 'SEARCHING...' }));
    setActiveNotams("Fetching NOTAMs...");

    try {
      const url = `${process.env.REACT_APP_API_URL}/weather?lat=${lat}&lng=${lon}`;
      const res = await fetch(url);
      
      if (!res.ok) throw new Error("Failed");
      const data = await res.json();

      if (data && !data.error) {
        setWinds({
          speed: data.wind_spd_kts !== null ? data.wind_spd_kts : '--',
          dir: data.wind_dir || 0,
          temp: data.temp_c !== null ? data.temp_c : '--',
          pressure: data.pressure || '--',
          station: data.station_id || 'UNKNOWN',
          vis: data.vis_sm !== null ? data.vis_sm : '--',
          dew: data.dewp_c !== null ? data.dewp_c : '--',
        });
        setActiveNotams(data.notams || "No active NOTAMs available.");
      }
    } catch (e) {
      console.error("Weather error", e);
      setWinds(prev => ({ ...prev, station: 'API OFFLINE' }));
      setActiveNotams("NOTAM fetch failed due to server error.");
    } finally {
      setLoadingWeather(false);
    }
  };

  return { winds, activeNotams, setActiveNotams, loadingWeather, fetchWeather };
};