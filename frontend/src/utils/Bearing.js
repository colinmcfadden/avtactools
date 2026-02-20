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