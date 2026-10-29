package app.ezpztac.network

import app.ezpztac.model.LatLon
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

/** The 3D view's calls against the server's recorded answers: what is sent (coordinates only in a body), what is read, and what is refused unsent. */
class Viewer3dCallsTest {
    private val here = LatLon(34.591552, -84.128225)
    private val key = "029a9f7de3bb64d6"                    // the recorded tileset's

    private fun body(request: RecordedRequest): JsonObject = ApiClient.JSON.parseToJsonElement(request.body.readUtf8()).jsonObject

    private fun noPlaceIn(request: RecordedRequest) {
        val url = request.requestUrl.toString()
        assertFalse("34.59" in url || "84.12" in url, "a place in a URL: $url")
    }

    @Test
    fun `a lookup sends the place in its body and reads where the point cloud is`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lidar: resolve, built") }
            val found = rig.client.lidarResolve(here)
            val request = rig.requestsTo("/api/lidar/resolve").single()
            assertEquals("POST", request.method)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals(mapOf("lat" to JsonPrimitive(here.lat), "lon" to JsonPrimitive(here.lon)), body(request))
            noPlaceIn(request)
            assertTrue(found.available)
            assertEquals("/lidar/tilesets/$key/tileset.json", found.url)
            assertEquals("/lidar/tilesets/$key/context/tileset.json", found.contextUrl)

