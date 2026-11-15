import { lineLengthKm, linePoints, linesFromData, plainPoints, savedLines } from "../setLines/setLines";

/*
 * A phase line (the owner's, 2026-11-14): a control measure drawn across the map as a plain solid line with `PL <NAME>` at each end,
 * as the doctrinal graphic is. It is kept with a set of routes beside its routes, as a FLOT is (`phaseLines` of the set's document,
 * left out when there are none), as { id, name, points: [{ lat, lon }, ...] }. It is no route, so it is never written to an AMPS or
 * ForeFlight file. What it shares with a FLOT is feature/setLines.
 *
 * `name` is what follows PL, in capitals ("RED" is drawn `PL RED`).
 */

export const PHASE_LINE_NAME_MAX = 24;

const PHONETIC = [
  "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE",
  "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU",
];

/**
 * A name as a phase line keeps it: in capitals, its spaces single, without a PL typed in front of it (the label says PL itself), and
 * at most PHASE_LINE_NAME_MAX characters, counted as characters so none is cut in half. Nothing left is no name.
 */
export const cleanPhaseLineName = (text) => {
  const name = String(text ?? "").trim().toUpperCase().replace(/\s+/g, " ").replace(/^PL(?: |$)/, "");
  return [...name].slice(0, PHASE_LINE_NAME_MAX).join("").trim();
};

/** What is written at each end of a phase line. */
export const phaseLineLabel = (line) => {
  const name = typeof line?.name === "string" ? line.name.trim() : "";
  return name ? `PL ${name}` : "PL";
};

/** The name a new phase line is offered: the first word of the phonetic alphabet none of `lines` has, and past ZULU a number. */
export const nextPhaseLineName = (lines) => {
  const taken = new Set((lines ?? []).map((line) => cleanPhaseLineName(line?.name)));
  const word = PHONETIC.find((candidate) => !taken.has(candidate));
  if (word) return word;
  let n = PHONETIC.length + 1;
  while (taken.has(String(n))) n += 1;
  return String(n);
};

export const phaseLinePoints = linePoints;

export const createPhaseLine = (points, id, name) => ({ id, name: cleanPhaseLineName(name), points: plainPoints(points) });

/** The phase line under another name; a name with nothing in it changes nothing. */
export const renamePhaseLine = (line, name) => {
  const next = cleanPhaseLineName(name);
  return next ? { ...line, name: next } : line;
};

/** The phase lines of a set's saved data; whatever is not an object with an id is none. */
export const phaseLinesFromData = (data) => linesFromData(data, "phaseLines");

/** What is saved of phase lines: without the set they are filed under here. */
export const savedPhaseLines = savedLines;

export const phaseLineLengthKm = lineLengthKm;

// -- On the screen ------------------------------------------------------------------------------------------------------------------

const outwardFrom = (end, inward) => {
  for (const point of inward) {
    const dx = end.x - point.x;
    const dy = end.y - point.y;
    const length = Math.hypot(dx, dy);
    if (length > 0) return { ux: dx / length, uy: dy / length };
  }
  return null;
};

/**
 * Where a phase line's two labels go, from its points in screen pixels: its first and last point, each with the unit vector that
 * leads on out of the line there (screen y runs downward). A line with no length has none.
 */
export const phaseLineEnds = (pixels) => {
  if (pixels.length < 2) return [];
  const first = pixels[0];
  const last = pixels[pixels.length - 1];
  const out = [outwardFrom(first, pixels.slice(1)), outwardFrom(last, pixels.slice(0, -1).reverse())];
  if (!out[0] || !out[1]) return [];
  return [{ x: first.x, y: first.y, ...out[0] }, { x: last.x, y: last.y, ...out[1] }];
};

/**
 * How far a label's middle is from the end it names, in halves of its own width (`fx`) and height (`fy`): the direction out of the
 * line stretched until it reaches the label's edge, so the end is on that edge whatever the label's size and the line is never under it.
 */
export const labelShift = ({ ux, uy }) => {
  const reach = Math.max(Math.abs(ux), Math.abs(uy));
  return reach > 0 ? { fx: ux / reach, fy: uy / reach } : { fx: 0, fy: 0 };
};
