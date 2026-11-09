package app.ezpztac.map

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** What counts as a chart tile failing, at the requests MapLibre makes. */
class ChartTileWatchTest {
    private val server = MockWebServer()
    private val watch = ChartTileWatch()
    private val client = OkHttpClient.Builder().addInterceptor(watch).build()

    @After
    fun stop() = server.shutdown()

    private fun call(path: String) = client.newCall(Request.Builder().url(server.url(path)).build())

    private fun failuresDuring(block: () -> Unit): Int = runBlocking {
        val count = AtomicInteger()
        val listening = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { watch.failed.collect { count.incrementAndGet() } }
        block()
        listening.cancelAndJoin()
        count.get()
    }

    private fun answered(code: Int, path: String = "/charts/vfr/b/5/1/1.webp") = failuresDuring {
        server.enqueue(MockResponse().setResponseCode(code))
        call(path).execute().close()
    }

    @Test
    fun `a build gone, a server fault and a refusal are failures`() {
        assertEquals(1, answered(404))
        assertEquals(1, answered(500))
        assertEquals(1, answered(403))
    }

    @Test
    fun `a tile that came, or had not changed, is none`() {
        assertEquals(0, answered(200))
        assertEquals(0, answered(304))
    }

    @Test
    fun `a tile from ArcGIS never asks for the config again`() {
        assertEquals(0, answered(404, "/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/VFR_Sectional/MapServer/tile/9/1/1"))
        assertEquals(0, answered(500, "/api/config"))
    }

    @Test
    fun `no network is a failure, and the request still fails as it would have`() {
        val failures = failuresDuring {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertThrows(IOException::class.java) { call("/charts/vfr/b/5/1/1.webp").execute() }
        }
        assertEquals(1, failures)
    }

    @Test
    fun `a tile the map gave up on as it moved is none`() {
        val failures = failuresDuring {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val tile = call("/charts/vfr/b/5/1/1.webp")
            val waiting = Thread { assertThrows(IOException::class.java) { tile.execute() } }
            waiting.start()
            server.takeRequest()
            tile.cancel()
            waiting.join()
        }
        assertEquals(0, failures)
    }
}
