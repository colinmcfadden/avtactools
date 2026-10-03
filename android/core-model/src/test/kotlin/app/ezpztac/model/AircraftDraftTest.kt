package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The aircraft form, held to what the server takes (`contracts/fixtures/aircraft/limits.json`, probed from `_apply_fields`): a profile made with no
 * signal must be one the server will accept, because a refused create stays in the outbox for good.
 */
class AircraftDraftTest {
    private val limits = Fixtures.load("aircraft/limits.json")
    private val numbers = limits.getValue("numbers").jsonObject

    private fun draft(name: String = "MH-47G Chinook", designation: String = "MH-47G") = AircraftDraft(name = name, designation = designation)

    private fun valid(draft: AircraftDraft, existing: JsonObject? = null) = draft.check(existing) as AircraftDraft.Checked.Valid

    private fun refused(draft: AircraftDraft, existing: JsonObject? = null) = (draft.check(existing) as AircraftDraft.Checked.Refused).message

    private fun number(data: JsonObject, key: String) = data.getValue(key).jsonPrimitive.double

    /** The form's numbers, each with the setter that puts text in it. */
    private val fields: Map<String, Pair<String, (AircraftDraft, String) -> AircraftDraft>> = mapOf(
        "rotor_diameter_m" to ("Rotor diameter" to { d, t -> d.copy(rotorDiameterM = t) }),
        "rotor_tip_clearance_m" to ("Tip clearance" to { d, t -> d.copy(rotorTipClearanceM = t) }),
        "default_airspeed_kts" to ("Cruise airspeed" to { d, t -> d.copy(defaultAirspeedKts = t) }),
        "max_indicated_kts" to ("Max indicated airspeed" to { d, t -> d.copy(maxIndicatedKts = t) }),
        "default_altitude_ft" to ("Default altitude" to { d, t -> d.copy(defaultAltitudeFt = t) }),
        "default_fuel_flow_lb_hr" to ("Fuel flow" to { d, t -> d.copy(defaultFuelFlowLbHr = t) }),
        "default_gross_weight_lb" to ("Gross weight" to { d, t -> d.copy(defaultGrossWeightLb = t) }),
    )

    // -- The blank form -----------------------------------------------------------------------------------------------

    @Test
    fun `a blank form carries the web's defaults and makes a custom profile`() {
        val result = valid(draft())
        assertEquals("MH-47G Chinook", result.name)
        val data = result.data
        assertEquals("MH-47G", data.getValue("designation").jsonPrimitive.content)
        assertEquals("generic", data.getValue("icon_key").jsonPrimitive.content)
        assertEquals(16.36, number(data, "rotor_diameter_m"))
        assertEquals(60.0, number(data, "rotor_tip_clearance_m"))
        assertEquals(100.0, number(data, "default_airspeed_kts"))
        assertEquals("ground", data.getValue("default_airspeed_type").jsonPrimitive.content)
        assertEquals(160.0, number(data, "max_indicated_kts"))
        assertEquals(50.0, number(data, "default_altitude_ft"))
        assertEquals("agl", data.getValue("default_altitude_ref").jsonPrimitive.content)
        assertEquals(960.0, number(data, "default_fuel_flow_lb_hr"))
        assertEquals(16000.0, number(data, "default_gross_weight_lb"))
        assertEquals("custom", data.getValue("perf_source").jsonPrimitive.content)
    }

    @Test
    fun `a new profile is not given altitude limits, the server's own are left to apply`() {
        val data = valid(draft()).data
        assertFalse("min_altitude_ft_msl" in data)
        assertFalse("max_altitude_ft_msl" in data)
    }

    // -- Name and designation ----------------------------------------------------------------------------------------

