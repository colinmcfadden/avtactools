/**
 * Top-down aircraft silhouettes for the map.
 *
 * Inline SVG rather than PNGs: they stay sharp at any zoom (the sprite is
 * scaled to the real rotor diameter, so a Chinook is visibly bigger than a
 * Little Bird), they recolour for violation states, and they add no requests.
 *
 * Every silhouette is drawn in a 100x100 box, nose up, centred on the mast, so
 * rotating the element by the aircraft heading Just Works and the rotor disc
 * lines up with the footprint the separation math uses.
 */

const DISC = 46; // rotor-disc radius in viewBox units, i.e. the sprite's edge

/**
 * `count` individual blades radiating from the mast — one line per blade, not
 * one line per opposed pair, so a 4-blade head reads as four blades.
 */
const blades = (count, cx, cy, radius, width = 2.4) => {
  const out = [];
  for (let i = 0; i < count; i += 1) {
    const angle = (2 * Math.PI * i) / count;
    const x = cx + Math.sin(angle) * radius;
    const y = cy - Math.cos(angle) * radius;
    out.push(
      `<line x1="${cx}" y1="${cy}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}" stroke="var(--ac-blade)" stroke-width="${width}" stroke-linecap="round" />`,
    );
  }
  return out.join("");
};

const disc = (cx, cy, r) =>
  `<circle cx="${cx}" cy="${cy}" r="${r}" fill="var(--ac-disc)" stroke="var(--ac-disc-edge)" stroke-width="1.2" />`;

/**
 * Single main rotor with a tail boom — the utility/attack layout.
 *
 * Drawn back to front: rotor disc, then the airframe on top of it (so the
 * fuselage stays legible against the disc), then the blades over everything,
 * then the hub. Nose points up.
 */
const conventional = ({ bladeCount, bodyWidth, bodyLength, tailWidth, stabWidth }) => {
  const cx = 50;
  const cy = 42; // mast sits forward of centre, leaving room for the boom
  const halfBody = bodyLength / 2;
  const noseY = cy - halfBody;
  const tailY = cy + halfBody * 0.55;
  const halfW = bodyWidth / 2;

  return `
    ${disc(cx, cy, DISC)}
    <rect x="${cx - tailWidth / 2}" y="${tailY}" width="${tailWidth}" height="${94 - tailY}"
          rx="${tailWidth / 2}" fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="1" />
    <rect x="${cx - stabWidth / 2}" y="${86}" width="${stabWidth}" height="4"
          rx="2" fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="0.8" />
    <ellipse cx="${cx + tailWidth}" cy="93" rx="2" ry="5"
             fill="var(--ac-blade)" stroke="var(--ac-edge)" stroke-width="0.8" />
    <path d="M ${cx} ${noseY}
             C ${cx + halfW} ${noseY + halfBody * 0.35}, ${cx + halfW} ${cy + halfBody * 0.25}, ${cx + halfW * 0.62} ${tailY + 2}
             L ${cx - halfW * 0.62} ${tailY + 2}
             C ${cx - halfW} ${cy + halfBody * 0.25}, ${cx - halfW} ${noseY + halfBody * 0.35}, ${cx} ${noseY} Z"
          fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="1.2" />
    <path d="M ${cx} ${noseY + 2}
             C ${cx + halfW * 0.7} ${noseY + halfBody * 0.4}, ${cx + halfW * 0.7} ${noseY + halfBody * 0.6}, ${cx} ${noseY + halfBody * 0.62}
             C ${cx - halfW * 0.7} ${noseY + halfBody * 0.6}, ${cx - halfW * 0.7} ${noseY + halfBody * 0.4}, ${cx} ${noseY + 2} Z"
          fill="var(--ac-glass)" />
    ${blades(bladeCount, cx, cy, DISC - 2)}
    <circle cx="${cx}" cy="${cy}" r="3" fill="var(--ac-edge)" />`;
};

