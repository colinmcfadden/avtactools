import { calculateBearing, getDistanceFeet } from "../../utils/Helpers";

/*
 * A backstop (the owner's, 2026-11-08): a reference for a crew that has overflown an LZ/PZ. A dashed line sketched wherever the
 * terrain or a landmark makes it plain, and an arrow from it back to the LZ/PZ, labelled with the azimuth and the distance in km.
 *
 * Only the line is kept ({ id, points: [{ lat, lon }, ...] }, in the diagram's graphics.backstops). The arrow starts at the line's
 * middle, so what the label says is always worked out from where the line and the target are now and can never disagree with them.
 * Pure, and held to contracts/fixtures/planning/backstops.json for the native apps.
 */

const FEET_PER_KM = 3280.84;

const isPoint = (point) => Number.isFinite(point?.lat) && Number.isFinite(point?.lon);

/** The drawn points that are places. */
export const backstopPoints = (backstop) => (Array.isArray(backstop?.points) ? backstop.points.filter(isPoint) : []);

export const createBackstop = (points, id) => ({ id, points: points.map(({ lat, lon }) => ({ lat, lon })) });

/** The middle of the line, by distance along it: where its arrow starts, and what it turns about. */
export const backstopAnchor = (points) => {
  if (!points?.length) return null;
  const legs = points.slice(1).map((point, i) => getDistanceFeet(points[i].lat, points[i].lon, point.lat, point.lon));
  let toGo = legs.reduce((sum, leg) => sum + leg, 0) / 2;
  for (let i = 0; i < legs.length; i += 1) {
    if (legs[i] > 0 && toGo <= legs[i]) {
      const along = toGo / legs[i];
      return {
        lat: points[i].lat + (points[i + 1].lat - points[i].lat) * along,
        lon: points[i].lon + (points[i + 1].lon - points[i].lon) * along,
      };
    }
    toGo -= legs[i];
  }
  return { lat: points[0].lat, lon: points[0].lon };
};

/**
 * The azimuth (degrees true, whole) and distance from the line back to the target (`[lat, lon]`), and the label's words.
 * Null for a line with no points or a target that is not a place.
 */
export const backstopReading = (points, target) => {
  const anchor = backstopAnchor(points);
  const lat = Number(target?.[0]);
  const lon = Number(target?.[1]);
  if (!anchor || !target || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const azimuth = calculateBearing(anchor.lat, anchor.lon, lat, lon) % 360;
  const distanceKm = getDistanceFeet(anchor.lat, anchor.lon, lat, lon) / FEET_PER_KM;
  return { anchor, azimuth, distanceKm, text: `${String(azimuth).padStart(3, "0")}°T · ${distanceKm.toFixed(1)} km` };
};

/** The line moved whole, by degrees of latitude and longitude. */
export const moveBackstopPoints = (points, dLat, dLon) => points.map(({ lat, lon }) => ({ lat: lat + dLat, lon: lon + dLon }));

/** The line turned clockwise about `about`, keeping its shape on the ground. */
export const rotateBackstopPoints = (points, about, degrees) => {
  const turn = (degrees * Math.PI) / 180;
  const sin = Math.sin(turn);
  const cos = Math.cos(turn);
  const stretch = Math.cos((about.lat * Math.PI) / 180);
  return points.map(({ lat, lon }) => {
    const east = (lon - about.lon) * stretch;
    const north = lat - about.lat;
    return {
      lat: about.lat - east * sin + north * cos,
      lon: about.lon + (east * cos + north * sin) / stretch,
    };
  });
};