    @Test
    fun `name and designation are both needed, and blanks do not count`() {
        assertEquals("Name and designation are required.", refused(draft(name = "")))
        assertEquals("Name and designation are required.", refused(draft(designation = "")))
        assertEquals("Name and designation are required.", refused(draft(name = "   ", designation = "MH-47G")))
        assertEquals("Name and designation are required.", refused(draft(name = "x", designation = "\t \n")))
    }

    @Test
    fun `name and designation are saved trimmed`() {
        val result = valid(draft(name = "  MH-47G Chinook ", designation = " MH-47G  "))
        assertEquals("MH-47G Chinook", result.name)
        assertEquals("MH-47G", result.data.getValue("designation").jsonPrimitive.content)
    }

    @Test
    fun `name and designation are cut where the server cuts them, by characters and not in the middle of one`() {
        val maxName = limits.getValue("maxName").jsonPrimitive.int
        val maxDesignation = limits.getValue("maxDesignation").jsonPrimitive.int
        val long = valid(draft(name = "n".repeat(maxName + 30), designation = "d".repeat(maxDesignation + 30)))
        assertEquals(maxName, long.name.length)
        assertEquals(maxDesignation, long.data.getValue("designation").jsonPrimitive.content.length)

        // An emoji is two UTF-16 units: one that straddles the limit goes whole, or not at all.
        val emoji = "🚁"
        val straddling = valid(draft(name = "n".repeat(maxName - 1) + emoji + "tail"))
        assertEquals("n".repeat(maxName - 1) + emoji, straddling.name)
        assertEquals(maxName, straddling.name.codePointCount(0, straddling.name.length))
    }

    @Test
    fun `a name at the limit is kept whole`() {
        val maxName = limits.getValue("maxName").jsonPrimitive.int
        assertEquals("n".repeat(maxName), valid(draft(name = "n".repeat(maxName))).name)
    }

    @Test
    fun `an icon is kept, and an empty one is the generic silhouette`() {
        assertEquals("ch47", valid(draft().copy(iconKey = "ch47")).data.getValue("icon_key").jsonPrimitive.content)
        assertEquals("generic", valid(draft().copy(iconKey = "  ")).data.getValue("icon_key").jsonPrimitive.content)
        val maxIcon = limits.getValue("maxIconKey").jsonPrimitive.int
        assertEquals(maxIcon, valid(draft().copy(iconKey = "i".repeat(maxIcon + 5))).data.getValue("icon_key").jsonPrimitive.content.length)
    }

    @Test
    fun `the icons offered are the silhouettes the web draws, generic last`() {
        assertEquals(listOf("uh60", "ah64", "ch47", "uh72", "mh6", "generic"), AircraftDraft.ICON_KEYS)
    }

    // -- The server's limits -----------------------------------------------------------------------------------------

    @Test
    fun `every number the form sets has a limit in the server's file, and no limit is missing from the form`() {
        val settable = numbers.keys - setOf("min_altitude_ft_msl", "max_altitude_ft_msl")      // the form does not show the altitude limits
        assertEquals(settable, fields.keys)
    }

    @Test
    fun `each number is accepted at the server's limits and refused just beyond them`() {
        for ((key, parts) in fields) {
            val (label, set) = parts
            val low = numbers.getValue(key).jsonObject.getValue("min").jsonPrimitive.double
            val high = numbers.getValue(key).jsonObject.getValue("max").jsonPrimitive.double
            val base = draft()
            assertEquals(low, number(valid(set(base, AircraftDraft.plain(low))).data, key), "$key at its minimum")
            assertEquals(high, number(valid(set(base, AircraftDraft.plain(high))).data, key), "$key at its maximum")
            for (beyond in listOf(low - 0.01, high + 0.01)) {
                val message = refused(set(base, java.math.BigDecimal.valueOf(beyond).toPlainString()))
                assertEquals("$label must be between ${AircraftDraft.plain(low)} and ${AircraftDraft.plain(high)}.", message, "$key at $beyond")
            }
        }
    }

