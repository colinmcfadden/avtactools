/*
 * Finding local points by name, for the search fields (TargetField): the sets on the map and the sets kept in the
 * Library, which need not be on the map. All of it is on the device, so a name is found as it is typed.
 */

// A name is looked for from this many characters, as the place search is (placeQuery.js).
export const LOCAL_POINT_MIN_CHARS = 2;
// As many as the list shows, the best first.
export const LOCAL_POINTS_SHOWN = 8;

// Capitals with accents and spacing evened out and leading dots off, so ".cp1" finds ".CP1" and "cp1" does too.
const fold = (text) => String(text ?? "")
  .normalize("NFKD")
  .replace(/\p{M}/gu, "")
  .toUpperCase()
  .replace(/^[\s.]+/, "")
  .replace(/\s+/g, " ")
  .trim();

const usable = (point) => Number.isFinite(point?.lat) && Number.isFinite(point?.lon) && fold(point?.name) !== "";

/**
 * Every point that can be found: `loaded` (the sets in this session) and `library` (the Library's, `[{ savedId, name,
 * points }]`), a Library set already loaded counted once. The same name in the same place, in two sets, is one point.
 */
export const searchablePoints = (loaded = [], library = []) => {
  const onMap = new Set(loaded.map((set) => set.savedId).filter((id) => id != null));
  const seen = new Set();
  return [...loaded, ...library.filter((set) => !onMap.has(set.savedId))].flatMap((set) =>
    (set.points ?? []).flatMap((point) => {
      if (!usable(point)) return [];
      const key = `${fold(point.name)}|${point.lat.toFixed(5)}|${point.lon.toFixed(5)}`;
      if (seen.has(key)) return [];
      seen.add(key);
      return [{ name: point.name, lat: point.lat, lon: point.lon, elevationFt: point.elevationFt, setName: set.name }];
    }),
  );
};

// 0 the whole name, 1 how it starts, 2 how a word in it starts, 3 anywhere in it; null no match.
const rankOf = (name, query) => {
  if (name === query) return 0;
  if (name.startsWith(query)) return 1;
  if (name.split(/[^A-Z0-9]+/).some((word) => word.startsWith(query))) return 2;
  return name.includes(query) ? 3 : null;
};

const awayFrom = (near) => {
  if (!near || !Number.isFinite(near.lat) || !Number.isFinite(near.lon)) return () => 0;
  const cos = Math.cos((near.lat * Math.PI) / 180);
  return (point) => (point.lat - near.lat) ** 2 + ((point.lon - near.lon) * cos) ** 2;
};

/** The points whose names `text` could be: the closest match first, then the nearest `near`, at most `limit`. */
export const findLocalPoints = (points, text, near = null, limit = LOCAL_POINTS_SHOWN) => {
  const query = fold(text);
  if (query.length < LOCAL_POINT_MIN_CHARS || !points?.length) return [];
  const away = awayFrom(near);
  return points
    .flatMap((point) => {
      const name = fold(point.name);
      const rank = rankOf(name, query);
      return rank === null ? [] : [{ point, name, rank, away: away(point) }];
    })
    .sort((a, b) => a.rank - b.rank || a.away - b.away || a.name.localeCompare(b.name))
    .slice(0, limit)
    .map(({ point }) => point);
};

/**
 * A local point as the place search's answer has it (feature/places/placeWords.js), so the fields list it and choose it
 * as they do a place. A route point made from it keeps `localPoint`, for its charted elevation.
 */
export const localPointPlace = (point) => ({
  ident: point.name,
  name: point.name,
  kind: "local_point",
  kindWords: point.setName ? `Local point · ${point.setName}` : "Local point",
  lat: point.lat,
  lon: point.lon,
  elevationFt: point.elevationFt,
  localPoint: point,
});
