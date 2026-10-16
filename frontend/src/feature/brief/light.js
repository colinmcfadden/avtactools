/**
 * Light data for a brief, worked out on the device: BMNT, sunrise, sunset, EENT, moonrise, moonset and the moon's
 * illumination. No service, so it works offline and says nothing about where the mission is to anyone.
 *
 * The sun is the Astronomical Almanac's low-precision position (about 0.01°), the moon its low-precision series
 * (about 0.3° in longitude). Rise, set and twilight are found by stepping through the local day and bisecting each
 * crossing, so a body that does not rise or set that day (polar day, or the moon's daily lag) has no time: never one
 * made up. Held to Skyfield's answers in contracts/fixtures/brief/light.json (sun events to a minute, the moon's to
 * three, illumination to one percent); Android's core-brief is a port of this file held to the same fixture.
 *
 * Instants are milliseconds since 1970 (UTC). A "local day" is the 24 hours from local midnight at the offset given,
 * the offset the brief's clock times are in.
 */

const RAD = Math.PI / 180;
const DAY_MS = 86400000;
const J2000_MS = Date.UTC(2000, 0, 1, 12, 0, 0); // JD 2451545.0

/** Sun's centre at this altitude is sunrise or sunset: refraction (34') and its semidiameter (16'). */
export const SUNRISE_ALTITUDE = -0.8333;
/** Nautical twilight: the sun's centre 12° below the horizon. */
export const NAUTICAL_ALTITUDE = -12;

const STEP_MS = 10 * 60 * 1000;
const PRECISION_MS = 1000;

const sin = (deg) => Math.sin(deg * RAD);
const cos = (deg) => Math.cos(deg * RAD);
const norm360 = (deg) => ((deg % 360) + 360) % 360;

/** Days since J2000.0 (TT and UT are not told apart: the difference is about a minute of the moon's motion's worth of nothing here). */
const daysSinceJ2000 = (ms) => (ms - J2000_MS) / DAY_MS;

const obliquity = (d) => 23.439 - 0.0000004 * d;

/** The sun's ecliptic longitude (°), its distance is not needed. */
const sunLongitude = (d) => {
  const g = norm360(357.529 + 0.98560028 * d);
  const q = norm360(280.459 + 0.98564736 * d);
  return norm360(q + 1.915 * sin(g) + 0.02 * sin(2 * g));
};

const equatorial = (lambda, beta, d) => {
  const e = obliquity(d);
  const ra = Math.atan2(sin(lambda) * cos(e) - Math.tan(beta * RAD) * sin(e), cos(lambda)) / RAD;
  const dec = Math.asin(sin(beta) * cos(e) + cos(beta) * sin(e) * sin(lambda)) / RAD;
  return { ra: norm360(ra), dec };
};

/** The moon's geocentric ecliptic longitude, latitude and horizontal parallax (°). */
const moonPosition = (d) => {
  const t = d / 36525;
  const lambda =
    218.32 + 481267.881 * t +
    6.29 * sin(135.0 + 477198.87 * t) - 1.27 * sin(259.3 - 413335.36 * t) +
    0.66 * sin(235.7 + 890534.22 * t) + 0.21 * sin(269.9 + 954397.74 * t) -
    0.19 * sin(357.5 + 35999.05 * t) - 0.11 * sin(186.5 + 966404.03 * t);
  const beta =
    5.13 * sin(93.3 + 483202.02 * t) + 0.28 * sin(228.2 + 960400.89 * t) -
    0.28 * sin(318.3 + 6003.15 * t) - 0.17 * sin(217.6 - 407332.21 * t);
  const parallax =
    0.9508 + 0.0518 * cos(135.0 + 477198.87 * t) + 0.0095 * cos(259.3 - 413335.36 * t) +
    0.0078 * cos(235.7 + 890534.22 * t) + 0.0028 * cos(269.9 + 954397.74 * t);
  return { lambda: norm360(lambda), beta, parallax };
};

/** Greenwich mean sidereal time (°). */
const siderealTime = (d) => norm360(280.46061837 + 360.98564736629 * d);

const altitude = ({ ra, dec }, d, lat, lon) => {
  const h = siderealTime(d) + lon - ra;
  return Math.asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(h)) / RAD;
};

/** The sun's altitude less the altitude that counts, at an instant: zero at the event. */
const sunAbove = (threshold, lat, lon) => (ms) => {
  const d = daysSinceJ2000(ms);
  return altitude(equatorial(sunLongitude(d), 0, d), d, lat, lon) - threshold;
};

