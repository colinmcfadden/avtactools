import CoreModel

/// Finding the profile for an aircraft, as `aircraftProfiles.js` does it.
public enum AircraftLookup {
    /// Resolves a profile from a list by id or slug, so saved maps keep working across databases (ids differ, slugs don't). Nil for
    /// no reference or an empty list.
    public static func findProfile(_ profiles: [AircraftProfile], _ ref: String?) -> AircraftProfile? {
        guard let ref, !ref.isEmpty, !profiles.isEmpty else { return nil }
        if let byId = profiles.first(where: { $0.id != nil && same(String($0.id!), ref) }) { return byId }
        return profiles.first { !$0.slug.isEmpty && same($0.slug, ref) }
    }

    /// Profile for a placed aircraft, falling back to the mission default and then to the built-in UH-60L. Never nil, so callers can
    /// do geometry unconditionally.
    public static func profileForAsset(
        _ assetProfileRef: String?, _ profiles: [AircraftProfile], _ defaultProfile: AircraftProfile?
    ) -> AircraftProfile {
        findProfile(profiles, assetProfileRef) ?? defaultProfile ?? findProfile(profiles, AircraftProfile.fallback.slug) ?? .fallback
    }

    /// Best profile for an airframe read out of an imported `.msnx`. Prefers an exact match on the AMPS vehicle description, then
    /// compares designations with punctuation and case ignored, so "UH-60L", "UH60L" and "uh 60 l" all land on the same profile. Nil
    /// when nothing matches, leaving the caller's choice alone.
    public static func matchProfileToAircraft(
        _ profiles: [AircraftProfile], _ description: String?, _ designation: String?
    ) -> AircraftProfile? {
        if profiles.isEmpty { return nil }
        if let description, !description.isEmpty, let exact = profiles.first(where: { same($0.ampsVehicleDescription, description) }) {
            return exact
        }
        guard let designation, !designation.isEmpty else { return nil }
        let target = squash(designation)
        if target.isEmpty { return nil }
        return profiles.first { same(squash($0.designation), target) }
    }

    /// The ASCII letters and digits, lower-cased.
    private static func squash(_ value: String) -> String {
        let kept = value.unicodeScalars.filter { ("a"..."z").contains($0) || ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
        return String(kept).lowercased()
    }
}
