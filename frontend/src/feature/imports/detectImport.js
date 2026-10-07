import { parseLpsFile } from "../localPoints/parseLps";
import { parseMsnxFile } from "../msnxImport/parseMsnx";
import { parseThsFile } from "../threats/parseThs";

/*
 * What a file someone dropped or picked is, worked out from its content rather than its name (an AMPS
 * file mailed around often loses its extension, or gains ".zip"): a zip is a mission (.msnx), a
 * SQLite database is local points (.LPS) or threats (.ths), depending on which tables it holds. The
 * name only decides which to try first. Each is read once here so the review can say what is in it.
 */

const OVERLAY_EXTENSIONS = new Set(["kmz", "kml", "tif", "tiff", "geotiff", "png", "jpg", "jpeg", "pgw", "jgw", "tfw"]);

export const KIND_LABELS = { lps: "Local points", msnx: "Mission file", ths: "Threats" };

export const formatSize = (bytes) => {
  if (!Number.isFinite(bytes)) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const plural = (n, one, many = `${one}s`) => `${n.toLocaleString("en-US")} ${n === 1 ? one : many}`;

const extensionOf = (name) => {
  const at = (name ?? "").lastIndexOf(".");
  return at < 0 ? "" : name.slice(at + 1).toLowerCase();
};

const head = async (file) => {
  const buffer = await file.slice(0, 16).arrayBuffer();
  return new Uint8Array(buffer);
};

const isZip = (bytes) => bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
const isSqlite = (bytes) => String.fromCharCode(...bytes.slice(0, 15)) === "SQLite format 3";

const asMission = async (file) => {
  // The bytes, not the File: JSZip reads an ArrayBuffer the same way everywhere.
  const { routes } = await parseMsnxFile(await file.arrayBuffer());
  if (!routes?.length) throw new Error("This mission file has no routes.");
  const points = routes.reduce((sum, route) => sum + (route.points?.length ?? 0), 0);
  return { kind: "msnx", summary: `${plural(routes.length, "route")} · ${plural(points, "point")}`, name: file.name.replace(/\.msnx$/i, "") };
};

const asThreats = async (file) => {
  const threats = await parseThsFile(file);
  if (!threats?.length) throw new Error("No threats found in this file.");
  return { kind: "ths", summary: plural(threats.length, "threat"), count: threats.length, name: file.name };
};

const asPoints = async (file) => {
  const { name, points } = await parseLpsFile(file);
  if (!points?.length) throw new Error("No points found in this file.");
  return { kind: "lps", summary: plural(points.length, "point"), count: points.length, name: (name || file.name.replace(/\.lps$/i, "")).toUpperCase() };
};

/**
 * { kind: "lps" | "msnx" | "ths" | null, label, summary, name, error } for one file. `kind` null means
 * nothing will be imported from it, and `error` says why in words.
 */
export const detectImport = async (file) => {
  const ext = extensionOf(file.name);
  const base = { file, size: formatSize(file.size) };
  let bytes;
  try {
    bytes = await head(file);
  } catch {
    return { ...base, kind: null, error: "This file could not be read." };
  }
  try {
    if (isZip(bytes)) return { ...base, ...(await asMission(file)) };
    if (isSqlite(bytes)) {
      const order = ext === "ths" ? [asThreats, asPoints] : [asPoints, asThreats];
      try {
        return { ...base, ...(await order[0](file)) };
      } catch (first) {
        try {
          return { ...base, ...(await order[1](file)) };
        } catch {
          throw first;
        }
      }
    }
  } catch (err) {
    return { ...base, kind: null, error: `This file could not be read: ${err.message}` };
  }
  if (OVERLAY_EXTENSIONS.has(ext)) {
    return { ...base, kind: null, error: "Map overlays are not supported yet. Nothing will be imported from this file." };
  }
  return { ...base, kind: null, error: "Not supported. Nothing will be imported from this file." };
};
