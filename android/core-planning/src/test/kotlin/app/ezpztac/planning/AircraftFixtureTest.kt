package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/** Held to contracts/fixtures/planning/aircraft.json, which records what the web's aircraftProfiles.js answers. */
class AircraftFixtureTest {
    private val fixture = Fixtures.load("planning/aircraft.json")
    private val tolerance = fixture["tolerance"]!!.jsonPrimitive.double
    private val encoder = Json { encodeDefaults = true }

    private val byKey: Map<String, AircraftProfile> = fixture["profiles"]!!.jsonArray.associate {
        val o = it.jsonObject
        o["key"]!!.jsonPrimitive.content to AircraftProfile.normalize(o["profile"]!!.jsonObject)
    }
    private val bySlug = byKey.values.associateBy { it.slug }
    private val everyProfile = byKey.values.toList()

    private fun close(expected: Double, actual: Double) = abs(expected - actual) <= tolerance * max(1.0, abs(expected))

    @Test
    fun `the built-in profile is the web's fallback`() {
        val expected = fixture["fallbackProfile"]!!
        val differences = JsonCompare.differences(expected, encoder.encodeToJsonElement(AircraftProfile.FALLBACK), tolerance)
        assertEquals(emptyList<String>(), differences)
    }

    @Test
    fun `normalizing fills in what is missing, as the web does`() {
        fixture["normalize"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val raw = c["raw"]!!
            val actual = AircraftProfile.normalize(raw as? JsonObject)
            val differences = JsonCompare.differences(c["expected"]!!, encoder.encodeToJsonElement(actual), tolerance)
            assertEquals(emptyList<String>(), differences, "normalize($raw)")
        }
    }

    @Test
    fun `geometry matches the web`() {
        fixture["geometry"]!!.jsonArray.map { it.jsonObject }.forEach { g ->
            val key = g["key"]!!.jsonPrimitive.content
            val p = byKey.getValue(key)
            fun check(name: String, actual: Double) =
                assertTrue(close(g[name]!!.jsonPrimitive.double, actual), "$key.$name: expected ${g[name]}, got $actual")
            check("rotorRadiusM", AircraftGeometry.rotorRadiusM(p))
            check("rotorRadiusFt", AircraftGeometry.rotorRadiusFt(p))
            check("tipClearanceM", AircraftGeometry.tipClearanceM(p))
            check("tipClearanceFt", AircraftGeometry.tipClearanceFt(p))
            check("centerSpacingM", AircraftGeometry.centerSpacingM(p))
            check("centerSpacingFt", AircraftGeometry.centerSpacingFt(p))
            check("spotSizeSqFt", AircraftGeometry.spotSizeSqFt(p))
        }
    }

    @Test
    fun `capacity matches the web, including at the edges of a cell`() {
        val cases = fixture["capacity"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 50)
        cases.forEach { c ->
            // The fixture writes a non-finite area (NaN, infinity) as null.
            val area = c["areaSqFt"]!!.let { if (it is JsonNull) Double.NaN else it.jsonPrimitive.double }
            val expected = c["expected"]!!.jsonPrimitive.int
            val actual = when (c["fn"]!!.jsonPrimitive.content) {
                "calculateUH60Capacity" -> AircraftGeometry.calculateUH60Capacity(area)
                else -> AircraftGeometry.capacityForArea(area, byKey.getValue(c["profile"]!!.jsonPrimitive.content))
            }
            assertEquals(expected, actual, "$c")
        }
    }

    @Test
    fun `separation and the alert threshold match the web exactly`() {
        val cases = fixture["separation"]!!.jsonArray.map { it.jsonObject }
        // 4 profiles make 10 pairs (each with itself too), at 7 distances around the limit.
        assertEquals(70, cases.size)
        cases.forEach { c ->
            val a = bySlug.getValue(c["a"]!!.jsonPrimitive.content)
            val b = bySlug.getValue(c["b"]!!.jsonPrimitive.content)
            val d = c["centerDistanceFt"]!!.jsonPrimitive.double
            val pair = AircraftGeometry.pairSeparation(a, b)
            assertTrue(close(c["radiiFt"]!!.jsonPrimitive.double, pair.radiiFt), "radii $c")
            assertTrue(close(c["requiredClearanceFt"]!!.jsonPrimitive.double, pair.requiredClearanceFt), "clearance $c")
            assertTrue(close(c["minCenterDistanceFt"]!!.jsonPrimitive.double, pair.minCenterDistanceFt), "min centre $c")
            assertTrue(close(c["edgeGapFt"]!!.jsonPrimitive.double, AircraftGeometry.edgeGapFt(d, a, b)), "edge gap $c")
            // The alert flips a hundredth of a foot either side of the limit; no tolerance on the verdict.
            assertEquals(c["violation"]!!.jsonPrimitive.boolean, AircraftGeometry.isSeparationViolation(d, a, b), "violation $c")
        }
    }

    @Test
    fun `finding a profile by id or slug matches the web`() {
        fixture["lookups"]!!.jsonObject["findProfile"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val ref = c["ref"]!!.jsonPrimitive.contentOrNull
            assertEquals(c["expected"]!!.jsonPrimitive.contentOrNull, AircraftLookup.findProfile(everyProfile, ref)?.slug, "findProfile($ref)")
        }
    }

    @Test
    fun `a placed aircraft falls back to the mission default and then the UH-60L`() {
        fixture["lookups"]!!.jsonObject["profileForAsset"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val profiles = if (c["profiles"]!!.jsonPrimitive.content == "all") everyProfile else emptyList()
            val default = c["defaultProfile"]!!.jsonPrimitive.contentOrNull?.let { byKey.getValue(it) }
            val actual = AircraftLookup.profileForAsset(c["assetProfileId"]!!.jsonPrimitive.contentOrNull, profiles, default)
            assertEquals(c["expected"]!!.jsonPrimitive.content, actual.slug, "$c")
        }
    }

    @Test
    fun `an imported airframe is matched by description, then by designation`() {
        fixture["lookups"]!!.jsonObject["matchProfileToAircraft"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val aircraft = c["aircraft"]!!.jsonObject
            val actual = AircraftLookup.matchProfileToAircraft(
                everyProfile,
                aircraft["description"]?.jsonPrimitive?.contentOrNull,
                aircraft["designation"]?.jsonPrimitive?.contentOrNull,
            )
            assertEquals(c["expected"]!!.jsonPrimitive.contentOrNull, actual?.slug, "$c")
        }
    }
}
