/**
 * Latitude/longitude to MGRS, in the browser.
 *
 * The cursor readout converts on every mouse move, which is far too often for a
 * round trip to /api/convert-to-mgrs. This is the same conversion the backend
 * does (PyGeodesy, WGS84) as plain arithmetic: transverse Mercator by Krüger's
 * series — accurate to nanometres across a UTM zone, so 1 m digits match the
 * backend's exactly (checked against it at thousands of points worldwide) —
 * then the MGRS 100 km square letters.
 *
 * Covers MGRS's UTM range, 80°S to 84°N. The polar caps use a different
 * projection (UPS) that no landing zone here needs; they return null.
 */

const A = 6378137.0;                     // WGS84 semi-major axis, metres
const F = 1 / 298.257223563;             // WGS84 flattening
const K0 = 0.9996;                       // UTM central scale factor
const FALSE_EASTING = 500000;
const FALSE_NORTHING_SOUTH = 10000000;

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

const BANDS = "CDEFGHJKLMNPQRSTUVWX";              // 8° each from 80°S; X is 12°
const COLUMN_SETS = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"];
const ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";        // I and O are never used

const toRad = (deg) => (deg * Math.PI) / 180;

/** The UTM zone, including the Norway and Svalbard exceptions. */
const zoneFor = (lat, lon) => {
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

/** Latitude/longitude to UTM. Null outside 80°S–84°N or for bad input. */
export const toUtm = (lat, lon) => {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (lat < -80 || lat >= 84) return null;
  const wrapped = ((((lon + 180) % 360) + 360) % 360) - 180;

  const zone = zoneFor(lat, wrapped);
  const centralMeridian = (zone - 1) * 6 - 180 + 3;
  const phi = toRad(lat);
  const lambda = toRad(wrapped - centralMeridian);

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

  const band = BANDS[Math.min(Math.floor((lat + 80) / 8), BANDS.length - 1)];
  return { zone, band, easting, northing };
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

  // The 100 km square: column letters cycle every three zones, rows every
  // two million metres, with even zones' rows offset by five letters.
  const set = (zone - 1) % 3;
  const column = COLUMN_SETS[set][Math.floor(easting / 100000) - 1];
  const rowIndex = (Math.floor(northing / 100000) + (zone % 2 === 0 ? 5 : 0)) % 20;
  const square = `${column}${ROW_LETTERS[rowIndex]}`;

  const scale = 10 ** (5 - digits);
  const within = (metres) => Math.floor((metres % 100000) / scale)
    .toString().padStart(digits, "0");

  // Zone padded to two digits, as the backend writes it ("05R", not "5R").
  const zoneText = String(zone).padStart(2, "0");
  return `${zoneText}${band} ${square} ${within(easting)} ${within(northing)}`;
};
