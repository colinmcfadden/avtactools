package app.ezpztac.network

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The 3D view's LiDAR calls against the **real** routes (`backend/tests/live_server.py`): the lookup, a build through the build service's real
 * queue (in the server's process, a build being the fixture tileset put in place), its progress asked after by key with the watcher and keep in
 * the query, letting go of it, a build nobody waits for being dropped, and the tileset's files. The terrain routes are held to their recordings
 * only: they need DEMs and the analysis blueprint's model. Skipped without `EZPZ_LIVE_PYTHON`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveViewer3dTest {
    private var server: LiveServer? = null

    @BeforeAll
    fun start() {
        assumeTrue(LiveServer.available, "set EZPZ_LIVE_PYTHON to run the live-server tests")
        server = LiveServer.startOrNull(accessTokenSeconds = 600)
    }

    @AfterAll
    fun stop() { server?.close() }

    private fun live(): LiveServer = server ?: error("no live server")

    private suspend fun signedIn(): ApiClient {
        val email = "viewer-${UUID.randomUUID()}@example.com"
        live().makeAccount(email)
        live().clearRateLimits()
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        return ApiClient(live().baseUrl, http, ClientInfo.android("1.4.0", 212), InMemorySessionStore()).also { it.login(email, LiveServer.PASSWORD) }
    }

    @Test
    fun `a point cloud is looked up, built while someone waits, and read file by file`() = runBlocking<Unit> {
        val client = signedIn()
        val here = LatLon(34.591552, -84.128225)
        val watcher = "android-live-${UUID.randomUUID().toString().take(8)}"

        val missing = client.lidarResolve(here)
        assertFalse(missing.available)
        assertEquals(true, missing.canBuild)
        val queued = client.lidarBuild(here, keep = false, watcher = watcher)
        assertEquals(missing.key, queued.key)
        assertEquals("queued", queued.state)
        assertEquals(queued.key, client.lidarBuild(here, keep = false, watcher = watcher).key)       // asked again: the same build
        val waiting = client.lidarBuildStatus(queued.key, watcher = watcher, keep = false)
        assertEquals("waiting", waiting.stage)

        assertEquals(listOf(queued.key), live().runLidarBuilds())
        val done = client.lidarBuildStatus(queued.key, watcher = watcher, keep = false)
        assertEquals("done", done.state)
        assertEquals("/lidar/tilesets/${queued.key}/tileset.json", done.url)
        val found = client.lidarResolve(here)
        assertTrue(found.available)
        assertEquals("/lidar/tilesets/${queued.key}/context/tileset.json", found.contextUrl)

        assertArrayEquals(Fixtures.bytes("viewer3d/tileset/tileset.json"), client.lidarTilesetFile(queued.key, "tileset.json"))
        assertArrayEquals(Fixtures.bytes("viewer3d/tileset/points/r0.pnts"), client.lidarTilesetFile(queued.key, "points/r0.pnts"))
        assertArrayEquals(Fixtures.bytes("viewer3d/tileset/context/points/r1.pnts"), client.lidarTilesetFile(queued.key, "context/points/r1.pnts"))
        assertEquals(404, assertThrows<ApiException> { client.lidarTilesetFile(queued.key, "points/r99.pnts") }.status)
        // Built already: a build is the tileset, at once.
        assertEquals("done", client.lidarBuild(here, keep = false, watcher = watcher).state)
    }

    @Test
    fun `a build let go of by the one who waited is dropped, a saved one is not, and one never had is nothing to let go of`() = runBlocking<Unit> {
        val client = signedIn()
        val watcher = "android-live-${UUID.randomUUID().toString().take(8)}"
        val unsaved = client.lidarBuild(LatLon(34.70, -84.30), keep = false, watcher = watcher)
        val saved = client.lidarBuild(LatLon(34.80, -84.40), keep = true, watcher = watcher)
        client.lidarRelease(unsaved.key, watcher = watcher)
        client.lidarRelease(saved.key, watcher = watcher)
        client.lidarRelease("0123456789abcdef", watcher = watcher)                                    // 204: nothing to read
        // Nobody asks after either for longer than the service's lease.
        live().lidarLater(91)
        assertEquals("cancelled", client.lidarBuildStatus(unsaved.key, watcher = watcher, keep = false).state)
        assertEquals("queued", client.lidarBuildStatus(saved.key, watcher = watcher, keep = true).state)
        val forgotten = assertThrows<ApiException> { client.lidarBuildStatus("fedcba9876543210", watcher = watcher, keep = false) }
        assertEquals(404, forgotten.status)
        assertEquals("no_build", forgotten.code)
        live().runLidarBuilds()                                                                       // the kept one finishes; the next test starts clean
    }
}
