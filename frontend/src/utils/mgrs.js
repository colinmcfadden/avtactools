/**
 * MGRS in the browser: latitude/longitude to a grid and back.
 *
 * The cursor readout converts on every mouse move, which is far too often for a
 * round trip to /api/convert-to-mgrs. This is the same conversion the backend
 * does (PyGeodesy, WGS84) as plain arithmetic: transverse Mercator by Krüger's
 * series — accurate to nanometres across a UTM zone, so 1 m digits match the
 * backend's exactly (checked against it at thousands of points worldwide) —
 * then the MGRS 100 km square letters.
 *
 * The inverse (UTM or a grid to latitude/longitude) is the same series run
 * backwards, ported from the Android app's MgrsConverter and held to the same
 * PyGeodesy fixture (contracts/fixtures/mgrs/inverse.json). The map's MGRS grid
 * draws its lines with it.
 *
 * Covers MGRS's UTM range, 80°S to 84°N. The polar caps use a different
 * projection (UPS) that no landing zone here needs; they return null.
 */

const A = 6378137.0;                     // WGS84 semi-major axis, metres
const F = 1 / 298.257223563;             // WGS84 flattening
const K0 = 0.9996;                       // UTM central scale factor
const FALSE_EASTING = 500000;
const FALSE_NORTHING_SOUTH = 10000000;
const SQUARE_M = 100000;
const ROW_CYCLE_M = 2000000;

const N = F / (2 - F);
const E = Math.sqrt(F * (2 - F));        // eccentricity
// Rectifying radius, and Krüger's series to sixth order in n.
const RECTIFYING = (A / (1 + N)) * (1 + N ** 2 / 4 + N ** 4 / 64 + N ** 6 / 256);
const ALPHA = [
  N / 2 - (2 * N ** 2) / 3 + (5 * N ** 3) / 16 + (41 * N ** 4) / 180
    - (127 * N ** 5) / 288 + (7891 * N ** 6) / 37800,
  (13 * N ** 2) / 48 - (3 * N ** 3) / 5 + (557 * N ** 4) / 1440
    + (281 * N ** 5) / 630 - (1983433 * N ** 6) / 1935360,
  (61 * N ** 3) / 240 - (103 * N ** 4) / 140 + (15061 * N ** 5) / 26880
    + (167603 * N ** 6) / 181440,
  (49561 * N ** 4) / 161280 - (179 * N ** 5) / 168 + (6601661 * N ** 6) / 7257600,
  (34729 * N ** 5) / 80640 - (3418889 * N ** 6) / 1995840,
  (212378941 * N ** 6) / 319334400,
];
// The inverse series (Karney 2011), then conformal to geodetic latitude.
const BETA = [
  N / 2 - (2 * N ** 2) / 3 + (37 * N ** 3) / 96 - N ** 4 / 360
    - (81 * N ** 5) / 512 + (96199 * N ** 6) / 604800,
  N ** 2 / 48 + N ** 3 / 15 - (437 * N ** 4) / 1440 + (46 * N ** 5) / 105
    - (1118711 * N ** 6) / 3870720,
  (17 * N ** 3) / 480 - (37 * N ** 4) / 840 - (209 * N ** 5) / 4480
    + (5569 * N ** 6) / 90720,
  (4397 * N ** 4) / 161280 - (11 * N ** 5) / 504 - (830251 * N ** 6) / 7257600,
  (4583 * N ** 5) / 161280 - (108847 * N ** 6) / 3991680,
  (20648693 * N ** 6) / 638668800,
];
const DELTA = [
  2 * N - (2 * N ** 2) / 3 - 2 * N ** 3 + (116 * N ** 4) / 45
    + (26 * N ** 5) / 45 - (2854 * N ** 6) / 675,
  (7 * N ** 2) / 3 - (8 * N ** 3) / 5 - (227 * N ** 4) / 45
    + (2704 * N ** 5) / 315 + (2323 * N ** 6) / 945,
  (56 * N ** 3) / 15 - (136 * N ** 4) / 35 - (1262 * N ** 5) / 105
    + (73814 * N ** 6) / 2835,
  (4279 * N ** 4) / 630 - (332 * N ** 5) / 35 - (399572 * N ** 6) / 14175,
  (4174 * N ** 5) / 315 - (144838 * N ** 6) / 6237,
  (601676 * N ** 6) / 22275,
];

const BANDS = "CDEFGHJKLMNPQRSTUVWX";              // 8° each from 80°S; X is 12°
const COLUMN_SETS = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"];
const ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";        // I and O are never used
const EVEN_ZONE_ROWS = "FGHJKLMNPQRSTUVABCDE";     // even zones start their rows at F

const toRad = (deg) => (deg * Math.PI) / 180;
const toDeg = (rad) => (rad * 180) / Math.PI;
const wrapLon = (lon) => ((((lon + 180) % 360) + 360) % 360) - 180;

