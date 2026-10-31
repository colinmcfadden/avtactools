// The pure parts of MIL-STD-2525C symbol codes (SIDCs), kept apart from milsymbol and Leaflet so the native apps can be held to them
// (frontend/src/contracts/symbolFixtures.test.js). Nothing here changes how a symbol is built.

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
