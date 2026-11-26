import JSZip from "jszip";
import { setNameFromFile } from "../threats/threatSets";
import { parseLpsFile } from "../localPoints/parseLps";
import { MISSION_PATHS, parseMsnxFile } from "../msnxImport/parseMsnx";
import { parseRouteFile } from "../msnxImport/parseRouteFile";
import { leftOutWords, mainKmlIn, parseKmz } from "../overlays/parseKmz";
import { parseThsFile } from "../threats/parseThs";
import { decodeXml } from "../../utils/xmlFiles";

/*
 * What a file someone dropped or picked is, worked out from its content rather than its name (an AMPS
 * file mailed around often loses its extension, or gains ".zip"): a zip holding AMPS's mission parts
 * is a mission (.msnx) and one holding a KML is a map overlay (.kmz), a SQLite database is local
 * points (.LPS) or threats (.ths) depending on which tables it holds, and an XML file is what its root
 * element says: a KML, a GPX or a Garmin flight plan. The name only decides which to try first. Each
 * is read once here so the review can say what is in it, and what was read goes on to the import.
 *
 * A document (docs/DOCUMENTS.md §9.1) is a PDF, or a Word, Excel or PowerPoint file the server turns into one. A .docx, .xlsx or
 * .pptx is a zip too, so a zip is asked for Office's parts before it is taken for a mission, as the server reads it (documents/core.py);
 * a legacy .doc, .xls or .ppt is an OLE file, told apart by its name. Nothing of it is read here but enough to say what it is: the
 * server checks it.
 */

const IMAGE_OVERLAY_EXTENSIONS = new Set(["tif", "tiff", "geotiff", "png", "jpg", "jpeg", "pgw", "jgw", "tfw"]);
const OVERLAY_EXTENSIONS = new Set(["kmz", "kml"]);
const ROUTE_EXTENSIONS = new Set(["gpx", "fpl"]);

export const KIND_LABELS = { lps: "Local points", msnx: "Mission file", ths: "Threats", routefile: "Route file", overlay: "Map overlay", document: "Document" };

/** The most a document upload can be (the server's `MAX_UPLOAD_BYTES`). */
export const DOCUMENT_MAX_BYTES = 25 * 1024 * 1024;

const DOCUMENT_LABELS = {
  pdf: "PDF",
  docx: "Word document · turned into a PDF",
  doc: "Word document · turned into a PDF",
  xlsx: "Excel workbook · turned into a PDF, a page a sheet",
  xls: "Excel workbook · turned into a PDF, a page a sheet",
  pptx: "PowerPoint presentation · turned into a PDF",
  ppt: "PowerPoint presentation · turned into a PDF",
  office: "Office file · turned into a PDF",
};
const OLE_EXTENSIONS = new Set(["doc", "xls", "ppt"]);

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

// Enough to find a PDF's header, which readers allow a little way in (as the server does: within the first 1 KB).
const head = async (file) => {
  const buffer = await file.slice(0, 1024).arrayBuffer();
  return new Uint8Array(buffer);
};

const isZip = (bytes) => bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
const isSqlite = (bytes) => String.fromCharCode(...bytes.slice(0, 15)) === "SQLite format 3";
const OLE_MAGIC = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1];
const isOle = (bytes) => OLE_MAGIC.every((byte, i) => bytes[i] === byte);
const isPdf = (bytes) => {
  for (let i = 0; i + 5 <= bytes.length; i += 1) {
    if (bytes[i] === 0x25 && bytes[i + 1] === 0x50 && bytes[i + 2] === 0x44 && bytes[i + 3] === 0x46 && bytes[i + 4] === 0x2d) return true;
  }
  return false;
};

/** An Office zip's type from its parts (`[Content_Types].xml` and Word's, Excel's or PowerPoint's main part), or null. */
export const officeKindOf = (zip) => {
  if (!zip?.file("[Content_Types].xml")) return null;
  if (zip.file("word/document.xml")) return "docx";
  if (zip.file("xl/workbook.xml")) return "xlsx";
  if (zip.file("ppt/presentation.xml")) return "pptx";
  return null;
};

const withoutExtension = (name) => {
  const base = (name ?? "").replace(/^.*[\\/]/, "");
  const at = base.lastIndexOf(".");
  return (at > 0 ? base.slice(0, at) : base).trim().slice(0, 100) || "DOCUMENT";
};

