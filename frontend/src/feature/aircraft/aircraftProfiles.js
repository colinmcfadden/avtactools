/**
 * Aircraft profile shape and the geometry derived from it.
 *
 * A profile arrives from /api/aircraft-profiles (admin-managed master list plus
 * the user's own). Everything the map and the planner need is derived here so
 * there is exactly one place that knows how a rotor diameter becomes a
 * footprint, and one place that decides when two aircraft are too close.
 */

export const METERS_TO_FEET = 3.280839895;

/**
 * Used until the API responds, and whenever a saved map references a profile
 * that no longer exists. Matches the UH-60L values extracted from UH60L.vidx,
 * which is what the app assumed before profiles existed — so an unresolved
 * profile behaves exactly like the old hard-coded build.
 */
export const FALLBACK_PROFILE = Object.freeze({
  id: null,
  slug: "uh60l",
  name: "UH-60L Black Hawk",
  designation: "UH-60L",
  icon_key: "uh60",
  is_system: true,
  rotor_diameter_m: 16.357,
  rotor_tip_clearance_m: 60,
  default_airspeed_kts: 100,
  default_airspeed_type: "ground",
  max_indicated_kts: 193,
  default_altitude_ft: 50,
  default_altitude_ref: "agl",
  min_altitude_ft_msl: -2000,
  max_altitude_ft_msl: 20000,
  default_fuel_flow_lb_hr: 960,
  default_gross_weight_lb: 16000,
  perf_source: "vidx",
  amps_vehicle_description: "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L",
  has_template: false,
  template_kind: null,
});

const num = (value, fallback) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

/** Fills in anything the server left out so callers never guard for null. */
export const normalizeProfile = (raw) => {
  if (!raw) return { ...FALLBACK_PROFILE };
  return {
    ...FALLBACK_PROFILE,
    ...raw,
    icon_key: raw.icon_key || "generic",
    rotor_diameter_m: num(raw.rotor_diameter_m, FALLBACK_PROFILE.rotor_diameter_m),
    rotor_tip_clearance_m: num(
      raw.rotor_tip_clearance_m,
      FALLBACK_PROFILE.rotor_tip_clearance_m,
    ),
    default_airspeed_kts: num(raw.default_airspeed_kts, FALLBACK_PROFILE.default_airspeed_kts),
    max_indicated_kts: num(raw.max_indicated_kts, FALLBACK_PROFILE.max_indicated_kts),
    default_altitude_ft: num(raw.default_altitude_ft, FALLBACK_PROFILE.default_altitude_ft),
    default_fuel_flow_lb_hr: num(
      raw.default_fuel_flow_lb_hr,
      FALLBACK_PROFILE.default_fuel_flow_lb_hr,
    ),
    default_gross_weight_lb: num(
      raw.default_gross_weight_lb,
      FALLBACK_PROFILE.default_gross_weight_lb,
    ),
  };
};

/* --------------------------------------------------------------------------
 * Geometry
 * ----------------------------------------------------------------------- */

export const rotorRadiusM = (profile) => normalizeProfile(profile).rotor_diameter_m / 2;
export const rotorRadiusFt = (profile) => rotorRadiusM(profile) * METERS_TO_FEET;
export const tipClearanceM = (profile) => normalizeProfile(profile).rotor_tip_clearance_m;
export const tipClearanceFt = (profile) => tipClearanceM(profile) * METERS_TO_FEET;

/**
 * Center-to-center spacing for a formation of one aircraft type: a full rotor
 * diameter plus the required tip-to-tip clearance. Derived, never stored, so
 * diameter and clearance can't drift apart.
 */
export const centerSpacingM = (profile) => {
  const p = normalizeProfile(profile);
  return p.rotor_diameter_m + p.rotor_tip_clearance_m;
};

export const centerSpacingFt = (profile) => centerSpacingM(profile) * METERS_TO_FEET;

/** Planning-grid cell for LZ capacity: one aircraft's square of ground. */
export const spotSizeSqFt = (profile) => centerSpacingFt(profile) ** 2;

/**
 * Conservative square-grid capacity estimate for an area. Actual placement
 * still depends on LZ shape, obstacles, and the landing plan.
 */