/** Tandem rotors — two discs fore and aft over one long fuselage. */
const tandem = () => {
  const cx = 50;
  const fore = 26;
  const aft = 74;
  const r = 25;
  return `
    ${disc(cx, fore, r)}
    ${disc(cx, aft, r)}
    <rect x="${cx - 12}" y="14" width="24" height="74" rx="9"
          fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="1.2" />
    <path d="M ${cx - 12} 26 C ${cx - 12} 16, ${cx + 12} 16, ${cx + 12} 26 Z" fill="var(--ac-glass)" />
    <rect x="${cx - 6}" y="8" width="12" height="9" rx="3"
          fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="1" />
    <rect x="${cx - 7}" y="84" width="14" height="10" rx="3"
          fill="var(--ac-body)" stroke="var(--ac-edge)" stroke-width="1" />
    ${blades(3, cx, fore, r - 1.5)}
    ${blades(3, cx, aft, r - 1.5)}
    <circle cx="${cx}" cy="${fore}" r="2.6" fill="var(--ac-edge)" />
    <circle cx="${cx}" cy="${aft}" r="2.6" fill="var(--ac-edge)" />`;
};

// Proportions follow each airframe's real planform: the Apache is long and
// narrow, the Lakota short and stubby, the Little Bird almost all cabin.
const SILHOUETTES = {
  uh60: () => conventional({ bladeCount: 4, bodyWidth: 22, bodyLength: 52, tailWidth: 7, stabWidth: 22 }),
  ah64: () => conventional({ bladeCount: 4, bodyWidth: 13, bodyLength: 58, tailWidth: 6, stabWidth: 24 }),
  ch47: tandem,
  uh72: () => conventional({ bladeCount: 4, bodyWidth: 20, bodyLength: 40, tailWidth: 6, stabWidth: 18 }),
  mh6: () => conventional({ bladeCount: 5, bodyWidth: 21, bodyLength: 30, tailWidth: 4, stabWidth: 16 }),
  generic: () => conventional({ bladeCount: 4, bodyWidth: 20, bodyLength: 48, tailWidth: 7, stabWidth: 20 }),
};

/**
 * Palette per state. A light airframe with a dark outline stays legible over
 * both satellite imagery and the dark map themes; violations go red.
 */
const PALETTES = {
  normal: {
    body: "#dbe2ea", edge: "#11161c", glass: "rgba(90,167,212,0.55)",
    disc: "rgba(214,224,236,0.13)", discEdge: "rgba(214,224,236,0.34)", blade: "rgba(226,233,241,0.7)",
  },
  violation: {
    body: "#f0c3bf", edge: "#3a1210", glass: "rgba(214,107,100,0.5)",
    disc: "rgba(220,38,38,0.20)", discEdge: "rgba(224,115,108,0.55)", blade: "rgba(240,180,175,0.8)",
  },
  ghost: {
    body: "#8d97a3", edge: "#1b2027", glass: "rgba(120,140,160,0.35)",
    disc: "rgba(148,163,178,0.08)", discEdge: "rgba(148,163,178,0.22)", blade: "rgba(160,172,186,0.45)",
  },
};

/**
 * SVG markup for an aircraft.
 *
 * @param iconKey   profile.icon_key; unknown keys fall back to the generic shape
 * @param sizePx    rendered edge length — the rotor disc fills it
 * @param state     "normal" | "violation" | "ghost"
 */
export const aircraftSvg = (iconKey, sizePx = 40, state = "normal") => {
  const draw = SILHOUETTES[iconKey] || SILHOUETTES.generic;
  const palette = PALETTES[state] || PALETTES.normal;
  const style = [
    `--ac-body:${palette.body}`,
    `--ac-edge:${palette.edge}`,
    `--ac-glass:${palette.glass}`,
    `--ac-disc:${palette.disc}`,
    `--ac-disc-edge:${palette.discEdge}`,
    `--ac-blade:${palette.blade}`,
  ].join(";");
  return `<svg viewBox="0 0 100 100" width="${sizePx}" height="${sizePx}" style="${style}" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">${draw()}</svg>`;
};

/** Small preview for pickers and lists, where scale-to-rotor doesn't apply. */
export const aircraftPreviewSvg = (iconKey, sizePx = 28) =>
  aircraftSvg(iconKey, sizePx, "normal");

export const ICON_KEYS = Object.keys(SILHOUETTES);
