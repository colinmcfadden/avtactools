/**
 * Reads a latitude/longitude out of free text, in whatever shape it arrives.
 *
 * The MGRS target input accepts a pasted coordinate as well as a grid, so this
 * has to cope with everything people actually paste — Google Maps, ForeFlight,
 * a flight plan, a spreadsheet cell, a text message:
 *
 *   34.545678, -84.123456          decimal degrees
 *   N34.545678 W084.123456         decimal with hemispheres
 *   34°32.740'N 084°07.407'W       degrees + decimal minutes (aviation)
 *   34°32'44.4"N 84°07'24.4"W      degrees/minutes/seconds
 *   34 32 44.4 N, 84 07 24.4 W     the same, unpunctuated
 *   3432.740N 08407.407W           packed DDMM.mmm (flight plan)
 *   343244N 0840724W               packed DDMMSS
 *
 * Anything that is a valid MGRS grid is deliberately refused, so typing a grid
 * never gets reinterpreted as a coordinate.
 */

// Band letters run C–X skipping I and O; 100 km square letters skip I and O
// too. Matching the real grammar (rather than [A-Z]) keeps "34N 084W" from
// being mistaken for a grid.
const MGRS_PATTERN = /^\d{1,2}[C-HJ-NP-X][A-HJ-NP-Z]{2}\d{0,10}$/i;

const HEMISPHERES = {
  N: { axis: "lat", sign: 1 },
  S: { axis: "lat", sign: -1 },
  E: { axis: "lon", sign: 1 },
  W: { axis: "lon", sign: -1 },
};

const AXIS_MAX = { lat: 90, lon: 180 };

/** True when the text is (or is becoming) an MGRS grid rather than a coordinate. */
export const looksLikeMgrs = (text) =>
  MGRS_PATTERN.test(String(text ?? "").replace(/\s+/g, ""));

