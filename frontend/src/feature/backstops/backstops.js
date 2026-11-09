import { calculateBearing, getDistanceFeet } from "../../utils/Helpers";

/*
 * A backstop (the owner's, 2026-11-08): a reference for a crew that has overflown an LZ/PZ. A dashed line sketched wherever the
 * terrain or a landmark makes it plain, and an arrow from it back to the LZ/PZ, labelled with the azimuth and the distance in km.
 *
 * Kept as { id, points: [{ lat, lon }, ...], arrow?: { tail: { lat, lon }, tip?: { lat, lon } } }, in the diagram's graphics.backstops.
 * The line stays where it was sketched and the arrow is what moves (the owner's, 2026-11-09). With no `arrow` it runs from the line's
 * middle to the target, and a tip with no place of its own is on the target and follows it. The label is always worked out from where
 * the arrow is now, so it can never disagree with it. Pure, and held to contracts/fixtures/planning/backstops.json for the native apps.
 */

const FEET_PER_KM = 3280.84;

// A tip dropped this near the target goes back onto it, and an arrow is never made shorter than this, so it can still be picked up.
const TARGET_SNAP_PX = 20;
const MIN_ARROW_PX = 24;

const isPoint = (point) => Number.isFinite(point?.lat) && Number.isFinite(point?.lon);

const placeOf = (target) => {
  const lat = Number(target?.[0]);
  const lon = Number(target?.[1]);
  return target && Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
};

/** The drawn points that are places. */
export const backstopPoints = (backstop) => (Array.isArray(backstop?.points) ? backstop.points.filter(isPoint) : []);

export const createBackstop = (points, id) => ({ id, points: points.map(({ lat, lon }) => ({ lat, lon })) });

/** The middle of the line, by distance along it: where its arrow starts until it is moved. */
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
 * Where the arrow runs now: { tail, tip, onTarget }, for a target of `[lat, lon]`. Null when it has nowhere to start or nothing to
 * point at.
 */
export const backstopArrow = (backstop, target) => {
  const tail = isPoint(backstop?.arrow?.tail) ? backstop.arrow.tail : backstopAnchor(backstopPoints(backstop));
  const onTarget = !isPoint(backstop?.arrow?.tip);
  const tip = onTarget ? placeOf(target) : backstop.arrow.tip;
  return tail && tip ? { tail, tip, onTarget } : null;
};

/** The arrow's azimuth (degrees true, whole) and its length, and the label's words. Null for no arrow. */
export const backstopReading = (arrow) => {
  if (!arrow) return null;
  const { tail, tip } = arrow;
  const azimuth = calculateBearing(tail.lat, tail.lon, tip.lat, tip.lon) % 360;
  const distanceKm = getDistanceFeet(tail.lat, tail.lon, tip.lat, tip.lon) / FEET_PER_KM;
  return { azimuth, distanceKm, text: `${String(azimuth).padStart(3, "0")}°T · ${distanceKm.toFixed(1)} km` };
};

/** The arrow moved whole, by degrees of latitude and longitude. Its tip goes with it, off the target if it was on it. */
export const moveBackstopArrow = ({ tail, tip }, dLat, dLon) => {
  const moved = ({ lat, lon }) => ({ lat: lat + dLat, lon: lon + dLon });
  return { tail: moved(tail), tip: moved(tip) };
};

/**
 * The arrow with its tip dragged to `at`, its tail staying. Null when that would leave it too short to pick up again. `pixelsTo(place)` is
 * how far a place is from `at` on the screen.
 */
export const aimBackstopArrow = ({ tail }, at, target, pixelsTo) => {
  if (pixelsTo(tail) < MIN_ARROW_PX) return null;
  const onTarget = placeOf(target);
  return onTarget && pixelsTo(onTarget) <= TARGET_SNAP_PX ? { tail } : { tail, tip: at };
};
