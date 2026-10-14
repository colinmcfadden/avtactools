// The slope tile's call: can a UH-60 land on this LZ/PZ at its landing heading, in the wind there, and which heading keeps it within
// limits. The server measures the steepest slope for every whole heading once (`byHeading` of /api/terrain-analysis); everything here is
// worked out from that, so turning the landing doghouse or a new weather report judges again at once, with no new request.
//
// Pure, and held by the native apps to contracts/fixtures/planning/summary.json (frontend/src/contracts/webFixtures.test.js).

/** The UH-60's slope landing limits in degrees with no wind: nose up, nose down, cross slope (the owner's, a UH-60 pilot, 2026-10-13). */
export const UH60_SLOPE_LIMITS = Object.freeze({ noseUp: 15, noseDown: 6, cross: 15 });

/** Every full five knots of steady wind takes two degrees off each limit. */
const KT_PER_STEP = 5;
const DEG_PER_STEP = 2;

/** Above this steady wind (knots) the UH-60 may not land nose down at all. */
export const NOSE_DOWN_PROHIBITED_ABOVE_KT = 15;

/** Recommended headings are these apart. */
const STEP_DEG = 5;

/** The three limits, in the order a tie between them is named. */
const LIMITS = ["noseUp", "noseDown", "cross"];
const FIELD = { noseUp: "noseHighMaxDeg", noseDown: "noseLowMaxDeg", cross: "crossSlopeMaxDeg" };
const LIMIT_WORDS = { noseUp: "nose up", noseDown: "nose down", cross: "cross slope" };

const MINUS = "−";

/**
 * The wind the limits are judged for, from what GET /api/weather answered: the steady speed in knots (a gust is not counted) and the
 * direction it blows from in degrees true (METAR winds are true), or variable. Null when there is nothing to go by: no station answered, or
 * it reported no speed. A direction that is neither a number nor `VRB` is none.
 */
export const slopeWindOf = (report) => {
  if (!report || typeof report !== "object") return null;
  const speed = report.wind_spd_kts;
  if (typeof speed !== "number" || !Number.isFinite(speed) || speed < 0) return null;
  const dir = report.wind_dir;
  return {
    speedKts: speed,
    fromDeg: typeof dir === "number" && Number.isFinite(dir) ? ((Math.round(dir) % 360) + 360) % 360 : null,
    variable: typeof dir === "string" && dir.trim().toUpperCase() === "VRB",
  };
};

/**
 * The limits for [wind] (slopeWindOf's): each 2° less for every full 5 knots of steady wind and never below 0, and no nose down at all above
 * 15 knots. With no wind report (null) they are the calm-wind limits, not reduced.
 */
export const slopeLimitsFor = (wind) => {
  const speed = wind?.speedKts;
  if (typeof speed !== "number" || !(speed >= 0)) return { ...UH60_SLOPE_LIMITS, reductionDeg: 0, noseDownProhibited: false };
  const reductionDeg = DEG_PER_STEP * Math.floor(speed / KT_PER_STEP);
  const noseDownProhibited = speed > NOSE_DOWN_PROHIBITED_ABOVE_KT;
  const reduced = (limit) => Math.max(0, limit - reductionDeg);
  return {
    noseUp: reduced(UH60_SLOPE_LIMITS.noseUp),
    noseDown: noseDownProhibited ? 0 : reduced(UH60_SLOPE_LIMITS.noseDown),
    cross: reduced(UH60_SLOPE_LIMITS.cross),
    reductionDeg,
    noseDownProhibited,
  };
};

/**
 * The landing heading the flight data gives ([landingHdg], its `landing_hdg`, which the landing doghouse writes), read as the doghouse box
 * reads its text, by its leading integer ("270°" is 270, "-90°" is 270), as a heading 0..359. Null when there is none: unlike the box, which
 * draws an unreadable heading as north, a heading that cannot be read is not taken as 000.
 */