            rig.serve { Recorded.mock("lidar: resolve, nothing built here") }
            val missing = rig.client.lidarResolve(here, radiusM = 500.0)
            assertEquals(JsonPrimitive(500.0), body(rig.requestsTo("/api/lidar/resolve").last())["radius_m"])
            assertFalse(missing.available)
            assertEquals(true, missing.canBuild)
            assertEquals(LidarTargetDto(here.lat, here.lon, 500.0), missing.target)
            assertNull(missing.url)
        }
    }

    @Test
    fun `a lookup asks for a stream only when told, and reads the streamed survey and where to look`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lidar: resolve, streamed") }
            val streamed = rig.client.lidarResolve(here, stream = true)
            val request = rig.requestsTo("/api/lidar/resolve").single()
            assertEquals(JsonPrimitive(true), body(request)["stream"])
            noPlaceIn(request)
            assertEquals(true, streamed.streamed)
            val frame = streamed.frame!!
            assertEquals(here.lat, frame.lat, 0.0)
            assertEquals(here.lon, frame.lon, 0.0)
            assertNotNull(frame.heightM)

            rig.serve { Recorded.mock("lidar: resolve, streamed but not asked for") }
            val asBefore = rig.client.lidarResolve(here)
            assertFalse("stream" in body(rig.requestsTo("/api/lidar/resolve").last()))
            assertNull(asBefore.streamed)
            assertNull(asBefore.frame)
        }
    }

    @Test
    fun `a build says who waits and whether to keep it, and reads the job or the tileset`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lidar: build") }
            val job = rig.client.lidarBuild(here, keep = false, watcher = "android-abc12345")
            val sent = body(rig.requestsTo("/api/lidar/build").single())
            assertEquals(JsonPrimitive(false), sent["keep"])
            assertEquals(JsonPrimitive("android-abc12345"), sent["watcher"])
            assertEquals("queued", job.state)
            assertEquals("waiting", job.stage)
            assertEquals(0, job.position)

            rig.serve { Recorded.mock("lidar: build, built already") }
            val built = rig.client.lidarBuild(here, keep = true, watcher = null)
            val kept = body(rig.requestsTo("/api/lidar/build").last())
            assertEquals(JsonPrimitive(true), kept["keep"])
            assertFalse("watcher" in kept)                                                     // a save's build: nobody waits on it
            assertEquals("done", built.state)
            assertEquals("/lidar/tilesets/$key/tileset.json", built.url)
        }
    }

    @Test
    fun `progress is asked for by key, with the watcher and keep in the query and no place anywhere in the URL`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lidar: a build under way") }
            val job = rig.client.lidarBuildStatus(key, watcher = "android-abc12345", keep = true)
            val request = rig.requestsTo("/api/lidar/build/$key").single()
            assertEquals("GET", request.method)
            assertEquals("android-abc12345", request.requestUrl!!.queryParameter("watcher"))
            assertEquals("1", request.requestUrl!!.queryParameter("keep"))
            noPlaceIn(request)
            assertEquals("running", job.state)
            assertEquals("processing points", job.stage)
            assertEquals(42, job.elapsedS)

            rig.serve { Recorded.mock("lidar: progress, a build the service does not know") }
            val forgotten = assertThrows<ApiException> { rig.client.lidarBuildStatus(key, watcher = null, keep = false) }
            assertEquals(404, forgotten.status)
            assertEquals("no_build", forgotten.code)
            assertNull(rig.requestsTo("/api/lidar/build/$key").last().requestUrl!!.queryParameter("keep"))
        }
    }

    @Test
    fun `letting a build go is a DELETE by key, and a build the service never had is nothing to read`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("lidar: release, a build the service never had") }
            rig.client.lidarRelease(key, watcher = "android-abc12345")
            val request = rig.requestsTo("/api/lidar/build/$key").single()
            assertEquals("DELETE", request.method)
            assertEquals("android-abc12345", request.requestUrl!!.queryParameter("watcher"))
        }
    }

    @Test
    fun `the service's refusals keep their codes`() = runBlocking<Unit> {
        Rig().use { rig ->
            // A full queue is the transport's 429, as every 429 is: the build's own word for it is not needed to say so.
            rig.serve { Recorded.mock("lidar: build, the queue full") }
            assertThrows<RateLimitedException> { rig.client.lidarBuild(here, keep = false, watcher = null) }
            for ((name, status, code) in listOf(
                Triple("lidar: build, the service down", 503, "builder_unavailable"),
                Triple("lidar: build, on a server that cannot build", 503, "builder_not_configured"),
            )) {
                rig.serve { Recorded.mock(name) }
                val refused = assertThrows<ApiException> { rig.client.lidarBuild(here, keep = false, watcher = null) }
                assertEquals(status, refused.status, name)
                assertEquals(code, refused.code, name)
            }
        }
    }

    @Test
    fun `a tileset's files come as the bytes that were served`() = runBlocking<Unit> {
        Rig().use { rig ->
            val tile = Recorded["lidar: a tile"]
            rig.serve { Recorded.mock("lidar: a tile") }
            val bytes = rig.client.lidarTilesetFile(key, "points/r0.pnts")
            assertEquals("/api/lidar/tilesets/$key/points/r0.pnts", rig.requests.single().requestUrl!!.encodedPath)
            assertArrayEquals(tile.bytes, bytes)
            assertEquals(
                "86d932ab10f937d01c27040ccc969fba52d69432a99213e84192fad24cf4918b",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            )

            rig.serve { Recorded.mock("lidar: a tileset that is not there") }
            assertEquals(404, assertThrows<ApiException> { rig.client.lidarTilesetFile("0123456789abcdef", "tileset.json") }.status)
        }
    }

    @Test
    fun `a file or key that could reach another route is refused before anything is sent`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setResponseCode(200) }
            for (file in listOf("../../auth/me", "points/../../../auth/me", ".", "..", "./tileset.json", "points/.hidden", "", "points//r0.pnts",
                                "points/r0.pnts?x=1", "a b.pnts", "%2e%2e/auth")) {
                assertThrows<IllegalArgumentException>(file) { rig.client.lidarTilesetFile(key, file) }
            }
            for (bad in listOf("", "029A9F7DE3BB64D6", "029a9f7de3bb64d", "../029a9f7de3bb64", "029a9f7de3bb64d6/x")) {
                assertThrows<IllegalArgumentException>(bad) { rig.client.lidarTilesetFile(bad, "tileset.json") }
                assertThrows<IllegalArgumentException>(bad) { rig.client.lidarBuildStatus(bad, watcher = null, keep = false) }
                assertThrows<IllegalArgumentException>(bad) { rig.client.lidarRelease(bad, watcher = null) }
            }
            assertTrue(rig.requests.isEmpty())
        }
    }

    @Test
    fun `a heightmap is read as 65 by 65 heights, north row first, and no DEM is none`() = runBlocking<Unit> {
        Rig().use { rig ->
            val recorded = Recorded["terrain: heightmap"]
            rig.serve { Recorded.mock("terrain: heightmap") }
            val heights = rig.client.terrainHeightmap(14, 8726, 5043)!!
            assertEquals("/api/terrain/heightmap/14/8726/5043", rig.requests.single().requestUrl!!.encodedPath)
            assertEquals(TERRAIN_SAMPLES * TERRAIN_SAMPLES, heights.size)
            // Little-endian Int16, as the server writes them (terrain_tiles.encode).
            val bytes = recorded.bytes!!
            for (i in heights.indices) assertEquals(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(2 * i), heights[i])
            assertEquals(369, heights.min().toInt())
            assertEquals(377, heights.max().toInt())

            rig.serve { Recorded.mock("terrain: heightmap where no DEM reaches") }
            assertNull(rig.client.terrainHeightmap(12, 100, 100))
        }
    }

    @Test
    fun `heights below the ellipsoid and either end of an Int16 read as themselves`() = runBlocking<Unit> {
        Rig().use { rig ->
            val values = shortArrayOf(-30, 128, 384, 32767, -32768, 0, -1, 255, 256)
            val body = java.nio.ByteBuffer.allocate(8450).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 4225) body.putShort(values[i % values.size])
            rig.serve { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(body.array())) }
            val heights = rig.client.terrainHeightmap(14, 1, 1)!!
            for (i in 0 until 4225) assertEquals(values[i % values.size], heights[i], "height $i")
        }
    }

    @Test
    fun `a heightmap longer than one is refused as it arrives, said or not`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(8451))) }
            assertEquals("too_large", assertThrows<ApiException> { rig.client.terrainHeightmap(14, 1, 1) }.code)
            rig.serve { MockResponse().setResponseCode(200).setChunkedBody(okio.Buffer().write(ByteArray(20_000)), 1024) }
            assertEquals("too_large", assertThrows<ApiException> { rig.client.terrainHeightmap(14, 1, 1) }.code)
            // An error's own page is read for its words, though it is longer than a tile.
            rig.serve { MockResponse().setResponseCode(400).setBody("{\"error\": \"" + "x".repeat(9000) + "\"}") }
            assertEquals(400, assertThrows<ApiException> { rig.client.terrainHeightmap(14, 1, 1) }.status)
        }
    }

    @Test
    fun `heights that are not one of each for every point are refused as unreadable`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"groundM": [1.0], "geoidM": [-30.0, -30.0]}""") }
            assertEquals("unreadable_response", assertThrows<ApiException> { rig.client.terrainHeights(listOf(here, here)) }.code)
            rig.serve { MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"groundM": [1.0, 2.0], "geoidM": [-30.0]}""") }
            assertEquals("unreadable_response", assertThrows<ApiException> { rig.client.terrainHeights(listOf(here, here)) }.code)
        }
    }

    @Test
    fun `a heightmap of the wrong size is refused as unreadable, never read as ground`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(100))) }
            assertEquals("unreadable_response", assertThrows<ApiException> { rig.client.terrainHeightmap(14, 1, 1) }.code)
            assertThrows<IllegalArgumentException> { rig.client.terrainHeightmap(TERRAIN_MAX_LEVEL + 1, 0, 0) }
            assertThrows<IllegalArgumentException> { rig.client.terrainHeightmap(3, -1, 0) }
        }
    }

    @Test
    fun `a heightmap waits while heavy work runs, and a tileset file does not`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { request -> if (request.path!!.startsWith("/api/terrain")) Recorded.mock("terrain: heightmap") else Recorded.mock("lidar: a tile") }
            val heavy = rig.priority.begin()
            val tile = async { rig.client.terrainHeightmap(14, 8726, 5043) }
            val file = async { rig.client.lidarTilesetFile(key, "points/r0.pnts") }
            withTimeout(5_000) { file.await() }
            delay(200)
            assertFalse(tile.isCompleted, "a terrain tile went out while an analysis ran")
            heavy.close()
            withTimeout(5_000) { tile.await() }
        }
    }

    @Test
    fun `heights are asked for in a body and keep their nulls`() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.serve { Recorded.mock("terrain: heights") }
            val points = listOf(here, LatLon(34.6, -84.14), LatLon(35.5, -83.0))
            val answer = rig.client.terrainHeights(points)
            val request = rig.requestsTo("/api/terrain/heights").single()
            noPlaceIn(request)
            assertEquals(3, (body(request)["points"] as kotlinx.serialization.json.JsonArray).size)
            assertEquals(listOf(376.4, 359.17, null), answer.groundM)
            assertEquals(listOf(-30.35, -30.35, -30.35), answer.geoidM)

            assertEquals(TerrainHeightsDto(emptyList(), emptyList()), rig.client.terrainHeights(emptyList()))
            assertEquals(1, rig.requests.size)                                                 // nothing to ask, nothing asked
            assertThrows<IllegalArgumentException> { rig.client.terrainHeights(List(TERRAIN_HEIGHTS_MAX + 1) { here }) }
        }
    }
}