/**
 * The moon's geocentric altitude less the altitude of its rising: its upper limb on the horizon seen from the
 * ground, refraction included (Meeus, Astronomical Algorithms, ch. 15: 0.7275 π − 0°34′).
 */
const moonAbove = (lat, lon) => (ms) => {
  const d = daysSinceJ2000(ms);
  const m = moonPosition(d);
  return altitude(equatorial(m.lambda, m.beta, d), d, lat, lon) - (0.7275 * m.parallax - 0.5667);
};

/** Bisects a sign change of f between a and b (f(a) and f(b) of opposite signs) to a second. */
const bisect = (f, a, b) => {
  let lo = a;
  let hi = b;
  let fLo = f(lo);
  while (hi - lo > PRECISION_MS) {
    const mid = lo + Math.floor((hi - lo) / 2);
    const fMid = f(mid);
    if ((fMid < 0) === (fLo < 0)) {
      lo = mid;
      fLo = fMid;
    } else hi = mid;
  }
  return Math.round((lo + hi) / 2 / 1000) * 1000;
};

/** Every crossing of zero by f in [start, end): rising where it goes from below to above, setting the other way. */
const crossings = (f, start, end) => {
  const rises = [];
  const sets = [];
  let t0 = start;
  let f0 = f(t0);
  while (t0 < end) {
    const t1 = Math.min(t0 + STEP_MS, end);
    const f1 = f(t1);
    if (f0 < 0 && f1 >= 0) rises.push(bisect(f, t0, t1));
    else if (f0 >= 0 && f1 < 0) sets.push(bisect(f, t0, t1));
    t0 = t1;
    f0 = f1;
  }
  return { rises, sets };
};

/** Local midnight of a date (YYYY-MM-DD) at an offset east of UTC in minutes, as an instant. */
export const localMidnight = (date, utcOffsetMinutes) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date || "");
  if (!m) return null;
  return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) - utcOffsetMinutes * 60000;
};

/**
 * The moon's illuminated fraction (0..1) at an instant: from its elongation from the sun and the two distances
 * (Meeus, ch. 48).
 */
export const moonIllumination = (ms) => {
  const d = daysSinceJ2000(ms);
  const m = moonPosition(d);
  const sunLambda = sunLongitude(d);
  const cosPsi = cos(m.beta) * cos(m.lambda - sunLambda);
  const psi = Math.acos(Math.max(-1, Math.min(1, cosPsi)));
  const moonKm = 6378.14 / Math.sin(m.parallax * RAD);
  const sunKm = 149598000;
  const phase = Math.atan2(sunKm * Math.sin(psi), moonKm - sunKm * Math.cos(psi));
  return (1 + Math.cos(phase)) / 2;
};

const firstOrNull = (list) => (list.length > 0 ? list[0] : null);

/**
 * Light data for a place and a local day.
 *
 * @param lat, lon         degrees (east positive)
 * @param date             "YYYY-MM-DD", the local date
 * @param utcOffsetMinutes the local offset east of UTC on that date (−240 for EDT)
 * @param at               the instant illumination is for (the takeoff); local noon if missing
 * @returns { bmnt, sunrise, sunset, eent, moonrise, moonset, illumination } with instants or null, or null if the
 *          date or place cannot be read.
 */
export const lightData = ({ lat, lon, date, utcOffsetMinutes = 0, at = null }) => {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90) return null;
  if (!Number.isFinite(utcOffsetMinutes)) return null;
  const start = localMidnight(date, utcOffsetMinutes);
  if (start == null) return null;
  const end = start + DAY_MS;

  const sun = crossings(sunAbove(SUNRISE_ALTITUDE, lat, lon), start, end);
  const nautical = crossings(sunAbove(NAUTICAL_ALTITUDE, lat, lon), start, end);
  const moon = crossings(moonAbove(lat, lon), start, end);
  const when = Number.isFinite(at) ? at : start + DAY_MS / 2;

  return {
    bmnt: firstOrNull(nautical.rises),
    sunrise: firstOrNull(sun.rises),
    sunset: firstOrNull(sun.sets),
    eent: firstOrNull(nautical.sets),
    moonrise: firstOrNull(moon.rises),
    moonset: firstOrNull(moon.sets),
    illumination: moonIllumination(when),
  };
};
