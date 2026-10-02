package app.ezpztac.data.session

import app.ezpztac.network.ApiUser
import app.ezpztac.network.StoredSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

class EncryptedSessionStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    private fun box(seed: Int = 1) = AesGcmBox({ key(seed) }, "test-purpose")

    private fun user() = ApiUser(
        id = 7, email = "pilot@example.com", name = "Test Pilot", role = "user", isAdmin = false, isActive = true,
        features = mapOf("threats" to true), accessOk = true,
    )

    private fun session(access: String = "ACCESS-TOKEN-123", refresh: String? = "REFRESH-TOKEN-456") =
        StoredSession(access, refresh, refreshExpiresAtEpochSeconds = 4_000_000_000, user = user())

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("session").toFile()
        file = File(dir, "session.bin")
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    // -- What is kept ---------------------------------------------------------------------------

    @Test
    fun `a session comes back as it went in, after the app is restarted`() = runBlocking<Unit> {
        EncryptedSessionStore(file, box()).write(session())
        val reopened = EncryptedSessionStore(file, box())                                    // a new process, the same key
        assertEquals(session(), reopened.read())
    }

    @Test
    fun `nothing in the file is readable without the key, the tokens least of all`() = runBlocking<Unit> {
        EncryptedSessionStore(file, box()).write(session())
        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("ACCESS-TOKEN-123", "REFRESH-TOKEN-456", "pilot@example.com", "Test Pilot")) {
            assertFalse("$secret is in the file in the clear", onDisk.contains(secret))
        }
    }

    @Test
    fun `no session before anything was written`() = runBlocking<Unit> {
        assertNull(EncryptedSessionStore(file, box()).read())
    }

    @Test
    fun `a write replaces the last, and leaves nothing else behind`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        store.write(session(access = "one", refresh = "r1"))
        store.write(session(access = "two", refresh = "r2"))
        assertEquals("two", store.read()!!.accessToken)
        assertEquals("r2", store.read()!!.refreshToken)
        assertEquals(listOf("session.bin"), dir.list()!!.toList())                           // no temporary file left over
    }

    @Test
    fun `a session with no refresh token keeps having none`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        store.write(session(refresh = null))
        assertNull(store.read()!!.refreshToken)
    }

    @Test
    fun `clearing removes the session, and the next read finds none`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        store.write(session())
        store.clear()
        assertFalse(file.exists())
        assertNull(store.read())
        store.clear()                                                                        // clearing nothing is fine
    }

    @Test
    fun `an interrupted write is cleaned up by clearing`() = runBlocking<Unit> {
        File(dir, "session.bin.tmp").writeBytes(byteArrayOf(1, 2, 3))
        EncryptedSessionStore(file, box()).clear()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `writes made at once leave one whole session, never a mixture`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        (1..30).map { n -> async { store.write(session(access = "access-$n", refresh = "refresh-$n")) } }.awaitAll()
        val read = store.read()!!
        assertEquals(read.accessToken.removePrefix("access-"), read.refreshToken!!.removePrefix("refresh-"))
    }

    // -- What cannot be trusted is not a session -------------------------------------------------

    @Test
    fun `a key the OS lost means signed out, not a crash, and the useless file is removed`() = runBlocking<Unit> {
        EncryptedSessionStore(file, box(seed = 1)).write(session())
        val store = EncryptedSessionStore(file, box(seed = 2))                               // a different key now
        assertNull(store.read())
        assertFalse("the file that can never be opened is removed", file.exists())
    }

    @Test
    fun `a changed byte means signed out`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        store.write(session())
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertNull(store.read())
        assertFalse(file.exists())
    }

    @Test
    fun `a truncated file, an empty one and rubbish all mean signed out`() = runBlocking<Unit> {
        val store = EncryptedSessionStore(file, box())
        store.write(session())
        val whole = file.readBytes()
        for (bad in listOf(whole.copyOf(whole.size - 5), whole.copyOf(10), ByteArray(0), ByteArray(64) { 7 })) {
            file.writeBytes(bad)
            assertNull("${bad.size} bytes", store.read())
            assertFalse(file.exists())
        }
    }

    @Test
    fun `a file sealed for another purpose is not a session`() = runBlocking<Unit> {
        file.writeBytes(AesGcmBox({ key(1) }, "something-else").seal(session().let { """{"x":1}""".encodeToByteArray() }))
        assertNull(EncryptedSessionStore(file, box()).read())
    }

    @Test
    fun `sealed bytes that are not a session are discarded`() = runBlocking<Unit> {
        file.writeBytes(box().seal("this is not json".encodeToByteArray()))
        assertNull(EncryptedSessionStore(file, box()).read())
        assertFalse(file.exists())
    }

    // -- The box -------------------------------------------------------------------------------------

    @Test
    fun `the same bytes seal differently every time`() {
        val a = box().seal("same".encodeToByteArray())
        val b = box().seal("same".encodeToByteArray())
        assertNotEquals(a.toList(), b.toList())                                              // a fresh IV each time
        assertArrayEquals("same".encodeToByteArray(), box().open(a))
        assertArrayEquals("same".encodeToByteArray(), box().open(b))
    }

    @Test
    fun `opening what was sealed for another purpose fails`() {
        val sealed = AesGcmBox({ key(1) }, "purpose-a").seal("x".encodeToByteArray())
        assertThrows(GeneralSecurityException::class.java) { AesGcmBox({ key(1) }, "purpose-b").open(sealed) }
    }

    @Test
    fun `a file from a format this app does not know is refused`() {
        val sealed = box().seal("x".encodeToByteArray())
        sealed[0] = 2
        assertThrows(GeneralSecurityException::class.java) { box().open(sealed) }
        assertTrue(true)
    }
}
