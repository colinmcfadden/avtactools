// The slope profile: the ground along the landing heading through the LZ (the approach, the LZ and the departure) and across it, from the
// lines the server measured through the LZ's middle (`profiles` of /api/terrain-analysis). Turning the landing doghouse picks another line,
// with no new request. Pure, and held by the native apps to contracts/fixtures/planning/profile.json (src/contracts/webFixtures.test.js).

const FEET_PER_METRE = 3.28084;

/** Under this many feet of rise or fall through the LZ, it is called level. */
const LEVEL_FT = 1;

const headingText = (heading) => `${String(heading).padStart(3, "0")}°`;

const isNumber = (v) => typeof v === "number" && Number.isFinite(v);

/** [profiles] as the server sent it, or null when it cannot be read whole (a server from before it sends none). */
const readable = (profiles) => {
  if (!profiles || typeof profiles !== "object") return null;
  const { stepM, halfLengthM, headingStepDeg, lines } = profiles;
  if (!isNumber(stepM) || stepM <= 0 || !isNumber(halfLengthM) || !Number.isInteger(headingStepDeg) || headingStepDeg <= 0) return null;
  if (!Array.isArray(lines) || lines.length === 0) return null;
  return profiles;
};

/** The ground's height [atM] metres along a line of heights every [stepM] from -[halfM]; null where a height it needs is missing. */
const heightAt = (elevations, stepM, halfM, atM) => {
  const at = (atM + halfM) / stepM;
  const i = Math.floor(at);
  if (i < 0 || i >= elevations.length) return null;
  if (i === elevations.length - 1) return isNumber(elevations[i]) ? elevations[i] : null;
  const [a, b] = [elevations[i], elevations[i + 1]];
  if (!isNumber(a) || !isNumber(b)) return null;
  return a + (b - a) * (at - i);
};

/**
 * The ground along the line through the LZ's middle at [heading] (any whole number of degrees, true): the measured line nearest it (headings
 * are measured every `headingStepDeg`; a heading of 180 or more is the line of the opposite one, read backwards), as `heading` (the line's own,
 * 0..359), `distancesFt` (from behind the middle, negative, to ahead of it), `elevationsFt` (null where the terrain has none), `lz` (where the
 * boundary is, in feet along it, or null) and `changeFt` / `averageDeg`: how much the ground rises (positive) or falls through the LZ, from
 * where the line enters it to where it leaves, and the slope that makes on average (null when either end has no height). Null when the line is
 * not there or its heights do not match the others.
 */
export const profileLine = (profiles, heading) => {
  const p = readable(profiles);
  if (!p || !Number.isFinite(heading)) return null;
  const step = p.headingStepDeg;
  const nearest = ((Math.round(heading / step) * step) % 360 + 360) % 360;
  const reversed = nearest >= 180;
  const base = reversed ? nearest - 180 : nearest;
  const line = p.lines.find((l) => l?.heading === base);
  if (!line || !Array.isArray(line.elevationsFt) || line.elevationsFt.length < 2) return null;
  const count = line.elevationsFt.length;
  const heights = line.elevationsFt.map((h) => (isNumber(h) ? h : null));
  const elevations = reversed ? [...heights].reverse() : heights;
  const half = (p.stepM * (count - 1)) / 2;
  const distancesFt = elevations.map((_, i) => (i * p.stepM - half) * FEET_PER_METRE);
  const span = Array.isArray(line.lz) && line.lz.length === 2 && line.lz.every(isNumber) ? line.lz : null;
  const lzM = span ? (reversed ? [-span[1], -span[0]] : [span[0], span[1]]) : null;
  let changeFt = null;
  let averageDeg = null;
  if (lzM && lzM[1] > lzM[0]) {
    const entry = heightAt(elevations, p.stepM, half, lzM[0]);
    const exit = heightAt(elevations, p.stepM, half, lzM[1]);
    if (entry !== null && exit !== null) {
      changeFt = exit - entry;
      averageDeg = (Math.atan2(Math.abs(changeFt), (lzM[1] - lzM[0]) * FEET_PER_METRE) * 180) / Math.PI;
    }
  }
  return {
    heading: nearest,
    distancesFt,
    elevationsFt: elevations,
    lz: lzM ? [lzM[0] * FEET_PER_METRE, lzM[1] * FEET_PER_METRE] : null,
    changeFt,
    averageDeg,
  };
};

const changeWords = ({ lz, changeFt, averageDeg }) => {
  if (lz === null) return "the line misses the LZ";
  if (changeFt === null) return "no height at an edge of the LZ";
  const feet = Math.round(Math.abs(changeFt));
  if (feet < LEVEL_FT) return "level through the LZ";
  return `${changeFt > 0 ? "rises" : "falls"} ${feet} ft through the LZ, ${averageDeg.toFixed(1)}° on average`;
};

/**
 * The slope profile for the flight data's landing heading ([heading], 0..359, or null for none): `along` it (the approach behind the middle,
 * the departure ahead) and `across` it (left to right: the line 90° to the right of it), each profileLine's with its `words`, what it says in a
 * sentence ("Along 270°: falls 23 ft through the LZ, 4.4° on average"; "level through the LZ" under a foot; "the line misses the LZ" with no
 * crossing; "no height at an edge of the LZ"). Null with no heading or no profiles.
 */
export const slopeProfileFor = (profiles, heading) => {
  if (heading === null || heading === undefined) return null;
  const along = profileLine(profiles, heading);
  const across = profileLine(profiles, heading + 90);
  if (!along || !across) return null;
  return {
    along: { ...along, words: `Along ${headingText(along.heading)}: ${changeWords(along)}` },
    across: { ...across, words: `Across, left to right: ${changeWords(across)}` },
  };
};