const asDocument = (file, docType) => {
  if (file.size > DOCUMENT_MAX_BYTES) throw new Error("This file is larger than 25 MB, the most a document can be.");
  return { kind: "document", docType, summary: DOCUMENT_LABELS[docType], name: withoutExtension(file.name) };
};

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
  return { kind: "ths", summary: plural(threats.length, "threat"), count: threats.length, name: setNameFromFile(file.name) };
};

const asPoints = async (file) => {
  const { name, points } = await parseLpsFile(file);
  if (!points?.length) throw new Error("No points found in this file.");
  return { kind: "lps", summary: plural(points.length, "point"), count: points.length, name: (name || file.name.replace(/\.lps$/i, "")).toUpperCase() };
};

const asOverlay = async (file) => {
  const doc = await parseKmz(file);
  return { kind: "overlay", summary: doc.summary, name: doc.name.toUpperCase(), parsed: doc, notes: leftOutWords(doc.leftOut) };
};

const asRouteFile = async (file) => {
  const parsed = await parseRouteFile(file);
  // A GPX with nothing but waypoints is a set of local points.
  if (parsed.routes.length === 0) {
    const set = { name: file.name.replace(/\.(gpx|xml)$/i, ""), points: parsed.points };
    return { kind: "lps", summary: parsed.summary, count: parsed.points.length, name: set.name.toUpperCase(), parsed: set };
  }
  return { kind: "routefile", summary: parsed.summary, name: parsed.name, parsed };
};

// A zip is told apart by what it holds: an .msnx also holds XML, and may be called anything.
const asZip = async (file) => {
  let zip = null;
  try {
    zip = await JSZip.loadAsync(await file.arrayBuffer());
  } catch {
    // The mission reader says it in its own words below.
  }
  // A Word, Excel or PowerPoint file first: otherwise it would be refused as a broken mission.
  const office = officeKindOf(zip);
  if (office) return asDocument(file, office);
  if (zip && !zip.file(MISSION_PATHS.points) && mainKmlIn(zip)) return asOverlay(file);
  return asMission(file);
};

/** The name of an XML file's root element, from its first few KB, or null if it does not look like XML. */
const rootElementOf = async (file) => {
  const bytes = new Uint8Array(await file.slice(0, 4096).arrayBuffer());
  const text = decodeXml(bytes).replace(/<\?[\s\S]*?\?>/g, "").replace(/<!--[\s\S]*?-->/g, "");
  const match = /^\s*<\s*(?:[A-Za-z_][\w.-]*:)?([A-Za-z_][\w.-]*)/.exec(text);
  return match ? match[1] : null;
};

/**
 * { kind: "lps" | "msnx" | "ths" | "routefile" | "overlay" | "document" | null, label, summary, name, error, parsed?, docType? }
 * for one file. `kind` null means nothing will be imported from it, and `error` says why in words.
 * `parsed` is what was read, where the import can use it rather than read the file again; `docType` is a document's type (pdf,
 * docx, xlsx, pptx, doc, xls, ppt, or office for a legacy file its name does not settle).
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
    if (isOle(bytes)) return { ...base, ...asDocument(file, OLE_EXTENSIONS.has(ext) ? ext : "office") };
    if (isZip(bytes)) return { ...base, ...(await asZip(file)) };
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
    // After the formats whose mark is at the very start: a PDF's may be a little way in.
    if (isPdf(bytes)) return { ...base, ...asDocument(file, "pdf") };
    const root = await rootElementOf(file);
    if (root === "kml") return { ...base, ...(await asOverlay(file)) };
    if (root === "gpx" || root === "flight-plan") return { ...base, ...(await asRouteFile(file)) };
    // Named as one but not one: the reader for what it claims to be says what is wrong with it.
    if (OVERLAY_EXTENSIONS.has(ext)) return { ...base, ...(await asOverlay(file)) };
    if (ROUTE_EXTENSIONS.has(ext)) return { ...base, ...(await asRouteFile(file)) };
  } catch (err) {
    // Some readers already say so in a whole sentence.
    return { ...base, kind: null, error: /^This file /.test(err.message) ? err.message : `This file could not be read: ${err.message}` };
  }
  if (IMAGE_OVERLAY_EXTENSIONS.has(ext)) {
    return { ...base, kind: null, error: "Image overlays are not supported yet. Nothing will be imported from this file." };
  }
  return { ...base, kind: null, error: "Not supported. Nothing will be imported from this file." };
};
