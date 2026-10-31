package app.ezpztac.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * An airframe's footprint and planning defaults, as `/api/aircraft-profiles`
 * sends it (snake_case, which is why the names are mapped).
 *
 * Defaults are the built-in UH-60L (`FALLBACK_PROFILE` on the web), used until
 * the API answers and whenever a saved map names a profile that no longer
 * exists, so an unresolved profile behaves like the original hard-coded build.
 */
@Serializable
public data class AircraftProfile(
    val id: Long? = null,
    val slug: String = "uh60l",
    val name: String = "UH-60L Black Hawk",
    val designation: String = "UH-60L",
    @SerialName("icon_key") val iconKey: String = "uh60",
    @SerialName("is_system") val isSystem: Boolean = true,
    @SerialName("rotor_diameter_m") val rotorDiameterM: Double = 16.357,
    @SerialName("rotor_tip_clearance_m") val rotorTipClearanceM: Double = 60.0,
    @SerialName("default_airspeed_kts") val defaultAirspeedKts: Double = 100.0,
    @SerialName("default_airspeed_type") val defaultAirspeedType: String = "ground",
    @SerialName("max_indicated_kts") val maxIndicatedKts: Double = 193.0,
    @SerialName("default_altitude_ft") val defaultAltitudeFt: Double = 50.0,
    @SerialName("default_altitude_ref") val defaultAltitudeRef: String = "agl",
    @SerialName("min_altitude_ft_msl") val minAltitudeFtMsl: Double = -2000.0,
    @SerialName("max_altitude_ft_msl") val maxAltitudeFtMsl: Double = 20000.0,
    @SerialName("default_fuel_flow_lb_hr") val defaultFuelFlowLbHr: Double = 960.0,
    @SerialName("default_gross_weight_lb") val defaultGrossWeightLb: Double = 16000.0,
    /** `vidx` (from a real AMPS install), `published` (spec sheet, unverified) or `custom`. */
    @SerialName("perf_source") val perfSource: String = "vidx",
    @SerialName("amps_vehicle_description") val ampsVehicleDescription: String? =
        "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L",
    @SerialName("has_template") val hasTemplate: Boolean = false,
    @SerialName("template_kind") val templateKind: String? = null,
) {
    public companion object {
        /** The built-in UH-60L. */
        public val FALLBACK: AircraftProfile = AircraftProfile()

        /**
         * `normalizeProfile` from the web: fills in whatever [raw] leaves out so
         * callers never guard for missing values. No profile at all is the
         * UH-60L; a profile with no `icon_key` gets the generic silhouette.
         */
        public fun normalize(raw: JsonObject?): AircraftProfile {
            if (raw == null) return FALLBACK
            val f = FALLBACK
            fun number(key: String, fallback: Double) = JsNumber.finiteOr(raw[key], fallback)
            fun text(key: String, fallback: String): String =
                (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallback
            fun optionalText(key: String, fallback: String?): String? = when (val v = raw[key]) {
                null -> fallback
                is JsonPrimitive -> if (v.isString) v.content else null
                else -> fallback
            }
            return AircraftProfile(
                id = (raw["id"] as? JsonPrimitive)?.content?.toLongOrNull(),
                slug = text("slug", f.slug),
                name = text("name", f.name),
                designation = text("designation", f.designation),
                // `raw.icon_key || "generic"`: a profile that arrives without one is generic, not a Black Hawk.
                iconKey = text("icon_key", "").ifEmpty { "generic" },
                isSystem = (raw["is_system"] as? JsonPrimitive)?.booleanOrNull ?: f.isSystem,
                rotorDiameterM = number("rotor_diameter_m", f.rotorDiameterM),
                rotorTipClearanceM = number("rotor_tip_clearance_m", f.rotorTipClearanceM),
                defaultAirspeedKts = number("default_airspeed_kts", f.defaultAirspeedKts),
                defaultAirspeedType = text("default_airspeed_type", f.defaultAirspeedType),
                maxIndicatedKts = number("max_indicated_kts", f.maxIndicatedKts),
                defaultAltitudeFt = number("default_altitude_ft", f.defaultAltitudeFt),
                defaultAltitudeRef = text("default_altitude_ref", f.defaultAltitudeRef),
                minAltitudeFtMsl = number("min_altitude_ft_msl", f.minAltitudeFtMsl),
                maxAltitudeFtMsl = number("max_altitude_ft_msl", f.maxAltitudeFtMsl),
                defaultFuelFlowLbHr = number("default_fuel_flow_lb_hr", f.defaultFuelFlowLbHr),
                defaultGrossWeightLb = number("default_gross_weight_lb", f.defaultGrossWeightLb),
                perfSource = text("perf_source", f.perfSource),
                ampsVehicleDescription = optionalText("amps_vehicle_description", f.ampsVehicleDescription),
                hasTemplate = (raw["has_template"] as? JsonPrimitive)?.booleanOrNull ?: f.hasTemplate,
                templateKind = optionalText("template_kind", f.templateKind),
            )
        }
    }
}