/** A zone's central meridian, in degrees. */
export const centralMeridian = (zone) => (zone - 1) * 6 - 180 + 3;

/** The latitude band letter for a latitude in 80°S–84°N (band X runs to 84°). */
export const bandFor = (lat) => BANDS[Math.min(Math.floor((lat + 80) / 8), BANDS.length - 1)];

/** The UTM zone, including the Norway and Svalbard exceptions. */
export const zoneFor = (lat, lon) => {
  let zone = Math.floor((lon + 180) / 6) + 1;
  if (zone > 60) zone = 60;                         // lon === 180
  if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
  if (lat >= 72 && lat < 84) {
    if (lon >= 0 && lon < 9) zone = 31;
    else if (lon >= 9 && lon < 21) zone = 33;
    else if (lon >= 21 && lon < 33) zone = 35;
    else if (lon >= 33 && lon < 42) zone = 37;
  }
  return zone;
};

/** The forward series: a latitude and a longitude from the central meridian to metres. */
const transverseMercator = (lat, fromMeridian) => {
  const phi = toRad(lat);
  const lambda = toRad(fromMeridian);

  // Conformal latitude, then the transverse Mercator series.
  const t = Math.sinh(Math.atanh(Math.sin(phi)) - E * Math.atanh(E * Math.sin(phi)));
  const xiPrime = Math.atan2(t, Math.cos(lambda));
  const etaPrime = Math.atanh(Math.sin(lambda) / Math.sqrt(1 + t * t));
  let xi = xiPrime;
  let eta = etaPrime;
  ALPHA.forEach((alpha, i) => {
    const j = 2 * (i + 1);
    xi += alpha * Math.sin(j * xiPrime) * Math.cosh(j * etaPrime);
    eta += alpha * Math.cos(j * xiPrime) * Math.sinh(j * etaPrime);
  });

  const easting = FALSE_EASTING + K0 * RECTIFYING * eta;
  let northing = K0 * RECTIFYING * xi;
  if (lat < 0) northing += FALSE_NORTHING_SOUTH;
  return { easting, northing };
};

/** Latitude/longitude to UTM. Null outside 80°S–84°N or for bad input. */
export const toUtm = (lat, lon) => {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (lat < -80 || lat >= 84) return null;
  const wrapped = wrapLon(lon);

  const zone = zoneFor(lat, wrapped);
  const { easting, northing } = transverseMercator(lat, wrapped - centralMeridian(zone));
  return { zone, band: bandFor(lat), easting, northing };
};

/**
 * Latitude/longitude projected into a zone of the caller's choosing, which need
 * not be the zone the point is in: a grid line near a zone's edge, or in the
 * Norway and Svalbard exceptions, belongs to its own zone and is computed there.
 * Same shape as toUtm. Null outside 80°S–84°N (84° itself included, as the top
 * of band X), for a zone outside 1–60, or for bad input.
 */
export const toUtmInZone = (lat, lon, zone) => {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (!Number.isInteger(zone) || zone < 1 || zone > 60) return null;
  if (lat < -80 || lat > 84) return null;
  let fromMeridian = wrapLon(lon) - centralMeridian(zone);
  // Zone 60's east edge is +180, which wraps to -180: measure it from the near side.
  if (fromMeridian < -180) fromMeridian += 360;
  else if (fromMeridian >= 180) fromMeridian -= 360;
  const { easting, northing } = transverseMercator(lat, fromMeridian);
  return { zone, band: bandFor(lat), easting, northing };
};

/**
 * Longitude folded into ±180 the way PyGeodesy's `wrap180` does: a value
 * already in range, ±180 included, is left alone. A square on the far side
 * of its zone's edge (zone 60's east, zone 1's west) otherwise comes back
 * as 180.0003 where the server says -179.9997.
 */
const wrap180 = (lon) => (lon >= -180 && lon <= 180 ? lon : wrapLon(lon));

/**
 * UTM to latitude/longitude. `hemisphere` is "N" or "S"; a southern northing
 * carries the 10,000 km false northing, as toUtm gives it. Null for a zone
 * outside 1–60, another hemisphere, or a non-finite coordinate.
 */
export const fromUtm = (zone, hemisphere, easting, northing) => {
  if (!Number.isInteger(zone) || zone < 1 || zone > 60) return null;
  if (hemisphere !== "N" && hemisphere !== "S") return null;
  if (!Number.isFinite(easting) || !Number.isFinite(northing)) return null;

  const xi0 = (northing - (hemisphere === "S" ? FALSE_NORTHING_SOUTH : 0)) / (K0 * RECTIFYING);
  const eta0 = (easting - FALSE_EASTING) / (K0 * RECTIFYING);
  let xi = xi0;
  let eta = eta0;
  BETA.forEach((beta, i) => {
    const j = 2 * (i + 1);
    xi -= beta * Math.sin(j * xi0) * Math.cosh(j * eta0);
    eta -= beta * Math.cos(j * xi0) * Math.sinh(j * eta0);
  });
  const chi = Math.asin(Math.sin(xi) / Math.cosh(eta));
  let phi = chi;
  DELTA.forEach((delta, i) => {
    phi += delta * Math.sin(2 * (i + 1) * chi);
  });
  const lambda = Math.atan2(Math.sinh(eta), Math.cos(xi));
  return { lat: toDeg(phi), lon: wrap180(toDeg(lambda) + centralMeridian(zone)) };
};

