import {
  FALLBACK_PROFILE,
  METERS_TO_FEET,
  capacityForArea,
  centerSpacingM,
  edgeGapFt,
  findProfile,
  isSeparationViolation,
  matchProfileToAircraft,
  normalizeProfile,
  pairSeparation,
  profileForAsset,
  rotorRadiusFt,
  spotSizeSqFt,
} from "./aircraftProfiles";

const profile = (over) => normalizeProfile({ ...FALLBACK_PROFILE, ...over });

const UH60 = profile({ slug: "uh60l", designation: "UH-60L", rotor_diameter_m: 16.357 });
const CH47 = profile({ slug: "ch47f", designation: "CH-47F", rotor_diameter_m: 30.14 });
const MH6 = profile({ slug: "mh6m", designation: "MH-6M", rotor_diameter_m: 8.38 });

describe("geometry", () => {
  it("matches the pre-profile UH-60 constants", () => {
    // The values the app used before profiles existed; a regression here means
    // existing LZ plans would silently change capacity.
    expect(centerSpacingM(UH60)).toBeCloseTo(76.357, 3);
    expect(centerSpacingM(UH60) * METERS_TO_FEET).toBeCloseTo(250.515, 2);
    expect(spotSizeSqFt(UH60)).toBeCloseTo(250.515 ** 2, 0);
  });

  it("gives each platform its own spacing", () => {
    expect(centerSpacingM(CH47)).toBeCloseTo(90.14, 2);
    expect(centerSpacingM(MH6)).toBeCloseTo(68.38, 2);
    expect(centerSpacingM(CH47)).toBeGreaterThan(centerSpacingM(UH60));
    expect(centerSpacingM(MH6)).toBeLessThan(centerSpacingM(UH60));
  });

  it("fits fewer Chinooks than Little Birds in the same LZ", () => {
    const area = 500 * 500;
    expect(capacityForArea(area, CH47)).toBeLessThan(capacityForArea(area, MH6));
  });

  it("returns zero capacity for a nonsense area", () => {
    expect(capacityForArea(0, UH60)).toBe(0);
    expect(capacityForArea(-1, UH60)).toBe(0);
    expect(capacityForArea(NaN, UH60)).toBe(0);
  });
});

describe("pair separation", () => {
  it("measures the gap from each aircraft's own rotor", () => {
    const centers = 400;
    const gap = edgeGapFt(centers, UH60, CH47);
    expect(gap).toBeCloseTo(centers - rotorRadiusFt(UH60) - rotorRadiusFt(CH47), 5);
    // Asymmetric pair: strictly tighter than two Little Birds at the same distance.
    expect(gap).toBeLessThan(edgeGapFt(centers, MH6, MH6));
  });

  it("requires the stricter of the two platforms' clearances", () => {
    const strict = profile({ slug: "strict", rotor_tip_clearance_m: 100 });
    const loose = profile({ slug: "loose", rotor_tip_clearance_m: 30 });
    const { requiredClearanceFt } = pairSeparation(loose, strict);
    expect(requiredClearanceFt).toBeCloseTo(100 * METERS_TO_FEET, 3);
    // Order must not matter.
    expect(pairSeparation(strict, loose).requiredClearanceFt).toBeCloseTo(
      requiredClearanceFt,
      6,
    );
  });

  it("flags a violation exactly at the required center distance", () => {
    const { minCenterDistanceFt } = pairSeparation(UH60, CH47);
    expect(isSeparationViolation(minCenterDistanceFt + 1, UH60, CH47)).toBe(false);
    expect(isSeparationViolation(minCenterDistanceFt - 1, UH60, CH47)).toBe(true);
  });

  it("reproduces the old UH-60 rule for a uniform formation", () => {
    // Two UH-60s violate below 250.515 ft between centers, as before.
    const legacyMinFt = 76.357 * METERS_TO_FEET;
    expect(pairSeparation(UH60, UH60).minCenterDistanceFt).toBeCloseTo(legacyMinFt, 2);
  });
});

describe("lookup", () => {
  const list = [UH60, CH47, MH6];

  it("resolves by id or slug", () => {
    const withId = [{ ...UH60, id: 7 }];
    expect(findProfile(withId, 7).slug).toBe("uh60l");
    expect(findProfile(withId, "7").slug).toBe("uh60l");
    expect(findProfile(list, "ch47f").designation).toBe("CH-47F");
    expect(findProfile(list, "nope")).toBeNull();
  });

  it("falls back for an aircraft whose profile is gone", () => {
    const orphan = { id: 1, profileId: "deleted-airframe" };
    expect(profileForAsset(orphan, list, CH47).slug).toBe("ch47f");
    // No mission default either — still never null.
    expect(profileForAsset(orphan, [], null).slug).toBe(FALLBACK_PROFILE.slug);
  });
});

describe("matching an imported mission's airframe", () => {
  const list = [
    { ...UH60, amps_vehicle_description: "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L" },
    CH47,
  ];

  it("prefers an exact AMPS vehicledescription", () => {
    const match = matchProfileToAircraft(list, {
      description: "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L",
      designation: "UH-60L",
    });
    expect(match.slug).toBe("uh60l");
  });

  it("falls back to a punctuation-insensitive designation", () => {
    expect(matchProfileToAircraft(list, { designation: "ch47f" }).slug).toBe("ch47f");
    expect(matchProfileToAircraft(list, { designation: "CH 47 F" }).slug).toBe("ch47f");
  });

  it("returns null rather than guessing", () => {
    expect(matchProfileToAircraft(list, { designation: "AH-64E" })).toBeNull();
    expect(matchProfileToAircraft(list, null)).toBeNull();
    expect(matchProfileToAircraft([], { designation: "UH-60L" })).toBeNull();
  });
});

describe("normalizeProfile", () => {
  it("fills gaps from the built-in profile", () => {
    const sparse = normalizeProfile({ slug: "x", designation: "X" });
    expect(sparse.rotor_diameter_m).toBe(FALLBACK_PROFILE.rotor_diameter_m);
    expect(sparse.icon_key).toBe("generic");
  });

  it("survives junk numbers rather than producing NaN geometry", () => {
    const junk = normalizeProfile({ rotor_diameter_m: "abc", rotor_tip_clearance_m: null });
    expect(Number.isFinite(centerSpacingM(junk))).toBe(true);
    expect(junk.rotor_diameter_m).toBe(FALLBACK_PROFILE.rotor_diameter_m);
  });
});
