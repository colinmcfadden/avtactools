package app.ezpztac.planning

import app.ezpztac.model.Airspeed
import app.ezpztac.model.AltitudeSetting
import app.ezpztac.model.JsNumber
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.Wind

/**
 * What the route-plan form holds while the person is typing (the web's `RoutePlanSection`, the route-wide part): the numbers as typed, and nothing applied
 * until [check] says they are good. The numbers are text because "1." is not yet a number and must not be turned into one under a finger.
 *
 * **Not like the web:** the web's `num` turns an unreadable number into 0 (or 15 for the temperature) without a word. Here it is refused with the field named,
 * because a wind speed nobody typed is a wind nobody forecast. A wind direction outside 0 to 360 is refused too, which the web's inputs only advise.
 */
public data class PlanDraft(
    /** `YYYY-MM-DD`, or blank for today. */
    val date: String = "",
    val tempC: String = "15",
    val fuelFlowLbHr: String = "960",
    val airspeedValue: String = "100",
    val airspeedType: String = Airspeed.TYPE_GROUND,
    val altitudeValue: String = "50",
    val altitudeRef: String = AltitudeSetting.REF_AGL,
    val windDir: String = "0",
    val windSpeed: String = "0",
) {
    /** What [check] found: the change to make, or a refusal in words. */
    public sealed interface Checked {
        public data class Valid(val patch: PlanPatch) : Checked

        public data class Refused(val message: String) : Checked
    }

    public fun check(): Checked = try {
        if (airspeedType !in AIRSPEED_TYPES) throw Refusal("Airspeed reference must be ground, indicated or true.")
        if (altitudeRef !in ALTITUDE_REFS) throw Refusal("Altitude reference must be AGL or MSL.")
        val day = date.trim()
        if (day.isNotEmpty() && !isDate(day)) throw Refusal("The date must be written year-month-day, like 2026-10-03.")
        Checked.Valid(
            PlanPatch(
                airspeed = Airspeed(number("Airspeed", airspeedValue, 0.0, 400.0), airspeedType),
                altitude = AltitudeSetting(number("Altitude", altitudeValue, -2000.0, 30000.0), altitudeRef),
                wind = Wind(number("Wind direction", windDir, 0.0, 360.0), number("Wind speed", windSpeed, 0.0, 200.0)),
                tempC = number("Temperature", tempC, -100.0, 100.0), fuelFlowLbHr = number("Fuel flow", fuelFlowLbHr, 0.0, 20000.0), date = day,
            ),
        )
    } catch (refusal: Refusal) {
        Checked.Refused(refusal.message.orEmpty())
    }

    internal class Refusal(message: String) : Exception(message)

    public companion object {
        public val AIRSPEED_TYPES: List<String> = listOf(Airspeed.TYPE_GROUND, Airspeed.TYPE_INDICATED, Airspeed.TYPE_TRUE)
        public val ALTITUDE_REFS: List<String> = listOf(AltitudeSetting.REF_AGL, AltitudeSetting.REF_MSL)

        private val NUMBER = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)""")
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        /** A typed number: digits with an optional point and sign, and nothing else ("12 kt", "1e3" and "1,5" are not numbers here). */
        internal fun parseNumber(text: String): Double? = text.trim().takeIf { NUMBER.matches(it) }?.toDouble()

        private fun isDate(text: String): Boolean = DATE.matches(text) && runCatching { java.time.LocalDate.parse(text) }.isSuccess

        /** [typed] as a number between [min] and [max], or a [Refusal] naming [label]. */
        internal fun number(label: String, typed: String, min: Double, max: Double): Double {
            val value = parseNumber(typed) ?: throw Refusal(if (typed.isBlank()) "$label needs a number." else "$label is not a number.")
            if (value < min || value > max) throw Refusal("$label must be between ${JsNumber.toText(min)} and ${JsNumber.toText(max)}.")
            return value
        }

        /** A number as it is typed back into a field: no trailing ".0", no exponent, no locale. */
        internal fun typed(value: Double): String = JsNumber.toText(value)

        /** The draft for a plan, to change it. A value the plan does not hold takes the default, as the web's `{...defaultRoutePlan(), ...plan}` does. */
        public fun of(plan: RoutePlan): PlanDraft {
            val wind = plan.wind ?: Wind()
            return PlanDraft(
                date = plan.date, tempC = typed(plan.tempC ?: 15.0), fuelFlowLbHr = typed(plan.fuelFlowLbHr ?: 960.0),
                airspeedValue = typed(plan.airspeed.value), airspeedType = plan.airspeed.type,
                altitudeValue = typed(plan.altitude.value), altitudeRef = plan.altitude.ref,
                windDir = typed(wind.dirTrue), windSpeed = typed(wind.speedKts),
            )
        }
    }
}
