import ms from "milsymbol";
import L from "leaflet";

/**
 * MIL-STD-2525C / APP-6 symbology via milsymbol (https://github.com/spatialillusions/milsymbol).
 * Symbols are built from a 15-char SIDC and rendered to SVG — used for both
 * threat markers (which already carry a SIDC) and the unit symbol builder.
 * Everything renders for any valid SIDC, so a live preview lets the user
 * compose/verify a symbol the way symbol.army does.
 */

/** Builds a 2525C 15-char SIDC from parts (pads/truncates defensively). */
export const buildSidc = ({
  affiliation = "F", // F Friend, H Hostile, N Neutral, U Unknown
  dimension = "G", // G Ground, A Air, S Sea surface
  status = "P", // P Present, A Anticipated
  functionId = "------", // positions 5–10
  echelon = "-", // position 11
} = {}) => {
  const fn = `${functionId}------`.slice(0, 6);
  return `S${affiliation}${dimension}${status}${fn}${echelon}----`.slice(0, 15).padEnd(15, "-");
};

/** Returns a copy of a SIDC with a different affiliation (position 2). */
export const withAffiliation = (sidc, affiliation) =>
  sidc && sidc.length >= 2 ? sidc[0] + affiliation + sidc.slice(2) : sidc;

/** Reads the affiliation character (position 2) from a SIDC. */
export const sidcAffiliation = (sidc) => (sidc && sidc.length >= 2 ? sidc[1] : "U");

/** Splits a 2525C SIDC into the parts the unit builder edits. */
export const parseSidc = (sidc) => {
  if (!sidc || sidc.length < 11) return {};
  return {
    affiliation: sidc[1],
    dimension: sidc[2],
    status: sidc[3],
    functionId: sidc.slice(4, 10),
    echelon: sidc[10],
  };
};

const makeSymbol = (sidc, options) => {
  if (!sidc) return null;
  try {
    const s = new ms.Symbol(sidc, { size: 30, ...options });
    return s.isValid() ? s : null;
  } catch {
    return null;
  }
};

/** SVG string for a SIDC, or null if it can't be rendered. */
export const symbolSvg = (sidc, options = {}) => {
  const s = makeSymbol(sidc, options);
  return s ? s.asSVG() : null;
};

/** An <img>-ready data URI for a SIDC (used for menu previews). */
export const symbolDataUri = (sidc, options = {}) => {
  const svg = symbolSvg(sidc, options);
  return svg ? `data:image/svg+xml,${encodeURIComponent(svg)}` : null;
};

/** { svg, size, anchor } for building a custom Leaflet icon; null if invalid. */
export const symbolParts = (sidc, options = {}) => {
  const s = makeSymbol(sidc, options);
  return s ? { svg: s.asSVG(), size: s.getSize(), anchor: s.getAnchor() } : null;
};

/**
 * A Leaflet divIcon anchored using milsymbol's own size/anchor so the symbol's
 * reference point sits exactly on the map coordinate.
 */
export const symbolDivIcon = (sidc, options = {}) => {
  const s = makeSymbol(sidc, options);
  if (!s) return null;
  const size = s.getSize();
  const anchor = s.getAnchor();
  return L.divIcon({
    className: "milsym-icon",
    html: s.asSVG(),
    iconSize: [size.width, size.height],
    iconAnchor: [anchor.x, anchor.y],
  });
};