// Punctuation that appears in coordinates and never in a grid.
const COORDINATE_PUNCTUATION = /[.,;/°º˚∘′‵'´`″‶"“”+\-‐-―−\t\n]/;

/**
 * A lenient, keystroke-by-keystroke check used to decide whether the target
 * input is being given a coordinate rather than a grid.
 *
 * Deliberately looser than {@link parseCoordinate}: it has to say "yes" to
 * half-typed text like "34." or "34.5, -" so the field stops reformatting mid
 * entry. Being wrong is cheap — the value is only reformatted, never rejected.
 */
export const looksLikeCoordinateText = (text) => {
  const raw = String(text ?? "");
  if (!raw.trim()) return false;
  if (looksLikeMgrs(raw)) return false;
  if (COORDINATE_PUNCTUATION.test(raw)) return true;
  // Hemisphere letters and nothing else alphabetic ("34N 84W") mean a
  // coordinate; any other letter is a grid being typed.
  return /[NSEW]/i.test(raw) && !/[A-MOP-VXYZ]/i.test(raw);
};

/**
 * Folds the many ways coordinate punctuation gets typed or pasted down to a
 * plain ASCII form: degree/minute/second marks become spaces, exotic dashes
 * become hyphens, and separators become spaces.
 */
const normalize = (text) =>
  String(text ?? "")
    .replace(/[°º˚∘]/g, " ")          // ° º ˚ ∘
    .replace(/[′‵'´`]/g, " ")              // ′ ‵ ' ´ `
    .replace(/[″‶"“”]/g, " ")         // ″ ‶ " " "
    .replace(/[‐-―−]/g, "-")               // ‐ – — − …
    .replace(/[,;/|\t\n\r]+/g, " ")
    .trim();

/**
 * Splits normalized text into ordered number and hemisphere tokens.
 *
 * Numbers keep their raw text: leading zeros are what distinguish a packed
 * "0840724" (DDDMMSS) from a plain 840724, and Number() would discard them.
 */
const tokenize = (text) => {
  const tokens = [];
  const pattern = /([NSEW])|([+-]?\d+(?:\.\d+)?)/gi;
  let match = pattern.exec(text);
  while (match !== null) {
    if (match[1]) {
      // Whether the letter is glued to the number before it ("34.5N") or the
      // one after it ("W084") is the only thing separating a suffix from a
      // prefix, and it decides which value the hemisphere applies to.
      const before = text[match.index - 1];
      const after = text[match.index + match[1].length];
      tokens.push({
        type: "hemi",
        letter: match[1].toUpperCase(),
        suffixLike: /\d/.test(before || ""),
        prefixLike: /[\d+-]/.test(after || ""),
      });
    } else {
      const raw = match[2];
      const value = Number(raw);
      if (!Number.isFinite(value)) return null;
      tokens.push({
        type: "number",
        raw,
        value,
        negative: raw.startsWith("-"),
        // Integer digits *as written*, leading zeros included.
        intDigits: raw.replace(/^[+-]/, "").split(".")[0].length,
      });
    }
    match = pattern.exec(text);
  }
  return tokens;
};

/**
 * Collects tokens into two coordinate groups.
 *
 * A hemisphere letter closes the group it belongs to, whether it was written
 * in front (`N34 30`) or behind (`34 30 N`). With no hemisphere letters at all
 * the numbers are simply split down the middle.
 */
const group = (tokens) => {
  const hasHemisphere = tokens.some((t) => t.type === "hemi");

  if (!hasHemisphere) {
    const numbers = tokens.filter((t) => t.type === "number");
    // 2 = D/D, 4 = D M/D M, 6 = D M S/D M S. Anything else is not a pair.
    if (![2, 4, 6].includes(numbers.length)) return null;
    const half = numbers.length / 2;
    return [
      { numbers: numbers.slice(0, half), hemi: null },
      { numbers: numbers.slice(half), hemi: null },
    ];
  }

  const groups = [];
  let current = { numbers: [], hemi: null };
  let pending = null;

  for (const token of tokens) {
    if (token.type === "number") {
      if (current.numbers.length === 0 && pending) {
        current.hemi = pending;
        pending = null;
      }
      current.numbers.push(token);
      continue;
    }
    // A letter written in front of its number ("W084"), or one arriving when
    // this group is already labelled ("N34 32.74 W084 07.41"), belongs to the
    // group that follows — it must not close the group it interrupted.
    const startsNextGroup =
      current.hemi !== null || (token.prefixLike && !token.suffixLike);

    if (current.numbers.length === 0) {
      pending = token.letter; // prefix on the very first value
    } else if (startsNextGroup) {
      groups.push(current);
      current = { numbers: [], hemi: null };
      pending = token.letter;
    } else {
      current.hemi = token.letter; // suffix: "34 30 N"
      groups.push(current);
      current = { numbers: [], hemi: null };
    }
  }
  if (current.numbers.length > 0) groups.push(current);

  return groups.length === 2 ? groups : null;
};

/** Splits a packed DDMM[SS] integer into parts, or null if it can't be one. */
const unpack = (token) => {
  const digits = token.raw.replace(/^[+-]/, "");
  const [intPart, decimals = ""] = digits.split(".");
  const fraction = decimals ? Number(`0.${decimals}`) : 0;

  // Width tells us where the degrees stop: DDMM / DDDMM / DDMMSS / DDDMMSS.
  const layout = { 4: [2, 0], 5: [3, 0], 6: [2, 1], 7: [3, 1] }[intPart.length];
  if (!layout) return null;
  const [degWidth, hasSeconds] = layout;

  const degrees = Number(intPart.slice(0, degWidth));
  const minutes = Number(intPart.slice(degWidth, degWidth + 2));
  const seconds = hasSeconds ? Number(intPart.slice(degWidth + 2)) + fraction : 0;
  const minuteFraction = hasSeconds ? 0 : fraction;

  if (minutes >= 60 || seconds >= 60) return null;
  return {
    degrees: degrees + (minutes + minuteFraction) / 60 + seconds / 3600,
    format: hasSeconds ? "packed-dms" : "packed-ddm",
  };
};

/** Converts one group's numbers to absolute decimal degrees. */
const toDegrees = (numbers, axisMax) => {
  if (numbers.length > 3) return null;

  if (numbers.length >= 2) {
    const [d, m, s] = numbers.map((n) => Math.abs(n.value));
    if (m >= 60 || (s !== undefined && s >= 60)) return null;
    return {
      degrees: d + m / 60 + (s || 0) / 3600,
      format: numbers.length === 3 ? "dms" : "ddm",
    };
  }

  const token = numbers[0];
  const magnitude = Math.abs(token.value);

  // A plain reading wins whenever it's in range. Packed forms are recognised
  // only when the number can't be degrees at all (3432.740 as a latitude,
  // 08407.407 as a longitude), which is what makes "0034" read as 34° rather
  // than 00°34'. The trade-off is that a *zero-padded packed* value small
  // enough to also be valid degrees — "0032.740N" near the equator — reads as
  // plain degrees. That's unambiguous-looking text with two honest readings,
  // and preferring the common one keeps behaviour predictable.
  if (magnitude <= axisMax) return { degrees: magnitude, format: "decimal" };

  return unpack(token);
};

const FORMAT_LABELS = {
  decimal: "decimal degrees",
  ddm: "degrees/decimal minutes",
  dms: "degrees/minutes/seconds",
  "packed-ddm": "packed DDMM.mmm",
  "packed-dms": "packed DDMMSS",
};

/**
 * Parses free text into `{ lat, lon, format, label }`, or null when the text
 * isn't a coordinate (including when it's a valid MGRS grid).
 */
export const parseCoordinate = (text) => {
  const raw = String(text ?? "").trim();
  if (!raw) return null;
  // A grid is a grid. Never reinterpret one as a coordinate.
  if (looksLikeMgrs(raw)) return null;

  const tokens = tokenize(normalize(raw));
  if (!tokens || tokens.length === 0) return null;

  // Letters other than N/S/E/W mean this is something else entirely (a place
  // name, a partial grid); refuse rather than parse the digits out of it.
  if (/[A-MOP-VXYZ]/i.test(normalize(raw).replace(/[NSEW]/gi, ""))) return null;

  const groups = group(tokens);
  if (!groups) return null;

  const resolved = groups.map((g) => {
    const info = g.hemi ? HEMISPHERES[g.hemi] : null;
    // Use the axis limit we know about; without a hemisphere assume the wider
    // longitude limit so a valid longitude in first position still parses and
    // gets sorted out by the ordering rules below.
    const axisMax = info ? AXIS_MAX[info.axis] : AXIS_MAX.lon;
    const converted = toDegrees(g.numbers, axisMax);
    if (!converted) return null;
    const signedByText = g.numbers[0].negative ? -1 : 1;
    return {
      axis: info?.axis ?? null,
      value: converted.degrees * (info ? info.sign : signedByText),
      format: converted.format,
    };
  });

  if (resolved.some((r) => r === null)) return null;
  const [first, second] = resolved;

  let lat;
  let lon;
  if (first.axis && second.axis && first.axis !== second.axis) {
    // Hemispheres name the axes outright, in either order ("W084 N34").
    ({ lat, lon } = first.axis === "lat"
      ? { lat: first.value, lon: second.value }
      : { lat: second.value, lon: first.value });
  } else if (first.axis === second.axis && first.axis) {
    return null; // "34N 84N" — not a coordinate pair.
  } else if (first.axis === "lon" || second.axis === "lat") {
    // Only one side was labelled, but that's enough to fix both.
    lat = second.value;
    lon = first.value;
  } else {
    // Nothing labelled: latitude first, as every mapping tool writes it.
    //
    // Deliberately no cleverness here. Guessing from magnitudes (treating a
    // first value beyond ±90 as a longitude) would let "91.0, -84.1" quietly
    // become a point in Antarctica instead of reporting the out-of-range
    // latitude it almost certainly is. Both orders are valid coordinates and
    // nothing in the text distinguishes them, so this follows the convention
    // strictly and rejects what the convention calls invalid. Anyone who
    // genuinely means longitude first can say so with a hemisphere letter.
    lat = first.value;
    lon = second.value;
  }

  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;

  const format = first.format === second.format ? first.format : "mixed";
  return {
    lat,
    lon,
    format,
    label: FORMAT_LABELS[format] || "lat/long",
  };
};

/** Compact display of a parsed pair, for confirming what was recognised. */
export const formatDecimal = (lat, lon, places = 5) =>
  `${lat.toFixed(places)}, ${lon.toFixed(places)}`;
