/**
 * What typing a name on a route point does with the loaded local points: a name that is a local point's (capitals, a leading dot ignored) puts the route
 * point on it, at its position and with the elevation AMPS charted for it. Pure, so the native apps can be held to the same cases
 * (contracts/fixtures/localpoints/match.json).
 */

/** The local points by their names in capitals; a point with no name is not findable, and of two with one name the later wins. */
export const indexLocalPointsByName = (localPoints) => {
  const byName = new Map();
  for (const lp of localPoints) {
    if (lp?.name) byName.set(lp.name.toUpperCase(), lp);
  }
  return byName;
};

/**
 * What [raw], as typed into a route point's name, becomes: the name in capitals, the position of the local point it names (undefined when it names
 * none) and that point's charted elevation in feet (undefined when it has none).
 */
export const matchLocalPointName = (byName, raw) => {
  const name = raw.toUpperCase();
  const match = byName.get(name.replace(/^\./, ""));
  return {
    name,
    coords: match ? { lat: match.lat, lon: match.lon } : undefined,
    chartElevationFt: match && typeof match.elevationFt === "number" ? match.elevationFt : undefined,
  };
};