export const landingHeadingOf = (landingHdg) => {
  const degrees = parseInt(landingHdg);
  return Number.isFinite(degrees) ? ((degrees % 360) + 360) % 360 : null;
};

/**
 * `byHeading` as one entry per whole heading 0..359, each limit's slope in tenths of a degree (the server rounds to 0.1). Null when it is not
 * there (a server from before it) or is not whole: a missing heading, or a slope that is not a number, judges nothing rather than guessing.
 */
const slopeTable = (byHeading) => {
  if (!Array.isArray(byHeading)) return null;
  const table = new Array(360).fill(null);
  byHeading.forEach((entry) => {
    const heading = entry?.heading;
    if (!Number.isInteger(heading) || heading < 0 || heading > 359 || table[heading]) return;
    const values = LIMITS.map((limit) => entry[FIELD[limit]]);
    if (!values.every((v) => typeof v === "number" && Number.isFinite(v) && v >= 0)) return;
    table[heading] = Object.fromEntries(LIMITS.map((limit, i) => [limit, Math.round(values[i] * 10)]));
  });
  return table.every(Boolean) ? table : null;
};

/** Each limit's excess at [slopes] in tenths of a degree (over it when above 0), and the worst of them (the first in LIMITS on a tie). */
const worstOf = (slopes, limits) =>
  LIMITS.map((limit) => ({ limit, tenths: slopes[limit] - limits[limit] * 10 })).reduce((worst, next) => (next.tenths > worst.tenths ? next : worst));

/** A slope is outside its limit only when it is greater than it, at the 0.1° the server reports: level ground (0.0) is never nose down. */
const withinLimits = (slopes, limits) => worstOf(slopes, limits).tenths <= 0;

/** Degrees between two headings, the short way round. */
const apart = (a, b) => {
  const d = Math.abs(a - b) % 360;
  return Math.min(d, 360 - d);
};

/** The heading that sorts first by [keys], each a number to keep small, the smaller heading winning a tie. */
const firstBy = (headings, keys) =>
  headings.reduce((best, h) => {
    for (const key of keys) {
      const d = key(h) - key(best);
      if (d !== 0) return d < 0 ? h : best;
    }
    return h < best ? h : best;
  });

/**
 * The heading to recommend among the multiples of 5°. Of those within [limits]: the one closest to into the wind ([windFromDeg], the
 * direction it blows from) when there is a wind to land into; else the one closest to [landingHeading]; else, with neither, the one with the
 * most margin (the least of its limits' excesses). `offDeg` is how far it is from the wind's direction or the landing heading. If none is
 * within limits, the one whose worst excess is smallest (then the closest, as before), with that limit and by how much. Ties go to the
 * smaller heading.
 */
export const recommendHeading = (table, limits, { windFromDeg = null, landingHeading = null } = {}) => {
  const candidates = Array.from({ length: 360 / STEP_DEG }, (_, i) => i * STEP_DEG);
  const toward = windFromDeg ?? landingHeading;
  const closeness = toward == null ? [] : [(h) => apart(h, toward)];
  const excess = (h) => worstOf(table[h], limits).tenths;
  const inside = candidates.filter((h) => withinLimits(table[h], limits));
  if (inside.length > 0) {
    const heading = firstBy(inside, toward == null ? [excess] : closeness);
    return {
      heading,
      within: true,
      reason: windFromDeg != null ? "wind" : landingHeading != null ? "landing" : "margin",
      offDeg: toward == null ? null : apart(heading, toward),
    };
  }
  const heading = firstBy(candidates, [excess, ...closeness]);
  const worst = worstOf(table[heading], limits);
  return { heading, within: false, limit: worst.limit, overDeg: worst.tenths / 10 };
};

const headingText = (heading) => `${String(heading).padStart(3, "0")}°`;

