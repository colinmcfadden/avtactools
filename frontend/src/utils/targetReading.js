/**
 * What the text in the target field means, worked out as it is typed: the
 * places it could be, each with its grid and lat/long, or a sentence saying
 * why it is none yet.
 *
 * Everything is computed in the browser (`utils/mgrs.js` both ways, and
 * `coordParse.js`), never by the API, so the field answers with no signal. The
 * Android app's `PlaceSearch.read` is a port, held to
 * `contracts/fixtures/coords/reading.json`.
 *
 *   { choices: [], message: null }          nothing typed
 *   { choices: [one], message: null }       a grid or a coordinate
 *   { choices: [two], message: "…" }        readings to choose from
 *   { choices: [], message: "…" }           not a place (yet), in words
 *
 * A choice is `{ lat, lon, mgrs, latLong, how }`: the point, its grid as the
 * target keeps it, its lat/long in words, and how the text was read.
 */
import { looksLikeMgrs, readCoordinate } from "./coordParse";
import { mgrsToLatLon, parseMgrs, toMgrs } from "./mgrs";

const BANDS = "CDEFGHJKLMNPQRSTUVWX";
// How far past its band's edge a square's centre may fall: a coarse square straddles the edge.
const BAND_MARGIN_DEG = 0.5;

export const MESSAGES = {
  incomplete: "Keep typing: a grid, or a latitude and a longitude.",
  unreadable: "Type a grid, such as 16S GC 28864 55349, or a latitude and longitude, such as 34.5457, -84.1234.",
  pair: "That is not a latitude and a longitude: give two values, each in degrees, degrees and minutes, or degrees, minutes and seconds.",
  minutes: "Minutes and seconds are under 60.",
  latitude: "A latitude is at most 90°, north or south.",
  longitude: "A longitude is at most 180°, east or west.",
  "same-axis": "Both values say the same axis: give one latitude (N or S) and one longitude (E or W).",
  gridSquare: "Keep typing the grid: its 100 km square is two letters.",
  gridDigits: "Keep typing: add the grid's easting and northing.",
  gridOdd: "Keep typing: a grid has as many northing digits as easting digits.",
  gridOff: "That grid is not on the map.",
  gridLong: "A grid has at most ten digits: five for the easting and five for the northing.",
  gridBand: "That grid's band letter does not hold that square: check the letter after the zone.",
  beyond: "That point is beyond the MGRS grid: north of 84° N or south of 80° S.",
  eastOrWest: "The longitude has no sign or E/W, so it could be east or west. Choose one.",
};

// A grid's precision in words, by the digits in each half.
const PRECISION = { 1: "10 km", 2: "1 km", 3: "100 m", 4: "10 m", 5: "1 m" };

const FORMAT_WORDS = {
  decimal: "decimal degrees",
  ddm: "degrees and decimal minutes",
  dms: "degrees, minutes and seconds",
  "packed-ddm": "packed degrees and minutes (DDMM.mmm)",
  "packed-dms": "packed degrees, minutes and seconds (DDMMSS)",
  mixed: "degrees, in two notations",
};

const hemisphereText = (value, positive, negative) => {
  const text = Math.abs(value).toFixed(5);
  // A value that rounds to zero is on the line, not south or west of it.
  const letter = value < 0 && Number(text) !== 0 ? negative : positive;
  return `${text}° ${letter}`;
};

/**
 * A lat/long in words: "34.54568° N, 84.12346° W". Five places (about a
 * metre), and hemisphere letters rather than signs so it cannot be misread.
 * The target field reads it back.
 */
export const formatLatLong = (lat, lon) => `${hemisphereText(lat, "N", "S")}, ${hemisphereText(lon, "E", "W")}`;

const choiceAt = (lat, lon, mgrs, how) => ({ lat, lon, mgrs, latLong: formatLatLong(lat, lon), how });

// Spaces as JavaScript's \s knows them, which the MGRS patterns also ignore.
const squeeze = (text) => text.replace(/\s/g, "");