/**
 * The 100 km square's two letters: column letters cycle every three zones,
 * rows every two million metres, with even zones' rows offset by five letters.
 */
export const squareLetters = (zone, easting, northing) => {
  const set = (zone - 1) % 3;
  const column = COLUMN_SETS[set][Math.floor(easting / SQUARE_M) - 1];
  const rowIndex = (Math.floor(northing / SQUARE_M) + (zone % 2 === 0 ? 5 : 0)) % 20;
  return `${column}${ROW_LETTERS[rowIndex]}`;
};

/**
 * Latitude/longitude to an MGRS grid such as "16S GD 66993 52949".
 *
 * `digits` per coordinate: 5 is 1 m, 4 is 10 m. Digits are truncated, not
 * rounded, as MGRS specifies — a grid names the square the point is in.
 */
export const toMgrs = (lat, lon, { digits = 5 } = {}) => {
  const utm = toUtm(lat, lon);
  if (!utm) return null;
  const { zone, band, easting, northing } = utm;
  const square = squareLetters(zone, easting, northing);

  const scale = 10 ** (5 - digits);
  const within = (metres) => Math.floor((metres % SQUARE_M) / scale)
    .toString().padStart(digits, "0");

  // Zone padded to two digits, as the backend writes it ("05R", not "5R").
  const zoneText = String(zone).padStart(2, "0");
  return `${zoneText}${band} ${square} ${within(easting)} ${within(northing)}`;
};

const GRID = /^(\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-Z])(\d*)$/;

/**
 * A grid, as typed, to its parts — { zone, band, square, easting, northing },
 * the digits kept as text because leading zeros are part of the grid — or null
 * when it is not a usable grid. Spaces and case are ignored and the digits split
 * evenly into easting and northing, so any even count of two or more works;
 * each pair past ten refines the square tenfold below a metre.
 */
export const parseMgrs = (text) => {
  if (typeof text !== "string") return null;
  const match = GRID.exec(text.replace(/\s/g, "").toUpperCase());
  if (!match) return null;
  const [, zoneText, band, column, row, digits] = match;
  const zone = Number(zoneText);
  if (zone < 1 || zone > 60) return null;
  if (digits.length < 2 || digits.length % 2 !== 0) return null;
  const half = digits.length / 2;
  return {
    zone,
    band,
    square: `${column}${row}`,
    easting: digits.slice(0, half),
    northing: digits.slice(half),
  };
};

/**
 * A grid to the latitude/longitude of the centre of the square it names, as
 * /api/convert-grid answers (PyGeodesy's parseMGRS(grid).toLatLon()), or null
 * for a grid parseMgrs refuses or a square the zone does not have.
 */
export const mgrsToLatLon = (text) => {
  const grid = parseMgrs(text);
  if (!grid) return null;
  const { zone, band, square } = grid;
  const columnIndex = COLUMN_SETS[(zone - 1) % 3].indexOf(square[0]);
  const rowIndex = (zone % 2 === 0 ? EVEN_ZONE_ROWS : ROW_LETTERS).indexOf(square[1]);
  if (columnIndex < 0 || rowIndex < 0) return null;
  const bandIndex = BANDS.indexOf(band);

  // Position within the square: the digits name its lower-left corner at
  // 10^(5 - digits) metres, and the answer is its centre.
  const resolution = 10 ** (5 - grid.easting.length);
  const cornerEasting = (columnIndex + 1) * SQUARE_M + Number(grid.easting) * resolution;
  let cornerNorthing = rowIndex * SQUARE_M + Number(grid.northing) * resolution;

  // The row letters repeat every 2,000 km north; add blocks until the
  // northing reaches the band's floor. PyGeodesy takes the floor from the
  // band's southern edge on the 0° meridian, truncated to 100 km, and works
  // from the square's corner, centring afterwards.
  const bandFloor = toUtm(bandIndex * 8 - 80, 0).northing;
  const northingBottom = Math.floor(bandFloor / SQUARE_M) * SQUARE_M;
  const blocks = (northingBottom - cornerNorthing) / ROW_CYCLE_M;
  if (blocks > 0) {
    cornerNorthing += Math.min(Math.trunc(blocks) + 1, band === "W" ? 3 : 4) * ROW_CYCLE_M;
  }

  return fromUtm(
    zone,
    band < "N" ? "S" : "N",
    cornerEasting + resolution / 2,
    cornerNorthing + resolution / 2,
  );
};
