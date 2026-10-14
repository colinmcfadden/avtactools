import { useCallback, useRef, useState } from 'react';
import api from '../auth/api';
import { slopeWindOf } from '../terrain/slopeCall';

/** Whether two [lat, lon] positions are within about a kilometre: the same LZ/PZ's weather (a station is miles away). */
export const sameWeatherSpot = (a, b) =>
  Array.isArray(a) && Array.isArray(b) &&
  Math.abs(a[0] - b[0]) < 0.009 &&
  Math.abs((a[1] - b[1]) * Math.cos((a[0] * Math.PI) / 180)) < 0.009;

/**
 * The wind the slope tile judges [target]'s limits by: the report's steady wind (slopeWindOf), when the last report is for that LZ/PZ.
 * Another LZ/PZ's report, or none, is no wind report.
 */
export const slopeWindFor = (winds, target) => (sameWeatherSpot(winds?.at, target) ? winds.slopeWind ?? null : null);

export const useWeather = () => {
  const [winds, setWinds] = useState({ 
    speed: '--', dir: 0, temp: '--', pressure: '--', 
    station: 'AWAITING ANALYSIS', vis: '--', dew: '--',
    slopeWind: null, at: null,
  });
  const [activeNotams, setActiveNotams] = useState("");
  const [loadingWeather, setLoadingWeather] = useState(false);
  // The latest request, so an answer that comes after a later one was asked (another LZ/PZ) is not shown, and where it was for.
  const latest = useRef(0);
  const askedAt = useRef(null);

  const fetchWeather = useCallback(async (lat, lon) => {
    const request = ++latest.current;
    askedAt.current = [lat, lon];
    setLoadingWeather(true);
    setWinds(prev => ({ ...prev, station: 'SEARCHING...' }));
    setActiveNotams("Fetching NOTAMs...");

    try {
      const res = await api.get('/weather', { params: { lat, lng: lon } });
      if (request !== latest.current) return;
      const data = res.data;

      if (data && !data.error) {
        setWinds({
          speed: data.wind_spd_kts !== null ? data.wind_spd_kts : '--',
          dir: data.wind_dir || 0,
          temp: data.temp_c !== null ? data.temp_c : '--',
          pressure: data.pressure || '--',
          station: data.station_id || 'UNKNOWN',
          vis: data.vis_sm !== null ? data.vis_sm : '--',
          dew: data.dewp_c !== null ? data.dewp_c : '--',
          slopeWind: slopeWindOf(data),
          at: [lat, lon],
        });
        setActiveNotams(data.notams || "No active NOTAMs available.");
      }
    } catch (e) {
      if (request !== latest.current) return;
      console.error("Weather error", e);
      setWinds(prev => ({ ...prev, station: 'API OFFLINE' }));
      setActiveNotams("NOTAM fetch failed due to server error.");
    } finally {
      if (request === latest.current) setLoadingWeather(false);
    }
  }, []);

  /** Fetches [lat, lon]'s weather unless the last request was for there: switching to another analysed LZ/PZ shows its own. */
  const ensureWeather = useCallback((lat, lon) => {
    if (!sameWeatherSpot(askedAt.current, [lat, lon])) fetchWeather(lat, lon);
  }, [fetchWeather]);

  return { winds, activeNotams, setActiveNotams, loadingWeather, fetchWeather, ensureWeather };
};
