import { threatToPayload } from "./threatModel";

/*
 * What a threats file for ForeFlight, ATAK or Aero App is made from (POST /api/threats-kmz): every open set in one file,
 * or the one set chosen, each set a folder of its own in it. A set with no threats is left out. Hidden threats go in
 * too, as they always have: hiding one is how the map looks, not what the mission holds.
 */

/** Every open set (`setId` null) or the one chosen, those with threats. */
export const exportGroups = (sets, setId = null) =>
  (setId ? sets.filter((set) => set.id === setId) : sets).filter((set) => set.threats.length > 0);

export const threatCount = (groups) => groups.reduce((count, set) => count + set.threats.length, 0);

/** A file name from a set's name, or `threats`, that every system can keep, with the extension asked for. */
export const exportFileName = (base, extension) => {
  const stem = String(base || "")
    .replace(/\.(msnx|kmz|kml|ths)$/i, "")
    // eslint-disable-next-line no-control-regex
    .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, "_")
    .trim()
    .slice(0, 80);
  return `${stem || "threats"}.${extension}`;
};

/** The request's body: the sets, each with its threats as the server reads them. */
export const exportBody = (groups, fileName, format) => ({
  fileName,
  format,
  sets: groups.map((set) => ({ name: set.name, threats: set.threats.map(threatToPayload) })),
});
