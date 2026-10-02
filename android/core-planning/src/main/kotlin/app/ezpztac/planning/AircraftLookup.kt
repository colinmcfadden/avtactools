package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile

/** Finding the profile for an aircraft, as `aircraftProfiles.js` does it. */
public object AircraftLookup {
    /**
     * Resolves a profile from a list by id or slug, so saved maps keep working
     * across databases (ids differ, slugs don't). Null for no reference or an
     * empty list.
     */
    public fun findProfile(profiles: List<AircraftProfile>, ref: String?): AircraftProfile? {
        if (ref.isNullOrEmpty() || profiles.isEmpty()) return null
        profiles.firstOrNull { it.id != null && it.id.toString() == ref }?.let { return it }
        return profiles.firstOrNull { it.slug.isNotEmpty() && it.slug == ref }
    }

    /**
     * Profile for a placed aircraft, falling back to the mission default and then
     * to the built-in UH-60L. Never null, so callers can do geometry unconditionally.
     */
    public fun profileForAsset(
        assetProfileRef: String?,
        profiles: List<AircraftProfile>,
        defaultProfile: AircraftProfile?,
    ): AircraftProfile =
        findProfile(profiles, assetProfileRef)
            ?: defaultProfile
            ?: findProfile(profiles, AircraftProfile.FALLBACK.slug)
            ?: AircraftProfile.FALLBACK

    /**
     * Best profile for an airframe read out of an imported `.msnx`. Prefers an
     * exact match on the AMPS vehicle description, then compares designations with
     * punctuation and case ignored, so "UH-60L", "UH60L" and "uh 60 l" all land on
     * the same profile. Null when nothing matches, leaving the caller's choice alone.
     */
    public fun matchProfileToAircraft(
        profiles: List<AircraftProfile>,
        description: String?,
        designation: String?,
    ): AircraftProfile? {
        if (profiles.isEmpty()) return null
        if (!description.isNullOrEmpty()) {
            profiles.firstOrNull { it.ampsVehicleDescription == description }?.let { return it }
        }
        if (designation.isNullOrEmpty()) return null
        val target = squash(designation)
        if (target.isEmpty()) return null
        return profiles.firstOrNull { squash(it.designation) == target }
    }

    private fun squash(value: String): String = value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.lowercase()
}
