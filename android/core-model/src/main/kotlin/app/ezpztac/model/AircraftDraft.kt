package app.ezpztac.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * What the aircraft form holds while a profile is being made or changed (the web's `AircraftProfileModal`): the words and numbers as typed,
 * and nothing saved until [check] says they are good.
 *
 * The numbers are kept as text because that is what a person is in the middle of typing ("1." is not yet a number, and must not be turned
 * into one under their finger). [check] holds them to the server's own limits (`routes/aircraft_routes.py`, `_apply_fields`), so a profile made with
 * no signal is never one the server later refuses: a refused create would sit in the outbox for good.
 *
 * **Not like the web:** the web's `num` turns a blank or unreadable number into the form's default, silently. Here that is refused with the
 * field named, because a rotor diameter nobody typed is a footprint nobody chose.
 */
public data class AircraftDraft(
    val name: String = "",
    val designation: String = "",
    val iconKey: String = "generic",
    val rotorDiameterM: String = "16.36",
    val rotorTipClearanceM: String = "60",
    val defaultAirspeedKts: String = "100",
    val defaultAirspeedType: String = "ground",
    val maxIndicatedKts: String = "160",
    val defaultAltitudeFt: String = "50",
    val defaultAltitudeRef: String = "agl",
    val defaultFuelFlowLbHr: String = "960",
    val defaultGrossWeightLb: String = "16000",
) {
    /** The planner's centre-to-centre spacing these two numbers make (the web shows it under the footprint), or null while either is not a number. */
    public val spacingM: Double?
        get() {
            val rotor = parse(rotorDiameterM) ?: return null
            val clearance = parse(rotorTipClearanceM) ?: return null
            return rotor + clearance
        }

    /** What [check] found: the name and the document to save, or a refusal in words. */
    public sealed interface Checked {
        /** [data] is what the record carries: the settable fields (and `perf_source`), with anything in the profile being edited that the form does not show kept as it was. */
        public data class Valid(val name: String, val data: JsonObject) : Checked

        public data class Refused(val message: String) : Checked
    }

    /**
     * Whether this draft can be saved. [existing] is the document of the profile being edited, so what the form does not show (the altitude
     * limits, the server's slug and id) stays as it is; null for a new profile.
     */
    public fun check(existing: JsonObject? = null): Checked {
        val trimmedName = name.trim()
        val trimmedDesignation = designation.trim()
        if (trimmedName.isEmpty() || trimmedDesignation.isEmpty()) return Checked.Refused("Name and designation are required.")
        if (defaultAirspeedType !in AIRSPEED_TYPES) return Checked.Refused("Airspeed reference must be ground, indicated or true.")
        if (defaultAltitudeRef !in ALTITUDE_REFS) return Checked.Refused("Altitude reference must be AGL or MSL.")

        val fields = LinkedHashMap<String, JsonElement>()
        for (field in NUMBERS) {
            val typed = field.read(this)
            val value = parse(typed) ?: return Checked.Refused(if (typed.isBlank()) "${field.label} needs a number." else "${field.label} is not a number.")
            if (value < field.min || value > field.max) return Checked.Refused("${field.label} must be between ${plain(field.min)} and ${plain(field.max)}.")
            fields[field.key] = JsonPrimitive(value)
        }
        val document = LinkedHashMap<String, JsonElement>(existing.orEmpty())
        document["designation"] = JsonPrimitive(cut(trimmedDesignation, MAX_DESIGNATION))
        document["icon_key"] = JsonPrimitive(cut(iconKey.trim(), MAX_ICON_KEY).ifEmpty { "generic" })
        document.putAll(fields)
        document["default_airspeed_type"] = JsonPrimitive(defaultAirspeedType)
        document["default_altitude_ref"] = JsonPrimitive(defaultAltitudeRef)
        // What the server records for a profile a user made; the sync leaves it out of what it sends, but the app reads it before the server has.
        if (existing == null || "perf_source" !in existing) document["perf_source"] = JsonPrimitive("custom")
        return Checked.Valid(cut(trimmedName, MAX_NAME), JsonObject(document))
    }

    private class NumberField(
        val key: String,
        val label: String,
        val min: Double,
        val max: Double,
        val read: (AircraftDraft) -> String,
    )

    public companion object {
        public const val MAX_NAME: Int = 120
        public const val MAX_DESIGNATION: Int = 40
        private const val MAX_ICON_KEY = 40

        public val AIRSPEED_TYPES: List<String> = listOf("ground", "indicated", "true")
        public val ALTITUDE_REFS: List<String> = listOf("agl", "msl")

        /** The silhouettes the web draws (`ICON_KEYS`); an icon this version cannot name is drawn as the generic one. */
        public val ICON_KEYS: List<String> = listOf("uh60", "ah64", "ch47", "uh72", "mh6", "generic")

        /** The server's limits, in the order the form shows them. */
        private val NUMBERS = listOf(
            NumberField("rotor_diameter_m", "Rotor diameter", 1.0, 60.0) { it.rotorDiameterM },
            NumberField("rotor_tip_clearance_m", "Tip clearance", 0.0, 1000.0) { it.rotorTipClearanceM },
            NumberField("default_airspeed_kts", "Cruise airspeed", 1.0, 400.0) { it.defaultAirspeedKts },
            NumberField("max_indicated_kts", "Max indicated airspeed", 1.0, 400.0) { it.maxIndicatedKts },
            NumberField("default_altitude_ft", "Default altitude", -2000.0, 30000.0) { it.defaultAltitudeFt },
            NumberField("default_fuel_flow_lb_hr", "Fuel flow", 0.0, 20000.0) { it.defaultFuelFlowLbHr },
            NumberField("default_gross_weight_lb", "Gross weight", 0.0, 200000.0) { it.defaultGrossWeightLb },
        )

        private val NUMBER = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)""")

        /** A typed number: digits with an optional point and sign, and nothing else ("12 kt", "1e3" and "1,5" are not numbers here). */
        private fun parse(text: String): Double? = text.trim().takeIf { NUMBER.matches(it) }?.toDouble()

        /** At most [max] characters, counted as the server counts them (code points), so an emoji at the end is never split. */
        private fun cut(text: String, max: Int): String =
            if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

        /** A number as it is typed back into a field: no trailing ".0", no exponent, no locale. */
        internal fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

        /** The draft for a saved profile, to change it: the form's fields as it has them (a profile read with `normalize` is never missing one). */
        public fun of(profile: AircraftProfile): AircraftDraft = AircraftDraft(
            name = profile.name,
            designation = profile.designation,
            iconKey = profile.iconKey,
            rotorDiameterM = plain(profile.rotorDiameterM),
            rotorTipClearanceM = plain(profile.rotorTipClearanceM),
            defaultAirspeedKts = plain(profile.defaultAirspeedKts),
            defaultAirspeedType = profile.defaultAirspeedType,
            maxIndicatedKts = plain(profile.maxIndicatedKts),
            defaultAltitudeFt = plain(profile.defaultAltitudeFt),
            defaultAltitudeRef = profile.defaultAltitudeRef,
            defaultFuelFlowLbHr = plain(profile.defaultFuelFlowLbHr),
            defaultGrossWeightLb = plain(profile.defaultGrossWeightLb),
        )

        /** A new profile that starts as a copy of one (the web's "Copy"): its numbers, and a name that says it is a copy. */
        public fun copyOf(profile: AircraftProfile): AircraftDraft = of(profile).copy(name = "${profile.name} (copy)")
    }
}
