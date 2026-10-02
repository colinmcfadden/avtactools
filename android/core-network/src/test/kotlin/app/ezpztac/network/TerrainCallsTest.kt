package app.ezpztac.network

import app.ezpztac.model.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Landing-zone analysis: what is sent, what comes back (the server's recorded answers), how a failure reads, and that it is heavy work. */
class TerrainCallsTest {
    private val target = LatLon(34.0965, -117.095)
    private val polygon = listOf(LatLon(34.098, -117.097), LatLon(34.098, -117.093), LatLon(34.094, -117.093), LatLon(34.094, -117.097))

    private fun body(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        ApiClient.JSON.parseToJsonElement(request.body.readUtf8()).jsonObject

    // -- Finding the area ---------------------------------------------------------------------------------

    @Test
    fun `finding the area sends the target, signed, and reads what the model found`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("analyze-field") }
            val found = rig.client.analyzeField(target)

            val request = rig.requestsTo("/api/analyze-field").single()
            assertEquals("POST", request.method)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val sent = body(request)                                                         // a request's body can be read once
            assertEquals(JsonPrimitive(34.0965), sent["lat"])
            assertEquals(JsonPrimitive(-117.095), sent["lon"])

            assertEquals("4050", found.elevation)                                           // whole feet, as a string
            assertEquals(4, found.suggestedLz.size)
            assertTrue(found.suggestedLz.all { it.size == 2 })                                // [lat, lon]
            assertEquals(34.100859688544034, found.suggestedLz.first()[0], 0.0)
            assertEquals(-117.10567474365234, found.suggestedLz.first()[1], 0.0)
        }
    }

    @Test
    fun `no area at the point is a 400 in the server's words, and a failure of the service is a 500`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("analyze-field: no distinct area at the point") }
            val none = assertThrows<ApiException> { rig.client.analyzeField(target) }
            assertEquals(400, none.status)
            assertEquals("No distinct field found at this point", none.message)

            rig.serve { Recorded.mock("analyze-field: the map tile is unavailable") }
            val down = assertThrows<ApiException> { rig.client.analyzeField(target) }
            assertEquals(500, down.status)
            assertEquals("Map data unavailable", down.message)
        }
    }

    // -- Slope --------------------------------------------------------------------------------------------

    @Test
    fun `slope sends the polygon as lat-lon pairs and the heading when there is one`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("terrain-analysis") }
            rig.client.terrainAnalysis(polygon, landingHeadingDeg = 270.0)
            val sent = body(rig.requestsTo("/api/terrain-analysis").single())
            assertEquals(4, sent.getValue("polygon").jsonArray.size)
            val first = sent.getValue("polygon").jsonArray[0].jsonArray
            assertEquals(listOf(34.098, -117.097), first.map { it.jsonPrimitive.doubleOrNull })   // latitude first, as the server reads it
            assertEquals(270.0, sent.getValue("landingHeading").jsonPrimitive.doubleOrNull)
        }
    }

    @Test
    fun `with no heading none is sent, rather than a zero that would mean north`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("terrain-analysis: no landing heading") }
            val result = rig.client.terrainAnalysis(polygon)
            assertFalse("landingHeading" in body(rig.requestsTo("/api/terrain-analysis").single()))
            assertNull(result.directional)
        }
    }

    @Test
    fun `the analysis carries the raster, where it goes, which source gave it and the numbers`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("terrain-analysis") }
            val result = rig.client.terrainAnalysis(polygon, 270.0)
            assertTrue(result.overlay.startsWith("data:image/png;base64,"))
            assertEquals(listOf(34.09364066565814, -117.09743932180162), result.bounds[0])        // [south, west] first
            assertEquals(listOf(34.09836305409083, -117.09260684807441), result.bounds[1])        // then [north, east]
            assertEquals("local_highres_cog", result.source)
            assertEquals("270.0", result.directional!!.headingDeg.toString())
            assertEquals(listOf(3.0, 6.0, 10.0, 15.0), result.thresholds.bands)
            assertEquals(Uh60Limits(noseHigh = 6.0, noseLow = 15.0, crossSlope = 15.0), result.thresholds.uh60)
            assertTrue(result.stats.sampleCount > 0)
        }
    }

    @Test
    fun `the server's refusals of a slope request come through with their status`() = runBlocking<Unit> {
        Rig().use { rig ->
            val cases = listOf(
                "terrain-analysis: too few points" to 400,
                "terrain-analysis: a heading that is not a number" to 400,
                "terrain-analysis: no terrain source covers the polygon" to 502,
            )
            for ((name, status) in cases) {
                rig.serve { Recorded.mock(name) }
                val e = assertThrows<ApiException>(name) { rig.client.terrainAnalysis(polygon) }
                assertEquals(status, e.status, name)
                assertEquals((Recorded[name].body as JsonObject).getValue("error").jsonPrimitive.content, e.message, name)
            }
        }
    }

    // -- Heavy work ---------------------------------------------------------------------------------------

    @Test
    fun `an analysis in flight holds the gate, so background calls wait for it`() = runBlocking<Unit> {
        Rig().use { rig ->
            val release = CountDownLatch(1)
            val arrived = CompletableDeferred<Unit>()
            rig.serve { request ->
                if (request.requestUrl!!.encodedPath == "/api/analyze-field") {
                    arrived.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                    Recorded.mock("analyze-field")
                } else Recorded.mock("sync: nothing new")
            }
            val analysis = async(Dispatchers.IO) { rig.client.analyzeField(target) }
            withTimeout(5_000) { arrived.await() }
            assertTrue(rig.priority.isBusy)                                                  // the server is working for us

            val pull = async(Dispatchers.IO) { rig.client.changes(since = 999) }
            Thread.sleep(300)
            assertTrue(rig.requestsTo("/api/sync/changes").isEmpty(), "the pull must not reach the server while the analysis runs")

            release.countDown()
            analysis.await()
            withTimeout(5_000) { pull.await() }
            assertEquals(1, rig.requestsTo("/api/sync/changes").size)
            assertFalse(rig.priority.isBusy)
        }
    }

    @Test
    fun `an analysis outlasts the ordinary read timeout`() = runBlocking<Unit> {
        // Everything else gives up after the client's read timeout (1 s here); SAM, one at a time on a busy server, is given 190 s.
        Rig(readTimeoutSeconds = 1).use { rig ->
            rig.serve { Recorded.mock("analyze-field").setBodyDelay(2, TimeUnit.SECONDS) }
            assertEquals("4050", rig.client.analyzeField(target).elevation)
            assertThrows<NetworkException> { rig.client.changes() }.also { assertTrue(it.requestMayHaveBeenSent) }
        }
    }

    @Test
    fun `giving up on an analysis releases the gate and abandons the request`() = runBlocking<Unit> {
        Rig().use { rig ->
            val release = CountDownLatch(1)
            val arrived = CompletableDeferred<Unit>()
            rig.serve {
                arrived.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
                Recorded.mock("analyze-field")
            }
            val job = launch(Dispatchers.IO) { rig.client.analyzeField(target) }
            withTimeout(5_000) { arrived.await() }
            assertTrue(rig.priority.isBusy)
            job.cancelAndJoin()                                                              // the person pressed Stop
            assertTrue(job.isCancelled)
            assertFalse(rig.priority.isBusy)                                                 // background work is let through again
            release.countDown()
        }
    }

    @Test
    fun `slope is heavy too`() = runBlocking<Unit> {
        Rig().use { rig ->
            val release = CountDownLatch(1)
            val arrived = CompletableDeferred<Unit>()
            rig.serve {
                arrived.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
                Recorded.mock("terrain-analysis")
            }
            val slope = async(Dispatchers.IO) { rig.client.terrainAnalysis(polygon) }
            withTimeout(5_000) { arrived.await() }
            assertTrue(rig.priority.isBusy)
            release.countDown()
            assertNotNull(slope.await())
            assertFalse(rig.priority.isBusy)
        }
    }

    @Test
    fun `a polygon is written exactly, a latitude and a longitude, nothing swapped or rounded`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("terrain-analysis") }
            rig.client.terrainAnalysis(listOf(LatLon(-33.123456789, 151.987654321), LatLon(1.0, 2.0), LatLon(3.0, 4.0)))
            val pairs = body(rig.requestsTo("/api/terrain-analysis").single()).getValue("polygon") as JsonArray
            assertEquals(JsonPrimitive(-33.123456789), pairs[0].jsonArray[0])
            assertEquals(JsonPrimitive(151.987654321), pairs[0].jsonArray[1])
        }
    }
}
