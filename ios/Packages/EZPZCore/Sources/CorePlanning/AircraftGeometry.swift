import CoreModel
import Foundation

/// The geometry derived from an aircraft profile: how a rotor diameter becomes a footprint, a planning cell and an LZ capacity, and
/// when two aircraft are too close. A port of `aircraftProfiles.js` and `helicopterCapacity.js`, kept in one place so there is
/// exactly one answer to "how close is too close".
public enum AircraftGeometry {
    private static let m2f = Units.metersToFeet

    public static func rotorRadiusM(_ profile: AircraftProfile) -> Double { profile.rotorDiameterM / 2 }
    public static func rotorRadiusFt(_ profile: AircraftProfile) -> Double { rotorRadiusM(profile) * m2f }
    public static func tipClearanceM(_ profile: AircraftProfile) -> Double { profile.rotorTipClearanceM }
    public static func tipClearanceFt(_ profile: AircraftProfile) -> Double { tipClearanceM(profile) * m2f }

    /// Centre-to-centre spacing for a formation of one aircraft type: a full rotor diameter plus the required tip-to-tip clearance.
    /// Derived, never stored, so diameter and clearance can't drift apart.
    public static func centerSpacingM(_ profile: AircraftProfile) -> Double { profile.rotorDiameterM + profile.rotorTipClearanceM }
    public static func centerSpacingFt(_ profile: AircraftProfile) -> Double { centerSpacingM(profile) * m2f }

    /// Planning-grid cell for LZ capacity: one aircraft's square of ground.
    public static func spotSizeSqFt(_ profile: AircraftProfile) -> Double {
        let side = centerSpacingFt(profile)
        return side * side
    }

    /// Conservative square-grid capacity estimate for an area. Actual placement still depends on LZ shape, obstacles and the landing
    /// plan. Zero for an area that is not a positive finite number.
    public static func capacityForArea(_ areaSqFt: Double, _ profile: AircraftProfile) -> Int {
        let spot = spotSizeSqFt(profile)
        if !areaSqFt.isFinite || areaSqFt <= 0 || spot <= 0 { return 0 }
        // `coerceAtMost(Int.MAX_VALUE)`: Kotlin's Int is 32 bits, and a spot of no size would otherwise fit an infinity of aircraft.
        return kotlinInt(min(floor(areaSqFt / spot), Double(Int32.max)))
    }

    /// `calculateUH60Capacity`: the same estimate for the built-in UH-60L.
    public static func calculateUH60Capacity(_ areaSqFt: Double) -> Int { capacityForArea(areaSqFt, .fallback) }

    /// Separation required between a *pair*, which may be different airframes.
    public struct PairSeparation: Hashable, Sendable {
        public var radiiFt: Double
        public var requiredClearanceFt: Double
        /// Centre distance at which the tip paths are exactly the required distance apart.
        public var minCenterDistanceFt: Double

        public init(radiiFt: Double, requiredClearanceFt: Double, minCenterDistanceFt: Double) {
            self.radiiFt = radiiFt
            self.requiredClearanceFt = requiredClearanceFt
            self.minCenterDistanceFt = minCenterDistanceFt
        }
    }

    /// Edge-to-edge distance is the centre distance less both rotor radii, so a Chinook next to a Little Bird is measured from each
    /// one's own tip path. The required clearance is the stricter of the two platforms': the aircraft with the tighter tolerance
    /// doesn't get to relax the other's.
    public static func pairSeparation(_ a: AircraftProfile, _ b: AircraftProfile) -> PairSeparation {
        let radiiFt = rotorRadiusFt(a) + rotorRadiusFt(b)
        let requiredClearanceFt = javaMax(tipClearanceFt(a), tipClearanceFt(b))
        return PairSeparation(
            radiiFt: radiiFt, requiredClearanceFt: requiredClearanceFt, minCenterDistanceFt: radiiFt + requiredClearanceFt)
    }

    /// Edge-to-edge (rotor tip to rotor tip) gap for a given centre distance.
    public static func edgeGapFt(_ centerDistanceFt: Double, _ a: AircraftProfile, _ b: AircraftProfile) -> Double {
        centerDistanceFt - pairSeparation(a, b).radiiFt
    }

    /// True when two aircraft at this centre distance violate tip clearance.
    public static func isSeparationViolation(_ centerDistanceFt: Double, _ a: AircraftProfile, _ b: AircraftProfile) -> Bool {
        edgeGapFt(centerDistanceFt, a, b) < pairSeparation(a, b).requiredClearanceFt
    }
}
