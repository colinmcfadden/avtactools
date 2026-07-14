/**
 * Curated 2525C building blocks for the symbol composers. These render distinct
 * icons in milsymbol (verified); labels are a starting point — the live preview
 * is authoritative, so users can adjust the SIDC and see the result.
 */

export const AFFILIATIONS = [
  { id: "F", label: "Friendly", color: "#3b82f6" },
  { id: "H", label: "Hostile", color: "#ef4444" },
  { id: "N", label: "Neutral", color: "#22c55e" },
  { id: "U", label: "Unknown", color: "#eab308" },
];

/** Common army unit function IDs (2525C positions 5–10), ground dimension. */
export const UNIT_FUNCTIONS = [
  { id: "infantry", label: "Infantry", functionId: "UCI---" },
  { id: "light_inf", label: "Infantry (Light)", functionId: "UCIL--" },
  { id: "air_assault", label: "Infantry (Air Assault)", functionId: "UCIS--" },
  { id: "airborne", label: "Infantry (Airborne)", functionId: "UCIA--" },
  { id: "mountain", label: "Infantry (Mountain)", functionId: "UCIO--" },
  { id: "mech_inf", label: "Infantry (Mech)", functionId: "UCIZ--" },
  { id: "armor", label: "Armor", functionId: "UCA---" },
  { id: "cavalry", label: "Cavalry / Recon", functionId: "UCR---" },
  { id: "field_arty", label: "Field Artillery", functionId: "UCF---" },
  { id: "mortar", label: "Mortar", functionId: "UCFHE-" },
  { id: "air_defense", label: "Air Defense", functionId: "UCD---" },
  { id: "atgm", label: "Anti-Armor (ATGM)", functionId: "UCAT--" },
  { id: "engineer", label: "Engineer", functionId: "UCE---" },
  { id: "aviation", label: "Aviation", functionId: "UCV---" },
  { id: "signal", label: "Signal", functionId: "UUS---" },
  { id: "medical", label: "Medical", functionId: "UUMS--" },
  { id: "maintenance", label: "Maintenance", functionId: "USM---" },
  { id: "supply", label: "Supply", functionId: "USS---" },
  { id: "hq", label: "Headquarters", functionId: "UH----" },
];

/** Echelon (2525C position 11). */
export const ECHELONS = [
  { id: "none", label: "—", code: "-" },
  { id: "team", label: "Team / Crew", code: "A" },
  { id: "squad", label: "Squad", code: "B" },
  { id: "section", label: "Section", code: "C" },
  { id: "platoon", label: "Platoon", code: "D" },
  { id: "company", label: "Company", code: "E" },
  { id: "battalion", label: "Battalion", code: "F" },
  { id: "regiment", label: "Regiment", code: "G" },
  { id: "brigade", label: "Brigade", code: "H" },
];

/**
 * Common threat starter symbols (2525C hostile ground equipment). Labels are a
 * convenience — confirm the rendered symbol in the preview and adjust the SIDC
 * as needed for the specific system.
 */
export const THREAT_PRESETS = [
  { id: "ew_radar", label: "EW / Acquisition Radar", sidc: "SHGPEWMAI-----" },
  { id: "air_srch_radar", label: "Air Search Radar", sidc: "SHGPEWRH------" },
  { id: "radar", label: "Radar", sidc: "SHGPEWRL------" },
  { id: "sam_launcher", label: "SAM Launcher", sidc: "SHGPEWRR------" },
  { id: "msl_launcher", label: "Missile Launcher", sidc: "SHGPEWMA------" },
  { id: "aaa_gun", label: "AAA (AD Gun)", sidc: "SHGPEWA-------" },
  { id: "adgun_self", label: "Self-Propelled AD Gun", sidc: "SHGPEWAH------" },
  { id: "manpad", label: "MANPADS", sidc: "SHGPEWMS------" },
];
