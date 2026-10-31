package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.Wind

/**
 * What a route point's planning row holds while the person is typing (the web's per-point row in `RoutePlanSection`): the altitude, speed and wind flown *to*
 * this point (the leg that arrives at it) and the clock time to be there, as typed. What shows is the value in force, the point's own override or else the route's;
 * what is *applied* is only what the person changed ([check]), so looking at a point and pressing Apply does not turn the route's values into the point's own.
 *
 * The first point has no arriving leg, so it has no speed or wind: only its altitude (the start altitude) and a clock.
 */
public data class PointDraft(
    val altitudeValue: String,
    val altitudeRef: String,
    val speedValue: String,
    val speedType: String,
    val windDir: String,
    val windSpeed: String,
    /** The time of day to be at this point (the TOT), `HH:MM` or `HH:MM:SS`; blank for none. */
    val clock: String = "",
) {
    /**
     * What [check] found: the override to merge into the point's, and, when the clock was changed, what it was changed to (null when it was not; blank clears it).
     */
    public sealed interface Checked {
        public data class Valid(val patch: OverridePatch, val clock: String?) : Checked {
            /** Nothing was changed. */
            val isEmpty: Boolean get() = patch.altitude == null && patch.airspeed == null && patch.wind == null && clock == null
        }

        public data class Refused(val message: String) : Checked
    }

    /**
     * Checks what was typed against [before] (what the row showed when it was opened). Only a group that was changed is part of the patch: the altitude and its
     * reference together, the speed and its reference together, the direction and speed of the wind together, as the web writes them. [first] leaves the speed and wind out.
     */
    public fun check(before: PointDraft, first: Boolean): Checked = try {
        val altitude = if (altitudeValue == before.altitudeValue && altitudeRef == before.altitudeRef) null else {
            if (altitudeRef !in PlanDraft.ALTITUDE_REFS) throw PlanDraft.Refusal("Altitude reference must be AGL or MSL.")
            AltitudeSetting(PlanDraft.number("Altitude", altitudeValue, -2000.0, 30000.0), altitudeRef)
        }
        val speed = if (first || (speedValue == before.speedValue && speedType == before.speedType)) null else {
            if (speedType !in PlanDraft.AIRSPEED_TYPES) throw PlanDraft.Refusal("Airspeed reference must be ground, indicated or true.")
            Airspeed(PlanDraft.number("Airspeed", speedValue, 0.0, 400.0), speedType)
        }
        val wind = if (first || (windDir == before.windDir && windSpeed == before.windSpeed)) null else {
            Wind(PlanDraft.number("Wind direction", windDir, 0.0, 360.0), PlanDraft.number("Wind speed", windSpeed, 0.0, 200.0))
        }
        val time = clock.trim()
        if (time.isNotEmpty() && !CLOCK.matches(time)) throw PlanDraft.Refusal("The time must be written hours:minutes, like 09:15 or 09:15:30.")
        Checked.Valid(OverridePatch(altitude, speed, wind), clock = if (time == before.clock.trim()) null else time)
    } catch (refusal: PlanDraft.Refusal) {
        Checked.Refused(refusal.message.orEmpty())
    }

    public companion object {
        private val CLOCK = Regex("""(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?""")

        /** The row for the point [pointId] of a route planned as [plan]: the values in force there. */
        public fun of(plan: RoutePlan, pointId: String): PointDraft {
            val own = plan.perPoint[pointId]
            val altitude = own?.altitude ?: plan.altitude
            val speed = own?.airspeed ?: plan.airspeed
            val wind = own?.wind ?: plan.wind ?: Wind()
            return PointDraft(
                altitudeValue = PlanDraft.typed(altitude.value), altitudeRef = altitude.ref,
                speedValue = PlanDraft.typed(speed.value), speedType = speed.type,
                windDir = PlanDraft.typed(wind.dirTrue), windSpeed = PlanDraft.typed(wind.speedKts),
                clock = own?.clock.orEmpty(),
            )
        }
    }
}
