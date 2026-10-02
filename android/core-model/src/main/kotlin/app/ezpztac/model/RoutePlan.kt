package app.ezpztac.model

import kotlinx.serialization.Serializable

/*
 * Route planning settings and results, in the shape the web saves them
 * (`route_data.plan` and the output of `computeRoutePlan`). Field names are the
 * web's, camelCase, so a plan made on a tablet opens on the web and back.
 *
 * `type` and `ref` stay text rather than enums so a value a newer web release
 * adds survives a round trip; the planner reads them with the same fall-through
 * the web uses (anything that is not "ground" or "indicated" is true airspeed).
 */

/** Airspeed reference: `ground`, `indicated` or `true`. */
@Serializable
public data class Airspeed(val value: Double, val type: String = TYPE_GROUND) {
    public companion object {
        public const val TYPE_GROUND: String = "ground"
        public const val TYPE_INDICATED: String = "indicated"
        public const val TYPE_TRUE: String = "true"
    }
}

/** Altitude reference: `agl` or `msl`. */
@Serializable
public data class AltitudeSetting(val value: Double, val ref: String = REF_AGL) {
    public companion object {
        public const val REF_AGL: String = "agl"
        public const val REF_MSL: String = "msl"
    }
}

/** Wind as it is reported: the direction it blows FROM, in degrees true. */
@Serializable
public data class Wind(val dirTrue: Double = 0.0, val speedKts: Double = 0.0)

/**
 * Values that apply to the leg *arriving at* one point. Plan values mean "to
 * this point", which is how AMPS stores them. At most one point carries a
 * [clock]; it anchors every other point's clock time.
 */
@Serializable
public data class PointOverride(
    val altitude: AltitudeSetting? = null,
    val airspeed: Airspeed? = null,
    val wind: Wind? = null,
    /** `HH:MM` or `HH:MM:SS`. */
    val clock: String? = null,
)

/** Plan settings; defaults are the web's `defaultRoutePlan()` for the UH-60L. */
@Serializable
public data class RoutePlan(
    val aircraft: String = "UH-60L Black Hawk",
    /** The profile's slug, not its id, so a saved route survives a database change. */
    val aircraftProfile: String = "uh60l",
    val airspeed: Airspeed = Airspeed(100.0, Airspeed.TYPE_GROUND),
    val altitude: AltitudeSetting = AltitudeSetting(50.0, AltitudeSetting.REF_AGL),
    val wind: Wind? = Wind(),
    val tempC: Double? = 15.0,
    val fuelFlowLbHr: Double? = 960.0,
    /** `YYYY-MM-DD`, the day every clock time falls on; empty means today. */
    val date: String = "",
    val perPoint: Map<String, PointOverride> = emptyMap(),
) {
    public companion object {
        /** `defaultRoutePlan(profile)`: a fresh plan seeded from an aircraft profile. */
        public fun forAircraft(profile: AircraftProfile = AircraftProfile.FALLBACK): RoutePlan = RoutePlan(
            aircraft = profile.name,
            aircraftProfile = profile.slug,
            airspeed = Airspeed(profile.defaultAirspeedKts, profile.defaultAirspeedType),
            altitude = AltitudeSetting(profile.defaultAltitudeFt, profile.defaultAltitudeRef),
            wind = Wind(0.0, 0.0),
            tempC = 15.0,
            fuelFlowLbHr = profile.defaultFuelFlowLbHr,
            date = "",
            perPoint = emptyMap(),
        )
    }
}

/** A point on a sketched route. Shaping points only bend the leg between route points. */
@Serializable
public data class RoutePoint(
    /** The AMPS point id. A point without one cannot hold a per-point override, so it is left out of the plan. */
    val id: String? = null,
    val uiId: String? = null,
    val lat: Double,
    val lon: Double,
    val kind: String? = null,
    val ptType: String? = null,
    val name: String? = null,
    /** Charted elevation (a snapped local point); authoritative over the DEM. */
    val chartElevationFt: Double? = null,
) {
    public companion object {
        public const val KIND_SHAPING: String = "shaping"
    }
}

@Serializable
public data class PlanLeg(
    val fromId: String?,
    val toId: String?,
    val fromName: String,
    val toName: String,
    val distNm: Double,
    val courseTrueDeg: Double,
    val airspeed: Airspeed,
    val wind: Wind,
    val tasKts: Double?,
    val gsKts: Double?,
    val windCorrectionDeg: Double,
    val timeSec: Double?,
    val fuelLb: Double?,
)

@Serializable
public data class PlanTotals(val distNm: Double, val timeSec: Double?, val fuelLb: Double?)

/** One AMPS point in the computed plan, with the leg that arrives at it. */
@Serializable
public data class PlanPoint(
    val id: String?,
    val uiId: String,
    val name: String?,
    val ptType: String?,
    val lat: Double,
    val lon: Double,
    val ref: String,
    val value: Double,
    val mslFt: Double?,
    val aglFt: Double?,
    val groundFt: Double?,
    val airspeed: Airspeed?,
    val wind: Wind?,
    val legDistNm: Double?,
    val legCourseTrueDeg: Double?,
    val legGsKts: Double?,
    val legTimeSec: Double?,
    val legFuelLb: Double?,
    /** Seconds since the first point; null once any earlier leg has no ground speed. */
    val elapsedSec: Double?,
    /** Time of day at this point, `HH:MM:SS`, when a point carries a clock. */
    val clock: String?,
    val hasClock: Boolean,
    val isTotAnchor: Boolean,
)

@Serializable
public data class PlanResult(
    val points: List<PlanPoint>,
    val legs: List<PlanLeg>,
    val totals: PlanTotals?,
    val warnings: List<String>,
)
