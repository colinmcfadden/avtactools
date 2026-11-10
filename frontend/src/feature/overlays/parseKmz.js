import JSZip from "jszip";
import { childNamed, childrenNamed, childText, decodeXml, parseHostileXml, readPosition, strictNumber } from "../../utils/xmlFiles.js";

/*
 * Reads a KMZ (or a bare KML) into what the map can draw. A KMZ comes from whoever sent it, so it is
 * read as hostile input: the zip's inflated size is counted as it inflates (the sizes its headers
 * claim are the attacker's), a document type is refused by its text, nothing is ever fetched, and
 * names and descriptions are only ever text.
 *
 * Drawn: placemarks (Point, LineString, LinearRing, Polygon with its holes, MultiGeometry, gx:Track
 * as a line), their Style and StyleMap colours and widths, and ground overlays that are a PNG or JPEG
 * inside the archive with a north-up LatLonBox.
 *
 * Left out, and counted so the review can say so: a rotated ground overlay or a gx:LatLonQuad (Leaflet
 * lays an image on a north-up box), anything a network link would fetch, any image outside the archive
 * (a remote URL is never loaded), icon pictures (a point is drawn as a dot in its colour), models,
 * screen and photo overlays. Altitude is ignored, as the map is flat, and so is `<visibility>`: a file
 * brought in is drawn whole.
 */

export const LIMITS = {
  fileBytes: 32 * 1024 * 1024,
  inflatedBytes: 64 * 1024 * 1024,
  entries: 5000,
  features: 20000,
  coordinates: 500000,
  images: 32,
  imagePixels: 64 * 1000 * 1000,
  depth: 64,
};

export const TOO_LARGE = "This file is too large to be drawn (over 32 MB).";
export const INFLATES_TOO_FAR = "This KMZ unpacks to more than 64 MB, so it was not read.";
export const NOT_KML = "This is not a KML or KMZ file.";
export const NOTHING_TO_DRAW = "Nothing in this file can be drawn on the map.";
const TOO_MANY = "This file holds more than the map can draw: at most 20,000 placemarks and 500,000 coordinates.";

const NAME_LENGTH = 200;
const DESCRIPTION_LENGTH = 1000;
const RAW_DESCRIPTION_LENGTH = 16000;

// ---- The archive -------------------------------------------------------------------------------

const asBytes = (chunks, total) => {
  const out = new Uint8Array(total);
  let at = 0;
  chunks.forEach((chunk) => {
    out.set(chunk, at);
    at += chunk.length;
  });
  return out;
};

/**
 * One entry's bytes, counted as they inflate and stopped once the archive's budget is spent: a zip's
 * own uncompressed sizes are whatever its author wrote there.
 */
const inflate = (entry, budget) =>
  new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let stopped = false;
    const stream = entry.internalStream("uint8array");
    stream
      .on("data", (chunk) => {
        if (stopped) return;
        total += chunk.length;
        if (total > budget.left) {
          stopped = true;
          stream.pause();
          reject(new Error(INFLATES_TOO_FAR));
          return;
        }
        chunks.push(chunk);
      })
      .on("error", () => {
        if (stopped) return;
        stopped = true;
        reject(new Error("This KMZ is damaged and could not be unpacked."));
      })
      .on("end", () => {
        if (stopped) return;
        budget.left -= total;
        resolve(asBytes(chunks, total));
      })
      .resume();
  });

const isKmlName = (name) => /\.kml$/i.test(name);

/** The KML a KMZ is about: `doc.kml` by convention, else the first at the shallowest level. */
export const mainKmlIn = (zip) => {
  const names = Object.keys(zip.files).filter((name) => !zip.files[name].dir && isKmlName(name));
  if (names.length === 0) return null;
  const depthOf = (name) => name.split("/").length;
  return (
    names.find((name) => name.toLowerCase() === "doc.kml") ??
    names.reduce((best, name) => (depthOf(name) < depthOf(best) ? name : best), names[0])
  );
};

