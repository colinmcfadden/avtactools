package app.ezpztac.data

import app.ezpztac.network.NetworkException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The point cloud's files kept on the device: for the server's week, within a budget, never outside their folder, and gone at sign-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedTilesetFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = "0123456789abcdef"

    /** The server: each file's bytes are its path's, every ask written down; [gate] holds an answer until it is completed. */
    private class Server : TilesetFiles {
        val asked = mutableListOf<String>()
        var size = 100
        var failing: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun file(key: String, path: String): ByteArray {
            asked += "$key/$path"
            gate?.await()
            failing?.let { throw it }
            return ByteArray(size) { (path.hashCode() + it).toByte() }
        }
    }

    private var now = 1_000_000_000_000L

    private fun cache(server: Server, dir: File = folder.root, maxBytes: Long = CachedTilesetFiles.MAX_BYTES, maxFile: Long = CachedTilesetFiles.MAX_FILE_BYTES) =
        CachedTilesetFiles(server, dir, maxBytes, maxFile) { now }

    @Test
    fun `a file fetched once is read from the device after, by this process or the next`() = runTest {
        val server = Server()
        val first = cache(server).file(key, "points/r0.pnts")
        assertArrayEquals(first, cache(server).file(key, "points/r0.pnts"))
        val again = cache(server)
        assertArrayEquals(first, again.file(key, "points/r0.pnts"))
        assertArrayEquals(first, again.file(key, "points/r0.pnts"))
        assertEquals(listOf("$key/points/r0.pnts"), server.asked)
        assertTrue(File(folder.root, "$key/points/r0.pnts").isFile)
    }

    @Test
    fun `a file older than the server's week is fetched again`() = runTest {
        val server = Server()
        val cache = cache(server)
        cache.file(key, "tileset.json")
        File(folder.root, "$key/tileset.json").setLastModified(now - CachedTilesetFiles.MAX_AGE_MS + 1_000)
        cache.file(key, "tileset.json")
        assertEquals(1, server.asked.size)
        File(folder.root, "$key/tileset.json").setLastModified(now - CachedTilesetFiles.MAX_AGE_MS)
        cache.file(key, "tileset.json")
        assertEquals(2, server.asked.size)
        // One from a clock set wrong (written in the future) is not trusted either.
        File(folder.root, "$key/tileset.json").setLastModified(now + 60_000)
        cache.file(key, "tileset.json")
        assertEquals(3, server.asked.size)
    }

    @Test
    fun `over its budget it lets go of the files longest unused`() = runTest {
        val server = Server()
        val cache = cache(server, maxBytes = 350)
        for (name in listOf("a", "b", "c")) cache.file(key, "$name.pnts")
        now += 1_000
        cache.file(key, "a.pnts")                                          // used again: b is now the longest unused
        now += 1_000
        cache.file(key, "d.pnts")
        assertEquals(300L, cache.heldBytes)
        assertFalse(File(folder.root, "$key/b.pnts").exists())
        assertTrue(listOf("a", "c", "d").all { File(folder.root, "$key/$it.pnts").isFile })
        val asked = server.asked.size
        cache.file(key, "a.pnts")
        assertEquals(asked, server.asked.size)
        cache.file(key, "b.pnts")
        assertEquals(asked + 1, server.asked.size)
    }

    @Test
    fun `a tileset's folder goes with its last file`() = runTest {
        val server = Server()
        val cache = cache(server, maxBytes = 150)
        cache.file(key, "points/a.pnts")
        now += 1_000
        cache.file("fedcba9876543210", "points/b.pnts")
        assertFalse(File(folder.root, key).exists())
        assertTrue(File(folder.root, "fedcba9876543210/points/b.pnts").isFile)
    }

    @Test
    fun `a file larger than any is kept is fetched each time, and one that fails is not kept`() = runTest {
        val server = Server()
        val cache = cache(server, maxFile = 50)
        cache.file(key, "big.pnts")
        cache.file(key, "big.pnts")
        assertEquals(2, server.asked.size)
        assertFalse(File(folder.root, "$key/big.pnts").exists())
        val failing = Server().apply { failing = NetworkException("no route", requestMayHaveBeenSent = false) }
        val other = cache(failing, dir = folder.newFolder())
        repeat(2) { runCatching { other.file(key, "tileset.json") } }
        assertEquals(2, failing.asked.size)
    }

    @Test
    fun `a path it will not hold is passed to the server as it is, and nothing is written outside its folder`() = runTest {
        val server = Server()
        val dir = File(folder.root, "tiles").apply { mkdirs() }
        val cache = cache(server, dir)
        for ((k, path) in listOf(".." to "outside.pnts", "../escape0123456" to "tileset.json", key to "../outside.pnts", key to ".hidden", key to "a//b.pnts", key to "a.part-1")) {
            cache.file(k, path)
            cache.file(k, path)
        }
        assertEquals(12, server.asked.size)
        assertFalse(File(folder.root, "outside.pnts").exists())
        assertTrue(!dir.exists() || dir.walkTopDown().none { it.isFile })
    }

    @Test
    fun `clearing lets go of every file, and one fetched across the clear is not kept`() = runTest {
        val server = Server()
        val cache = cache(server)
        cache.file(key, "tileset.json")
        cache.file(key, "points/r.pnts")
        server.gate = CompletableDeferred()
        val inFlight = async { cache.file(key, "points/r0.pnts") }
        runCurrent()
        cache.clear()
        server.gate!!.complete(Unit)
        inFlight.await()
        assertEquals(0L, cache.heldBytes)
        assertTrue(!folder.root.exists() || folder.root.walkTopDown().none { it.isFile })
        assertFalse("no folder named for the key", File(folder.root, key).exists())
        server.gate = null
        cache.file(key, "tileset.json")
        assertEquals(4, server.asked.size)
    }

    @Test
    fun `a file half written by a process that ended is not taken for one, and is removed`() = runTest {
        val server = Server()
        val dir = File(folder.root, key).apply { mkdirs() }
        File(dir, "tileset.json.part-1234").writeBytes(ByteArray(10))
        val cache = cache(server)
        cache.file(key, "tileset.json")
        assertEquals(1, server.asked.size)
        assertFalse(File(dir, "tileset.json.part-1234").exists())
        assertEquals(100L, cache.heldBytes)
    }
}
