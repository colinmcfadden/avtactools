export const isPointInPolygon = (point, polygon) => {
  if (!point || !polygon) return false;
  const x = point[0], y = point[1]; // target lat/lon
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const xi = polygon[i][0], yi = polygon[i][1];
    const xj = polygon[j][0], yj = polygon[j][1];
    const intersect = ((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
    if (intersect) inside = !inside;
  }
  return inside;
};

export const calculateBearing = (startLat, startLng, endLat, endLng) => {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const toDeg = (rad) => (rad * 180) / Math.PI;

  const lat1 = toRad(startLat); 
  const lat2 = toRad(endLat);
  const dLng = toRad(endLng - startLng);

  const y = Math.sin(dLng) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) -
            Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);

  const brng = toDeg(Math.atan2(y, x));
  return Math.round((brng + 360) % 360);
};

export const convertToLatLongString = (lat, lng) => {
  const toDMS = (coordinate, isLat) => {
    const absolute = Math.abs(coordinate);
    const degrees = Math.floor(absolute);
    const minutesNotTruncated = (absolute - degrees) * 60;
    const minutes = Math.floor(minutesNotTruncated);
    const seconds = ((minutesNotTruncated - minutes) * 60).toFixed(2);

    let direction = "";
    if (isLat) {
      direction = coordinate >= 0 ? "N" : "S";
    } else {
      direction = coordinate >= 0 ? "E" : "W";
    }

    return `${degrees}° ${minutes}' ${seconds}" ${direction}`;
  };

  return `${toDMS(lat, true)}  ${toDMS(lng, false)}`;
};

export const getDistanceMeters = (lat1, lon1, lat2, lon2) => {
  const R = 6371e3; // Earth radius in meters
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;

  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1 * rad) * Math.cos(lat2 * rad) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  
  return R * c; // Distance in meters
};

export const calculateAngle = (centerLat, centerLon, mouseLat, mouseLon) => {
  const dy = mouseLat - centerLat;
  const dx = mouseLon - centerLon;
  const theta = Math.atan2(dy, dx);
  let angle = theta * (180 / Math.PI) + 90;
  return angle;
};

export const calculateHandlePos = (lat, lon, rotation) => {
  const offset = 0.0001; // Distance of handle from center
  const angleRad = (rotation - 90) * (Math.PI / 180);
  return [lat - offset * Math.sin(angleRad), lon + offset * Math.cos(angleRad)];
};

export const calculateDoghouseHandlePos = (lat, lon) => {
  // Fixed offset to the North (approx 30 meters)
  // Since Lat increases as you go North, we just ADD to the latitude.
  // 0.0003 is roughly 33 meters, usually good for zoom level 18-20.
  const offset = 0.0003;

  return [lat + offset, lon];
};

export const formatMGRS = (val) => {
  // Strip all non-alphanumeric characters and force uppercase
  let clean = val.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
  if (!clean) return '';

  // Match Grid Zone Designator (1 or 2 digits + 1 letter)
  const gzdMatch = clean.match(/^(\d{1,2}[A-Z])/);
  
  if (gzdMatch) {
    let gzd = gzdMatch[1];
    clean = clean.substring(gzd.length);

    // Match 100km Square (1 or 2 letters)
    const sqMatch = clean.match(/^([A-Z]{1,2})/);
    let sq = '';
    let coords = '';

    if (sqMatch) {
      sq = sqMatch[1];
      coords = clean.substring(sq.length).replace(/[^\d]/g, ''); // Coordinates can only be digits
    } else {
      // If they haven't finished typing the square yet
      sq = clean.replace(/[^A-Z]/g, '');
    }

    // Rebuild the string with spaces
    let res = gzd;
    if (sq) res += ' ' + sq;
    
    if (coords) {
      // Dynamically split the coordinates down the middle
      const mid = Math.ceil(coords.length / 2);
      res += ' ' + coords.substring(0, mid);
      if (coords.length > 1) {
        res += ' ' + coords.substring(mid);
      }
    }
    return res;
  }
  
  // Return what they have if it isn't a full Grid Zone yet
  return clean;
};

export const getPolygonArea = (coords) => {
  if (!coords || coords.length < 3) return 0;
  const R = 6378137; // Earth radius in meters
  let area = 0;

  for (let i = 0; i < coords.length; i++) {
    const j = (i + 1) % coords.length;
    const lat1 = coords[i][0] * (Math.PI / 180);
    const lat2 = coords[j][0] * (Math.PI / 180);
    const lon1 = coords[i][1] * (Math.PI / 180);
    const lon2 = coords[j][1] * (Math.PI / 180);
    area += (lon2 - lon1) * (2 + Math.sin(lat1) + Math.sin(lat2));
  }
  area = (area * R * R) / 2.0;
  return Math.abs(area);
};