    @Test
    fun `the airspeed references and altitude references are the server's`() {
        assertEquals(limits.getValue("airspeedTypes").jsonArray.map { it.jsonPrimitive.content }, AircraftDraft.AIRSPEED_TYPES)
        assertEquals(limits.getValue("altitudeRefs").jsonArray.map { it.jsonPrimitive.content }, AircraftDraft.ALTITUDE_REFS)
        for (type in AircraftDraft.AIRSPEED_TYPES) assertEquals(type, valid(draft().copy(defaultAirspeedType = type)).data.getValue("default_airspeed_type").jsonPrimitive.content)
        for (ref in AircraftDraft.ALTITUDE_REFS) assertEquals(ref, valid(draft().copy(defaultAltitudeRef = ref)).data.getValue("default_altitude_ref").jsonPrimitive.content)
    }

    @Test
    fun `an airspeed or altitude reference the server does not know is refused`() {
        assertEquals("Airspeed reference must be ground, indicated or true.", refused(draft().copy(defaultAirspeedType = "calibrated")))
        assertEquals("Altitude reference must be AGL or MSL.", refused(draft().copy(defaultAltitudeRef = "hat")))
    }

    // -- What counts as a number -------------------------------------------------------------------------------------

    @Test
    fun `a blank number is refused with the field named, not turned into the default`() {
        assertEquals("Rotor diameter needs a number.", refused(draft().copy(rotorDiameterM = "")))
        assertEquals("Fuel flow needs a number.", refused(draft().copy(defaultFuelFlowLbHr = "   ")))
    }

    @Test
    fun `text that is not a plain number is refused`() {
        for (text in listOf("abc", "12 kt", "1,5", "1e3", "--5", "1.2.3", ".", "+", "NaN", "Infinity", "0x10")) {
            assertEquals("Cruise airspeed is not a number.", refused(draft().copy(defaultAirspeedKts = text)), "'$text'")
        }
    }

    @Test
    fun `numbers are read as typed, with a sign, a leading or trailing point and spaces round them`() {
        assertEquals(16.5, number(valid(draft().copy(rotorDiameterM = " 16.5 ")).data, "rotor_diameter_m"))
        assertEquals(0.5, number(valid(draft().copy(rotorTipClearanceM = ".5")).data, "rotor_tip_clearance_m"))
        assertEquals(12.0, number(valid(draft().copy(rotorDiameterM = "12.")).data, "rotor_diameter_m"))
        assertEquals(-100.0, number(valid(draft().copy(defaultAltitudeFt = "-100")).data, "default_altitude_ft"))
        assertEquals(30.0, number(valid(draft().copy(defaultAirspeedKts = "+30")).data, "default_airspeed_kts"))
    }

    @Test
    fun `the first refusal is the first field in the form`() {
        val twoBad = draft().copy(rotorDiameterM = "abc", defaultGrossWeightLb = "abc")
        assertEquals("Rotor diameter is not a number.", refused(twoBad))
    }

    // -- Spacing ----------------------------------------------------------------------------------------------------

    @Test
    fun `the spacing is the rotor and its clearance, and nothing while either is not a number`() {
        assertEquals(76.36, AircraftDraft().spacingM!!, 1e-9)
        assertEquals(10.5, AircraftDraft(rotorDiameterM = "4.5", rotorTipClearanceM = "6").spacingM!!, 1e-9)
        assertNull(AircraftDraft(rotorDiameterM = "").spacingM)
        assertEquals(10.0, AircraftDraft(rotorDiameterM = "4", rotorTipClearanceM = "6.").spacingM!!, 1e-9)       // "6." is a number being typed
        assertNull(AircraftDraft(rotorTipClearanceM = "x").spacingM)
    }

    // -- Changing and copying a profile ------------------------------------------------------------------------------