const readGrid = (text) => {
  const grid = parseMgrs(text);
  if (!grid) {
    const digits = squeeze(text).replace(/^\d{1,2}[A-Z]{3}/i, "");
    if (digits.length === 0) return { choices: [], message: MESSAGES.gridDigits };
    if (digits.length % 2 !== 0) return { choices: [], message: MESSAGES.gridOdd };
    return { choices: [], message: MESSAGES.gridOff };
  }
  const point = mgrsToLatLon(text);
  // A square the zone has can still name a place past the grid's ends (16X GC is at 87° N): it is no target.
  // Nor is a zone Svalbard does not have (32X, 34X, 36X).
  if (!point || !toMgrs(point.lat, point.lon)) return { choices: [], message: MESSAGES.gridOff };
  if (grid.band === "X" && [32, 34, 36].includes(grid.zone)) return { choices: [], message: MESSAGES.gridOff };
  // A band letter mistyped (16T for 16S) still names a square, 900 km from the one meant: refuse a square
  // whose centre is not in or beside the band typed.
  const south = BANDS.indexOf(grid.band) * 8 - 80;
  const north = grid.band === "X" ? 84 : south + 8;
  if (point.lat < south - BAND_MARGIN_DEG || point.lat > north + BAND_MARGIN_DEG) return { choices: [], message: MESSAGES.gridBand };
  const zone = String(grid.zone).padStart(2, "0");
  const mgrs = `${zone}${grid.band} ${grid.square} ${grid.easting} ${grid.northing}`;
  const precision = PRECISION[grid.easting.length] ?? "under 1 m";
  return { choices: [choiceAt(point.lat, point.lon, mgrs, `MGRS grid, to ${precision}`)], message: null };
};

// Quotes, brackets and a sentence's last mark round a grid pasted from a message: "“16S GC 28864 55349”.".
const unwrap = (text) => text.replace(/^["'“”‘’([]+/, "").replace(/["'“”‘’)\].,;:]+$/, "").trim();

/** What `text`, typed into the target field, could mean. See the file's head. */
export const readTarget = (text) => {
  const typed = String(text ?? "").trim();
  if (!typed) return { choices: [], message: null };

  const unwrapped = unwrap(typed);
  if (looksLikeMgrs(unwrapped)) return readGrid(unwrapped);
  // A zone, band and one square letter is a grid half typed ("16S G").
  if (/^\d{1,2}[C-HJ-NP-X][A-HJ-NP-Z]$/i.test(squeeze(typed))) return { choices: [], message: MESSAGES.gridSquare };
  if (/^\d{1,2}[C-HJ-NP-X][A-HJ-NP-Z]{2}\d{11,}$/i.test(squeeze(typed))) return { choices: [], message: MESSAGES.gridLong };

  const { parsed, signed, problem } = readCoordinate(typed);
  // "34S" is a band being typed as much as a latitude: "incomplete" says both.
  if (problem) return { choices: [], message: MESSAGES[problem] ?? MESSAGES.unreadable };

  const how = `Read as ${FORMAT_WORDS[parsed.format] ?? "degrees"}`;
  const at = (lat, lon) => {
    const mgrs = toMgrs(lat, lon);
    return mgrs ? choiceAt(lat, lon, mgrs, how) : null;
  };
  const literal = at(parsed.lat, parsed.lon);
  if (!literal) return { choices: [], message: MESSAGES.beyond };

  // With no sign or E/W on the longitude (whatever the latitude says), it is
  // east as written, but crews west of Greenwich drop the minus all the time:
  // offer both rather than guess. Latitude first stays the rule (see coordParse).
  if (!signed && parsed.lon !== 0) {
    const west = at(parsed.lat, -parsed.lon);
    // 180° east is 180° west, and a longitude that rounds to 0° reads the same both ways: one place to offer.
    if (west && west.latLong !== literal.latLong && west.mgrs !== literal.mgrs) {
      return { choices: [literal, west], message: MESSAGES.eastOrWest };
    }
  }
  return { choices: [literal], message: null };
};

/**
 * The choices nearest `near` (`{ lat, lon }`, where the map is) first, so the
 * reading the planner most likely meant is the first one offered. Stable: the
 * order of equals is kept. Flat-earth distance in degrees is enough to rank
 * an east and a west reading.
 */
export const nearestFirst = (choices, near) => {
  if (!near || !Number.isFinite(near.lat) || !Number.isFinite(near.lon) || choices.length < 2) return choices;
  const distance = (c) => {
    const dLon = Math.abs(((c.lon - near.lon + 540) % 360) - 180);
    return (c.lat - near.lat) ** 2 + dLon ** 2;
  };
  return choices
    .map((choice, index) => ({ choice, index, d: distance(choice) }))
    .sort((a, b) => a.d - b.d || a.index - b.index)
    .map((entry) => entry.choice);
};
