/**
 * Readers for the AMPS/Mission X attribute value formats, inverse of
 * ampsFormats.js. Used to pull an imported mission's planned performance data
 * (airspeeds, altitudes, winds, clock times) back out of points.xml/legs.xml
 * so it can populate the same inline plan editor the sketched routes use.
 *
 * Observed real-file formats (GOAT SUCKER.msnx, AMPS-authored):
 *   AirspeedValue  "raw 80 Ground Knot"
 *   CruiseWind     "0 T/0 m/s"           (dir °true / speed m/s)
 *   CmdAlt         "341.0712 MM"         (meters MSL)
 *   Elevation      "325.8312 m DAFIF"    (meters ground, source-tagged)
 *   CmdClockTime   "9/17/2025 12:00:00.0000 AM"
 */

export const FT_PER_M = 3.28084;
const MPS_TO_KTS = 1 / 0.514444;

const AMPS_TYPE_TO_KEY = { ground: "ground", indicated: "indicated", true: "true" };

/** "raw 80 Ground Knot" -> { value: 80, type: "ground" }. */
export const parseAmpsAirspeed = (raw) => {
  if (!raw) return null;
  const m = String(raw).match(/([\d.]+)\s+(Ground|Indicated|True)\s+Knot/i);
  if (!m) return null;
  return { value: Math.round(parseFloat(m[1])), type: AMPS_TYPE_TO_KEY[m[2].toLowerCase()] };
};

/** "270 T/10.5 m/s" -> { dirTrue: 270, speedKts: 20 }. */
export const parseAmpsWind = (raw) => {
  if (!raw) return null;
  const m = String(raw).match(/(-?[\d.]+)\s*T\s*\/\s*([\d.]+)\s*m\/s/i);
  if (!m) return null;
  return {
    dirTrue: Math.round(parseFloat(m[1])),
    speedKts: Math.round(parseFloat(m[2]) * MPS_TO_KTS),
  };
};

/** Leading numeric value of a meters-tagged field ("341.07 MM", "325 m DAFIF") -> meters. */
export const parseAmpsMeters = (raw) => {
  if (raw == null) return null;
  const m = String(raw).match(/-?[\d.]+/);
  return m ? parseFloat(m[0]) : null;
};

/** "9/17/2025 1:30:00.0000 PM" -> { date: "2025-09-17", time: "13:30:00" }. */
export const parseAmpsClock = (raw) => {
  if (!raw) return null;
  const m = String(raw).match(
    /(\d{1,2})\/(\d{1,2})\/(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})(?:[.\d]*)?\s*(AM|PM)/i,
  );
  if (!m) return null;
  const [, mo, day, year, hhRaw, mm, ss, meridiem] = m;
  let hh = parseInt(hhRaw, 10) % 12;
  if (meridiem.toUpperCase() === "PM") hh += 12;
  return {
    date: `${year}-${String(mo).padStart(2, "0")}-${String(day).padStart(2, "0")}`,
    time: `${String(hh).padStart(2, "0")}:${mm}:${ss}`,
  };
};