    private fun existing(vararg extra: Pair<String, Any>) = JsonObject(
        buildMap {
            put("id", JsonPrimitive(41))
            put("slug", JsonPrimitive("mh-47g"))
            put("min_altitude_ft_msl", JsonPrimitive(-500.0))
            put("max_altitude_ft_msl", JsonPrimitive(15000.0))
            put("rotor_diameter_m", JsonPrimitive(18.3))
            extra.forEach { (k, v) -> put(k, if (v is String) JsonPrimitive(v) else JsonPrimitive(v as Number)) }
        },
    )

    @Test
    fun `changing a profile keeps what the form does not show and replaces what it does`() {
        val data = valid(draft().copy(rotorDiameterM = "19"), existing()).data
        assertEquals(41, data.getValue("id").jsonPrimitive.int)
        assertEquals("mh-47g", data.getValue("slug").jsonPrimitive.content)
        assertEquals(-500.0, number(data, "min_altitude_ft_msl"))
        assertEquals(15000.0, number(data, "max_altitude_ft_msl"))
        assertEquals(19.0, number(data, "rotor_diameter_m"))
    }

    @Test
    fun `a profile that came from the server keeps the source the server gave it, and one with none is custom`() {
        assertEquals("published", valid(draft(), existing("perf_source" to "published")).data.getValue("perf_source").jsonPrimitive.content)
        assertEquals("custom", valid(draft(), existing()).data.getValue("perf_source").jsonPrimitive.content)
    }

    @Test
    fun `a draft of a saved profile reads back as the same profile`() {
        val profile = AircraftProfile.FALLBACK.copy(
            name = "Mine", designation = "X-1", iconKey = "ah64", rotorDiameterM = 16.357, defaultAirspeedType = "indicated", defaultAltitudeRef = "msl",
            defaultAltitudeFt = -1500.0, defaultFuelFlowLbHr = 1234.5,
        )
        val draft = AircraftDraft.of(profile)
        assertEquals("16.357", draft.rotorDiameterM)
        assertEquals("60", draft.rotorTipClearanceM)
        assertEquals("-1500", draft.defaultAltitudeFt)
        assertEquals("1234.5", draft.defaultFuelFlowLbHr)
        val again = AircraftProfile.normalize(JsonObject(valid(draft).data + ("name" to JsonPrimitive("Mine"))))
        assertEquals("Mine", again.name)
        assertEquals("X-1", again.designation)
        assertEquals("ah64", again.iconKey)
        assertEquals(16.357, again.rotorDiameterM)
        assertEquals(60.0, again.rotorTipClearanceM)
        assertEquals("indicated", again.defaultAirspeedType)
        assertEquals("msl", again.defaultAltitudeRef)
        assertEquals(-1500.0, again.defaultAltitudeFt)
        assertEquals(1234.5, again.defaultFuelFlowLbHr)
        assertEquals("custom", again.perfSource)
    }

    @Test
    fun `numbers are written back without a trailing point zero or an exponent`() {
        assertEquals("60", AircraftDraft.plain(60.0))
        assertEquals("0", AircraftDraft.plain(0.0))
        assertEquals("200000", AircraftDraft.plain(200000.0))
        assertEquals("0.0001", AircraftDraft.plain(0.0001))
        assertEquals("-2000", AircraftDraft.plain(-2000.0))
        assertEquals("16.357", AircraftDraft.plain(16.357))
    }

    @Test
    fun `a copy is a new profile named as a copy, with the numbers of the one it came from`() {
        val master = AircraftProfile.FALLBACK.copy(name = "UH-60L Black Hawk", id = 3)
        val copy = AircraftDraft.copyOf(master)
        assertEquals("UH-60L Black Hawk (copy)", copy.name)
        assertEquals("UH-60L", copy.designation)
        assertEquals("uh60", copy.iconKey)
        assertEquals("100", copy.defaultAirspeedKts)
        assertEquals("193", copy.maxIndicatedKts)
        assertTrue(valid(copy).data.containsKey("designation"))
    }
}
