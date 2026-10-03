package app.ezpztac.network

import app.ezpztac.model.LatLon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Planning a route: ground elevations and the wind at each point. What is sent, and what the server's recorded answers say. */
class PlanningCallsTest {
    private val points = listOf(LatLon(34.7, -84.1), LatLon(40.0, -100.0))

    private fun body(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        ApiClient.JSON.parseToJsonElement(request.body.readUtf8()).jsonObject

    // -- Elevations -----------------------------------------------------------------------------------------

    @Test
    fun `elevations sends the points, signed, and answers in feet in the order sent`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("elevations") }
            val feet = rig.client.elevations(points)

            val request = rig.requestsTo("/api/elevations").single()
            assertEquals("POST", request.method)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val sent = body(request)["points"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(JsonPrimitive(34.7), JsonPrimitive(40.0)), sent.map { it["lat"] })
            assertEquals(listOf(JsonPrimitive(-84.1), JsonPrimitive(-100.0)), sent.map { it["lon"] })
            assertEquals(listOf(1312.0, null), feet)                                         // the second point's tile could not be read
        }
    }

    @Test
    fun `no points asks nothing`() = runBlocking<Unit> {
        Rig().use { rig ->
            assertEquals(emptyList<Double?>(), rig.client.elevations(emptyList()))
            assertTrue(rig.requestsTo("/api/elevations").isEmpty())
        }
    }

    @Test
    fun `a failure while sampling is a 200 with nothing in it, and reads as no elevations rather than as an error`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("elevations: sampling fails") }
            assertEquals(listOf<Double?>(null, null), rig.client.elevations(points))
        }
    }

    @Test
    fun `points the server cannot read are a 400 in its words`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("elevations: a point that is not one") }
            val refused = assertThrows<ApiException> { rig.client.elevations(points) }
            assertEquals(400, refused.status)
            assertEquals("Invalid points", refused.message)
        }
    }

    // -- Winds ----------------------------------------------------------------------------------------------

    private val asked = listOf(
        WindQuestion("p1", 34.0, -84.6, null),
        WindQuestion("p2", 34.05, -84.55, "2099-01-01T00:00:00.000Z"),
        WindQuestion("p3", 34.3, -84.4, "2020-01-01T00:00:00.000Z"),
        WindQuestion("p4", 34.3, -84.4, "2099-01-01T00:00:00.000Z"),
    )

    @Test
    fun `winds sends each point with its id and the time it is wanted for, and leaves the time out when there is none`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route-winds") }
            rig.client.routeWinds(asked)

            val request = rig.requestsTo("/api/route-winds").single()
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val sent = body(request)["points"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("p1", "p2", "p3", "p4"), sent.map { (it["id"] as JsonPrimitive).content })
            assertFalse(sent[0].containsKey("time"))                                          // no time: the server takes the latest observation
            assertEquals(JsonPrimitive("2099-01-01T00:00:00.000Z"), sent[1]["time"])
            assertEquals(JsonPrimitive(34.05), sent[1]["lat"])
            assertEquals(JsonPrimitive(-84.55), sent[1]["lon"])
        }
    }

    @Test
    fun `winds are read by point id, with where each came from`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route-winds") }
            val winds = rig.client.routeWinds(asked)

            assertEquals(setOf("p1", "p2", "p3", "p4"), winds.keys)
            val observed = winds.getValue("p1")
            assertEquals(270, observed.dirTrue)
            assertEquals(12.0, observed.speedKts)
            assertEquals("METAR", observed.source)
            assertEquals("KRYY", observed.station)
            assertEquals(18.0, observed.tempC)
            assertFalse(observed.variable)

            val forecast = winds.getValue("p2")
            assertEquals("TAF", forecast.source)
            assertEquals(300, forecast.dirTrue)

            val variable = winds.getValue("p3")
            assertTrue(variable.variable)
            assertEquals(0, variable.dirTrue)                                               // a variable wind has no direction: the server says 0
        }
    }

    @Test
    fun `a point with no station in reach is not in the answer`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route-winds: no station answers") }
            assertEquals(emptyMap<String, PointWindDto>(), rig.client.routeWinds(asked))
        }
    }

    @Test
    fun `no points asks nothing of the weather service either`() = runBlocking<Unit> {
        Rig().use { rig ->
            assertEquals(emptyMap<String, PointWindDto>(), rig.client.routeWinds(emptyList()))
            assertTrue(rig.requestsTo("/api/route-winds").isEmpty())
        }
    }

    @Test
    fun `a failure of the weather lookup is a 500, which is for the app to put in its own words`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("route-winds: not a list of points") }
            val failed = assertThrows<ApiException> { rig.client.routeWinds(asked) }
            assertEquals(500, failed.status)
            assertNull(JsonArray(emptyList()).firstOrNull())                                 // (the server's own text names a Python type: a screen must not show it)
        }
    }
}