export const capacityForArea = (areaSqFt, profile) => {
  const spot = spotSizeSqFt(profile);
  if (!Number.isFinite(areaSqFt) || areaSqFt <= 0 || spot <= 0) return 0;
  return Math.floor(areaSqFt / spot);
};

/* --------------------------------------------------------------------------
 * Separation between two aircraft
 * ----------------------------------------------------------------------- */

/**
 * Separation requirement for a *pair*, which may be different airframes.
 *
 * Edge-to-edge distance is the center distance less both rotor radii, so a
 * Chinook next to a Little Bird is measured from each one's own tip path. The
 * required clearance is the stricter of the two platforms' requirements — the
 * aircraft with the tighter tolerance doesn't get to relax the other's.
 */
export const pairSeparation = (profileA, profileB) => {
  const radiiFt = rotorRadiusFt(profileA) + rotorRadiusFt(profileB);
  const requiredClearanceFt = Math.max(tipClearanceFt(profileA), tipClearanceFt(profileB));
  return {
    radiiFt,
    requiredClearanceFt,
    // Center distance at which the tip paths are exactly the required distance apart.
    minCenterDistanceFt: radiiFt + requiredClearanceFt,
  };
};

/** Edge-to-edge (rotor tip to rotor tip) gap for a given center distance. */
export const edgeGapFt = (centerDistanceFt, profileA, profileB) =>
  centerDistanceFt - pairSeparation(profileA, profileB).radiiFt;

/** True when two aircraft at this center distance violate tip clearance. */
export const isSeparationViolation = (centerDistanceFt, profileA, profileB) => {
  const { requiredClearanceFt } = pairSeparation(profileA, profileB);
  return edgeGapFt(centerDistanceFt, profileA, profileB) < requiredClearanceFt;
};

/* --------------------------------------------------------------------------
 * Lookup
 * ----------------------------------------------------------------------- */

/**
 * Resolves an aircraft's profile from a list. Accepts a profile id or slug so
 * saved maps keep working across databases (ids differ, slugs don't).
 */
export const findProfile = (profiles, ref) => {
  if (!ref || !Array.isArray(profiles) || profiles.length === 0) return null;
  const byId = profiles.find((p) => p.id != null && String(p.id) === String(ref));
  if (byId) return byId;
  return profiles.find((p) => p.slug && p.slug === ref) || null;
};

/**
 * Profile for a placed aircraft, falling back to the mission default and then
 * to the built-in UH-60L. Never returns null, so callers can do geometry math
 * unconditionally.
 */
export const profileForAsset = (asset, profiles, defaultProfile) =>
  findProfile(profiles, asset?.profileId) ||
  defaultProfile ||
  findProfile(profiles, FALLBACK_PROFILE.slug) ||
  FALLBACK_PROFILE;

/**
 * Best profile for an airframe read out of an imported .msnx.
 *
 * Prefers an exact match on the AMPS vehicledescription (unambiguous), then
 * falls back to comparing designations with punctuation and case ignored, so
 * "UH-60L", "UH60L", and "uh 60 l" all land on the same profile. Returns null
 * when nothing matches, leaving the caller's current selection alone.
 */
export const matchProfileToAircraft = (profiles, aircraft) => {
  if (!aircraft || !Array.isArray(profiles) || profiles.length === 0) return null;

  const { description, designation } = aircraft;
  if (description) {
    const exact = profiles.find((p) => p.amps_vehicle_description === description);
    if (exact) return exact;
  }

  if (!designation) return null;
  const squash = (value) => String(value || "").replace(/[^a-z0-9]/gi, "").toLowerCase();
  const target = squash(designation);
  if (!target) return null;
  return profiles.find((p) => squash(p.designation) === target) || null;
};

/** Airspeed reference types the planner and AMPS AirspeedValue support. */
export const AIRSPEED_TYPES = [
  { value: "ground", label: "GS (Ground)", ampsName: "Ground" },
  { value: "indicated", label: "KIAS (Indicated)", ampsName: "Indicated" },
  { value: "true", label: "KTAS (True)", ampsName: "True" },
];
