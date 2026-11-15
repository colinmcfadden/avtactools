import { getDistanceFeet } from "../../utils/Helpers";

/*
 * A line kept with a set of routes, beside its routes: a FLOT (feature/flot) or a phase line (feature/phaseLines). Each is
 * { id, points: [{ lat, lon }, ...] } with whatever its kind adds, under its kind's key of the set's document (`flots`, `phaseLines`),
 * the key left out when there are none. None of them is a route, so none is ever written to an AMPS or ForeFlight file.
 *
 * In a session a line is filed as a sketched route is: `setId` none for this session's own, or the id of the set it is in.
 */

const FEET_PER_KM = 3280.84;

const isPoint = (point) => Number.isFinite(point?.lat) && Number.isFinite(point?.lon);

export const linePoints = (line) => (Array.isArray(line?.points) ? line.points.filter(isPoint) : []);

/** Points as a line keeps them: a place each, and nothing else. */
export const plainPoints = (points) => points.map(({ lat, lon }) => ({ lat, lon }));

/** The lines under `key` of a set's saved data; whatever is not an object with an id is none. */
export const linesFromData = (data, key) =>
  (Array.isArray(data?.[key]) ? data[key] : []).filter((line) => line && typeof line === "object" && line.id !== undefined);

/** What is saved of lines: without the set they are filed under here. */
export const savedLines = (lines) => (lines ?? []).map(({ setId: _filed, ...line }) => line);

/** The line filed under the set `setId` (none: this session's own). */
export const filedLine = ({ setId: _old, ...line }, setId) => (setId ? { ...line, setId } : line);

export const lineLengthKm = (line) => {
  const points = linePoints(line);
  const feet = points.slice(1).reduce((sum, point, i) => sum + getDistanceFeet(points[i].lat, points[i].lon, point.lat, point.lon), 0);
  return feet / FEET_PER_KM;
};

/**
 * The sketched sets (useRouteSketch's) with their lines, and a set that has lines and no routes; this session's own first.
 * `lists` is the lines of each kind under the name a set has them by ({ flots, phaseLines }), and each set gets every one of them.
 */
export const setsWithLines = (sketchSets, lists, isOpenedSetId) => {
  const kinds = Object.entries(lists);
  const all = kinds.flatMap(([, lines]) => lines);
  const known = new Set(sketchSets.map((set) => set.setId));
  const extra = [...new Set(all.map((line) => line.setId ?? null))]
    .filter((setId) => !known.has(setId) && (setId === null || isOpenedSetId(setId)))
    .map((setId) => ({ setId, routes: [] }));
  const session = extra.filter((set) => set.setId === null);
  const opened = extra.filter((set) => set.setId !== null);
  return [...session, ...sketchSets, ...opened].map((set) => ({
    ...set,
    ...Object.fromEntries(kinds.map(([name, lines]) => [name, lines.filter((line) => (line.setId ?? null) === set.setId)])),
  }));
};
