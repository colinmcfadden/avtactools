import { getDistanceFeet } from "../../utils/Helpers";

/*
 * The FLOT, the forward line of own troops (the owner's, 2026-11-08): a line of semicircles drawn across the map, kept with a set of
 * routes beside its routes (`flots` of the set's document, left out when there are none) as { id, points: [{ lat, lon }, ...] }, with
 * `flipped: true` when its scallops face the other way. It is no route, so it is never written to an AMPS or ForeFlight file.
 *
 * Scallops are the size of the screen's, not the ground's, so they are worked out for the zoom in screen pixels (MapView's Flot).
 * By default they are to the right of the direction the line was drawn in; flipping puts them on the other side.
 */

const FEET_PER_KM = 3280.84;
const ARC_STEPS = 8;

/** Across one scallop, in screen pixels. */
export const SCALLOP_PX = 18;

const isPoint = (point) => Number.isFinite(point?.lat) && Number.isFinite(point?.lon);

export const flotPoints = (flot) => (Array.isArray(flot?.points) ? flot.points.filter(isPoint) : []);

export const createFlot = (points, id) => ({ id, points: points.map(({ lat, lon }) => ({ lat, lon })) });

/** The FLOT with its scallops on the other side. `flipped` is left out unless it is true, so an unflipped FLOT keeps one shape. */
export const flipFlot = (flot) => {
  const { flipped, ...rest } = flot;
  return flipped ? rest : { ...rest, flipped: true };
};

/** The FLOTs of a set's saved data; whatever is not an object with an id is none. */
export const flotsFromData = (data) =>
  (Array.isArray(data?.flots) ? data.flots : []).filter((flot) => flot && typeof flot === "object" && flot.id !== undefined);

/** What is saved of FLOTs: without the set they are filed under here. */
export const savedFlots = (flots) => (flots ?? []).map(({ setId: _filed, ...flot }) => flot);

export const flotLengthKm = (flot) => {
  const points = flotPoints(flot);
  const feet = points.slice(1).reduce((sum, point, i) => sum + getDistanceFeet(points[i].lat, points[i].lon, point.lat, point.lon), 0);
  return feet / FEET_PER_KM;
};

/** The sketched sets (useRouteSketch's) with their FLOTs, and a set that has FLOTs and no routes; this session's own first. */
export const setsWithFlots = (sketchSets, flots, isOpenedSetId) => {
  const ofSet = (setId) => flots.filter((flot) => (flot.setId ?? null) === setId);
  const known = new Set(sketchSets.map((set) => set.setId));
  const extra = [...new Set(flots.map((flot) => flot.setId ?? null))]
    .filter((setId) => !known.has(setId) && (setId === null || isOpenedSetId(setId)))
    .map((setId) => ({ setId, routes: [] }));
  const session = extra.filter((set) => set.setId === null);
  const opened = extra.filter((set) => set.setId !== null);
  return [...session, ...sketchSets, ...opened].map((set) => ({ ...set, flots: ofSet(set.setId) }));
};

// -- On the screen ------------------------------------------------------------------------------------------------------------------

const legsOf = (pixels) => pixels.slice(1).map((point, i) => Math.hypot(point.x - pixels[i].x, point.y - pixels[i].y));

const leftOutside = (point, within) =>
  within && (point.x < within.minX || point.x > within.maxX || point.y < within.minY || point.y > within.maxY);

/** A semicircle from `a` to `b`, without its first point. Screen y runs downward, so (-dy, dx) is to the right of a to b. */
const arcAfter = (a, b, flipped) => {
  const cx = (a.x + b.x) / 2;
  const cy = (a.y + b.y) / 2;
  const rx = a.x - cx;
  const ry = a.y - cy;
  const side = flipped ? -1 : 1;
  const nx = side * ry;
  const ny = side * -rx;
  const points = [];
  for (let i = 1; i <= ARC_STEPS; i += 1) {
    const t = (i / ARC_STEPS) * Math.PI;
    points.push({ x: cx + rx * Math.cos(t) + nx * Math.sin(t), y: cy + ry * Math.cos(t) + ny * Math.sin(t) });
  }
  return points;
};

/**
 * The scallops along a line given in screen pixels: lists of pixels, one for each unbroken run. A whole number of scallops as near
 * `diameter` as fits, each between two places on the line, so they follow it round a corner and the last ends where it ends.
 * With `within` ({ minX, minY, maxX, maxY }) a scallop off that box is left out: at a close zoom a long FLOT is tens of thousands of them.
 */
export const scallopRuns = (pixels, { diameter = SCALLOP_PX, flipped = false, within = null } = {}) => {
  if (pixels.length < 2) return [];
  const legs = legsOf(pixels);
  const total = legs.reduce((sum, leg) => sum + leg, 0);
  if (!(total > 0)) return [];
  const count = Math.max(1, Math.round(total / diameter));
  const chord = total / count;

  let leg = 0;
  let start = 0;
  const placeAt = (distance) => {
    while (leg < legs.length - 1 && distance > start + legs[leg]) {
      start += legs[leg];
      leg += 1;
    }
    const along = legs[leg] > 0 ? (distance - start) / legs[leg] : 0;
    return { x: pixels[leg].x + (pixels[leg + 1].x - pixels[leg].x) * along, y: pixels[leg].y + (pixels[leg + 1].y - pixels[leg].y) * along };
  };

  const runs = [];
  let run = null;
  let from = pixels[0];
  for (let i = 1; i <= count; i += 1) {
    const to = i === count ? pixels[pixels.length - 1] : placeAt(i * chord);
    const middle = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 };
    if (leftOutside(middle, within)) {
      run = null;
    } else {
      if (!run) {
        run = [from];
        runs.push(run);
      }
      run.push(...arcAfter(from, to, flipped));
    }
    from = to;
  }
  return runs;
};

/** The middle of the line by distance along it, and the unit vector away from the side its scallops are on (where its label goes). */
export const flotLabelSpot = (pixels, flipped = false) => {
  if (pixels.length < 2) return null;
  const legs = legsOf(pixels);
  let toGo = legs.reduce((sum, leg) => sum + leg, 0) / 2;
  for (let i = 0; i < legs.length; i += 1) {
    if (legs[i] > 0 && toGo <= legs[i]) {
      const a = pixels[i];
      const b = pixels[i + 1];
      const along = toGo / legs[i];
      const ux = (b.x - a.x) / legs[i];
      const uy = (b.y - a.y) / legs[i];
      const side = flipped ? -1 : 1;
      return { x: a.x + (b.x - a.x) * along, y: a.y + (b.y - a.y) * along, awayX: side * uy, awayY: side * -ux };
    }
    toGo -= legs[i];
  }
  return null;
};
