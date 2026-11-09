import { strictNumber } from "../../utils/xmlFiles";

/*
 * Points a person makes by hand, in the shape an .LPS import gives (parseLps.js): the Library,
 * a mission pack and Android all hold them as they hold imported ones.
 */

export const LIMITS = { name: 50, description: 255, group: 50, elevationFt: 100000 };
export const DEFAULT_GROUP = "Default";
export const NEW_SET_NAME = "CUSTOM POINTS";
export const MAX_POINTS_EXPORTED = 10000;

const random = () => Math.random().toString(36).slice(2, 8);
export const newPointId = () => `pt-${Date.now()}-${random()}`;

// By characters, so an emoji at the limit is left whole or left out, never split.
const cut = (text, limit) => Array.from(String(text ?? "").trim()).slice(0, limit).join("");

/**
 * What a filled-in point form comes to. `position`: { lat, lon }, or null where none has been read.
 * { point } (without an id), or { problems: { field: words } }. Blank elevation is no elevation, never 0.
 */
export const pointFromForm = ({ name, position, elevation, group, description }) => {
  const problems = {};
  const cleanName = cut(name, LIMITS.name).toUpperCase();
  if (!cleanName) problems.name = "Give the point a name.";
  if (!position || !Number.isFinite(position.lat) || !Number.isFinite(position.lon)) problems.position = "Enter an MGRS grid or a lat/long the point is at.";
  let elevationFt = null;
  if (String(elevation ?? "").trim() !== "") {
    elevationFt = strictNumber(elevation);
    if (elevationFt === null || Math.abs(elevationFt) > LIMITS.elevationFt) {
      problems.elevation = "Elevation is a number of feet, or blank if it is not known.";
    }
  }
  if (Object.keys(problems).length > 0) return { problems };
  return {
    point: {
      name: cleanName,
      description: cut(description, LIMITS.description),
      group: cut(group, LIMITS.group) || DEFAULT_GROUP,
      icon: "",
      elevationFt,
      lat: position.lat,
      lon: position.lon,
    },
  };
};

const groupOf = (point) => point.group || DEFAULT_GROUP;

/** The groups a set's points fall in, in the order each first appears: [{ name, points }]. */
export const pointGroups = (points) => {
  const groups = new Map();
  (points ?? []).forEach((point) => {
    const name = groupOf(point);
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(point);
  });
  return [...groups].map(([name, list]) => ({ name, points: list }));
};

/** A file name for an export: what a file system or a chat app trips over made an underscore. */
export const exportFileName = (name, extension) => {
  const safe = String(name ?? "").trim().replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^[._]+|[._]+$/g, "");
  return `${safe || "local_points"}.${extension}`;
};

const escapeXml = (text) =>
  String(text ?? "").replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[ch]);

const FEET_PER_METRE = 3.28084;

/** Points as GPX waypoints. Read back by this app they are local points again (parseRouteFile.js). */
export const pointsToGpx = (name, points) => {
  const waypoints = points.map((point) => {
    const parts = [];
    if (typeof point.elevationFt === "number") parts.push(`<ele>${(point.elevationFt / FEET_PER_METRE).toFixed(2)}</ele>`);
    parts.push(`<name>${escapeXml(point.name)}</name>`);
    if (point.description) parts.push(`<desc>${escapeXml(point.description)}</desc>`);
    parts.push(`<type>${escapeXml(groupOf(point))}</type>`);
    return `  <wpt lat="${point.lat.toFixed(6)}" lon="${point.lon.toFixed(6)}">${parts.join("")}</wpt>`;
  });
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<gpx version="1.1" creator="EZ/PZ" xmlns="http://www.topografix.com/GPX/1/1">',
    `  <metadata><name>${escapeXml(name)}</name></metadata>`,
    ...waypoints,
    "</gpx>",
    "",
  ].join("\n");
};
