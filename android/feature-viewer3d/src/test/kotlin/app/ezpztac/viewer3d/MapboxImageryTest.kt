package app.ezpztac.viewer3d

import app.ezpztac.scene3d.ImageryTileId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Mapbox's satellite tiles as the 3D view asks for them: the web's URL, the public token, and which failures are worth another try. */
class MapboxImageryTest {
    private val server = MockWebServer().apply { start() }
    private val tile = ImageryTileId(13, 2181, 3256)

    @After
    fun stop() = server.shutdown()

    private fun imagery(token: String? = "pk.test") = MapboxImagery(OkHttpClient(), server.url("").toString().trimEnd('/'), token = { token })

    @Test
    fun `a tile is asked for at the web's address, z then x then y, at 512 points twice over, with the token`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), imagery().tile(tile))
        val asked = server.takeRequest()
        assertEquals("/styles/v1/mapbox/satellite-v9/tiles/512/13/2181/3256@2x?access_token=pk.test", asked.path)
        assertEquals(null, asked.getHeader("Authorization"))
    }

    @Test
    fun `no token, or one that is not public, asks nothing and is refused`() = runBlocking<Unit> {
        for (token in listOf(null, "sk.secret", "")) {
            try {
                imagery(token).tile(tile)
                fail("$token")
            } catch (_: ImageryRefused) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a refusal is for good, while a 429, a server error or no connection is worth another try`() = runBlocking<Unit> {
        for (code in listOf(401, 403, 404)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                imagery().tile(tile)
                fail("$code")
            } catch (_: ImageryRefused) {
            }
        }
        for (code in listOf(429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                imagery().tile(tile)
                fail("$code")
            } catch (_: IOException) {
                // Worth another try: not an ImageryRefused, which is no IOException.
            }
        }
    }

    @Test
    fun `a tile larger than any Mapbox sends is refused, not read whole`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray((MapboxImagery.MAX_BYTES + 1).toInt()))))
        try {
            imagery().tile(tile)
            fail("read")
        } catch (_: ImageryRefused) {
        }
    }
}