const normalise = (path) => {
  const out = [];
  path.replace(/\\/g, "/").split("/").forEach((part) => {
    if (part === "" || part === ".") return;
    if (part === "..") out.pop();
    else out.push(part);
  });
  return out.join("/");
};

const entryFor = (zip, kmlName, href) => {
  let path = href.split(/[?#]/)[0];
  try {
    path = decodeURIComponent(path);
  } catch {
    // Not percent-encoded after all: the name as it stands.
  }
  const folder = kmlName.includes("/") ? kmlName.slice(0, kmlName.lastIndexOf("/") + 1) : "";
  const candidates = [normalise(folder + path), normalise(path)];
  for (const candidate of candidates) {
    if (zip.files[candidate] && !zip.files[candidate].dir) return zip.files[candidate];
  }
  // Windows tools write a KMZ with the case of a file's name changed.
  const wanted = new Set(candidates.map((c) => c.toLowerCase()));
  const found = Object.keys(zip.files).find((name) => wanted.has(name.toLowerCase()) && !zip.files[name].dir);
  return found ? zip.files[found] : null;
};

// ---- Images ------------------------------------------------------------------------------------

const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

const pngSize = (b) => {
  if (b.length < 24) return null;
  const read = (at) => ((b[at] << 24) | (b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3]) >>> 0;
  return { width: read(16), height: read(20) };
};

const jpegSize = (b) => {
  let i = 2;
  while (i + 8 < b.length) {
    if (b[i] !== 0xff) {
      i += 1;
      continue;
    }
    const marker = b[i + 1];
    if (marker === 0xff) {
      i += 1;
      continue;
    }
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: (b[i + 5] << 8) | b[i + 6], width: (b[i + 7] << 8) | b[i + 8] };
    }
    if (marker === 0xd9 || marker === 0xda) return null;
    if ((marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      i += 2;
      continue;
    }
    i += 2 + ((b[i + 2] << 8) | b[i + 3]);
  }
  return null;
};

/** What an image's bytes are, whatever its name says, or null if it is not one the map will decode. */
export const imageInfo = (bytes) => {
  const isPng = PNG_MAGIC.every((value, index) => bytes[index] === value);
  const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (!isPng && !isJpeg) return null;
  const size = isPng ? pngSize(bytes) : jpegSize(bytes);
  // A picture's header is enough to make a browser allocate width × height × 4 bytes.
  if (!size || !size.width || !size.height || size.width * size.height > LIMITS.imagePixels) return null;
  return { mime: isPng ? "image/png" : "image/jpeg", ...size };
};

// ---- Styles ------------------------------------------------------------------------------------

/** KML colours are `aabbggrr`. */
const readColor = (text) => {
  const match = /^#?([0-9a-f]{8})$/i.exec(String(text ?? "").trim());
  if (!match) return null;
  const hex = match[1].toLowerCase();
  return { color: `#${hex.slice(6, 8)}${hex.slice(4, 6)}${hex.slice(2, 4)}`, opacity: parseInt(hex.slice(0, 2), 16) / 255 };
};

const readStyle = (element) => {
  const style = {};
  const line = childNamed(element, "LineStyle");
  if (line) {
    const color = readColor(childText(line, "color"));
    const width = strictNumber(childText(line, "width"));
    style.line = { ...(color ?? {}), ...(width !== null && width >= 0 ? { width } : {}) };
  }
  const poly = childNamed(element, "PolyStyle");
  if (poly) {
    const color = readColor(childText(poly, "color"));
    style.poly = {
      ...(color ?? {}),
      ...(childText(poly, "fill") === "0" ? { fill: false } : {}),
      ...(childText(poly, "outline") === "0" ? { outline: false } : {}),
    };
  }
  const icon = childNamed(element, "IconStyle");
  if (icon) {
    const color = readColor(childText(icon, "color"));
    if (color) style.icon = color;
  }
  return style;
};

const mergeStyles = (shared, own) => ({
  line: { ...shared?.line, ...own?.line },
  poly: { ...shared?.poly, ...own?.poly },
  icon: { ...shared?.icon, ...own?.icon },
});

/** Every style the document names, so a placemark's `#id` finds it. Only an id in this file: never a URL. */
const collectStyles = (doc) => {
  const styles = new Map();
  Array.from(doc.getElementsByTagNameNS("*", "Style")).forEach((element) => {
    const id = element.getAttribute("id");
    if (id) styles.set(id, readStyle(element));
  });
  const localId = (url) => (url.startsWith("#") ? url.slice(1) : null);
  const maps = new Map();
  Array.from(doc.getElementsByTagNameNS("*", "StyleMap")).forEach((element) => {
    const id = element.getAttribute("id");
    if (!id) return;
    const pair = childrenNamed(element, "Pair").find((p) => childText(p, "key") === "normal") ?? childNamed(element, "Pair");
    if (!pair) return;
    const inline = childNamed(pair, "Style");
    const target = localId(childText(pair, "styleUrl"));
    maps.set(id, { inline: inline ? readStyle(inline) : null, target });
  });
  return (url) => {
    const id = localId(url);
    if (id === null) return null;
    if (styles.has(id)) return styles.get(id);
    const map = maps.get(id);
    if (!map) return null;
    return map.inline ?? styles.get(map.target) ?? null;
  };
};

// ---- Text --------------------------------------------------------------------------------------

const cut = (text, length) => (text.length > length ? `${text.slice(0, length - 1).trimEnd()}…` : text);

const ENTITIES = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#39;": "'", "&apos;": "'", "&nbsp;": " " };

/**
 * A description as plain words. Balloon text is often an HTML table; here it only ever becomes a text
 * node, so what a crude strip misses is seen as characters and cannot run.
 */
export const plainDescription = (raw) => {
  const text = String(raw ?? "")
    .slice(0, RAW_DESCRIPTION_LENGTH)
    .replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, " ")
    .replace(/<\/(p|div|tr|li|h\d)\s*>|<br\s*\/?>/gi, "\n")
    .replace(/<\/t[dh]\s*>/gi, "  ")
    .replace(/<[^>]*>/g, "")
    .replace(/&(amp|lt|gt|quot|#39|apos|nbsp);/gi, (entity) => ENTITIES[entity.toLowerCase()] ?? entity)
    .replace(/[ \t ]+/g, " ")
    .replace(/\s*\n\s*/g, "\n")
    .trim();
  return cut(text, DESCRIPTION_LENGTH);
};

// ---- The document ------------------------------------------------------------------------------

const newLeftOut = () => ({
  rotatedImages: 0,
  remoteImages: 0,
  badImages: 0,
  networkLinks: 0,
  models: 0,
  screenOverlays: 0,
  photoOverlays: 0,
  unreadable: 0,
});

const boxOf = (points) => {
  let [south, west] = points[0];
  let north = south;
  let east = west;
  points.forEach(([lat, lon]) => {
    if (lat < south) south = lat;
    if (lat > north) north = lat;
    if (lon < west) west = lon;
    if (lon > east) east = lon;
  });
  return [south, west, north, east];
};

const readCoordinates = (text, ctx) => {
  const tuples = String(text ?? "").replace(/\s*,\s*/g, ",").trim().split(/\s+/).filter(Boolean);
  const points = [];
  tuples.forEach((tuple) => {
    const [lon, lat] = tuple.split(",");
    const position = readPosition(lat, lon);
    if (position) points.push(position);
    else ctx.leftOut.unreadable += 1;
  });
  ctx.coordinates += points.length;
  if (ctx.coordinates > LIMITS.coordinates) throw new Error(TOO_MANY);
  return points;
};

const coordinatesIn = (element, ctx) => readCoordinates(childText(element, "coordinates"), ctx);

const add = (ctx, feature) => {
  ctx.features.push({ ...feature, id: `f${ctx.features.length}` });
  if (ctx.features.length > LIMITS.features) throw new Error(TOO_MANY);
};

const lineFeature = (path, base, style) => ({
  ...base,
  kind: "line",
  path,
  bbox: boxOf(path),
  color: style.line.color ?? "#ffffff",
  opacity: style.line.opacity ?? 1,
  width: style.line.width ?? 2,
});

const addGeometry = (element, base, style, ctx, depth) => {
  if (depth > LIMITS.depth) throw new Error(NOT_KML);
  switch (element.localName) {
    case "Point": {
      const [at] = coordinatesIn(element, ctx);
      if (!at) {
        ctx.leftOut.unreadable += 1;
        return;
      }
      add(ctx, { ...base, kind: "point", at, bbox: [at[0], at[1], at[0], at[1]], color: style.icon.color ?? "#ffffff", opacity: style.icon.opacity ?? 1 });
      return;
    }
    case "LineString": {
      const path = coordinatesIn(element, ctx);
      if (path.length < 2) ctx.leftOut.unreadable += 1;
      else add(ctx, lineFeature(path, base, style));
      return;
    }
    case "LinearRing": {
      const path = coordinatesIn(element, ctx);
      if (path.length < 3) ctx.leftOut.unreadable += 1;
      else add(ctx, lineFeature(path, base, style));
      return;
    }
    case "Polygon": {
      const ringIn = (boundary) => (boundary ? coordinatesIn(childNamed(boundary, "LinearRing"), ctx) : []);
      const outer = ringIn(childNamed(element, "outerBoundaryIs"));
      if (outer.length < 3) {
        ctx.leftOut.unreadable += 1;
        return;
      }
      const holes = childrenNamed(element, "innerBoundaryIs").map(ringIn).filter((ring) => ring.length >= 3);
      add(ctx, {
        ...base,
        kind: "polygon",
        rings: [outer, ...holes],
        bbox: boxOf(outer),
        color: style.line.color ?? "#ffffff",
        opacity: style.line.opacity ?? 1,
        width: style.line.width ?? 2,
        fillColor: style.poly.color ?? "#ffffff",
        // KML's default is an opaque white fill, which would hide the map under a file that states no style.
        fillOpacity: style.poly.opacity ?? 0.25,
        fill: style.poly.fill !== false,
        outline: style.poly.outline !== false,
      });
      return;
    }
    case "MultiGeometry":
      Array.from(element.children).forEach((child) => addGeometry(child, base, style, ctx, depth + 1));
      return;
    case "Track": {
      const path = childrenNamed(element, "coord").flatMap((coord) => {
        const [lon, lat] = (coord.textContent ?? "").trim().split(/\s+/);
        const position = readPosition(lat, lon);
        if (!position) ctx.leftOut.unreadable += 1;
        return position ? [position] : [];
      });
      ctx.coordinates += path.length;
      if (ctx.coordinates > LIMITS.coordinates) throw new Error(TOO_MANY);
      if (path.length < 2) ctx.leftOut.unreadable += 1;
      else add(ctx, lineFeature(path, base, style));
      return;
    }
    case "MultiTrack":
      childrenNamed(element, "Track").forEach((child) => addGeometry(child, base, style, ctx, depth + 1));
      return;
    case "Model":
      ctx.leftOut.models += 1;
      return;
    default:
  }
};

const GEOMETRY = ["Point", "LineString", "LinearRing", "Polygon", "MultiGeometry", "Track", "MultiTrack", "Model"];

const addPlacemark = (element, ctx) => {
  const shared = ctx.styleFor(childText(element, "styleUrl"));
  const own = childNamed(element, "Style");
  const style = mergeStyles(shared, own ? readStyle(own) : null);
  const base = {
    name: cut(childText(element, "name"), NAME_LENGTH),
    description: plainDescription(childText(element, "description")),
  };
  const geometries = childrenNamed(element, ...GEOMETRY);
  if (geometries.length === 0) ctx.leftOut.unreadable += 1;
  geometries.forEach((geometry) => addGeometry(geometry, base, style, ctx, 0));
};

/** A ground overlay as far as can be said without its bytes: `{ name, href, bounds, opacity }`, or null. */
const readGroundOverlay = (element, ctx) => {
  if (childNamed(element, "LatLonQuad")) {
    ctx.leftOut.rotatedImages += 1;
    return null;
  }
  const box = childNamed(element, "LatLonBox");
  const href = childText(childNamed(element, "Icon"), "href");
  if (!box || !href) {
    ctx.leftOut.badImages += 1;
    return null;
  }
  const rotation = strictNumber(childText(box, "rotation")) ?? 0;
  if (Math.abs(rotation) > 0.01) {
    ctx.leftOut.rotatedImages += 1;
    return null;
  }
  const north = strictNumber(childText(box, "north"));
  const south = strictNumber(childText(box, "south"));
  const east = strictNumber(childText(box, "east"));
  const west = strictNumber(childText(box, "west"));
  const sound =
    [north, south, east, west].every((value) => value !== null) &&
    Math.abs(north) <= 90 && Math.abs(south) <= 90 && Math.abs(east) <= 180 && Math.abs(west) <= 180 &&
    north > south && east > west;
  if (!sound) {
    ctx.leftOut.badImages += 1;
    return null;
  }
  // Anything with a scheme, or starting //, is somewhere else, and nothing here goes there.
  if (/^([a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) {
    ctx.leftOut.remoteImages += 1;
    return null;
  }
  return {
    name: cut(childText(element, "name"), NAME_LENGTH),
    href,
    bounds: [[south, west], [north, east]],
    opacity: readColor(childText(element, "color"))?.opacity ?? 1,
  };
};

const walk = (element, ctx, depth) => {
  if (depth > LIMITS.depth) throw new Error(NOT_KML);
  Array.from(element.children).forEach((child) => {
    switch (child.localName) {
      case "Document":
      case "Folder":
        walk(child, ctx, depth + 1);
        break;
      case "Placemark":
        addPlacemark(child, ctx);
        break;
      case "GroundOverlay": {
        const pending = readGroundOverlay(child, ctx);
        if (pending) ctx.grounds.push(pending);
        break;
      }
      case "NetworkLink":
        ctx.leftOut.networkLinks += 1;
        break;
      case "ScreenOverlay":
        ctx.leftOut.screenOverlays += 1;
        break;
      case "PhotoOverlay":
        ctx.leftOut.photoOverlays += 1;
        break;
      default:
    }
  });
};

const documentName = (root) => {
  const document = childNamed(root, "Document") ?? childNamed(root, "Folder");
  return cut(childText(document, "name"), NAME_LENGTH);
};

const plural = (n, one, many = `${one}s`) => `${n.toLocaleString("en-US")} ${n === 1 ? one : many}`;

export const leftOutCount = (leftOut) => Object.values(leftOut).reduce((sum, value) => sum + value, 0);

/** Why the things left out were left out, in words, for the review dialog. */
export const leftOutWords = (leftOut) => {
  const words = [];
  if (leftOut.rotatedImages) words.push(`${plural(leftOut.rotatedImages, "rotated image")} (only north-up images are drawn)`);
  if (leftOut.remoteImages) words.push(`${plural(leftOut.remoteImages, "image")} outside the file`);
  if (leftOut.badImages) words.push(`${plural(leftOut.badImages, "image")} that could not be read`);
  if (leftOut.networkLinks) words.push(plural(leftOut.networkLinks, "network link"));
  if (leftOut.models) words.push(plural(leftOut.models, "3D model"));
  if (leftOut.screenOverlays) words.push(plural(leftOut.screenOverlays, "screen overlay"));
  if (leftOut.photoOverlays) words.push(plural(leftOut.photoOverlays, "photo overlay"));
  if (leftOut.unreadable) words.push(`${plural(leftOut.unreadable, "unreadable item")}`);
  return words;
};

const summaryOf = (features, images, leftOut) => {
  const count = (kind) => features.filter((feature) => feature.kind === kind).length;
  const parts = [
    count("point") && plural(count("point"), "point"),
    count("line") && plural(count("line"), "line"),
    count("polygon") && plural(count("polygon"), "area"),
    images.length && plural(images.length, "image"),
  ].filter(Boolean);
  const dropped = leftOutCount(leftOut);
  if (dropped) parts.push(`${dropped.toLocaleString("en-US")} left out`);
  return parts.join(" · ");
};

const boundsOf = (features, images) => {
  const boxes = [
    ...features.map((feature) => feature.bbox),
    ...images.map(({ bounds }) => [bounds[0][0], bounds[0][1], bounds[1][0], bounds[1][1]]),
  ];
  if (boxes.length === 0) return null;
  const [first, ...rest] = boxes;
  const [south, west, north, east] = rest.reduce(
    (all, box) => [Math.min(all[0], box[0]), Math.min(all[1], box[1]), Math.max(all[2], box[2]), Math.max(all[3], box[3])],
    first,
  );
  return [[south, west], [north, east]];
};

const readDocument = async (text, { zip = null, kmlName = "", budget = null, fileName = "" }) => {
  const doc = parseHostileXml(text);
  const root = doc.documentElement;
  if (root.localName !== "kml") throw new Error(NOT_KML);
  const ctx = { features: [], grounds: [], leftOut: newLeftOut(), coordinates: 0, styleFor: collectStyles(doc) };
  walk(root, ctx, 0);

  const images = [];
  const cache = new Map();
  for (const ground of ctx.grounds) {
    if (images.length >= LIMITS.images) {
      ctx.leftOut.badImages += 1;
      continue;
    }
    const entry = zip ? entryFor(zip, kmlName, ground.href) : null;
    if (!entry) {
      ctx.leftOut.badImages += 1;
      continue;
    }
    if (!cache.has(entry.name)) cache.set(entry.name, await inflate(entry, budget));
    const bytes = cache.get(entry.name);
    const info = imageInfo(bytes);
    if (!info) {
      ctx.leftOut.badImages += 1;
      continue;
    }
    images.push({ id: `i${images.length}`, name: ground.name, bounds: ground.bounds, opacity: ground.opacity, bytes, mime: info.mime });
  }

  if (ctx.features.length === 0 && images.length === 0) {
    const words = leftOutWords(ctx.leftOut);
    throw new Error(words.length ? `${NOTHING_TO_DRAW} It holds ${words.join(", ")}.` : NOTHING_TO_DRAW);
  }
  const base = fileName.replace(/\.(kmz|kml)$/i, "").trim();
  return {
    name: documentName(root) || base || "OVERLAY",
    features: ctx.features,
    images,
    bounds: boundsOf(ctx.features, images),
    leftOut: ctx.leftOut,
    summary: summaryOf(ctx.features, images, ctx.leftOut),
  };
};

/** Reads an already opened KMZ (`detectImport` has one in hand). */
export const parseKmzArchive = async (zip, fileName = "") => {
  const names = Object.keys(zip.files);
  if (names.length > LIMITS.entries) throw new Error("This KMZ holds too many files to be read.");
  const kmlName = mainKmlIn(zip);
  if (!kmlName) throw new Error(NOT_KML);
  const budget = { left: LIMITS.inflatedBytes };
  const bytes = await inflate(zip.files[kmlName], budget);
  return readDocument(decodeXml(bytes), { zip, kmlName, budget, fileName });
};

const isZipBytes = (bytes) => bytes[0] === 0x50 && bytes[1] === 0x4b;

/**
 * What in a KMZ or KML can be drawn:
 * `{ name, features, images: [{ id, name, bounds, opacity, bytes, mime }], bounds, leftOut, summary }`.
 * Throws an Error in words for anything else.
 */
export const parseKmz = async (file) => {
  if (file.size > LIMITS.fileBytes) throw new Error(TOO_LARGE);
  const buffer = await file.arrayBuffer();
  if (buffer.byteLength > LIMITS.fileBytes) throw new Error(TOO_LARGE);
  const bytes = new Uint8Array(buffer);
  if (!isZipBytes(bytes)) return readDocument(decodeXml(bytes), { fileName: file.name });
  let zip;
  try {
    zip = await JSZip.loadAsync(buffer);
  } catch {
    throw new Error("This KMZ is damaged and could not be unpacked.");
  }
  return parseKmzArchive(zip, file.name);
};
