package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Units
import kotlin.math.floor
import kotlin.math.max

/**
 * The geometry derived from an aircraft profile: how a rotor diameter becomes a
 * footprint, a planning cell and an LZ capacity, and when two aircraft are too
 * close. A port of `aircraftProfiles.js` and `helicopterCapacity.js`, kept in one
 * place so there is exactly one answer to "how close is too close".
 */
public object AircraftGeometry {
    private const val M2F = Units.METERS_TO_FEET

    public fun rotorRadiusM(profile: AircraftProfile): Double = profile.rotorDiameterM / 2
    public fun rotorRadiusFt(profile: AircraftProfile): Double = rotorRadiusM(profile) * M2F
    public fun tipClearanceM(profile: AircraftProfile): Double = profile.rotorTipClearanceM
    public fun tipClearanceFt(profile: AircraftProfile): Double = tipClearanceM(profile) * M2F

    /**
     * Centre-to-centre spacing for a formation of one aircraft type: a full rotor
     * diameter plus the required tip-to-tip clearance. Derived, never stored, so
     * diameter and clearance can't drift apart.
     */
    public fun centerSpacingM(profile: AircraftProfile): Double = profile.rotorDiameterM + profile.rotorTipClearanceM
    public fun centerSpacingFt(profile: AircraftProfile): Double = centerSpacingM(profile) * M2F

    /** Planning-grid cell for LZ capacity: one aircraft's square of ground. */
    public fun spotSizeSqFt(profile: AircraftProfile): Double = centerSpacingFt(profile).let { it * it }

    /**
     * Conservative square-grid capacity estimate for an area. Actual placement
     * still depends on LZ shape, obstacles and the landing plan. Zero for an area
     * that is not a positive finite number.
     */
    public fun capacityForArea(areaSqFt: Double, profile: AircraftProfile): Int {
        val spot = spotSizeSqFt(profile)
        if (!areaSqFt.isFinite() || areaSqFt <= 0 || spot <= 0) return 0
        return floor(areaSqFt / spot).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** `calculateUH60Capacity`: the same estimate for the built-in UH-60L. */
    public fun calculateUH60Capacity(areaSqFt: Double): Int = capacityForArea(areaSqFt, AircraftProfile.FALLBACK)

    /** Separation required between a *pair*, which may be different airframes. */
    public data class PairSeparation(
        val radiiFt: Double,
        val requiredClearanceFt: Double,
        /** Centre distance at which the tip paths are exactly the required distance apart. */
        val minCenterDistanceFt: Double,
    )

    /**
     * Edge-to-edge distance is the centre distance less both rotor radii, so a
     * Chinook next to a Little Bird is measured from each one's own tip path. The
     * required clearance is the stricter of the two platforms': the aircraft with
     * the tighter tolerance doesn't get to relax the other's.
     */
    public fun pairSeparation(a: AircraftProfile, b: AircraftProfile): PairSeparation {
        val radiiFt = rotorRadiusFt(a) + rotorRadiusFt(b)
        val requiredClearanceFt = max(tipClearanceFt(a), tipClearanceFt(b))
        return PairSeparation(radiiFt, requiredClearanceFt, radiiFt + requiredClearanceFt)
    }

    /** Edge-to-edge (rotor tip to rotor tip) gap for a given centre distance. */
    public fun edgeGapFt(centerDistanceFt: Double, a: AircraftProfile, b: AircraftProfile): Double =
        centerDistanceFt - pairSeparation(a, b).radiiFt

    /** True when two aircraft at this centre distance violate tip clearance. */
    public fun isSeparationViolation(centerDistanceFt: Double, a: AircraftProfile, b: AircraftProfile): Boolean =
        edgeGapFt(centerDistanceFt, a, b) < pairSeparation(a, b).requiredClearanceFt
}