/** A wind speed as a station gives it: whole knots without a decimal point, anything else to one place. */
const knots = (speed) => (Number.isInteger(speed) ? String(speed) : speed.toFixed(1));

const reasonWords = ({ reason, offDeg }) => {
  if (reason === "wind") return offDeg === 0 ? "into wind" : `${offDeg}° off the wind`;
  if (reason === "landing") return offDeg === 0 ? "landing heading" : `${offDeg}° off landing heading`;
  return "most margin";
};

const recommendationWords = (r) =>
  r.within
    ? `Recommended ${headingText(r.heading)} · ${reasonWords(r)}`
    : `No heading keeps the whole LZ within limits: best ${headingText(r.heading)}, ${LIMIT_WORDS[r.limit]} ${r.overDeg.toFixed(1)}° over`;

const windWords = (wind, limits) => {
  if (!wind) return "No wind report: limits not reduced for wind";
  if (wind.speedKts === 0) return "Wind calm: limits not reduced";
  const what = `Wind ${wind.variable ? "variable " : ""}${knots(wind.speedKts)} kt`;
  if (limits.reductionDeg === 0) return `${what}: limits not reduced`;
  const reduced = `${what}: limits ${MINUS}${limits.reductionDeg}°`;
  return limits.noseDownProhibited ? `${reduced}, nose down prohibited above ${NOSE_DOWN_PROHIBITED_ABOVE_KT} kt` : reduced;
};

/** What an older server's analysis (no `byHeading`) gets under the slope: no heading was judged. */
export const HEADINGS_NOT_CHECKED = "Heading limits not checked (server not updated)";

const LANDING = { className: "status-safe", label: "LANDING" };
const CAUTION = { className: "status-warning", label: "CAUTION" };

/**
 * The call the slope tile makes for [terrainData] (an analysis of /api/terrain-analysis), the flight data's landing heading ([landingHdg], as
 * stored) and the wind ([wind], slopeWindOf's, or null for no report):
 *
 * - `className` and `label`: NO DATA with nothing measured; LIMIT EXCEEDED when a slope at the landing heading is over its wind-adjusted limit;
 *   else CAUTION above 10° of slope anywhere and LANDING. With no landing heading the recommended one is judged. HEADING REQUIRED (15° or
 *   more) is left only for a server that sends no `byHeading`.
 * - `max`: the steepest slope (`stats.maxDeg`), 0 when nothing was measured.
 * - `heading`: the heading judged, null when none was.
 * - `limits`: slopeLimitsFor(wind).
 * - `recommendation`: recommendHeading's, or null.
 * - `lines`: what the tile says under the slope, in smaller text.
 */
export const slopeCallFor = (terrainData, { landingHdg, wind = null } = {}) => {
  const limits = slopeLimitsFor(wind);
  if (!terrainData?.stats) {
    return { className: "status-safe", label: "NO DATA", max: 0, heading: null, limits, recommendation: null, lines: [] };
  }
  const max = terrainData.stats.maxDeg;
  const table = slopeTable(terrainData.byHeading);
  if (!table) {
    const call = max >= 15 ? { className: "status-warning", label: "HEADING REQUIRED" } : max > 10 ? CAUTION : LANDING;
    return { ...call, max, heading: null, limits, recommendation: null, lines: [HEADINGS_NOT_CHECKED] };
  }
  const intoWind = wind && wind.speedKts > 0 && !wind.variable ? wind.fromDeg : null;
  const landingHeading = landingHeadingOf(landingHdg);
  const recommendation = recommendHeading(table, limits, { windFromDeg: intoWind, landingHeading });
  const heading = landingHeading ?? recommendation.heading;
  const call = !withinLimits(table[heading], limits) ? { className: "status-danger", label: "LIMIT EXCEEDED" } : max > 10 ? CAUTION : LANDING;
  return { ...call, max, heading, limits, recommendation, lines: [recommendationWords(recommendation), windWords(wind, limits)] };
};
