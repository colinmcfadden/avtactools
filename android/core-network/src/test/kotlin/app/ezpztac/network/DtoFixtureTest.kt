package app.ezpztac.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every successful response the server was recorded giving, decoded **strictly** by the type the client uses for it.
 * Strictly means a field the server sends and the type lacks is a failure here (the client itself ignores unknown
 * fields, so an installed app survives a newer server), so a type cannot quietly fall behind the server.
 */
class DtoFixtureTest {
    private val strict = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

    private val decoders: Map<String, KSerializer<*>> = mapOf(
        "config" to AppConfig.serializer(),
        "login: android" to TokenResponse.serializer(),
        "login: web, which gets no refresh token" to TokenResponse.serializer(),
        "refresh" to TokenResponse.serializer(),
        "refresh: the same request again within the grace period" to TokenResponse.serializer(),
        "me" to ApiUser.serializer(),
        "sessions" to SessionsBody.serializer(),
        "lz: create" to LzSummary.serializer(),
        "lz: create again with the same identity" to LzSummary.serializer(),
        "lz: list" to ListSerializer(LzSummary.serializer()),
        "lz: get" to LzFull.serializer(),
        "lz: update" to LzSummary.serializer(),
        "route: create" to RouteSummary.serializer(),
        "route: create again with the same identity" to RouteSummary.serializer(),
        "route: list" to ListSerializer(RouteSummary.serializer()),
        "route: get" to RouteFull.serializer(),
        "route: update" to RouteSummary.serializer(),
        "weather" to WeatherReportDto.serializer(),
        "weather: the second station is nearer" to WeatherReportDto.serializer(),
        "weather: no station and no NOTAMs" to WeatherReportDto.serializer(),
        "weather: both services down" to WeatherReportDto.serializer(),
        "weather: the NOTAM search fails" to WeatherReportDto.serializer(),
        "pointset: create" to PointSetSummary.serializer(),
        "pointset: create again with the same identity" to PointSetSummary.serializer(),
        "pointset: list" to ListSerializer(PointSetSummary.serializer()),
        "pointset: get" to PointSetFull.serializer(),
        "pointset: update" to PointSetSummary.serializer(),
        "aircraft: list with no profiles of my own" to ListSerializer(AircraftProfileDto.serializer()),
        "aircraft: create" to AircraftProfileDto.serializer(),
        "aircraft: list" to ListSerializer(AircraftProfileDto.serializer()),
        "aircraft: update" to AircraftProfileDto.serializer(),
        "register" to Accepted.serializer(),
        "register: the same address again looks the same" to Accepted.serializer(),
        "verify-email" to Done.serializer(),
        "login: right after verifying" to TokenResponse.serializer(),
        "resend-verification" to Accepted.serializer(),
        "forgot-password" to Accepted.serializer(),
        "forgot-password: an address nobody has looks the same" to Accepted.serializer(),
        "reset-password" to Done.serializer(),
        "mil: me before the gate is cleared" to ApiUser.serializer(),
        "mil/request" to Done.serializer(),
        "mil/verify" to MilVerifyBody.serializer(),
        "analyze-field" to FieldAnalysis.serializer(),
        "terrain-analysis" to TerrainAnalysis.serializer(),
        "terrain-analysis: no landing heading" to TerrainAnalysis.serializer(),
        "elevations" to ElevationsResponse.serializer(),
        "elevations: no points" to ElevationsResponse.serializer(),
        "elevations: sampling fails" to ElevationsResponse.serializer(),
        "route-winds" to WindsResponse.serializer(),
        "route-winds: no points" to WindsResponse.serializer(),
        "route-winds: no station answers" to WindsResponse.serializer(),
        "sync: changes" to ChangeFeed.serializer(),
        "sync: nothing new" to ChangeFeed.serializer(),
    )

    @TestFactory
    fun `every successful response decodes strictly`(): List<DynamicTest> =
        Recorded.all.filter { it.status in 200..202 && it.name in decoders }.map { entry ->
            DynamicTest.dynamicTest(entry.name) {
                @Suppress("UNCHECKED_CAST")
                val serializer = decoders.getValue(entry.name) as KSerializer<Any>
                val value = strict.decodeFromString(serializer, Recorded.text(entry.name))
                // ... and encodes back to at least what was read, so nothing the server sent was dropped on the way in.
                assertKeeps(entry.body!!, strict.encodeToJsonElement(serializer, value), "$")
            }
        }

    @Test
    fun `no successful response is left without a type`() {
        val uncovered = Recorded.all
            .filter { it.status in 200..202 && it.body != null && it.name !in decoders }
            .map { it.name }
            // Bodies that are a status word only, not a record.
            .filterNot { it in setOf("logout", "lz: delete", "route: delete", "pointset: delete", "aircraft: delete", "account deletion") }
        assertTrue(uncovered.isEmpty(), "recorded responses with no type decoding them: $uncovered")
    }

    @Test
    fun `an installed app survives a field a newer server adds`() {
        val withNew = Recorded.text("me").replaceFirst("{", """{"added_in_a_later_release": {"x": [1, 2]}, """)
        val user = ApiClient.JSON.decodeFromString(ApiUser.serializer(), withNew)
        assertEquals("pilot@example.com", user.email)
    }

    @Test
    fun `an entitlement is on unless the server says it is off`() {
        val user = Recorded.user()
        assertTrue(user.hasFeature("aircraft_profiles"))
        assertTrue(user.hasFeature("a_feature_added_after_this_release"))
        assertEquals(false, user.copy(features = mapOf("threats" to false)).hasFeature("threats"))
    }

    @Test
    fun `an own profile carries its identity and the master list does not`() {
        val own = strict.decodeFromString(AircraftProfileDto.serializer(), Recorded.text("aircraft: create"))
        assertTrue(own.clientUuid != null && own.revision == 1 && !own.isSystem)
    }

    /** Everything in [sent] is in [kept], with the same value. [kept] may hold more: a default the server left out. */
    private fun assertKeeps(sent: JsonElement, kept: JsonElement, path: String) {
        when (sent) {
            is JsonObject -> {
                assertTrue(kept is JsonObject, "$path: expected an object")
                sent.forEach { (key, value) ->
                    if (value is JsonNull) return@forEach                       // a null and a missing field are the same to the types
                    val there = (kept as JsonObject)[key]
                    assertTrue(there != null, "$path.$key was sent and is not kept")
                    assertKeeps(value, there!!, "$path.$key")
                }
            }
            is JsonArray -> {
                assertTrue(kept is JsonArray && kept.size == sent.size, "$path: expected ${sent.size} items")
                sent.forEachIndexed { i, item -> assertKeeps(item, (kept as JsonArray)[i], "$path[$i]") }
            }
            else -> assertEquals(sent, kept, path)
        }
    }
}
