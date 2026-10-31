package app.ezpztac.data

import app.ezpztac.data.session.AesGcmBox
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/** The sealed file the threat picture is kept in: what is in it is not readable, and what cannot be read is gone. */
class EncryptedThreatVaultTest {
    private lateinit var dir: File
    private lateinit var file: File

    private fun key(seed: Int) = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    private fun box(seed: Int = 1) = AesGcmBox({ key(seed) }, "test-threats")
    private fun vault(seed: Int = 1) = EncryptedThreatVault(file, box(seed))

    private val picture = ThreatPicture(
        1_234_567L,
        listOf(
            ThreatEntry("threat-1-0", Threat("SA-6 Gainful", "SHGPEWRR------", 34.783817, -84.08219, "Seen at 0300", "HUMINT", radars = Radars.defaultPair())),
            ThreatEntry("threat-1-1", Threat("ZSU-23", "SHGPEWAH------", 35.1, -83.9, "", "SOF", showThreat = false, radars = listOf(Radars.default(Radars.ENGAGEMENT))), visible = false),
        ),
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("threats").toFile()
        file = File(dir, "threats.bin")
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `a picture comes back as it went in, after the app is restarted`() {
        vault().save(picture)
        assertEquals(picture, vault().load())                                                 // a new process, the same key
    }

    @Test
    fun `nothing is kept before something is saved`() {
        assertNull(vault().load())
    }

    @Test
    fun `nothing in the file is readable without the key, names and places least of all`() {
        vault().save(picture)
        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("SA-6 Gainful", "Seen at 0300", "HUMINT", "34.783817", "-84.08219", "SHGPEWRR", "threat-1-0")) {
            assertFalse("$secret is in the clear", onDisk.contains(secret))
        }
    }

    @Test
    fun `a file sealed with another key is no picture, and is removed`() {
        vault(seed = 1).save(picture)
        assertNull(vault(seed = 2).load())
        assertFalse(file.exists())
    }

    @Test
    fun `a changed byte is no picture, and is removed`() {
        vault().save(picture)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(vault().load())
        assertFalse(file.exists())
    }

    @Test
    fun `a truncated or empty or foreign file is no picture, and is removed`() {
        vault().save(picture)
        file.writeBytes(file.readBytes().copyOf(10))
        assertNull(vault().load())
        assertFalse(file.exists())
        file.writeBytes(ByteArray(0))
        assertNull(vault().load())
        assertFalse(file.exists())
        file.writeText("{\"entries\": []}")
        assertNull(vault().load())
        assertFalse(file.exists())
    }

    @Test
    fun `sealed text that is not a picture is no picture, and is removed`() {
        file.writeBytes(box().seal("not json at all".encodeToByteArray()))
        assertNull(vault().load())
        assertFalse(file.exists())
        file.writeBytes(box().seal("{\"savedAtMillis\": \"soon\", \"entries\": 3}".encodeToByteArray()))
        assertNull(vault().load())
        assertFalse(file.exists())
    }

    @Test
    fun `a field a later release adds does not stop an older one reading the picture`() {
        val plain = """{"savedAtMillis": 5, "somethingNew": true, "entries": []}"""
        file.writeBytes(box().seal(plain.encodeToByteArray()))
        assertEquals(ThreatPicture(5, emptyList()), vault().load())
    }

    @Test
    fun `saving again replaces the picture whole, and leaves no half-written file`() {
        val v = vault()
        v.save(picture)
        v.save(picture.copy(entries = picture.entries.take(1)))
        assertEquals(1, vault().load()!!.entries.size)
        assertFalse(File(dir, "threats.bin.tmp").exists())
    }

    @Test
    fun `a wipe removes the file and any half-written one, and is fine with nothing to remove`() {
        val v = vault()
        v.save(picture)
        File(dir, "threats.bin.tmp").writeBytes(byteArrayOf(1, 2, 3))
        v.wipe()
        assertFalse(file.exists())
        assertFalse(File(dir, "threats.bin.tmp").exists())
        v.wipe()                                                                              // nothing there: no complaint
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
